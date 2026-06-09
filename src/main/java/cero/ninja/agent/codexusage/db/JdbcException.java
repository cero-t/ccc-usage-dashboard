package cero.ninja.agent.codexusage.db;

public class JdbcException extends RuntimeException {
    public JdbcException(String message, Throwable cause) {
        super(message, cause);
    }
}
