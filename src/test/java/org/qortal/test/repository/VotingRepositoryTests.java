package org.qortal.test.repository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollOptionData;
import org.qortal.data.voting.VoteOnPollData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.Common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class VotingRepositoryTests extends Common {

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();
	}

	@After
	public void afterTest() throws DataException {
		// Clean up any test polls
		try (final Repository repository = RepositoryManager.getRepository()) {
			cleanupTestPolls(repository);
		}
	}

	@Test
	public void testGetPollsByPrefixBasic() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create test polls
			createTestPoll(repository, "app-library-APP-rating-TestApp1");
			createTestPoll(repository, "app-library-APP-rating-TestApp2");
			createTestPoll(repository, "other-poll-name");

			// Fetch polls with app-library prefix
			List<PollDataWithVotes> results = repository.getVotingRepository()
					.getPollsByPrefix("app-library-", null, null);

			assertNotNull(results);
			assertTrue(results.size() >= 2);

			// Verify that only app-library polls are returned
			boolean foundTestApp1 = false;
			boolean foundTestApp2 = false;
			boolean foundOtherPoll = false;

			for (PollDataWithVotes pollWithVotes : results) {
				String pollName = pollWithVotes.getPollData().getPollName();
				if (pollName.equals("app-library-APP-rating-TestApp1")) foundTestApp1 = true;
				if (pollName.equals("app-library-APP-rating-TestApp2")) foundTestApp2 = true;
				if (pollName.equals("other-poll-name")) foundOtherPoll = true;
			}

			assertTrue(foundTestApp1);
			assertTrue(foundTestApp2);
			assertFalse(foundOtherPoll);
		}
	}

	@Test
	public void testGetPollsByPrefixWithVotes() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "app-library-APP-rating-VoteTest";
			createTestPoll(repository, pollName);

			// Add some votes
			byte[] voterPublicKey = Common.getTestAccount(repository, "alice").getPublicKey();
			VoteOnPollData vote = new VoteOnPollData(pollName, voterPublicKey, 4); // Vote for option index 4 (5 stars)
			repository.getVotingRepository().save(vote);
			repository.saveChanges();

			// Fetch polls with votes
			List<PollDataWithVotes> results = repository.getVotingRepository()
					.getPollsByPrefix("app-library-APP-rating-VoteTest", null, null);

			assertNotNull(results);
			assertEquals(1, results.size());

			PollDataWithVotes pollWithVotes = results.get(0);
			assertNotNull(pollWithVotes.getTotalVotes());
			assertNotNull(pollWithVotes.getTotalWeight());
			assertNotNull(pollWithVotes.getVoteCountMap());
			assertNotNull(pollWithVotes.getVoteWeightMap());

			// Verify vote counts
			assertTrue(pollWithVotes.getTotalVotes() >= 1);

			// Verify option "5" has at least one vote
			Map<String, Integer> voteCountMap = pollWithVotes.getVoteCountMap();
			assertTrue(voteCountMap.containsKey("5"));
			assertTrue(voteCountMap.get("5") >= 1);
		}
	}

	@Test
	public void testGetPollsByPrefixWithLimit() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create multiple test polls
			for (int i = 1; i <= 5; i++) {
				createTestPoll(repository, "app-library-APP-rating-LimitTest" + i);
			}

			// Fetch with limit of 2
			List<PollDataWithVotes> results = repository.getVotingRepository()
					.getPollsByPrefix("app-library-APP-rating-LimitTest", 2, null);

			assertNotNull(results);
			assertTrue(results.size() <= 2);
		}
	}

	@Test
	public void testGetPollsByPrefixWithOffset() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create multiple test polls
			for (int i = 1; i <= 5; i++) {
				createTestPoll(repository, "app-library-APP-rating-OffsetTest" + i);
			}

			// Fetch all
			List<PollDataWithVotes> allResults = repository.getVotingRepository()
					.getPollsByPrefix("app-library-APP-rating-OffsetTest", null, null);

			// Fetch with offset
			List<PollDataWithVotes> offsetResults = repository.getVotingRepository()
					.getPollsByPrefix("app-library-APP-rating-OffsetTest", null, 2);

			assertNotNull(allResults);
			assertNotNull(offsetResults);
			assertTrue(offsetResults.size() < allResults.size() || allResults.size() <= 2);
		}
	}

	@Test
	public void testGetPollsByPrefixNoResults() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Search for non-existent prefix
			List<PollDataWithVotes> results = repository.getVotingRepository()
					.getPollsByPrefix("non-existent-prefix-", null, null);

			assertNotNull(results);
			assertTrue(results.isEmpty());
		}
	}

	@Test
	public void testGetPollsByPrefixPollOptions() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "app-library-APP-rating-OptionsTest";
			createTestPoll(repository, pollName);

			List<PollDataWithVotes> results = repository.getVotingRepository()
					.getPollsByPrefix(pollName, null, null);

			assertNotNull(results);
			assertEquals(1, results.size());

			PollDataWithVotes pollWithVotes = results.get(0);
			PollData pollData = pollWithVotes.getPollData();

			// Verify poll options are populated
			assertNotNull(pollData.getPollOptions());
			assertEquals(5, pollData.getPollOptions().size());

			// Verify option names (1-5 star ratings)
			List<String> optionNames = new ArrayList<>();
			for (PollOptionData option : pollData.getPollOptions()) {
				optionNames.add(option.getOptionName());
			}

			assertTrue(optionNames.contains("1"));
			assertTrue(optionNames.contains("2"));
			assertTrue(optionNames.contains("3"));
			assertTrue(optionNames.contains("4"));
			assertTrue(optionNames.contains("5"));
		}
	}

	@Test
	public void testGetPollsByPrefixVoteMaps() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "app-library-APP-rating-MapTest";
			createTestPoll(repository, pollName);

			List<PollDataWithVotes> results = repository.getVotingRepository()
					.getPollsByPrefix(pollName, null, null);

			assertNotNull(results);
			assertEquals(1, results.size());

			PollDataWithVotes pollWithVotes = results.get(0);

			// Verify vote count map contains all options
			Map<String, Integer> voteCountMap = pollWithVotes.getVoteCountMap();
			assertNotNull(voteCountMap);
			assertTrue(voteCountMap.containsKey("1"));
			assertTrue(voteCountMap.containsKey("2"));
			assertTrue(voteCountMap.containsKey("3"));
			assertTrue(voteCountMap.containsKey("4"));
			assertTrue(voteCountMap.containsKey("5"));

			// Verify vote weight map contains all options
			Map<String, Integer> voteWeightMap = pollWithVotes.getVoteWeightMap();
			assertNotNull(voteWeightMap);
			assertTrue(voteWeightMap.containsKey("1"));
			assertTrue(voteWeightMap.containsKey("2"));
			assertTrue(voteWeightMap.containsKey("3"));
			assertTrue(voteWeightMap.containsKey("4"));
			assertTrue(voteWeightMap.containsKey("5"));
		}
	}

	// Helper methods

	private void createTestPoll(Repository repository, String pollName) throws DataException {
		// Create poll options (1-5 star rating)
		List<PollOptionData> options = new ArrayList<>();
		options.add(new PollOptionData("1"));
		options.add(new PollOptionData("2"));
		options.add(new PollOptionData("3"));
		options.add(new PollOptionData("4"));
		options.add(new PollOptionData("5"));

		// Create poll data
		PollData pollData = new PollData(
				Common.getTestAccount(repository, "alice").getPublicKey(),
				Common.getTestAccount(repository, "alice").getAddress(),
				pollName,
				"Test poll",
				options,
				System.currentTimeMillis()
		);

		// Save to repository
		repository.getVotingRepository().save(pollData);
		repository.saveChanges();
	}

	private void cleanupTestPolls(Repository repository) throws DataException {
		String[] testPollPrefixes = {
				"app-library-APP-rating-TestApp",
				"app-library-APP-rating-VoteTest",
				"app-library-APP-rating-LimitTest",
				"app-library-APP-rating-OffsetTest",
				"app-library-APP-rating-OptionsTest",
				"app-library-APP-rating-MapTest",
				"other-poll-name"
		};

		for (String prefix : testPollPrefixes) {
			try {
				// Try to get and delete polls with this prefix
				List<PollDataWithVotes> polls = repository.getVotingRepository()
						.getPollsByPrefix(prefix, null, null);
				for (PollDataWithVotes poll : polls) {
					repository.getVotingRepository().delete(poll.getPollData().getPollName());
				}
				repository.saveChanges();
			} catch (DataException e) {
				// Ignore errors during cleanup
			}
		}
	}

}
