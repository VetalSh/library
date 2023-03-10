package library.model.dao;

import library.exceptions.DaoException;
import library.model.entities.Lang;

import java.util.List;

/**
 * Functions specific to Lang class
 */
public interface LangDao {
    List<Lang> getAll() throws DaoException;
    Lang read(long id) throws DaoException;
    Lang read(String code) throws DaoException;
}