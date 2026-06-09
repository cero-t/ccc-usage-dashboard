package cero.ninja.agent.codexusage.db;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

public final class ResultSpec<T> {

    private final JdbcClient client;
    private final String sql;
    private final Map<String, Object> params;
    private final RowMapper<T> mapper;

    ResultSpec(JdbcClient client, String sql, Map<String, Object> params, RowMapper<T> mapper) {
        this.client = client;
        this.sql = sql;
        this.params = params;
        this.mapper = mapper;
    }

    public Optional<T> optional() {
        List<T> all = list();
        if (all.isEmpty()) return Optional.empty();
        if (all.size() > 1) {
            throw new JdbcException("Expected at most 1 row, got " + all.size() + ": " + sql, null);
        }
        return Optional.ofNullable(all.get(0));
    }

    public T single() {
        return optional().orElseThrow(() -> new NoSuchElementException("No row returned: " + sql));
    }

    public List<T> list() {
        List<T> out = new ArrayList<>();
        try (var conn = client.acquire();
             var ps = JdbcClient.bind(conn, sql, params);
             var rs = ps.executeQuery()) {
            int row = 0;
            while (rs.next()) {
                out.add(mapper.map(rs, row++));
            }
        } catch (SQLException e) {
            throw new JdbcException("SELECT failed: " + sql, e);
        }
        return out;
    }
}
