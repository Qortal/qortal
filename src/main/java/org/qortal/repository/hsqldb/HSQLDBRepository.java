package org.qortal.repository.hsqldb;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.crypto.Crypto;
import org.qortal.globalization.Translator;
import org.qortal.gui.SysTray;
import org.qortal.repository.*;
import org.qortal.repository.hsqldb.transaction.HSQLDBTransactionRepository;
import org.qortal.settings.Settings;

import java.awt.TrayIcon.MessageType;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class HSQLDBRepository implements Repository {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBRepository.class);

	public static final Object CHECKPOINT_LOCK = new Object();

	// "serialization failure"
	private static final Integer DEADLOCK_ERROR_CODE = Integer.valueOf(-4861);

	protected Connection connection;
	protected final Deque<Savepoint> savepoints = new ArrayDeque<>(3);
	protected boolean debugState = false;
	protected Long slowQueryThreshold = null;
	protected List<String> sqlStatements;
	protected long sessionId;
	protected final Map<String, PreparedStatement> preparedStatementCache = new HashMap<>();
	// We want the same object corresponding to the actual DB
	protected final Object trimHeightsLock = RepositoryManager.getRepositoryFactory();
	protected final Object latestATStatesLock = RepositoryManager.getRepositoryFactory();

	private final ATRepository atRepository = new HSQLDBATRepository(this);
	private final AccountRepository accountRepository = new HSQLDBAccountRepository(this);
	private final ArbitraryRepository arbitraryRepository = new HSQLDBArbitraryRepository(this);
	private final AssetRepository assetRepository = new HSQLDBAssetRepository(this);
	private final BlockRepository blockRepository = new HSQLDBBlockRepository(this);
	private final BlockArchiveRepository blockArchiveRepository = new HSQLDBBlockArchiveRepository(this);
	private final ChatRepository chatRepository = new HSQLDBChatRepository(this);
	private final CrossChainRepository crossChainRepository = new HSQLDBCrossChainRepository(this);
	private final GroupRepository groupRepository = new HSQLDBGroupRepository(this);
	private final MessageRepository messageRepository = new HSQLDBMessageRepository(this);
	private final NameRepository nameRepository = new HSQLDBNameRepository(this);
	private final NetworkRepository networkRepository = new HSQLDBNetworkRepository(this);
	private final TransactionRepository transactionRepository = new HSQLDBTransactionRepository(this);
	private final VotingRepository votingRepository = new HSQLDBVotingRepository(this);

	// Constructors

	// NB: no visibility modifier so only callable from within same package
	/* package */ HSQLDBRepository(Connection connection) throws DataException {
		this.connection = connection;

		this.slowQueryThreshold = Settings.getInstance().getSlowQueryThreshold();
		if (this.slowQueryThreshold != null)
			this.sqlStatements = new ArrayList<>();

		// Find out our session ID
		try (Statement stmt = this.connection.createStatement()) {
			if (!stmt.execute("SELECT SESSION_ID()"))
				throw new DataException("Unable to fetch session ID from repository");

			try (ResultSet resultSet = stmt.getResultSet()) {
				if (resultSet == null || !resultSet.next())
					throw new DataException("Unable to fetch session ID from repository");

				this.sessionId = resultSet.getLong(1);
			}
		} catch (SQLException e) {
			throw new DataException("Unable to fetch session ID from repository", e);
		}

		// synchronize to block new connections if checkpointing in progress 
		synchronized (CHECKPOINT_LOCK) {
			assertEmptyTransaction("connection creation");
		}
	}

	// Getters / setters

	@Override
	public ATRepository getATRepository() {
		return this.atRepository;
	}

	@Override
	public AccountRepository getAccountRepository() {
		return this.accountRepository;
	}

	@Override
	public ArbitraryRepository getArbitraryRepository() {
		return this.arbitraryRepository;
	}

	@Override
	public AssetRepository getAssetRepository() {
		return this.assetRepository;
	}

	@Override
	public BlockRepository getBlockRepository() {
		return this.blockRepository;
	}

	@Override
	public BlockArchiveRepository getBlockArchiveRepository() {
		return this.blockArchiveRepository;
	}

	@Override
	public ChatRepository getChatRepository() {
		return this.chatRepository;
	}

	@Override
	public CrossChainRepository getCrossChainRepository() {
		return this.crossChainRepository;
	}

	@Override
	public GroupRepository getGroupRepository() {
		return this.groupRepository;
	}

	@Override
	public MessageRepository getMessageRepository() {
		return this.messageRepository;
	}

	@Override
	public NameRepository getNameRepository() {
		return this.nameRepository;
	}

	@Override
	public NetworkRepository getNetworkRepository() {
		return this.networkRepository;
	}

	@Override
	public TransactionRepository getTransactionRepository() {
		return this.transactionRepository;
	}

	@Override
	public VotingRepository getVotingRepository() {
		return this.votingRepository;
	}

	@Override
	public boolean getDebug() {
		return this.debugState;
	}

	@Override
	public void setDebug(boolean debugState) {
		this.debugState = debugState;
	}

	// Transaction COMMIT / ROLLBACK / savepoints

	@Override
	public Connection getConnection() {
		return this.connection;
	}

	@Override
	public void saveChanges() throws DataException {
		long beforeQuery = this.slowQueryThreshold == null ? 0 : System.currentTimeMillis();

		try {
			this.connection.commit();

			if (this.slowQueryThreshold != null) {
				long queryTime = System.currentTimeMillis() - beforeQuery;

				if (queryTime > this.slowQueryThreshold) {
					LOGGER.info(() -> String.format("[Session %d] HSQLDB COMMIT took %d ms", this.sessionId, queryTime), new SQLException("slow commit"));

					logStatements();
				}
			}
		} catch (SQLException e) {
			throw new DataException("commit error", e);
		} finally {
			this.savepoints.clear();

			// Before clearing statements so we can log what led to assertion error
			assertEmptyTransaction("transaction commit");

			if (this.sqlStatements != null)
				this.sqlStatements.clear();
		}
	}

	@Override
	public void discardChanges() throws DataException {
		try {
			this.connection.rollback();
		} catch (SQLException e) {
			throw new DataException("rollback error", e);
		} finally {
			this.savepoints.clear();

			// Before clearing statements so we can log what led to assertion error
			assertEmptyTransaction("transaction rollback");

			if (this.sqlStatements != null)
				this.sqlStatements.clear();
		}
	}

	@Override
	public void setSavepoint() throws DataException {
		try {
			if (this.sqlStatements != null)
				// We don't know savepoint's ID yet
				this.sqlStatements.add("SAVEPOINT [?]");

			Savepoint savepoint = this.connection.setSavepoint();
			this.savepoints.push(savepoint);

			// Update query log with savepoint ID
			if (this.sqlStatements != null)
				this.sqlStatements.set(this.sqlStatements.size() - 1, "SAVEPOINT [" + savepoint.getSavepointId() + "]");
		} catch (SQLException e) {
			throw new DataException("savepoint error", e);
		}
	}

	@Override
	public void rollbackToSavepoint() throws DataException {
		if (this.savepoints.isEmpty())
			throw new DataException("no savepoint to rollback");

		Savepoint savepoint = this.savepoints.pop();

		try {
			if (this.sqlStatements != null)
				this.sqlStatements.add("ROLLBACK TO SAVEPOINT [" + savepoint.getSavepointId() + "]");

			this.connection.rollback(savepoint);
		} catch (SQLException e) {
			throw new DataException("savepoint rollback error", e);
		}
	}

	// Close / backup / rebuild / restore

	@Override
	public void close() throws DataException {
		// Already closed? No need to do anything but maybe report double-call
		if (this.connection == null) {
			LOGGER.warn("HSQLDBRepository.close() called when repository already closed. This is expected when bootstrapping.");
			return;
		}

		try {
			assertEmptyTransaction("connection close");

			// Assume we are not going to be GC'd for a while
			this.preparedStatementCache.clear();
			this.sqlStatements = null;
			this.savepoints.clear();

			// If a checkpoint has been requested, we could perform that now
			this.maybeCheckpoint();

			// Give connection back to the pool
			this.connection.close();
			this.connection = null;
		} catch (SQLException e) {
			throw new DataException("Error while closing repository", e);
		}
	}

	private void maybeCheckpoint() throws DataException {
		// To serialize checkpointing and to block new sessions when checkpointing in progress
		synchronized (CHECKPOINT_LOCK) {
			Boolean quickCheckpointRequest = RepositoryManager.getRequestedCheckpoint();
			if (quickCheckpointRequest == null)
				return;

			// We can only perform a CHECKPOINT if no other HSQLDB session is mid-transaction,
			// otherwise the CHECKPOINT blocks for COMMITs and other threads can't open HSQLDB sessions
			// due to HSQLDB blocking until CHECKPOINT finishes - i.e. deadlock
			String sql = "SELECT COUNT(*) "
					+ "FROM Information_schema.system_sessions "
					+ "WHERE transaction = TRUE";

			try {
				PreparedStatement pstmt = this.cachePreparedStatement(sql);

				if (!pstmt.execute())
					throw new DataException("Unable to check repository session status");

				try (ResultSet resultSet = pstmt.getResultSet()) {
					if (resultSet == null || !resultSet.next())
						// Failed to even find HSQLDB session info!
						throw new DataException("No results when checking repository session status");

					int transactionCount = resultSet.getInt(1);

					if (transactionCount > 0)
						// We can't safely perform CHECKPOINT due to ongoing SQL transactions
						return;
				}

				LOGGER.info("Performing repository CHECKPOINT...");

				if (Settings.getInstance().getShowCheckpointNotification())
					SysTray.getInstance().showMessage(Translator.INSTANCE.translate("SysTray", "DB_CHECKPOINT"),
							Translator.INSTANCE.translate("SysTray", "PERFORMING_DB_CHECKPOINT"),
							MessageType.INFO);

				try (Statement stmt = this.connection.createStatement()) {
					stmt.execute(Boolean.TRUE.equals(quickCheckpointRequest) ? "CHECKPOINT" : "CHECKPOINT DEFRAG");
				}

				// Completed!
				LOGGER.info("Repository CHECKPOINT completed!");
				RepositoryManager.setRequestedCheckpoint(null);
			} catch (SQLException e) {
				throw new DataException("Unable to check repository session status", e);
			}
		}
	}

	@Override
	public void rebuild() throws DataException {
		LOGGER.info("Rebuilding repository from scratch");

		// Clean out any previous backup
		try {
			String connectionUrl = this.connection.getMetaData().getURL();
			String dbPathname = getDbPathname(connectionUrl);
			if (dbPathname == null)
				throw new DataException("Unable to locate repository for rebuild?");

			// Close repository reference so we can close repository factory cleanly
			this.close();

			// Close repository factory to prevent access
			RepositoryManager.closeRepositoryFactory();

			// No need to wipe files for in-memory database
			if (!dbPathname.equals("mem")) {
				Path oldRepoDirPath = Paths.get(dbPathname).getParent();

				// Delete old repository files
				try (Stream<Path> paths = Files.walk(oldRepoDirPath)) {
					paths.sorted(Comparator.reverseOrder())
						.map(Path::toFile)
						.filter(file -> file.getPath().startsWith(dbPathname))
						.forEach(File::delete);
				}
			}
		} catch (NoSuchFileException e) {
			// Nothing to remove
		} catch (SQLException | IOException e) {
			throw new DataException("Unable to remove previous repository");
		}
	}

	@Override
	public void backup(boolean quick, String name, Long timeout) throws DataException, TimeoutException {
		synchronized (CHECKPOINT_LOCK) {

			// We can only perform a CHECKPOINT if no other HSQLDB session is mid-transaction,
			// otherwise the CHECKPOINT blocks for COMMITs and other threads can't open HSQLDB sessions
			// due to HSQLDB blocking until CHECKPOINT finishes - i.e. deadlock.
			// Since we don't want to give up too easily, it's best to wait until the other transaction
			// count reaches zero, and then continue.
			this.blockUntilNoOtherTransactions(timeout);

			if (!quick)
				// First perform a CHECKPOINT
				try (Statement stmt = this.connection.createStatement()) {
					LOGGER.info("Performing maintenance - this will take a while...");
					stmt.execute("CHECKPOINT");
					stmt.execute("CHECKPOINT DEFRAG");
					LOGGER.info("Maintenance completed");
				} catch (SQLException e) {
					throw new DataException("Unable to prepare repository for backup");
				}

			// Clean out any previous backup
			try {
				String connectionUrl = this.connection.getMetaData().getURL();
				String dbPathname = getDbPathname(connectionUrl);
				if (dbPathname == null)
					throw new DataException("Unable to locate repository for backup?");

				// Doesn't really make sense to backup an in-memory database...
				if (dbPathname.equals("mem")) {
					LOGGER.debug("Ignoring request to backup in-memory repository!");
					return;
				}

				String backupUrl = buildBackupUrl(dbPathname, name);
				String backupPathname = getDbPathname(backupUrl);
				if (backupPathname == null)
					throw new DataException("Unable to determine location for repository backup?");

				Path backupDirPath = Paths.get(backupPathname).getParent();
				String backupDirPathname = backupDirPath.toString();

				try (Stream<Path> paths = Files.walk(backupDirPath)) {
					paths.sorted(Comparator.reverseOrder())
							.map(Path::toFile)
							.filter(file -> file.getPath().startsWith(backupDirPathname))
							.forEach(File::delete);
				}
			} catch (NoSuchFileException e) {
				// Nothing to remove
			} catch (SQLException | IOException e) {
				throw new DataException("Unable to remove previous repository backup");
			}

			// Actually create backup
			try (Statement stmt = this.connection.createStatement()) {
				LOGGER.info("Backing up repository...");
				stmt.execute(String.format("BACKUP DATABASE TO '%s/' BLOCKING AS FILES", name));
				LOGGER.info("Backup completed");
			} catch (SQLException e) {
				throw new DataException("Unable to backup repository");
			}

		}
	}

	@Override
	public void performPeriodicMaintenance(Long timeout) throws DataException, TimeoutException {
		synchronized (CHECKPOINT_LOCK) {

			// We can only perform a CHECKPOINT if no other HSQLDB session is mid-transaction,
			// otherwise the CHECKPOINT blocks for COMMITs and other threads can't open HSQLDB sessions
			// due to HSQLDB blocking until CHECKPOINT finishes - i.e. deadlock.
			// Since we don't want to give up too easily, it's best to wait until the other transaction
			// count reaches zero, and then continue.
			this.blockUntilNoOtherTransactions(timeout);

			// Defrag DB - takes a while!
			try (Statement stmt = this.connection.createStatement()) {
				LOGGER.info("performing maintenance - this will take a while");
				stmt.execute("CHECKPOINT");
				stmt.execute("CHECKPOINT DEFRAG");
				LOGGER.info("maintenance completed");
			} catch (SQLException e) {
				throw new DataException("Unable to defrag repository");
			}
		}
	}

	@Override
	public void exportNodeLocalData() throws DataException {
		HSQLDBImportExport.backupTradeBotStates(this, null);
		HSQLDBImportExport.backupMintingAccounts(this);
	}

	@Override
	public void importDataFromFile(String filename) throws DataException, IOException {
		HSQLDBImportExport.importDataFromFile(filename, this);
	}

	@Override
	public void checkConsistency() throws DataException {
		this.getATRepository().checkConsistency();
	}

	/** Returns DB pathname from passed connection URL. If memory DB, returns "mem". */
	/*package*/ static String getDbPathname(String connectionUrl) {
		Pattern pattern = Pattern.compile("hsqldb:(mem|file):(.*?)(;|$)");
		Matcher matcher = pattern.matcher(connectionUrl);

		if (!matcher.find())
			return null;

		if (matcher.group(1).equals("mem"))
			return "mem";
		else
			return matcher.group(2);
	}

	private static String buildBackupUrl(String dbPathname, String backupName) {
		Path oldRepoPath = Paths.get(dbPathname);
		Path oldRepoDirPath = oldRepoPath.getParent();
		Path oldRepoFilePath = oldRepoPath.getFileName();

		// Try to open backup. We need to remove "create=true" and insert "backup" dir before final filename.
		String backupUrlTemplate = "jdbc:hsqldb:file:%s%s%s%s%s;create=false;hsqldb.full_log_replay=true";
		return String.format(backupUrlTemplate, oldRepoDirPath.toString(), File.separator, backupName, File.separator, oldRepoFilePath.toString());
	}

	/* package */ static void attemptRecovery(String connectionUrl, String name) throws DataException {
		String dbPathname = getDbPathname(connectionUrl);
		if (dbPathname == null)
			throw new DataException("Unable to locate repository for backup?");

		String backupUrl = buildBackupUrl(dbPathname, name);
		Path oldRepoDirPath = Paths.get(dbPathname).getParent();

		// Attempt connection to backup to see if it is viable
		try (Connection connection = DriverManager.getConnection(backupUrl)) {
			LOGGER.info("Attempting repository recovery using backup");

			// Move old repository files out the way
			try (Stream<Path> paths = Files.walk(oldRepoDirPath)) {
				paths.sorted(Comparator.reverseOrder())
					.map(Path::toFile)
					.filter(file -> file.getPath().startsWith(dbPathname))
					.forEach(File::delete);
			}

			try (Statement stmt = connection.createStatement()) {
				// Now "backup" the backup back to original repository location (the parent).
				// NOTE: trailing / is OK because HSQLDB checks for both / and O/S-specific separator.
				// textdb.allow_full_path connection property is required to be able to use '..'
				stmt.execute("BACKUP DATABASE TO '../' BLOCKING AS FILES");
			} catch (SQLException e) {
				// We really failed
				throw new DataException("Failed to recover repository to original location");
			}

			// Close backup
		} catch (SQLException e) {
			// We really failed
			throw new DataException("Failed to open repository or perform recovery");
		} catch (IOException e) {
			throw new DataException("Failed to delete old repository to perform recovery");
		}

		// Now attempt to open recovered repository, just to check
		try (Connection connection = DriverManager.getConnection(connectionUrl)) {
		} catch (SQLException e) {
			// We really failed
			throw new DataException("Failed to open recovered repository");
		}
	}

	// SQL statements, etc.

	/**
	 * Returns prepared statement using passed SQL, logging query if necessary.
	 */
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		if (this.debugState)
			LOGGER.debug(() -> String.format("[%d] %s", this.sessionId, sql));

		if (this.sqlStatements != null)
			this.sqlStatements.add(sql);

		return cachePreparedStatement(sql);
	}

	private PreparedStatement cachePreparedStatement(String sql) throws SQLException {
		/*
		 * We cache a duplicate PreparedStatement for this SQL string,
		 * which we never close, which means HSQLDB also caches a parsed,
		 * prepared statement that can be reused for subsequent
		 * calls to HSQLDB.prepareStatement(sql).
		 *
		 * See org.hsqldb.StatementManager for more details.
		 */
		PreparedStatement preparedStatement = this.preparedStatementCache.get(sql);
		if (preparedStatement == null || preparedStatement.isClosed()) {
			if (preparedStatement != null)
				// This shouldn't occur, so log, but recompile
				LOGGER.debug(() -> String.format("Recompiling closed PreparedStatement: %s", sql));

			preparedStatement =  this.connection.prepareStatement(sql);
			this.preparedStatementCache.put(sql, preparedStatement);
		} else {
			try {
				// Clean up ready for reuse
				preparedStatement.clearBatch();
				preparedStatement.clearParameters();
			} catch (SQLException e) {
				// Connection may have been closed, try to recreate the statement
				if (this.connection == null || this.connection.isClosed()) {
					throw new SQLException("Connection is closed", e);
				}
				// Statement is closed but connection is still open, recreate
				preparedStatement = this.connection.prepareStatement(sql);
				this.preparedStatementCache.put(sql, preparedStatement);
			}
		}

		return preparedStatement;
	}

	/**
	 * Execute SQL and return ResultSet with but added checking.
	 * <p>
	 * <b>Note: calls ResultSet.next()</b> therefore returned ResultSet is already pointing to first row.
	 * 
	 * @param sql
	 * @param objects
	 * @return ResultSet, or null if there are no found rows
	 * @throws SQLException
	 */
	public ResultSet checkedExecute(String sql, Object... objects) throws SQLException {
		PreparedStatement preparedStatement = this.prepareStatement(sql);

		// We don't close the PreparedStatement when the ResultSet is closed because we cached PreparedStatements now.
		// They are cleaned up when connection/session is closed.

		long beforeQuery = this.slowQueryThreshold == null ? 0 : System.currentTimeMillis();

		ResultSet resultSet = this.checkedExecuteResultSet(preparedStatement, objects);

		if (this.slowQueryThreshold != null) {
			long queryTime = System.currentTimeMillis() - beforeQuery;

			if (queryTime > this.slowQueryThreshold) {
				LOGGER.info(() -> String.format("[Session %d] HSQLDB query took %d ms: %s", this.sessionId, queryTime, sql), new SQLException("slow query"));

				logStatements();
			}
		}

		return resultSet;
	}

	/**
	 * Bind objects to placeholders in prepared statement.
	 * <p>
	 * Special treatment for BigDecimals so that they retain their "scale".
	 * 
	 * @param preparedStatement
	 * @param objects
	 * @throws SQLException
	 */
	private void bindStatementParams(PreparedStatement preparedStatement, Object... objects) throws SQLException {
		for (int i = 0; i < objects.length; ++i)
			// Special treatment for BigDecimals so that they retain their "scale",
			// which would otherwise be assumed as 0.
			if (objects[i] instanceof BigDecimal)
				preparedStatement.setBigDecimal(i + 1, (BigDecimal) objects[i]);
			else
				preparedStatement.setObject(i + 1, objects[i]);
	}

	/**
	 * Execute PreparedStatement and return ResultSet with but added checking.
	 * <p>
	 * <b>Note: calls ResultSet.next()</b> therefore returned ResultSet is already pointing to first row.
	 * 
	 * @param preparedStatement
	 * @param objects
	 * @return ResultSet, or null if there are no found rows
	 * @throws SQLException
	 */
	private ResultSet checkedExecuteResultSet(PreparedStatement preparedStatement, Object... objects) throws SQLException {
		bindStatementParams(preparedStatement, objects);

		// synchronize to block new executions if checkpointing in progress
		synchronized (CHECKPOINT_LOCK) {
			if (!preparedStatement.execute())
				throw new SQLException("Fetching from database produced no results");
		}

		ResultSet resultSet = preparedStatement.getResultSet();
		if (resultSet == null)
			throw new SQLException("Fetching results from database produced no ResultSet");

		if (!resultSet.next())
			return null;

		return resultSet;
	}

	/**
	 * Execute PreparedStatement and return changed row count.
	 * 
	 * @param sql
	 * @param objects
	 * @return number of changed rows
	 * @throws SQLException
	 */
	/* package */ int executeCheckedUpdate(String sql, Object... objects) throws SQLException {
		return this.executeCheckedBatchUpdate(sql, Collections.singletonList(objects));
	}

	/**
	 * Execute batched PreparedStatement
	 * 
	 * @param sql
	 * @param batchedObjects
	 * @return number of changed rows
	 * @throws SQLException
	 */
	/* package */ int executeCheckedBatchUpdate(String sql, List<Object[]> batchedObjects) throws SQLException {
		// Nothing to do?
		if (batchedObjects == null || batchedObjects.isEmpty())
			return 0;

		PreparedStatement preparedStatement = this.prepareStatement(sql);
		for (Object[] objects : batchedObjects) {
			this.bindStatementParams(preparedStatement, objects);
			preparedStatement.addBatch();
		}

		long beforeQuery = this.slowQueryThreshold == null ? 0 : System.currentTimeMillis();

		int[] updateCounts = null;
		try {
			updateCounts = preparedStatement.executeBatch();
		} catch (SQLException e) {
			if (isDeadlockException(e))
				// We want more info on what other DB sessions are doing to cause this
				examineException(e);

			throw e;
		}

		if (this.slowQueryThreshold != null) {
			long queryTime = System.currentTimeMillis() - beforeQuery;

			if (queryTime > this.slowQueryThreshold) {
				LOGGER.info(() -> String.format("[Session %d] HSQLDB query took %d ms: %s", this.sessionId, queryTime, sql), new SQLException("slow query"));

				logStatements();
			}
		}

		int totalCount = 0;
		for (int i = 0; i < updateCounts.length; ++i) {
			if (updateCounts[i] < 0)
				throw new SQLException("Database returned invalid row count");

			totalCount += updateCounts[i];
		}

		return totalCount;
	}

	/**
	 * Fetch last value of IDENTITY column after an INSERT statement.
	 * <p>
	 * Performs "CALL IDENTITY()" SQL statement to retrieve last value used when INSERTing into a table that has an IDENTITY column.
	 * <p>
	 * Typically used after INSERTing NULL as the IDENTITY column's value to fetch what value was actually stored by HSQLDB.
	 * 
	 * @return Long
	 * @throws SQLException
	 */
	public Long callIdentity() throws SQLException {
		// We don't need to use HSQLDBRepository.prepareStatement for this as it's so trivial
		try (PreparedStatement preparedStatement = this.connection.prepareStatement("CALL IDENTITY()");
				ResultSet resultSet = this.checkedExecuteResultSet(preparedStatement)) {
			if (resultSet == null)
				return null;

			return resultSet.getLong(1);
		}
	}

	/**
	 * Efficiently query database for existence of matching row.
	 * <p>
	 * {@code whereClause} is SQL "WHERE" clause containing "?" placeholders suitable for use with PreparedStatements.
	 * <p>
	 * Example call:
	 * <p>
	 * {@code String manufacturer = "Lamborghini";}<br>
	 * {@code int maxMileage = 100_000;}<br>
	 * {@code boolean isAvailable = exists("Cars", "manufacturer = ? AND mileage <= ?", manufacturer, maxMileage);}
	 * 
	 * @param tableName
	 * @param whereClause
	 * @param objects
	 * @return true if matching row found in database, false otherwise
	 * @throws SQLException
	 */
	public boolean exists(String tableName, String whereClause, Object... objects) throws SQLException {
		StringBuilder sql = new StringBuilder(256);
		sql.append("SELECT TRUE FROM ");
		sql.append(tableName);
		sql.append(" WHERE ");
		sql.append(whereClause);
		sql.append(" LIMIT 1");

		try (ResultSet resultSet = this.checkedExecute(sql.toString(), objects)) {
			// If matching row is found then resultSet will not be null
			return resultSet != null;
		}
	}

	/**
	 * Delete rows from database table.
	 * 
	 * @param tableName
	 * @param whereClause
	 * @param objects
	 * @throws SQLException
	 */
	public int delete(String tableName, String whereClause, Object... objects) throws SQLException {
		StringBuilder sql = new StringBuilder(256);
		sql.append("DELETE FROM ");
		sql.append(tableName);
		sql.append(" WHERE ");
		sql.append(whereClause);

		return this.executeCheckedUpdate(sql.toString(), objects);
	}

	/**
	 * Delete rows from database table.
	 * 
	 * @param tableName
	 * @param whereClause
	 * @param batchedObjects
	 * @throws SQLException
	 */
	public int deleteBatch(String tableName, String whereClause, List<Object[]> batchedObjects) throws SQLException {
		StringBuilder sql = new StringBuilder(256);
		sql.append("DELETE FROM ");
		sql.append(tableName);
		sql.append(" WHERE ");
		sql.append(whereClause);

		return this.executeCheckedBatchUpdate(sql.toString(), batchedObjects);
	}

	/**
	 * Delete all rows from database table.
	 * 
	 * @param tableName
	 * @throws SQLException
	 */
	public int delete(String tableName) throws SQLException {
		StringBuilder sql = new StringBuilder(256);
		sql.append("DELETE FROM ");
		sql.append(tableName);

		return this.executeCheckedUpdate(sql.toString());
	}

	/**
	 * Appends additional SQL "LIMIT" and "OFFSET" clauses.
	 * <p>
	 * (Convenience method for HSQLDB repository subclasses).
	 * 
	 * @param limit
	 * @param offset
	 */
	public static void limitOffsetSql(StringBuilder stringBuilder, Integer limit, Integer offset) {
		if (limit != null && limit > 0) {
			stringBuilder.append(" LIMIT ");
			stringBuilder.append(limit);
		}

		if (offset != null) {
			stringBuilder.append(" OFFSET ");
			stringBuilder.append(offset);
		}
	}

	/**
	 * Appends SQL for filling a temporary VALUES table, values NOT supplied.
	 * <p>
	 * (Convenience method for HSQLDB repository subclasses).
	 */
	/* package */ static void temporaryValuesTableSql(StringBuilder stringBuilder, int valuesCount, String tableName, String columnName) {
		stringBuilder.append("(VALUES ");

		for (int i = 0; i < valuesCount; ++i) {
			if (i != 0)
				stringBuilder.append(", ");

			stringBuilder.append("(?)");
		}

		stringBuilder.append(") AS ");
		stringBuilder.append(tableName);
		stringBuilder.append(" (");
		stringBuilder.append(columnName);
		stringBuilder.append(") ");
	}

	/**
	 * Appends SQL for filling a temporary VALUES table, literal values ARE supplied.
	 * <p>
	 * (Convenience method for HSQLDB repository subclasses).
	 */
	/* package */ static void temporaryValuesTableSql(StringBuilder stringBuilder, Collection<?> values, String tableName, String columnName) {
		stringBuilder.append("(VALUES ");

		boolean first = true;
		for (Object value : values) {
			if (first)
				first = false;
			else
				stringBuilder.append(", ");

			stringBuilder.append("(");
			stringBuilder.append(value);
			stringBuilder.append(")");
		}

		stringBuilder.append(") AS ");
		stringBuilder.append(tableName);
		stringBuilder.append(" (");
		stringBuilder.append(columnName);
		stringBuilder.append(") ");
	}

	// Debugging

	/**
	 * Logs this transaction's SQL statements, if enabled.
	 */
	public void logStatements() {
		if (this.sqlStatements == null)
			return;

		LOGGER.info(() -> String.format("[Session %d] HSQLDB SQL statements leading up to this were:", this.sessionId));

		for (String sql : this.sqlStatements)
			LOGGER.info(() -> String.format("[Session %d] %s", this.sessionId, sql));
	}

	/** Logs other HSQLDB sessions then returns passed exception */
	public SQLException examineException(SQLException e) {
		// TODO: could log at DEBUG for deadlocks by checking RepositoryManager.isDeadlockRelated(e)?

		LOGGER.error(() -> String.format("[Session %d] HSQLDB error: %s", this.sessionId, e.getMessage()), e);

		logStatements();

		// Serialization failure / potential deadlock - so list other sessions
		String sql = "SELECT session_id, transaction, transaction_size, waiting_for_this, this_waiting_for, current_statement FROM Information_schema.system_sessions";
		try (ResultSet resultSet = this.checkedExecute(sql)) {
			if (resultSet == null)
				return e;

			do {
				long systemSessionId = resultSet.getLong(1);
				boolean inTransaction = resultSet.getBoolean(2);
				long transactionSize = resultSet.getLong(3);
				String waitingForThis = resultSet.getString(4);
				String thisWaitingFor = resultSet.getString(5);
				String currentStatement = resultSet.getString(6);

				// Skip logging idle sessions
				if (transactionSize == 0 && waitingForThis.isEmpty() && thisWaitingFor.isEmpty() && currentStatement.isEmpty())
					continue;

				LOGGER.error(() -> String.format("Session %d, %s transaction (size %d), waiting for this '%s', this waiting for '%s', current statement: %s",
						systemSessionId, (inTransaction ? "in" : "not in"), transactionSize, waitingForThis, thisWaitingFor, currentStatement));
			} while (resultSet.next());
		} catch (SQLException de) {
			// Throw original exception instead
			return e;
		}

		return e;
	}

	private void assertEmptyTransaction(String context) throws DataException {
		// If connection is already closed, skip this check
		try {
			if (this.connection == null || this.connection.isClosed()) {
				LOGGER.debug(() -> String.format("Skipping transaction check after %s - connection already closed", context));
				return;
			}
		} catch (SQLException e) {
			// If we can't check if connection is closed, assume it is and skip
			LOGGER.debug(() -> String.format("Unable to check connection status after %s, skipping transaction check", context));
			return;
		}

		String sql = "SELECT transaction, transaction_size FROM information_schema.system_sessions WHERE session_id = ?";

		try {
			PreparedStatement stmt = this.cachePreparedStatement(sql);
			stmt.setLong(1, this.sessionId);

			// Diagnostic check for uncommitted changes
			if (!stmt.execute()) // TRANSACTION_SIZE() broken?
				throw new DataException("Unable to check repository status after " + context);

			try (ResultSet resultSet = stmt.getResultSet()) {
				if (resultSet == null || !resultSet.next()) {
					LOGGER.warn(() -> String.format("Unable to check repository status after %s", context));
					return;
				}

				boolean inTransaction = resultSet.getBoolean(1);
				int transactionCount = resultSet.getInt(2);

				if (inTransaction && transactionCount != 0) {
					LOGGER.warn(() -> String.format("Uncommitted changes (%d) after %s, session [%d]",
							transactionCount,
							context,
							this.sessionId),
							new Exception("Uncommitted repository changes"));
					logStatements();
				}
			}
		} catch (SQLException e) {
			// During shutdown, the connection might be closed by another thread
			// Check if this is the case and log appropriately
			try {
				if (this.connection == null || this.connection.isClosed()) {
					LOGGER.debug(() -> String.format("Connection closed while checking repository status after %s", context));
					return;
				}
			} catch (SQLException ignored) {
				// Ignore - we'll throw the original exception below
			}
			throw new DataException("Error checking repository status after " + context, e);
		}
	}

	public static byte[] ed25519PrivateToPublicKey(byte[] privateKey) {
		if (privateKey == null)
			return null;

		return Crypto.toPublicKey(privateKey);
	}

	public static String ed25519PublicKeyToAddress(byte[] publicKey) {
		if (publicKey == null)
			return null;

		return Crypto.toAddress(publicKey);
	}

	/*package*/ static boolean isDeadlockException(SQLException e) {
		return DEADLOCK_ERROR_CODE.equals(e.getErrorCode());
	}

	private int otherTransactionsCount() throws DataException {
		// We can only perform a CHECKPOINT if no other HSQLDB session is mid-transaction,
		// otherwise the CHECKPOINT blocks for COMMITs and other threads can't open HSQLDB sessions
		// due to HSQLDB blocking until CHECKPOINT finishes - i.e. deadlock
		String sql = "SELECT COUNT(*) "
				+ "FROM Information_schema.system_sessions "
				+ "WHERE transaction = TRUE AND session_id != ?";
		try {
			PreparedStatement pstmt = this.cachePreparedStatement(sql);
			pstmt.setLong(1, this.sessionId);

			if (!pstmt.execute())
				throw new DataException("Unable to check repository session status");

			try (ResultSet resultSet = pstmt.getResultSet()) {
				if (resultSet == null || !resultSet.next())
					// Failed to even find HSQLDB session info!
					throw new DataException("No results when checking repository session status");

				int transactionCount = resultSet.getInt(1);

				return transactionCount;
			}
		} catch (SQLException e) {
			throw new DataException("Unable to check repository session status", e);
		}
	}

	private void blockUntilNoOtherTransactions(Long timeout) throws DataException, TimeoutException {
		try {
			long startTime = System.currentTimeMillis();
			while (this.otherTransactionsCount() > 0) {
				// Wait and try again
				LOGGER.debug("Waiting for repository...");
				Thread.sleep(1000L);

				if (timeout != null) {
					if (System.currentTimeMillis() - startTime >= timeout) {
						throw new TimeoutException("Timed out waiting for repository to become available");
					}
				}
			}
		} catch (InterruptedException e) {
			throw new DataException("Interrupted before repository became available");
		}
	}

}
