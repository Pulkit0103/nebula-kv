package com.nebulakv;

import com.nebulakv.core.NodeInfo;
import com.nebulakv.core.NodeStatus;
import com.nebulakv.core.SystemInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Bootstrap — Phase 1 Sanity Checks")
class BootstrapTest {

    @Test
    @DisplayName("SystemInfo collects valid JVM metadata")
    void systemInfoCollectsJvmMetadata() {
        SystemInfo info = SystemInfo.collect();

        assertNotNull(info.javaVersion(), "java.version must not be null");
        assertFalse(info.javaVersion().isBlank(), "java.version must not be blank");
        assertNotNull(info.osName(), "os.name must not be null");
        assertTrue(info.availableProcessors() >= 1, "must have at least 1 CPU");
        assertTrue(info.maxHeapMb() > 0, "max heap must be positive");
    }

    @Test
    @DisplayName("NodeInfo defaults produces a valid standalone node")
    void nodeInfoDefaultsProducesStandaloneNode() {
        NodeInfo node = NodeInfo.defaults();

        assertNotNull(node.nodeId(), "nodeId must not be null");
        assertFalse(node.nodeId().isBlank(), "nodeId must not be blank");
        assertNotNull(node.host(), "host must not be null");
        assertFalse(node.host().isBlank(), "host must not be blank");
        assertEquals(NodeStatus.STANDALONE, node.status(), "bootstrap node must be STANDALONE");
    }

    @Test
    @DisplayName("Each NodeInfo.defaults() call produces a unique node ID")
    void eachNodeInfoHasUniqueId() {
        NodeInfo a = NodeInfo.defaults();
        NodeInfo b = NodeInfo.defaults();

        assertNotEquals(a.nodeId(), b.nodeId(), "node IDs must be unique across instances");
    }

    @Test
    @DisplayName("NodeStatus enum contains all required cluster states")
    void nodeStatusEnumContainsAllStates() {
        // Verify all lifecycle states are present — future phases depend on them.
        assertDoesNotThrow(() -> NodeStatus.valueOf("STANDALONE"));
        assertDoesNotThrow(() -> NodeStatus.valueOf("JOINING"));
        assertDoesNotThrow(() -> NodeStatus.valueOf("ACTIVE"));
        assertDoesNotThrow(() -> NodeStatus.valueOf("SUSPECT"));
        assertDoesNotThrow(() -> NodeStatus.valueOf("DOWN"));
        assertDoesNotThrow(() -> NodeStatus.valueOf("LEAVING"));
    }

    @Test
    @DisplayName("NebulaKV version constant is set")
    void versionConstantIsSet() {
        assertNotNull(NebulaKV.VERSION);
        assertFalse(NebulaKV.VERSION.isBlank());
    }

    @Test
    @DisplayName("SystemInfo is a value type — equal instances compare equal")
    void systemInfoValueEquality() {
        // Records provide structural equality automatically.
        SystemInfo a = new SystemInfo("21", "Linux", 4, 512L);
        SystemInfo b = new SystemInfo("21", "Linux", 4, 512L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
