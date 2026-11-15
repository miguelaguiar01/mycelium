"""Ask the game's own classes what changes blocks over time.

Runs GameFacts.java against the 26.3 server's classes and writes the answer to
.mc/server/26.3/probe/game_facts.json: for every block the class behind it, whether the
game ticks it at random, and the game's tables of copper weathering and waxing.
gen_rules.py reads the copper tables from there, and docs/TIME-EFFECTS.md was written from
the list of ticking blocks.

The server must have been started once, so that its jar is unpacked under
.mc/server/26.3/bundle.

    python3 tools/mc/probe_game_facts.py
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from mcserver import MC_HOME, MC_VERSION, find_java

SERVER = MC_HOME / "server" / MC_VERSION
OUT = SERVER / "probe" / "game_facts.json"


def main() -> None:
    bundle = SERVER / "bundle"
    jars = sorted(bundle.glob("versions/*/*.jar")) + sorted(bundle.glob("libraries/**/*.jar"))
    if not jars:
        raise SystemExit(f"no unpacked server under {bundle}; start a server once first")
    source = Path(__file__).with_name("GameFacts.java")
    result = subprocess.run([find_java(), "-cp", ":".join(map(str, jars)), str(source)],
                            capture_output=True, text=True, cwd=SERVER)
    start = result.stdout.find("{")
    if result.returncode != 0 or start < 0:
        raise SystemExit(f"GameFacts failed:\n{result.stdout[-2000:]}\n{result.stderr[-2000:]}")
    facts = json.loads(result.stdout[start:])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(facts, indent=1, sort_keys=True) + "\n")
    blocks = facts["blocks"]
    ticking = [name for name, block in blocks.items() if block["random_tick_states"]]
    print(f"{len(blocks)} blocks, {len(ticking)} ticked at random, {len(facts['copper_next'])} copper steps, "
          f"{len(facts['waxed'])} waxed blocks -> {OUT.relative_to(MC_HOME.parent)}")


if __name__ == "__main__":
    main()
