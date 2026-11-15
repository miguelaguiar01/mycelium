# Mycelium — Design Spec

Nov 12, 2026

## Overview

Mycelium is a Fabric mod that lets a group of friends share one Minecraft world without a game server. Every member keeps a full replica. Three untrusted nodes from different operators hold the world's lease and an encrypted copy of its logs, and peers gossip whatever the others are missing.

Build details for implementation (stack, formats, contracts, milestones): Implementation guide

**Goals**

- No game server. The only infrastructure is untrusted nodes that anyone can run, and no single node matters.
- Any member can play at any time, alone or with others.
- Playing together feels like normal multiplayer: one host simulates, the others connect to it.
- No lost work. Concurrent solo sessions merge automatically, and true conflicts stay visible.

**Non-goals for v1**

- Public servers or untrusted players. There is no anti-cheat.
- Groups larger than about 10 members.
- Splitting the simulation of one world across several peers.
- Mixed Minecraft versions or mod sets inside one group.

**Design principles**

- **Local-first.** A member's own replica is the world; the network only moves changes.
- **One writer per log.** Each play session has exactly one writer, its host. Logs never need locks.
- **Determinism replaces consensus.** The same set of log entries always produces the same world on every peer.
- **Lease first.** A lease confirmed by a majority of the world's nodes prevents forks. Merge and rebase only handle the rare offline session.

## Architecture

Everything runs inside one Fabric mod, identical on every member's PC. The session logs are the source of truth; the world folder is derived from them.

&#91;embedded content: mod components · 8 parts, one network path\]

The host's integrated server writes changes through the capture hook into its session log. Gossip moves only missing log entries between peers, and each peer's merge engine rebuilds its own world from the logs it holds.

**On-disk layout**

```
.minecraft/mycelium/
  identity.key            peer key pair, one per installation
  <groupId>/
    group.json            members, encrypted group key
    replica/              materialized world the game loads
    logs/<sessionId>.log  signed entries, one file per session
    outbox/               entries no peer has acknowledged yet
    checkpoints/<id>/     world snapshot + its version vector
    conflicts.log         recorded true conflicts
```

## Data model

The world is a set of signed, append-only session logs. A replica is whatever those logs materialize to under the merge rules.

| Concept | Definition |
| --- | --- |
| Group | One shared world. `groupId` is a random 128-bit id. Membership is a list of member public keys signed by the founder. |
| Peer | One installation. Ed25519 key pair; `peerId` = hash of the public key, mapped to the player's Minecraft UUID. |
| Session | One continuous run of a host, solo or with guests. `sessionId` = (`peerId`, local counter). The host is the session's only writer. |
| Log entry | `{sessionId, seq, hlc, chunk, parents, payload, sig}`. `seq` is contiguous per session. Signed by the host. |
| Chunk version | Identified by (`sessionId`, `seq`). `parents` = the versions of that chunk the writer had applied when it captured the change. |
| Version vector | Map `sessionId` → highest contiguous `seq` held. Used to answer "what are you missing?" |
| Checkpoint | A materialized world snapshot at a given version vector. Lets old entries be dropped. |

**Payload types**

- `SectionState`: the full post-change state of each changed 16×16×16 section (block palette, block entities, biomes). Diffs are computed only at merge time, against the ancestor.
- `EntityState`: entities keyed by UUID, independent of which chunk they sit in.
- `PlayerState`: one player's data file.
- `LevelState`: `level.dat` fields (game time, weather, game rules, spawn).

**Chunk history is a DAG.** Each chunk's versions form a graph like git commits. A version with two parents is a merged state. The merge base of two heads is their lowest common ancestor.

**Hybrid logical clocks.** Every entry carries an HLC timestamp. "Newest wins" compares HLCs, then `sessionId`, so the order is the same on every peer even with skewed wall clocks.

**Checkpoints and pruning.** Each peer advertises its vector during gossip. The element-wise minimum across active members is the stable frontier. Any peer may write a checkpoint at that frontier, after which the entries it covers can be deleted. A member inactive for more than 30 days drops out of the frontier calculation and resyncs from a checkpoint when it returns.

## Nodes

Each world is backed by three untrusted nodes run by different operators. Together they hold the world's lease and an encrypted copy of its logs. A node never runs the game and cannot read the world.

**API**

| Call | Purpose |
| --- | --- |
| `POST /worlds` | Register a world: member public keys, signed by the founder |
| `POST /worlds/{id}/lease` | Propose a lease epoch; returns a signed acceptance or a refusal |
| `POST /worlds/{id}/lease/renew`, `/release` | Heartbeat every 30 s, and clean release |
| `PUT /worlds/{id}/entries` | Append signed, encrypted entries; rejected unless they carry the current epoch |
| `GET /worlds/{id}/entries?since=<vector>` | Pull missing entries |
| `PUT`, `GET /worlds/{id}/checkpoints` | Store and fetch snapshots |
| `GET /worlds/{id}/presence` | Current host and encrypted join address |
| WebSocket | Push "new entries" and "lease changed" notifications |

Requests are signed with member keys. Nodes keep no accounts and no personal data.

**Trust model**

- **Cannot read:** entries and checkpoints are encrypted with the group key.
- **Cannot forge:** entries are signed by members, and every member verifies them.
- **Can refuse service, lose data or lie about the lease.** Full member replicas cover the first two; the quorum below covers the third.

**Lease quorum (2 of 3)**

1. To take the lease, a peer proposes epoch N+1 to all three nodes.
2. A node accepts if N+1 is above every epoch it has seen and no unexpired lease exists. It returns a signed acceptance.
3. The peer holds the lease once 2 of 3 nodes accept, and attaches those acceptances as proof in `HOST_CLAIM`.
4. Members and guests accept a host only with a majority proof for the highest epoch they know.
5. Every append carries the current epoch. Nodes reject older epochs, so a host that froze and lost its lease can no longer write (fencing).
6. A lease expires 90 seconds after its last renewal, measured on each node's own clock.

If two peers split the votes, both back off for a random delay and retry. The world tolerates one node being offline or dishonest.

**Storage and retention.** A node keeps the latest checkpoint, the one before it, and all entries since. Idle worlds are kept for an operator-set period, 180 days by default, with warnings published through presence first.

**Moving a world.** A member publishes a signed "world moved" record naming the new node set and an epoch floor 1,000 above the highest epoch seen. It is stored on the old nodes, if reachable, and gossiped between peers. Members then upload from their replicas, so nothing is lost and no old lease can revive.

**Running a node.** One binary or Docker image, backed by SQLite and a folder of blobs; it runs on a Raspberry Pi. Operators set a storage quota, a maximum number of worlds, invite-only or public access, and retention. Optional extras: a connection relay when direct P2P fails, and acting as an always-on carrier (see Carrier network).

**Finding nodes.** A signed node directory, published in a public repository and announced on the DHT, lists each node's region, capacity, policy and uptime. When a world is created, the mod suggests three nodes from different operators. A group may also use a single node, such as a member's home server, accepting that the lease stops while it is down.

**Public node abuse controls.** Per-world quotas, rate limits, and a small proof-of-work to register a world.

## Lease and provisional sessions

Every session normally runs under the lease, so the world never forks and no special gameplay rules exist. A session without the lease is provisional, and is rebased onto the canonical history when it reconnects.

- **Canonical history** is the sessions that held the lease, in epoch order. They are always kept exactly as they happened.
- **A provisional session** is offered only when no node majority is reachable, with the warning "Play offline — your session may be adjusted when it syncs."
- **Rebase.** On reconnect, the provisional session's actions are replayed in order on top of the latest canonical world.

**Item-flow actions.** Provisional sessions record item movements alongside section states. Each action carries a precondition checked during rebase.

| Action | Precondition at rebase |
| --- | --- |
| Take N items from a container | The container still holds them |
| Break a block (and receive its drops) | The block is still that type |
| Pick up an item entity | The entity still exists |
| Craft, smelt or trade | The inputs exist after the earlier replayed actions |

**Corrections.** When a precondition fails, the items it produced are removed from the player: from the inventory first, then the ender chest, then any carried shulker box. If they were already turned into something else, that result is removed instead. The player sees a message such as "Your offline session conflicted with Ana's: 64 diamonds returned."

**Building changes** in a provisional session, such as placed blocks with no item conflict, merge with the 3-way merge below.

The result: no duplication and no ownership rules. Normal sessions play exactly like vanilla; the only exception is a rare, explained correction after a player knowingly chose to play offline.

## Peer discovery and transport

Peers find each other only while their game is open, through free shared infrastructure that no member runs.

| Layer | v1 choice | Fallback / later |
| --- | --- | --- |
| LAN discovery | UDP broadcast on the local network | — |
| Internet presence | Mainline DHT (BEP 44): one signed mutable item per group, keyed by the group key, listing online peers' contact info, encrypted | Mojang friends presence (the official friends / P2P flow) |
| Connection | Direct UDP with hole punching | e4mc tunnel |
| During play | Gossip rides the Minecraft connection as a custom payload channel `myceliump2p:gossip` | — |

- **Presence refresh.** An online peer republishes the DHT item every 10 minutes and removes itself on quit. Entries older than 15 minutes are ignored.
- **Encryption.** All gossip and the DHT item are encrypted with the group key. Entries are additionally signed by their session host.
- **The DHT stores only presence.** It never holds world data. BEP 44 items are limited to about 1 KB.

**With nodes.** A world's nodes are its primary presence service: they publish the current host and its encrypted join address. The DHT remains the fallback for provisional play and the carrier network.

## Gossip protocol

Any two connected peers exchange every entry the other is missing, including entries from other people's sessions. Changes therefore hop between members who never meet directly.

**Anti-entropy on connect**

1. Both peers send `HELLO` with their version vector.
2. Each computes, per session, the entries above the other's vector.
3. Each sends `WANT` for the ranges it lacks.
4. The other answers with `ENTRIES` batches, in `seq` order per session.
5. The receiver verifies signatures, writes the entries to disk, then sends `ACK` with its new vector.

Entries within a session are applied in contiguous `seq` order, so a replica never holds gaps. Entries from different sessions need no ordering between them.

**Live streaming during play.** The host appends each captured entry to its log and broadcasts it as `ENTRY` to every connected guest. Guests store it at once, so each guest leaves the session with the full log and becomes a carrier for it.

**Messages**

| Message | Sent by | Carries |
| --- | --- | --- |
| `HELLO` | both, on connect | `peerId`, `groupId`, version vector, latest checkpoint id, Minecraft version, mod version and mod-set hash |
| `WANT` | receiver | session ranges `(sessionId, fromSeq, toSeq)` |
| `ENTRIES` | sender | a batch of signed log entries |
| `ENTRY` | host, during play | one newly captured entry |
| `ACK` | receiver | updated version vector |
| `CHECKPOINT_OFFER` | either | checkpoint id, its vector, size; the peer may pull it instead of old entries |
| `HOST_CLAIM`, `HOST_YIELD`, `PING` | election | see Host election |

**Flush on quit.** After the final capture, the mod waits in the menu until at least one peer has acknowledged the latest entry, for up to 3 minutes, and shows the progress. If nobody is online, the entries stay in the local outbox and go out on the next connection.

**Compatibility gate.** Peers whose Minecraft version or mod-set hash differ may exchange presence but never entries.

## Host election and handoff

The online peers pick a host deterministically from what they all see. Two hosts at once is allowed: it produces two sessions that merge later, never corruption.

**When an election runs:** a peer wants to play, at least one other peer is online, and no `HOST_CLAIM` is live.

**Ranking.** Every online peer ranks candidates by the same key, highest first:

1. Wants to play right now.
2. Manual preference flag (for example, the member with the best upload).
3. Largest version vector (sum of `seq` across sessions).
4. Lowest `peerId` as the tiebreak.

**Claiming**

1. The winner pulls every entry the other online peers hold and materializes the merged world.
2. It opens a new session and broadcasts `HOST_CLAIM {sessionId, rank}`.
3. Other peers connect to it as normal Minecraft guests.
4. The host sends `PING` every 5 seconds. A claim with no `PING` for 15 seconds is dead.

**Two hosts.** If a peer sees two live claims, the lower-ranked host sends `HOST_YIELD`, closes its session after a final capture, and its players reconnect to the higher-ranked host. Both sessions remain valid and merge like any other concurrent sessions.

**Graceful handoff.** The host leaving sends `HOST_YIELD {next}`, naming the top-ranked remaining peer. Guests already hold every entry from live streaming, so the next host starts from its own replica with no download. Downtime is the time to load the world.

**Host crash.** Guests detect the missing `PING`, run an election among themselves and continue. Only changes the host had not yet captured are lost, at most one capture interval (30 seconds).

**With nodes.** The election only decides who tries first. The winner must take the lease from a majority of the world's nodes before sending `HOST_CLAIM`, and the claim carries that majority proof. A peer that cannot reach a majority may only start a provisional session. Two hosts at once is then impossible except for provisional sessions.

## Fine-grained 3-way merge

Concurrent versions of a chunk merge against their common ancestor, narrowing from chunk to section to block. A true conflict needs both sides to change the same block, slot or entity in different ways.

With the lease in place, forks only come from provisional sessions. Their building changes merge as described here; their item movements are checked and corrected by the rebase (see Lease and provisional sessions).

**Inputs.** For each chunk with more than one head in its version DAG: the heads A and B and their lowest common ancestor O. With three or more heads, merge pairwise in canonical order (ascending HLC, then `sessionId`).

**Cascade.** At each level, for (O, A, B):

1. A = O → take B. B = O → take A. A = B → take A.
2. Otherwise descend: chunk → each 16×16×16 section → each block.
3. At block level, if both sides changed the block differently, apply the noise rules below; if none applies, the newer HLC wins and the conflict is recorded.

**Rules by data type**

| Data | Merge rule | On true conflict |
| --- | --- | --- |
| Blocks | Per-block 3-way | Noise rule, else newest HLC wins; recorded in the conflict log |
| Container contents (chests, barrels, shulkers) | Per-slot 3-way | Resolved by rebase corrections, never by the merge |
| Other block entities (signs, furnaces, banners) | Whole block entity, 3-way | Newest wins |
| Entities (mobs, item frames, armour stands, items) | 3-way set merge by UUID, across chunk borders | Both modified → newest wins. Removed on one side, modified on the other → keep the modified one |
| Player data | Whole file per player, 3-way | Newest wins; the other version is kept as a backup file |
| Advancements, stats | Advancements: union. Stats: max per counter | — |
| `level.dat` | Game time: max. Weather, spawn: newest. Game rules: per rule, newest | — |
| Map items (`map_N.dat`) | Whole file | Newest wins |
| Raids, POI data | Discarded for the merged chunk, rebuilt by the game | — |

**Simulation noise rules.** Changes the game makes by itself must not count as conflicts:

- Growth (crop, sapling and cave vine age): take the higher stage.
- Machine progress (furnace, brewing stand, campfire): newest wins without recording a conflict.
- Fire, leaf decay and snow layers: newest wins without recording a conflict.
- Redstone power levels: take the newest; the game re-evaluates on the next tick.

**Items.** The merge never creates or removes items on its own. Duplication is prevented by the rebase corrections, which keep the canonical history and adjust the provisional player.

**Determinism.** The merge is a pure function of (O, A, B) with tiebreaks by HLC, then `sessionId`. Merge results are not logged. Each peer recomputes them, and identical inputs give identical worlds everywhere. The next session's entries list both heads as `parents`, which closes the fork in the DAG.

## Lifecycle flows

All sync happens while a member's game is open; nothing runs in the background after quit.

**Launch**

1. Load the peer identity and the group keys.
2. Publish presence (LAN broadcast, DHT item) and look up online peers.
3. Run anti-entropy with every online peer, then materialize and merge.
4. Show freshness on the world screen, for example "Last synced with Ana 2 days ago · 340 unsynced chunks".

**Play**

1. Pull missing entries from the world's nodes and any online peer.
2. A live, majority-backed `HOST_CLAIM` exists → join that host.
3. Otherwise, the online peers elect who tries first, and the winner takes the lease from a node majority and hosts, solo or with others.
4. No node majority reachable → offer a provisional offline session.

**During a session (host)**

1. Every 30 seconds, capture the sections changed since the last capture.
2. Append them as entries to the local outbox, and fsync before acknowledging.
3. Stream each entry to guests and to any other connected peer.

**Quit**

1. Final capture, then push to the nodes and to connected peers.
2. Release the lease once a node majority has acknowledged the latest entry.
3. Withdraw presence.

**Crash recovery.** The lease expires 90 seconds after the last renewal, and the next player continues from the last entry the nodes hold. Entries still in the crashed host's outbox go out on its next launch. Only changes in the last capture interval, at most 30 seconds, are lost.

**New member.** An invite token, shared out of band, carries the group key plus the founder's signature over the new member's key. The first sync pulls the latest checkpoint plus newer entries from any online peer.

## Guarantees, failure modes and trust

While a node majority is reachable, the world has a single, linear history with no forks and no duplication. Provisional offline sessions fall back to eventual consistency, with rebase corrections preventing duplication.

**Guarantees**

- **No forks under the lease.** Only one majority-backed host can write at a time, enforced by epoch fencing on the nodes.
- **No duplication.** Provisional sessions are rebased onto the canonical history, and failed item actions are corrected.
- **Convergence.** Peers holding the same entries materialize identical worlds.
- **Bounded crash loss.** At most one capture interval of 30 seconds, on the crashed host only.

**Not guaranteed**

- **Play while two of three nodes are down** is provisional only, and may be corrected when it syncs.
- **Exact gameplay outcomes in provisional sessions.** A rebased session can lose items it took, and merged building changes can produce combinations that existed in neither copy.

**Failure modes**

| Situation | Effect | Handling |
| --- | --- | --- |
| Host crashes mid-session | Up to 30 s of changes lost | Lease expires after 90 s; the next host continues from the nodes' entries |
| One node offline or dishonest | None | The other two form the majority |
| Two or three nodes offline | No lease available | Provisional sessions, rebased on reconnect |
| Node loses its data | Fewer copies | Members re-upload from replicas; move the world if the node stays unreliable |
| Votes split between two candidates | Brief delay | Random backoff and retry |
| Member offline for months | Their replica is far behind | Pull the latest checkpoint and entries from the nodes |
| Clock skew between peers | None for the lease | Lease expiry uses node clocks; HLCs order provisional merges |
| Mod or Minecraft version mismatch | Entries rejected | Compatibility gate in `HELLO`; upgrade together |

**Trust model**

- Members are trusted friends. A host can write anything into its session, as on any server.
- Nodes are untrusted. They cannot read or forge entries; the 2-of-3 quorum limits what a lying node can do.
- Non-members cannot read or inject: transport, node data and DHT records are encrypted with the group key, and entries need a valid member signature.
- Removing a member rotates the group key and re-registers the member list on the nodes. Copies they already hold cannot be recalled.

## Carrier network (phase 3)

Once enough groups use the mod, any user can opt in to carry other worlds' encrypted logs. Changes then reach a member even when the world's nodes and the other members are offline.

- **Placement.** A world's logs are stored on the k peers whose IDs are closest to `hash(worldId)` in the DHT, Kademlia-style. Retrieval is a lookup, not a broadcast.
- **Incentives.** Reciprocity, as in BitTorrent: the network carries your logs in proportion to how much you carry for others. Per-peer quotas and an expiry time bound the load.
- **Privacy.** Carriers see only blinded world IDs, padded sizes and timing. Content stays encrypted and signed.
- **Abuse.** Rate limits and proof-of-work against storage spam and fake identities. A carrier dropping data is harmless, because k copies exist.
- **Presence.** An encrypted "hosting world X" record spreads through the same network, so members find a live host without a node.

**Limits.** Carriers improve freshness and presence, not the guarantee: they cannot serialize writes, so without nodes the lease is soft. Carriers are other players with the game open, so their availability clusters in the same hours. The network only helps at scale.

Prior art: Secure Scuttlebutt (gossiped signed logs), Hyphanet (DHT storage), BitTorrent (reciprocity).

## Open questions and build plan

The chunk merge is the riskiest part, so it is built and proven first as a standalone tool.

**Open questions**

- Does the game rebuild POI and structure data cleanly after a merged chunk discards them, or does a rebuild step need to be forced?
- Entities that cross chunk borders during concurrent sessions: is per-UUID merging enough, or do some need special handling (vehicles, leashed mobs, passengers)?
- Capture cost: is serializing changed sections every 30 seconds cheap enough on a large base?
- Initial sync size for a new member with a large world, and whether checkpoints should be chunked for resumable transfer.
- Version upgrades: how the group moves to a new Minecraft version together, given the game's data upgraders.
- Mods that store their own world data outside region files.
- Which item flows can be recorded reliably for rebase (hoppers, villagers, item frames, dispensers), and which need extra hooks.
- Who signs a "world moved" record: the founder, any member, or a majority of members.
- Whether the 2-of-3 lease needs more than the simple majority rule to stay safe under message delays and node restarts, or a small proven protocol such as single-decree Paxos.

**Build plan**

1. **Merge tool (CLI).** Input: three copies of a world (ancestor, A, B). Output: one merged world plus a conflict report. Test on real forked worlds. Candidate library for reading `.mca` and NBT: Querz/NBT.
2. **Capture and log.** Fabric hook that emits section entries every 30 seconds into a local log, plus a materializer that rebuilds a world from a checkpoint and the log.
3. **Single node.** Lease with fencing, entry storage, checkpoints and presence. Run it on a member's home server.
4. **Gossip and live streaming.** Anti-entropy between peers, host claim and handoff, entries streamed over the game connection.
5. **Provisional sessions.** Item-flow recording, rebase and corrections.
6. **Three-node quorum.** Majority lease, log replication to all three nodes, world migration.
7. **Node directory and public nodes.** Signed directory, operator policies, abuse controls, optional connection relay.
8. **Carrier network.** DHT placement, reciprocity, blinded presence.

**Prior art**

- SharedWorld: rotating host with a central coordinator and S3 or Drive storage. This design replaces the single coordinator with a 2-of-3 quorum of untrusted nodes, which also hold the encrypted logs.
- Ink & Switch, "Local-first software": the log-per-writer, gossip and deterministic-merge pattern this design applies to Minecraft worlds.
