"""Local Codex upstream-sync coordinator. Semantic review is performed by Codex.

Never merges, cherry-picks, installs APKs or changes power settings implicitly.
Publication uses the existing signed ReSeq workflow after exact-commit validation.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

REPO = "abbaskip/Nuvio-ReSeq"
ORIGIN = "https://github.com/abbaskip/Nuvio-ReSeq.git"
UPSTREAM = "https://github.com/NuvioMedia/NuvioTV.git"
UPSTREAM_REF = "refs/remotes/nuvio-upstream/dev"
LEDGER = "automation/upstream-ledger.json"
STATUSES = ("integrated", "equivalent", "irrelevant", "held")
GIT_ACTIONS = {"show", "diff", "log", "status", "cherry", "rev-parse", "merge-base",
               "cherry-pick", "merge", "add", "commit", "ls-files", "diff-tree", "rev-list"}
MIN_BUILD_FREE = 6 * 1024**3


def command(*args, cwd=None):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="Never", GH_PROMPT_DISABLED="1")
    result = subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True,
                            encoding="utf-8", errors="replace", timeout=300)
    if result.returncode:
        # Commands here contain no secret arguments. Do not echo build output/config.
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]} {args[1] if len(args) > 1 else ''}")
    return result.stdout.strip()


def git(*args, cwd=None):
    return command("git", *args, cwd=cwd)


def api(endpoint):
    return json.loads(command("gh", "api", endpoint))


def ancestor(a, b, cwd=None):
    return subprocess.run(["git", "merge-base", "--is-ancestor", a, b], cwd=cwd, capture_output=True).returncode == 0


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def root_dir():
    common = Path(git("rev-parse", "--git-common-dir")).resolve()
    if common.name != ".git":
        raise RuntimeError("Expected a normal shared .git directory")
    return common.parent


def active_releases(head=None):
    runs = api(f"repos/{REPO}/actions/workflows/reseq-release.yml/runs?per_page=30")["workflow_runs"]
    return [r["id"] for r in runs if r["status"] != "completed" and
            (head is None or r.get("head_sha") == head)]


def choose_base(dev, released):
    if ancestor(dev, released):
        return released
    if ancestor(released, dev):
        return dev
    raise RuntimeError("Development and latest published release diverged; compare before choosing a base")


def upstream_inventory(base, upstream, cwd, ledger):
    cherry = {line[2:]: line[0] for line in git("cherry", base, upstream, cwd=cwd).splitlines()}
    commits = []
    # Merge resolutions can introduce functional changes absent from both sides.
    # They need review records too, even though git cherry only compares patches.
    for sha in git("rev-list", "--reverse", "--topo-order", f"{base}..{upstream}", cwd=cwd).splitlines():
        parents = git("show", "-s", "--format=%P", sha, cwd=cwd).split()
        is_merge = len(parents) > 1
        diff_args = ["diff-tree", "--no-commit-id", "--name-only", "-r"]
        if is_merge: diff_args.append("--cc")
        files = git(*diff_args, sha, cwd=cwd).splitlines()
        commits.append({"sha": sha, "subject": git("show", "-s", "--format=%s", sha, cwd=cwd),
                        "is_merge": is_merge, "patch_equivalent": cherry.get(sha) == "-",
                        "previous_review": ledger["commits"].get(sha), "files": files})
    return commits


def pending_candidate(state, root, base):
    """A review branch can contain integrated changes that dev has never received."""
    path = state / "pending-integration.json"
    if not path.is_file():
        return None
    pending = json.loads(path.read_text())
    if not re.fullmatch(r"automation/upstream-\d{8}T\d{6}Z-[a-f0-9]{6}", pending["branch"]):
        raise RuntimeError("Invalid preserved integration branch")
    git("fetch", "--no-tags", ORIGIN, "refs/heads/" + pending["branch"], cwd=root)
    if git("rev-parse", "FETCH_HEAD", cwd=root) != pending["head"]:
        raise RuntimeError("Preserved branch moved; reconcile it against the saved ledger before continuing")
    return None if ancestor(pending["head"], base, cwd=root) else pending


def require_ledger_continuity(previous, current):
    missing = previous["commits"].keys() - current["commits"].keys()
    if missing:
        raise RuntimeError(f"Candidate lost {len(missing)} historical review records; reconcile from the preserved branch")


def begin(args, root, state):
    if not (state / "review-ledger.json").is_file():
        raise RuntimeError("Missing shared review ledger; recover durable state before starting")
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6]
    lock = state / "lock.json"
    # No timeout/lease stealing: an interrupted run must be explicitly recovered.
    with lock.open("x", encoding="utf-8") as stream:
        json.dump({"run": run_id, "started_utc": datetime.now(timezone.utc).isoformat()}, stream)
    try:
        retry_pending_cleanup(root, state)
        if shutil.disk_usage(root).free < MIN_BUILD_FREE:
            raise RuntimeError("Less than 6 GiB free; preserve the build reserve before starting another run")
        if active_releases():
            raise RuntimeError("An existing release workflow is active; defer this run")
        # Explicit URLs prevent accidentally monitoring the renamed fork as upstream.
        git("fetch", "--no-tags", ORIGIN, "+refs/heads/dev:refs/remotes/origin/dev", cwd=root)
        git("fetch", "--no-tags", UPSTREAM, f"+refs/heads/dev:{UPSTREAM_REF}", cwd=root)
        latest = api(f"repos/{REPO}/releases/latest")
        git("fetch", ORIGIN, f"refs/tags/{latest['tag_name']}:refs/tags/{latest['tag_name']}", cwd=root)
        released = git("rev-parse", f"{latest['tag_name']}^{{commit}}", cwd=root)
        dev = git("rev-parse", "refs/remotes/origin/dev", cwd=root)
        base = choose_base(dev, released)
        pending = pending_candidate(state, root, base)
        start = pending["head"] if pending else base
        upstream = git("rev-parse", UPSTREAM_REF, cwd=root)
        branch = f"automation/upstream-{run_id}"
        worktree = root / ".worktrees" / f"upstream-{run_id}"
        if args.setup_worktree:
            if not args.dry_run:
                raise RuntimeError("An existing setup worktree may only be used in dry-run mode")
            worktree = Path(git("rev-parse", "--show-toplevel")).resolve()
            branch = git("branch", "--show-current")
            if worktree == root or not branch or not ancestor(base, "HEAD") or git("status", "--porcelain"):
                raise RuntimeError("Setup rehearsal requires a clean isolated descendant worktree")
        else:
            git("worktree", "add", "-b", branch, str(worktree), start, cwd=root)
        ledger_path = worktree / LEDGER
        ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {"schema": 1, "commits": {}}
        # Review-only and held runs need durable decisions even when dev is unchanged.
        shared = state / "review-ledger.json"
        if shared.exists():
            previous = json.loads(shared.read_text())
            for sha, decision in previous["commits"].items():
                if sha not in ledger["commits"] and decision["outcome"] != "integrated":
                    ledger["commits"][sha] = decision
            require_ledger_continuity(previous, ledger)
            if ledger["commits"]:
                save(ledger_path, ledger)
        commits = upstream_inventory(base, upstream, root, ledger)
        run = {"schema": 1, "id": run_id, "dry_run": args.dry_run, "branch": branch,
               "worktree": str(worktree), "dev": dev, "base": base, "upstream": upstream,
               "latest_tag": latest["tag_name"], "commits": commits, "validation": None,
               "compatibility": None, "device": None, "publication": None,
               "pending_parent": pending}
        save(state / "runs" / f"{run_id}.json", run)
        print(json.dumps({"run": run_id, "worktree": str(worktree), "base": base, "dev": dev,
                          "latest_tag": latest["tag_name"], "upstream": upstream,
                          "pending_parent": pending, "merge_current_base_before_validation": not ancestor(base, start, cwd=root),
                          "unreviewed": sum(not c["previous_review"] for c in commits),
                          "previously_held": sum(bool(c["previous_review"] and c["previous_review"]["outcome"] == "held") for c in commits),
                          "inventory": str(state / "runs" / f"{run_id}.json")}, indent=2))
    except Exception:
        # Preserve the lock once a run manifest exists, otherwise release preflight failure.
        if not (state / "runs" / f"{run_id}.json").exists():
            lock.unlink()
        raise


def load_run(state, run_id):
    if not re.fullmatch(r"[0-9]{8}T[0-9]{6}Z-[a-f0-9]{6}", run_id):
        raise RuntimeError("Invalid run id")
    if json.loads((state / "lock.json").read_text())["run"] != run_id:
        raise RuntimeError("Run does not own the shared lock")
    return json.loads((state / "runs" / f"{run_id}.json").read_text())


def review(run, args):
    if args.commit not in {c["sha"] for c in run["commits"]}:
        raise RuntimeError("Commit is not in this run's inventory")
    path = Path(run["worktree"]) / LEDGER
    ledger = json.loads(path.read_text()) if path.exists() else {"schema": 1, "commits": {}}
    previous = ledger["commits"].get(args.commit, {})
    protected = {key:previous[key] for key in ("requires_decision", "decision_context", "raw_upstream", "accepted_adaptation",
                                                  "historical_review_before_reconciliation") if key in previous}
    if previous.get("requires_decision") and args.outcome in ("integrated", "equivalent"):
        raise RuntimeError("This functional hold requires the maintainer's design decision; lint approval does not clear it")
    ledger["commits"][args.commit] = {"outcome": args.outcome, "reason": args.reason,
                                      "run": run["id"], "base": run["base"], **protected}
    save(path, ledger)
    # Any decision change requires fresh compatibility and exact-commit validation.
    run["validation"] = run["compatibility"] = run["device"] = None


def configure(run, args, root):
    source = Path(args.properties).resolve()
    if not source.is_relative_to(root) or not source.is_file():
        raise RuntimeError("Approved configuration must be an existing file in this project")
    # Preserve Java properties escaping; never propagate TMDB or signing material.
    lines = [line for line in source.read_text(encoding="utf-8-sig").splitlines()
             if "=" in line and not line.lstrip().startswith(("#", "!")) and
             not re.match(r"\s*(?:TMDB|NUVIO_RELEASE_|sdk\.dir)", line, re.I)]
    worktree = Path(run["worktree"])
    (worktree / "local.dev.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if args.sdk:
        lines.append("sdk.dir=" + Path(args.sdk).resolve().as_posix().replace(":", r"\:"))
    (worktree / "local.properties").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("Project service settings prepared; TMDB and signing properties excluded; values withheld.")


def worktree_git(run, argv):
    if argv and argv[0] == "--": argv = argv[1:]
    if not argv or argv[0] not in GIT_ACTIONS:
        raise RuntimeError("Unsupported worktree Git action; use guarded publication for pushes")
    if any(a in ("--git-dir", "--work-tree", "-C", "--force", "-f") or
           a.startswith(("--git-dir=", "--work-tree=")) for a in argv):
        raise RuntimeError("Git directory overrides and forced operations are forbidden")
    print(git(*argv, cwd=run["worktree"]))


def probe_device(run):
    raise RuntimeError("Physical device access is disabled for upstream sync. Use host tests or isolated emulators.")


def validation(run):
    worktree = Path(run["worktree"])
    if not ancestor(run["base"], "HEAD", cwd=worktree):
        raise RuntimeError("Merge the current development/release base into the preserved candidate before testing")
    if git("status", "--porcelain", cwd=worktree):
        raise RuntimeError("Commit reviewed changes/version/notes/ledger before exact-commit validation")
    run["validation"] = None
    head = git("rev-parse", "HEAD", cwd=worktree)
    output = worktree / "build" / "upstream-sync"
    output.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(worktree).free < MIN_BUILD_FREE:
        raise RuntimeError("Validation requires 6 GiB free for build outputs and a safety reserve")
    gradle = str(worktree / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    lint_report = worktree / "app/build/reports/lint-results-fullDebug.xml"
    released_lint = root_dir() / ".upstream-sync/evidence/lint-reference-beta7.1/lint-results-fullDebug.xml"
    lint_report.unlink(missing_ok=True)
    commands = [[sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"],
                [gradle,
                 ":app:testFullDebugUnitTest", ":app:assembleFullDebug", ":app:lintFullDebug",
                 "--init-script", "scripts/ci.gradle",
                 "-PuseLocalFfmpegDecoder=false", "-Pkotlin.compiler.execution.strategy=in-process",
                 "--max-workers=2", "--console=plain"],
                [sys.executable, "scripts/lint_guard.py", "check", "--project", ".",
                 "--report", str(lint_report), "--baseline", "automation/lint-baseline.json",
                 "--previous-ref", run["base"], "--released-report", str(released_lint),
                 "--summary", str(output / "lint-verdict.json")],
                [sys.executable, "scripts/verify_apk_services.py", "--properties", "local.properties",
                 "--apk-dir", "app/build/outputs/apk/full/debug", "--report", str(output / "apk-services.json")],
                [sys.executable, "scripts/lint_guard.py", "ratchet", "--project", ".", "--report", str(lint_report),
                 "--baseline", "automation/lint-baseline.json", "--previous-ref", run["base"],
                 "--released-report", str(released_lint)]]
    env = dict(os.environ, PYTHONPATH="scripts")
    for index, argv in enumerate(commands):
        log = output / f"validation-{index}.log"
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run(argv, cwd=worktree, env=env, stdout=stream, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Validation failed; inspect {log}; no publication allowed")
    git("diff", "--check", cwd=worktree)
    suites = [ET.parse(p).getroot() for p in (worktree / "app/build/test-results/testFullDebugUnitTest").glob("TEST-*.xml")]
    totals = {key: sum(int(s.get(key, 0)) for s in suites) for key in ("tests", "failures", "errors", "skipped")}
    if not totals["tests"] or totals["failures"] or totals["errors"]:
        raise RuntimeError("No passing regression results")
    if "automation/lint-baseline.json" in git("diff", "--name-only", cwd=worktree).splitlines():
        raise RuntimeError("Lint improved: commit the automatically reduced budget, then repeat exact-commit validation")
    if head != git("rev-parse", "HEAD", cwd=worktree) or git("status", "--porcelain", cwd=worktree):
        raise RuntimeError("Source changed during validation")
    run["validation"] = {"head": head, "result": "passed", "unit_tests": totals,
                         "lint": json.loads((output / "lint-verdict.json").read_text()),
                         "checks": ["Python tests", "fullDebug unit suite", "debug APKs",
                                    "complete lint report with shrinking released-source budget", "packaged services", "diff check"]}
    print(json.dumps(run["validation"], indent=2))


def attest(run, args):
    worktree = Path(run["worktree"])
    report = Path(args.report).resolve()
    if not report.is_file() or not report.read_text(encoding="utf-8").strip():
        raise RuntimeError("A substantive compatibility/device report is required")
    if not run["validation"] or run["validation"]["head"] != git("rev-parse", "HEAD", cwd=worktree):
        raise RuntimeError("Validate this exact commit before attestation")
    run["compatibility"] = {"head": run["validation"]["head"], "report": str(report),
                            "sha256": hashlib.sha256(report.read_bytes()).hexdigest(), "preserved": True}
    run["device"] = {"status": args.device, "reason": args.reason}


def enforce_held_changes(run, ledger, head):
    held = {c["sha"] for c in run["commits"] if ledger[c["sha"]]["outcome"] == "held" or
            ledger[c["sha"]].get("requires_decision") or
            ledger[c["sha"]].get("raw_upstream", {}).get("outcome") == "held"}
    if not held: return
    worktree = run["worktree"]
    for sha in held:
        if ancestor(sha, head, cwd=worktree):
            raise RuntimeError("A held change is included in release ancestry")
    messages = git("log", "--format=%B", f"{run['base']}..{head}", cwd=worktree)
    for sha in held:
        if re.search(r"(?:cherry picked from commit\s+|Upstream-Commit:\s*)" + re.escape(sha), messages, re.I):
            raise RuntimeError("A held change was cherry-picked or adapted without clearing its decision")
    equivalents = {line[2:] for line in git("cherry", head, run["upstream"], cwd=worktree).splitlines() if line.startswith("- ")}
    blocked = held & equivalents
    for sha in blocked.copy():
        decision = ledger[sha]
        adaptation = decision.get("accepted_adaptation") or {}
        # An accepted shipped adaptation and the raw-upstream hold are separate.
        # Approval never permits a new ancestor, cherry-pick or modified protected implementation.
        if (decision["outcome"] != "integrated" or decision.get("requires_decision") or
                decision.get("raw_upstream", {}).get("outcome") != "held"):
            continue
        release = adaptation.get("release_commit")
        blobs = adaptation.get("protected_blobs") or {}
        if not release or not blobs or not ancestor(release, run["base"], cwd=worktree):
            continue
        prior = {line[2:] for line in git("cherry", run["base"], run["upstream"], cwd=worktree).splitlines()
                 if line.startswith("- ")}
        if sha in prior and all(git("rev-parse", f"{head}:{path}", cwd=worktree) == blob
                                for path, blob in blobs.items()):
            blocked.remove(sha)
    if blocked:
        raise RuntimeError("A held change's patch is present in the candidate")


def assert_release_allowed(worktree):
    marker = worktree / "automation/publication-hold.json"
    if not marker.exists(): return
    try:
        value = json.loads(marker.read_text())
        if value.get("schema") != 1 or type(value.get("hold")) is not bool:
            raise ValueError("Invalid publication hold")
    except (ValueError, OSError) as error:
        raise RuntimeError("Malformed publication hold; no release allowed") from error
    if value["hold"]:
        raise RuntimeError("Maintainer publication hold is active; artifact-only validation remains permitted")


def has_releasable_changes(base, head, worktree):
    """A maintenance release can contain ReSeq runtime changes without new upstream work."""
    paths = git("diff", "--name-only", base, head, cwd=worktree).splitlines()
    build_files = {"app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts",
                   "gradle.properties", "gradle/libs.versions.toml", "compose_stability_config.conf"}
    for path in paths:
        if (path.startswith(("app/src/main/", "app/src/full/", "app/src/playstore/", "app/libs/",
                             "ffmpeg-decoder-downmix/", "libmpv-android/", "baselineprofile/src/"))):
            return True
        if path in build_files:
            def content(ref):
                text = git("show", f"{ref}:{path}", cwd=worktree)
                return re.sub(r"(?m)^.*\bversion(?:Code|Name)\s*=.*$", "", text)
            if content(base) != content(head):
                return True
    return False


def retrying_failed_publication(run, remote, head):
    """Only this run's own failed push may replace its originally fetched dev tip."""
    previous = run.get("publication") or {}
    if previous.get("head") != remote or previous.get("verified") or not ancestor(remote, head, cwd=run["worktree"]):
        return False
    endpoint = f"repos/{REPO}/actions/workflows/reseq-release.yml/runs?head_sha={remote}&per_page=30"
    attempts = [item for item in api(endpoint)["workflow_runs"]
                if item["head_sha"] == remote and item["head_branch"] == run["branch"]]
    return bool(attempts) and all(item["status"] == "completed" and
                                  item["conclusion"] in ("failure", "cancelled", "timed_out")
                                  for item in attempts)


def gate(run, state):
    if run["dry_run"]:
        raise RuntimeError("Dry runs cannot publish")
    if not (state / "publication-enabled.json").exists():
        raise RuntimeError("Initial dry run has not enabled publication")
    worktree = Path(run["worktree"])
    assert_release_allowed(worktree)
    head = git("rev-parse", "HEAD", cwd=worktree)
    if git("status", "--porcelain", cwd=worktree) or git("ls-files", "-u", cwd=worktree):
        raise RuntimeError("Uncommitted changes or conflicts")
    if not run["validation"] or run["validation"]["head"] != head:
        raise RuntimeError("Exact commit has not passed full validation")
    if not run["compatibility"] or run["compatibility"]["head"] != head:
        raise RuntimeError("Compatibility and functional overlap review missing")
    report = Path(run["compatibility"]["report"])
    if not report.is_file() or hashlib.sha256(report.read_bytes()).hexdigest() != run["compatibility"]["sha256"]:
        raise RuntimeError("Compatibility evidence changed or disappeared")
    if not run["device"] or run["device"]["status"] not in ("passed", "not-required"):
        raise RuntimeError("Required device testing has not passed")
    ledger = json.loads((worktree / LEDGER).read_text())["commits"]
    if any(c["sha"] not in ledger for c in run["commits"]):
        raise RuntimeError("Unreviewed upstream commits remain")
    pending = run.get("pending_parent") or {}
    if pending and not ancestor(pending["head"], head, cwd=worktree):
        raise RuntimeError("Candidate lost its preserved integration ancestry")
    candidate_runs = {run["id"], *pending.get("runs", [])}
    integrated = [c for c in run["commits"] if ledger[c["sha"]]["outcome"] == "integrated" and
                  ledger[c["sha"]].get("run") in candidate_runs]
    if not integrated and not has_releasable_changes(run["base"], head, worktree):
        raise RuntimeError("No application changes or new upstream integrations to release")
    enforce_held_changes(run, ledger, head)
    if not ancestor(run["dev"], head, cwd=worktree) or not ancestor(run["base"], head, cwd=worktree):
        raise RuntimeError("Release would omit development or published functionality")
    remote = git("ls-remote", ORIGIN, "refs/heads/dev", cwd=worktree).split()[0]
    if remote != run["dev"] and not retrying_failed_publication(run, remote, head):
        raise RuntimeError("Development advanced; rebuild from new base and repeat validation")
    latest = api(f"repos/{REPO}/releases/latest")
    if latest["tag_name"] != run["latest_tag"] or active_releases():
        raise RuntimeError("Release state changed or another release is running")
    contents = (worktree / "app/build.gradle.kts").read_text(encoding="utf-8-sig")
    version = re.search(r'versionName\s*=\s*"([^"]+)"', contents)[1]
    if not re.fullmatch(r"0\.1\.0-beta\.\d+(?:\.\d+)?", version):
        raise RuntimeError("Prepare a new version using ReSeq beta conventions")
    previous_version = run["latest_tag"].removeprefix("v")
    if not re.fullmatch(r"0\.1\.0-beta\.\d+(?:\.\d+)?", previous_version):
        raise RuntimeError("Release version convention changed; review before publishing")
    if tuple(map(int, version.split("-beta.")[1].split("."))) <= tuple(map(int, previous_version.split("-beta.")[1].split("."))):
        raise RuntimeError("Beta version must advance the latest published release")
    before = git("show", f"{run['base']}:app/build.gradle.kts", cwd=worktree)
    if int(re.search(r"versionCode\s*=\s*(\d+)", contents)[1]) <= int(re.search(r"versionCode\s*=\s*(\d+)", before)[1]):
        raise RuntimeError("versionCode must increase")
    return head, version


def publish(run, state, args):
    head, version = gate(run, state)
    worktree = Path(run["worktree"])
    notes = (worktree / args.notes).resolve()
    if not notes.is_relative_to(worktree.resolve()) or not notes.is_file():
        raise RuntimeError("Reviewed notes must be committed in this worktree")
    git("ls-files", "--error-unmatch", str(notes.relative_to(worktree.resolve())), cwd=worktree)
    # Normal atomic fast-forward pushes only; never update the checked-out local dev.
    git("push", "--atomic", ORIGIN, f"{head}:refs/heads/{run['branch']}", f"{head}:refs/heads/dev", cwd=worktree)
    run["publication"] = {"head": head, "version": version, "state": "pushed; dispatch pending"}
    save(state / "runs" / f"{run['id']}.json", run)
    command("gh", "workflow", "run", "reseq-release.yml", "--repo", REPO, "--ref", run["branch"],
            "-f", "publish=true", "-f", f"notes={notes.relative_to(worktree.resolve()).as_posix()}")
    run["publication"]["state"] = "dispatched; verify workflow, release and all five APKs before finishing"
    print(json.dumps(run["publication"], indent=2))


def preserve(run, state):
    worktree = Path(run["worktree"])
    branch = git("branch", "--show-current", cwd=worktree)
    if branch != run["branch"] or not (branch.startswith("automation/upstream-") or branch == "codex/upstream-automation"):
        raise RuntimeError("Only this run's isolated branch may be preserved")
    if git("status", "--porcelain", cwd=worktree):
        raise RuntimeError("Commit reviewed source/ledger first; private logs must remain ignored")
    git("push", ORIGIN, f"HEAD:refs/heads/{branch}", cwd=worktree)
    head = git("rev-parse", "HEAD", cwd=worktree)
    if git("ls-remote", ORIGIN, f"refs/heads/{branch}", cwd=worktree).split()[0] != head:
        raise RuntimeError("Could not verify the review branch backup")
    parent = run.get("pending_parent") or {}
    if not run["dry_run"] and branch.startswith("automation/upstream-"):
        save(state / "pending-integration.json", {"branch": branch, "head": head,
             "runs": [*parent.get("runs", []), run["id"]]})
    print(f"Preserved {branch}; no development branch, release or tag changed")


def retire_build_outputs(run, root, state):
    """Only this task's completed run; retain evidence and held ARM32 test APK."""
    tree = Path(run["worktree"]).resolve()
    expected_parent = (root / ".worktrees").resolve()
    if (tree.parent != expected_parent or not
            (re.fullmatch(r"upstream-\d{8}T\d{6}Z-[a-f0-9]{6}", tree.name) or tree.name == "upstream-automation")):
        raise RuntimeError("Refuse cleanup outside this task's known worktree names")
    build = (tree / "app/build").resolve()
    if build != tree / "app/build" or not build.is_relative_to(tree):
        raise RuntimeError("Refuse redirected build cleanup target")
    if not build.exists():
        run.setdefault("storage", {}).update(cleanup_pending=[], free_bytes=shutil.disk_usage(root).free)
        return
    evidence = state / "evidence" / run["id"]
    evidence.mkdir(parents=True, exist_ok=True)
    for name in ("test-results", "reports"):
        source = build / name
        if source.is_dir(): shutil.copytree(source, evidence / name, dirs_exist_ok=True)
    held_apk = build / "outputs/apk/full/debug/app-full-armeabi-v7a-debug.apk"
    # A successful published run can retrieve signed APKs from its release.
    if held_apk.is_file() and not (run.get("publication") or {}).get("verified"):
        target = state / "artifacts" / run["id"]
        target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(held_apk, target / held_apk.name)
    before = shutil.disk_usage(root).free
    pending = []
    def deferred(operation, path, error):
        # A shared Windows lint worker can retain JAR handles after a build.
        # Leave held files alone; never stop a shared daemon to reclaim them.
        pending.append({"path":str(Path(path).relative_to(build)),
                        "operation":operation.__name__, "error":type(error[1]).__name__})
    shutil.rmtree(build, onerror=deferred)
    run["storage"] = {"reclaimed_bytes": shutil.disk_usage(root).free - before,
                      "free_bytes": shutil.disk_usage(root).free, "evidence": str(evidence),
                      "cleanup_pending": pending}


def retry_pending_cleanup(root, state):
    for path in (state / "runs").glob("*.json"):
        old = json.loads(path.read_text())
        if not old.get("finished_utc") or not (old.get("storage") or {}).get("cleanup_pending"):
            continue
        tree = Path(old["worktree"])
        if (tree.resolve().parent != (root / ".worktrees").resolve() or
                not tree.is_dir() or not old.get("finished_head") or
                git("rev-parse", "HEAD", cwd=tree) != old["finished_head"] or
                git("status", "--porcelain", cwd=tree)):
            continue
        retire_build_outputs(old, root, state)
        save(path, old)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("begin"); p.add_argument("--dry-run", action="store_true")
    p.add_argument("--setup-worktree", action="store_true", help="Rehearse in this already isolated clean setup worktree")
    sub.add_parser("status")
    for name in ("review", "configure", "git", "preserve", "probe-device", "validate", "attest", "gate", "publish", "finish", "enable"):
        p = sub.add_parser(name); p.add_argument("--run", required=True)
        if name == "review":
            p.add_argument("--commit", required=True); p.add_argument("--outcome", choices=STATUSES, required=True)
            p.add_argument("--reason", required=True)
        if name == "configure":
            p.add_argument("--properties", required=True); p.add_argument("--sdk")
        if name == "git": p.add_argument("git_args", nargs=argparse.REMAINDER)
        if name == "attest":
            p.add_argument("--report", required=True); p.add_argument("--device", choices=("passed", "not-required", "held"), required=True)
            p.add_argument("--reason", required=True)
        if name == "publish": p.add_argument("--notes", required=True)
        if name == "finish": p.add_argument("--report", required=True)
        if name == "enable": p.add_argument("--report", required=True)
    args = parser.parse_args()
    root = root_dir(); state = root / ".upstream-sync"
    if args.action == "begin": return begin(args, root, state)
    if args.action == "status":
        print((state / "lock.json").read_text() if (state / "lock.json").exists() else "No active run")
        return
    run = load_run(state, args.run)
    if args.action == "review": review(run, args)
    elif args.action == "configure": configure(run, args, root)
    elif args.action == "git": worktree_git(run, args.git_args)
    elif args.action == "probe-device": probe_device(run)
    elif args.action == "preserve": preserve(run, state)
    elif args.action == "validate":
        # Persist invalidation before running tools; a failed retry must never reuse a pass.
        run["validation"] = run["compatibility"] = run["device"] = None
        save(state / "runs" / f"{run['id']}.json", run)
        validation(run)
    elif args.action == "attest": attest(run, args)
    elif args.action == "gate": print(gate(run, state))
    elif args.action == "publish": publish(run, state, args)
    elif args.action == "enable":
        if (not run["dry_run"] or not Path(args.report).is_file() or not run["validation"] or
                run["validation"]["head"] != git("rev-parse", "HEAD", cwd=run["worktree"])):
            raise RuntimeError("A completed dry-run validation and report are required")
        head = run["validation"]["head"]
        compatibility = run.get("compatibility") or {}
        if (compatibility.get("head") != head or (run.get("device") or {}).get("status") not in ("passed", "not-required")):
            raise RuntimeError("Dry-run compatibility and required device evidence are incomplete")
        evidence = Path(compatibility["report"])
        if not evidence.is_file() or hashlib.sha256(evidence.read_bytes()).hexdigest() != compatibility["sha256"]:
            raise RuntimeError("Dry-run compatibility evidence changed")
        save(state / "publication-enabled.json", {"dry_run": run["id"], "report": str(Path(args.report).resolve())})
    elif args.action == "finish":
        report = Path(args.report).resolve()
        if not report.is_file(): raise RuntimeError("Morning report is required")
        if run["publication"] and active_releases(head=run["publication"]["head"]):
            raise RuntimeError("Release still active; retain the lock and resume monitoring")
        ledger = Path(run["worktree"]) / LEDGER
        if ledger.exists() and not run["dry_run"]:
            current = json.loads(ledger.read_text())
            require_ledger_continuity(json.loads((state / "review-ledger.json").read_text()), current)
            save(state / "evidence" / run["id"] / "review-ledger.json", current)
            save(state / "review-ledger.json", current)
        retire_build_outputs(run, root, state)
        run["finished_head"] = git("rev-parse", "HEAD", cwd=run["worktree"])
        run["report"] = str(report); run["finished_utc"] = datetime.now(timezone.utc).isoformat()
    save(state / "runs" / f"{run['id']}.json", run)
    if args.action == "finish": (state / "lock.json").unlink()


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, FileExistsError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
