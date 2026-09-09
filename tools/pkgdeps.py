#!/usr/bin/env python3
"""Top level package dependency matrix for the engine sources.

Reads every ``.java`` file under the given source roots, folds each
``import dev.gameengine.…`` statement onto the top level package it belongs to
and prints the resulting matrix, the fan in / fan out table and — the number the
layering gate cares about — the edges that leave the lower layer.

Usage::

    python3 tools/pkgdeps.py                 # matrix + lower layer report
    python3 tools/pkgdeps.py --detail        # also list the offending files

The lower layer is the same set the ArchUnit rule in
``src/test/java/dev/gameengine/ArchitectureTest.java`` enforces; keep the two in
sync. Standard library only, no virtualenv needed.
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import re
import sys

ROOT_PACKAGE = "dev.gameengine"

# Classes that sit directly in the root package and therefore fold onto <root>,
# but that carry no engine dependency of their own, so the lower layer may use
# them. Keep in sync with ArchitectureTest#LOWER_LEVEL_ROOT_CLASSES.
LOWER_LEVEL_ROOT_CLASSES = frozenset({
    "dev.gameengine.ServerFlag",
})

LOWER_LEVEL_PACKAGES = (
    "codec",
    "condition",
    "coordinate",
    "exception",
    "gamedata",
    "monitoring",
    "thread",
    "timer",
    "utils",
)

PACKAGE_PATTERN = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)\s*;", re.MULTILINE)
IMPORT_PATTERN = re.compile(
    r"^\s*import\s+(?:static\s+)?(" + re.escape(ROOT_PACKAGE) + r"\.[A-Za-z0-9_.$]+)\s*;",
    re.MULTILINE,
)


def top_level(fqn: str) -> str:
    """Fold a fully qualified name onto its top level engine package."""
    tail = fqn[len(ROOT_PACKAGE) + 1:] if fqn.startswith(ROOT_PACKAGE + ".") else ""
    head = tail.split(".", 1)[0]
    return head if head[:1].islower() else "<root>"


def imported_type(fqn: str) -> str:
    """The imported type, dropping a trailing static member or nested class."""
    parts = fqn.split(".")
    kept: list[str] = []
    for part in parts:
        kept.append(part)
        if part[:1].isupper():
            break
    return ".".join(kept)


def scan(roots: list[pathlib.Path]) -> tuple[dict, collections.Counter, dict]:
    """Return per package file counts, the edge counter and the per edge files."""
    files: collections.Counter = collections.Counter()
    edges: collections.Counter = collections.Counter()
    witnesses: dict = collections.defaultdict(list)
    for root in roots:
        for path in sorted(root.rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            declared = PACKAGE_PATTERN.search(text)
            if declared is None or not declared.group(1).startswith(ROOT_PACKAGE):
                continue
            source = top_level(declared.group(1) + ".X")
            files[source] += 1
            for target_fqn in IMPORT_PATTERN.findall(text):
                target = top_level(target_fqn)
                if target == source:
                    continue
                edges[(source, target)] += 1
                witnesses[(source, target)].append((str(path), imported_type(target_fqn)))
    return files, edges, witnesses


def leaves_lower_layer(source: str, target: str, imported: str) -> bool:
    if source not in LOWER_LEVEL_PACKAGES:
        return False
    if target in LOWER_LEVEL_PACKAGES:
        return False
    return imported not in LOWER_LEVEL_ROOT_CLASSES


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "roots",
        nargs="*",
        default=["src/main/java"],
        help="source roots to scan (default: src/main/java)",
    )
    parser.add_argument("--detail", action="store_true", help="list the offending files")
    args = parser.parse_args()

    roots = [pathlib.Path(root) for root in args.roots]
    missing = [root for root in roots if not root.is_dir()]
    if missing:
        print("no such source root: " + ", ".join(str(root) for root in missing), file=sys.stderr)
        return 2

    files, edges, witnesses = scan(roots)

    fan_out = collections.Counter()
    fan_in = collections.Counter()
    out_imports = collections.Counter()
    in_imports = collections.Counter()
    for (source, target), count in edges.items():
        fan_out[source] += 1
        fan_in[target] += 1
        out_imports[source] += count
        in_imports[target] += count

    print(f"source roots: {', '.join(str(root) for root in roots)}")
    print(f"packages: {len(files)}   edges: {len(edges)}   imports: {sum(edges.values())}")

    print("\npackage                files  fanout  fanin  outImp  inImp")
    for package in sorted(files, key=lambda name: -files[name]):
        print(
            f"{package:<20} {files[package]:>6} {fan_out[package]:>7} {fan_in[package]:>6}"
            f" {out_imports[package]:>7} {in_imports[package]:>6}"
        )

    print("\nedges (source -> target, imports)")
    for (source, target), count in sorted(edges.items(), key=lambda item: -item[1]):
        print(f"  {source:<14} -> {target:<14} {count:>4}")

    lower = {
        edge: count
        for edge, count in edges.items()
        if any(leaves_lower_layer(edge[0], edge[1], imported) for _, imported in witnesses[edge])
    }
    lower_imports = 0
    for edge, _ in lower.items():
        lower_imports += sum(
            1 for _, imported in witnesses[edge] if leaves_lower_layer(edge[0], edge[1], imported)
        )

    print("\nlower layer: " + ", ".join(LOWER_LEVEL_PACKAGES))
    print(f"lower layer edges into the rest of the engine: {len(lower)} ({lower_imports} imports)")
    for (source, target), _ in sorted(lower.items(), key=lambda item: -item[1]):
        offending = [
            (path, imported)
            for path, imported in witnesses[(source, target)]
            if leaves_lower_layer(source, target, imported)
        ]
        print(f"  {source:<14} -> {target:<14} {len(offending):>4}")
        if args.detail:
            for path, imported in offending:
                print(f"      {path}: {imported}")

    inner = sorted(
        edge for edge in edges if edge[0] in LOWER_LEVEL_PACKAGES and edge[1] in LOWER_LEVEL_PACKAGES
    )
    mutual = sorted({tuple(sorted(edge)) for edge in inner if (edge[1], edge[0]) in edges})
    print(f"\nmutual dependencies inside the lower layer: {len(mutual)}")
    for first, second in mutual:
        print(f"  {first} <-> {second}   {edges[(first, second)]} / {edges[(second, first)]}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
