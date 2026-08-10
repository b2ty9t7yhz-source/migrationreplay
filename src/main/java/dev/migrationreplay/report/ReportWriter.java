package dev.migrationreplay.report;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.migrationreplay.report.ReportModels.RunReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ReportWriter {
    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    public record WrittenReports(Path json, Path markdown) {}

    public WrittenReports write(Path outputDirectory, RunReport report) throws IOException {
        Files.createDirectories(outputDirectory);
        Path json = outputDirectory.resolve("report.json");
        Path markdown = outputDirectory.resolve("report.md");
        String jsonText = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        writeAtomically(json, jsonText);
        writeAtomically(markdown, markdownRenderer.render(report));
        return new WrittenReports(json.toAbsolutePath(), markdown.toAbsolutePath());
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".migrationreplay-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
