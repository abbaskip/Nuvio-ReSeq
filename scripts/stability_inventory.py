"""Reproduce Phase 1 source counts without reading local configuration or app data."""
from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
GATES = {
    "PlayerSessionTracker": r"\bPlayerSessionTracker\b",
    "idle_wait": r"BackgroundWork\.(awaitIdle|awaitPlaybackIdleBlocking)",
    "quiet": r"BackgroundWork\.quiet",
    "reads": r"BackgroundWork\.reads",
    "heavy": r"BackgroundWork\.(heavy|awaitNoHeavy)\b",
    "navigation": r"\b(yieldToNavigation|onNavigation)\b",
    "live_fullscreen": r"LiveTvFullscreen\.active",
    "recommendation_playback": r"\b(isPlaybackActive)\b",
    "guide_building": r"\bguideBuilding\b",
    "live_channel_open": r"\bliveChannelOpen\b",
    "sports_gates": r"\bsports(Background|Scores|Browsing|ChannelLookup|Catalogue)Allowed\b",
    "home_gates": r"\b(startupGracePeriodActive|holdContinueWatchingWhilePlayerOpen|homeContentActive|runVisibleHomeWork)\b",
}
HOTSPOTS = [
    "SportsRepository.kt", "SportsMatcher.kt", "NewsArticles.kt",
    "PlayerRuntimeController.kt", "HomeEpisodeShuffle.kt", "UsenetFile.kt",
    "SportsScreen.kt", "StreamScreenViewModel.kt", "PlayerRuntimeControllerStreams.kt",
    "PlayerRuntimeControllerInitialization.kt", "TvdbContinueWatching.kt",
    "HomeViewModelContinueWatching.kt", "LiveTvRepository.kt", "UsenetPlayback.kt",
]


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True,
                          encoding="utf-8").stdout


def inventory(ref: str) -> dict:
    revision = git("rev-parse", "--verify", f"{ref}^{{commit}}").strip()
    # Deliberately read committed blobs, never untracked files or local properties.
    paths = git("ls-tree", "-r", "--name-only", revision).splitlines()
    sources = [p for p in paths if p.startswith("app/src/main/") and p.endswith(".kt")]
    blobs = subprocess.run(
        ["git", "cat-file", "--batch"], cwd=ROOT,
        input="".join(f"{revision}:{p}\n" for p in sources).encode(),
        capture_output=True, check=True).stdout
    texts = {}
    offset = 0
    for path in sources:
        end = blobs.index(b"\n", offset)
        size = int(blobs[offset:end].split()[-1])
        texts[path] = blobs[end + 1:end + 1 + size].decode("utf-8-sig")
        offset = end + size + 2
    gate_hits = {}
    for label, pattern in GATES.items():
        hits = [{"file": path, "line": i, "source": line.strip()}
                for path, source in texts.items()
                for i, line in enumerate(source.splitlines(), 1) if re.search(pattern, line)]
        gate_hits[label] = {"files": len({h["file"] for h in hits}), "hits": hits}
    scope_hits = [{"file": path, "line": i, "source": line.strip()}
                  for path, source in texts.items()
                  for i, line in enumerate(source.splitlines(), 1)
                  if re.search(r"\bCoroutineScope\s*\(", line)]
    commits = git("log", "--since=2026-11-05T00:00:00+11:00",
                  "--until=2026-11-12T00:00:00+11:00", "--format=%H%x09%an", revision).splitlines()
    recent = git("log", "--since=2026-11-09T00:00:00+11:00",
                 "--until=2026-11-12T00:00:00+11:00", "--format=%H", revision).splitlines()
    hotspots = {}
    for name in HOTSPOTS:
        matched = [p for p in sources if Path(p).name == name]
        hotspots[name] = {p: len(git("log", "--since=2026-11-05T00:00:00+11:00",
                                      "--until=2026-11-12T00:00:00+11:00", "--format=%H",
                                      revision, "--", p).splitlines()) for p in matched}
    sports = {p: t for p, t in texts.items() if "/livetv/sports/" in p}
    cw_names = {"HomeViewModelContinueWatching.kt", "HomeEpisodeShuffle.kt",
                "TvdbContinueWatching.kt", "ContinueWatchingNextUpReconciliation.kt"}
    return {
        "revision": revision,
        "method": "Committed Kotlin blobs; lexical counts include comments/declarations, not proof of runtime ownership. Git log uses author names, commit dates, all reachable parents, Sydney midnight boundaries.",
        "commits_nov5_to_nov11": len(commits), "commits_nov9_to_nov11": len(recent),
        "authors": dict(collections.Counter(c.split("\t", 1)[1] for c in commits)),
        "jvm_kotlin_files": sum(p.startswith("app/src/test/") and p.endswith(".kt") for p in paths),
        "instrumentation_kotlin_files": sum(p.startswith("app/src/androidTest/") and p.endswith(".kt") for p in paths),
        "sports_files": len(sports), "sports_lines": sum(len(t.splitlines()) for t in sports.values()),
        "sports_repository_stateflow_constructor_mentions": len(re.findall(r"MutableStateFlow(?:<[^\n]*?>)?\(", next(t for p, t in sports.items() if p.endswith("/SportsRepository.kt")))),
        "cw_core_file_lines": {p: len(t.splitlines()) for p, t in texts.items() if Path(p).name in cw_names},
        "hotspots": hotspots, "gates": gate_hits,
        "scope_constructor_lines": scope_hits,
        "scope_constructor_line_count": len(scope_hits),
        "scope_constructor_lines_with_io": sum("Dispatchers.IO" in h["source"] for h in scope_hits),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inventory(args.ref)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Inventory for {result['revision']} written to {args.output}")


if __name__ == "__main__":
    main()
