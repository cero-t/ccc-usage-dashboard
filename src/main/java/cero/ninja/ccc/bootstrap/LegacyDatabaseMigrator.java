package cero.ninja.ccc.bootstrap;

import org.sqlite.SQLiteConnection;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.function.Consumer;

/** Copies a legacy working-directory database to the stable location without overwriting data. */
final class LegacyDatabaseMigrator {

    static final String SQLITE_OPTIONS = "?journal_mode=WAL&busy_timeout=10000";

    private static final String DATABASE_FILE = "ccc-usage-dashboard.sqlite";
    private static final String LEGACY_DATABASE_FILE = "codex-usage-dashboard.sqlite";

    private LegacyDatabaseMigrator() {
    }

    static ApplicationBootstrap.Migration migrate(Path workingDirectory, Path destination, Consumer<String> logger) {
        Path legacyDatabase = workingDirectory.resolve("data").resolve(LEGACY_DATABASE_FILE).toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        if (legacyDatabase.equals(normalizedDestination)) {
            logger.accept("Legacy database path already matches the selected database path: " + destination);
            return ApplicationBootstrap.Migration.ALREADY_SELECTED;
        }
        if (Files.exists(normalizedDestination)) {
            if (Files.exists(legacyDatabase)) {
                logger.accept("Both legacy and destination databases exist; keeping destination and leaving legacy untouched: "
                        + normalizedDestination);
            }
            return ApplicationBootstrap.Migration.DESTINATION_EXISTS;
        }
        if (!Files.isRegularFile(legacyDatabase)) {
            return ApplicationBootstrap.Migration.NO_LEGACY_DATABASE;
        }

        Path temporary = normalizedDestination.resolveSibling(
                "." + DATABASE_FILE + ".migrating-" + UUID.randomUUID());
        try {
            backupSqlite(legacyDatabase, temporary);
            verifySqlite(temporary);
            moveWithoutReplacing(temporary, normalizedDestination);
            logger.accept("Copied legacy database to the stable location; the original was retained for rollback: "
                    + legacyDatabase + " -> " + normalizedDestination);
            return ApplicationBootstrap.Migration.COPIED;
        } catch (FileAlreadyExistsException e) {
            deleteQuietly(temporary);
            logger.accept("Destination database appeared during migration; it was not overwritten: " + normalizedDestination);
            return ApplicationBootstrap.Migration.DESTINATION_EXISTS;
        } catch (Exception e) {
            deleteQuietly(temporary);
            throw new IllegalStateException(
                    "Failed to migrate legacy SQLite database without modifying the original: " + legacyDatabase, e);
        }
    }

    private static void backupSqlite(Path source, Path destination) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + source + "?busy_timeout=10000")) {
            SQLiteConnection sqlite = connection.unwrap(SQLiteConnection.class);
            int result = sqlite.getDatabase().backup("main", destination.toString(), null);
            if (result != 0) {
                throw new IllegalStateException("SQLite backup returned status " + result);
            }
        }
    }

    private static void verifySqlite(Path database) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA quick_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new IllegalStateException("Migrated SQLite database failed PRAGMA quick_check");
            }
        }
    }

    private static void moveWithoutReplacing(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Preserve the original migration exception.
        }
    }

}
