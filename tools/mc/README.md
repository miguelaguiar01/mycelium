# tools/mc

Scripts that drive a real Minecraft 26.3 dedicated server to build test worlds and check the format layer against the game. Python 3, standard library only.

They need a Java 25 runtime for the server. `mcserver.py` uses `MC_JAVA_HOME` if set, otherwise the newest `25.*` under `~/.sdkman/candidates/java`. The server jar is downloaded from Mojang's version manifest on first use and checked against its SHA-1. Starting the server writes `eula=true`, which accepts [Mojang's EULA](https://aka.ms/MinecraftEULA). Servers bind to `127.0.0.1:25599` only.

Everything is written under `.mc/` at the repo root, which is not checked in.

| Script | What it does |
| --- | --- |
| `mcserver.py` | Starts a server in a run directory, sends console commands and waits for them to finish |
| `build_probe_world.py` | Builds the probe worlds and their `expected.json` |
| `check_probe_world.py` | Asserts every claim of `docs/FORMAT-26.3.md` on the probe worlds, with its own Python reader |
| `probe_rebuild.py` | Strips heightmaps, light, POI and sections from a copy of a probe world and reports what the game rebuilds |
| `probe_units.py` | Asks the game where the other half of a door, bed, double chest and piston sits |
| `probe_game_facts.py`, `GameFacts.java` | Asks the game's own classes which blocks it ticks at random and what each copper block weathers into. The Java file runs against the unpacked server and copies nothing from it |
| `gen_rules.py` | Generates the built-in block table and vanilla rules file in `merge/src/main/resources` from the game's block report and the game facts. Fails if a block the game ticks at random is in no rule |
| `probe_postprocessing.py` | Checks that the game refreshes blocks listed in a chunk's post-processing list |
| `check_merged_world.py` | Builds a base world, plays two copies, merges them with `mycelium-merge` and loads the result in the game |
| `scenarios.py`, `scenariolib.py` | The merge scenarios of the spec: fork a world, play both copies, merge, load in the game, check. Results are in `docs/SCENARIOS.md` |
| `garden.py`, `pens.py` | The worlds of scenarios 13 to 16: a garden of everything the game changes by itself, and pens of animals, eggs and bees. They print what each side and the merge made of every plot and pen; `docs/TIME-EFFECTS.md` was written from that |
| `build_large_world.py` | Builds a large natural world in one dimension and two copies in which the game changed every chunk |
| `measure_merge.py` | Measures the run time of `mycelium-merge` on those worlds with a range of heap limits. Results are in `docs/MEASUREMENTS.md` |
| `profile_merge.py` | Samples the tool's threads during a merge of those worlds and says where the time goes |
| `check_large_merge.py` | Merges a 4-region cut of each large world and checks every chunk of two regions in the game. With `relit`, merges a cut in which most chunks are copied with `--relight all` and checks that the game gives every chunk its light back |
| `check_play_session.py` | Merges the three worlds of the play session both ways, reads the results and loads them in the game. The worlds were played by hand in the client; see `docs/SCENARIOS.md` |
| `install_play_fixture.py` | Cuts the play-session worlds down to the fixture `play-session`: player files, maps, the entity region with the boats and the two chunks with the barrels. Replaces the player's account UUID with a placeholder everywhere |
| `world_digest.py` | Prints one digest of everything a world folder holds once decompressed. Two runs of the same merge must print the same digest, on any machine |
| `install_fixtures.py` | Copies the probe worlds into `merge/src/test/resources/fixtures` |
| `check_rewritten_world.py` | Passes each fixture world through the Java codecs and loads the result in the game |
| `nbtlib.py`, `regionlib.py` | Small NBT and region readers used by the scripts. They share no code with the Java codec, so each checks the other |

`./gradlew :merge:mergeCheck --args="<base> <ours> <theirs> ours|theirs changed|neighbours|all <work folder>"` merges three worlds whole and in small pieces and compares the results as decoded data. It is how the worlds of `measure_merge.py` were used to check that dividing the work does not change the merged world.

`check_probe_world.py` and `build_probe_world.py` read the block report from the server's data generator. Create it once with:

```
cd .mc/server/26.3 && mkdir -p datagen && cd datagen && \
  java -DbundlerMainClass=net.minecraft.data.Main -jar ../server.jar --reports --output generated
```

Typical run, from the repo root:

```
python3 tools/mc/build_probe_world.py
python3 tools/mc/check_probe_world.py
python3 tools/mc/probe_rebuild.py
python3 tools/mc/install_fixtures.py
./gradlew :merge:test
python3 tools/mc/check_rewritten_world.py
```
