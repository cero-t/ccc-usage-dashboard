package cero.ninja.ccc.store;

import cero.ninja.ccc.db.JdbcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Forward-only cursors persisted in the local {@code cursor} table. A job reads
 * its cursor, processes only rows strictly after it, then advances it. On
 * failure a job simply returns without advancing, so the next pass retries the
 * same range — no gaps, no double counting.
 */
@ApplicationScoped
public class Cursors {

    @Inject
    JdbcClient db;

    /** Returns the stored cursor as a long, or {@code defaultValue} if unset. */
    public long getLong(String name, long defaultValue) {
        return db.sql("SELECT value FROM cursor WHERE name = :name")
                .param("name", name)
                .query((rs, row) -> rs.getString(1))
                .optional()
                .map(Long::parseLong)
                .orElse(defaultValue);
    }

    public void setLong(String name, long value) {
        db.sql("""
                INSERT INTO cursor (name, value) VALUES (:name, :value)
                ON CONFLICT(name) DO UPDATE SET value = :value
                """)
                .param("name", name)
                .param("value", Long.toString(value))
                .update();
    }
}
