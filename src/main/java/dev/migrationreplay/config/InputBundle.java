package dev.migrationreplay.config;

import dev.migrationreplay.config.ConfigModels.QueriesConfig;
import java.nio.file.Path;
import java.util.Map;

public record InputBundle(
        Path directory,
        String baselineSchema,
        String fixtures,
        String migrationUp,
        String migrationDown,
        QueriesConfig config,
        Map<String, String> inputHashes) {}
