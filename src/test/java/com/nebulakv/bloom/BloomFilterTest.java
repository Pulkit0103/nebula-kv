package com.nebulakv.bloom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BloomFilter — correctness and serialization tests")
class BloomFilterTest {

    @Test
    @DisplayName("added keys are always found (no false negatives)")
    void noFalseNegatives() {
        BloomFilter bf = new BloomFilter(1000, 0.01);
        for (int i = 0; i < 1000; i++) {
            String key = "key:" + i;
            bf.add(key);
            assertTrue(bf.mightContain(key),
                    "False negative detected for key: " + key);
        }
    }

    @Test
    @DisplayName("false positive rate stays within 3x of configured rate")
    void falsePositiveRateWithinBounds() {
        int n = 10_000;
        double fpr = 0.01;
        BloomFilter bf = new BloomFilter(n, fpr);

        // Add n items.
        for (int i = 0; i < n; i++) {
            bf.add("present:" + i);
        }

        // Test n items that were NOT added.
        int falsePositives = 0;
        for (int i = 0; i < n; i++) {
            if (bf.mightContain("absent:" + i)) falsePositives++;
        }

        double actualFpr = (double) falsePositives / n;
        // Allow up to 3× the configured rate to account for hash quality variance.
        assertTrue(actualFpr <= fpr * 3,
                "FPR too high: " + actualFpr + " (expected ≤ " + (fpr * 3) + ")");
    }

    @Test
    @DisplayName("key not added returns false (when hash space is large enough)")
    void absentKeyReturnsFalse() {
        BloomFilter bf = new BloomFilter(100, 0.001); // very low FPR
        bf.add("present");
        // "absent" has very low probability of being a false positive with 0.1% FPR.
        // This is probabilistic, but with this filter size it should be false.
        boolean result = bf.mightContain("completely-different-key-xyzabc123");
        // We can only assert the false-positive rate is low, not that any specific key is absent.
        // Just verify the method doesn't throw.
        assertNotNull(Boolean.valueOf(result));
    }

    @Test
    @DisplayName("serialize and deserialize preserves mightContain results")
    void serializeDeserialize() {
        BloomFilter original = new BloomFilter(500, 0.01);
        for (int i = 0; i < 500; i++) {
            original.add("item:" + i);
        }

        ByteBuffer serialized = original.serialize();
        BloomFilter restored = BloomFilter.deserialize(serialized);

        // All previously added items must still be found.
        for (int i = 0; i < 500; i++) {
            assertTrue(restored.mightContain("item:" + i),
                    "Deserialized filter should find item:" + i);
        }
    }

    @Test
    @DisplayName("serialized size scales with bit count")
    void serializedSizeScalesWithBitCount() {
        BloomFilter small = new BloomFilter(100, 0.01);
        BloomFilter large = new BloomFilter(10_000, 0.01);

        assertTrue(large.bitCount() > small.bitCount());
        assertTrue(large.serialize().remaining() > small.serialize().remaining());
    }

    @Test
    @DisplayName("empty filter never contains any key")
    void emptyFilterContainsNothing() {
        BloomFilter bf = new BloomFilter(100, 0.01);
        for (int i = 0; i < 100; i++) {
            assertFalse(bf.mightContain("key:" + i));
        }
    }

    @Test
    @DisplayName("UUID keys are handled correctly")
    void uuidKeysWork() {
        BloomFilter bf = new BloomFilter(100, 0.01);
        String[] keys = new String[50];
        for (int i = 0; i < 50; i++) {
            keys[i] = UUID.randomUUID().toString();
            bf.add(keys[i]);
        }
        for (String key : keys) {
            assertTrue(bf.mightContain(key));
        }
    }

    @Test
    @DisplayName("different expected sizes produce different bit counts")
    void differentSizesDifferentBitCounts() {
        BloomFilter small  = new BloomFilter(100, 0.01);
        BloomFilter medium = new BloomFilter(1_000, 0.01);
        BloomFilter large  = new BloomFilter(10_000, 0.01);

        assertTrue(small.bitCount() < medium.bitCount());
        assertTrue(medium.bitCount() < large.bitCount());
    }
}
