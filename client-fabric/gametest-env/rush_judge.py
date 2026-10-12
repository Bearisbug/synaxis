#!/usr/bin/env python3
"""The judge of job rush (docs/DESIGN-PLAY.md §6.7.6, docs/TEST.md TC-PLAY-028): reads the reports
RushClientGameTest writes, one per client launch, at
client-fabric/build/run/<run>/synaxis-gametest/rush.txt. Standard library only: CI runs it with the
runner's python3, a local run with the repository's .venv.

    rush_judge.py <rush.txt> [<rush.txt> ...]

Every report has one line per step, in the order Q0a, Q0b, Q0c, Q1 to Q9, each `rush <step> PASS|FAIL|SKIP
<evidence>`. This stage of the judge checks Q0 against the play fixture, the first step of §26 S1 (Q0a,
Q0b) and the client widgets of group C (Q0c): Q0a, Q0b and Q0c PASS in every report. Q1 to Q9 may be SKIP, since the sample game's server is not in the
job yet; a FAIL anywhere, a missing or doubled line, a line of another shape and a missing report are
failures. Exit code 0 when every report holds, 1 otherwise; the reasons go to standard output.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

STEPS = ["Q0a", "Q0b", "Q0c"] + [f"Q{n}" for n in range(1, 10)]
MUST_PASS = {"Q0a", "Q0b", "Q0c"}
LINE = re.compile(r"rush (Q\d+[a-z]?) (PASS|FAIL|SKIP)(?: (.*))?")


def judge_report(path: Path) -> list[str]:
    """The problems of one report, each prefixed with the report's path."""
    if not path.is_file():
        return [f"{path}: no report"]
    problems: list[str] = []
    seen: dict[str, str] = {}
    for number, text in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not text.strip():
            continue
        match = LINE.fullmatch(text)
        if match is None or match.group(1) not in STEPS:
            problems.append(f"{path}:{number}: not a step line: {text!r}")
            continue
        step, status = match.group(1), match.group(2)
        if step in seen:
            problems.append(f"{path}:{number}: {step} a second time")
            continue
        seen[step] = status
        if status == "FAIL":
            problems.append(f"{path}:{number}: {text}")
        elif status == "SKIP" and step in MUST_PASS:
            problems.append(f"{path}:{number}: {step} must run, it was skipped: {text}")
    for step in STEPS:
        if step not in seen:
            problems.append(f"{path}: no line for {step}")
    return problems


def judge(paths: list[Path]) -> list[str]:
    """The problems of all reports; none means the job passes."""
    if not paths:
        return ["no report named"]
    problems: list[str] = []
    for path in paths:
        problems.extend(judge_report(path))
    return problems


def main(argv: list[str]) -> int:
    paths = [Path(argument) for argument in argv]
    for path in paths:
        print(f"== {path}")
        if path.is_file():
            print(path.read_text(encoding="utf-8"), end="")
    problems = judge(paths)
    for problem in problems:
        print(f"FAIL {problem}")
    print(f"rush judge: {'PASS' if not problems else 'FAIL'} ({len(paths)} reports, {len(problems)} problems)")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
