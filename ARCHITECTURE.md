# NebulaKV — Architecture

## Overview

NebulaKV is a distributed key-value database. This document describes the target architecture and the reasoning behind each major design decision.

---

## Component Map

```
┌─────────────────────────────────────────────────────────┐
│                       CLIENT                            │
└──────────────────────┬──────────────────────────────────┘
                       │  TCP (binary protocol)
┌──────────────────────▼──────────────────────────────────┐
│                    COORDINATOR                           │
│  - Routes requests to the correct partition owner       │
│  - Coordinates quorum reads and writes                  │
│  - Manages N, R, W parameters                           │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              CONSISTENT HASH RING                        │
│  - Virtual nodes for balanced key distribution          │
│  - O(log N) partition lookup                            │
└──────────┬────────────────────────┬──────────────────────┘
           │                        │
    ┌──────▼──────┐          ┌──────▼──────┐
    │   NODE A    │          │   NODE B    │  ...
    │  (primary)  │          │  (replica)  │
    └──────┬──────┘          └─────────────┘
           │
    ┌──────▼──────────────────────────────────┐
    │            STORAGE ENGINE               │
    │                                         │
    │  PUT/GET/DELETE/EXISTS                  │
    │         ↓                               │
    │        WAL  (durability first)          │
    │         ↓                               │
    │     MemTable  (ConcurrentSkipListMap)   │
    │         ↓  (flush threshold)            │
    │      SSTable  (immutable, sorted)       │
    │         ↓  (periodic)                   │
    │     Compaction  (merge + GC)            │
    └─────────────────────────────────────────┘
```

---

## Storage Engine

### Write path

```
1. Append to WAL (fsync for durability)
2. Update MemTable
3. ACK to client
4. When MemTable hits threshold → flush to SSTable
5. Background compaction merges SSTables
```

### Read path

```
1. Check MemTable (newest data)
2. Check Bloom filter for each SSTable (skip if absent)
3. Binary search sparse index → seek data block
4. Return value or tombstone
```

---

## Consistency Model

NebulaKV targets **tunable consistency** (similar to Apache Cassandra):

- `N` — replication factor (number of replicas)
- `W` — write quorum (must acknowledge before ACK to client)
- `R` — read quorum (must respond before returning to client)

For strong consistency: `R + W > N`  
For high availability: allow `R = 1, W = 1`  

Default target: `N=3, W=2, R=2`

See [CONSISTENCY.md](CONSISTENCY.md) for full details.

---

## Cluster Membership

Nodes transition through states:

```
STANDALONE → JOINING → ACTIVE → SUSPECT → DOWN → LEAVING
```

Heartbeat-based failure detection. Phi-accrual or timeout threshold determines SUSPECT → DOWN.

---

## Failure Handling

| Scenario | Mechanism |
|---|---|
| Node crash during write | WAL replay on restart |
| Replica temporarily unavailable | Hinted handoff |
| Stale replica detected on read | Read repair (async) |
| Node joins/leaves ring | Partition rebalancing |

See [FAILURE_MODEL.md](FAILURE_MODEL.md) for full details.

---

## ADRs (Architecture Decision Records)

| ADR | Decision | Rationale |
|---|---|---|
| ADR-001 | Java NIO over Netty | Fewer abstractions; explicit buffer management teaches the real cost of I/O |
| ADR-002 | WAL before MemTable ACK | Durability guarantee; crash recovery without data loss |
| ADR-003 | SSTables over B-Tree | Write-optimized; immutability simplifies concurrency |
| ADR-004 | Bloom filters on SSTable | Avoid disk reads for keys known to be absent; false positive rate tunable |
| ADR-005 | Consistent hashing + vnodes | Balanced distribution; minimal data movement on topology changes |
| ADR-006 | Quorum reads/writes | Tunable consistency/availability trade-off per operation |
| ADR-007 | Async replication default | Lower write latency; strong consistency achievable via W quorum |
| ADR-008 | Spring Boot only for admin API | Storage engine must not depend on a web framework; admin is optional tooling |
| ADR-009 | Virtual threads (Phase 26) | Evaluate for blocking I/O workloads; benchmark before committing |
| ADR-010 | Sequence numbers over wall-clock | Wall-clock cannot resolve causality across nodes; sequence numbers are monotonic within a node |

---

## Non-Goals (Phase 1)

- No network layer yet
- No cluster membership
- No replication
- No WAL
- No SSTables

Phase 1 establishes the project skeleton only.
