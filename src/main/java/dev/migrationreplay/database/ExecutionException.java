package dev.migrationreplay.database;

public final class ExecutionException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final String phase;

    public ExecutionException(String code, String phase, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.phase = phase;
    }

    public ExecutionException(String code, String phase, String message) {
        super(message);
        this.code = code;
        this.phase = phase;
    }

    public String code() {
        return code;
    }

    public String phase() {
        return phase;
    }
}
