package com.nebulakv.raft;

/**
 * Response to a RequestVote RPC.
 *
 * @param term        responder's current term (candidate updates itself if stale)
 * @param voteGranted true if the responder cast its vote for the candidate
 */
public record RequestVoteResponse(long term, boolean voteGranted) {}
