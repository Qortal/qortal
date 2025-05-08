package org.qortal.test.api;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.qortal.api.resource.AddressesResource;
import org.qortal.test.common.ApiCommon;

import java.util.Collections;

import static org.junit.Assert.assertNotNull;

public class AddressesApiTests extends ApiCommon {

	private AddressesResource addressesResource;

	@Before
	public void buildResource() {
		this.addressesResource = (AddressesResource) ApiCommon.buildResource(AddressesResource.class);
	}

	@Test
	public void testGetAccountInfo() {
		assertNotNull(this.addressesResource.getAccountInfo(aliceAddress));
	}

	@Test
	@Ignore(value = "No Logic Coded")
	public void testGetOnlineAccounts() {
		// Add and remove users as online checking count after minting
		// TODO: Need to construct logic
		// this.addressesResource.getOnlineAccounts(), empty Array, Size = 0
		assertNotNull(this.addressesResource.getOnlineAccounts());
		int blocksToMint = 5;
		// Add 2 accounts to the online array, mint some blocks
		// Assert number of accountsOnline == 2
		// Remove an account from onlineStatus, mint some blocks
		// Assert number of accountsOnline == 1
		// Add two accounts as online, mint some blocks
		// Asset number of accountsOnline == 3
	}

	@Test
	public void testGetRewardShares() {
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), null, null, null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(null, Collections.singletonList(aliceAddress), null, null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), null, null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(null, null, Collections.singletonList(aliceAddress), null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), null, null, null));
		assertNotNull(this.addressesResource.getRewardShares(Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), Collections.singletonList(aliceAddress), 1, 1, true));
	}

}
