package com.nebulakv.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes Java values into RESP2 wire format.
 *
 * Used to send responses back to redis-cli or any RESP2 client.
 */
public final class RespEncoder {

    private RespEncoder() {}

    public static byte[] ok() {
        return "+OK\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] nullBulk() {
        return "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] error(String message) {
        return ("-ERR " + message + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] integer(long value) {
        return (":" + value + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] bulkString(String value) {
        if (value == null) return nullBulk();
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        String header = "$" + valueBytes.length + "\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[headerBytes.length + valueBytes.length + 2];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(valueBytes, 0, result, headerBytes.length, valueBytes.length);
        result[result.length - 2] = '\r';
        result[result.length - 1] = '\n';
        return result;
    }

    public static byte[] array(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(values.size()).append("\r\n");
        for (String v : values) {
            if (v == null) {
                sb.append("$-1\r\n");
            } else {
                byte[] vb = v.getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(vb.length).append("\r\n").append(v).append("\r\n");
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
