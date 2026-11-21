package org.qortal.test.api;

import org.junit.Before;
import org.junit.Test;
import org.qortal.api.ApiError;
import org.qortal.api.ApiException;
import org.qortal.api.resource.TransactionsResource;
import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.test.common.ApiCommon;
import org.qortal.transaction.Transaction.TransactionType;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TransactionsApiTests extends ApiCommon {

	private TransactionsResource transactionsResource;

	@Before
	public void buildResource() {
		this.transactionsResource = (TransactionsResource) ApiCommon.buildResource(TransactionsResource.class);
	}

	@Test
	public void test() {
		assertNotNull(this.transactionsResource);
	}

	@Test
	public void paymentSearchRejectsFractionalUnitsAndOverflow() {
		for (String amount : List.of("0.000000001", "92233720368.54775808", "-92233720368.54775809")) {
			try {
				this.transactionsResource.getPaymentsBetweenAddresses(aliceAddress, null, amount,
						null, null, ConfirmationStatus.CONFIRMED, null, null, null);
				fail("Invalid amount must not reach the repository query");
			} catch (ApiException e) {
				assertEquals(ApiError.INVALID_CRITERIA.getCode(), e.error);
			}
		}
	}

	@Test
	public void testGetPendingTransactions() {
		for (Integer txGroupId : Arrays.asList(null, 0, 1)) {
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, null, null, null));
			assertNotNull(this.transactionsResource.getPendingTransactions(txGroupId, 1, 1, true));
		}
	}

	@Test
	public void testGetUnconfirmedTransactions() {
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, null, null, null));
		assertNotNull(this.transactionsResource.getUnconfirmedTransactions(null, null, 1, 1, true));
	}

	@Test
	public void testSearchTransactions() {
		List<TransactionType> txTypes = Arrays.asList(TransactionType.PAYMENT, TransactionType.ISSUE_ASSET);

		for (Integer startBlock : Arrays.asList(null, 1))
			for (Integer blockLimit : Arrays.asList(null, 1))
				for (Integer txGroupId : Arrays.asList(null, 1))
					for (String address : Arrays.asList(null, aliceAddress))
						for (ConfirmationStatus confirmationStatus : ConfirmationStatus.values()) {
							if (confirmationStatus != ConfirmationStatus.CONFIRMED) {
								startBlock = null;
								blockLimit = null;
							}

							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, null, null, null));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, txTypes, address, confirmationStatus, 1, 1, true));
							assertNotNull(this.transactionsResource.searchTransactions(startBlock, blockLimit, txGroupId, null, address, confirmationStatus, 1, 1, true));
						}
	}

}
