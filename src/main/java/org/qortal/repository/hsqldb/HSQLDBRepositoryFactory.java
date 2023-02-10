package org.qortal.repository.hsqldb;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hsqldb.HsqlException;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.jdbc.HSQLDBPool;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.repository.RepositoryFactory;
import org.qortal.settings.Settings;

public class HSQLDBRepositoryFactory implements RepositoryFactory {

	public enum HSQLDBRepositoryType {
		MAIN,
		CHAT
	}

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBRepositoryFactory.class);

	/** Log getConnection() calls that take longer than this. (ms) */
	private static final long SLOW_CONNECTION_THRESHOLD = 1000L;

	private String connectionUrl;
	private HSQLDBPool connectionPool;
	private HSQLDBRepositoryType type;
	private final boolean wasPristine;

	/**
	 * Constructs new RepositoryFactory using passed <tt>connectionUrl</tt> and <tt>type</tt>.
	 * 
	 * @param connectionUrl
	 * @param type
	 * @throws DataException <i>without throwable</i> if repository in use by another process.
	 * @throws DataException <i>with throwable</i> if repository cannot be opened for some other reason.
	 */
	public HSQLDBRepositoryFactory(String connectionUrl, HSQLDBRepositoryType type) throws DataException {
		// one-time initialization goes in here
		this.connectionUrl = connectionUrl;
		this.type = type;

		// Check no-one else is accessing database
		try (Connection connection = DriverManager.getConnection(this.connectionUrl)) {
			// We only need to check we can obtain connection. It will be auto-closed.
		} catch (SQLException e) {
			Throwable cause = e.getCause();
			if (!(cause instanceof HsqlException))
				throw new DataException("Unable to open repository: " + e.getMessage(), e);

			HsqlException he = (HsqlException) cause;
			if (he.getErrorCode() == -ErrorCode.LOCK_FILE_ACQUISITION_FAILURE)
				throw new DataException("Unable to lock repository: " + e.getMessage());

			if (he.getErrorCode() != -ErrorCode.ERROR_IN_LOG_FILE && he.getErrorCode() != -ErrorCode.M_DatabaseScriptReader_read)
				throw new DataException("Unable to read repository: " + e.getMessage(), e);

			// Attempt recovery?
			HSQLDBRepository.attemptRecovery(connectionUrl, "backup");
		}

		this.connectionPool = new HSQLDBPool(Settings.getInstance().getRepositoryConnectionPoolSize());
		this.connectionPool.setUrl(this.connectionUrl);

		Properties properties = new Properties();
		properties.setProperty("close_result", "true"); // Auto-close old ResultSet if Statement creates new ResultSet
		this.connectionPool.setProperties(properties);

		// Perform DB updates?
		try (final Connection connection = this.connectionPool.getConnection()) {
			switch (this.type) {
				case MAIN:
					this.wasPristine = HSQLDBDatabaseUpdates.updateDatabase(connection);
					break;

				case CHAT:
					this.wasPristine = HSQLDBChatDatabaseUpdates.updateDatabase(connection);
					break;

				default:
					this.wasPristine = false;
					throw new DataException(String.format("No updates defined for %s repository", this.type));
			}
		} catch (SQLException e) {
			throw new DataException("Repository initialization error", e);
		}
	}

	/**
	 * Constructs new RepositoryFactory using passed <tt>connectionUrl</tt>, using the <tt>MAIN</tt> repository type.
	 *
	 * @param connectionUrl
	 * @throws DataException <i>without throwable</i> if repository in use by another process.
	 * @throws DataException <i>with throwable</i> if repository cannot be opened for some other reason.
	 */
	public HSQLDBRepositoryFactory(String connectionUrl) throws DataException {
		this(connectionUrl, HSQLDBRepositoryType.MAIN);
	}


	@Override
	public boolean wasPristineAtOpen() {
		return this.wasPristine;
	}

	@Override
	public RepositoryFactory reopen() throws DataException {
		return new HSQLDBRepositoryFactory(this.connectionUrl, this.type);
	}

	@Override
	public Repository getRepository() throws DataException {
		try {
			return new HSQLDBRepository(this.getConnection());
		} catch (SQLException e) {
			throw new DataException("Repository instantiation error", e);
		}
	}

	@Override
	public Repository tryRepository() throws DataException {
		try {
			Connection connection = this.tryConnection();
			if (connection == null)
				return null;

			return new HSQLDBRepository(connection);
		} catch (SQLException e) {
			throw new DataException("Repository instantiation error", e);
		}
	}

	private Connection getConnection() throws SQLException {
		final long before = System.currentTimeMillis();
		Connection connection = this.connectionPool.getConnection();
		final long delay = System.currentTimeMillis() - before;

		if (delay > SLOW_CONNECTION_THRESHOLD)
			// This could be an indication of excessive repository use, or insufficient pool size
			LOGGER.warn(() -> String.format("Fetching repository connection from pool took %dms (threshold: %dms)", delay, SLOW_CONNECTION_THRESHOLD));

		setupConnection(connection);
		return connection;
	}

	private Connection tryConnection() throws SQLException {
		Connection connection = this.connectionPool.tryConnection();
		if (connection == null)
			return null;

		setupConnection(connection);
		return connection;
	}

	private void setupConnection(Connection connection) throws SQLException {
		// Set transaction level
		connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
		connection.setAutoCommit(false);
	}

	@Override
	public void close() throws DataException {
		try {
			// Close all existing connections immediately
			this.connectionPool.close(0);

			// Now that all connections are closed, create a dedicated connection to shut down repository
			try (Connection connection = DriverManager.getConnection(this.connectionUrl);
					Statement stmt = connection.createStatement()) {
				stmt.execute("SHUTDOWN");
			}
		} catch (SQLException e) {
			throw new DataException("Error during repository shutdown", e);
		}
	}

	@Override
	public boolean isDeadlockException(SQLException e) {
		return HSQLDBRepository.isDeadlockException(e);
	}

}
