"""Measure the time and memory mycelium-merge needs on the worlds of build_large_world.py.

Cases are cut from the three large worlds of a dimension by region file, so nothing is rebuilt:

  size     the first 2x2 or 4x4 regions of the dimension, or all of it
  none     neither side changed anything: ours and theirs are the base
  some     each side played in two regions, one of them the same region
  all      both sides changed every chunk

Each case runs once with the JVM's default heap limit, which is a quarter of the machine's
memory, and then with smaller and smaller limits (-Xmx) until the tool no longer finishes. The
smallest limit it finishes with is the memory figure. The tool divides its work to fit the limit
it is given, so a smaller limit can make it slower.

    python3 tools/mc/measure_merge.py [--dimension overworld|nether|end] [--whole 8] [size ...]

--whole is the number of regions per side the dimension was built with. Sizes default to 2, 4,
6 and the whole. Results are printed and written to .mc/measure/results.json.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import struct
import subprocess
from pathlib import Path

from build_large_world import DIMENSIONS, run_dir
from mcserver import MC_HOME
from scenariolib import TOOL

WORK = MC_HOME / "measure"
STORAGE = ("region", "entities", "poi")
HEAPS_MB = [None, 4096, 2048, 1024, 512, 256, 128, 64]    # None is the JVM's default
REGION_NAME = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca$")


def folder(dimension: str) -> Path:
    """Where a dimension's storage is, inside a world folder."""
    namespace, path = DIMENSIONS[dimension][0].split(":")
    return Path("dimensions") / namespace / path


def visited(side: str, size: int) -> set[tuple[int, int]]:
    """The regions a side played in, in the `some` cases."""
    if side == "ours":
        return {(0, 0), (1, 0)}
    return {(1, 0), (1, 1)} if size == 2 else {(1, 0), (2, 0)}


def clone(source: Path, target: Path) -> None:
    """Copy a file; on APFS this shares the blocks instead of copying them."""
    target.parent.mkdir(parents=True, exist_ok=True)
    if subprocess.run(["cp", "-c", str(source), str(target)], capture_output=True).returncode != 0:
        shutil.copy2(source, target)


def compose(target: Path, own: Path, base: Path, dim: Path, keep, own_regions) -> Path:
    """A world made of `own`'s files, with the chunk storage of the regions outside `own_regions` from `base`.

    `own_regions` is a set of region positions, or None for all of them. `keep(rx, rz)` says
    which regions of the dimension `dim` are in the world at all.
    """
    if target.exists():
        shutil.rmtree(target)
    for file in sorted(own.rglob("*")):
        relative = file.relative_to(own)
        measured = relative.parts[:3] == dim.parts and len(relative.parts) > 3 and relative.parts[3] in STORAGE
        if file.is_file() and file.name != "session.lock" and not measured:
            clone(file, target / relative)
    for storage in STORAGE:
        names = {f.name for world in (own, base) for f in (world / dim / storage).glob("r.*.mca")}
        for name in sorted(names):
            rx, rz = map(int, REGION_NAME.match(name).groups())
            source = own if own_regions is None or (rx, rz) in own_regions else base
            if keep(rx, rz) and (source / dim / storage / name).exists():
                clone(source / dim / storage / name, target / dim / storage / name)
    return target


def chunk_count(world: Path, dim: Path) -> int:
    count = 0
    for file in (world / dim / "region").glob("r.*.mca"):
        with open(file, "rb") as stream:
            header = stream.read(4096)
        if len(header) == 4096:
            count += sum(1 for location in struct.unpack(">1024I", header) if location)
    return count


def megabytes(path: Path) -> float:
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file()) / 1e6


def command(worlds: dict[str, Path], out: Path) -> list[str]:
    if out.exists():
        shutil.rmtree(out)
    return [str(TOOL), "--base", str(worlds["base"]), "--ours", str(worlds["ours"]), "--theirs", str(worlds["theirs"]),
            "--newer", "theirs", "--out", str(out / "world"), "--report", str(out / "report.json")]


def finished(stdout: str, stderr: str, code: int) -> bool:
    # Running out of memory exits with 3. The first build ended with the JVM's 1, so the text is still looked at.
    return code in (0, 1) and "merged into" in stdout and "OutOfMemoryError" not in stderr


def timed_run(worlds: dict[str, Path], out: Path, heap_mb: int | None, timeout: float | None = None) -> dict:
    """One run under /usr/bin/time -l, with the JVM's default heap limit if none is given."""
    env = {**os.environ, "JAVA_OPTS": f"-Xmx{heap_mb}m" if heap_mb else ""}
    try:
        result = subprocess.run(["/usr/bin/time", "-l", *command(worlds, out)], capture_output=True, text=True,
                                env=env, timeout=timeout)
    except subprocess.TimeoutExpired:
        return {"heap_mb": heap_mb, "ok": False, "why": f"not finished after {timeout:.0f} s"}
    times = re.search(r"([\d.]+) real\s+([\d.]+) user\s+([\d.]+) sys", result.stderr)
    rss = re.search(r"(\d+)\s+maximum resident set size", result.stderr)
    ok = finished(result.stdout, result.stderr, result.returncode)
    return {"heap_mb": heap_mb, "ok": ok, "exit": result.returncode,
            "why": "" if ok else ("OutOfMemoryError" if "OutOfMemoryError" in result.stderr else result.stderr[-300:]),
            "wall_s": float(times.group(1)), "cpu_s": float(times.group(2)) + float(times.group(3)),
            "rss_mb": int(rss.group(1)) / 1e6, "summary": result.stdout.strip().splitlines()[:1]}


def case_worlds(dimension: str, size: int, whole: int, change: str) -> tuple[Path, dict[str, Path]]:
    """Cut the three worlds of one case out of the large worlds. Returns the case folder and the worlds."""
    case = WORK / f"{dimension}-{size}-{change}"
    dim = folder(dimension)
    base_world = run_dir(dimension, "base") / "world"

    def keep(rx: int, rz: int) -> bool:
        # The whole dimension includes the unfinished chunks the game left around the square.
        return size == whole or (0 <= rx < size and 0 <= rz < size)

    base = compose(case / "base", base_world, base_world, dim, keep, None)
    worlds = {"base": base}
    for side in ("ours", "theirs"):
        if change == "none":
            worlds[side] = base
        else:
            worlds[side] = compose(case / side, run_dir(dimension, side) / "world", base_world, dim, keep,
                                   None if change == "all" else visited(side, size))
    return case, worlds


def measure(dimension: str, size: int, whole: int, change: str) -> dict:
    case, worlds = case_worlds(dimension, size, whole, change)
    base, dim = worlds["base"], folder(dimension)
    out = case / "out"
    result = {"dimension": dimension, "size": size, "change": change, "chunks": chunk_count(base, dim),
              "base_mb": round(megabytes(base))}
    runs = []
    for heap_mb in HEAPS_MB:
        runs.append(timed_run(worlds, out, heap_mb, 3600))
        if not runs[-1]["ok"]:
            break
        if heap_mb is None:
            report = json.loads((out / "report.json").read_text())
            result.update(out_mb=round(megabytes(out / "world")), entries=len(report["entries"]),
                          report_mb=round((out / "report.json").stat().st_size / 1e6, 1))
    if out.exists():
        shutil.rmtree(out)
    result["runs"] = runs
    passed = [run for run in runs if run["ok"]]
    print(f"{dimension} {size}x{size} regions, {change:4}: {result['chunks']:6} chunks, {result['base_mb']:4} MB | "
          + " | ".join(f"{run['heap_mb'] or 'default'}: " + (f"{run['wall_s']:.1f} s, {run['rss_mb']:.0f} MB" if run["ok"]
                                                              else run["why"][:40]) for run in runs)
          + (f" | report {result['entries']} entries" if passed else ""), flush=True)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dimension", choices=sorted(DIMENSIONS), default="overworld")
    parser.add_argument("--whole", type=int, default=8, help="regions per side the dimension was built with")
    parser.add_argument("sizes", nargs="*", type=int)
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    results_file = WORK / "results.json"
    results = json.loads(results_file.read_text()) if results_file.exists() else []
    for size in args.sizes or sorted({s for s in (2, 4, 6, args.whole) if s <= args.whole}):
        for change in ("none", "some", "all"):
            result = measure(args.dimension, size, args.whole, change)
            key = (args.dimension, size, change)
            results = [r for r in results if (r["dimension"], r["size"], r["change"]) != key] + [result]
            results_file.write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
