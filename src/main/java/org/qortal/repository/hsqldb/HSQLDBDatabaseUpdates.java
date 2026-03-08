package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.controller.Controller;
import org.qortal.controller.tradebot.BitcoinACCTv1TradeBot;
import org.qortal.gui.SplashFrame;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.stream.Collectors;

public class HSQLDBDatabaseUpdates {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBDatabaseUpdates.class);

	private static final String TRANSACTION_KEYS = "PRIMARY KEY (signature), "
			+ "FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE";

	/**
	 * Apply any incremental changes to database schema.
	 * 
	 * @return true if database was non-existent/empty, false otherwise
	 * @throws SQLException
	 */
	public static boolean updateDatabase(Connection connection) throws SQLException {
		final boolean wasPristine = fetchDatabaseVersion(connection) == 0;

		SplashFrame.getInstance().updateStatus("Upgrading database, please wait...");

		while (databaseUpdating(connection, wasPristine))
			incrementDatabaseVersion(connection);

		String text = String.format("Starting Qortal Core v%s...", Controller.getInstance().getVersionStringWithoutPrefix());
		SplashFrame.getInstance().updateStatus(text);

		return wasPristine;
	}

	/**
	 * Increment database's schema version.
	 * 
	 * @throws SQLException
	 */
	private static void incrementDatabaseVersion(Connection connection) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("UPDATE DatabaseInfo SET version = version + 1");
			connection.commit();
		}
	}

	/**
	 * Fetch current version of database schema.
	 * 
	 * @return database version, or 0 if no schema yet
	 * @throws SQLException
	 */
	public static int fetchDatabaseVersion(Connection connection) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			if (stmt.execute("SELECT version FROM DatabaseInfo"))
				try (ResultSet resultSet = stmt.getResultSet()) {
					if (resultSet.next())
						return resultSet.getInt(1);
				}
		} catch (SQLException e) {
			// empty database
		}

		return 0;
	}

	/**
	 * Incrementally update database schema, returning whether an update happened.
	 * 
	 * @return true - if a schema update happened, false otherwise
	 * @throws SQLException
	 */
	private static boolean databaseUpdating(Connection connection, boolean wasPristine) throws SQLException {
		int databaseVersion = fetchDatabaseVersion(connection);

		try (Statement stmt = connection.createStatement()) {

			/*
			 * Try not to add too many constraints as much of these checks will be performed during transaction validation. Also some constraints might be too
			 * harsh on competing unconfirmed transactions.
			 * 
			 * Only really add "ON DELETE CASCADE" to sub-tables that store type-specific data. For example on sub-types of Transactions like
			 * PaymentTransactions. A counterexample would be adding "ON DELETE CASCADE" to Assets using Assets' "reference" as a foreign key referring to
			 * Transactions' "signature". We want to database to automatically delete complete transaction data (Transactions row and corresponding
			 * PaymentTransactions row), but leave deleting less related table rows (Assets) to the Java logic.
			 */

			switch (databaseVersion) {
				case 0:
					// create from new
					// FYI: "UCC" in HSQLDB means "upper-case comparison", i.e. case-insensitive
					stmt.execute("SET DATABASE SQL NAMES TRUE"); // SQL keywords cannot be used as DB object names, e.g. table names
					stmt.execute("SET DATABASE SQL SYNTAX MYS TRUE"); // Required for our use of INSERT ... ON DUPLICATE KEY UPDATE ... syntax
					stmt.execute("SET DATABASE SQL RESTRICT EXEC TRUE"); // No multiple-statement execute() or DDL/DML executeQuery()
					stmt.execute("SET DATABASE TRANSACTION CONTROL MVCC"); // Use MVCC over default two-phase locking, a-k-a "LOCKS"
					stmt.execute("SET DATABASE DEFAULT TABLE TYPE CACHED");
					stmt.execute("SET DATABASE COLLATION SQL_TEXT NO PAD"); // Do not pad strings to same length before comparison

					stmt.execute("CREATE COLLATION SQL_TEXT_UCC_NO_PAD FOR SQL_TEXT FROM SQL_TEXT_UCC NO PAD");
					stmt.execute("CREATE COLLATION SQL_TEXT_NO_PAD FOR SQL_TEXT FROM SQL_TEXT NO PAD");

					stmt.execute("SET FILES SPACE TRUE"); // Enable per-table block space within .data file, useful for CACHED table types
					// Slow down log fsync() calls from every 500ms to reduce I/O load
					stmt.execute("SET FILES WRITE DELAY 5"); // only fsync() every 5 seconds

					stmt.execute("CREATE TABLE DatabaseInfo ( version INTEGER NOT NULL )");
					stmt.execute("INSERT INTO DatabaseInfo VALUES ( 0 )");

					stmt.execute("CREATE TYPE ArbitraryData AS VARBINARY(256)");
					stmt.execute("CREATE TYPE AssetData AS VARCHAR(400K)");
					stmt.execute("CREATE TYPE AssetID AS BIGINT");
					stmt.execute("CREATE TYPE AssetName AS VARCHAR(34) COLLATE SQL_TEXT_NO_PAD");
					stmt.execute("CREATE TYPE AssetOrderID AS VARBINARY(64)");
					stmt.execute("CREATE TYPE ATCode AS VARBINARY(1024)"); // was: 16bit * 1
					stmt.execute("CREATE TYPE ATCreationBytes AS VARBINARY(4096)"); // was: 16bit * 1 + 16bit * 8
					stmt.execute("CREATE TYPE ATMessage AS VARBINARY(32)");
					stmt.execute("CREATE TYPE ATName AS VARCHAR(32) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE ATState AS VARBINARY(1024)"); // was: 16bit * 8 + 16bit * 4 + 16bit * 4
					stmt.execute("CREATE TYPE ATTags AS VARCHAR(80) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE ATType AS VARCHAR(32) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE ATStateHash as VARBINARY(32)");
					stmt.execute("CREATE TYPE BlockSignature AS VARBINARY(128)");
					stmt.execute("CREATE TYPE DataHash AS VARBINARY(32)");
					stmt.execute("CREATE TYPE EpochMillis AS BIGINT");
					stmt.execute("CREATE TYPE GenericDescription AS VARCHAR(4000)");
					stmt.execute("CREATE TYPE GroupID AS INTEGER");
					stmt.execute("CREATE TYPE GroupName AS VARCHAR(400) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE GroupReason AS VARCHAR(128) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE MessageData AS VARBINARY(4000)");
					stmt.execute("CREATE TYPE NameData AS VARCHAR(4000)");
					stmt.execute("CREATE TYPE PollName AS VARCHAR(128) COLLATE SQL_TEXT_NO_PAD");
					stmt.execute("CREATE TYPE PollOption AS VARCHAR(80) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE PollOptionIndex AS TINYINT");
					stmt.execute("CREATE TYPE QortalAddress AS VARCHAR(36)");
					stmt.execute("CREATE TYPE QortalKeySeed AS VARBINARY(32)");
					stmt.execute("CREATE TYPE QortalPublicKey AS VARBINARY(32)");
					stmt.execute("CREATE TYPE QortalAmount AS BIGINT");
					stmt.execute("CREATE TYPE RegisteredName AS VARCHAR(128) COLLATE SQL_TEXT_NO_PAD");
					stmt.execute("CREATE TYPE RewardSharePercent AS INT");
					stmt.execute("CREATE TYPE Signature AS VARBINARY(64)");
					break;

				case 1:
					// Blocks
					stmt.execute("CREATE TABLE Blocks (signature BlockSignature, version TINYINT NOT NULL, reference BlockSignature, "
							+ "transaction_count INTEGER NOT NULL, total_fees QortalAmount NOT NULL, transactions_signature Signature NOT NULL, "
							+ "height INTEGER NOT NULL, minted_when EpochMillis NOT NULL, "
							+ "minter QortalPublicKey NOT NULL, minter_signature Signature NOT NULL, AT_count INTEGER NOT NULL, AT_fees QortalAmount NOT NULL, "
							+ "online_accounts VARBINARY(1024), online_accounts_count INTEGER NOT NULL, online_accounts_timestamp EpochMillis, online_accounts_signatures VARBINARY(1M), "
							+ "PRIMARY KEY (signature))");
					// For finding blocks by height.
					stmt.execute("CREATE INDEX BlockHeightIndex ON Blocks (height)");
					// For finding blocks by the account that minted them.
					stmt.execute("CREATE INDEX BlockMinterIndex ON Blocks (minter)");
					// For finding blocks by reference, e.g. child blocks.
					stmt.execute("CREATE INDEX BlockReferenceIndex ON Blocks (reference)");
					// For finding blocks by timestamp or finding height of latest block immediately before timestamp, etc.
					stmt.execute("CREATE INDEX BlockTimestampHeightIndex ON Blocks (minted_when, height)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE Blocks NEW SPACE");
					break;

				case 2:
					// Generic transactions (null reference, creator and milestone_block for genesis transactions)
					stmt.execute("CREATE TABLE Transactions (signature Signature, reference Signature, type TINYINT NOT NULL, "
							+ "creator QortalPublicKey NOT NULL, created_when EpochMillis NOT NULL, fee QortalAmount NOT NULL, "
							+ "tx_group_id GroupID NOT NULL, block_height INTEGER, "
							+ "approval_status TINYINT NOT NULL, approval_height INTEGER, "
							+ "PRIMARY KEY (signature))");
					// For finding transactions by transaction type.
					stmt.execute("CREATE INDEX TransactionTypeIndex ON Transactions (type)");
					// For finding transactions using creation timestamp.
					stmt.execute("CREATE INDEX TransactionTimestampIndex ON Transactions (created_when)");
					// For when a user wants to lookup ALL transactions they have created, with optional type.
					stmt.execute("CREATE INDEX TransactionCreatorIndex ON Transactions (creator, type)");
					// For finding transactions by reference, e.g. child transactions.
					stmt.execute("CREATE INDEX TransactionReferenceIndex ON Transactions (reference)");
					// For finding transactions by groupID
					stmt.execute("CREATE INDEX TransactionGroupIndex ON Transactions (tx_group_id)");
					// For finding transactions by block height
					stmt.execute("CREATE INDEX TransactionHeightIndex on Transactions (block_height)");
					// For searching transactions based on approval status
					stmt.execute("CREATE INDEX TransactionApprovalStatusIndex on Transactions (approval_status, block_height)");
					// For searching transactions based on approval height
					stmt.execute("CREATE INDEX TransactionApprovalHeightIndex on Transactions (approval_height)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE Transactions NEW SPACE");

					// Transaction-Block mapping ("transaction_signature" is unique as a transaction cannot be included in more than one block)
					stmt.execute("CREATE TABLE BlockTransactions (block_signature BlockSignature, sequence INTEGER, transaction_signature Signature UNIQUE, "
							+ "PRIMARY KEY (block_signature, sequence), FOREIGN KEY (transaction_signature) REFERENCES Transactions (signature) ON DELETE CASCADE, "
							+ "FOREIGN KEY (block_signature) REFERENCES Blocks (signature) ON DELETE CASCADE)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE BlockTransactions NEW SPACE");

					// Unconfirmed transactions
					// We use this as searching for transactions with no corresponding mapping in BlockTransactions is much slower.
					stmt.execute("CREATE TABLE UnconfirmedTransactions (signature Signature PRIMARY KEY, created_when EpochMillis NOT NULL)");
					// Index to allow quick sorting by creation-else-signature
					stmt.execute("CREATE INDEX UnconfirmedTransactionsIndex ON UnconfirmedTransactions (created_when, signature)");

					// Transaction participants
					// To allow lookup of all activity by an address
					stmt.execute("CREATE TABLE TransactionParticipants (signature Signature NOT NULL, participant QortalAddress NOT NULL, "
							+ "FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// Add index to TransactionParticipants to speed up queries
					stmt.execute("CREATE INDEX TransactionParticipantsAddressIndex on TransactionParticipants (participant)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE TransactionParticipants NEW SPACE");
					break;

				case 3:
					// Accounts
					stmt.execute("CREATE TABLE Accounts (account QortalAddress, reference Signature, public_key QortalPublicKey, "
							+ "default_group_id GroupID NOT NULL DEFAULT 0, flags INTEGER NOT NULL DEFAULT 0, level INT NOT NULL DEFAULT 0, "
							+ "blocks_minted INTEGER NOT NULL DEFAULT 0, blocks_minted_adjustment INTEGER NOT NULL DEFAULT 0, "
							+ "PRIMARY KEY (account))");
					// For looking up an account by public key
					stmt.execute("CREATE INDEX AccountPublicKeyIndex on Accounts (public_key)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE Accounts NEW SPACE");

					// Account balances
					stmt.execute("CREATE TABLE AccountBalances (account QortalAddress, asset_id AssetID, balance QortalAmount NOT NULL, "
							+ "PRIMARY KEY (account, asset_id), FOREIGN KEY (account) REFERENCES Accounts (account) ON DELETE CASCADE)");
					// Index for speeding up fetch legacy QORA holders for Block processing
					stmt.execute("CREATE INDEX AccountBalancesAssetBalanceIndex ON AccountBalances (asset_id, balance)");
					// Add CHECK constraint to account balances
					stmt.execute("ALTER TABLE AccountBalances ADD CONSTRAINT CheckBalanceNotNegative CHECK (balance >= 0)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE AccountBalances NEW SPACE");

					// Keeping track of QORT gained from holding legacy QORA
					stmt.execute("CREATE TABLE AccountQortFromQoraInfo (account QortalAddress, final_qort_from_qora QortalAmount, final_block_height INT, "
									+ "PRIMARY KEY (account), FOREIGN KEY (account) REFERENCES Accounts (account) ON DELETE CASCADE)");
					break;

				case 4:
					// Genesis Transactions
					stmt.execute("CREATE TABLE GenesisTransactions (signature Signature, recipient QortalAddress NOT NULL, "
							+ "amount QortalAmount NOT NULL, asset_id AssetID NOT NULL, " + TRANSACTION_KEYS + ")");

					// Genesis-block-only transaction to set/clear flags
					stmt.execute("CREATE TABLE AccountFlagsTransactions (signature Signature, creator QortalPublicKey NOT NULL, target QortalAddress NOT NULL, "
							+ "and_mask INT NOT NULL, or_mask INT NOT NULL, xor_mask INT NOT NULL, previous_flags INT, " + TRANSACTION_KEYS + ")");

					// Genesis-block-only transaction to set level
					stmt.execute("CREATE TABLE AccountLevelTransactions (signature Signature, creator QortalPublicKey NOT NULL, target QortalAddress NOT NULL, "
							+ "level INT NOT NULL, " + TRANSACTION_KEYS + ")");
					break;

				case 5:
					// Payments
					// Arbitrary/Multi-payment/Message/Payment Transaction Payments
					stmt.execute("CREATE TABLE SharedTransactionPayments (signature Signature, recipient QortalAddress NOT NULL, "
							+ "amount QortalAmount NOT NULL, asset_id AssetID NOT NULL, " + TRANSACTION_KEYS + ")");

					// Payment Transactions
					stmt.execute("CREATE TABLE PaymentTransactions (signature Signature, sender QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
							+ "amount QortalAmount NOT NULL, " + TRANSACTION_KEYS + ")");

					// Multi-payment Transactions
					stmt.execute("CREATE TABLE MultiPaymentTransactions (signature Signature, sender QortalPublicKey NOT NULL, "
							+ TRANSACTION_KEYS + ")");
					break;

				case 6:
					// Message Transactions
					stmt.execute("CREATE TABLE MessageTransactions (signature Signature, version TINYINT NOT NULL, nonce INT NOT NULL, "
							+ "sender QortalPublicKey NOT NULL, recipient QortalAddress, amount QortalAmount NOT NULL, asset_id AssetID, "
							+ "is_text BOOLEAN NOT NULL, is_encrypted BOOLEAN NOT NULL, data MessageData NOT NULL, "
							+ TRANSACTION_KEYS + ")");
					break;

				case 7:
					// Arbitrary Transactions
					stmt.execute("CREATE TABLE ArbitraryTransactions (signature Signature, sender QortalPublicKey NOT NULL, version TINYINT NOT NULL, "
							+ "service SMALLINT NOT NULL, is_data_raw BOOLEAN NOT NULL, data ArbitraryData NOT NULL, "
							+ TRANSACTION_KEYS + ")");
					// NB: Actual data payload stored elsewhere
					break;

				case 8:
					// Name-related
					stmt.execute("CREATE TABLE Names (name RegisteredName, reduced_name RegisteredName, owner QortalAddress NOT NULL, "
							+ "registered_when EpochMillis NOT NULL, updated_when EpochMillis, "
							+ "is_for_sale BOOLEAN NOT NULL DEFAULT FALSE, sale_price QortalAmount, data NameData NOT NULL, "
							+ "reference Signature, creation_group_id GroupID NOT NULL DEFAULT 0, "
							+ "PRIMARY KEY (name))");
					// For finding names by owner
					stmt.execute("CREATE INDEX NamesOwnerIndex ON Names (owner)");
					// For finding names by 'reduced' form
					stmt.execute("CREATE INDEX NamesReducedNameIndex ON Names (reduced_name)");

					// Register Name Transactions
					stmt.execute("CREATE TABLE RegisterNameTransactions (signature Signature, registrant QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "data NameData NOT NULL, reduced_name RegisteredName NOT NULL, " + TRANSACTION_KEYS + ")");

					// Update Name Transactions
					stmt.execute("CREATE TABLE UpdateNameTransactions (signature Signature, owner QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "new_name RegisteredName NOT NULL, new_data NameData NOT NULL, reduced_new_name RegisteredName NOT NULL, "
							+ "name_reference Signature, " + TRANSACTION_KEYS + ")");

					// Sell Name Transactions
					stmt.execute("CREATE TABLE SellNameTransactions (signature Signature, owner QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "amount QortalAmount NOT NULL, " + TRANSACTION_KEYS + ")");

					// Cancel Sell Name Transactions
					stmt.execute("CREATE TABLE CancelSellNameTransactions (signature Signature, owner QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ TRANSACTION_KEYS + ")");

					// Buy Name Transactions
					stmt.execute("CREATE TABLE BuyNameTransactions (signature Signature, buyer QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "seller QortalAddress NOT NULL, amount QortalAmount NOT NULL, name_reference Signature, " + TRANSACTION_KEYS + ")");
					break;

				case 9:
					// Polls/voting
					stmt.execute("CREATE TABLE Polls (poll_name PollName, creator QortalPublicKey NOT NULL, "
							+ "owner QortalAddress NOT NULL, published_when EpochMillis NOT NULL, "
							+ "description GenericDescription NOT NULL, "
							+ "PRIMARY KEY (poll_name))");
					// For when a user wants to lookup poll they own
					stmt.execute("CREATE INDEX PollOwnerIndex on Polls (owner)");

					// Various options available on a poll
					stmt.execute("CREATE TABLE PollOptions (poll_name PollName, option_index PollOptionIndex NOT NULL, option_name PollOption, "
							+ "PRIMARY KEY (poll_name, option_index), FOREIGN KEY (poll_name) REFERENCES Polls (poll_name) ON DELETE CASCADE)");

					// Actual votes cast on a poll by voting users. NOTE: only one vote per user supported at this time.
					stmt.execute("CREATE TABLE PollVotes (poll_name PollName, voter QortalPublicKey, option_index PollOptionIndex NOT NULL, "
							+ "PRIMARY KEY (poll_name, voter), FOREIGN KEY (poll_name) REFERENCES Polls (poll_name) ON DELETE CASCADE)");

					// Create Poll Transactions
					stmt.execute("CREATE TABLE CreatePollTransactions (signature Signature, creator QortalPublicKey NOT NULL, owner QortalAddress NOT NULL, "
							+ "poll_name PollName NOT NULL, description GenericDescription NOT NULL, " + TRANSACTION_KEYS + ")");

					// Poll options. NB: option is implicitly NON NULL and UNIQUE due to being part of compound primary key
					stmt.execute("CREATE TABLE CreatePollTransactionOptions (signature Signature, option_index PollOptionIndex NOT NULL, option_name PollOption, "
							+ "PRIMARY KEY (signature, option_index), FOREIGN KEY (signature) REFERENCES CreatePollTransactions (signature) ON DELETE CASCADE)");
					// For the future: add flag to polls to allow one or multiple votes per voter

					// Vote On Poll Transactions
					stmt.execute("CREATE TABLE VoteOnPollTransactions (signature Signature, voter QortalPublicKey NOT NULL, poll_name PollName NOT NULL, "
							+ "option_index PollOptionIndex NOT NULL, previous_option_index PollOptionIndex, " + TRANSACTION_KEYS + ")");
					break;

				case 10:
					// Assets (including QORT coin itself)
					stmt.execute("CREATE TABLE Assets (asset_id AssetID, owner QortalAddress NOT NULL, "
							+ "asset_name AssetName NOT NULL, description GenericDescription NOT NULL, "
							+ "quantity BIGINT NOT NULL, is_divisible BOOLEAN NOT NULL, "
							+ "is_unspendable BOOLEAN NOT NULL DEFAULT FALSE, creation_group_id GroupID NOT NULL DEFAULT 0, "
							+ "reference Signature NOT NULL, data AssetData NOT NULL DEFAULT '', "
							+ "reduced_asset_name AssetName NOT NULL, PRIMARY KEY (asset_id))");
					// For when a user wants to lookup an asset by name
					stmt.execute("CREATE INDEX AssetNameIndex on Assets (asset_name)");
					// For looking up assets by 'reduced' name
					stmt.execute("CREATE INDEX AssetReducedNameIndex on Assets (reduced_asset_name)");

					// We need a corresponding trigger to make sure new asset_id values are assigned sequentially start from 0
					stmt.execute("CREATE TRIGGER Asset_ID_Trigger BEFORE INSERT ON Assets "
							+ "REFERENCING NEW ROW AS new_row FOR EACH ROW WHEN (new_row.asset_id IS NULL) "
							+ "SET new_row.asset_id = (SELECT IFNULL(MAX(asset_id) + 1, 0) FROM Assets)");

					// Asset Orders
					stmt.execute("CREATE TABLE AssetOrders (asset_order_id AssetOrderID, creator QortalPublicKey NOT NULL, "
							+ "have_asset_id AssetID NOT NULL, want_asset_id AssetID NOT NULL, "
							+ "amount QortalAmount NOT NULL, fulfilled QortalAmount NOT NULL, price QortalAmount NOT NULL, "
							+ "ordered_when EpochMillis NOT NULL, is_closed BOOLEAN NOT NULL, is_fulfilled BOOLEAN NOT NULL, "
							+ "PRIMARY KEY (asset_order_id))");
					// For quick matching of orders. is_closed are is_fulfilled included so inactive orders can be filtered out.
					stmt.execute("CREATE INDEX AssetOrderMatchingIndex on AssetOrders (have_asset_id, want_asset_id, is_closed, is_fulfilled, price, ordered_when)");
					// For when a user wants to look up their current/historic orders. is_closed included so user can filter by active/inactive orders.
					stmt.execute("CREATE INDEX AssetOrderCreatorIndex on AssetOrders (creator, is_closed)");

					// Asset Trades
					stmt.execute("CREATE TABLE AssetTrades (initiating_order_id AssetOrderId NOT NULL, target_order_id AssetOrderId NOT NULL, "
							+ "target_amount QortalAmount NOT NULL, initiator_amount QortalAmount NOT NULL, traded_when EpochMillis NOT NULL, "
							+ "initiator_saving QortalAmount NOT NULL DEFAULT 0)");
					// For looking up historic trades based on orders
					stmt.execute("CREATE INDEX AssetTradeBuyOrderIndex on AssetTrades (initiating_order_id, traded_when)");
					stmt.execute("CREATE INDEX AssetTradeSellOrderIndex on AssetTrades (target_order_id, traded_when)");

					// Issue Asset Transactions
					stmt.execute("CREATE TABLE IssueAssetTransactions (signature Signature, issuer QortalPublicKey NOT NULL, asset_name AssetName NOT NULL, "
							+ "description GenericDescription NOT NULL, quantity BIGINT NOT NULL, is_divisible BOOLEAN NOT NULL, asset_id AssetID, "
							+ "is_unspendable BOOLEAN NOT NULL, data AssetData NOT NULL DEFAULT '', reduced_asset_name AssetName NOT NULL, "
							+ TRANSACTION_KEYS + ")");

					// Transfer Asset Transactions
					stmt.execute("CREATE TABLE TransferAssetTransactions (signature Signature, sender QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
							+ "asset_id AssetID NOT NULL, amount QortalAmount NOT NULL," + TRANSACTION_KEYS + ")");

					// Add support for UPDATE_ASSET transactions
					stmt.execute("CREATE TABLE UpdateAssetTransactions (signature Signature, owner QortalPublicKey NOT NULL, asset_id AssetID NOT NULL, "
									+ "new_owner QortalAddress NOT NULL, new_description GenericDescription NOT NULL, new_data AssetData NOT NULL, "
									+ "orphan_reference Signature, " + TRANSACTION_KEYS + ")");

					// Create Asset Order Transactions
					stmt.execute("CREATE TABLE CreateAssetOrderTransactions (signature Signature, creator QortalPublicKey NOT NULL, "
							+ "have_asset_id AssetID NOT NULL, amount QortalAmount NOT NULL, want_asset_id AssetID NOT NULL, price QortalAmount NOT NULL, "
							+ TRANSACTION_KEYS + ")");

					// Cancel Asset Order Transactions
					stmt.execute("CREATE TABLE CancelAssetOrderTransactions (signature Signature, creator QortalPublicKey NOT NULL, "
							+ "asset_order_id AssetOrderID NOT NULL, " + TRANSACTION_KEYS + ")");
					break;

				case 11:
					// CIYAM Automated Transactions
					stmt.execute("CREATE TABLE ATs (AT_address QortalAddress, creator QortalPublicKey NOT NULL, created_when EpochMillis NOT NULL, "
							+ "version INTEGER NOT NULL, asset_id AssetID NOT NULL, code_bytes ATCode NOT NULL, code_hash VARBINARY(32) NOT NULL, "
							+ "creation_group_id GroupID NOT NULL DEFAULT 0, is_sleeping BOOLEAN NOT NULL, sleep_until_height INTEGER, "
							+ "is_finished BOOLEAN NOT NULL, had_fatal_error BOOLEAN NOT NULL, is_frozen BOOLEAN NOT NULL, frozen_balance QortalAmount, "
							+ "PRIMARY key (AT_address))");
					// For finding executable ATs, ordered by creation timestamp
					stmt.execute("CREATE INDEX ATIndex on ATs (is_finished, created_when)");
					// For finding ATs by creator
					stmt.execute("CREATE INDEX ATCreatorIndex on ATs (creator)");

					// AT state on a per-block basis
					stmt.execute("CREATE TABLE ATStates (AT_address QortalAddress, height INTEGER NOT NULL, created_when EpochMillis NOT NULL, "
							+ "state_data ATState, state_hash ATStateHash NOT NULL, fees QortalAmount NOT NULL, is_initial BOOLEAN NOT NULL, "
							+ "PRIMARY KEY (AT_address, height), FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE)");
					// For finding per-block AT states, ordered by creation timestamp
					stmt.execute("CREATE INDEX BlockATStateIndex on ATStates (height, created_when)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE ATStates NEW SPACE");

					// Deploy CIYAM AT Transactions
					stmt.execute("CREATE TABLE DeployATTransactions (signature Signature, creator QortalPublicKey NOT NULL, AT_name ATName NOT NULL, "
							+ "description GenericDescription NOT NULL, AT_type ATType NOT NULL, AT_tags ATTags NOT NULL, "
							+ "creation_bytes ATCreationBytes NOT NULL, amount QortalAmount NOT NULL, asset_id AssetID NOT NULL, AT_address QortalAddress, "
							+ TRANSACTION_KEYS + ")");
					// For looking up the Deploy AT Transaction based on deployed AT address
					stmt.execute("CREATE INDEX DeployATAddressIndex on DeployATTransactions (AT_address)");

					// Generated AT Transactions
					stmt.execute("CREATE TABLE ATTransactions (signature Signature, AT_address QortalAddress NOT NULL, recipient QortalAddress, "
							+ "amount QortalAmount, asset_id AssetID, message ATMessage, "
							+ TRANSACTION_KEYS + ")");
					// For finding AT Transactions generated by a specific AT
					stmt.execute("CREATE INDEX ATTransactionsIndex on ATTransactions (AT_address)");
					break;

				case 12:
					// Groups
					// NOTE: We need to set Groups to `GROUPS` here to avoid SQL Standard Keywords in HSQLDB v2.7.4
					stmt.execute("CREATE TABLE `GROUPS` (group_id GroupID, owner QortalAddress NOT NULL, group_name GroupName NOT NULL, "
							+ "created_when EpochMillis NOT NULL, updated_when EpochMillis, is_open BOOLEAN NOT NULL, "
							+ "approval_threshold TINYINT NOT NULL, min_block_delay INTEGER NOT NULL, max_block_delay INTEGER NOT NULL, "
							+ "reference Signature, creation_group_id GroupID, reduced_group_name GroupName NOT NULL, "
							+ "description GenericDescription NOT NULL, PRIMARY KEY (group_id))");
					// For finding groups by name
					stmt.execute("CREATE INDEX GroupNameIndex on `GROUPS` (group_name)");
					// For finding groups by reduced name
					stmt.execute("CREATE INDEX GroupReducedNameIndex on `GROUPS` (reduced_group_name)");
					// For finding groups by owner
					stmt.execute("CREATE INDEX GroupOwnerIndex ON `GROUPS` (owner)");

					// We need a corresponding trigger to make sure new group_id values are assigned sequentially starting from 1
					stmt.execute("CREATE TRIGGER Group_ID_Trigger BEFORE INSERT ON `GROUPS` "
							+ "REFERENCING NEW ROW AS new_row FOR EACH ROW WHEN (new_row.group_id IS NULL) "
							+ "SET new_row.group_id = (SELECT IFNULL(MAX(group_id) + 1, 1) FROM `GROUPS`)");

					// Admins
					stmt.execute("CREATE TABLE GroupAdmins (group_id GroupID, admin QortalAddress, reference Signature NOT NULL, "
							+ "PRIMARY KEY (group_id, admin), FOREIGN KEY (group_id) REFERENCES `GROUPS` (group_id) ON DELETE CASCADE)");
					// For finding groups by admin address
					stmt.execute("CREATE INDEX GroupAdminIndex ON GroupAdmins (admin)");

					// Members
					stmt.execute("CREATE TABLE GroupMembers (group_id GroupID, address QortalAddress, "
							+ "joined_when EpochMillis NOT NULL, reference Signature NOT NULL, "
							+ "PRIMARY KEY (group_id, address), FOREIGN KEY (group_id) REFERENCES `GROUPS` (group_id) ON DELETE CASCADE)");
					// For finding groups by member address
					stmt.execute("CREATE INDEX GroupMemberIndex ON GroupMembers (address)");

					// Invites
					stmt.execute("CREATE TABLE GroupInvites (group_id GroupID, inviter QortalAddress, invitee QortalAddress, "
							+ "expires_when EpochMillis, reference Signature, "
							+ "PRIMARY KEY (group_id, invitee), FOREIGN KEY (group_id) REFERENCES `GROUPS` (group_id) ON DELETE CASCADE)");
					// For finding invites sent by inviter
					stmt.execute("CREATE INDEX GroupInviteInviterIndex ON GroupInvites (inviter)");
					// For finding invites by group
					stmt.execute("CREATE INDEX GroupInviteInviteeIndex ON GroupInvites (invitee)");
					// For expiry maintenance
					stmt.execute("CREATE INDEX GroupInviteExpiryIndex ON GroupInvites (expires_when)");

					// Pending "join requests"
					stmt.execute("CREATE TABLE GroupJoinRequests (group_id GroupID, joiner QortalAddress, reference Signature NOT NULL, "
							+ "PRIMARY KEY (group_id, joiner))");

					// Bans
					// NULL expires_when means does not expire!
					stmt.execute("CREATE TABLE GroupBans (group_id GroupID, offender QortalAddress, admin QortalAddress NOT NULL, "
							+ "banned_when EpochMillis NOT NULL, reason GenericDescription NOT NULL, expires_when EpochMillis, reference Signature NOT NULL, "
							+ "PRIMARY KEY (group_id, offender), FOREIGN KEY (group_id) REFERENCES `GROUPS` (group_id) ON DELETE CASCADE)");
					// For expiry maintenance
					stmt.execute("CREATE INDEX GroupBanExpiryIndex ON GroupBans (expires_when)");
					break;

				case 13:
					// Group transactions
					// Create group
					stmt.execute("CREATE TABLE CreateGroupTransactions (signature Signature, creator QortalPublicKey NOT NULL, group_name GroupName NOT NULL, "
							+ "is_open BOOLEAN NOT NULL, approval_threshold TINYINT NOT NULL, reduced_group_name GroupName NOT NULL, "
							+ "min_block_delay INTEGER NOT NULL, max_block_delay INTEGER NOT NULL, group_id GroupID, description GenericDescription NOT NULL, "
							+ TRANSACTION_KEYS + ")");

					// Update group
					stmt.execute("CREATE TABLE UpdateGroupTransactions (signature Signature, owner QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "new_owner QortalAddress NOT NULL, new_is_open BOOLEAN NOT NULL, new_approval_threshold TINYINT NOT NULL, "
							+ "new_min_block_delay INTEGER NOT NULL, new_max_block_delay INTEGER NOT NULL, "
							+ "group_reference Signature, new_description GenericDescription NOT NULL, " + TRANSACTION_KEYS + ")");

					// Promote to admin
					stmt.execute("CREATE TABLE AddGroupAdminTransactions (signature Signature, owner QortalPublicKey NOT NULL, "
							+ "group_id GroupID NOT NULL, address QortalAddress NOT NULL, " + TRANSACTION_KEYS + ")");

					// Demote from admin
					stmt.execute("CREATE TABLE RemoveGroupAdminTransactions (signature Signature, owner QortalPublicKey NOT NULL, "
							+ "group_id GroupID NOT NULL, admin QortalAddress NOT NULL, admin_reference Signature, "
							+ TRANSACTION_KEYS + ")");

					// Join group
					stmt.execute("CREATE TABLE JoinGroupTransactions (signature Signature, joiner QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "invite_reference Signature, previous_group_id GroupID, " + TRANSACTION_KEYS + ")");

					// Leave group
					stmt.execute("CREATE TABLE LeaveGroupTransactions (signature Signature, leaver QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "member_reference Signature, admin_reference Signature, previous_group_id GroupID, " + TRANSACTION_KEYS + ")");

					// Kick from group
					stmt.execute("CREATE TABLE GroupKickTransactions (signature Signature, admin QortalPublicKey NOT NULL, "
							+ "group_id GroupID NOT NULL, address QortalAddress NOT NULL, reason GroupReason, previous_group_id GroupID, "
							+ "member_reference Signature, admin_reference Signature, join_reference Signature, " + TRANSACTION_KEYS + ")");

					// Invite to group
					stmt.execute("CREATE TABLE GroupInviteTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "invitee QortalAddress NOT NULL, time_to_live INTEGER NOT NULL, join_reference Signature, previous_group_id GroupID, "
							+ TRANSACTION_KEYS + ")");

					// Cancel group invite
					stmt.execute("CREATE TABLE CancelGroupInviteTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "invitee QortalAddress NOT NULL, invite_reference Signature, " + TRANSACTION_KEYS + ")");

					// Ban from group
					stmt.execute("CREATE TABLE GroupBanTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "address QortalAddress NOT NULL, reason GroupReason, time_to_live INTEGER NOT NULL, previous_group_id GroupID, "
							+ "member_reference Signature, admin_reference Signature, join_invite_reference Signature, "
							+ TRANSACTION_KEYS + ")");

					// Unban from group
					stmt.execute("CREATE TABLE CancelGroupBanTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "address QortalAddress NOT NULL, ban_reference Signature, " + TRANSACTION_KEYS + ")");

					// Approval transactions
					// "pending_signature" contains signature of pending transaction requiring approval
					// "prior_reference" contains signature of previous approval transaction for orphaning purposes
					stmt.execute("CREATE TABLE GroupApprovalTransactions (signature Signature, admin QortalPublicKey NOT NULL, pending_signature Signature NOT NULL, approval BOOLEAN NOT NULL, "
							+ "prior_reference Signature, " + TRANSACTION_KEYS + ")");
					// For finding transactions pending approval, and maybe decision by specific admin
					stmt.execute("CREATE INDEX GroupApprovalLatestIndex on GroupApprovalTransactions (pending_signature, admin)");

					// SET_GROUP transaction support
					stmt.execute("CREATE TABLE SetGroupTransactions (signature Signature, default_group_id GroupID NOT NULL, previous_default_group_id GroupID, "
							+ TRANSACTION_KEYS + ")");
					break;

				case 14:
					// Networking
					stmt.execute("CREATE TABLE Peers (address VARCHAR(255), last_connected EpochMillis, last_attempted EpochMillis, "
							+ "last_misbehaved EpochMillis, added_when EpochMillis, added_by VARCHAR(255), PRIMARY KEY (address))");
					break;

				case 15:
					// Reward-shares
					// Transaction emitted by minter announcing they are sharing with recipient
					stmt.execute("CREATE TABLE RewardShareTransactions (signature Signature, minter_public_key QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
							+ "reward_share_public_key QortalPublicKey NOT NULL, share_percent RewardSharePercent NOT NULL, previous_share_percent RewardSharePercent, "
							+ TRANSACTION_KEYS + ")");

					// Active reward-shares
					stmt.execute("CREATE TABLE RewardShares (minter_public_key QortalPublicKey NOT NULL, minter QortalAddress NOT NULL, recipient QortalAddress NOT NULL, "
							+ "reward_share_public_key QortalPublicKey NOT NULL, share_percent RewardSharePercent NOT NULL, "
							+ "PRIMARY KEY (minter_public_key, recipient))");
					// For looking up reward-shares based on reward-share public key
					stmt.execute("CREATE INDEX RewardSharePublicKeyIndex ON RewardShares (reward_share_public_key)");
					break;

				case 16:
					// Stash of private keys used for generating blocks. These should be proxy keys!
					stmt.execute("CREATE TABLE MintingAccounts (minter_private_key QortalKeySeed NOT NULL, minter_public_key QortalPublicKey NOT NULL, PRIMARY KEY (minter_private_key))");
					break;

				case 17:
					// TRANSFER_PRIVS transaction
					stmt.execute("CREATE TABLE TransferPrivsTransactions (signature Signature, sender QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
							+ "previous_sender_flags INT, previous_recipient_flags INT, "
							+ "previous_sender_blocks_minted_adjustment INT, previous_sender_blocks_minted INT, "
							+ TRANSACTION_KEYS + ")");
					break;

				case 18:
					// Chat transactions
					stmt.execute("CREATE TABLE ChatTransactions (signature Signature, sender QortalAddress NOT NULL, nonce INT NOT NULL, recipient QortalAddress, "
							+ "is_text BOOLEAN NOT NULL, is_encrypted BOOLEAN NOT NULL, data MessageData NOT NULL, " + TRANSACTION_KEYS + ")");
					// For finding chat messages by sender
					stmt.execute("CREATE INDEX ChatTransactionsSenderIndex ON ChatTransactions (sender)");
					// For finding chat messages by recipient
					stmt.execute("CREATE INDEX ChatTransactionsRecipientIndex ON ChatTransactions (recipient, sender)");
					break;

				case 19:
					// PUBLICIZE transactions
					stmt.execute("CREATE TABLE PublicizeTransactions (signature Signature, nonce INT NOT NULL, " + TRANSACTION_KEYS + ")");
					break;

				case 20:
					// Trade bot
					// See case 25 below for changes
					stmt.execute("CREATE TABLE TradeBotStates (trade_private_key QortalKeySeed NOT NULL, trade_state TINYINT NOT NULL, "
							+ "creator_address QortalAddress NOT NULL, at_address QortalAddress, updated_when BIGINT NOT NULL, qort_amount QortalAmount NOT NULL, "
							+ "trade_native_public_key QortalPublicKey NOT NULL, trade_native_public_key_hash VARBINARY(32) NOT NULL, "
							+ "trade_native_address QortalAddress NOT NULL, secret VARBINARY(32) NOT NULL, hash_of_secret VARBINARY(32) NOT NULL, "
							+ "trade_foreign_public_key VARBINARY(33) NOT NULL, trade_foreign_public_key_hash VARBINARY(32) NOT NULL, "
							+ "bitcoin_amount BIGINT NOT NULL, xprv58 VARCHAR(200), last_transaction_signature Signature, locktime_a BIGINT, "
							+ "receiving_account_info VARBINARY(32) NOT NULL, PRIMARY KEY (trade_private_key))");
					break;

				case 21:
					// AT functionality index
					stmt.execute("CREATE INDEX IF NOT EXISTS ATCodeHashIndex ON ATs (code_hash, is_finished)");
					break;

				case 22:
					// LOB downsizing
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN online_accounts VARBINARY(1024)");
					stmt.execute("CHECKPOINT");
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN online_accounts_signatures VARBINARY(1048576)");
					stmt.execute("CHECKPOINT");

					stmt.execute("ALTER TABLE DeployATTransactions ALTER COLUMN creation_bytes VARBINARY(4096)");
					stmt.execute("CHECKPOINT");

					stmt.execute("ALTER TABLE ATs ALTER COLUMN code_bytes VARBINARY(1024)");
					stmt.execute("CHECKPOINT");

					stmt.execute("ALTER TABLE ATStates ALTER COLUMN state_data VARBINARY(1024)");
					stmt.execute("CHECKPOINT");
					break;

				case 23:
					// MESSAGE transactions index
					stmt.execute("CREATE INDEX IF NOT EXISTS MessageTransactionsRecipientIndex ON MessageTransactions (recipient, sender)");
					break;

				case 24:
					// Remove unused NextBlockHeight table and corresponding triggers
					stmt.execute("DROP TRIGGER IF EXISTS Next_block_height_insert_trigger");
					stmt.execute("DROP TRIGGER IF EXISTS Next_block_height_update_trigger");
					stmt.execute("DROP TRIGGER IF EXISTS Next_block_height_delete_trigger");
					stmt.execute("DROP TABLE IF EXISTS NextBlockHeight");
					break;

				case 25:
					// DISABLED: improved version in case 30!
					// Remove excess created_when from ATStates
					// stmt.execute("ALTER TABLE ATStates DROP created_when");
					// stmt.execute("CREATE INDEX ATStateHeightIndex on ATStates (height)");
					break;

				case 26:
					// Support for trimming
					stmt.execute("ALTER TABLE DatabaseInfo ADD AT_trim_height INT NOT NULL DEFAULT 0");
					stmt.execute("ALTER TABLE DatabaseInfo ADD online_signatures_trim_height INT NOT NULL DEFAULT 0");
					break;

				case 27:
					// More indexes
					stmt.execute("CREATE INDEX IF NOT EXISTS PaymentTransactionsRecipientIndex ON PaymentTransactions (recipient)");
					stmt.execute("CREATE INDEX IF NOT EXISTS ATTransactionsRecipientIndex ON ATTransactions (recipient)");
					break;

				case 28:
					// Latest AT state cache
					stmt.execute("CREATE TEMPORARY TABLE IF NOT EXISTS LatestATStates ("
								+ "AT_address QortalAddress NOT NULL, "
								+ "height INT NOT NULL"
							+ ")");
					break;

				case 29:
					// Turn off HSQLDB redo-log "blockchain.log" and periodically call "CHECKPOINT" ourselves
					stmt.execute("SET FILES LOG FALSE");
					stmt.execute("CHECKPOINT");
					break;

				case 30: {
					// Split AT state data off to new table for better performance/management.

					if (!wasPristine && !"mem".equals(HSQLDBRepository.getDbPathname(connection.getMetaData().getURL()))) {
						// First, backup node-local data in case user wants to avoid long reshape and use bootstrap instead
						try (ResultSet resultSet = stmt.executeQuery("SELECT COUNT(*) FROM MintingAccounts")) {
							int rowCount = resultSet.next() ? resultSet.getInt(1) : 0;
							if (rowCount > 0) {
								stmt.execute("PERFORM EXPORT SCRIPT FOR TABLE MintingAccounts DATA TO 'MintingAccounts.script'");
								LOGGER.info("Exported sensitive/node-local minting keys into MintingAccounts.script");
							}
						}

						try (ResultSet resultSet = stmt.executeQuery("SELECT COUNT(*) FROM TradeBotStates")) {
							int rowCount = resultSet.next() ? resultSet.getInt(1) : 0;
							if (rowCount > 0) {
								stmt.execute("PERFORM EXPORT SCRIPT FOR TABLE TradeBotStates DATA TO 'TradeBotStates.script'");
								LOGGER.info("Exported sensitive/node-local trade-bot states into TradeBotStates.script");
							}
						}

						LOGGER.info("If following reshape takes too long, use bootstrap and import node-local data using API's POST /admin/repository/data");
					}

					// Create new AT-states table without full state data
					stmt.execute("CREATE TABLE ATStatesNew ("
							+ "AT_address QortalAddress, height INTEGER NOT NULL, state_hash ATStateHash NOT NULL, "
							+ "fees QortalAmount NOT NULL, is_initial BOOLEAN NOT NULL, "
							+ "PRIMARY KEY (AT_address, height), "
							+ "FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE)");
					stmt.execute("SET TABLE ATStatesNew NEW SPACE");
					stmt.execute("CHECKPOINT");

					ResultSet resultSet = stmt.executeQuery("SELECT height FROM Blocks ORDER BY height DESC LIMIT 1");
					final int blockchainHeight = resultSet.next() ? resultSet.getInt(1) : 0;
					final int heightStep = 100;

					LOGGER.info("Rebuilding AT state summaries in repository - this might take a while... (approx. 2 mins on high-spec)");
					for (int minHeight = 1; minHeight < blockchainHeight; minHeight += heightStep) {
						stmt.execute("INSERT INTO ATStatesNew ("
								+ "SELECT AT_address, height, state_hash, fees, is_initial "
								+ "FROM ATStates "
								+ "WHERE height BETWEEN " + minHeight + " AND " + (minHeight + heightStep - 1)
								+ ")");
						stmt.execute("COMMIT");
					}
					stmt.execute("CHECKPOINT");

					LOGGER.info("Rebuilding AT states height index in repository - this might take about 3x longer...");
					stmt.execute("CREATE INDEX ATStatesHeightIndex ON ATStatesNew (height)");
					stmt.execute("CHECKPOINT");

					stmt.execute("CREATE TABLE ATStatesData ("
							+ "AT_address QortalAddress, height INTEGER NOT NULL, state_data ATState NOT NULL, "
							+ "PRIMARY KEY (height, AT_address), "
							+ "FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE)");
					stmt.execute("SET TABLE ATStatesData NEW SPACE");
					stmt.execute("CHECKPOINT");

					LOGGER.info("Rebuilding AT state data in repository - this might take a while... (approx. 2 mins on high-spec)");
					for (int minHeight = 1; minHeight < blockchainHeight; minHeight += heightStep) {
						stmt.execute("INSERT INTO ATStatesData ("
								+ "SELECT AT_address, height, state_data "
								+ "FROM ATstates "
								+ "WHERE state_data IS NOT NULL "
								+ "AND height BETWEEN " + minHeight + " AND " + (minHeight + heightStep - 1)
								+ ")");
						stmt.execute("COMMIT");
					}
					stmt.execute("CHECKPOINT");

					stmt.execute("DROP TABLE ATStates");
					stmt.execute("ALTER TABLE ATStatesNew RENAME TO ATStates");
					stmt.execute("CHECKPOINT");
					break;
				}

				case 31:
					// Fix latest AT state cache which was previous created as TEMPORARY
					stmt.execute("DROP TABLE IF EXISTS LatestATStates");
					stmt.execute("CREATE TABLE IF NOT EXISTS LatestATStates ("
								+ "AT_address QortalAddress NOT NULL, "
								+ "height INT NOT NULL, PRIMARY KEY (height, AT_address))");
					break;

				case 32:
					// Multiple blockchains, ACCTs and trade-bots
					stmt.execute("ALTER TABLE TradeBotStates ADD COLUMN acct_name VARCHAR(40) BEFORE trade_state");
					stmt.execute("UPDATE TradeBotStates SET acct_name = 'BitcoinACCTv1' WHERE acct_name IS NULL");
					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN acct_name SET NOT NULL");

					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN trade_state RENAME TO trade_state_value");

					stmt.execute("ALTER TABLE TradeBotStates ADD COLUMN trade_state VARCHAR(40) BEFORE trade_state_value");
					// Any existing values will be BitcoinACCTv1
					StringBuilder updateTradeBotStatesSql = new StringBuilder(1024);
					updateTradeBotStatesSql.append("UPDATE TradeBotStates SET (trade_state) = (")
							.append("SELECT state_name FROM (VALUES ")
							.append(
									Arrays.stream(BitcoinACCTv1TradeBot.State.values())
									.map(state -> String.format("(%d, '%s')", state.value, state.name()))
									.collect(Collectors.joining(", ")))
							.append(") AS BitcoinACCTv1States (state_value, state_name) ")
							.append("WHERE state_value = trade_state_value)");
					stmt.execute(updateTradeBotStatesSql.toString());
					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN trade_state SET NOT NULL");

					stmt.execute("ALTER TABLE TradeBotStates ADD COLUMN foreign_blockchain VARCHAR(40) BEFORE trade_foreign_public_key");

					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN bitcoin_amount RENAME TO foreign_amount");

					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN xprv58 RENAME TO foreign_key");

					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN secret SET NULL");
					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN hash_of_secret SET NULL");
					break;

				case 33:
					// PRESENCE transactions
					stmt.execute("CREATE TABLE IF NOT EXISTS PresenceTransactions ("
							+ "signature Signature, nonce INT NOT NULL, presence_type INT NOT NULL, "
							+ "timestamp_signature Signature NOT NULL, " + TRANSACTION_KEYS + ")");
					break;

				case 34: {
					// AT sleep-until-message support
					LOGGER.info("Altering AT table in repository - this might take a while... (approx. 20 seconds on high-spec)");
					stmt.execute("ALTER TABLE ATs ADD sleep_until_message_timestamp BIGINT");

					// Create new AT-states table with new column
					stmt.execute("CREATE TABLE ATStatesNew ("
							+ "AT_address QortalAddress, height INTEGER NOT NULL, state_hash ATStateHash NOT NULL, "
							+ "fees QortalAmount NOT NULL, is_initial BOOLEAN NOT NULL, sleep_until_message_timestamp BIGINT, "
							+ "PRIMARY KEY (AT_address, height), "
							+ "FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE)");
					stmt.execute("SET TABLE ATStatesNew NEW SPACE");
					stmt.execute("CHECKPOINT");

					// Add the height index
					LOGGER.info("Adding index to AT states table...");
					stmt.execute("CREATE INDEX ATStatesNewHeightIndex ON ATStatesNew (height)");
					stmt.execute("CHECKPOINT");

					ResultSet resultSet = stmt.executeQuery("SELECT height FROM Blocks ORDER BY height DESC LIMIT 1");
					final int blockchainHeight = resultSet.next() ? resultSet.getInt(1) : 0;
					final int heightStep = 100;

					LOGGER.info("Altering AT states table in repository - this might take a while... (approx. 3 mins on high-spec)");
					for (int minHeight = 1; minHeight < blockchainHeight; minHeight += heightStep) {
						stmt.execute("INSERT INTO ATStatesNew ("
								+ "SELECT AT_address, height, state_hash, fees, is_initial, NULL "
								+ "FROM ATStates "
								+ "WHERE height BETWEEN " + minHeight + " AND " + (minHeight + heightStep - 1)
								+ ")");
						stmt.execute("COMMIT");

						int processed = Math.min(minHeight + heightStep - 1, blockchainHeight);
						double percentage = (double)processed / (double)blockchainHeight * 100.0f;
						LOGGER.info(String.format("Processed %d of %d blocks (%.1f%%)", processed, blockchainHeight, percentage));
					}
					stmt.execute("CHECKPOINT");

					stmt.execute("DROP TABLE ATStates");
					stmt.execute("ALTER TABLE ATStatesNew RENAME TO ATStates");
					stmt.execute("ALTER INDEX ATStatesNewHeightIndex RENAME TO ATStatesHeightIndex");
					stmt.execute("CHECKPOINT");
					break;
				}
				case 35:
					// Support for pruning
					stmt.execute("ALTER TABLE DatabaseInfo ADD AT_prune_height INT NOT NULL DEFAULT 0");
					stmt.execute("ALTER TABLE DatabaseInfo ADD block_prune_height INT NOT NULL DEFAULT 0");
					break;

				case 36:
					// Block archive support
					stmt.execute("ALTER TABLE DatabaseInfo ADD block_archive_height INT NOT NULL DEFAULT 0");

					// Block archive (lookup table to map signature to height)
					// Actual data is stored in archive files outside of the database
					stmt.execute("CREATE TABLE BlockArchive (signature BlockSignature, height INTEGER NOT NULL, "
							+ "minted_when EpochMillis NOT NULL, minter QortalPublicKey NOT NULL, "
							+ "PRIMARY KEY (signature))");
					// For finding blocks by height.
					stmt.execute("CREATE INDEX BlockArchiveHeightIndex ON BlockArchive (height)");
					// For finding blocks by the account that minted them.
					stmt.execute("CREATE INDEX BlockArchiveMinterIndex ON BlockArchive (minter)");
					// For finding blocks by timestamp or finding height of latest block immediately before timestamp, etc.
					stmt.execute("CREATE INDEX BlockArchiveTimestampHeightIndex ON BlockArchive (minted_when, height)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE BlockArchive NEW SPACE");
					break;

				case 37:
					// ARBITRARY transaction updates for off-chain data storage

					// We may want to use a nonce rather than a transaction fee for ARBITRARY transactions
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD nonce INT NOT NULL DEFAULT 0");
					// We need to know the total size of the data file(s) associated with each transaction
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD size INT NOT NULL DEFAULT 0");
					// Larger data files need to be split into chunks, for easier transmission and greater decentralization
					// We store their hashes (and possibly other things) in a metadata file
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD metadata_hash VARBINARY(32)");
					// For finding transactions by file hash
					stmt.execute("CREATE INDEX ArbitraryDataIndex ON ArbitraryTransactions (is_data_raw, data)");
					break;

				case 38:
					// We need the ability for arbitrary transactions to be associated with a name
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD name RegisteredName");
					// A "method" specifies how the data should be applied (e.g. PUT or PATCH)
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD update_method INTEGER NOT NULL DEFAULT 0");
					// For public data, the AES shared secret needs to be available. This is more for data obfuscation as apposed to actual encryption.
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD secret VARBINARY(32)");
					// We want to support compressed and uncompressed data, as well as different compression algorithms
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD compression INTEGER NOT NULL DEFAULT 0");
					// An optional identifier string can be used to allow more than one resource per user/service combo
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD identifier VARCHAR(64)");
					// For finding transactions by registered name
					stmt.execute("CREATE INDEX ArbitraryNameIndex ON ArbitraryTransactions (name)");
					break;

				case 39:
					// Add DHT-style lookup table to track file locations
					// This maps ARBITRARY transactions to peer addresses, but also includes additional metadata to
					// track the local success rate and reachability. It is keyed by a "hash" column, to keep it
					// generic, as this way we aren't limited to transaction signatures only.
					// Multiple rows with the same hash are allowed, to allow for metadata. Longer term it could be
					// reshaped to one row per hash if this is too verbose.
					// Transaction signatures are hashed to 32 bytes using SHA256. In doing this we lose the ability
					// to join against transaction tables, but on balance the space savings seem more important.
					stmt.execute("CREATE TABLE ArbitraryPeers (hash VARBINARY(32) NOT NULL, "
							+ "peer_address VARCHAR(255), successes INTEGER NOT NULL, failures INTEGER NOT NULL, "
							+ "last_attempted EpochMillis NOT NULL, last_retrieved EpochMillis NOT NULL, "
							+ "PRIMARY KEY (hash, peer_address))");

					// For finding peers by data hash
					stmt.execute("CREATE INDEX ArbitraryPeersHashIndex ON ArbitraryPeers (hash)");
					break;

				case 40:
					// For looking up name registration transactions based on name or reduced name
					stmt.execute("CREATE INDEX RegisterNameNameIndex ON RegisterNameTransactions (name)");
					stmt.execute("CREATE INDEX RegisterNameReducedNameIndex ON RegisterNameTransactions (reduced_name)");
					// For looking up update name transactions based on name, new name, or new reduced name
					stmt.execute("CREATE INDEX UpdateNameNameIndex ON UpdateNameTransactions (name)");
					stmt.execute("CREATE INDEX UpdateNameNewNameIndex ON UpdateNameTransactions (new_name)");
					stmt.execute("CREATE INDEX UpdateNameReducedNewNameIndex ON UpdateNameTransactions (reduced_new_name)");
					// For looking up buy name transactions based on name
					stmt.execute("CREATE INDEX BuyNameNameIndex ON BuyNameTransactions (name)");
					// For looking up sell name transactions based on name
					stmt.execute("CREATE INDEX SellNameNameIndex ON SellNameTransactions (name)");
					break;

				case 41:
					// Drop the ArbitraryPeers table as it's no longer needed
					stmt.execute("DROP TABLE ArbitraryPeers");
					break;

				case 42:
					// We need more space for online accounts
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN online_accounts SET DATA TYPE VARBINARY(10240)");
					break;

				case 43:
					// Pirate Chain requires storing addresses that are 78 bytes long (69 bytes when decoded), so increase
					// from 32 to 128 to give some padding for potentially even larger addresses in the future
					stmt.execute("ALTER TABLE TradeBotStates ALTER COLUMN receiving_account_info SET DATA TYPE VARBINARY(128)");
					break;

				case 44:
					// Add blocks minted penalty
					stmt.execute("ALTER TABLE Accounts ADD blocks_minted_penalty INTEGER NOT NULL DEFAULT 0");
					break;

				case 45:
					// Add a chat reference, to allow one message to reference another, and for this to be easily
					// searchable. Null values are allowed as most transactions won't have a reference.
					stmt.execute("ALTER TABLE ChatTransactions ADD chat_reference Signature");
					// For finding chat messages by reference
					stmt.execute("CREATE INDEX ChatTransactionsChatReferenceIndex ON ChatTransactions (chat_reference)");
					break;

				case 46:
					// We need to track the sale price when canceling a name sale, so it can be put back when orphaned
					stmt.execute("ALTER TABLE CancelSellNameTransactions ADD sale_price QortalAmount");
					break;

				case 47:
					// Add `block_sequence` to the Transaction table, as the BlockTransactions table is pruned for
					// older blocks and therefore the sequence becomes unavailable
					LOGGER.info("Reshaping Transactions table - this can take a while...");
					stmt.execute("ALTER TABLE Transactions ADD block_sequence INTEGER");

					// For finding transactions by height and sequence
					LOGGER.info("Adding index to Transactions table - this can take a while...");
					stmt.execute("CREATE INDEX TransactionHeightSequenceIndex on Transactions (block_height, block_sequence)");
					break;

				case 48:
					// We need to keep a local cache of arbitrary resources (items published to QDN), for easier searching.
					// IMPORTANT: this is a cache of the last known state of a resource (both confirmed
					// and valid unconfirmed). It cannot be assumed that all nodes will contain the same state at a
					// given block height, and therefore must NOT be used for any consensus/validation code. It is
					// simply a cache, to avoid having to query the raw transactions and the metadata in flat files
					// when serving API requests.
					// ARBITRARY transactions aren't really suitable for updating resources in the same way we'd update
					// names or groups for instance, as there is no distinction between creations and updates, and metadata
					// is off-chain. Plus, QDN allows (valid) unconfirmed data to be queried and viewed. It is very
					// easy to keep a cache of the latest transaction's data, but anything more than that would need
					// considerable thought (and most likely a rewrite).

					stmt.execute("CREATE TABLE ArbitraryResourcesCache (service SMALLINT NOT NULL, "
							+ "name RegisteredName NOT NULL, identifier VARCHAR(64), size INT NOT NULL, "
							+ "status INTEGER DEFAULT 1, created_when EpochMillis NOT NULL, updated_when EpochMillis, "
							+ "PRIMARY KEY (service, name, identifier))");
					// For finding resources by service.
					stmt.execute("CREATE INDEX ArbitraryResourcesServiceIndex ON ArbitraryResourcesCache (service)");
					// For finding resources by name.
					stmt.execute("CREATE INDEX ArbitraryResourcesNameIndex ON ArbitraryResourcesCache (name)");
					// For finding resources by identifier.
					stmt.execute("CREATE INDEX ArbitraryResourcesIdentifierIndex ON ArbitraryResourcesCache (identifier)");
					// For finding resources by creation date (the default column when ordering).
					stmt.execute("CREATE INDEX ArbitraryResourcesCreatedIndex ON ArbitraryResourcesCache (created_when)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE ArbitraryResourcesCache NEW SPACE");

					stmt.execute("CREATE TABLE ArbitraryMetadataCache (service SMALLINT NOT NULL, "
							+ "name RegisteredName NOT NULL, identifier VARCHAR(64), "
							+ "title VARCHAR(80), description VARCHAR(240), category VARCHAR(64), "
							+ "tag1 VARCHAR(20), tag2 VARCHAR(20), tag3 VARCHAR(20), tag4 VARCHAR(20), tag5 VARCHAR(20), "
							+ "PRIMARY KEY (service, name, identifier), FOREIGN KEY (service, name, identifier) "
							+ "REFERENCES ArbitraryResourcesCache (service, name, identifier) ON DELETE CASCADE)");
					// For finding metadata by title.
					stmt.execute("CREATE INDEX ArbitraryMetadataTitleIndex ON ArbitraryMetadataCache (title)");

					// For finding arbitrary transactions by service
					stmt.execute("CREATE INDEX ArbitraryServiceIndex ON ArbitraryTransactions (service)");
					// For finding arbitrary transactions by identifier
					stmt.execute("CREATE INDEX ArbitraryIdentifierIndex ON ArbitraryTransactions (identifier)");
					break;

				case 49:
					// Update blocks minted penalty
					stmt.execute("UPDATE Accounts SET blocks_minted_penalty = -5000000 WHERE blocks_minted_penalty < 0");
					break;

				case 50:
					// Primary name for a Qortal Address, 0-1 for any address
					stmt.execute("CREATE TABLE PrimaryNames (owner QortalAddress, name RegisteredName, "
							+ "PRIMARY KEY (owner), FOREIGN KEY (name) REFERENCES Names (name) ON DELETE CASCADE)");
					break;

				case 51:

					LOGGER.info("Adding signatures to arbitrary resources cache table - this can take a while...");
					stmt.execute("ALTER TABLE ArbitraryResourcesCache ADD latest_signature Signature");
					stmt.execute("ALTER TABLE ArbitraryResourcesCache ADD lower_case_name RegisteredName");
					stmt.execute("CREATE INDEX ArbitraryResourcesServiceLowerNameIdIndex ON ArbitraryResourcesCache (service, lower_case_name, identifier)");
					stmt.execute("ALTER TABLE DatabaseInfo ADD latest_signature_populated TINYINT NOT NULL DEFAULT 0");

					break;

				default:
					// nothing to do
					return false;
			}
		}

		// database was updated
		LOGGER.info(() -> String.format("HSQLDB repository updated to version %d", databaseVersion + 1));
		return true;
	}
}
