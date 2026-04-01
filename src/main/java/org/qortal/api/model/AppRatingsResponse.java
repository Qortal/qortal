package org.qortal.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.List;
import java.util.Map;

@Schema(description = "Bulk app ratings response")
@XmlAccessorType(XmlAccessType.FIELD)
public class AppRatingsResponse {

	@Schema(description = "Total number of rating polls")
	public Integer count;

	@Schema(description = "Pagination offset")
	public Integer offset;

	@Schema(description = "Map of poll names to rating data")
	public Map<String, AppRating> ratings;

	// For JAX-RS
	protected AppRatingsResponse() {
	}

	public AppRatingsResponse(Integer count, Integer offset, Map<String, AppRating> ratings) {
		this.count = count;
		this.offset = offset;
		this.ratings = ratings;
	}

	@Schema(description = "App rating data with vote counts")
	@XmlAccessorType(XmlAccessType.FIELD)
	public static class AppRating {
		@Schema(description = "Poll name")
		public String pollName;

		@Schema(description = "Service type (APP or WEBSITE)")
		public String service;

		@Schema(description = "App name")
		public String appName;

		@Schema(description = "Poll owner address")
		public String owner;

		@Schema(description = "Publication timestamp")
		public Long published;

		@Schema(description = "Poll description")
		public String description;

		@Schema(description = "Total number of votes")
		public Integer totalVotes;

		@Schema(description = "Total weight of votes")
		public Integer totalWeight;

		@Schema(description = "List of vote counts for each option")
		public List<PollVotes.OptionCount> voteCounts;

		@Schema(description = "List of vote weights for each option")
		public List<PollVotes.OptionWeight> voteWeights;

		// For JAX-RS
		protected AppRating() {
		}

		public AppRating(String pollName, String service, String appName, String owner, Long published,
				String description, Integer totalVotes, Integer totalWeight,
				List<PollVotes.OptionCount> voteCounts, List<PollVotes.OptionWeight> voteWeights) {
			this.pollName = pollName;
			this.service = service;
			this.appName = appName;
			this.owner = owner;
			this.published = published;
			this.description = description;
			this.totalVotes = totalVotes;
			this.totalWeight = totalWeight;
			this.voteCounts = voteCounts;
			this.voteWeights = voteWeights;
		}
	}
}
