package com.nebulakv.raft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raft state machine backed by an in-memory key-value map.
 *
 * Applied in log order by RaftNode.triggerApply(). NoOp entries (leader
 * election no-ops) are silently ignored.
 */
public final class KVStateMachine implements RaftStateMachine {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public void apply(LogEntry entry) {
        RaftCommand cmd = entry.command();
        if (cmd instanceof RaftCommand.Put p) {
            store.put(p.key(), p.value());
        } else if (cmd instanceof RaftCommand.Delete d) {
            store.remove(d.key());
        }
        // NoOp — election no-op, nothing to apply
    }

    public String get(String key) {
        return store.get(key);
    }

    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    /** Snapshot of current state — useful for testing. */
    public Map<String, String> snapshot() {
        return Map.copyOf(store);
    }
}
