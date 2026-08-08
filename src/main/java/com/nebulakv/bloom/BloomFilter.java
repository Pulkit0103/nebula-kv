package com.nebulakv.bloom;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * Probabilistic set membership: no false negatives, tunable false positive rate.
 *
 * Algorithm: double-hashing with k hash functions simulated from two base hashes.
 *   h_i(key) = (h1(key) + i * h2(key)) mod m
 *
 * Why? Kirsch-Mitzenmacher (2006): two hash functions are sufficient to achieve
 * the same false positive rate as k independent hash functions, with much less
 * computation.
 *
 * Serialization: the bit array is stored as a compact byte array with an 8-byte
 * header (bit count). Embedded in SSTable files.
 *
 * Sizing formula (to achieve target FP rate p with n expected items):
 *   m = -n * ln(p) / (ln 2)^2   bits
 *   k = m/n * ln(2)              hash functions
 */
public final class BloomFilter {

    private final BitSet bits;
    private final int m; // number of bits
    private final int k; // number of hash functions

    /**
     * Constructs a Bloom filter for the given expected item count and false positive rate.
     *
     * @param expectedItems    expected number of distinct keys to be added
     * @param falsePositiveRate target FP rate, e.g., 0.01 for 1%
     */
    public BloomFilter(int expectedItems, double falsePositiveRate) {
        if (expectedItems <= 0) throw new IllegalArgumentException("expectedItems must be > 0");
        if (falsePositiveRate <= 0 || falsePositiveRate >= 1)
            throw new IllegalArgumentException("falsePositiveRate must be in (0, 1)");

        double ln2 = Math.log(2);
        this.m = (int) Math.ceil(-expectedItems * Math.log(falsePositiveRate) / (ln2 * ln2));
        this.k = Math.max(1, (int) Math.round((double) m / expectedItems * ln2));
        this.bits = new BitSet(m);
    }

    /** Constructs from existing serialized state (deserialization). */
    private BloomFilter(BitSet bits, int m, int k) {
        this.bits = bits;
        this.m = m;
        this.k = k;
    }

    /**
     * Adds a key to the filter. After this call, mightContain(key) returns true.
     */
    public void add(String key) {
        long[] hashes = hash(key);
        for (int i = 0; i < k; i++) {
            bits.set(bitIndex(hashes, i));
        }
    }

    /**
     * Returns true if the key might be present (may be a false positive).
     * Returns false only when the key is DEFINITELY not present.
     */
    public boolean mightContain(String key) {
        long[] hashes = hash(key);
        for (int i = 0; i < k; i++) {
            if (!bits.get(bitIndex(hashes, i))) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Serialization
    // -------------------------------------------------------------------------

    /**
     * Serializes to a ByteBuffer:
     *   [4 bytes: m (number of bits)]
     *   [4 bytes: k (number of hash functions)]
     *   [4 bytes: byte array length]
     *   [N bytes: bit array as bytes]
     */
    public ByteBuffer serialize() {
        byte[] bitBytes = bits.toByteArray();
        // Ensure the byte array is always exactly ceil(m/8) bytes so deserialization knows the size.
        int expectedByteLen = (m + 7) / 8;
        if (bitBytes.length < expectedByteLen) {
            byte[] padded = new byte[expectedByteLen];
            System.arraycopy(bitBytes, 0, padded, 0, bitBytes.length);
            bitBytes = padded;
        }

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + bitBytes.length);
        buf.putInt(m);
        buf.putInt(k);
        buf.putInt(bitBytes.length);
        buf.put(bitBytes);
        buf.flip();
        return buf;
    }

    public static BloomFilter deserialize(ByteBuffer buf) {
        int m        = buf.getInt();
        int k        = buf.getInt();
        int byteLen  = buf.getInt();
        byte[] bytes = new byte[byteLen];
        buf.get(bytes);
        BitSet bits = BitSet.valueOf(bytes);
        return new BloomFilter(bits, m, k);
    }

    public int bitCount() { return m; }
    public int hashCount() { return k; }

    // -------------------------------------------------------------------------
    // Hashing — double-hashing via Murmur-inspired mix
    // -------------------------------------------------------------------------

    private long[] hash(String key) {
        byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
        long h1 = murmurMix(bytes, 0x9747b28cL);
        long h2 = murmurMix(bytes, 0xc4ceb9fe1a85ec53L);
        return new long[]{h1, h2};
    }

    private int bitIndex(long[] hashes, int i) {
        long combined = (hashes[0] + (long) i * hashes[1]) % m;
        if (combined < 0) combined += m;
        return (int) combined;
    }

    /**
     * Simple hash mix based on Murmur3 finalizer.
     * Not cryptographic; optimized for speed and distribution.
     */
    private static long murmurMix(byte[] data, long seed) {
        long h = seed;
        for (byte b : data) {
            h ^= (b & 0xFFL);
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
        }
        h ^= data.length;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }
}
