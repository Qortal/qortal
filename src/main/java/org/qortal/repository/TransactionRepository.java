package org.qortal.repository;

import org.qortal.api.resource.TransactionsResource.ConfirmationStatus;
import org.qortal.arbitrary.misc.Service;
import org.qortal.data.group.GroupApprovalData;
import org.qortal.data.transaction.GroupApprovalTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.data.transaction.TransferAssetTransactionData;
import org.qortal.transaction.Transaction.TransactionType;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

public interface TransactionRepository {

	// Fetching transactions / transaction height

	public TransactionData fromSignature(byte[] signature) throws DataException;

	public List<TransactionData> fromSignatures(List<byte[]> signatures) throws DataException;

	public TransactionData fromReference(byte[] reference) throws DataException;

	public TransactionData fromHeightAndSequence(int height, int sequence) throws DataException;

	/** Returns block height containing transaction or 0 if not in a block or transaction doesn't exist */
	public int getHeightFromSignature(byte[] signature) throws DataException;

	public boolean exists(byte[] signature) throws DataException;

	// Transaction participants

	public List<byte[]> getSignaturesInvolvingAddress(String address) throws DataException;

	public void saveParticipants(TransactionData transactionData, List<String> participants) throws DataException;

	public void deleteParticipants(TransactionData transactionData) throws DataException;

	// Searching transactions

	/**
	 * Returns number of each transaction type in blocks from startHeight to endHeight inclusive.
	 * <p>
	 * Note: endHeight >= startHeight
	 * 
	 * @param startHeight height of first block to check
	 * @param endHeight height of last block to check
	 * @return transaction counts, indexed by transaction type value
	 * @throws DataException
	 */
	public Map<TransactionType, Integer> getTransactionSummary(int startHeight, int endHeight) throws DataException;

	/**
	 * Returns signatures for transactions that match search criteria.
	 * <p>
	 * If <tt>blockLimit</tt> is specified, and <tt>startBlock</tt> is <tt>null</tt>,
	 * then <tt>startBlock</tt> is assumed to be 1 or max-block-height,
	 * depending on <tt>reverse</tt> being <tt>false</tt> or <tt>true</tt>
	 * respectively.
	 * 
	 * @param startBlock height of first block to check
	 * @param blockLimit number of blocks (from <tt>startBlock</tt>) to check
	 * @param txGroupId
	 * @param txTypes
	 * @param service arbitrary transaction service ID
	 * @param address
	 * @param confirmationStatus
	 * @param limit
	 * @param offset
	 * @param reverse
	 * @return
	 * @throws DataException
	 */
	public List<byte[]> getSignaturesMatchingCriteria(Integer startBlock, Integer blockLimit, Integer txGroupId,
													  List<TransactionType> txTypes, Service service, String name, String address,
													  ConfirmationStatus confirmationStatus, Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns signatures for transactions that match search criteria.
	 * <p>
	 * Simpler version that only checks accepts one (optional) transaction type,
	 * and one (optional) public key.
	 * 
	 * @param txType
	 * @param publicKey
	 * @param confirmationStatus
	 * @param limit
	 * @param offset
	 * @param reverse
	 * @return
	 * @throws DataException
	 */
	public List<byte[]> getSignaturesMatchingCriteria(TransactionType txType, byte[] publicKey,
			ConfirmationStatus confirmationStatus, Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns signatures for transactions that match search criteria.
	 * <p>
	 * Simpler version that only checks accepts one (optional) transaction type,
	 * and one (optional) public key, within an block height range.
	 * 
	 * @param txType
	 * @param publicKey
	 * @param minBlockHeight
	 * @param maxBlockHeight
	 * @return
	 * @throws DataException
	 */
	public List<byte[]> getSignaturesMatchingCriteria(TransactionType txType, byte[] publicKey,
			Integer minBlockHeight, Integer maxBlockHeight) throws DataException;

	/**
	 * Returns signatures for transactions that match search criteria.
	 * <p>
	 * Alternate version that allows for custom where clauses and bind params.
	 * Only use for very specific use cases, such as the names integrity check.
	 * Not advised to be used otherwise, given that it could be possible for
	 * unsanitized inputs to be passed in if not careful.
	 *
	 * @param txType
	 * @param whereClauses
	 * @param bindParams
	 * @return
	 * @throws DataException
	 */
	public List<byte[]> getSignaturesMatchingCustomCriteria(TransactionType txType, List<String> whereClauses,
															List<Object> bindParams) throws DataException;

	/**
	 * Returns signatures for transactions that match search criteria, with optional limit.
	 * <p>
	 * Alternate version that allows for custom where clauses and bind params.
	 * Only use for very specific use cases, such as the names integrity check.
	 * Not advised to be used otherwise, given that it could be possible for
	 * unsanitized inputs to be passed in if not careful.
	 *
	 * @param txType
	 * @param whereClauses
	 * @param bindParams
	 * @return
	 * @throws DataException
	 */
	public List<byte[]> getSignaturesMatchingCustomCriteria(TransactionType txType, List<String> whereClauses,
															List<Object> bindParams, Integer limit) throws DataException;

	/**
	 * Returns signature for latest auto-update transaction.
	 * <p>
	 * Transaction must be <tt>CONFIRMED</tt> and <tt>APPROVED</tt>
	 * and also <b>not</b> created by group admin/owner.
	 * <p>
	 * We can check the latter by testing for transaction's <tt>approvalHeight</tt>
	 * being greater than <tt>blockHeight</tt>.
	 * 
	 * @param txType
	 * @param txGroupId
	 * @param service
	 * @return
	 * @throws DataException
	 */
	public byte[] getLatestAutoUpdateTransaction(TransactionType txType, int txGroupId, Integer service) throws DataException;

	/**
	 * Returns signatures for all name-registration related transactions relating to supplied name.
	 * Note: this does not currently include ARBITRARY data relating to the name.
	 *
	 * @param name
	 * @param confirmationStatus
	 * @return
	 * @throws DataException
	 */
	public List<TransactionData> getTransactionsInvolvingName(String name, ConfirmationStatus confirmationStatus) throws DataException;

	/**
	 * Returns list of transactions relating to specific asset ID.
	 * 
	 * @param assetId
	 * @param confirmationStatus
	 * @param limit
	 * @param offset
	 * @param reverse
	 * @return list of transactions, or empty if none
	 */
	public List<TransactionData> getAssetTransactions(long assetId, ConfirmationStatus confirmationStatus, Integer limit, Integer offset, Boolean reverse)
			throws DataException;

	/**
	 * Returns list of TRANSFER_ASSET transactions relating to specific asset ID, with optional address filter.
	 * 
	 * @param assetId
	 * @param address
	 * @param limit
	 * @param offset
	 * @param reverse
	 * @return list of transactions, or empty if none
	 */
	public List<TransferAssetTransactionData> getAssetTransfers(long assetId, String address, Integer limit, Integer offset, Boolean reverse)
			throws DataException;

	/**
	 * Returns list of reward share transaction creators, excluding self shares.
	 * This uses confirmed transactions only.
	 *
	 * @return
	 * @throws DataException
	 */
	public List<String> getConfirmedRewardShareCreatorsExcludingSelfShares() throws DataException;

	/**
	 * Returns list of transfer asset transaction creators.
	 * This uses confirmed transactions only.
	 *
	 * @return
	 * @throws DataException
	 */
	public List<String> getConfirmedTransferAssetCreators() throws DataException;

	/**
	 * Returns payment transactions between a specific recipient and sender.
	 * <p>
	 * This is optimized for querying PAYMENT transactions where we need to filter
	 * by recipient address and/or sender's address. At least one address is required.
	 * 
	 * @param recipientAddress the recipient's address (optional, but at least one address required)
	 * @param senderAddress the sender's address (optional, but at least one address required)
	 * @param amount the payment amount to filter by (optional)
	 * @param startBlock height of first block to check (optional)
	 * @param blockLimit number of blocks from startBlock to check (optional)
	 * @param confirmationStatus whether to include confirmed, unconfirmed or both
	 * @param limit maximum number of results
	 * @param offset result offset for pagination
	 * @param reverse whether to reverse the sort order
	 * @return list of payment transaction data
	 * @throws DataException
	 */
	public List<TransactionData> getPaymentsBetweenAddresses(String recipientAddress, String senderAddress,
			Long amount, Integer startBlock, Integer blockLimit, ConfirmationStatus confirmationStatus, 
			Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns list of transactions pending approval, with optional txGgroupId filtering.
	 * <p>
	 * This is typically called by the API.
	 * 
	 * @param txGroupId
	 * @param limit
	 * @param offset
	 * @param reverse
	 * @return list of transactions, or empty if none.
	 * @throws DataException
	 */
	public List<TransactionData> getApprovalPendingTransactions(Integer txGroupId, Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns list of transactions pending approval that can be resolved as of passed blockHeight.
	 * <p>
	 * Typically called by Block.process().
	 * 
	 * @param blockHeight
	 * @return
	 * @throws DataException
	 */
	public List<TransactionData> getApprovalPendingTransactions(int blockHeight) throws DataException;

	/**
	 * Returns list of transactions that have now expired as of passed blockHeight.
	 * <p>
	 * Typically called by Block.process().
	 * 
	 * @param blockHeight
	 * @return
	 * @throws DataException
	 */
	public List<TransactionData> getApprovalExpiringTransactions(int blockHeight) throws DataException;

	/** Returns list of transactions that had group-approval decided at passed block height. */
	public List<TransactionData> getApprovalTransactionDecidedAtHeight(int approvalHeight) throws DataException;

	/** Returns latest approval decision by given admin for given pending transaction signature. */
	public GroupApprovalTransactionData getLatestApproval(byte[] pendingSignature, byte[] adminPublicKey) throws DataException;

	/**
	 * Returns list of latest approval decisions for given pending transaction signature.
	 * 
	 * @param pendingSignature
	 * @return
	 * @throws DataException
	 */
	public GroupApprovalData getApprovalData(byte[] pendingSignature) throws DataException;

	/**
	 * Returns whether transaction is confirmed or not.
	 * 
	 * @param signature
	 * @return true if confirmed, false if not.
	 */
	public boolean isConfirmed(byte[] signature) throws DataException;

	/**
	 * Returns list of unconfirmed transaction signatures in timestamp-else-signature order.
	 * 
	 * @return list of transaction signatures, or empty if none.
	 * @throws DataException
	 */
	public List<byte[]> getUnconfirmedTransactionSignatures() throws DataException;

	/**
	 * Returns list of unconfirmed transactions in timestamp-else-signature order.
	 * <p>
	 * This is typically called by the API.
	 * 
	 * @param limit
	 * @param offset
	 * @param reverse
	 * @return list of transactions, or empty if none.
	 * @throws DataException
	 */
	public List<TransactionData> getUnconfirmedTransactions(List<TransactionType> txTypes, byte[] creatorPublicKey,
															Integer limit, Integer offset, Boolean reverse) throws DataException;

	/**
	 * Returns list of unconfirmed transactions in timestamp-else-signature order.
	 * 
	 * @return list of transactions, or empty if none.
	 * @throws DataException
	 */
	public default List<TransactionData> getUnconfirmedTransactions() throws DataException {
		return getUnconfirmedTransactions(null, null, null, null, null);
	}

	/**
	 * Returns list of unconfirmed transactions with specified type and/or creator.
	 * <p>
	 * At least one of <tt>txType</tt> or <tt>creatorPublicKey</tt> must be non-null.
	 * 
	 * @param txType optional
	 * @param creatorPublicKey optional
	 * @return list of transactions, or empty if none.
	 * @throws DataException
	 */
	public List<TransactionData> getUnconfirmedTransactions(TransactionType txType, byte[] creatorPublicKey) throws DataException;

	/**
	 * Returns list of unconfirmed transactions excluding specified type(s).
	 * 
	 * @return list of transactions, or empty if none.
	 * @throws DataException
	 */
	public List<TransactionData> getUnconfirmedTransactions(EnumSet<TransactionType> excludedTxTypes, Integer limit) throws DataException;

	/**
	 * Returns list of unconfirmed transactions created before the given timestamp.
	 * Used to efficiently pre-filter expiry candidates without loading the full pool.
	 *
	 * @param timestamp only return transactions with created_when &lt; this value
	 * @return list of transactions, or empty if none.
	 * @throws DataException
	 */
	public List<TransactionData> getUnconfirmedTransactionsCreatedBefore(long timestamp) throws DataException;

	/**
	 * Remove transaction from unconfirmed transactions pile.
	 * 
	 * @param signature
	 * @throws DataException
	 */
	public void confirmTransaction(byte[] signature) throws DataException;

	public void updateBlockHeight(byte[] signature, Integer height) throws DataException;

	public void updateBlockSequence(byte[] signature, Integer sequence) throws DataException;

	public void updateApprovalHeight(byte[] signature, Integer approvalHeight) throws DataException;

	/**
	 * Add transaction to unconfirmed transactions pile.
	 * 
	 * @param transactionData
	 * @throws DataException
	 */
	public void unconfirmTransaction(TransactionData transactionData) throws DataException;

	public void save(TransactionData transactionData) throws DataException;

	public void delete(TransactionData transactionData) throws DataException;


}
