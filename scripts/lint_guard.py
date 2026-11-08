"""Compare complete Android lint reports against a released-source, shrinking budget.

No lint issue is suppressed. New errors, increased counts/severity, changed error
details and returns of retired findings fail. Reports and source snippets stay
private; the committed baseline stores only relative paths and fingerprints.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

LEVEL = {"Ignore": 0, "Informational": 1, "Information": 1, "Hint": 1, "Warning": 2, "Error": 3, "Fatal": 4}
BASELINE = "automation/lint-baseline.json"
DECLARATION = re.compile(r"^\s*(?:(?:public|private|internal|protected|open|override|suspend|abstract|data|sealed|enum|inline|tailrec|actual|expect|external|infix|operator|final)\s+)*(?:fun|class|interface|object)\s+[^({=]+")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root, text=True, encoding="utf-8").strip()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def findings(report, project):
    project = project.resolve()
    tracked = set(git(project, "ls-files").replace("\\", "/").splitlines())
    xml = ET.parse(report).getroot()
    if xml.tag != "issues":
        raise ValueError("Not an Android lint report")
    result = {}
    ignored_local = 0
    for issue in xml.findall("issue"):
        severity = issue.get("severity")
        if severity not in LEVEL:
            raise ValueError("Unknown lint severity")
        locations = issue.findall("location")
        paths = []
        for location in locations:
            source = Path(location.get("file", ""))
            if not source.is_absolute(): source = project / source
            try: relative = source.resolve().relative_to(project).as_posix()
            except ValueError: relative = "<external>/" + source.name
            paths.append(relative)
        if paths and paths[0] not in tracked and not paths[0].startswith("<external>/"):
            # Ignored local.properties/generated files are not released source.
            # A clean committed tree is required by the caller, so untracked app
            # source cannot be hidden through this filter.
            ignored_local += 1
            continue
        message = issue.get("message", "").replace(str(project), "<project>").replace(project.as_posix(), "<project>")
        message = message.replace("\\", "/")
        source_line = issue.get("errorLine1", "").strip()
        context = []
        owner = ""
        if paths and paths[0] in tracked:
            lines = (project / paths[0]).read_text(encoding="utf-8-sig", errors="replace").splitlines()
            index = int(locations[0].get("line", "1")) - 1
            context = [line.strip() for line in lines[max(0, index-2):index+3]]
            declarations = [m.group(0).strip() for line in lines[:index+1]
                            for m in [DECLARATION.search(line)] if m]
            owner = declarations[-1] if declarations else ""
        kind = issue.get("id", "")
        translations = re.findall(r'"([^"]+)"', message) if kind == "MissingTranslation" else []
        if translations:
            # Adding a missing language is worsening; fixing one shrinks this set.
            anchor = translations[0]
            details = {"missing_locales": sorted(set(translations[1:]))}
        else:
            anchor = digest([source_line, owner, context])
            details = {"message": digest(message)}
        key = digest([kind, paths, anchor])
        entry = {"id": kind, "paths": paths, "severity": LEVEL[severity], "details": details,
                 "count": 1, "levels": {str(LEVEL[severity]): 1}}
        if key in result:
            existing = result[key]
            if existing["details"] != details:
                # Preserve distinct messages at an otherwise identical location.
                key = digest([key, details])
        if key in result:
            existing = result[key]
            existing["count"] += 1
            existing["severity"] = max(existing["severity"], LEVEL[severity])
            level = str(LEVEL[severity])
            existing["levels"][level] = existing["levels"].get(level, 0) + 1
        else: result[key] = entry
    return result, ignored_local


def compatible_details(current, allowed):
    if "missing_locales" in current and "missing_locales" in allowed:
        return set(current["missing_locales"]) <= set(allowed["missing_locales"])
    return current == allowed


def increased_levels(current, allowed):
    return any(sum(n for level,n in current["levels"].items() if int(level) >= threshold) >
               sum(n for level,n in allowed["levels"].items() if int(level) >= threshold)
               for threshold in range(1, 5))


def compare(current, baseline):
    failures = []
    new_warnings = 0
    for key, finding in current.items():
        allowed = baseline["active"].get(key)
        if allowed is None:
            if finding["severity"] >= 3:
                failures.append({"kind": "returned" if key in baseline["seed"] else "new", "fingerprint": key,
                                 "id": finding["id"], "paths": finding["paths"]})
            else: new_warnings += finding["count"]
            continue
        worsened = (finding["severity"] > allowed["severity"] or finding["count"] > allowed["count"] or
                    increased_levels(finding, allowed) or
                    not compatible_details(finding["details"], allowed["details"]))
        if worsened:
            failures.append({"kind": "worsened", "fingerprint": key, "id": finding["id"], "paths": finding["paths"]})
    return {"passed": not failures, "failures": failures, "new_warnings": new_warnings,
            "errors": sum(x["count"] for x in current.values() if x["severity"] >= 3),
            "findings": sum(x["count"] for x in current.values())}


def verified_version_advice(current, baseline, report, released_report):
    """Opt-in proposal: distinguish remote version advice from source/severity/count changes.

    The immutable released XML proves the exact old message. No seed or allowance
    changes; only the suggested version may vary on an existing warning at the
    same source fingerprint. The complete XML and explicit drift list stay visible.
    """
    if hashlib.sha256(released_report.read_bytes()).hexdigest() != baseline["origin"]["report_sha256"]:
        raise ValueError("Released lint report does not match immutable provenance")
    ids = {"GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion"}
    pattern = re.compile(r"^A newer version of (\S+) than (\S+) is available: ([0-9][0-9A-Za-z.+_-]*)$")
    def messages(path):
        xml = ET.parse(path).getroot()
        if xml.tag != "issues": raise ValueError("Not an Android lint report")
        return {digest(i.get("message", "")): i.get("message", "") for i in xml.findall("issue")
                if i.get("id") in ids and i.get("severity") == "Warning"}
    old_messages, new_messages = messages(released_report), messages(report)
    adjusted = json.loads(json.dumps(current))
    changes = []
    for key, finding in adjusted.items():
        allowed = baseline["active"].get(key)
        if not allowed or finding["id"] not in ids or finding["severity"] != LEVEL["Warning"]:
            continue
        if allowed["severity"] != LEVEL["Warning"]:
            continue
        old_hash = allowed["details"].get("message")
        new_hash = finding["details"].get("message")
        if old_hash == new_hash: continue
        # A retired or otherwise different seed record cannot obtain an exemption.
        seed = baseline["seed"].get(key, {})
        if seed.get("details", {}).get("message") != old_hash: continue
        old = pattern.fullmatch(old_messages.get(old_hash, ""))
        new = pattern.fullmatch(new_messages.get(new_hash, ""))
        if not old or not new or old.groups()[:2] != new.groups()[:2]: continue
        finding["details"] = json.loads(json.dumps(allowed["details"]))
        changes.append({"id": finding["id"], "paths": finding["paths"],
                        "artifact": new[1], "declared": new[2],
                        "previous_available": old[3], "available": new[3]})
    return adjusted, changes


def ratchet(current, baseline):
    verdict = compare(current, baseline)
    if not verdict["passed"]: raise ValueError("Cannot ratchet a failing lint report")
    updated = json.loads(json.dumps(baseline))
    updated["active"] = {key: {"count": min(old["count"], current[key]["count"]),
                              "severity": min(old["severity"], current[key]["severity"]),
                              "levels": current[key]["levels"],
                              "details": current[key]["details"]}
                         for key, old in baseline["active"].items() if key in current}
    return updated


def monotonic(current, previous):
    if current["origin"] != previous["origin"] or current["seed"] != previous["seed"]:
        raise ValueError("Released-source lint seed/provenance is immutable")
    for key, allowance in current["active"].items():
        old = previous["active"].get(key)
        if (old is None or allowance["count"] > old["count"] or allowance["severity"] > old["severity"] or
                increased_levels(allowance, old) or
                not compatible_details(allowance["details"], old["details"])):
            raise ValueError("Lint allowance expanded or a retired finding was restored")


def check_history(project, baseline, previous_ref=None):
    revisions = git(project, "log", "-2", "--format=%H", "--", BASELINE).splitlines()
    if not revisions: raise ValueError("Commit the captured baseline before validation")
    # Also check against the current committed copy if a ratchet is being prepared.
    committed = json.loads(git(project, "show", f"HEAD:{BASELINE}"))
    monotonic(baseline, committed)
    if len(revisions) > 1:
        previous = json.loads(git(project, "show", f"{revisions[1]}:{BASELINE}"))
        monotonic(baseline, previous)
    if previous_ref:
        git(project, "rev-parse", f"{previous_ref}^{{commit}}")
        previous = subprocess.run(["git", "show", f"{previous_ref}:{BASELINE}"], cwd=project,
                                  text=True, capture_output=True, encoding="utf-8")
        if previous.returncode == 0:
            # The run's fetched base remains trusted even if a candidate branch
            # has several intermediate edits to its budget.
            monotonic(baseline, json.loads(previous.stdout))
        elif git(project, "log", "-1", "--format=%H", previous_ref, "--", BASELINE):
            raise ValueError("Cannot drop a previously established lint baseline")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("capture", "check", "ratchet"))
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--release-tag")
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--previous-ref")
    parser.add_argument("--released-report", type=Path,
                        help="Opt-in verified external version-advisory comparison; complete warnings remain visible")
    args = parser.parse_args()
    current, ignored = findings(args.report, args.project)
    advice = []
    if args.action == "capture":
        if not args.release_tag or not re.fullmatch(r"v\d+\.\d+\.\d+-beta\.\d+(?:\.\d+)?", args.release_tag):
            raise ValueError("An explicit released ReSeq tag is required")
        commit = git(args.project, "rev-parse", "HEAD")
        if commit != git(args.project, "rev-parse", f"{args.release_tag}^{{commit}}") or git(args.project, "status", "--porcelain"):
            raise ValueError("Capture requires unchanged tracked source at the released tag")
        if args.baseline.exists(): raise ValueError("Never overwrite or recapture the released lint seed")
        baseline = {"schema": 1, "origin": {"tag": args.release_tag, "commit": commit,
                    "tree": git(args.project, "rev-parse", "HEAD^{tree}"),
                    "report_sha256": hashlib.sha256(args.report.read_bytes()).hexdigest()},
                    "seed": current, "active": {k: {field:v[field] for field in ("severity", "count", "levels", "details")} for k,v in current.items()}}
        write(args.baseline, baseline)
        verdict = compare(current, baseline)
    else:
        baseline = json.loads(args.baseline.read_text())
        check_history(args.project, baseline, args.previous_ref)
        if args.released_report:
            current, advice = verified_version_advice(current, baseline, args.report, args.released_report)
        verdict = compare(current, baseline)
        if args.action == "ratchet": write(args.baseline, ratchet(current, baseline))
    verdict["ignored_non_source_findings"] = ignored
    if advice: verdict["external_version_advice"] = advice
    if args.summary: write(args.summary, verdict)
    print(json.dumps({k:v for k,v in verdict.items() if k != "failures"}))
    if not verdict["passed"]:
        print(f"Lint gate failed: {len(verdict['failures'])} new, worsened or returned findings; inspect the private summary.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try: sys.exit(main())
    except (ValueError, ET.ParseError) as error:
        print(str(error), file=sys.stderr); sys.exit(1)
