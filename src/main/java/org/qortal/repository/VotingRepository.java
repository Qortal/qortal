package org.qortal.repository;

import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.VoteOnPollData;

import java.util.List;

public interface VotingRepository {

	// Polls

	public List<PollData> getAllPolls(Integer limit, Integer offset, Boolean reverse) throws DataException;

	public PollData fromPollName(String pollName) throws DataException;

	/**
	 * Get polls matching a name prefix with aggregated vote data.
	 * This method is optimized for bulk fetching of poll data with vote counts,
	 * reducing the need for multiple separate queries.
	 *
	 * @param prefix Poll name prefix to match (e.g., "app-library-")
	 * @param limit Maximum number of results (0 = all)
	 * @param offset Pagination offset
	 * @return List of polls with aggregated vote counts and weights
	 * @throws DataException if database error occurs
	 */
	public List<PollDataWithVotes> getPollsByPrefix(String prefix, Integer limit, Integer offset) throws DataException;

	public boolean pollExists(String pollName) throws DataException;

	public void save(PollData pollData) throws DataException;

	public void delete(String pollName) throws DataException;

	// Votes

	public List<VoteOnPollData> getVotes(String pollName) throws DataException;

	public VoteOnPollData getVote(String pollName, byte[] voterPublicKey) throws DataException;

	public void save(VoteOnPollData voteOnPollData) throws DataException;

	public void delete(String pollName, byte[] voterPublicKey) throws DataException;

}
