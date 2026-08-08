# NebulaKV — Consistency Model

## Overview

NebulaKV implements **tunable consistency** — the client can choose the trade-off between consistency and availability per operation.

This is the same model used by Apache Cassandra.

---

## Quorum Parameters

| Parameter | Meaning |
|---|---|
| `N` | Replication factor — how many nodes hold a copy |
| `W` | Write quorum — how many nodes must ACK a write |
| `R` | Read quorum — how many nodes must respond to a read |

### Consistency Condition

```
Strong consistency:  R + W > N
Eventual consistency: R + W ≤ N
```

### Default Target

```
N = 3   (3 replicas)
W = 2   (majority write)
R = 2   (majority read)
```

`R + W = 4 > N = 3` → strong consistency guaranteed.

---

## Write Path (Quorum)

```
1. Client sends PUT to Coordinator
2. Coordinator identifies N replicas via consistent hash ring
3. Sends write request to all N replicas in parallel
4. Waits for W acknowledgments
5. Returns success to client
6. Remaining (N - W) replicas complete asynchronously
```

If W nodes do not respond within timeout:
- Return error to client (write failed)
- No partial write is acknowledged

---

## Read Path (Quorum)

```
1. Client sends GET to Coordinator
2. Coordinator identifies N replicas
3. Sends read request to R replicas
4. Collects responses
5. Resolves conflicts (highest sequence number wins)
6. If stale replicas detected → schedules async read repair
7. Returns latest value to client
```

---

## Conflict Resolution

### Primary mechanism: Sequence Numbers

Each write carries a monotonically increasing sequence number assigned by the writing node.

On read conflict:
- Higher sequence number wins
- This is deterministic and requires no coordination

### Why not vector clocks?

Vector clocks detect concurrent writes more precisely but add complexity:
- Every write must carry a vector of per-node clocks
- Merging divergent values may require application-level resolution

For this implementation, sequence-number resolution is the default. Vector clocks can be added as an optional Phase 17 enhancement.

### Why not wall-clock timestamps?

Wall-clock time is unreliable across distributed nodes:
- NTP synchronization is imprecise (~100ms typical error)
- Clocks can move backwards during corrections
- Two nodes can assign identical timestamps

Sequence numbers are monotonic within a node and unambiguous for ordering.

---

## Consistency Guarantees by Configuration

| R | W | N | Guarantee |
|---|---|---|---|
| 2 | 2 | 3 | Strong consistency |
| 1 | 3 | 3 | Strong consistency (all replicas must ack) |
| 1 | 1 | 3 | Eventual consistency (highest availability) |
| 3 | 1 | 3 | Strong read, weak write |

---

## Read Repair

When a read response reveals that one or more replicas hold a stale value:

```
1. Return the latest value to the client immediately
2. Asynchronously send the latest value to stale replicas
3. Stale replicas update their MemTable/WAL with the corrected value
```

This is a best-effort background healing mechanism.

---

## Hinted Handoff

If a write's intended replica is temporarily unavailable:

```
1. The Coordinator (or another available node) stores a "hint"
2. Hint = (intended_node_id, key, value, sequence_number, timestamp)
3. When the intended node recovers and rejoins, hints are replayed
4. Hints expire after a configurable TTL to prevent unbounded accumulation
```

This mechanism prevents write failures caused by transient node unavailability, at the cost of temporarily relaxed consistency.

---

## CAP Theorem Position

NebulaKV is designed as a **CP system by default** (consistent + partition-tolerant) with the option to trade toward AP (available + partition-tolerant) by reducing W and R.

| Mode | R+W vs N | CAP |
|---|---|---|
| Default (R=2, W=2, N=3) | R+W > N | CP |
| Relaxed (R=1, W=1, N=3) | R+W < N | AP |

---

## Current Status

Consistency mechanisms are implemented in phases 16–20.
