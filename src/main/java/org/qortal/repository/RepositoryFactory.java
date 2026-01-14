package org.qortal.repository;

import java.sql.SQLException;

public interface RepositoryFactory {

	public boolean wasPristineAtOpen();

	public RepositoryFactory reopen() throws DataException;

	public Repository getRepository() throws DataException;

	public Repository tryRepository() throws DataException;

	public void close() throws DataException;

	// Not ideal place for this but implementing class will know the answer without having to open a new DB session
	public boolean isDeadlockException(SQLException e);

}
