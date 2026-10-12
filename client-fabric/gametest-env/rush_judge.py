#!/usr/bin/env python3
"""The judge of job rush (docs/DESIGN-PLAY.md §6.7.6, docs/TEST.md TC-PLAY-119, TC-PLAY-120). Standard
library only: CI runs it with the runner's python3, a local run with the repository's .venv.

    rush_judge.py --ledger-dir logs --server-log rush-server.log --session-log sessionserver.log \\
        [--client-source <dir>] <rush.txt> [<rush.txt> ...]

The reports are what RushClientGameTest writes, one per client launch, at
client-fabric/build/run/<run>/synaxis-gametest/rush.txt; the ledger directory, the sample server's log and
the fake sessionserver's log are those of the same run. Those paths are taken as given, from the current
directory; the client mod's main sources default to client-fabric/src/main/java beside this script. N is the
number of reports, one round played alone per launch. It fails, exit code 1 with the reasons on standard
output, unless all of these hold:

1. every report has one line per step, Q0a, Q0b, Q0c, Q1 to Q9, each `rush <step> PASS <evidence>`;
2. the ledger has N arenas (kind_id rush:round), each with its play_instance_created, _opened, _started,
   _ended and _closed once and in that order, closed at most 15 s after ended, and prepare_ms at most 1,000;
3. the normal end rate of docs/DESIGN-PLAY.md §3 over those arenas is N/N;
4. after every play_instance_moved the same connection's next play_scene_ready comes within 5 s, and there
   are at least 2N play_scene_ready;
5. rush_round_ended N times, each with players=1 and cause=time_limit;
6. client_verified N times, client_rejected none; the fake sessionserver saw N joins and answered N
   hasJoined, all 200;
7. no ERROR line in the server's log but those ALLOWED_ERRORS names, and no "Plugin channel handler failed";
8. the client mod's main sources hold the word rush nowhere.

Every arena's prepare_ms and what Q0b saw of the screens go into the notes, which it prints either way.
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import sys
from calendar import timegm
from pathlib import Path
from typing import Iterator

STEPS = ["Q0a", "Q0b", "Q0c"] + [f"Q{n}" for n in range(1, 10)]
LINE = re.compile(r"rush (Q\d+[a-z]?) (PASS|FAIL|SKIP)(?: (.*))?")
Q0B_SCREENS = re.compile(r"screens (\[[^\]]*\])")

ARENA_KIND = "rush:round"
LIFECYCLE = ["play_instance_created", "play_instance_opened", "play_instance_started", "play_instance_ended",
             "play_instance_closed"]
ENDED_TO_CLOSED_S = 15.0
PREPARE_LIMIT_MS = 1000
MOVE_TO_READY_S = 5.0
NORMAL_CAUSES = {"objective", "time_limit"}
COUNTED_CAUSES = {"objective", "time_limit", "error", "prepare_failed"}

TIMESTAMP = re.compile(r"(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(?:\.(\d{1,9}))?Z")
ERROR_LINE = re.compile(r"\d\d:\d\d:\d\d\.\d{3} \[[^\]]*\] ERROR ")
# ERROR lines of the sample server's log that a run may have, each with the reason it is harmless; none yet
ALLOWED_ERRORS: list[tuple[re.Pattern[str], str]] = []
CHANNEL_FAILED = "Plugin channel handler failed"

JOIN = re.compile(r"[0-9]+ POST /session/minecraft/join -> 204")
HAS_JOINED = re.compile(r"[0-9]+ GET /session/minecraft/hasJoined.*")
HAS_JOINED_200 = re.compile(r"[0-9]+ GET /session/minecraft/hasJoined.* -> 200")

# What `grep -w` takes for a word: letters, digits and the underscore
RUSH_WORD = re.compile(r"(?<![A-Za-z0-9_])rush(?![A-Za-z0-9_])")
CLIENT_SOURCE = Path(__file__).resolve().parent.parent / "src" / "main" / "java"


class Verdict:
    """What the judge found: the problems, none for a pass, and the readings it copies out either way."""

    def __init__(self) -> None:
        self.problems: list[str] = []
        self.notes: list[str] = []


class Event:
    """One line of the ledger: its time, its place in the files and its attributes."""

    def __init__(self, nanos: int, position: int, attributes: dict) -> None:
        self.nanos = nanos
        self.position = position
        self.attributes = attributes


def judge_report(path: Path, verdict: Verdict) -> None:
    """One report: every step once, every step PASS; Q0b's screens go into the notes."""
    if not path.is_file():
        verdict.problems.append(f"{path}: no report")
        return
    seen: set[str] = set()
    for number, text in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not text.strip():
            continue
        match = LINE.fullmatch(text)
        if match is None or match.group(1) not in STEPS:
            verdict.problems.append(f"{path}:{number}: not a step line: {text!r}")
            continue
        step, status, evidence = match.group(1), match.group(2), match.group(3) or ""
        if step in seen:
            verdict.problems.append(f"{path}:{number}: {step} a second time")
            continue
        seen.add(step)
        if status == "FAIL":
            verdict.problems.append(f"{path}:{number}: {text}")
        elif status == "SKIP":
            verdict.problems.append(f"{path}:{number}: {step} was skipped, every step has to run: {text}")
        if step == "Q0b":
            screens = Q0B_SCREENS.search(evidence)
            verdict.notes.append(f"{path}: Q0b screens {screens.group(1) if screens else '(none named)'}")
    for step in STEPS:
        if step not in seen:
            verdict.problems.append(f"{path}: no line for {step}")


def nanos(timestamp: str) -> int:
    """A ledger timestamp, Instant#toString in UTC, as nanoseconds since the epoch."""
    match = TIMESTAMP.fullmatch(timestamp)
    if match is None:
        raise ValueError(f"not a ledger timestamp: {timestamp!r}")
    seconds = timegm(tuple(int(part) for part in match.groups()[:6]) + (0, 0, 0))
    return seconds * 1_000_000_000 + int((match.group(7) or "0").ljust(9, "0"))


def ledger_lines(directory: Path) -> Iterator[str]:
    """Every line of the ledger, a day already gzipped by the ledger's housekeeping included."""
    for path in sorted(directory.glob("events-*.jsonl.gz")):
        with gzip.open(path, "rt", encoding="utf-8") as day:
            yield from day
    for path in sorted(directory.glob("events-*.jsonl")):
        yield from path.read_text(encoding="utf-8").splitlines()


def read_ledger(directory: Path, verdict: Verdict) -> list[Event]:
    events: list[Event] = []
    if not directory.is_dir():
        verdict.problems.append(f"{directory}: no ledger directory")
        return events
    for position, text in enumerate(ledger_lines(directory)):
        if not text.strip():
            continue
        try:
            attributes = json.loads(text)
            events.append(Event(nanos(attributes["ts"]), position, attributes))
        except (ValueError, KeyError, TypeError) as e:
            verdict.problems.append(f"ledger line {position + 1} unreadable ({e}): {text.strip()!r}")
    events.sort(key=lambda event: (event.nanos, event.position))
    return events


def named(events: list[Event], name: str) -> list[Event]:
    return [event for event in events if event.attributes.get("event") == name]


def seconds(earlier: Event, later: Event) -> float:
    return (later.nanos - earlier.nanos) / 1e9


def judge_arenas(events: list[Event], launches: int, verdict: Verdict) -> None:
    """Criteria 2 and 3: the arenas' five states, their timing, prepare_ms and the normal end rate."""
    arenas: dict[str, dict[str, list[Event]]] = {}
    for event in events:
        name = event.attributes.get("event")
        if name in LIFECYCLE and event.attributes.get("kind_id") == ARENA_KIND:
            states = arenas.setdefault(str(event.attributes.get("instance_id")), {state: [] for state in LIFECYCLE})
            states[name].append(event)
    if len(arenas) != launches:
        verdict.problems.append(f"ledger: {len(arenas)} arenas ({ARENA_KIND}), expected {launches}, one per launch")
    normal = counted = 0
    for instance, states in arenas.items():
        complete = True
        for state in LIFECYCLE:
            if len(states[state]) != 1:
                verdict.problems.append(f"ledger: arena {instance} has {state} {len(states[state])} times, expected once")
                complete = False
        for opened in states["play_instance_opened"]:
            prepare = opened.attributes.get("prepare_ms")
            verdict.notes.append(f"arena {instance}: prepare_ms {prepare}")
            if not isinstance(prepare, (int, float)) or prepare > PREPARE_LIMIT_MS:
                verdict.problems.append(f"ledger: arena {instance} prepare_ms {prepare}, over {PREPARE_LIMIT_MS}: "
                                        "the arenas are to be prepared at startup instead (docs/DESIGN-PLAY.md §3)")
        for closed in states["play_instance_closed"]:
            cause = closed.attributes.get("cause")
            timed_out = str(closed.attributes.get("settle_timeout")).lower() == "true"
            if cause in COUNTED_CAUSES:
                counted += 1
                if cause in NORMAL_CAUSES and not timed_out:
                    normal += 1
        if not complete:
            continue
        times = [states[state][0] for state in LIFECYCLE]
        if any(later.nanos < earlier.nanos for earlier, later in zip(times, times[1:])):
            order = ", ".join(f"{state} {event.attributes['ts']}" for state, event in zip(LIFECYCLE, times))
            verdict.problems.append(f"ledger: arena {instance} states out of order: {order}")
        settle = seconds(states["play_instance_ended"][0], states["play_instance_closed"][0])
        if settle > ENDED_TO_CLOSED_S:
            verdict.problems.append(f"ledger: arena {instance} {settle:.1f} s from ended to closed, "
                                    f"over {ENDED_TO_CLOSED_S:.0f} s")
    if normal != launches or counted != launches:
        verdict.problems.append(f"ledger: normal end rate {normal}/{counted}, expected {launches}/{launches}")


def judge_readiness(events: list[Event], launches: int, verdict: Verdict) -> None:
    """Criterion 4: every move answered by the same connection's next ready within 5 s; 2N readies at least."""
    readies = named(events, "play_scene_ready")
    for moved in named(events, "play_instance_moved"):
        connection = moved.attributes.get("connection_id")
        after = [ready for ready in readies
                 if ready.attributes.get("connection_id") == connection and ready.nanos >= moved.nanos]
        if not after:
            verdict.problems.append(f"ledger: no play_scene_ready after the move of connection_id={connection} "
                                    f"at {moved.attributes['ts']}")
            continue
        took = seconds(moved, after[0])
        if took > MOVE_TO_READY_S:
            verdict.problems.append(f"ledger: play_scene_ready {took:.1f} s after the move of connection_id={connection} "
                                    f"at {moved.attributes['ts']}, over {MOVE_TO_READY_S:.0f} s")
    if len(readies) < 2 * launches:
        verdict.problems.append(f"ledger: play_scene_ready {len(readies)} times, expected at least {2 * launches}")


def judge_rounds_and_logins(events: list[Event], launches: int, verdict: Verdict) -> None:
    """Criteria 5 and the ledger half of 6."""
    ended = named(events, "rush_round_ended")
    if len(ended) != launches:
        verdict.problems.append(f"ledger: rush_round_ended {len(ended)} times, expected {launches}")
    for event in ended:
        players, cause = event.attributes.get("players"), event.attributes.get("cause")
        if players != 1 or cause != "time_limit":
            verdict.problems.append(f"ledger: rush_round_ended round_id={event.attributes.get('round_id')} "
                                    f"players={players} cause={cause}, expected players=1 cause=time_limit")
    for name, expected in (("client_verified", launches), ("client_rejected", 0)):
        count = len(named(events, name))
        if count != expected:
            verdict.problems.append(f"ledger: {name} {count} times, expected {expected}")


def judge_session_log(path: Path, launches: int, verdict: Verdict) -> None:
    """The sessionserver half of criterion 6."""
    if not path.is_file():
        verdict.problems.append(f"{path}: no sessionserver log")
        return
    lines = path.read_text(encoding="utf-8").splitlines()
    joins = sum(1 for line in lines if JOIN.fullmatch(line))
    asked = sum(1 for line in lines if HAS_JOINED.fullmatch(line))
    answered = sum(1 for line in lines if HAS_JOINED_200.fullmatch(line))
    if joins != launches:
        verdict.problems.append(f"{path}: sessionserver join {joins}, expected {launches}")
    if asked != launches or answered != launches:
        verdict.problems.append(f"{path}: sessionserver hasJoined 200 {answered} of {asked}, "
                                f"expected {launches} of {launches}")


def judge_server_log(path: Path, verdict: Verdict) -> None:
    """Criterion 7."""
    if not path.is_file():
        verdict.problems.append(f"{path}: no server log")
        return
    for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
        if ERROR_LINE.match(line) and not any(pattern.search(line) for pattern, _ in ALLOWED_ERRORS):
            verdict.problems.append(f"{path}:{number}: an ERROR line: {line}")
        elif CHANNEL_FAILED in line:
            verdict.problems.append(f"{path}:{number}: {line}")


def judge_client_source(directory: Path, verdict: Verdict) -> None:
    """Criterion 8, `grep -rlw rush` over the client mod's main sources."""
    if not directory.is_dir():
        verdict.problems.append(f"{directory}: no client source directory")
        return
    for path in sorted(directory.rglob("*")):
        if path.is_file() and RUSH_WORD.search(path.read_text(encoding="utf-8", errors="replace")):
            verdict.problems.append(f"{path}: the client mod's main sources name rush; "
                                    "the sample game has no client code of its own")


def judge(reports: list[Path], ledger_dir: Path, server_log: Path, session_log: Path,
          client_source: Path = CLIENT_SOURCE) -> Verdict:
    """Judges one run of job rush; a verdict without problems is a pass."""
    verdict = Verdict()
    if not reports:
        verdict.problems.append("no report named")
    for path in reports:
        judge_report(path, verdict)
    launches = len(reports)
    events = read_ledger(ledger_dir, verdict)
    judge_arenas(events, launches, verdict)
    judge_readiness(events, launches, verdict)
    judge_rounds_and_logins(events, launches, verdict)
    judge_session_log(session_log, launches, verdict)
    judge_server_log(server_log, verdict)
    judge_client_source(client_source, verdict)
    return verdict


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Judges a run of job rush (docs/DESIGN-PLAY.md §6.7.6).")
    parser.add_argument("--ledger-dir", type=Path, required=True, help="the sample server's logs/ directory")
    parser.add_argument("--server-log", type=Path, required=True, help="the sample server's standard output")
    parser.add_argument("--session-log", type=Path, required=True, help="the fake sessionserver's output")
    parser.add_argument("--client-source", type=Path, default=CLIENT_SOURCE, help="the client mod's main sources")
    parser.add_argument("reports", type=Path, nargs="*", help="rush.txt of every client launch")
    arguments = parser.parse_args(argv)
    for path in arguments.reports:
        print(f"== {path}")
        if path.is_file():
            print(path.read_text(encoding="utf-8"), end="")
    verdict = judge(arguments.reports, arguments.ledger_dir, arguments.server_log, arguments.session_log,
                    arguments.client_source)
    for note in verdict.notes:
        print(f"note {note}")
    for problem in verdict.problems:
        print(f"FAIL {problem}")
    print(f"rush judge: {'PASS' if not verdict.problems else 'FAIL'} "
          f"({len(arguments.reports)} reports, {len(verdict.problems)} problems)")
    return 0 if not verdict.problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
