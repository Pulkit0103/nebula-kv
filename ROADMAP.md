# NebulaKV — Implementation Roadmap

Each phase is self-contained: implement → test → verify → commit → push → stop.

## Status Summary

v0.1.0 — All 35 phases complete. 218 tests passing.  
v0.2.0 — All 40 phases complete. 277 tests passing.

---

## v0.2.0 Phases

### Phase 36 — TTL / Key Expiration
`feature/ttl`  
Per-key expiry. `PUT key value ttl_ms`. Background sweeper removes expired entries from MemTable and propagates tombstones to SSTable on flush. `TTL key` returns remaining ms or -1.

### Phase 37 — Batch Operations
`feature/batch`  
Atomic `MPUT` / `MGET` / `MDELETE`. Single WAL entry per batch. All-or-nothing semantics on the local node.

### Phase 38 — Prefix / Range Scan
`feature/scan`  
`SCAN prefix` returns all matching keys. `SCAN_RANGE from to` returns keys in lexicographic range. Merges live MemTable and SSTables in one pass.

### Phase 39 — RESP2 Protocol (Redis wire compat)
`feature/resp`  
Parse the Redis Serialisation Protocol so `redis-cli -p 7777` works against NebulaKV. Supports GET, SET (→ PUT), DEL, EXISTS, MGET, MSET.

### Phase 40 — Java Client Library
`feature/client`  
Thin blocking client: `NebulaClient.connect(host, port)` with `put`, `get`, `delete`, `exists`, `mget`, `mput`, `scan`. Auto-reconnect. Published as `nebula-kv-client` artifact.

---

## Phase 1 — Bootstrap ✅

**Branch:** `feature/bootstrap`  
**Status:** Complete

- Initialize Maven project (Java 21)
- Create documentation skeleton
- Configure `.gitignore`
- Minimal executable (`NebulaKV.java` prints version/system info)
- Sanity unit tests (SystemInfo, NodeInfo, NodeStatus)
- `mvn test` passes

---

## Phase 2 — In-Memory KeyValueStore

**Branch:** `feature/in-memory-store`

Implement a thread-safe in-memory key-value store backed by `ConcurrentHashMap`.

Operations:
- `put(key, value)`
- `get(key) → Optional<String>`
- `delete(key)`
- `exists(key) → boolean`

Tests:
- Basic CRUD
- Overwrite
- Delete non-existent key
- Concurrent reads and writes

---

## Phase 3 — Command Protocol

**Branch:** `feature/command-protocol`

Define the binary wire protocol for NebulaKV commands.

Commands:
- `PUT key value`
- `GET key`
- `DELETE key`
- `EXISTS key`

Request/response models using `ByteBuffer`. No Java serialization.

---

## Phase 4 — TCP Networking

**Branch:** `feature/tcp-networking`

TCP server using Java NIO (`ServerSocketChannel`, `Selector`).

Architecture:
```
Client → TCP → KVServer → StorageEngine
```

Tests:
- Single-client put/get/delete
- Multiple concurrent clients
- Malformed request handling

---

## Phase 5 — Write-Ahead Log

**Branch:** `feature/wal`

Durable WAL using `FileChannel` + `ByteBuffer`.

Guarantee:
```
WAL durable (fsync) → MemTable update → ACK
```

Crash recovery via WAL replay on startup.

Tests:
- Append and replay
- Crash simulation (truncated WAL)
- Checksum verification
- Replay correctness after restart

---

## Phase 6 — MemTable

**Branch:** `feature/memtable`

`ConcurrentSkipListMap`-backed in-memory buffer.

Track:
- Memory size in bytes
- Entry count
- Sequence numbers (monotonically increasing)

Flush to SSTable when threshold is reached.

---

## Phase 7 — SSTables

**Branch:** `feature/sstable`

Immutable sorted disk files.

Format:
- Header (magic, version, entry count)
- Data blocks (sorted key-value-seq-flags)
- Sparse index
- Footer (offsets, checksum)

Tests:
- Write and read back
- Multiple SSTables
- Key not found

---

## Phase 8 — Bloom Filters

**Branch:** `feature/bloom-filter`

Probabilistic set membership using double-hashing.

Embedded in each SSTable. Checked before disk seek.

Tests:
- No false negatives
- Configurable false positive rate
- Serialization/deserialization

---

## Phase 9 — SSTable Sparse Indexes

**Branch:** `feature/sparse-index`

Index every Nth key → file offset. Binary search to find block containing target key.

Measure: read latency before vs after index.

---

## Phase 10 — Compaction

**Branch:** `feature/compaction`

K-way merge of SSTables. Remove obsolete versions and tombstones.

Tests:
- Multiple versions of same key → latest survives
- Tombstones removed after compaction
- Input SSTables remain valid until compaction completes

---

## Phase 11 — Versioned Records

**Branch:** `feature/versioning`

Sequence numbers on every write. Sequence numbers assigned atomically per node.

Tests:
- Concurrent writes produce distinct sequence numbers
- Higher sequence number wins conflict resolution

---

## Phase 12 — Consistent Hashing

**Branch:** `feature/consistent-hashing`

Token ring with virtual nodes.

Operations:
- Add node to ring
- Remove node from ring
- Lookup: key → responsible node(s)

Tests:
- Key distribution across nodes
- Minimal remapping on node add/remove

---

## Phase 13 — Node Membership

**Branch:** `feature/membership`

Node lifecycle: STANDALONE → JOINING → ACTIVE → SUSPECT → DOWN → LEAVING

Heartbeat mechanism. Timeout-based failure detection.

Tests:
- Node join
- Node leave (graceful)
- Node crash detection

---

## Phase 14 — Coordinator

**Branch:** `feature/coordinator`

Route requests to the correct primary and replicas based on consistent hash ring.

---

## Phase 15 — Replication

**Branch:** `feature/replication`

Configurable replication factor N. Asynchronous replication to N-1 replicas after primary write.

---

## Phase 16 — Quorum Reads/Writes

**Branch:** `feature/quorum`

N, R, W parameters. Configurable per cluster (and optionally per operation).

Default: N=3, R=2, W=2.

---

## Phase 17 — Conflict Resolution

**Branch:** `feature/conflict-resolution`

Sequence-number-based. Higher sequence number wins. Optionally explore vector clocks.

---

## Phase 18 — Failure Detection

**Branch:** `feature/failure-detection`

Heartbeats, phi-accrual or timeout threshold. Node state transitions.

Test: crash a node, observe detection time.

---

## Phase 19 — Hinted Handoff

**Branch:** `feature/hinted-handoff`

Store hints for unavailable replicas. Replay on recovery.

---

## Phase 20 — Read Repair

**Branch:** `feature/read-repair`

Detect stale replicas during quorum reads. Asynchronously repair.

---

## Phase 21 — Rebalancing

**Branch:** `feature/rebalancing`

Transfer affected partitions when nodes join or leave.

---

## Phase 22 — Gossip Membership

**Branch:** `feature/gossip`

Simplified gossip protocol for cluster state propagation.

---

## Phase 23 — Snapshots

**Branch:** `feature/snapshots`

Periodic state snapshots. On restart: load snapshot → replay WAL from snapshot point.

---

## Phase 24 — Checksums

**Branch:** `feature/checksums`

CRC32 or similar on WAL entries and SSTable blocks. Detect and handle corruption.

---

## Phase 25 — Concurrency Optimization

**Branch:** `feature/concurrency`

Stress tests. Lock contention analysis. Race condition investigation.

---

## Phase 26 — Virtual Thread Evaluation

**Branch:** `feature/virtual-threads`

Benchmark platform threads vs virtual threads for network/blocking I/O paths.

Do not switch unless benchmarks justify it.

---

## Phase 27 — Observability

**Branch:** `feature/observability`

Prometheus metrics. Grafana dashboards. OpenTelemetry traces.

Metrics: requests, latency, replication lag, quorum failures, WAL recovery, compaction, read repair.

---

## Phase 28 — Administrative API

**Branch:** `feature/admin-api`

Spring Boot admin layer only.

Endpoints:
- `GET /admin/nodes`
- `GET /admin/partitions`
- `GET /admin/health`
- `GET /admin/storage`
- `POST /admin/compaction`
- `POST /admin/rebalance`

---

## Phase 29 — Docker Compose

**Branch:** `feature/docker`

Local multi-node cluster: Coordinator, Node 1-3, Prometheus, Grafana.

---

## Phase 30 — Kubernetes

**Branch:** `feature/kubernetes`

StatefulSets, PersistentVolumes, readiness/liveness probes, graceful shutdown.

---

## Phase 31 — Helm

**Branch:** `feature/helm`

Configurable Helm charts for NebulaKV cluster deployment.

---

## Phase 32 — Failure Testing

**Branch:** `feature/failure-testing`

Systematic failure injection:
- Node crash
- Network delay/partition
- Disk failure simulation
- Process crash during WAL/flush/compaction

---

## Phase 33 — Performance Benchmarking

**Branch:** `feature/benchmarking`

JMH benchmarks. Throughput, P50/P95/P99 latency, CPU, memory, disk I/O.

No invented numbers.

---

## Phase 34 — Consistency Testing

**Branch:** `feature/consistency-testing`

Concurrent operations under failures. Detect lost writes, stale reads, replica divergence.

---

## Phase 35 — Final Documentation and Hardening

**Branch:** `feature/final-docs`

Complete all ADRs. Document limitations. Update all docs to reflect final implementation.
