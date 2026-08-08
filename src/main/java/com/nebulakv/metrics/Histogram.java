package com.nebulakv.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fixed-bucket histogram for latency recording.
 *
 * Buckets (upper bounds in ms): 1, 5, 10, 50, 100, 500, 1000, +Inf
 *
 * Each observation is counted in all buckets whose upper bound is >= the value
 * (Prometheus cumulative histogram convention). This lets Prometheus compute
 * percentiles via histogram_quantile().
 */
public final class Histogram {

    static final long[] UPPER_BOUNDS = {1, 5, 10, 50, 100, 500, 1_000, Long.MAX_VALUE};

    private final String name;
    private final LongAdder[] buckets;
    private final LongAdder   count;
    private final LongAdder   sum;

    Histogram(String name) {
        this.name    = name;
        this.buckets = new LongAdder[UPPER_BOUNDS.length];
        for (int i = 0; i < UPPER_BOUNDS.length; i++) buckets[i] = new LongAdder();
        this.count = new LongAdder();
        this.sum   = new LongAdder();
    }

    public void record(long valueMs) {
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            if (valueMs <= UPPER_BOUNDS[i]) {
                buckets[i].increment();
            }
        }
        count.increment();
        sum.add(valueMs);
    }

    public long count() { return count.sum(); }
    public long sum()   { return sum.sum();   }

    /** Returns the cumulative count for the bucket whose upper bound is {@code le}. */
    public long bucketCount(long le) {
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            if (UPPER_BOUNDS[i] == le) return buckets[i].sum();
        }
        return 0L;
    }

    String toPrometheusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# TYPE ").append(name).append(" histogram\n");
        for (int i = 0; i < UPPER_BOUNDS.length; i++) {
            String le = UPPER_BOUNDS[i] == Long.MAX_VALUE ? "+Inf" : String.valueOf(UPPER_BOUNDS[i]);
            sb.append(name).append("_bucket{le=\"").append(le).append("\"} ")
              .append(buckets[i].sum()).append('\n');
        }
        sb.append(name).append("_count ").append(count.sum()).append('\n');
        sb.append(name).append("_sum ")  .append(sum.sum()).append('\n');
        return sb.toString();
    }
}
