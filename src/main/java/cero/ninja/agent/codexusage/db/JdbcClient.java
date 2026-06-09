package cero.ninja.agent.codexusage.db;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class JdbcClient {

    @Inject
    AgroalDataSource dataSource;

    public StatementSpec sql(String sql) {
        return new StatementSpec(this, sql);
    }

    Connection acquire() throws SQLException {
        return dataSource.getConnection();
    }

    static PreparedStatement bind(Connection conn, String sql, Map<String, Object> params) throws SQLException {
        NamedParameters.Parsed parsed = NamedParameters.parse(sql);
        String raw = parsed.sql();
        List<Object> ordered = new ArrayList<>(parsed.names().size());
        StringBuilder out = new StringBuilder(raw.length());
        int cursor = 0;
        for (String name : parsed.names()) {
            int q = raw.indexOf('?', cursor);
            out.append(raw, cursor, q);
            if (!params.containsKey(name)) {
                throw new SQLException("Missing parameter ':" + name + "' for SQL: " + sql);
            }
            Object value = params.get(name);
            if (value instanceof Collection<?> collection) {
                if (collection.isEmpty()) {
                    throw new SQLException("Empty collection for ':" + name + "' in SQL: " + sql);
                }
                boolean first = true;
                for (Object element : collection) {
                    if (!first) {
                        out.append(',');
                    }
                    out.append('?');
                    ordered.add(element);
                    first = false;
                }
            } else {
                out.append('?');
                ordered.add(value);
            }
            cursor = q + 1;
        }
        out.append(raw, cursor, raw.length());

        String finalSql = out.toString();
        PreparedStatement ps = conn.prepareStatement(finalSql);
        for (int i = 0; i < ordered.size(); i++) {
            Object value = ordered.get(i);
            if (value == null) {
                ps.setObject(i + 1, null);
            } else if (value instanceof Instant instant) {
                ps.setTimestamp(i + 1, Timestamp.from(instant));
            } else {
                ps.setObject(i + 1, value);
            }
        }
        return ps;
    }
}
