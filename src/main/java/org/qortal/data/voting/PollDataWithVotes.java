package org.qortal.data.voting;

import java.util.Map;

/**
 * Container class that holds poll data along with aggregated vote counts and weights.
 * Used for bulk fetching of poll data with voting information.
 */
public class PollDataWithVotes {

	// Properties
	private PollData pollData;
	private Integer totalVotes;
	private Integer totalWeight;
	private Map<String, Integer> voteCountMap;
	private Map<String, Integer> voteWeightMap;

	// Constructors

	public PollDataWithVotes() {
		super();
	}

	public PollDataWithVotes(PollData pollData, Integer totalVotes, Integer totalWeight,
			Map<String, Integer> voteCountMap, Map<String, Integer> voteWeightMap) {
		this.pollData = pollData;
		this.totalVotes = totalVotes;
		this.totalWeight = totalWeight;
		this.voteCountMap = voteCountMap;
		this.voteWeightMap = voteWeightMap;
	}

	// Getters/setters

	public PollData getPollData() {
		return this.pollData;
	}

	public void setPollData(PollData pollData) {
		this.pollData = pollData;
	}

	public Integer getTotalVotes() {
		return this.totalVotes;
	}

	public void setTotalVotes(Integer totalVotes) {
		this.totalVotes = totalVotes;
	}

	public Integer getTotalWeight() {
		return this.totalWeight;
	}

	public void setTotalWeight(Integer totalWeight) {
		this.totalWeight = totalWeight;
	}

	public Map<String, Integer> getVoteCountMap() {
		return this.voteCountMap;
	}

	public void setVoteCountMap(Map<String, Integer> voteCountMap) {
		this.voteCountMap = voteCountMap;
	}

	public Map<String, Integer> getVoteWeightMap() {
		return this.voteWeightMap;
	}

	public void setVoteWeightMap(Map<String, Integer> voteWeightMap) {
		this.voteWeightMap = voteWeightMap;
	}

}
