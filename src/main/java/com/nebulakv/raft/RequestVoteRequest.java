package com.nebulakv.raft;

/**
 * Sent by a CANDIDATE to solicit votes from peers.
 *
 * @param term         candidate's current term
 * @param candidateId  node ID of the candidate
 * @param lastLogIndex index of candidate's last log entry
 * @param lastLogTerm  term of candidate's last log entry
 */
public record RequestVoteRequest(long term, String candidateId, long lastLogIndex, long lastLogTerm) {}
