package com.nebulakv.checksum;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Checksums — CRC32 computation and verification")
class ChecksumsTest {

    @Test
    @DisplayName("compute(byte[]) is consistent")
    void computeBytesConsistent() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        long crc1 = Checksums.compute(data);
        long crc2 = Checksums.compute(data);
        assertEquals(crc1, crc2);
        assertTrue(crc1 != 0);
    }

    @Test
    @DisplayName("compute(ByteBuffer) matches compute(byte[])")
    void computeByteBufferMatchesByteArray() {
        byte[] data = "nebula-kv".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(data);
        assertEquals(Checksums.compute(data), Checksums.compute(buf));
    }

    @Test
    @DisplayName("different data produces different CRC")
    void differentDataDifferentCrc() {
        assertNotEquals(
            Checksums.compute("abc".getBytes(StandardCharsets.UTF_8)),
            Checksums.compute("xyz".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    @DisplayName("verify passes when CRC matches")
    void verifyPassesOnMatch() {
        byte[] data = "ok".getBytes(StandardCharsets.UTF_8);
        long crc = Checksums.compute(data);
        assertDoesNotThrow(() -> Checksums.verify(data, crc));
    }

    @Test
    @DisplayName("verify throws ChecksumMismatchException on mismatch")
    void verifyThrowsOnMismatch() {
        byte[] data = "ok".getBytes(StandardCharsets.UTF_8);
        assertThrows(ChecksumMismatchException.class, () -> Checksums.verify(data, 0xDEADBEEFL));
    }

    @Test
    @DisplayName("computeFile matches compute on the same bytes")
    void computeFileMatchesByteArray(@TempDir Path tmpDir) throws IOException {
        byte[] content = "file content".getBytes(StandardCharsets.UTF_8);
        Path file = tmpDir.resolve("test.bin");
        Files.write(file, content);

        long fileCrc = Checksums.computeFile(file);
        long dataCrc = Checksums.compute(content);
        assertEquals(dataCrc, fileCrc);
    }

    @Test
    @DisplayName("computeFileRegion covers partial file")
    void computeFileRegionPartial(@TempDir Path tmpDir) throws IOException {
        byte[] content = "HEADERDATA".getBytes(StandardCharsets.UTF_8);
        Path file = tmpDir.resolve("partial.bin");
        Files.write(file, content);

        // Checksum just "DATA" (offset=6, length=4)
        long regionCrc = Checksums.computeFileRegion(file, 6, 4);
        long expected  = Checksums.compute("DATA".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, regionCrc);
    }

    @Test
    @DisplayName("ChecksumMismatchException carries expected and actual values")
    void exceptionCarriesValues() {
        ChecksumMismatchException ex = new ChecksumMismatchException(0xABCDL, 0x1234L);
        assertEquals(0xABCDL, ex.expected());
        assertEquals(0x1234L, ex.actual());
        assertTrue(ex.getMessage().contains("abcd"));
        assertTrue(ex.getMessage().contains("1234"));
    }
}
