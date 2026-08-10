package dev.migrationreplay.config;

public final class ConfigurationException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String code;

    public ConfigurationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ConfigurationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
