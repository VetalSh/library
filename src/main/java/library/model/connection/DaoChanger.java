package library.model.connection;

import library.exceptions.DaoException;

import java.sql.Connection;

/**
 * Interface to be used in lambda-s in {@link library.model.connection.Transaction} class.
 * Changes something in DB, should be used only in transaction
 */
@FunctionalInterface
public interface DaoChanger {
  void proceed(Connection c) throws DaoException;
}
