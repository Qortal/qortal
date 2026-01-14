package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ciyam.at.*;
import org.qortal.account.Account;
import org.qortal.api.resource.CrossChainUtils;
import org.qortal.asset.Asset;
import org.qortal.at.QortalFunctionCode;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.at.ATStateData;
import org.qortal.data.crosschain.CrossChainTradeData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.ciyam.at.OpCode.calcOffset;

/**
 * Cross-chain trade AT
 * 
 * <p>
 * <ul>
 * <li>Bob generates Digibyte & Qortal 'trade' keys
 * 		<ul>
 * 			<li>private key required to sign P2SH redeem tx</li>
 * 			<li>private key could be used to create 'secret' (e.g. double-SHA256)</li>
 * 			<li>encrypted private key could be stored in Qortal AT for access by Bob from any node</li>
 * 		</ul>
 * </li>
 * <li>Bob deploys Qortal AT
 * 		<ul>
 * 		</ul>
 * </li>
 * <li>Alice finds Qortal AT and wants to trade
 * 		<ul>
 * 			<li>Alice generates Digibyte & Qortal 'trade' keys</li>
 * 			<li>Alice funds Digibyte P2SH-A</li>
 * 			<li>Alice sends 'offer' MESSAGE to Bob from her Qortal trade address, containing:
 * 				<ul>
 * 					<li>hash-of-secret-A</li>
 * 					<li>her 'trade' Digibyte PKH</li>
 * 				</ul>
 * 			</li>
 * 		</ul>
 * </li>
 * <li>Bob receives "offer" MESSAGE
 * 		<ul>
 * 			<li>Checks Alice's P2SH-A</li>
 * 			<li>Sends 'trade' MESSAGE to Qortal AT from his trade address, containing:
 * 				<ul>
 * 					<li>Alice's trade Qortal address</li>
 * 					<li>Alice's trade Digibyte PKH</li>
 * 					<li>hash-of-secret-A</li>
 * 				</ul>
 * 			</li>
 * 		</ul>
 * </li>
 * <li>Alice checks Qortal AT to confirm it's locked to her
 * 		<ul>
 * 			<li>Alice sends 'redeem' MESSAGE to Qortal AT from her trade address, containing:
 * 				<ul>
 * 					<li>secret-A</li>
 * 					<li>Qortal receiving address of her choosing</li>
 * 				</ul>
 * 			</li>
 * 			<li>AT's QORT funds are sent to Qortal receiving address</li>
 * 		</ul>
 * </li>
 * <li>Bob checks AT, extracts secret-A
 * 		<ul>
 * 			<li>Bob redeems P2SH-A using his Digibyte trade key and secret-A</li>
 * 			<li>P2SH-A DGB funds end up at Digibyte address determined by redeem transaction output(s)</li>
 * 		</ul>
 * </li>
 * </ul>
 */
public class DigibyteACCTv3 implements ACCT {

	private static final Logger LOGGER = LogManager.getLogger(DigibyteACCTv3.class);

	public static final String NAME = DigibyteACCTv3.class.getSimpleName();
	public static final byte[] CODE_BYTES_HASH = HashCode.fromString("e6a7dcd87296fae3ce7d80183bf7660c8e2cb4f8746c6a0421a17148f87a0e1d").asBytes(); // SHA256 of AT code bytes

	public static final int SECRET_LENGTH = 32;

	/** <b>Value</b> offset into AT segment where 'mode' variable (long) is stored. (Multiply by MachineState.VALUE_SIZE for byte offset). */
	private static final int MODE_VALUE_OFFSET = 61;
	/** <b>Byte</b> offset into AT state data where 'mode' variable (long) is stored. */
	public static final int MODE_BYTE_OFFSET = MachineState.HEADER_LENGTH + (MODE_VALUE_OFFSET * MachineState.VALUE_SIZE);

	public static class OfferMessageData {
		public byte[] partnerDigibytePKH;
		public byte[] hashOfSecretA;
		public long lockTimeA;
	}
	public static final int OFFER_MESSAGE_LENGTH = 20 /*partnerDigibytePKH*/ + 20 /*hashOfSecretA*/ + 8 /*lockTimeA*/;
	public static final int TRADE_MESSAGE_LENGTH = 32 /*partner's Qortal trade address (padded from 25 to 32)*/
			+ 24 /*partner's Digibyte PKH (padded from 20 to 24)*/
			+ 8 /*AT trade timeout (minutes)*/
			+ 24 /*hash of secret-A (padded from 20 to 24)*/
			+ 8 /*lockTimeA*/;
	public static final int REDEEM_MESSAGE_LENGTH = 32 /*secret-A*/ + 32 /*partner's Qortal receiving address padded from 25 to 32*/;
	public static final int CANCEL_MESSAGE_LENGTH = 32 /*AT creator's Qortal address*/;

	private static DigibyteACCTv3 instance;

	private DigibyteACCTv3() {
	}

	public static synchronized DigibyteACCTv3 getInstance() {
		if (instance == null)
			instance = new DigibyteACCTv3();

		return instance;
	}

	@Override
	public byte[] getCodeBytesHash() {
		return CODE_BYTES_HASH;
	}

	@Override
	public int getModeByteOffset() {
		return MODE_BYTE_OFFSET;
	}

	@Override
	public ForeignBlockchain getBlockchain() {
		return Digibyte.getInstance();
	}

	/**
	 * Returns Qortal AT creation bytes for cross-chain trading AT.
	 * <p>
	 * <tt>tradeTimeout</tt> (minutes) is the time window for the trade partner to send the
	 * 32-byte secret to the AT, before the AT automatically refunds the AT's creator.
	 * 
	 * @param creatorTradeAddress AT creator's trade Qortal address
	 * @param digibytePublicKeyHash 20-byte HASH160 of creator's trade Digibyte public key
	 * @param qortAmount how much QORT to pay trade partner if they send correct 32-byte secrets to AT
	 * @param digibyteAmount how much DGB the AT creator is expecting to trade
	 * @param tradeTimeout suggested timeout for entire trade
	 */
	public static byte[] buildQortalAT(String creatorTradeAddress, byte[] digibytePublicKeyHash, long qortAmount, long digibyteAmount, int tradeTimeout) {
		if (digibytePublicKeyHash.length != 20)
			throw new IllegalArgumentException("Digibyte public key hash should be 20 bytes");

		// Labels for data segment addresses
		int addrCounter = 0;

		// Constants (with corresponding dataByteBuffer.put*() calls below)

		final int addrCreatorTradeAddress1 = addrCounter++;
		final int addrCreatorTradeAddress2 = addrCounter++;
		final int addrCreatorTradeAddress3 = addrCounter++;
		final int addrCreatorTradeAddress4 = addrCounter++;

		final int addrDigibytePublicKeyHash = addrCounter;
		addrCounter += 4;

		final int addrQortAmount = addrCounter++;
		final int addrDigibyteAmount = addrCounter++;
		final int addrTradeTimeout = addrCounter++;

		final int addrMessageTxnType = addrCounter++;
		final int addrExpectedTradeMessageLength = addrCounter++;
		final int addrExpectedRedeemMessageLength = addrCounter++;

		final int addrCreatorAddressPointer = addrCounter++;
		final int addrQortalPartnerAddressPointer = addrCounter++;
		final int addrMessageSenderPointer = addrCounter++;

		final int addrTradeMessagePartnerDigibytePKHOffset = addrCounter++;
		final int addrPartnerDigibytePKHPointer = addrCounter++;
		final int addrTradeMessageHashOfSecretAOffset = addrCounter++;
		final int addrHashOfSecretAPointer = addrCounter++;

		final int addrRedeemMessageReceivingAddressOffset = addrCounter++;

		final int addrMessageDataPointer = addrCounter++;
		final int addrMessageDataLength = addrCounter++;

		final int addrPartnerReceivingAddressPointer = addrCounter++;

		final int addrEndOfConstants = addrCounter;

		// Variables

		final int addrCreatorAddress1 = addrCounter++;
		final int addrCreatorAddress2 = addrCounter++;
		final int addrCreatorAddress3 = addrCounter++;
		final int addrCreatorAddress4 = addrCounter++;

		final int addrQortalPartnerAddress1 = addrCounter++;
		final int addrQortalPartnerAddress2 = addrCounter++;
		final int addrQortalPartnerAddress3 = addrCounter++;
		final int addrQortalPartnerAddress4 = addrCounter++;

		final int addrLockTimeA = addrCounter++;
		final int addrRefundTimeout = addrCounter++;
		final int addrRefundTimestamp = addrCounter++;
		final int addrLastTxnTimestamp = addrCounter++;
		final int addrBlockTimestamp = addrCounter++;
		final int addrTxnType = addrCounter++;
		final int addrResult = addrCounter++;

		final int addrMessageSender1 = addrCounter++;
		final int addrMessageSender2 = addrCounter++;
		final int addrMessageSender3 = addrCounter++;
		final int addrMessageSender4 = addrCounter++;

		final int addrMessageLength = addrCounter++;

		final int addrMessageData = addrCounter;
		addrCounter += 4;

		final int addrHashOfSecretA = addrCounter;
		addrCounter += 4;

		final int addrPartnerDigibytePKH = addrCounter;
		addrCounter += 4;

		final int addrPartnerReceivingAddress = addrCounter;
		addrCounter += 4;

		final int addrMode = addrCounter++;
		assert addrMode == MODE_VALUE_OFFSET : String.format("addrMode %d does not match MODE_VALUE_OFFSET %d", addrMode, MODE_VALUE_OFFSET);

		// Data segment
		ByteBuffer dataByteBuffer = ByteBuffer.allocate(addrCounter * MachineState.VALUE_SIZE);

		// AT creator's trade Qortal address, decoded from Base58
		assert dataByteBuffer.position() == addrCreatorTradeAddress1 * MachineState.VALUE_SIZE : "addrCreatorTradeAddress1 incorrect";
		byte[] creatorTradeAddressBytes = Base58.decode(creatorTradeAddress);
		dataByteBuffer.put(Bytes.ensureCapacity(creatorTradeAddressBytes, 32, 0));

		// Digibyte public key hash
		assert dataByteBuffer.position() == addrDigibytePublicKeyHash * MachineState.VALUE_SIZE : "addrDigibytePublicKeyHash incorrect";
		dataByteBuffer.put(Bytes.ensureCapacity(digibytePublicKeyHash, 32, 0));

		// Redeem Qort amount
		assert dataByteBuffer.position() == addrQortAmount * MachineState.VALUE_SIZE : "addrQortAmount incorrect";
		dataByteBuffer.putLong(qortAmount);

		// Expected Digibyte amount
		assert dataByteBuffer.position() == addrDigibyteAmount * MachineState.VALUE_SIZE : "addrDigibyteAmount incorrect";
		dataByteBuffer.putLong(digibyteAmount);

		// Suggested trade timeout (minutes)
		assert dataByteBuffer.position() == addrTradeTimeout * MachineState.VALUE_SIZE : "addrTradeTimeout incorrect";
		dataByteBuffer.putLong(tradeTimeout);

		// We're only interested in MESSAGE transactions
		assert dataByteBuffer.position() == addrMessageTxnType * MachineState.VALUE_SIZE : "addrMessageTxnType incorrect";
		dataByteBuffer.putLong(API.ATTransactionType.MESSAGE.value);

		// Expected length of 'trade' MESSAGE data from AT creator
		assert dataByteBuffer.position() == addrExpectedTradeMessageLength * MachineState.VALUE_SIZE : "addrExpectedTradeMessageLength incorrect";
		dataByteBuffer.putLong(TRADE_MESSAGE_LENGTH);

		// Expected length of 'redeem' MESSAGE data from trade partner
		assert dataByteBuffer.position() == addrExpectedRedeemMessageLength * MachineState.VALUE_SIZE : "addrExpectedRedeemMessageLength incorrect";
		dataByteBuffer.putLong(REDEEM_MESSAGE_LENGTH);

		// Index into data segment of AT creator's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrCreatorAddressPointer * MachineState.VALUE_SIZE : "addrCreatorAddressPointer incorrect";
		dataByteBuffer.putLong(addrCreatorAddress1);

		// Index into data segment of partner's Qortal address, used by SET_B_IND
		assert dataByteBuffer.position() == addrQortalPartnerAddressPointer * MachineState.VALUE_SIZE : "addrQortalPartnerAddressPointer incorrect";
		dataByteBuffer.putLong(addrQortalPartnerAddress1);

		// Index into data segment of (temporary) transaction's sender's address, used by GET_B_IND
		assert dataByteBuffer.position() == addrMessageSenderPointer * MachineState.VALUE_SIZE : "addrMessageSenderPointer incorrect";
		dataByteBuffer.putLong(addrMessageSender1);

		// Offset into 'trade' MESSAGE data payload for extracting partner's Digibyte PKH
		assert dataByteBuffer.position() == addrTradeMessagePartnerDigibytePKHOffset * MachineState.VALUE_SIZE : "addrTradeMessagePartnerDigibytePKHOffset incorrect";
		dataByteBuffer.putLong(32L);

		// Index into data segment of partner's Digibyte PKH, used by GET_B_IND
		assert dataByteBuffer.position() == addrPartnerDigibytePKHPointer * MachineState.VALUE_SIZE : "addrPartnerDigibytePKHPointer incorrect";
		dataByteBuffer.putLong(addrPartnerDigibytePKH);

		// Offset into 'trade' MESSAGE data payload for extracting hash-of-secret-A
		assert dataByteBuffer.position() == addrTradeMessageHashOfSecretAOffset * MachineState.VALUE_SIZE : "addrTradeMessageHashOfSecretAOffset incorrect";
		dataByteBuffer.putLong(64L);

		// Index into data segment to hash of secret A, used by GET_B_IND
		assert dataByteBuffer.position() == addrHashOfSecretAPointer * MachineState.VALUE_SIZE : "addrHashOfSecretAPointer incorrect";
		dataByteBuffer.putLong(addrHashOfSecretA);

		// Offset into 'redeem' MESSAGE data payload for extracting Qortal receiving address
		assert dataByteBuffer.position() == addrRedeemMessageReceivingAddressOffset * MachineState.VALUE_SIZE : "addrRedeemMessageReceivingAddressOffset incorrect";
		dataByteBuffer.putLong(32L);

		// Source location and length for hashing any passed secret
		assert dataByteBuffer.position() == addrMessageDataPointer * MachineState.VALUE_SIZE : "addrMessageDataPointer incorrect";
		dataByteBuffer.putLong(addrMessageData);
		assert dataByteBuffer.position() == addrMessageDataLength * MachineState.VALUE_SIZE : "addrMessageDataLength incorrect";
		dataByteBuffer.putLong(32L);

		// Pointer into data segment of where to save partner's receiving Qortal address, used by GET_B_IND
		assert dataByteBuffer.position() == addrPartnerReceivingAddressPointer * MachineState.VALUE_SIZE : "addrPartnerReceivingAddressPointer incorrect";
		dataByteBuffer.putLong(addrPartnerReceivingAddress);

		assert dataByteBuffer.position() == addrEndOfConstants * MachineState.VALUE_SIZE : "dataByteBuffer position not at end of constants";

		// Code labels
		Integer labelRefund = null;

		Integer labelTradeTxnLoop = null;
		Integer labelCheckTradeTxn = null;
		Integer labelCheckCancelTxn = null;
		Integer labelNotTradeNorCancelTxn = null;
		Integer labelCheckNonRefundTradeTxn = null;
		Integer labelTradeTxnExtract = null;
		Integer labelRedeemTxnLoop = null;
		Integer labelCheckRedeemTxn = null;
		Integer labelCheckRedeemTxnSender = null;
		Integer labelPayout = null;

		ByteBuffer codeByteBuffer = ByteBuffer.allocate(768);

		// Two-pass version
		for (int pass = 0; pass < 2; ++pass) {
			codeByteBuffer.clear();

			try {
				/* Initialization */

				// Use AT creation 'timestamp' as starting point for finding transactions sent to AT
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_CREATION_TIMESTAMP, addrLastTxnTimestamp));

				/* NOP - to ensure DIGIBYTE ACCT is unique */
				codeByteBuffer.put(OpCode.NOP.compile());

				// Load B register with AT creator's address so we can save it into addrCreatorAddress1-4
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_CREATOR_INTO_B));
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrCreatorAddressPointer));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for message from AT creator's trade address containing trade partner details, or AT owner's address to cancel offer */

				/* Transaction processing loop */
				labelTradeTxnLoop = codeByteBuffer.position();

				/* Sleep until message arrives */
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.SLEEP_UNTIL_MESSAGE.value, addrLastTxnTimestamp));

				// Find next transaction (if any) to this AT since the last one (referenced by addrLastTxnTimestamp)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxnTimestamp));
				// If no transaction found, A will be zero. If A is zero, set addrResult to 1, otherwise 0.
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
				// If addrResult is zero (i.e. A is non-zero, transaction was found) then go check transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckTradeTxn)));
				// Stop and wait for next block
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				/* Check transaction */
				labelCheckTradeTxn = codeByteBuffer.position();

				// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxnTimestamp));
				// Extract transaction type (message/payment) from transaction and save type in addrTxnType
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxnType));
				// If transaction type is not MESSAGE type then go look for another transaction
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxnType, addrMessageTxnType, calcOffset(codeByteBuffer, labelTradeTxnLoop)));

				/* Check transaction's sender. We're expecting AT creator's trade address for 'trade' message, or AT creator's own address for 'cancel' message. */

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of message sender's address with AT creator's trade address. If they don't match, check for cancel situation.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrCreatorTradeAddress1, calcOffset(codeByteBuffer, labelCheckCancelTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrCreatorTradeAddress2, calcOffset(codeByteBuffer, labelCheckCancelTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrCreatorTradeAddress3, calcOffset(codeByteBuffer, labelCheckCancelTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrCreatorTradeAddress4, calcOffset(codeByteBuffer, labelCheckCancelTxn)));
				// Message sender's address matches AT creator's trade address so go process 'trade' message
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelCheckNonRefundTradeTxn == null ? 0 : labelCheckNonRefundTradeTxn));

				/* Checking message sender for possible cancel message */
				labelCheckCancelTxn = codeByteBuffer.position();

				// Compare each part of message sender's address with AT creator's address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrCreatorAddress1, calcOffset(codeByteBuffer, labelNotTradeNorCancelTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrCreatorAddress2, calcOffset(codeByteBuffer, labelNotTradeNorCancelTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrCreatorAddress3, calcOffset(codeByteBuffer, labelNotTradeNorCancelTxn)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrCreatorAddress4, calcOffset(codeByteBuffer, labelNotTradeNorCancelTxn)));
				// Partner address is AT creator's address, so cancel offer and finish.
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, AcctMode.CANCELLED.value));
				// We're finished forever (finishing auto-refunds remaining balance to AT creator)
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				/* Not trade nor cancel message */
				labelNotTradeNorCancelTxn = codeByteBuffer.position();

				// Loop to find another transaction
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTradeTxnLoop == null ? 0 : labelTradeTxnLoop));

				/* Possible switch-to-trade-mode message */
				labelCheckNonRefundTradeTxn = codeByteBuffer.position();

				// Check 'trade' message we received has expected number of message bytes
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(QortalFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, addrMessageLength));
				// If message length matches, branch to info extraction code
				codeByteBuffer.put(OpCode.BEQ_DAT.compile(addrMessageLength, addrExpectedTradeMessageLength, calcOffset(codeByteBuffer, labelTradeTxnExtract)));
				// Message length didn't match - go back to finding another 'trade' MESSAGE transaction
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelTradeTxnLoop == null ? 0 : labelTradeTxnLoop));

				/* Extracting info from 'trade' MESSAGE transaction */
				labelTradeTxnExtract = codeByteBuffer.position();

				// Extract message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrQortalPartnerAddress1 (as pointed to by addrQortalPartnerAddressPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrQortalPartnerAddressPointer));

				// Extract trade partner's Digibyte public key hash (PKH) from message into B
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrTradeMessagePartnerDigibytePKHOffset));
				// Store partner's Digibyte PKH (we only really use values from B1-B3)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrPartnerDigibytePKHPointer));
				// Extract AT trade timeout (minutes) (from B4)
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B4, addrRefundTimeout));

				// Grab next 32 bytes
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrTradeMessageHashOfSecretAOffset));

				// Extract hash-of-secret-A (we only really use values from B1-B3)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrHashOfSecretAPointer));
				// Extract lockTime-A (from B4)
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_B4, addrLockTimeA));

				// Calculate trade timeout refund 'timestamp' by adding addrRefundTimeout minutes to this transaction's 'timestamp', then save into addrRefundTimestamp
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.ADD_MINUTES_TO_TIMESTAMP, addrRefundTimestamp, addrLastTxnTimestamp, addrRefundTimeout));

				/* We are in 'trade mode' */
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, AcctMode.TRADING.value));

				// Set restart position to after this opcode
				codeByteBuffer.put(OpCode.SET_PCS.compile());

				/* Loop, waiting for trade timeout or 'redeem' MESSAGE from Qortal trade partner */

				// Fetch current block 'timestamp'
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_BLOCK_TIMESTAMP, addrBlockTimestamp));
				// If we're not past refund 'timestamp' then look for next transaction
				codeByteBuffer.put(OpCode.BLT_DAT.compile(addrBlockTimestamp, addrRefundTimestamp, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				// We're past refund 'timestamp' so go refund everything back to AT creator
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRefund == null ? 0 : labelRefund));

				/* Transaction processing loop */
				labelRedeemTxnLoop = codeByteBuffer.position();

				// Find next transaction to this AT since the last one (if any)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PUT_TX_AFTER_TIMESTAMP_INTO_A, addrLastTxnTimestamp));
				// If no transaction found, A will be zero. If A is zero, set addrComparator to 1, otherwise 0.
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.CHECK_A_IS_ZERO, addrResult));
				// If addrResult is zero (i.e. A is non-zero, transaction was found) then go check transaction
				codeByteBuffer.put(OpCode.BZR_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelCheckRedeemTxn)));
				// Stop and wait for next block
				codeByteBuffer.put(OpCode.STP_IMD.compile());

				/* Check transaction */
				labelCheckRedeemTxn = codeByteBuffer.position();

				// Update our 'last found transaction's timestamp' using 'timestamp' from transaction
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TIMESTAMP_FROM_TX_IN_A, addrLastTxnTimestamp));
				// Extract transaction type (message/payment) from transaction and save type in addrTxnType
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(FunctionCode.GET_TYPE_FROM_TX_IN_A, addrTxnType));
				// If transaction type is not MESSAGE type then go look for another transaction
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrTxnType, addrMessageTxnType, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));

				/* Check message payload length */
				codeByteBuffer.put(OpCode.EXT_FUN_RET.compile(QortalFunctionCode.GET_MESSAGE_LENGTH_FROM_TX_IN_A.value, addrMessageLength));
				// If message length matches, branch to sender checking code
				codeByteBuffer.put(OpCode.BEQ_DAT.compile(addrMessageLength, addrExpectedRedeemMessageLength, calcOffset(codeByteBuffer, labelCheckRedeemTxnSender)));
				// Message length didn't match - go back to finding another 'redeem' MESSAGE transaction
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRedeemTxnLoop == null ? 0 : labelRedeemTxnLoop));

				/* Check transaction's sender */
				labelCheckRedeemTxnSender = codeByteBuffer.position();

				// Extract sender address from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_ADDRESS_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageSender1 (as pointed to by addrMessageSenderPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageSenderPointer));
				// Compare each part of transaction's sender's address with expected address. If they don't match, look for another transaction.
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender1, addrQortalPartnerAddress1, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender2, addrQortalPartnerAddress2, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender3, addrQortalPartnerAddress3, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));
				codeByteBuffer.put(OpCode.BNE_DAT.compile(addrMessageSender4, addrQortalPartnerAddress4, calcOffset(codeByteBuffer, labelRedeemTxnLoop)));

				/* Check 'secret-A' in transaction's message */

				// Extract secret-A from first 32 bytes of message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN.compile(FunctionCode.PUT_MESSAGE_FROM_TX_IN_A_INTO_B));
				// Save B register into data segment starting at addrMessageData (as pointed to by addrMessageDataPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrMessageDataPointer));
				// Load B register with expected hash result (as pointed to by addrHashOfSecretAPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.SET_B_IND, addrHashOfSecretAPointer));
				// Perform HASH160 using source data at addrMessageData. (Location and length specified via addrMessageDataPointer and addrMessageDataLength).
				// Save the equality result (1 if they match, 0 otherwise) into addrResult.
				codeByteBuffer.put(OpCode.EXT_FUN_RET_DAT_2.compile(FunctionCode.CHECK_HASH160_WITH_B, addrResult, addrMessageDataPointer, addrMessageDataLength));
				// If hashes don't match, addrResult will be zero so go find another transaction
				codeByteBuffer.put(OpCode.BNZ_DAT.compile(addrResult, calcOffset(codeByteBuffer, labelPayout)));
				codeByteBuffer.put(OpCode.JMP_ADR.compile(labelRedeemTxnLoop == null ? 0 : labelRedeemTxnLoop));

				/* Success! Pay arranged amount to receiving address */
				labelPayout = codeByteBuffer.position();

				// Extract Qortal receiving address from next 32 bytes of message from transaction into B register
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(QortalFunctionCode.PUT_PARTIAL_MESSAGE_FROM_TX_IN_A_INTO_B.value, addrRedeemMessageReceivingAddressOffset));
				// Save B register into data segment starting at addrPartnerReceivingAddress (as pointed to by addrPartnerReceivingAddressPointer)
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.GET_B_IND, addrPartnerReceivingAddressPointer));
				// Pay AT's balance to receiving address
				codeByteBuffer.put(OpCode.EXT_FUN_DAT.compile(FunctionCode.PAY_TO_ADDRESS_IN_B, addrQortAmount));
				// Set redeemed mode
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, AcctMode.REDEEMED.value));
				// We're finished forever (finishing auto-refunds remaining balance to AT creator)
				codeByteBuffer.put(OpCode.FIN_IMD.compile());

				// Fall-through to refunding any remaining balance back to AT creator

				/* Refund balance back to AT creator */
				labelRefund = codeByteBuffer.position();

				// Set refunded mode
				codeByteBuffer.put(OpCode.SET_VAL.compile(addrMode, AcctMode.REFUNDED.value));
				// We're finished forever (finishing auto-refunds remaining balance to AT creator)
				codeByteBuffer.put(OpCode.FIN_IMD.compile());
			} catch (CompilationException e) {
				throw new IllegalStateException("Unable to compile DGB-QORT ACCT?", e);
			}
		}

		codeByteBuffer.flip();

		byte[] codeBytes = new byte[codeByteBuffer.limit()];
		codeByteBuffer.get(codeBytes);

		assert Arrays.equals(Crypto.digest(codeBytes), DigibyteACCTv3.CODE_BYTES_HASH)
			: String.format("BTCACCT.CODE_BYTES_HASH mismatch: expected %s, actual %s", HashCode.fromBytes(CODE_BYTES_HASH), HashCode.fromBytes(Crypto.digest(codeBytes)));

		final short ciyamAtVersion = 2;
		final short numCallStackPages = 0;
		final short numUserStackPages = 0;
		final long minActivationAmount = 0L;

		return MachineState.toCreationBytes(ciyamAtVersion, codeBytes, dataByteBuffer.array(), numCallStackPages, numUserStackPages, minActivationAmount);
	}

	/**
	 * Returns CrossChainTradeData with useful info extracted from AT.
	 */
	@Override
	public CrossChainTradeData populateTradeData(Repository repository, ATData atData) throws DataException {
		ATStateData atStateData = repository.getATRepository().getLatestATState(atData.getATAddress());
		return populateTradeData(repository, atData.getCreatorPublicKey(), atData.getCreation(), atStateData, OptionalLong.empty());
	}

	@Override
	public List<CrossChainTradeData> populateTradeDataList(Repository repository, List<ATData> atDataList) throws DataException {
		List<CrossChainTradeData> crossChainTradeDataList = CrossChainUtils.populateTradeDataList(repository, this, atDataList);

		return crossChainTradeDataList;
	}

	/**
	 * Returns CrossChainTradeData with useful info extracted from AT.
	 */
	@Override
	public CrossChainTradeData populateTradeData(Repository repository, ATStateData atStateData) throws DataException {
		ATData atData = repository.getATRepository().fromATAddress(atStateData.getATAddress());
		return populateTradeData(repository, atData.getCreatorPublicKey(), atData.getCreation(), atStateData, OptionalLong.empty());
	}

	/**
	 * Returns CrossChainTradeData with useful info extracted from AT.
	 */
	@Override
	public CrossChainTradeData populateTradeData(Repository repository, byte[] creatorPublicKey, long creationTimestamp, ATStateData atStateData, OptionalLong optionalBalance) throws DataException {
		byte[] addressBytes = new byte[25]; // for general use
		String atAddress = atStateData.getATAddress();

		CrossChainTradeData tradeData = new CrossChainTradeData();

		tradeData.foreignBlockchain = SupportedBlockchain.DIGIBYTE.name();
		tradeData.acctName = NAME;

		tradeData.qortalAtAddress = atAddress;
		tradeData.qortalCreator = Crypto.toAddress(creatorPublicKey);
		tradeData.creationTimestamp = creationTimestamp;

		if(optionalBalance.isPresent()) {
			tradeData.qortBalance = optionalBalance.getAsLong();
		}
		else {
			Account atAccount = new Account(repository, atAddress);
			tradeData.qortBalance = atAccount.getConfirmedBalance(Asset.QORT);
		}

		byte[] stateData = atStateData.getStateData();
		ByteBuffer dataByteBuffer = ByteBuffer.wrap(stateData);
		dataByteBuffer.position(MachineState.HEADER_LENGTH);

		/* Constants */

		// Skip creator's trade address
		dataByteBuffer.get(addressBytes);
		tradeData.qortalCreatorTradeAddress = Base58.encode(addressBytes);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - addressBytes.length);

		// Creator's Digibyte/foreign public key hash
		tradeData.creatorForeignPKH = new byte[20];
		dataByteBuffer.get(tradeData.creatorForeignPKH);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - tradeData.creatorForeignPKH.length); // skip to 32 bytes

		// We don't use secret-B
		tradeData.hashOfSecretB = null;

		// Redeem payout
		tradeData.qortAmount = dataByteBuffer.getLong();

		// Expected DGB amount
		tradeData.expectedForeignAmount = dataByteBuffer.getLong();

		// Trade timeout
		tradeData.tradeTimeout = (int) dataByteBuffer.getLong();

		// Skip MESSAGE transaction type
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip expected 'trade' message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip expected 'redeem' message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to creator's address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to partner's Qortal trade address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message sender
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip 'trade' message data offset for partner's Digibyte PKH
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to partner's Digibyte PKH
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip 'trade' message data offset for hash-of-secret-A
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to hash-of-secret-A
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip 'redeem' message data offset for partner's Qortal receiving address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to message data
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip message data length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip pointer to partner's receiving address
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		/* End of constants / begin variables */

		// Skip AT creator's address
		dataByteBuffer.position(dataByteBuffer.position() + 8 * 4);

		// Partner's trade address (if present)
		dataByteBuffer.get(addressBytes);
		String qortalRecipient = Base58.encode(addressBytes);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - addressBytes.length);

		// Potential lockTimeA (if in trade mode)
		int lockTimeA = (int) dataByteBuffer.getLong();

		// AT refund timeout (probably only useful for debugging)
		int refundTimeout = (int) dataByteBuffer.getLong();

		// Trade-mode refund timestamp (AT 'timestamp' converted to Qortal block height)
		long tradeRefundTimestamp = dataByteBuffer.getLong();

		// Skip last transaction timestamp
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip block timestamp
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip transaction type
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip temporary result
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip temporary message sender
		dataByteBuffer.position(dataByteBuffer.position() + 8 * 4);

		// Skip message length
		dataByteBuffer.position(dataByteBuffer.position() + 8);

		// Skip temporary message data
		dataByteBuffer.position(dataByteBuffer.position() + 8 * 4);

		// Potential hash160 of secret A
		byte[] hashOfSecretA = new byte[20];
		dataByteBuffer.get(hashOfSecretA);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - hashOfSecretA.length); // skip to 32 bytes

		// Potential partner's Digibyte PKH
		byte[] partnerDigibytePKH = new byte[20];
		dataByteBuffer.get(partnerDigibytePKH);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - partnerDigibytePKH.length); // skip to 32 bytes

		// Partner's receiving address (if present)
		byte[] partnerReceivingAddress = new byte[25];
		dataByteBuffer.get(partnerReceivingAddress);
		dataByteBuffer.position(dataByteBuffer.position() + 32 - partnerReceivingAddress.length); // skip to 32 bytes

		// Trade AT's 'mode'
		long modeValue = dataByteBuffer.getLong();
		AcctMode mode = AcctMode.valueOf((int) (modeValue & 0xffL));

		/* End of variables */

		if (mode != null && mode != AcctMode.OFFERING) {
			tradeData.mode = mode;
			tradeData.refundTimeout = refundTimeout;
			tradeData.tradeRefundHeight = new Timestamp(tradeRefundTimestamp).blockHeight;
			tradeData.qortalPartnerAddress = qortalRecipient;
			tradeData.hashOfSecretA = hashOfSecretA;
			tradeData.partnerForeignPKH = partnerDigibytePKH;
			tradeData.lockTimeA = lockTimeA;

			if (mode == AcctMode.REDEEMED)
				tradeData.qortalPartnerReceivingAddress = Base58.encode(partnerReceivingAddress);
		} else {
			tradeData.mode = AcctMode.OFFERING;
		}

		tradeData.duplicateDeprecated();

		return tradeData;
	}

	/** Returns info extracted from 'offer' MESSAGE payload sent by trade partner to AT creator's trade address, or null if not valid. */
	public static OfferMessageData extractOfferMessageData(byte[] messageData) {
		if (messageData == null || messageData.length != OFFER_MESSAGE_LENGTH)
			return null;

		OfferMessageData offerMessageData = new OfferMessageData();
		offerMessageData.partnerDigibytePKH = Arrays.copyOfRange(messageData, 0, 20);
		offerMessageData.hashOfSecretA = Arrays.copyOfRange(messageData, 20, 40);
		offerMessageData.lockTimeA = BitTwiddling.longFromBEBytes(messageData, 40);

		return offerMessageData;
	}

	/** Returns 'trade' MESSAGE payload for AT creator to send to AT. */
	public static byte[] buildTradeMessage(String partnerQortalTradeAddress, byte[] partnerBitcoinPKH, byte[] hashOfSecretA, int lockTimeA, int refundTimeout) {
		byte[] data = new byte[TRADE_MESSAGE_LENGTH];
		byte[] partnerQortalAddressBytes = Base58.decode(partnerQortalTradeAddress);
		byte[] lockTimeABytes = BitTwiddling.toBEByteArray((long) lockTimeA);
		byte[] refundTimeoutBytes = BitTwiddling.toBEByteArray((long) refundTimeout);

		System.arraycopy(partnerQortalAddressBytes, 0, data, 0, partnerQortalAddressBytes.length);
		System.arraycopy(partnerBitcoinPKH, 0, data, 32, partnerBitcoinPKH.length);
		System.arraycopy(refundTimeoutBytes, 0, data, 56, refundTimeoutBytes.length);
		System.arraycopy(hashOfSecretA, 0, data, 64, hashOfSecretA.length);
		System.arraycopy(lockTimeABytes, 0, data, 88, lockTimeABytes.length);

		return data;
	}

	/** Returns 'cancel' MESSAGE payload for AT creator to cancel trade AT. */
	@Override
	public byte[] buildCancelMessage(String creatorQortalAddress) {
		byte[] data = new byte[CANCEL_MESSAGE_LENGTH];
		byte[] creatorQortalAddressBytes = Base58.decode(creatorQortalAddress);

		System.arraycopy(creatorQortalAddressBytes, 0, data, 0, creatorQortalAddressBytes.length);

		return data;
	}

	/** Returns 'redeem' MESSAGE payload for trade partner to send to AT. */
	public static byte[] buildRedeemMessage(byte[] secretA, String qortalReceivingAddress) {
		byte[] data = new byte[REDEEM_MESSAGE_LENGTH];
		byte[] qortalReceivingAddressBytes = Base58.decode(qortalReceivingAddress);

		System.arraycopy(secretA, 0, data, 0, secretA.length);
		System.arraycopy(qortalReceivingAddressBytes, 0, data, 32, qortalReceivingAddressBytes.length);

		return data;
	}

	/** Returns refund timeout (minutes) based on trade partner's 'offer' MESSAGE timestamp and P2SH-A locktime. */
	public static int calcRefundTimeout(long offerMessageTimestamp, int lockTimeA) {
		// refund should be triggered halfway between offerMessageTimestamp and lockTimeA
		return (int) ((lockTimeA - (offerMessageTimestamp / 1000L)) / 2L / 60L);
	}

	@Override
	public byte[] findSecretA(Repository repository, CrossChainTradeData crossChainTradeData) throws DataException {
		String atAddress = crossChainTradeData.qortalAtAddress;
		String redeemerAddress = crossChainTradeData.qortalPartnerAddress;

		// We don't have partner's public key so we check every message to AT
		List<MessageTransactionData> messageTransactionsData = repository.getMessageRepository().getMessagesByParticipants(null, atAddress, null, null, null);
		if (messageTransactionsData == null)
			return null;

		// Find 'redeem' message
		for (MessageTransactionData messageTransactionData : messageTransactionsData) {
			// Check message payload type/encryption
			if (messageTransactionData.isText() || messageTransactionData.isEncrypted())
				continue;

			// Check message payload size
			byte[] messageData = messageTransactionData.getData();
			if (messageData.length != REDEEM_MESSAGE_LENGTH)
				// Wrong payload length
				continue;

			// Check sender
			if (!Crypto.toAddress(messageTransactionData.getSenderPublicKey()).equals(redeemerAddress))
				// Wrong sender;
				continue;

			// Extract secretA
			byte[] secretA = new byte[32];
			System.arraycopy(messageData, 0, secretA, 0, secretA.length);

			byte[] hashOfSecretA = Crypto.hash160(secretA);
			if (!Arrays.equals(hashOfSecretA, crossChainTradeData.hashOfSecretA))
				continue;

			return secretA;
		}

		return null;
	}

}
