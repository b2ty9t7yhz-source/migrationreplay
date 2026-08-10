package dev.migrationreplay.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.migrationreplay.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SqlPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "ATTACH DATABASE 'outside.db' AS outside;",
        "DETACH DATABASE outside;",
        "VACUUM;",
        "PRAGMA journal_mode = WAL;",
        "BEGIN; SELECT 1; COMMIT;",
        "ROLLBACK;",
        "SAVEPOINT nested;",
        "CREATE TRIGGER audit AFTER INSERT ON sample BEGIN SELECT 1; END;",
        "CREATE VIRTUAL TABLE search USING fts5(body);",
        "SELECT load_extension('unknown');",
        "END;"
    })
    void rejectsUnsupportedOrExternallyRiskyScriptSql(String sql) {
        ConfigurationException exception = assertThrows(
                ConfigurationException.class,
                () -> SqlPolicy.validateScript("migration_up.sql", sql));
        assertEquals("UNSAFE_SQL_SCRIPT", exception.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE sample SET value = 1",
        "DELETE FROM sample",
        "WITH removed AS (DELETE FROM sample RETURNING *) SELECT * FROM removed",
        "SELECT random()",
        "SELECT datetime('now')",
        "SELECT load_extension('unknown')",
        "SELECT CURRENT_TIMESTAMP",
        "SELECT 1; SELECT 2"
    })
    void rejectsWritableNondeterministicOrMultipleQuerySql(String sql) {
        assertThrows(ConfigurationException.class, () -> SqlPolicy.validateQuery(sql));
    }

    @Test
    void ignoresForbiddenWordsInsideLiteralsCommentsAndQuotedIdentifiers() {
        assertDoesNotThrow(() -> SqlPolicy.validateQuery(
                "SELECT 'VACUUM' AS \"DROP\" /* random() */ -- ATTACH\n"));
        assertDoesNotThrow(() -> SqlPolicy.validateScript(
                "fixtures.sql",
                "INSERT INTO messages(value) VALUES ('BEGIN; VACUUM;'); -- COMMIT\n"));
    }

    @Test
    void requiresOrderedQueriesToContainOrderByOutsideComments() throws Exception {
        org.junit.jupiter.api.Assertions.assertFalse(
                SqlPolicy.hasExplicitOrderBy("SELECT 1 /* ORDER BY ignored */"));
        org.junit.jupiter.api.Assertions.assertFalse(SqlPolicy.hasExplicitOrderBy(
                "SELECT * FROM (SELECT value FROM sample ORDER BY value)"));
        org.junit.jupiter.api.Assertions.assertTrue(
                SqlPolicy.hasExplicitOrderBy("SELECT value FROM sample ORDER BY value"));
    }
}
