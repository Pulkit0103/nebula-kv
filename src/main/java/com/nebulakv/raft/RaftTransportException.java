package com.nebulakv.raft;

/** Thrown by RaftTransport when a peer is unreachable or the call times out. */
public class RaftTransportException extends RuntimeException {
    public RaftTransportException(String message) { super(message); }
    public RaftTransportException(String message, Throwable cause) { super(message, cause); }
}
