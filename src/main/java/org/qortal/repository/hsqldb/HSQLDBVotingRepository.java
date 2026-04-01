package org.qortal.repository.hsqldb;

import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.repository.DataException;
import org.qortal.repository.VotingRepository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HSQLDBVotingRepository implements VotingRepository {

	protected HSQLDBRepository repository;

	public HSQLDBVotingRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// Polls

	@Override
	public List<PollData> getAllPolls(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT poll_name, description, creator, owner, published_when FROM Polls ORDER BY poll_name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<PollData> polls = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return polls;

			do {
				String pollName = resultSet.getString(1);
				String description = resultSet.getString(2);
				byte[] creatorPublicKey = resultSet.getBytes(3);
				String owner = resultSet.getString(4);
				long published = resultSet.getLong(5);

				String optionsSql = "SELECT option_name FROM PollOptions WHERE poll_name = ? ORDER BY option_index ASC";
				try (ResultSet optionsResultSet = this.repository.checkedExecute(optionsSql, pollName)) {
					if (optionsResultSet == null)
						return null;

					List<PollOptionData> pollOptions = new ArrayList<>();

					// NOTE: do-while because checkedExecute() above has already called rs.next() for us
					do {
						String optionName = optionsResultSet.getString(1);

						pollOptions.add(new PollOptionData(optionName));
					} while (optionsResultSet.next());

					polls.add(new PollData(creatorPublicKey, owner, pollName, description, pollOptions, published));
				}
				
			} while (resultSet.next());

			return polls;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch polls from repository", e);
		}
	}

	@Override
	public PollData fromPollName(String pollName) throws DataException {
		String sql = "SELECT description, creator, owner, published_when FROM Polls WHERE poll_name = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName)) {
			if (resultSet == null)
				return null;

			String description = resultSet.getString(1);
			byte[] creatorPublicKey = resultSet.getBytes(2);
			String owner = resultSet.getString(3);
			long published = resultSet.getLong(4);

			String optionsSql = "SELECT option_name FROM PollOptions WHERE poll_name = ? ORDER BY option_index ASC";
			try (ResultSet optionsResultSet = this.repository.checkedExecute(optionsSql, pollName)) {
				if (optionsResultSet == null)
					return null;

				List<PollOptionData> pollOptions = new ArrayList<>();

				// NOTE: do-while because checkedExecute() above has already called rs.next() for us
				do {
					String optionName = optionsResultSet.getString(1);

					pollOptions.add(new PollOptionData(optionName));
				} while (optionsResultSet.next());

				return new PollData(creatorPublicKey, owner, pollName, description, pollOptions, published);
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll from repository", e);
		}
	}

	@Override
	public List<PollDataWithVotes> getPollsByPrefix(String prefix, Integer limit, Integer offset) throws DataException {
		StringBuilder sql = new StringBuilder(1024);

		// Query to get all polls matching prefix with their options and aggregated vote data
		sql.append("SELECT ");
		sql.append("  p.poll_name, p.description, p.creator, p.owner, p.published_when, ");
		sql.append("  po.option_index, po.option_name, ");
		sql.append("  COUNT(pv.voter) AS vote_count, ");
		sql.append("  COALESCE(SUM(CASE WHEN a.blocks_minted + a.blocks_minted_penalty < 0 THEN 0 ELSE a.blocks_minted + a.blocks_minted_penalty END), 0) AS vote_weight ");
		sql.append("FROM Polls p ");
		sql.append("LEFT JOIN PollOptions po ON p.poll_name = po.poll_name ");
		sql.append("LEFT JOIN PollVotes pv ON p.poll_name = pv.poll_name AND po.option_index = pv.option_index ");
		sql.append("LEFT JOIN Accounts a ON pv.voter = a.public_key ");
		sql.append("WHERE p.poll_name LIKE ? ");
		sql.append("GROUP BY p.poll_name, p.description, p.creator, p.owner, p.published_when, po.option_index, po.option_name ");
		sql.append("ORDER BY p.poll_name, po.option_index");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<PollDataWithVotes> results = new ArrayList<>();
		Map<String, PollDataWithVotes> pollMap = new LinkedHashMap<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), prefix + "%")) {
			if (resultSet == null)
				return results;

			// Process results - multiple rows per poll (one per option)
			do {
				String pollName = resultSet.getString(1);
				String description = resultSet.getString(2);
				byte[] creatorPublicKey = resultSet.getBytes(3);
				String owner = resultSet.getString(4);
				long published = resultSet.getLong(5);
				Integer optionIndex = resultSet.getInt(6);
				String optionName = resultSet.getString(7);
				int voteCount = resultSet.getInt(8);
				int voteWeight = resultSet.getInt(9);

				// Get or create PollDataWithVotes for this poll
				PollDataWithVotes pollWithVotes = pollMap.get(pollName);
				if (pollWithVotes == null) {
					// Create new poll data
					PollData pollData = new PollData(creatorPublicKey, owner, pollName, description, new ArrayList<>(), published);
					Map<String, Integer> voteCountMap = new HashMap<>();
					Map<String, Integer> voteWeightMap = new HashMap<>();
					pollWithVotes = new PollDataWithVotes(pollData, 0, 0, voteCountMap, voteWeightMap);
					pollMap.put(pollName, pollWithVotes);
				}

				// Add option to poll if not null
				if (optionName != null) {
					pollWithVotes.getPollData().getPollOptions().add(new PollOptionData(optionName));

					// Add vote counts and weights
					pollWithVotes.getVoteCountMap().put(optionName, voteCount);
					pollWithVotes.getVoteWeightMap().put(optionName, voteWeight);

					// Update totals
					pollWithVotes.setTotalVotes(pollWithVotes.getTotalVotes() + voteCount);
					pollWithVotes.setTotalWeight(pollWithVotes.getTotalWeight() + voteWeight);
				}

			} while (resultSet.next());

			// Convert map to list
			results.addAll(pollMap.values());

			return results;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch polls by prefix from repository", e);
		}
	}

	@Override
	public boolean pollExists(String pollName) throws DataException {
		try {
			return this.repository.exists("Polls", "poll_name = ?", pollName);
		} catch (SQLException e) {
			throw new DataException("Unable to check for poll in repository", e);
		}
	}

	@Override
	public void save(PollData pollData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Polls");

		saveHelper.bind("poll_name", pollData.getPollName()).bind("description", pollData.getDescription()).bind("creator", pollData.getCreatorPublicKey())
				.bind("owner", pollData.getOwner()).bind("published_when", pollData.getPublished());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save poll into repository", e);
		}

		// Now attempt to save poll options
		List<PollOptionData> pollOptions = pollData.getPollOptions();
		for (int optionIndex = 0; optionIndex < pollOptions.size(); ++optionIndex) {
			PollOptionData pollOptionData = pollOptions.get(optionIndex);

			HSQLDBSaver optionSaveHelper = new HSQLDBSaver("PollOptions");

			optionSaveHelper.bind("poll_name", pollData.getPollName()).bind("option_index", optionIndex).bind("option_name", pollOptionData.getOptionName());

			try {
				optionSaveHelper.execute(this.repository);
			} catch (SQLException e) {
				throw new DataException("Unable to save poll option into repository", e);
			}
		}
	}

	@Override
	public void delete(String pollName) throws DataException {
		// NOTE: The corresponding rows in PollOptions are deleted automatically by the database
		// thanks to "ON DELETE CASCADE" in the PollOptions' FOREIGN KEY definition.
		try {
			this.repository.delete("Polls", "poll_name = ?", pollName);
		} catch (SQLException e) {
			throw new DataException("Unable to delete poll from repository", e);
		}
	}

	// Votes

	@Override
	public List<VoteOnPollData> getVotes(String pollName) throws DataException {
		String sql = "SELECT voter, option_index FROM PollVotes WHERE poll_name = ?";
		List<VoteOnPollData> votes = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName)) {
			if (resultSet == null)
				return votes;

			// NOTE: do-while because checkedExecute() above has already called rs.next() for us
			do {
				byte[] voterPublicKey = resultSet.getBytes(1);
				int optionIndex = resultSet.getInt(2);

				votes.add(new VoteOnPollData(pollName, voterPublicKey, optionIndex));
			} while (resultSet.next());

			return votes;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll votes from repository", e);
		}
	}

	@Override
	public VoteOnPollData getVote(String pollName, byte[] voterPublicKey) throws DataException {
		String sql = "SELECT option_index FROM PollVotes WHERE poll_name = ? AND voter = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, pollName, voterPublicKey)) {
			if (resultSet == null)
				return null;

			int optionIndex = resultSet.getInt(1);

			return new VoteOnPollData(pollName, voterPublicKey, optionIndex);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch poll vote from repository", e);
		}
	}

	@Override
	public void save(VoteOnPollData voteOnPollData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("PollVotes");

		saveHelper.bind("poll_name", voteOnPollData.getPollName()).bind("voter", voteOnPollData.getVoterPublicKey())
				.bind("option_index", voteOnPollData.getOptionIndex());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save poll vote into repository", e);
		}
	}

	@Override
	public void delete(String pollName, byte[] voterPublicKey) throws DataException {
		try {
			this.repository.delete("PollVotes", "poll_name = ? AND voter = ?", pollName, voterPublicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete poll vote from repository", e);
		}
	}

}
