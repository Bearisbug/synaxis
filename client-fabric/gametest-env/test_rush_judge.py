#!/usr/bin/env python3
"""Unit tests of rush_judge.py, standard library only (docs/TEST.md TC-PLAY-028):

    python3 -m unittest discover -s client-fabric/gametest-env -p 'test_*.py'
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

JUDGE = Path(__file__).with_name("rush_judge.py")


def load_judge():
    spec = importlib.util.spec_from_file_location("rush_judge_under_test", JUDGE)
    judge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(judge)
    return judge


def report(**statuses: str) -> str:
    """A report of every step, Q0a and Q0b PASS and Q1 to Q9 SKIP unless a keyword says otherwise."""
    lines = []
    for step in ["Q0a", "Q0b"] + [f"Q{n}" for n in range(1, 10)]:
        status = statuses.get(step, "PASS" if step.startswith("Q0") else "SKIP")
        lines.append(f"rush {step} {status} evidence of {step}")
    return "\n".join(lines) + "\n"


class JudgeTest(unittest.TestCase):

    def setUp(self) -> None:
        self.judge = load_judge()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def write(self, name: str, text: str) -> Path:
        path = self.directory / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_both_launches_with_q0_passed_and_the_rest_skipped_pass(self) -> None:
        reports = [self.write("dev.txt", report()), self.write("production.txt", report())]
        self.assertEqual(self.judge.judge(reports), [])

    def test_a_failed_step_fails(self) -> None:
        problems = self.judge.judge([self.write("dev.txt", report(Q0b="FAIL"))])
        self.assertEqual(len(problems), 1)
        self.assertIn("rush Q0b FAIL", problems[0])

    def test_a_failed_later_step_fails_too(self) -> None:
        self.assertEqual(len(self.judge.judge([self.write("dev.txt", report(Q5="FAIL"))])), 1)

    def test_a_skipped_q0_fails(self) -> None:
        problems = self.judge.judge([self.write("dev.txt", report(Q0a="SKIP"))])
        self.assertEqual(len(problems), 1)
        self.assertIn("Q0a must run", problems[0])

    def test_a_missing_report_fails(self) -> None:
        problems = self.judge.judge([self.write("dev.txt", report()), self.directory / "production.txt"])
        self.assertEqual(problems, [f"{self.directory / 'production.txt'}: no report"])

    def test_no_report_named_fails(self) -> None:
        self.assertEqual(self.judge.judge([]), ["no report named"])

    def test_a_missing_line_fails(self) -> None:
        text = "".join(line + "\n" for line in report().splitlines() if not line.startswith("rush Q7 "))
        self.assertEqual(self.judge.judge([self.write("dev.txt", text)]), [f"{self.directory / 'dev.txt'}: no line for Q7"])

    def test_a_doubled_line_fails(self) -> None:
        problems = self.judge.judge([self.write("dev.txt", report() + "rush Q0a PASS again\n")])
        self.assertEqual(len(problems), 1)
        self.assertIn("Q0a a second time", problems[0])

    def test_a_line_of_another_shape_fails(self) -> None:
        for line in ["rush Q0a OK fine", "rush Q10 PASS no such step", "Q0a PASS no prefix"]:
            with self.subTest(line=line):
                problems = self.judge.judge([self.write("dev.txt", report() + line + "\n")])
                self.assertEqual(len(problems), 1)
                self.assertIn("not a step line", problems[0])

    def test_the_exit_code_is_0_when_it_holds_and_1_when_it_does_not(self) -> None:
        good = self.write("good.txt", report())
        bad = self.write("bad.txt", report(Q0a="FAIL"))
        passed = subprocess.run([sys.executable, str(JUDGE), str(good)], capture_output=True, text=True)
        failed = subprocess.run([sys.executable, str(JUDGE), str(good), str(bad)], capture_output=True, text=True)
        self.assertEqual(passed.returncode, 0, passed.stdout)
        self.assertIn("rush judge: PASS", passed.stdout)
        self.assertEqual(failed.returncode, 1, failed.stdout)
        self.assertIn("rush judge: FAIL", failed.stdout)


if __name__ == "__main__":
    unittest.main()
