# NebulaKV

A distributed, fault-tolerant key-value database implemented from scratch in Java 21.

> **Portfolio project** — demonstrates deep distributed-systems engineering: storage engine design, WAL, SSTables, consistent hashing, replication, quorum, failure detection, and Kubernetes deployment.

---

## Why NebulaKV?

Most "key-value database" projects are a `HashMap` behind an HTTP endpoint. NebulaKV is not.

The goal is to build a system that can be explained and defended in a senior SDE / distributed-systems design interview — every architectural decision backed by reasoning, every trade-off documented.

---

## Architecture (target)

```
Client
  ↓
Coordinator
  ↓
Consistent Hash Ring
  ↓
Primary + Replicas (N)
  ↓
WAL  →  MemTable  →  SSTables  →  Compaction
```

Distributed layer:

```
Client → Coordinator → Partition ownership → Replication → Quorum → Conflict resolution
```

Cluster layer:

```
Node membership → Heartbeats → Failure detection → Hinted handoff → Read repair → Rebalancing
```

---

## Technology Stack

| Layer | Technology |
|---|---|
| Core database | Java 21, plain Java NIO, FileChannel, ByteBuffer |
| Concurrency | ConcurrentHashMap, ConcurrentSkipListMap, Atomic classes, Locks |
| Testing | JUnit 5 |
| Build | Maven |
| Observability (future) | Prometheus, Grafana, OpenTelemetry |
| Administration (future) | Spring Boot (admin API only) |
| Deployment (future) | Docker, Kubernetes, Helm |
| Benchmarking (future) | JMH |

**No Spring Boot in the storage engine. Ever.**

---

## Implementation Roadmap

| Phase | Description | Status |
|---|---|---|
| 1 | Repository bootstrap | ✅ Done |
| 2 | In-memory KeyValueStore (ConcurrentHashMap) | Pending |
| 3 | Command protocol (PUT/GET/DELETE/EXISTS) | Pending |
| 4 | TCP networking (Java NIO) | Pending |
| 5 | Write-Ahead Log (WAL) | Pending |
| 6 | MemTable (ConcurrentSkipListMap) | Pending |
| 7 | SSTables (immutable sorted disk files) | Pending |
| 8 | Bloom filters | Pending |
| 9 | SSTable sparse indexes | Pending |
| 10 | Compaction | Pending |
| 11 | Versioned records / sequence numbers | Pending |
| 12 | Consistent hashing + virtual nodes | Pending |
| 13 | Node membership (JOIN/LEAVE/HEARTBEAT) | Pending |
| 14 | Coordinator | Pending |
| 15 | Replication (configurable factor) | Pending |
| 16 | Quorum reads/writes (N, R, W) | Pending |
| 17 | Conflict resolution | Pending |
| 18 | Failure detection | Pending |
| 19 | Hinted handoff | Pending |
| 20 | Read repair | Pending |
| 21 | Rebalancing | Pending |
| 22 | Gossip membership | Pending |
| 23 | Snapshots | Pending |
| 24 | Checksums | Pending |
| 25 | Concurrency optimization | Pending |
| 26 | Virtual thread evaluation | Pending |
| 27 | Observability (Prometheus/Grafana/OTel) | Pending |
| 28 | Administrative API (Spring Boot) | Pending |
| 29 | Docker Compose multi-node cluster | Pending |
| 30 | Kubernetes deployment | Pending |
| 31 | Helm charts | Pending |
| 32 | Failure testing | Pending |
| 33 | Performance benchmarking (JMH) | Pending |
| 34 | Consistency testing | Pending |
| 35 | Final documentation and hardening | Pending |

---

## Getting Started

**Requirements:**

- Java 21+
- Maven 3.9+

**Build:**

```bash
mvn clean package
```

**Run tests:**

```bash
mvn test
```

**Run:**

```bash
java --enable-preview -jar target/nebula-kv-0.1.0-SNAPSHOT.jar
```

---

## Documentation

| Document | Purpose |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | System design and component overview |
| [STORAGE_ENGINE.md](STORAGE_ENGINE.md) | WAL, MemTable, SSTable, compaction design |
| [CONSISTENCY.md](CONSISTENCY.md) | Consistency model, quorum, conflict resolution |
| [FAILURE_MODEL.md](FAILURE_MODEL.md) | Failure detection, hinted handoff, read repair |
| [ROADMAP.md](ROADMAP.md) | Detailed implementation plan |

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
