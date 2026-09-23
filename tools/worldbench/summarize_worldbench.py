"""Prints the LargeWorldBench result JSON as the markdown tables bench-2026-09.md uses.

Usage: python3 tools/worldbench/summarize_worldbench.py <results.json>
"""
import json
import sys


def dist(d, digits=2):
    return f"{d['median']:.{digits}f} / {d['p95']:.{digits}f}"


def main(path):
    report = json.load(open(path, encoding="utf-8"))
    m = report["machine"]
    print(f"machine: {m['cpu']}, {m['availableProcessors']} cores, {m['physicalMemoryGiB']:.0f} GiB, {m['os']}, {m['java']}, commit {m['gitCommit'][:10]}")
    print(f"child JVM: {' '.join(report['childJvmArgs'])}")
    print()
    print("| job | framed B (no light) | framed B (light) | uncompressed B (no light / light) | first ser. ms (no light) | first ser. ms (lazy light) | ser. ms (light known) | whole pass ms/chunk wall/CPU: plain, lazy light, light known | heap KiB/chunk: plain, lit, lit+cache |")
    print("|---|---|---|---|---|---|---|---|---|")
    for j in report["jobs"]:
        if j["params"]["type"] != "packet":
            continue
        p = j["wholePass"]
        h = j["heap"]
        print(f"| {j['job']} | {dist(j['plainFramedBytes'], 0)} | {dist(j['litFramedBytes'], 0)} "
              f"| {j['plainUncompressedBytes']['median']:.0f} / {j['litUncompressedBytes']['median']:.0f} "
              f"| {dist(j['plainFirstSerializeMs'])} | {dist(j['lazyLightFirstSerializeMs'])} | {dist(j['litSerializeMs'])} "
              f"| {p['plainWallMsPerChunk']['median']:.2f}/{p['plainCpuMsPerChunk']['median']:.2f}, "
              f"{p['lazyLightWallMsPerChunk']['median']:.2f}/{p['lazyLightCpuMsPerChunk']['median']:.2f}, "
              f"{p['litWallMsPerChunk']['median']:.2f}/{p['litCpuMsPerChunk']['median']:.2f} "
              f"| {h['plainGeneratedKiBPerChunk']['median']:.1f}, {h['lightingLitKiBPerChunk']['median']:.1f}, "
              f"{h['lightingLitWithPacketCacheKiBPerChunk']['median']:.1f} |")
    print()
    print("| job | relight wall ms (median / max) | relight CPU ms | gen heap KiB/chunk | relight heap delta MiB | lit total KiB/chunk | per-round wall ms |")
    print("|---|---|---|---|---|---|---|")
    for j in report["jobs"]:
        if j["params"]["type"] != "relight":
            continue
        rounds = ", ".join(f"{r['relightMs']:.0f}" for r in j["rounds"])
        print(f"| {j['job']} | {j['relightWallMs']['median']:.0f} / {j['relightWallMs']['max']:.0f} "
              f"| {j['relightCpuMs']['median']:.0f} | {j['generatedKiBPerChunk']['median']:.1f} "
              f"| {j['relightHeapDeltaMiB']['median']:.1f} | {j['litTotalKiBPerChunk']['median']:.1f} | {rounds} |")
    print()
    print("| job | kind | lazy 3x3 ms median / p95 / max | relight 3x3 ms median / p95 / max |")
    print("|---|---|---|---|")
    for j in report["jobs"]:
        if j["params"]["type"] != "local":
            continue
        for kind in j["lazy3x3Ms"]:
            a = j["lazy3x3Ms"][kind]
            b = j["relight3x3Ms"][kind]
            print(f"| {j['job']} | {kind} (n={a['n']}) | {a['median']:.1f} / {a['p95']:.1f} / {a['max']:.1f} "
                  f"| {b['median']:.1f} / {b['p95']:.1f} / {b['max']:.1f} |")
    print()
    print("| job | sections | single / indirect / direct | palette KiB | sky / block arrays | light KiB |")
    print("|---|---|---|---|---|---|")
    for j in report["jobs"]:
        b = j.get("breakdown")
        if not b:
            continue
        print(f"| {j['job']} | {b['sectionsPerChunk']} | {b['singleValueSectionsPerChunk']:.1f} / {b['indirectSectionsPerChunk']:.1f} / {b['directSectionsPerChunk']:.1f} "
              f"| {b['paletteValuesKiBPerChunk']:.1f} | {b['skyLightArraysPerChunk']:.2f} / {b['blockLightArraysPerChunk']:.2f} | {b['lightArraysKiBPerChunk']:.1f} |")


if __name__ == "__main__":
    main(sys.argv[1])
