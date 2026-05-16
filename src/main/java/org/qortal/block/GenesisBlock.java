package org.qortal.block;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.account.NullAccount;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.asset.AssetData;
import org.qortal.data.block.BlockData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.Transaction;
import org.qortal.transaction.Transaction.ApprovalStatus;
import org.qortal.transform.TransformationException;
import org.qortal.transform.transaction.TransactionTransformer;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GenesisBlock extends Block {

	private static final Logger LOGGER = LogManager.getLogger(GenesisBlock.class);

	private static final byte[] GENESIS_BLOCK_REFERENCE = new byte[128];
	private static final byte[] GENESIS_TRANSACTION_REFERENCE = new byte[64];

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class GenesisInfo {
		public int version = 1;
		public long timestamp;

		public TransactionData[] transactions;

		public GenesisInfo() {
		}
	}

	// Properties
	private static BlockData genesisBlockData;
	private static List<TransactionData> transactionsData;
	private static List<AssetData> initialAssets;

	// Constructors

	private GenesisBlock(Repository repository, BlockData blockData, List<TransactionData> transactions) throws DataException {
		super(repository, blockData, transactions, Collections.emptyList());
	}

	public static GenesisBlock getInstance(Repository repository) throws DataException {
		return new GenesisBlock(repository, genesisBlockData, transactionsData);
	}

	// Construction from JSON

	/** Construct block data from blockchain config */
	public static void newInstance(GenesisInfo info) {
		// Should be safe to make this call as BlockChain's instance is set
		// so we won't be blocked trying to re-enter synchronized Settings.getInstance()
		BlockChain blockchain = BlockChain.getInstance();

		// Timestamp of zero means "now" but only valid for test nets!
		if (info.timestamp == 0) {
			if (!blockchain.isTestChain()) {
				LOGGER.error("Genesis timestamp of zero (i.e. now) not valid for non-test blockchain configs");
				throw new RuntimeException("Genesis timestamp of zero (i.e. now) not valid for non-test blockchain configs");
			}

			// This will only take effect if there is no current genesis block in blockchain
			info.timestamp = System.currentTimeMillis();
		}

		transactionsData = new ArrayList<>(Arrays.asList(info.transactions));

		// Add default values to transactions
		transactionsData.stream().forEach(transactionData -> {
			if (transactionData.getFee() == null)
				transactionData.setFee(0L);

			if (transactionData.getCreatorPublicKey() == null)
				transactionData.setCreatorPublicKey(NullAccount.PUBLIC_KEY);

			if (transactionData.getTimestamp() == 0)
				transactionData.setTimestamp(info.timestamp);
		});

		byte[] reference = GENESIS_BLOCK_REFERENCE;
		int transactionCount = transactionsData.size();
		long totalFees = 0;
		byte[] minterPublicKey = NullAccount.PUBLIC_KEY;
		byte[] bytesForSignature = getBytesForMinterSignature(info.timestamp, reference, minterPublicKey);
		byte[] minterSignature = calcGenesisMinterSignature(bytesForSignature);
		byte[] transactionsSignature = calcGenesisTransactionsSignature();
		int height = 1;
		int atCount = 0;
		long atFees = 0;

		genesisBlockData = new BlockData(info.version, reference, transactionCount, totalFees, transactionsSignature, height, info.timestamp,
				minterPublicKey, minterSignature, atCount, atFees);
	}

	// More information

	public static boolean isGenesisBlock(BlockData blockData) {
		if (blockData.getHeight() != 1)
			return false;

		byte[] signature = calcSignature(blockData);

		// Validate block minter's signature (first 64 bytes of block signature)
		if (!Arrays.equals(signature, 0, 64, genesisBlockData.getMinterSignature(), 0, 64))
			return false;

		// Validate transactions signature (last 64 bytes of block signature)
		if (!Arrays.equals(signature, 64, 128, genesisBlockData.getTransactionsSignature(), 0, 64))
			return false;

		return true;
	}

	public List<AssetData> getInitialAssets() {
		return Collections.unmodifiableList(initialAssets);
	}

	// Processing

	@Override
	public boolean addTransaction(TransactionData transactionData) {
		// The genesis block has a fixed set of transactions so always refuse.
		return false;
	}

	/**
	 * Refuse to calculate genesis block's minter signature!
	 * <p>
	 * This is not possible as there is no private key for the null account and so no way to sign data.
	 * <p>
	 * <b>Always throws IllegalStateException.</b>
	 * 
	 * @throws IllegalStateException
	 */
	@Override
	public void calcMinterSignature() {
		throw new IllegalStateException("There is no private key for null account");
	}

	/**
	 * Refuse to calculate genesis block's transactions signature!
	 * <p>
	 * This is not possible as there is no private key for the null account and so no way to sign data.
	 * <p>
	 * <b>Always throws IllegalStateException.</b>
	 * 
	 * @throws IllegalStateException
	 */
	@Override
	public void calcTransactionsSignature() {
		throw new IllegalStateException("There is no private key for null account");
	}

	/**
	 * Generate genesis block minter signature.
	 * <p>
	 * This is handled differently as there is no private key for the null account and so no way to sign data.
	 * 
	 * @return byte[]
	 */
	private static byte[] calcGenesisMinterSignature(byte[] bytes) {
		return Crypto.dupDigest(bytes);
	}

	private static byte[] getBytesForMinterSignature(long timestamp, byte[] reference, byte[] minterPublicKey) {
		try {
			// Passing expected size to ByteArrayOutputStream avoids reallocation when adding more bytes than default 32.
			// See below for explanation of some of the values used to calculated expected size.
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 + 128 + 32);

			// Genesis block timestamp
			bytes.write(Longs.toByteArray(timestamp));

			// Block's reference
			bytes.write(reference);

			// Minting account's public key (typically NullAccount)
			bytes.write(minterPublicKey);

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static byte[] calcGenesisTransactionsSignature() {
		// transaction index (int), transaction type (int), creator pubkey (32): so 40 bytes each
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(transactionsData.size() * (4 + 4 + 32));

		try {
			for (int ti = 0; ti < transactionsData.size(); ++ti) {
				bytes.write(Ints.toByteArray(ti));

				bytes.write(Ints.toByteArray(transactionsData.get(ti).getType().value));

				bytes.write(transactionsData.get(ti).getCreatorPublicKey());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return Crypto.dupDigest(bytes.toByteArray());
	}

	/** Convenience method for calculating genesis block signatures from block data */
	private static byte[] calcSignature(BlockData blockData) {
		byte[] bytes = getBytesForMinterSignature(blockData.getTimestamp(), blockData.getReference(), blockData.getMinterPublicKey());
		return Bytes.concat(calcGenesisMinterSignature(bytes), calcGenesisTransactionsSignature());
	}

	@Override
	public boolean isSignatureValid() {
		byte[] signature = calcSignature(this.blockData);

		// Validate block minter's signature (first 64 bytes of block signature)
		if (!Arrays.equals(signature, 0, 64, this.blockData.getMinterSignature(), 0, 64))
			return false;

		// Validate transactions signature (last 64 bytes of block signature)
		if (!Arrays.equals(signature, 64, 128, this.blockData.getTransactionsSignature(), 0, 64))
			return false;

		return true;
	}

	@Override
	public ValidationResult isValid() throws DataException {
		// Check there is no other block in DB
		if (this.repository.getBlockRepository().getBlockchainHeight() != 0)
			return ValidationResult.BLOCKCHAIN_NOT_EMPTY;

		// Validate transactions
		for (Transaction transaction : this.getTransactions())
			if (transaction.isValid() != Transaction.ValidationResult.OK)
				return ValidationResult.TRANSACTION_INVALID;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		LOGGER.info(String.format("Using genesis block timestamp of %d", this.blockData.getTimestamp()));

		/*
		 * Some transactions will be missing references and signatures,
		 * so we generate them by using <tt>GENESIS_TRANSACTION_REFERENCE</tt>
		 * and a duplicated SHA256 digest for signature
		 */
		try {
			for (Transaction transaction : this.getTransactions()) {
				TransactionData transactionData = transaction.getTransactionData();

				// Missing reference?
				if (transactionData.getReference() == null)
					transactionData.setReference(GENESIS_TRANSACTION_REFERENCE);

				// Missing signature?
				if (transactionData.getSignature() == null) {
					byte[] digest = Crypto.digest(TransactionTransformer.toBytesForSigning(transactionData));
					byte[] signature = Bytes.concat(digest, digest);

					transactionData.setSignature(signature);
				}

				// Approval status
				transactionData.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
			}
		} catch (TransformationException e) {
			throw new RuntimeException("Can't process genesis block transaction", e);
		}

		// Save transactions into repository ready for processing
		for (Transaction transaction : this.getTransactions())
			this.repository.getTransactionRepository().save(transaction.getTransactionData());

		// No ATs in genesis block
		this.ourAtStates = Collections.emptyList();
		this.ourAtFees = 0;

		LOGGER.debug("GenesisBlock.process() - Calling super.process()");
		super.process();
	}

}
