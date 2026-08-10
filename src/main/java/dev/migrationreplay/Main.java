package dev.migrationreplay;

import dev.migrationreplay.config.BundleLoader;
import dev.migrationreplay.config.ConfigurationException;
import dev.migrationreplay.config.InputBundle;
import dev.migrationreplay.engine.MigrationReplayEngine;
import dev.migrationreplay.report.ReportModels.RunReport;
import dev.migrationreplay.report.ReportWriter;
import dev.migrationreplay.report.ReportWriter.WrittenReports;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage(out);
            return args.length == 0 ? 2 : 0;
        }

        try {
            return switch (args[0]) {
                case "validate" -> validate(args, out);
                case "run" -> execute(args, out);
                default -> {
                    printError(err, "INVALID_COMMAND", "Unknown command: " + args[0]);
                    printUsage(err);
                    yield 2;
                }
            };
        } catch (ConfigurationException exception) {
            printError(err, exception.code(), exception.getMessage());
            return 2;
        } catch (IOException exception) {
            printError(err, "REPORT_WRITE_FAILED", exception.getMessage());
            return 2;
        }
    }

    private static int validate(String[] args, PrintStream out) throws ConfigurationException {
        if (args.length != 2) {
            throw new ConfigurationException(
                    "INVALID_ARGUMENTS", "Usage: migrationreplay validate <bundle-directory>");
        }
        InputBundle bundle = new BundleLoader().load(Path.of(args[1]));
        out.println("VALID");
        out.println("queries=" + bundle.config().queries().size());
        bundle.inputHashes().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> out.println(entry.getKey() + "=" + entry.getValue()));
        return 0;
    }

    private static int execute(String[] args, PrintStream out)
            throws ConfigurationException, IOException {
        if (args.length != 4 || !args[2].equals("--output-dir")) {
            throw new ConfigurationException(
                    "INVALID_ARGUMENTS",
                    "Usage: migrationreplay run <bundle-directory> --output-dir <directory>");
        }
        InputBundle bundle = new BundleLoader().load(Path.of(args[1]));
        RunReport report = new MigrationReplayEngine().run(bundle);
        WrittenReports written = new ReportWriter().write(Path.of(args[3]), report);
        out.println("status=" + report.status());
        out.println("json=" + written.json());
        out.println("markdown=" + written.markdown());
        return report.status().equals("PASS") ? 0 : 1;
    }

    private static void printUsage(PrintStream output) {
        output.println("MigrationReplay V1");
        output.println("Usage:");
        output.println("  migrationreplay validate <bundle-directory>");
        output.println("  migrationreplay run <bundle-directory> --output-dir <directory>");
    }

    private static void printError(PrintStream output, String code, String message) {
        output.println("{\"error\":{\"code\":\"" + jsonEscape(code)
                + "\",\"message\":\"" + jsonEscape(message == null ? "" : message) + "\"}}");
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
