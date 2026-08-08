# NebulaKV — Storage Engine

## Overview

NebulaKV uses an **LSM-Tree (Log-Structured Merge-Tree)** storage model. This is the same fundamental design used by LevelDB, RocksDB, Cassandra, and HBase.

The core insight: **make writes sequential and fast; pay the cost on reads and during background compaction**.

---

## Write-Ahead Log (WAL)

### Purpose

Guarantee durability without immediately writing to disk-resident data structures.

### Guarantee

```
WAL durable (fsync)
     ↓
MemTable update
     ↓
ACK to client
```

A write is only acknowledged after it has been safely written to the WAL. If the process crashes before the MemTable is flushed to an SSTable, the WAL is replayed on restart to reconstruct the MemTable.

### Format

Each WAL entry:

```
[sequence_number: 8 bytes][operation: 1 byte][key_len: 4 bytes][key: N bytes][value_len: 4 bytes][value: N bytes][checksum: 4 bytes]
```

Operations:
- `0x01` — PUT
- `0x02` — DELETE (tombstone)

### Crash Recovery

```
On startup:
  1. Open WAL file
  2. Read entries sequentially
  3. Verify checksum per entry (skip corrupt tail)
  4. Replay valid entries into MemTable
  5. Continue normal operation
```

---

## MemTable

### Purpose

An in-memory, ordered, mutable data structure that buffers recent writes before flushing to disk.

### Implementation

`ConcurrentSkipListMap<String, MemTableEntry>` — provides:
- O(log N) put/get/delete
- Sorted key iteration (required for SSTable flush)
- Thread-safe concurrent access

### Entry Model

```java
record MemTableEntry(String value, long sequenceNumber, boolean tombstone)
```

### Flush Threshold

When MemTable exceeds a configured byte threshold (e.g., 64 MB), it is:
1. Marked immutable
2. Flushed to a new SSTable in a background thread
3. Replaced with a fresh empty MemTable

---

## SSTables (Sorted String Tables)

### Purpose

Immutable, sorted, disk-resident files. Once written, never modified.

### File Format

```
┌─────────────────────┐
│ HEADER              │  magic number, version, entry count
├─────────────────────┤
│ DATA BLOCKS         │  sorted key-value pairs
│  [key_len][key]     │
│  [val_len][value]   │
│  [seq_num][flags]   │
├─────────────────────┤
│ SPARSE INDEX        │  every Nth key → file offset
├─────────────────────┤
│ BLOOM FILTER        │  bit array for fast negative lookups
├─────────────────────┤
│ FOOTER              │  offsets to index and bloom filter, checksum
└─────────────────────┘
```

### Read Path

```
1. Check Bloom filter → if key probably absent, skip file
2. Binary search sparse index → find closest block offset
3. Scan block → find exact key or confirm absence
```

---

## Bloom Filters

### Purpose

Probabilistic data structure: **never false negatives, controlled false positive rate**.

Before scanning an SSTable, check the Bloom filter. If it says the key is absent, skip the entire file — guaranteed correct. If it says the key may be present, do the actual scan.

### Trade-off

Higher bit array size → lower false positive rate → more memory.

Typical: 10 bits per key → ~1% false positive rate.

### Implementation

Standard double-hashing approach:
- `k` hash functions (simulated with two base hashes)
- Bit array of size `m`

---

## Compaction

### Purpose

Over time, multiple SSTables accumulate. Compaction merges them to:
- Remove obsolete versions of the same key
- Remove tombstones for deleted keys
- Reduce read amplification

### Strategy

Level-based compaction (similar to LevelDB):
- Level 0: SSTables directly from MemTable flushes (may overlap)
- Level 1+: Non-overlapping SSTables sorted by key range

Merge is a k-way merge of sorted files — similar to merge sort.

### Tombstone Handling

A DELETE is recorded as a tombstone entry. It suppresses older versions of the key. Tombstones are only removed during compaction **after** they are older than the cluster's maximum replication lag (to prevent resurrection of deleted data on replica recovery).

---

## Sequence Numbers

Every write is tagged with a monotonically increasing sequence number.

- Breaks ties when the same key appears in MemTable and SSTable
- Enables snapshot isolation (read at a consistent sequence number)
- Provides causality ordering within a single node

**Do not rely solely on wall-clock timestamps for ordering** — clocks skew, NTP corrections move time backwards.

---

## Current Status

| Component | Status |
|---|---|
| WAL | Phase 5 |
| MemTable | Phase 6 |
| SSTables | Phase 7 |
| Bloom filters | Phase 8 |
| Sparse indexes | Phase 9 |
| Compaction | Phase 10 |
| Sequence numbers | Phase 11 |
