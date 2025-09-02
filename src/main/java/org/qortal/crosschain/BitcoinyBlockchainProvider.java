package org.qortal.crosschain;

import cash.z.wallet.sdk.rpc.CompactFormats.CompactBlock;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public abstract class BitcoinyBlockchainProvider {

	public static final boolean INCLUDE_UNCONFIRMED = true;
	public static final boolean EXCLUDE_UNCONFIRMED = false;
	public static final String EMPTY = "";

	/** Sets the blockchain using this provider instance */
	public abstract void setBlockchain(Bitcoiny blockchain);

	/** Returns ID unique to bitcoiny network (e.g. "Litecoin-TEST3") */
	public abstract String getNetId();

	/** Returns current blockchain height. */
	public abstract int getCurrentHeight() throws ForeignBlockchainException;

	/** Returns a list of compact blocks, starting at <tt>startHeight</tt> (inclusive), up to <tt>count</tt> max.
	 * Used for Pirate/Zcash only. If ever needed for other blockchains, the response format will need to be
	 * made generic. */
	public abstract List<CompactBlock> getCompactBlocks(int startHeight, int count) throws ForeignBlockchainException;

	/** Returns a list of raw block headers, starting at <tt>startHeight</tt> (inclusive), up to <tt>count</tt> max. */
	public abstract List<byte[]> getRawBlockHeaders(int startHeight, int count) throws ForeignBlockchainException;

	/** Returns a list of block timestamps, starting at <tt>startHeight</tt> (inclusive), up to <tt>count</tt> max. */
	public abstract List<Long> getBlockTimestamps(int startHeight, int count) throws ForeignBlockchainException;

	/** Returns balance of address represented by <tt>scriptPubKey</tt>. */
	public abstract long getConfirmedBalance(byte[] scriptPubKey) throws ForeignBlockchainException;

	/** Returns balance of base58 encoded address. */
	public abstract long getConfirmedAddressBalance(String base58Address) throws ForeignBlockchainException;

	/** Returns raw, serialized, transaction bytes given <tt>txHash</tt>. */
	public abstract byte[] getRawTransaction(String txHash) throws ForeignBlockchainException;

	/** Returns raw, serialized, transaction bytes given <tt>txHash</tt>. */
	public abstract byte[] getRawTransaction(byte[] txHash) throws ForeignBlockchainException;

	/** Returns unpacked transaction given <tt>txHash</tt>. */
	public abstract BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException;

	/** Returns list of transaction hashes (and heights) for address represented by <tt>scriptPubKey</tt>, optionally including unconfirmed transactions. */
	public abstract List<TransactionHash> getAddressTransactions(byte[] scriptPubKey, boolean includeUnconfirmed) throws ForeignBlockchainException;

	/** Returns list of BitcoinyTransaction objects for <tt>address</tt>, optionally including unconfirmed transactions. */
	public abstract List<BitcoinyTransaction> getAddressBitcoinyTransactions(String address, boolean includeUnconfirmed) throws ForeignBlockchainException;

		/** Returns list of unspent transaction outputs for <tt>address</tt>, optionally including unconfirmed transactions. */
	public abstract List<UnspentOutput> getUnspentOutputs(String address, boolean includeUnconfirmed) throws ForeignBlockchainException;

	/** Returns list of unspent transaction outputs for address represented by <tt>scriptPubKey</tt>, optionally including unconfirmed transactions. */
	public abstract List<UnspentOutput> getUnspentOutputs(byte[] scriptPubKey, boolean includeUnconfirmed) throws ForeignBlockchainException;

	/** Broadcasts raw, serialized, transaction bytes to network, returning success/failure. */
	public abstract void broadcastTransaction(byte[] rawTransaction) throws ForeignBlockchainException;

	public abstract Set<ChainableServer> getServers();

	public abstract Set<ChainableServer> getUselessServers();

	public abstract ChainableServer getCurrentServer();

	/**
	 * Add Server
	 *
	 * Add server to list of candidate servers.
	 *
	 * @param server the server
	 *
	 * @return true if added, otherwise false
	 */
	public abstract boolean addServer( ChainableServer server );

	/**
	 * Remove Server
	 *
	 * Remove server from list of candidate servers.
	 *
	 * @param server the server
	 *
	 * @return true if removed, otherwise false
	 */
	public abstract boolean removeServer( ChainableServer server );

	/**
	 * Set Current Server
	 *
	 * Set server to be used for this foreign blockchain.
	 *
	 * @param server the server
	 * @param requestedBy who requested this setting
	 *
	 * @return the connection that was made
	 *
	 * @throws ForeignBlockchainException
	 */
	public abstract Optional<ChainableServerConnection> setCurrentServer(ChainableServer server, String requestedBy) throws ForeignBlockchainException;

	/**
	 * Get Server Connections
	 *
	 * Get the server connections made to this foreign blockchain,
	 *
	 * @return the server connections
	 */
	public abstract List<ChainableServerConnection> getServerConnections();

	/**
	 * Get Server
	 *
	 * Get a server for this foreign blockchain.
	 *
	 * @param hostName the host URL
	 * @param type the type of connection (TCP, SSL)
	 * @param port the port
	 *
	 * @return the server
	 */
	public abstract ChainableServer getServer(String hostName, ChainableServer.ConnectionType type, int port);
}
