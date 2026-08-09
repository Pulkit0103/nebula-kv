package com.nebulakv.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-process metrics registry.
 *
 * Provides three instrument types:
 *   Counter   — monotonically increasing value (ops, errors, bytes)
 *   Gauge     — current snapshot value (active connections, queue depth)
 *   Histogram — bucketed latency distribution (p50/p99 approximation)
 *
 * The registry exposes a Prometheus-compatible text scrape via {@link #scrape()}.
 * In production, wire this to an HTTP endpoint on /metrics; a Prometheus
 * scrape interval of 15s is typical.
 *
 * Why not use Micrometer or Prometheus Java client?
 *   For a portfolio project with no external dependencies, a thin in-process
 *   registry demonstrates the same observability patterns without the build
 *   complexity. Micrometer is the production replacement path.
 *
 * Thread safety: all instruments are backed by atomic primitives.
 */
public final class MetricsRegistry {

    private final ConcurrentHashMap<String, LongAdder>  counters   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram>  histograms = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Counter
    // -------------------------------------------------------------------------

    /** Increments the named counter by 1. */
    public void increment(String name) {
        counters.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    /** Increments the named counter by {@code delta}. */
    public void increment(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    /** Returns the current value of the named counter (0 if never incremented). */
    public long counter(String name) {
        LongAdder adder = counters.get(name);
        return adder == null ? 0L : adder.sum();
    }

    // -------------------------------------------------------------------------
    // Gauge
    // -------------------------------------------------------------------------

    /** Sets the named gauge to an absolute value. */
    public void gauge(String name, long value) {
        gauges.computeIfAbsent(name, k -> new AtomicLong()).set(value);
    }

    /** Returns the current value of the named gauge (0 if never set). */
    public long gaugeValue(String name) {
        AtomicLong g = gauges.get(name);
        return g == null ? 0L : g.get();
    }

    // -------------------------------------------------------------------------
    // Histogram
    // -------------------------------------------------------------------------

    /**
     * Records a latency observation in the named histogram.
     * Buckets: 1ms, 5ms, 10ms, 50ms, 100ms, 500ms, 1000ms, +Inf
     */
    public void observe(String name, long valueMs) {
        histograms.computeIfAbsent(name, Histogram::new).record(valueMs);
    }

    /** Returns the histogram for the given name, or null if no observations. */
    public Histogram histogram(String name) {
        return histograms.get(name);
    }

    // -------------------------------------------------------------------------
    // Prometheus text scrape
    // -------------------------------------------------------------------------

    /**
     * Returns all metrics in Prometheus text exposition format (version 0.0.4).
     * Suitable for serving on GET /metrics.
     */
    public String scrape() {
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, LongAdder> e : counters.entrySet()) {
            sb.append("# TYPE ").append(e.getKey()).append(" counter\n");
            sb.append(e.getKey()).append(' ').append(e.getValue().sum()).append('\n');
        }

        for (Map.Entry<String, AtomicLong> e : gauges.entrySet()) {
            sb.append("# TYPE ").append(e.getKey()).append(" gauge\n");
            sb.append(e.getKey()).append(' ').append(e.getValue().get()).append('\n');
        }

        for (Map.Entry<String, Histogram> e : histograms.entrySet()) {
            sb.append(e.getValue().toPrometheusText());
        }

        return sb.toString();
    }
}
