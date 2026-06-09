package cero.ninja.agent.codexusage.db;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet rs, int rowNum) throws SQLException;
}
