package com.nebulakv.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MetricsRegistry — counters, gauges, histograms, and scrape output")
class MetricsTest {

    private MetricsRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MetricsRegistry();
    }

    @Test
    @DisplayName("counter increments correctly")
    void counterIncrements() {
        registry.increment("ops_total");
        registry.increment("ops_total");
        registry.increment("ops_total", 3);
        assertEquals(5, registry.counter("ops_total"));
    }

    @Test
    @DisplayName("counter returns 0 for unknown name")
    void counterUnknownReturnsZero() {
        assertEquals(0, registry.counter("ghost"));
    }

    @Test
    @DisplayName("gauge sets and reads current value")
    void gaugeSetAndRead() {
        registry.gauge("connections_active", 42);
        assertEquals(42, registry.gaugeValue("connections_active"));
        registry.gauge("connections_active", 7);
        assertEquals(7, registry.gaugeValue("connections_active"));
    }

    @Test
    @DisplayName("gauge returns 0 for unknown name")
    void gaugeUnknownReturnsZero() {
        assertEquals(0, registry.gaugeValue("phantom"));
    }

    @Test
    @DisplayName("histogram records count and sum")
    void histogramCountAndSum() {
        registry.observe("read_latency_ms", 3);
        registry.observe("read_latency_ms", 8);
        registry.observe("read_latency_ms", 20);

        Histogram h = registry.histogram("read_latency_ms");
        assertNotNull(h);
        assertEquals(3, h.count());
        assertEquals(31, h.sum());
    }

    @Test
    @DisplayName("histogram buckets are cumulative")
    void histogramBucketsCumulative() {
        // Record 2 observations below 5ms and 1 between 5-10ms.
        registry.observe("lat", 2);
        registry.observe("lat", 4);
        registry.observe("lat", 7);

        Histogram h = registry.histogram("lat");
        assertEquals(2, h.bucketCount(5),    "≤5ms bucket must count observations ≤5ms");
        assertEquals(3, h.bucketCount(10),   "≤10ms bucket must count all 3 observations");
        assertEquals(3, h.bucketCount(Long.MAX_VALUE), "+Inf must count all observations");
    }

    @Test
    @DisplayName("scrape output contains counter TYPE line")
    void scrapeContainsCounterType() {
        registry.increment("write_ops_total");
        String output = registry.scrape();
        assertTrue(output.contains("# TYPE write_ops_total counter"),
                "Scrape output must contain TYPE line for counter");
        assertTrue(output.contains("write_ops_total 1"),
                "Scrape output must contain counter value");
    }

    @Test
    @DisplayName("scrape output contains histogram buckets")
    void scrapeContainsHistogramBuckets() {
        registry.observe("rpc_latency_ms", 5);
        String output = registry.scrape();
        assertTrue(output.contains("# TYPE rpc_latency_ms histogram"),
                "Scrape must contain histogram TYPE line");
        assertTrue(output.contains("rpc_latency_ms_bucket{le=\"+Inf\"}"),
                "Scrape must contain +Inf bucket");
        assertTrue(output.contains("rpc_latency_ms_count 1"),
                "Scrape must contain count");
        assertTrue(output.contains("rpc_latency_ms_sum 5"),
                "Scrape must contain sum");
    }

    @Test
    @DisplayName("multiple concurrent increments are safe")
    void concurrentIncrementsAreSafe() throws InterruptedException {
        int threads = 8;
        int perThread = 1000;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) registry.increment("concurrent_counter");
            });
            workers[t].start();
        }
        for (Thread w : workers) w.join();
        assertEquals((long) threads * perThread, registry.counter("concurrent_counter"));
    }
}
