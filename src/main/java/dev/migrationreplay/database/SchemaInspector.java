package dev.migrationreplay.database;

import dev.migrationreplay.replay.ReplayModels.ForeignKeyViolation;
import dev.migrationreplay.replay.ReplayModels.SchemaObject;
import dev.migrationreplay.replay.ReplayModels.SchemaSnapshot;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SchemaInspector {
    public SchemaSnapshot inspect(Connection connection) throws SQLException {
        List<SchemaObject> objects = schemaObjects(connection);
        Map<String, Long> rowCounts = rowCounts(connection, objects);
        List<String> integrityIssues = integrityIssues(connection);
        List<ForeignKeyViolation> foreignKeys = foreignKeyViolations(connection);
        List<String> indexNames = objects.stream()
                .filter(object -> object.type().equals("index"))
                .map(SchemaObject::name)
                .sorted()
                .toList();
        return new SchemaSnapshot(objects, rowCounts, integrityIssues, foreignKeys, indexNames);
    }

    public String sqliteVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT sqlite_version()")) {
            if (!result.next()) {
                throw new SQLException("sqlite_version() returned no row.");
            }
            return result.getString(1);
        }
    }

    private static List<SchemaObject> schemaObjects(Connection connection) throws SQLException {
        List<SchemaObject> objects = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT type, name, tbl_name, sql "
                                + "FROM sqlite_schema "
                                + "WHERE name NOT LIKE 'sqlite_%' "
                                + "ORDER BY type, name")) {
            while (rows.next()) {
                objects.add(new SchemaObject(
                        rows.getString("type"),
                        rows.getString("name"),
                        rows.getString("tbl_name"),
                        rows.getString("sql")));
            }
        }
        return List.copyOf(objects);
    }

    private static Map<String, Long> rowCounts(
            Connection connection, List<SchemaObject> objects) throws SQLException {
        Map<String, Long> counts = new TreeMap<>();
        for (SchemaObject object : objects) {
            if (!object.type().equals("table")) {
                continue;
            }
            String sql = "SELECT COUNT(*) FROM " + quoteIdentifier(object.name());
            try (Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery(sql)) {
                if (!result.next()) {
                    throw new SQLException("COUNT(*) returned no row for " + object.name());
                }
                counts.put(object.name(), result.getLong(1));
            }
        }
        return counts;
    }

    private static List<String> integrityIssues(Connection connection) throws SQLException {
        List<String> issues = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA integrity_check")) {
            while (rows.next()) {
                String value = rows.getString(1);
                if (!"ok".equalsIgnoreCase(value)) {
                    issues.add(value);
                }
            }
        }
        return List.copyOf(issues);
    }

    private static List<ForeignKeyViolation> foreignKeyViolations(Connection connection)
            throws SQLException {
        List<ForeignKeyViolation> violations = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            while (rows.next()) {
                Object rowId = rows.getObject(2);
                violations.add(new ForeignKeyViolation(
                        rows.getString(1),
                        rowId == null ? null : rowId.toString(),
                        rows.getString(3),
                        rows.getInt(4)));
            }
        }
        return List.copyOf(violations);
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
