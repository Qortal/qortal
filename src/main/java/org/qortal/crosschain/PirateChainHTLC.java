package org.qortal.crosschain;

import com.google.common.hash.HashCode;
import com.google.common.primitives.Bytes;
import org.bitcoinj.core.*;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptChunk;
import org.qortal.crypto.Crypto;
import org.qortal.utils.Base58;
import org.qortal.utils.BitTwiddling;

import java.util.*;

import static org.qortal.crosschain.BitcoinyHTLC.Status;

public class PirateChainHTLC {

	public static final int SECRET_LENGTH = 32;
	public static final int MIN_LOCKTIME = 1500000000;

	public static final long NO_LOCKTIME_NO_RBF_SEQUENCE = 0xFFFFFFFFL;
	public static final long LOCKTIME_NO_RBF_SEQUENCE = NO_LOCKTIME_NO_RBF_SEQUENCE - 1;

	// Assuming node's trade-bot has no more than 100 entries?
	private static final int MAX_CACHE_ENTRIES = 100;

	// Max time-to-live for cache entries (milliseconds)
	private static final long CACHE_TIMEOUT = 30_000L;

	@SuppressWarnings("serial")
	private static final Map<String, byte[]> SECRET_CACHE = Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, 0.75F, true) {
		// This method is called just after a new entry has been added
		@Override
		public boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
			return size() > MAX_CACHE_ENTRIES;
		}
	});
	private static final byte[] NO_SECRET_CACHE_ENTRY = new byte[0];

	@SuppressWarnings("serial")
	private static final Map<String, Status> STATUS_CACHE = Collections.synchronizedMap(new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, 0.75F, true) {
		// This method is called just after a new entry has been added
		@Override
		public boolean removeEldestEntry(Map.Entry<String, Status> eldest) {
			return size() > MAX_CACHE_ENTRIES;
		}
	});

	/*
	 * OP_RETURN + OP_PUSHDATA1 + bytes (not part of actual redeem script - used for "push only" secondary output when funding P2SH)
	 *
	 * OP_IF (if top stack value isn't false) (true=refund; false=redeem) (boolean is then removed from stack)
	 * 		<push 4 bytes> <intended locktime>
	 * 		OP_CHECKLOCKTIMEVERIFY (if stack locktime greater than transaction's lock time - i.e. refunding but too soon - then fail validation)
	 * 		OP_DROP (remove locktime from top of stack)
	 * 		<push 33 bytes> <intended refunder public key>
	 * 		OP_CHECKSIG (check signature and public key are correct; returns 1 or 0)
	 * OP_ELSE (if top stack value was false, i.e. attempting to redeem)
	 * 		OP_SIZE (push length of top item - the secret - to the top of the stack)
	 * 		<push 1 byte> 32
	 * 		OP_EQUALVERIFY (unhashed secret must be 32 bytes in length)
	 * 		OP_HASH160 (hash the secret)
	 * 		<push 20 bytes> <intended secret hash>
	 * 		OP_EQUALVERIFY (ensure hash of supplied secret matches intended secret hash; transaction invalid if no match)
	 * 		<push 33 bytes> <intended redeemer public key>
	 * 		OP_CHECKSIG (check signature and public key are correct; returns 1 or 0)
	 * OP_ENDIF
	 */

	private static final byte[] pushOnlyPrefix = HashCode.fromString("6a4c").asBytes(); // OP_RETURN + push(redeem script)
	private static final byte[] redeemScript1 = HashCode.fromString("6304").asBytes(); // OP_IF push(4 bytes locktime)
	private static final byte[] redeemScript2 = HashCode.fromString("b17521").asBytes(); // OP_CHECKLOCKTIMEVERIFY OP_DROP push(33 bytes refund pubkey)
	private static final byte[] redeemScript3 = HashCode.fromString("ac6782012088a914").asBytes(); // OP_CHECKSIG OP_ELSE OP_SIZE push(0x20) OP_EQUALVERIFY OP_HASH160 push(20 bytes hash of secret)
	private static final byte[] redeemScript4 = HashCode.fromString("8821").asBytes(); // OP_EQUALVERIFY push(33 bytes redeem pubkey)
	private static final byte[] redeemScript5 = HashCode.fromString("ac68").asBytes(); // OP_CHECKSIG OP_ENDIF

	/**
	 * Returns redeemScript used for cross-chain trading.
	 * <p>
	 * See comments in {@link PirateChainHTLC} for more details.
	 * 
	 * @param refunderPubKey 33-byte P2SH funder's public key, for refunding purposes
	 * @param lockTime seconds-since-epoch threshold, after which P2SH funder can claim refund
	 * @param redeemerPubKey 33-byte P2SH redeemer's public key
	 * @param hashOfSecret 20-byte HASH160 of secret, used by P2SH redeemer to claim funds
	 */
	public static byte[] buildScript(byte[] refunderPubKey, int lockTime, byte[] redeemerPubKey, byte[] hashOfSecret) {
		return Bytes.concat(redeemScript1, BitTwiddling.toLEByteArray((int) (lockTime & 0xffffffffL)), redeemScript2,
				refunderPubKey, redeemScript3, hashOfSecret, redeemScript4, redeemerPubKey, redeemScript5);
	}

	/**
	 * Alternative to buildScript() above, this time with a prefix suitable for adding the redeem script
	 * to a "push only" output (via OP_RETURN followed by OP_PUSHDATA1)
	 *
	 * @param refunderPubKey 33-byte P2SH funder's public key, for refunding purposes
	 * @param lockTime seconds-since-epoch threshold, after which P2SH funder can claim refund
	 * @param redeemerPubKey 33-byte P2SH redeemer's public key
	 * @param hashOfSecret 20-byte HASH160 of secret, used by P2SH redeemer to claim funds
	 * @return
	 */
	public static byte[] buildScriptWithPrefix(byte[] refunderPubKey, int lockTime, byte[] redeemerPubKey, byte[] hashOfSecret) {
		byte[] redeemScript = buildScript(refunderPubKey, lockTime, redeemerPubKey, hashOfSecret);
		int size = redeemScript.length;
		String sizeHex = Integer.toHexString(size & 0xFF);
		return Bytes.concat(pushOnlyPrefix, HashCode.fromString(sizeHex).asBytes(), redeemScript);
	}

	/**
	 * Returns 'secret', if any, given HTLC's P2SH address.
	 * <p>
	 * @throws ForeignBlockchainException
	 */
	public static byte[] findHtlcSecret(Bitcoiny bitcoiny, String p2shAddress) throws ForeignBlockchainException {
		NetworkParameters params = bitcoiny.getNetworkParameters();
		String compoundKey = String.format("%s-%s-%d", params.getId(), p2shAddress, System.currentTimeMillis() / CACHE_TIMEOUT);

		byte[] secret = SECRET_CACHE.getOrDefault(compoundKey, NO_SECRET_CACHE_ENTRY);
		if (secret != NO_SECRET_CACHE_ENTRY)
			return secret;

		List<byte[]> rawTransactions = bitcoiny.getAddressTransactions(p2shAddress);

		for (byte[] rawTransaction : rawTransactions) {
			Transaction transaction = new Transaction(params, rawTransaction);

			// Cycle through inputs, looking for one that spends our HTLC
			for (TransactionInput input : transaction.getInputs()) {
				Script scriptSig = input.getScriptSig();
				List<ScriptChunk> scriptChunks = scriptSig.getChunks();

				// Expected number of script chunks for redeem. Refund might not have the same number.
				int expectedChunkCount = 1 /*secret*/ + 1 /*sig*/ + 1 /*pubkey*/ + 1 /*redeemScript*/;
				if (scriptChunks.size() != expectedChunkCount)
					continue;

				// We're expecting last chunk to contain the actual redeemScript
				ScriptChunk lastChunk = scriptChunks.get(scriptChunks.size() - 1);
				byte[] redeemScriptBytes = lastChunk.data;

				// If non-push scripts, redeemScript will be null
				if (redeemScriptBytes == null)
					continue;

				byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
				Address inputAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

				if (!inputAddress.toString().equals(p2shAddress))
					// Input isn't spending our HTLC
					continue;

				secret = scriptChunks.get(0).data;
				if (secret.length != PirateChainHTLC.SECRET_LENGTH)
					continue;

				// Cache secret for a while
				SECRET_CACHE.put(compoundKey, secret);

				return secret;
			}
		}

		// Cache negative result
		SECRET_CACHE.put(compoundKey, null);

		return null;
	}

	/**
	 * Returns a string containing the txid of the transaction that funded supplied <tt>p2shAddress</tt>
	 * We have to do this in a bit of a roundabout way due to the Pirate Light Client server omitting
	 * transaction hashes from the raw transaction data.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public static String getFundingTxid(BitcoinyBlockchainProvider blockchain, String p2shAddress) throws ForeignBlockchainException {
		byte[] ourScriptPubKey = addressToScriptPubKey(p2shAddress);
		// HASH160(redeem script) for this p2shAddress
		byte[] ourRedeemScriptHash = addressToRedeemScriptHash(p2shAddress);


		// Firstly look for an unspent output

		// Note: we can't include unconfirmed transactions here because the Pirate light wallet server requires a block range
		List<UnspentOutput> unspentOutputs = blockchain.getUnspentOutputs(p2shAddress, false);
		for (UnspentOutput unspentOutput : unspentOutputs) {

			if (!Arrays.equals(ourScriptPubKey, unspentOutput.script)) {
				continue;
			}

			return HashCode.fromBytes(unspentOutput.hash).toString();
		}


		// No valid unspent outputs, so must be already spent...

		// Note: we can't include unconfirmed transactions here because the Pirate light wallet server requires a block range
		List<BitcoinyTransaction> transactions = blockchain.getAddressBitcoinyTransactions(p2shAddress, BitcoinyBlockchainProvider.EXCLUDE_UNCONFIRMED);

		// Sort by confirmed first, followed by ascending height
		transactions.sort(BitcoinyTransaction.CONFIRMED_FIRST.thenComparing(BitcoinyTransaction::getHeight));

		for (BitcoinyTransaction bitcoinyTransaction : transactions) {

			// Acceptable funding is one transaction output, so we're expecting only one input
			if (bitcoinyTransaction.inputs.size() != 1)
				// Wrong number of inputs
				continue;

			String scriptSig = bitcoinyTransaction.inputs.get(0).scriptSig;

			List<byte[]> scriptSigChunks = extractScriptSigChunks(HashCode.fromString(scriptSig).asBytes());
			if (scriptSigChunks.size() < 3 || scriptSigChunks.size() > 4)
				// Not valid chunks for our form of HTLC
				continue;

			// Last chunk is redeem script
			byte[] redeemScriptBytes = scriptSigChunks.get(scriptSigChunks.size() - 1);
			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
			if (!Arrays.equals(redeemScriptHash, ourRedeemScriptHash))
				// Not spending our specific HTLC redeem script
				continue;

			return bitcoinyTransaction.inputs.get(0).outputTxHash;

		}

		return null;
	}

	/**
	 * Returns a string containing the unspent txid of the transaction that funded supplied <tt>p2shAddress</tt>
	 * and is at least the value specified in <tt>minimumAmount</tt>
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public static String getUnspentFundingTxid(BitcoinyBlockchainProvider blockchain, String p2shAddress, long minimumAmount) throws ForeignBlockchainException {
		UnspentOutput funding = getUnspentFundingOutput(blockchain, p2shAddress, minimumAmount);
		return funding == null ? null : HashCode.fromBytes(funding.hash).toString();
	}

	/** Return the complete outpoint and value, retaining the funding output index. */
	public static UnspentOutput getUnspentFundingOutput(BitcoinyBlockchainProvider blockchain, String p2shAddress, long minimumAmount) throws ForeignBlockchainException {
		byte[] ourScriptPubKey = addressToScriptPubKey(p2shAddress);

		// Note: we can't include unconfirmed transactions here because the Pirate light wallet server requires a block range
		List<UnspentOutput> unspentOutputs = blockchain.getUnspentOutputs(p2shAddress, false);
		for (UnspentOutput unspentOutput : unspentOutputs) {

			if (!Arrays.equals(ourScriptPubKey, unspentOutput.script)) {
				// Not funding our specific HTLC script hash
				continue;
			}

			if (unspentOutput.value < minimumAmount) {
				// Not funding the required amount
				continue;
			}

			return unspentOutput;
		}


		// No valid unspent outputs, so must be already spent
		return null;
	}

	/** Locate the HTLC output immediately after funding, including nonzero vout. */
	public static UnspentOutput getFundingOutput(BitcoinyBlockchainProvider blockchain, String txid, String address, long minimumAmount) throws ForeignBlockchainException {
		// Funding ACKs use display-order txids; lightwalletd expects internal byte order.
		byte[] hash = HashCode.fromString(txid).asBytes();
		com.google.common.primitives.Bytes.reverse(hash);
		BitcoinyTransaction transaction;
		try {
			transaction = PirateChainTransaction.read(blockchain.getRawTransaction(hash)).transaction;
		} catch (org.qortal.transform.TransformationException e) {
			throw new ForeignBlockchainException("Unable to parse ARRR funding transaction: " + e.getMessage());
		}
		String script = HashCode.fromBytes(addressToScriptPubKey(address)).toString();
		for (int index = 0; index < transaction.outputs.size(); ++index) {
			BitcoinyTransaction.Output output = transaction.outputs.get(index);
			if (script.equals(output.scriptPubKey) && output.value >= minimumAmount)
				return new UnspentOutput(hash, index, 0, output.value);
		}
		throw new ForeignBlockchainException("Funding transaction has no matching ARRR HTLC output");
	}

	/**
	 * Returns HTLC status, given P2SH address and expected redeem/refund amount
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public static Status determineHtlcStatus(BitcoinyBlockchainProvider blockchain, String p2shAddress, long minimumAmount) throws ForeignBlockchainException {
		return determineHtlcStatus(blockchain, p2shAddress, minimumAmount, 1);
	}

	public static Status determineHtlcStatus(BitcoinyBlockchainProvider blockchain, String p2shAddress, long minimumAmount, int confirmations) throws ForeignBlockchainException {
		if (confirmations < 1) throw new IllegalArgumentException("Confirmations must be positive");
		String compoundKey = String.format("%s-%s-%d-%d-%d", blockchain.getNetId(), p2shAddress, minimumAmount, confirmations, System.currentTimeMillis() / CACHE_TIMEOUT);

		Status cachedStatus = STATUS_CACHE.getOrDefault(compoundKey, null);
		if (cachedStatus != null)
			return cachedStatus;

		byte[] ourScriptPubKey = addressToScriptPubKey(p2shAddress);

		// Note: we can't include unconfirmed transactions here because the Pirate light wallet server requires a block range
		List<BitcoinyTransaction> transactions = blockchain.getAddressBitcoinyTransactions(p2shAddress, BitcoinyBlockchainProvider.EXCLUDE_UNCONFIRMED);

		// Sort by confirmed first, followed by ascending height
		transactions.sort(BitcoinyTransaction.CONFIRMED_FIRST.thenComparing(BitcoinyTransaction::getHeight));

		// Transaction cache
		//Map<String, BitcoinyTransaction> transactionsByHash = new HashMap<>();
		// HASH160(redeem script) for this p2shAddress
		byte[] ourRedeemScriptHash = addressToRedeemScriptHash(p2shAddress);

		// Check for spends first, caching full transaction info as we progress just in case we don't return in this loop
		for (BitcoinyTransaction bitcoinyTransaction : transactions) {

			// Cache for possible later reuse
			// transactionsByHash.put(transactionInfo.txHash, bitcoinyTransaction);

			// Acceptable funding is one transaction output, so we're expecting only one input
			if (bitcoinyTransaction.inputs.size() != 1)
				// Wrong number of inputs
				continue;

			String scriptSig = bitcoinyTransaction.inputs.get(0).scriptSig;

			List<byte[]> scriptSigChunks = extractScriptSigChunks(HashCode.fromString(scriptSig).asBytes());
			if (scriptSigChunks.size() < 3 || scriptSigChunks.size() > 4)
				// Not valid chunks for our form of HTLC
				continue;

			// Last chunk is redeem script
			byte[] redeemScriptBytes = scriptSigChunks.get(scriptSigChunks.size() - 1);
			byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
			if (!Arrays.equals(redeemScriptHash, ourRedeemScriptHash))
				// Not spending our specific HTLC redeem script
				continue;

			boolean confirmed = bitcoinyTransaction.height > 0 && (confirmations == 1
					|| blockchain.getCurrentHeight() - bitcoinyTransaction.height + 1 >= confirmations);

			if (scriptSigChunks.size() == 4)
				// If we have 4 chunks, then secret is present, hence redeem
				cachedStatus = confirmed ? Status.REDEEMED : Status.REDEEM_IN_PROGRESS;
			else
				cachedStatus = confirmed ? Status.REFUNDED : Status.REFUND_IN_PROGRESS;

			STATUS_CACHE.put(compoundKey, cachedStatus);
			return cachedStatus;
		}

		String ourScriptPubKeyHex = HashCode.fromBytes(ourScriptPubKey).toString();

		// Check for funding
		for (BitcoinyTransaction bitcoinyTransaction : transactions) {
			if (bitcoinyTransaction == null)
				// Should be present in map!
				throw new ForeignBlockchainException("Cached Bitcoin transaction now missing?");

			// Check outputs for our specific P2SH
			for (BitcoinyTransaction.Output output : bitcoinyTransaction.outputs) {
				// Check amount
				if (output.value < minimumAmount)
					// Output amount too small (not taking fees into account)
					continue;

				String scriptPubKeyHex = output.scriptPubKey;
				if (!scriptPubKeyHex.equals(ourScriptPubKeyHex))
					// Not funding our specific P2SH
					continue;

				cachedStatus = bitcoinyTransaction.height == 0 ? Status.FUNDING_IN_PROGRESS : Status.FUNDED;
				STATUS_CACHE.put(compoundKey, cachedStatus);
				return cachedStatus;
			}
		}

		cachedStatus = Status.UNFUNDED;
		STATUS_CACHE.put(compoundKey, cachedStatus);
		return cachedStatus;
	}

	private static List<byte[]> extractScriptSigChunks(byte[] script) {
		List<byte[]> chunks = new ArrayList<>();
		int offset = 0;
		while (offset < script.length) {
			int op = Byte.toUnsignedInt(script[offset++]);
			if (op == 0x4f || (op >= 0x51 && op <= 0x60)) {
				chunks.add(new byte[] { (byte) (op == 0x4f ? 0x81 : op - 0x50) });
				continue;
			}
			long length = op;
			if (op >= 0x4c && op <= 0x4e) {
				int sizeBytes = op == 0x4c ? 1 : op == 0x4d ? 2 : 4;
				if (offset + sizeBytes > script.length) return Collections.emptyList();
				length = 0;
				for (int i=0; i<sizeBytes; i++) length |= (long) Byte.toUnsignedInt(script[offset++]) << (8*i);
			} else if (op > 0x4b) return Collections.emptyList();
			if (length > script.length - offset) return Collections.emptyList();
			chunks.add(Arrays.copyOfRange(script, offset, offset + (int) length));
			offset += (int) length;
		}
		return chunks;
	}

	private static byte[] addressToScriptPubKey(String p2shAddress) {
		// We want the HASH160 part of the P2SH address
		byte[] p2shAddressBytes = Base58.decode(p2shAddress);

		byte[] scriptPubKey = new byte[1 + 1 + 20 + 1];
		scriptPubKey[0x00] = (byte) 0xa9; /* OP_HASH160 */
		scriptPubKey[0x01] = (byte) 0x14; /* PUSH 0x14 bytes */
		// Both the one-byte b prefix and two-byte t3 prefix precede the same hash.
		System.arraycopy(p2shAddressBytes, p2shAddressBytes.length - 24, scriptPubKey, 2, 0x14);
		scriptPubKey[0x16] = (byte) 0x87; /* OP_EQUAL */

		return scriptPubKey;
	}

	private static byte[] addressToRedeemScriptHash(String p2shAddress) {
		// We want the HASH160 part of the P2SH address
		byte[] p2shAddressBytes = Base58.decode(p2shAddress);

		return Arrays.copyOfRange(p2shAddressBytes, p2shAddressBytes.length - 24, p2shAddressBytes.length - 4);
	}

}
