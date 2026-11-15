"""Sample the threads of mycelium-merge while it merges a large world, and say where the time goes.

The tool is run on a case of measure_merge.py, built from the worlds of build_large_world.py. Every
0.4 seconds its threads are dumped with jcmd, and each running worker is counted under what its stack
shows it doing. Sampling slows the run by about a third, so the shares matter, not the total.

    python3 tools/mc/profile_merge.py none|some|all ["<extra options>"] ["<JAVA_OPTS>"]

Results are in docs/MEASUREMENTS.md.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import time
from collections import Counter

from measure_merge import case_worlds, command

change, extra, env = sys.argv[1], sys.argv[2].split() if len(sys.argv) > 2 and sys.argv[2] else [], {}
if len(sys.argv) > 3:
    env["JAVA_OPTS"] = sys.argv[3]
case, worlds = case_worlds("overworld", 8, 8, change)
out = case / "out"
shutil.rmtree(out, ignore_errors=True)
start = time.time()
proc = subprocess.Popen(command(worlds, out) + extra, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                        env={**os.environ, **env})
GROUPS = [
    ("inflate (reading compressed chunks)", r"Inflater|InflaterInputStream|GZIPInputStream|LZ4.*(Decompress|InputStream)"),
    ("deflate (compressing output)", r"Deflater|DeflaterOutputStream|LZ4.*(Compress|OutputStream)"),
    ("comparing stored chunks", r"Plan\.(compare|same)|RawSlot|sameBytes|Arrays\.equals|ArraysSupport"),
    ("NBT parse", r"NbtIo\$Reader|NbtIo\.read"),
    ("NBT write", r"NbtIo\.write|NbtIo\$Writer|writePayload"),
    ("chunk decode (palettes, sections)", r"ChunkCodec\.decode|SectionCodec\.decode|PalettedContainer\.(decode|unpack|fromTag|read)|BlockState\.fromTag"),
    ("chunk encode (palettes, sections)", r"ChunkCodec\.encode|SectionCodec\.encode|PalettedContainer\.(encode|pack|toTag|of|write)|BlockState\.toTag"),
    ("cell merge", r"SectionMerge|ChunkMerge\.begin|Values\."),
    ("unit repair and refresh", r"UnitRepair|SeamRefresh|changedCells"),
    ("finish chunk (ticks, block entities, derived)", r"ChunkMerge\.(finish|blockEntities|ticks|postProcessing)|BlockEntityMerge"),
    ("file reads and writes", r"FileChannel|FileDispatcher|RegionWriter\.write|Files\.|BufferedOutputStream"),
]
counts, samples, states = Counter(), 0, Counter()
pid = None
while proc.poll() is None:
    if pid is None:
        jps = subprocess.run(["jps", "-l"], capture_output=True, text=True).stdout
        pid = next((line.split()[0] for line in jps.splitlines() if "mycelium" in line.lower()), None)
        time.sleep(0.2)
        continue
    dump = subprocess.run(["jcmd", pid, "Thread.print"], capture_output=True, text=True).stdout
    for block in dump.split("\n\n"):
        if '"mycelium-merge"' not in block and '"main"' not in block:
            continue
        frames = [l.strip()[3:] for l in block.splitlines() if l.strip().startswith("at ")]
        state = re.search(r"java.lang.Thread.State: (\w+)", block)
        if not frames or not state:
            continue
        states[state.group(1)] += 1
        if state.group(1) != "RUNNABLE":
            continue
        samples += 1
        text = "\n".join(frames[:14])
        for name, pattern in GROUPS:
            if re.search(pattern, text):
                counts[name] += 1
                break
        else:
            counts["other: " + frames[0].split("(")[0][-60:]] += 1
    time.sleep(0.4)
stdout, stderr = proc.communicate()
print(f"{change} {' '.join(extra)} {env}: {time.time() - start:.1f} s, exit {proc.returncode}")
print("thread states:", dict(states))
for name, n in counts.most_common(16):
    print(f"  {100 * n / samples:5.1f}%  {name}")
shutil.rmtree(out, ignore_errors=True)
