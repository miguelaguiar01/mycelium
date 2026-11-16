# AGENTS.md — working in Mycelium

How to contribute to this repo, for coding agents and the people running them. Read it before you change anything. When it disagrees with a habit of yours, it wins.

## What Mycelium is

A Fabric mod for Minecraft Java 26.3 that lets a small group of friends share one world without a rented server. One member's game hosts the world at a time. The world is checkpointed as encrypted file blocks to a store, and another member takes over when the host leaves or crashes.

**Read the design before you design.** The repo holds two specs, and one of them is partly out of date:

- `SPEC.md` is the original local-first design: full replicas, logs and merging in normal play. The team has since adopted a combined design. Use `SPEC.md` for background only.
- `CHUNK-MERGE-SPEC.md` specifies the chunk merger, which is built and parked. See The merger is parked, below.

The combined design is not in the repo yet. Until it is, these decisions bind every change:

| Topic | Decision |
| --- | --- |
| Core model | One authoritative host. Normal play never merges |
| Storage | Encrypted, content-named file blocks plus a signed manifest, behind a `Store` interface. One R2 bucket for the proof of concept; Google Drive may follow as bring-your-own storage |
| Lease | Compare-and-swap on the store, with epochs. Solo play is allowed |
| Offline play | Not allowed, so there are no branches by design. If a fork ever appears, the merger is the safety net |
| Crash loss | Under a minute for the proof of concept; a few seconds once the warm standby exists |
| Hosting | Integrated in the host's game, if the proof of concept shows it holds frame rate and latency; a dedicated server otherwise |
| Relays | Member relays only for the proof of concept |
| Mods | None in the proof of concept. Fabric without other mods |
| Credentials | Each member holds a scoped store key. No service holds secrets |
| Release | No public release soon |

## Repo layout

| Path | What it holds |
| --- | --- |
| `merge/`, `docs/` | The chunk merger and its evidence. Parked |
| `tools/mc/` | Python scripts that drive a real 26.3 server on this machine. Today they serve the merger; see `tools/mc/README.md` |
| `.mc/` | Server jars and run folders made by `tools/mc`. Ignored; never commit it |

Epic E1 turns this into one repo with `core` and `mod` modules next to the merger. Update this table when it lands.

## The merger is parked

The chunk merger in `merge/` is built, tested and not needed by the build plan until epic E10, its safety-net role. Until then:

- **Do not change `merge/`,** its rules and fixtures, or the merger's `docs/` and `tools/mc` scripts, unless your ticket belongs to E10 or the developer asks for it.
- **Do not build on it** from new modules. Nothing in the proof of concept merges worlds.
- **When it is picked up again,** `CHUNK-MERGE-SPEC.md`, `docs/` and `tools/mc/README.md` hold its rules, evidence and game checks. Read them first.

## Toolchain and commands

- **Java 25** for all new code, as Minecraft 26.3 requires. The parked merger stays on Java 21 until it is picked up again.
- **Gradle 9.9.1** through the wrapper. Always run `./gradlew`, never a system Gradle.
- **Python 3**, standard library only, for `tools/mc`.
- Compiler warnings are errors (`-Xlint:all -Werror`). Fix the warning; do not suppress it without a comment saying why.

```
./gradlew build                  # compile, test, check
./gradlew spotlessApply          # format the code (once E1 adds Spotless)
```

**Formatting.** Spotless with Palantir Java Format, enforced by `./gradlew build`. Run `spotlessApply` before committing. Until E1 adds it, match the surrounding code.

**Mappings.** Which names the mod uses for Minecraft's code is decided in E1, while building the mod skeleton. Record the decision here when it is made.

## How work flows

All work comes from the Mycelium project board, linked from the repo's Projects tab. Each epic is an issue labelled `epic`; its tickets are sub-issues. Phases are milestones.

1. **Take one ticket.** Move it to In Progress and assign it to the developer you work for.
2. **Branch per ticket** from `main`, named `<issue>-<short-slug>`, such as `27-r2-conditional-writes`.
3. **Commit** in Conventional Commits with a scope: `feat(store): split world files into 16 KiB blocks`. Scopes follow the module or area: `store`, `lease`, `host`, `net`, `mod`, `tools`, `docs`, `build`, and `merge` once the merger is picked up again.
4. **Open a draft pull request** to `main`. Its title is a Conventional Commit, because it becomes the commit on `main`. Its description says `Closes #<issue>`, what changed, how it was tested, and any spec change.
5. **Get it reviewed and merged** as Reviews below says. Nobody pushes to `main` directly.

Use whichever git client the checkout uses. One developer works with GitButler: if the repo has a `gitbutler/` branch, make branch and commit changes through `but`, not plain git.

## What agents may do

- **Stay on the ticket.** Change only what the ticket needs. Anything else you find becomes a new issue, or a note in the pull request. Never fold it into the change.
- **Git and GitHub.** Commit on your ticket branch, push it, and open a draft pull request. Merge only a small pull request, as Reviews below allows. Never force-push a shared branch or rewrite `main`.
- **Issues and the board.** You may create, split, comment on, move and close tickets when the work calls for it. Say what you did and why in a comment on the ticket.
- **Designed behaviour.** If a change would contradict a spec or a decision above, stop and ask the developer. Once they approve, update the spec in the same pull request and say so in its description.
- **Run the game locally.** `tools/mc` scripts may start a 26.3 server. They bind to `127.0.0.1` only and keep everything under `.mc/`. Starting one accepts Mojang's EULA on the developer's behalf, as `tools/mc/README.md` says.

## Reviews

Every pull request gets an agent review. Most also need the other developer; small ones do not.

**Agent review.** A fresh agent session reviews the diff: one that did not write the change, such as Claude Code's `/code-review`. It looks for bugs first, then for missing tests, spec drift, determinism and personal data. The author fixes every finding or answers it in the pull request.

**Ready for review.** The authoring agent marks the draft ready once all of these hold:

- `./gradlew build` passes, with all tests.
- The game checks the change calls for have run, and their results are in the description.
- The agent review is clean, or each finding is fixed or answered.

**Small pull requests** may merge after a clean agent review alone, and the authoring agent may merge them itself. A pull request is small only if every change in it is one of these:

- Docs, comments or typos, with no change in behaviour.
- Tests only, with no change to main code.
- Build and tooling: Gradle, CI, or `tools/mc` scripts that do not change shipped code.
- Code under about 100 changed lines that touches none of the core: the store, manifest, lease, host handoff, encryption and keys.

When in doubt, it is not small.

**Everything else** also needs the other developer's approval before it merges. The other developer merges it.

**Review comments.** The author pushes a fix for each comment, or replies why not, and replies with what changed. The reviewer resolves the thread, not the author.

**Merging** is always a squash merge. `main` gets one Conventional Commit per ticket, and the ticket closes through `Closes #<issue>`.

## Code rules

- **Tests come with every behaviour change.** A bug fix starts with a test that fails without it. New behaviour gets tests. Where an invariant exists, such as determinism or "no item is invented", add a property test with jqwik.
- **Before a pull request:** `./gradlew build` passes with all tests. If the change depends on how the game behaves, such as saving, hosting or transfers, check it against a real 26.3 server or client and report the results in the pull request.
- **Game code stays out of the repo.** Never commit Minecraft jars, decompiled code or copied game assets. Read game facts at run time.
- **Verify the game, don't remember it.** A claim about how 26.3 behaves or stores its files is checked against the real game and written down with how it was checked. 26.x is new, and memory of older versions misleads.

## Personal data and secrets

**No personal data of anyone, ever.** That means no Minecraft account UUIDs, player names, emails, IP addresses, home-directory paths or tokens, in any file, file name, fixture, log, screenshot or commit message.

- Fixtures cut from played worlds are scrubbed by the script that makes them, and the script fails if a trace is left. `tools/mc/install_play_fixture.py` is the pattern.
- Before committing, search everything you add, including decompressed NBT and region files. A UUID in a binary fixture is still a UUID.
- The repo is public. Anything committed is published.

**Secrets** come from environment variables. For local runs, put them in a `.env` file at the repo root, which git ignores. When code needs a new secret, add its name with an empty value to `.env.example`. Never log a secret, never print one in test output, and never paste one into an issue or a pull request.

## Writing

Docs, specs, pull requests and issue comments match the style of the specs:

- Plain English. Short sentences, one idea each.
- Lead with the result or the decision, then the detail.
- Tables to compare things; numbered lists for steps.
- Numbers with units, and where they came from.
- No filler, no hype, no restating what the reader just read.
