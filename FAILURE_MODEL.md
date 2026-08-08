# NebulaKV — Failure Model

## Overview

Distributed systems fail. NebulaKV is designed to tolerate the following failure modes:

| Failure | Mechanism |
|---|---|
| Process crash mid-write | WAL replay on restart |
| Process crash mid-flush | SSTable is incomplete → WAL replay reconstructs MemTable |
| Process crash mid-compaction | Old SSTables remain valid; compaction resumes or restarts |
| Node temporarily unavailable | Hinted handoff; read repair on recovery |
| Node permanently down | Rebalancing redistributes partitions |
| Disk corruption | Checksums on WAL and SSTable entries |
| Network partition | Quorum-based availability vs consistency trade-off |

---

## Crash Recovery

### WAL Replay

On every startup:

```
1. Locate WAL file(s)
2. Read entries sequentially from last known stable checkpoint
3. Verify checksum for each entry
4. Skip corrupted tail (partial write at crash boundary)
5. Replay valid entries into MemTable
6. Resume normal operation
```

This guarantees that no acknowledged write is lost, because:
- The WAL is fsynced before ACK is sent to the client
- Replaying the WAL fully reconstructs in-flight MemTable state

### SSTable Integrity

SSTables are immutable. A crash during flush leaves an incomplete SSTable file. On restart:
- Incomplete SSTable files (no valid footer) are discarded
- The WAL re-hydrates the MemTable from its last flush point

### Compaction Safety

Compaction writes a new SSTable before deleting the inputs. If the process crashes during compaction:
- The new (partial) SSTable is discarded (no valid footer)
- The original input SSTables remain valid and intact
- Compaction restarts cleanly

---

## Failure Detection

### Heartbeat Mechanism

```
Every node sends periodic heartbeat messages to known peers.
Default interval: 1 second.
```

### Phi Accrual / Timeout Threshold

A node is marked SUSPECT when its heartbeat has not been received within a timeout window.

```
ACTIVE  → (missed heartbeats > threshold)  → SUSPECT
SUSPECT → (timeout exceeded)               → DOWN
DOWN    → (node rejoins)                   → JOINING → ACTIVE
```

A node marked DOWN triggers:
1. Partition ownership transfer (if node holds primary partitions)
2. Replication factor restoration (a new replica is designated)

---

## Hinted Handoff

### Purpose

Prevent write failures due to transient replica unavailability.

### Mechanism

```
1. Write arrives at Coordinator with N=3, W=2
2. Replica C is temporarily DOWN
3. Coordinator writes to Replicas A and B (W=2 satisfied → ACK to client)
4. Coordinator (or Replica A) stores a hint for Replica C:
     (node_id=C, key=K, value=V, seq=42, hint_time=T)
5. Hint is stored in a local hints table
6. When C recovers:
     - Coordinator or peers detect C is back (JOINING → ACTIVE)
     - Replays hints stored for C
     - Deletes hints after successful delivery
```

### Hint TTL

Hints expire after a configurable TTL (default: 3 hours). Expired hints are discarded. If a node is down longer than the hint TTL, it must undergo full repair (read repair or explicit resync) when it rejoins.

---

## Read Repair

### Purpose

Passively heal stale replicas during normal read traffic.

### Mechanism

```
1. Client reads with R=2
2. Coordinator queries N=3 replicas
3. Responses:
     Replica A: version=42, value="hello"
     Replica B: version=40, value="world"  ← stale
     Replica C: version=42, value="hello"
4. Coordinator returns version=42 to client immediately
5. Coordinator asynchronously sends version=42 to Replica B
6. Replica B updates via its normal write path (WAL → MemTable)
```

Read repair is **best-effort** and asynchronous. It does not block the client response.

---

## Rebalancing

### Trigger

- A new node joins the ring
- A node is permanently removed from the ring

### Mechanism

```
1. New node N' is inserted into the consistent hash ring
2. N' takes ownership of a contiguous range of token space
3. The previous owner(s) of that range transfer the affected SSTables/data
4. N' acknowledges receipt
5. The old owner(s) remove transferred data
6. Read traffic is cut over to N' once transfer is complete
```

Only the **affected partitions** are moved. Unaffected data stays in place. This is the key advantage of consistent hashing over naive modulo partitioning.

### Transfer Tracking

In-progress transfers are tracked in a durable transfer log. If a crash occurs mid-transfer, the transfer resumes from the last committed checkpoint on restart.

---

## Failure Scenarios Not Covered (Current Scope)

| Scenario | Status |
|---|---|
| Byzantine failures (malicious nodes) | Out of scope |
| Split-brain during extended network partition | Documented in CONSISTENCY.md |
| Hardware-level bit flips (beyond checksum detection) | Out of scope |
| Multi-datacenter replication | Post Phase 35 |

---

## Testing Plan

| Test | Phase |
|---|---|
| WAL replay after crash | Phase 5 |
| Corrupt WAL entry handling | Phase 24 |
| Node crash during PUT | Phase 18, 32 |
| Network partition simulation | Phase 32 |
| Hinted handoff delivery | Phase 19 |
| Read repair on stale replica | Phase 20 |
| Rebalancing on node join/leave | Phase 21 |
