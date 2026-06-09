package cero.ninja.agent.codexusage.db;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StatementSpec {

    private final JdbcClient client;
    private final String sql;
    private final Map<String, Object> params = new LinkedHashMap<>();

    StatementSpec(JdbcClient client, String sql) {
        this.client = client;
        this.sql = sql;
    }

    public StatementSpec param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    public <T> ResultSpec<T> query(Class<T> type) {
        return new ResultSpec<>(client, sql, params, RecordMapper.of(type));
    }

    public <T> ResultSpec<T> query(RowMapper<T> mapper) {
        return new ResultSpec<>(client, sql, params, mapper);
    }

    public int update() {
        try (var conn = client.acquire();
             var ps = JdbcClient.bind(conn, sql, params)) {
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new JdbcException("UPDATE failed: " + sql, e);
        }
    }
}
