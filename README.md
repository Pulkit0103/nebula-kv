# NebulaKV

[![Maven Central](https://img.shields.io/maven-central/v/io.github.pulkit0103/nebula-kv)](https://central.sonatype.com/artifact/io.github.pulkit0103/nebula-kv)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

A distributed, fault-tolerant key-value database built from scratch in Java 17+.

NebulaKV demonstrates every layer of a production-grade distributed database:
from raw binary wire protocol and RESP2 compatibility to gossip-based membership,
consistent hashing, quorum reads/writes, compaction, Kubernetes deployment, and more.

> **Portfolio project** — every architectural decision is backed by reasoning, every trade-off documented in source comments.

---

## Architecture Overview

```
Client (NebulaClient / redis-cli / raw TCP)
  │ binary wire protocol (length-prefix framing) │ RESP2
  ▼
KVServer (NIO ServerSocketChannel, non-blocking accept)
  │
  ▼
Coordinator ──── HashRing (MD5 consistent hashing, 150 virtual nodes)
  │                  │
  │             replicaNodes(key, N=3)
  │
  ├──► replica 1 ──► DurableKeyValueStore
  ├──► replica 2       ├── MemTable (ConcurrentSkipListMap)
  └──► replica 3       ├── WriteAheadLog (CRC32, fsync)
                        └── SSTable (Bloom filter, sparse index, CRC footer)

Cluster management:
  MembershipManager ◄── FailureDetector (heartbeat-based, SUSPECT/DOWN)
  GossipProtocol         └── push-pull digest dissemination (O(log N) rounds)
  HintedHandoff          └── mutation buffering for unavailable replicas
  ReadRepair             └── convergence on reads (highest seq number wins)
  Rebalancer             └── key migration on ring join/leave

Operations:
  MetricsRegistry ──► /metrics (Prometheus text exposition)
  AdminServer     ──► /health, /cluster/nodes, /cluster/status
  SnapshotWriter  ──► crash-safe point-in-time binary snapshots
  Compactor       ──► k-way merge, tombstone GC, crash-safe swap
```

---

## Phases Implemented

| Phase | Component | Description |
|-------|-----------|-------------|
| 1 | Project scaffold | Maven 3.9, JUnit 5.10, Java 17 |
| 2 | InMemoryKeyValueStore | ConcurrentHashMap, atomic liveCount |
| 3 | Binary wire protocol | Request/Response codec, length-prefix framing |
| 4 | KVServer (NIO) | Non-blocking accept, fixed thread pool |
| 5 | WriteAheadLog | CRC32, fsync, crash recovery replay |
| 6 | MemTable | ConcurrentSkipListMap, tombstone tracking |
| 7 | SSTable | Sparse index, Bloom filter, footer CRC |
| 8 | BloomFilter | Double-hashing (Kirsch-Mitzenmacher), configurable FP rate |
| 9 | Compaction | k-way merge, crash-safe, tombstone GC in major compaction |
| 10 | Consistent hashing | MD5 token ring, 150 virtual nodes |
| 11 | QuorumConfig | N/W/R defaults (3/2/2), strong consistency check |
| 12 | Coordinator | Parallel quorum dispatch, CountDownLatch |
| 13 | MembershipManager | Node lifecycle (JOINING→ACTIVE→SUSPECT→DOWN) |
| 14-16 | ClusterNode | Immutable node record, ACTIVE factory |
| 17 | ConflictResolver | Highest sequence number wins, VersionedValue record |
| 18 | FailureDetector | Heartbeat tracking, configurable suspect/down thresholds |
| 19 | HintedHandoff | Mutation buffering for unavailable replicas, background replay |
| 20 | ReadRepair | Stale replica reconciliation on reads |
| 21 | Rebalancer | Key migration on ring join/leave |
| 22 | GossipProtocol | Push-pull dissemination, version-based merge, O(log N) convergence |
| 23 | Snapshots | Binary point-in-time snapshots, atomic rename for crash safety |
| 24 | Checksums | Unified CRC32 utilities (file, region, buffer, verify) |
| 25 | Concurrency stress | 16-thread stress tests, throughput baseline |
| 26 | Virtual threads | Runtime detection, Java 21+ transparent upgrade path |
| 27 | Observability | Prometheus counters/gauges/histograms, text scrape |
| 28 | AdminServer | HTTP management (health, metrics, cluster) — JDK HttpServer |
| 29 | Docker Compose | 3-node cluster, named volumes, healthchecks |
| 30 | Kubernetes | StatefulSet, PersistentVolumes, headless service, probes |
| 31 | Helm | Parameterized chart, auto-generated SEEDS from replicaCount |
| 32 | Failure testing | Crash, partition, disk failure, full recovery lifecycle |
| 33 | Benchmarks | Store ops, hashing, CRC32, concurrent puts |
| 34 | Consistency tests | Read-your-writes, monotonic reads, concurrent write ordering |
| 35 | Documentation | README, final hardening |
| 36 | TTL | Per-key expiry, lazy read expiry, background sweeper |
| 37 | Batch operations | mput/mget/mdelete, optimistic rollback |
| 38 | Prefix/range scan | scanPrefix, scanRange (half-open, lexicographic) |
| 39 | RESP2 protocol | redis-cli compatible (SET, GET, DEL, EXISTS, MGET, MSET, PING) |
| 40 | Java client library | NebulaClient with auto-reconnect, mget/mput/mdelete |

---

## Quick Start

### Use as a dependency

```xml
<dependency>
    <groupId>io.github.pulkit0103</groupId>
    <artifactId>nebula-kv</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
// Embedded store
InMemoryKeyValueStore store = new InMemoryKeyValueStore();
store.put("hello", "world");

// Remote TCP client (auto-reconnect)
try (NebulaClient client = NebulaClient.connect("localhost", 7777)) {
    client.put("hello", "world");
    Optional<String> val = client.get("hello");       // Optional["world"]
    Map<String, Optional<String>> batch = client.mget(List.of("a", "b", "c"));
}
```

### redis-cli compatibility (RESP2)

```bash
redis-cli -p 7777
127.0.0.1:7777> SET foo bar
OK
127.0.0.1:7777> GET foo
"bar"
127.0.0.1:7777> MSET a 1 b 2 c 3
OK
127.0.0.1:7777> MGET a b c
1) "1"
2) "2"
3) "3"
```

### Run tests
```bash
mvn test
# 277 tests, < 15 seconds
```

### Run benchmarks
```bash
mvn test -Dtest=StoreBenchmark
```

### Docker Compose (3-node cluster)
```bash
docker compose up --build
curl http://localhost:7101/health    # {"status":"UP","activeNodes":1}
curl http://localhost:7101/metrics  # Prometheus text
```

### Kubernetes
```bash
kubectl apply -f k8s/
```

### Helm
```bash
helm install nebula ./helm/nebula-kv/ --set replicaCount=3
```

---

## Key Design Decisions

### Why sequence numbers over wall-clock timestamps?
NTP skew (~100ms typical) makes timestamps unreliable for conflict resolution.
Sequence numbers are monotonically increasing within a node, providing
unambiguous ordering for concurrent writes.

### Why consistent hashing with virtual nodes?
Naive modulo hashing remaps O(N/N+1) keys when a node is added or removed.
Consistent hashing remaps only O(1/N) keys. Virtual nodes (150 per physical
node) ensure even load distribution without hotspots.

### Why CRC32 over stronger hashes?
CRC32 is not cryptographic but reliably detects accidental bit-flip errors
(the primary concern for disk and network I/O) at very low CPU cost.
For Byzantine fault tolerance, SHA-256 would be the upgrade path.

### Why gossip over a central coordinator?
Gossip disseminates membership state in O(log N) rounds with no single point
of failure. Each node contacts only `fanout` peers per round (default: 3).

### Why no Spring Boot in the storage engine?
Spring Boot adds complexity and startup overhead inappropriate for a tight
storage layer. JDK `HttpServer` is sufficient for the admin API. The
architecture is explicit about every dependency added.

---

## Performance (observed on development machine)

| Operation | Throughput |
|-----------|-----------|
| `put()` single-threaded | ~5M ops/sec |
| `get()` single-threaded | ~8M ops/sec |
| CRC32 (64 bytes) | ~10.7M ops/sec |
| `ConflictResolver.resolve()` (3 versions) | ~2.3M ops/sec |
| `HashRing.primaryNode()` | ~2M lookups/sec |
| 8-thread concurrent puts | ~2.4M aggregate ops/sec |

---

## Test Coverage

277 tests across all phases (v0.2.0).

| Package | Tests |
|---------|-------|
| store (KV, TTL, batch, scan) | 59 |
| protocol (binary + RESP2) | 25 |
| network | 8 |
| wal | 12 |
| memtable | 18 |
| sstable | 14 |
| compaction | 9 |
| client | 13 |
| cluster (hashing, quorum, gossip, failure) | 65+ |
| snapshot | 5 |
| checksum | 8 |
| admin | 6 |
| stress | 4 |
| failure scenarios | 6 |
| consistency | 5 |
| metrics | 9 |

---

## Git Workflow

```
main          ← stable, tagged releases
develop       ← integration branch
feature/*     ← one branch per phase
```

Commit convention: `feat:`, `fix:`, `test:`, `perf:`, `refactor:`, `docs:`

---

## License

MIT
