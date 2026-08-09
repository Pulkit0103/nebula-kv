package com.nebulakv.protocol;

/**
 * Enumeration of NebulaKV wire commands.
 *
 * Each command has a fixed 1-byte opcode used in the binary frame header.
 * Opcodes are stable — once assigned they must never change, because existing
 * clients encode them. New commands get new opcodes; old opcodes are never reused.
 */
public enum CommandType {

    PUT(0x01),
    GET(0x02),
    DELETE(0x03),
    EXISTS(0x04);

    private final byte opcode;

    CommandType(int opcode) {
        this.opcode = (byte) opcode;
    }

    public byte opcode() {
        return opcode;
    }

    public static CommandType fromOpcode(byte opcode) {
        for (CommandType cmd : values()) {
            if (cmd.opcode == opcode) return cmd;
        }
        throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(opcode & 0xFF));
    }
}
