package dev.migrationreplay.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SqlStatementsTest {
    @Test
    void splitsMultipleStatements() {
        assertEquals(
                List.of("CREATE TABLE sample (id INTEGER)", "INSERT INTO sample VALUES (1)"),
                SqlStatements.split(
                        "CREATE TABLE sample (id INTEGER); INSERT INTO sample VALUES (1);"));
    }

    @Test
    void preservesSemicolonsInsideLiteralsAndIdentifiers() {
        assertEquals(
                List.of("INSERT INTO [semi;colon] VALUES ('a;''b')"),
                SqlStatements.split("INSERT INTO [semi;colon] VALUES ('a;''b');"));
    }

    @Test
    void ignoresSemicolonsInsideComments() {
        assertEquals(
                List.of("/* ; */\nSELECT 1 -- ;"),
                SqlStatements.split("/* ; */\nSELECT 1 -- ;\n;"));
    }

    @Test
    void omitsEmptyAndCommentOnlySegments() {
        assertEquals(List.of("SELECT 1"), SqlStatements.split("; -- only a comment\n; SELECT 1;;"));
    }
}
