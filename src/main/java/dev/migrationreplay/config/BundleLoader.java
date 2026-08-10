package dev.migrationreplay.config;

import dev.migrationreplay.sql.SqlPolicy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BundleLoader {
    private static final long MAX_INPUT_BYTES = 10L * 1024L * 1024L;
    private static final String BASELINE_SCHEMA = "baseline_schema.sql";
    private static final String FIXTURES = "fixtures.sql";
    private static final String MIGRATION_UP = "migration_up.sql";
    private static final String MIGRATION_DOWN = "migration_down.sql";
    private static final String QUERIES = "queries.yaml";

    private final ConfigLoader configLoader = new ConfigLoader();

    public InputBundle load(Path requestedDirectory) throws ConfigurationException {
        final Path directory;
        try {
            directory = requestedDirectory.toRealPath();
        } catch (IOException exception) {
            throw new ConfigurationException(
                    "INVALID_BUNDLE_DIRECTORY",
                    "Bundle directory does not exist or cannot be read: " + requestedDirectory,
                    exception);
        }
        if (!Files.isDirectory(directory)) {
            throw new ConfigurationException(
                    "INVALID_BUNDLE_DIRECTORY", "Bundle path is not a directory: " + directory);
        }

        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (String fileName : new String[] {
                BASELINE_SCHEMA, FIXTURES, MIGRATION_UP, MIGRATION_DOWN, QUERIES
        }) {
            bytes.put(fileName, readRequiredFile(directory, fileName));
        }

        String baseline = decodeUtf8(bytes.get(BASELINE_SCHEMA), BASELINE_SCHEMA);
        String fixtures = decodeUtf8(bytes.get(FIXTURES), FIXTURES);
        String migrationUp = decodeUtf8(bytes.get(MIGRATION_UP), MIGRATION_UP);
        String migrationDown = decodeUtf8(bytes.get(MIGRATION_DOWN), MIGRATION_DOWN);
        String queriesYaml = decodeUtf8(bytes.get(QUERIES), QUERIES);

        SqlPolicy.validateScript(BASELINE_SCHEMA, baseline);
        SqlPolicy.validateScript(FIXTURES, fixtures);
        SqlPolicy.validateScript(MIGRATION_UP, migrationUp);
        SqlPolicy.validateScript(MIGRATION_DOWN, migrationDown);

        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : bytes.entrySet()) {
            hashes.put(entry.getKey(), sha256(entry.getValue()));
        }

        return new InputBundle(
                directory,
                baseline,
                fixtures,
                migrationUp,
                migrationDown,
                configLoader.load(queriesYaml),
                Map.copyOf(hashes));
    }

    private static byte[] readRequiredFile(Path directory, String fileName)
            throws ConfigurationException {
        Path file = directory.resolve(fileName);
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ConfigurationException(
                    "MISSING_INPUT_FILE",
                    "Required input must be a regular, non-symlink file: " + fileName);
        }
        try {
            long size = Files.size(file);
            if (size > MAX_INPUT_BYTES) {
                throw new ConfigurationException(
                        "INPUT_TOO_LARGE", fileName + " exceeds the 10 MiB V1 limit.");
            }
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new ConfigurationException(
                    "INPUT_READ_FAILED", "Could not read " + fileName + ".", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes, String fileName) throws ConfigurationException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ConfigurationException(
                    "INVALID_UTF8", fileName + " must be valid UTF-8.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", exception);
        }
    }
}
