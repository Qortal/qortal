package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.model.AppRatingsResponse;
import org.qortal.api.resource.PollsResource;
import org.qortal.data.voting.PollData;
import org.qortal.data.voting.PollDataWithVotes;
import org.qortal.data.voting.PollOptionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryManager;
import org.qortal.test.common.ApiCommon;
import org.qortal.test.common.Common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class PollsApiTests extends ApiCommon {

	private PollsResource pollsResource;

	@Before
	public void buildResource() {
		this.pollsResource = (PollsResource) ApiCommon.buildResource(PollsResource.class);
	}

	@Test
	public void testResource() {
		assertNotNull(this.pollsResource);
	}

	@Test
	public void testGetAppRatings() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create test app rating polls
			createTestAppRatingPoll(repository, "app-library-APP-rating-Q-Tube");
			createTestAppRatingPoll(repository, "app-library-WEBSITE-rating-Q-Blog");
			createTestAppRatingPoll(repository, "app-library-APP-rating-Q-Chat");

			// Test getting all app ratings
			AppRatingsResponse response = this.pollsResource.getAppRatings(null, null, null, null, null);
			assertNotNull(response);
			assertNotNull(response.ratings);
			assertTrue(response.count >= 3);

			// Verify response contains our test polls
			assertTrue(response.ratings.containsKey("app-library-APP-rating-Q-Tube"));
			assertTrue(response.ratings.containsKey("app-library-WEBSITE-rating-Q-Blog"));
			assertTrue(response.ratings.containsKey("app-library-APP-rating-Q-Chat"));

			// Clean up
			deleteTestPoll(repository, "app-library-APP-rating-Q-Tube");
			deleteTestPoll(repository, "app-library-WEBSITE-rating-Q-Blog");
			deleteTestPoll(repository, "app-library-APP-rating-Q-Chat");
		}
	}

	@Test
	public void testGetAppRatingsWithServiceFilter() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create test polls
			createTestAppRatingPoll(repository, "app-library-APP-rating-Test1");
			createTestAppRatingPoll(repository, "app-library-WEBSITE-rating-Test2");

			// Test filtering by APP service
			AppRatingsResponse appResponse = this.pollsResource.getAppRatings("APP", null, null, null, null);
			assertNotNull(appResponse);
			assertTrue(appResponse.ratings.containsKey("app-library-APP-rating-Test1"));
			assertFalse(appResponse.ratings.containsKey("app-library-WEBSITE-rating-Test2"));

			// Test filtering by WEBSITE service
			AppRatingsResponse websiteResponse = this.pollsResource.getAppRatings("WEBSITE", null, null, null, null);
			assertNotNull(websiteResponse);
			assertFalse(websiteResponse.ratings.containsKey("app-library-APP-rating-Test1"));
			assertTrue(websiteResponse.ratings.containsKey("app-library-WEBSITE-rating-Test2"));

			// Clean up
			deleteTestPoll(repository, "app-library-APP-rating-Test1");
			deleteTestPoll(repository, "app-library-WEBSITE-rating-Test2");
		}
	}

	@Test
	public void testGetAppRatingsWithPagination() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			// Create multiple test polls
			for (int i = 1; i <= 5; i++) {
				createTestAppRatingPoll(repository, "app-library-APP-rating-TestApp" + i);
			}

			// Test with limit
			AppRatingsResponse response = this.pollsResource.getAppRatings(null, 2, null, null, null);
			assertNotNull(response);
			assertTrue(response.ratings.size() <= 2);

			// Test with offset
			AppRatingsResponse offsetResponse = this.pollsResource.getAppRatings(null, 2, 1, null, null);
			assertNotNull(offsetResponse);
			assertEquals(Integer.valueOf(1), offsetResponse.offset);

			// Clean up
			for (int i = 1; i <= 5; i++) {
				deleteTestPoll(repository, "app-library-APP-rating-TestApp" + i);
			}
		}
	}

	@Test
	public void testGetAppRatingsResponseStructure() throws DataException {
		try (final Repository repository = RepositoryManager.getRepository()) {
			String pollName = "app-library-APP-rating-StructureTest";
			createTestAppRatingPoll(repository, pollName);

			AppRatingsResponse response = this.pollsResource.getAppRatings(null, null, null, null, null);
			assertNotNull(response);
			assertTrue(response.ratings.containsKey(pollName));

			AppRatingsResponse.AppRating rating = response.ratings.get(pollName);
			assertNotNull(rating);
			assertEquals(pollName, rating.pollName);
			assertEquals("APP", rating.service);
			assertEquals("StructureTest", rating.appName);
			assertNotNull(rating.owner);
			assertNotNull(rating.published);
			assertNotNull(rating.totalVotes);
			assertNotNull(rating.totalWeight);
			assertNotNull(rating.voteCounts);
			assertNotNull(rating.voteWeights);

			// Clean up
			deleteTestPoll(repository, pollName);
		}
	}

	@Test
	public void testGetAppRatingsEmptyResult() {
		// Test with non-existent service type
		AppRatingsResponse response = this.pollsResource.getAppRatings(null, null, null, null, null);
		assertNotNull(response);
		assertNotNull(response.ratings);
		// Should return successfully even if empty
	}

	// Helper methods

	private void createTestAppRatingPoll(Repository repository, String pollName) throws DataException {
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
				aliceAddress,
				pollName,
				"Test app rating poll",
				options,
				System.currentTimeMillis()
		);

		// Save to repository
		repository.getVotingRepository().save(pollData);
		repository.saveChanges();
	}

	private void deleteTestPoll(Repository repository, String pollName) throws DataException {
		try {
			repository.getVotingRepository().delete(pollName);
			repository.saveChanges();
		} catch (DataException e) {
			// Ignore if poll doesn't exist
		}
	}

}
