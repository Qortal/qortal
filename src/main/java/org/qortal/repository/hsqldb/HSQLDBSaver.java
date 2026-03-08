package org.qortal.repository.hsqldb;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.locks.Lock;

/**
 * Database helper for building, and executing, INSERT INTO ... ON DUPLICATE KEY UPDATE ... statements.
 * <p>
 * Columns, and corresponding values, are bound via close-coupled pairs in a chain thus:
 * <p>
 * {@code SaveHelper helper = new SaveHelper("TableName"); }<br>
 * {@code helper.bind("column_name", someColumnValue).bind("column2", columnValue2); }<br>
 * {@code helper.execute(repository); }<br>
 *
 */
public class HSQLDBSaver {

	private final Calendar utcCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

	private String table;

	private List<String> columns = new ArrayList<>();
	private List<Object> objects = new ArrayList<>();

	/**
	 * Construct a SaveHelper, using SQL Connection and table name.
	 * 
	 * @param table
	 */
	public HSQLDBSaver(String table) {
		this.table = table;
	}

	/**
	 * Add a column, and bound value, to be saved when execute() is called.
	 * 
	 * @param column
	 * @param value
	 * @return the same SaveHelper object
	 */
	public HSQLDBSaver bind(String column, Object value) {
		columns.add(column);
		objects.add(value);
		return this;
	}

	/**
	 * Build PreparedStatement using bound column-value pairs then execute it.
	 * 
	 * @param repository
	 *
	 * @return the result from {@link PreparedStatement#execute()}
	 * @throws SQLException
	 */
	public boolean execute(HSQLDBRepository repository) throws SQLException {
		String sql = this.formatInsertWithPlaceholders();

		Lock readLock = HSQLDBRepository.CHECKPOINT_GATE.readLock();
		readLock.lock();
		try {
			repository.markTransactionStarted();
			PreparedStatement preparedStatement = repository.prepareStatement(sql);
			this.bindValues(preparedStatement);

			return preparedStatement.execute();
		} catch (SQLException e) {
			throw repository.examineException(e);
		} finally {
			readLock.unlock();
		}
	}

	/**
	 * Format table and column names into an INSERT INTO ... SQL statement.
	 * <p>
	 * Full form is:
	 * <p>
	 * INSERT INTO <I>table</I> (<I>column</I>, ...) VALUES (?, ...) ON DUPLICATE KEY UPDATE <I>column</I>=?, ...
	 * <p>
	 * Note that HSQLDB needs to put into mySQL compatibility mode first via "SET DATABASE SQL SYNTAX MYS TRUE" or "sql.syntax_mys=true" in connection URL.
	 * 
	 * @return String
	 */
	private String formatInsertWithPlaceholders() {
		final int columnsSize = this.columns.size();

		StringBuilder output = new StringBuilder(1024);
		output.append("INSERT INTO ");
		output.append(this.table);
		output.append(" (");

		for (int ci = 0; ci < columnsSize; ++ci) {
			if (ci != 0)
				output.append(", ");

			output.append(this.columns.get(ci));
		}

		output.append(") VALUES (");

		for (int ci = 0; ci < columnsSize; ++ci) {
			if (ci != 0)
				output.append(", ");

			output.append("?");
		}

		output.append(") ON DUPLICATE KEY UPDATE ");

		for (int ci = 0; ci < columnsSize; ++ci) {
			if (ci != 0)
				output.append(", ");

			output.append(this.columns.get(ci));
			output.append("=?");
		}

		return output.toString();
	}

	/**
	 * Binds objects to PreparedStatement based on INSERT INTO ... ON DUPLICATE KEY UPDATE ...
	 * <p>
	 * Note that each object is bound to <b>two</b> place-holders based on this SQL syntax:
	 * <p>
	 * INSERT INTO <I>table</I> (<I>column</I>, ...) VALUES (<b>?</b>, ...) ON DUPLICATE KEY UPDATE <I>column</I>=<b>?</b>, ...
	 * <p>
	 * Requires that mySQL SQL syntax support is enabled during connection.
	 * 
	 * @param preparedStatement
	 * @throws SQLException
	 */
	private void bindValues(PreparedStatement preparedStatement) throws SQLException {
		for (int i = 0; i < this.objects.size(); ++i) {
			Object object = this.objects.get(i);

			if (object instanceof BigDecimal) {
				// Special treatment for BigDecimals so that they retain their "scale",
				// which would otherwise be assumed as 0.
				preparedStatement.setBigDecimal(i + 1, (BigDecimal) object);
				preparedStatement.setBigDecimal(i + this.objects.size() + 1, (BigDecimal) object);
			} else if (object instanceof Timestamp) {
				// Special treatment for Timestamps so that they are stored as UTC
				preparedStatement.setTimestamp(i + 1, (Timestamp) object, utcCalendar);
				preparedStatement.setTimestamp(i + this.objects.size() + 1, (Timestamp) object, utcCalendar);
			} else {
				preparedStatement.setObject(i + 1, object);
				preparedStatement.setObject(i + this.objects.size() + 1, object);
			}
		}

	}

}
