package dev.migrationreplay.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader();

    @Test
    void loadsAValidTypedParameterizedQuery() throws Exception {
        var config = loader.load("""
                version: 1
                queries:
                  - id: by-id
                    sql: SELECT :id AS id ORDER BY id
                    parameters:
                      id:
                        type: integer
                        value: 42
                    compare:
                      row_order: ordered
                """);

        assertEquals(1, config.queries().size());
        assertEquals(42L, config.queries().getFirst().parameters().get("id").value());
    }

    @Test
    void rejectsUnknownFields() {
        assertCode("UNKNOWN_CONFIG_FIELD", """
                version: 1
                queries:
                  - id: sample
                    sql: SELECT 1
                    typo: true
                """);
    }

    @Test
    void rejectsDuplicateQueryIds() {
        assertCode("DUPLICATE_QUERY_ID", """
                version: 1
                queries:
                  - id: repeated
                    sql: SELECT 1
                  - id: repeated
                    sql: SELECT 2
                """);
    }

    @Test
    void rejectsParameterMismatch() {
        assertCode("PARAMETER_MISMATCH", """
                version: 1
                queries:
                  - id: mismatch
                    sql: SELECT :expected
                    parameters:
                      different:
                        type: integer
                        value: 1
                """);
    }

    @Test
    void rejectsOrderedQueriesWithoutOrderBy() {
        assertCode("ORDERED_QUERY_WITHOUT_ORDER_BY", """
                version: 1
                queries:
                  - id: unordered-sql
                    sql: SELECT 1
                    compare:
                      row_order: ordered
                """);
    }

    @Test
    void rejectsInvalidBase64Parameters() {
        assertCode("INVALID_PARAMETER_VALUE", """
                version: 1
                queries:
                  - id: blob-query
                    sql: SELECT :payload
                    parameters:
                      payload:
                        type: blob
                        value: "%%%"
                """);
    }

    @Test
    void rejectsInvalidSchemaAssertionStateWithStructuredError() {
        assertCode("INVALID_ENUM_VALUE", """
                version: 1
                queries:
                  - id: sample
                    sql: SELECT 1
                schema_assertions:
                  - state: later
                    index_exists: idx_sample
                """);
    }

    @Test
    void rejectsDuplicateYamlKeys() {
        assertCode("INVALID_YAML", """
                version: 1
                version: 1
                queries:
                  - id: sample
                    sql: SELECT 1
                """);
    }

    @Test
    void rejectsUnsupportedConfigVersions() {
        assertCode("UNSUPPORTED_CONFIG_VERSION", """
                version: 2
                queries:
                  - id: sample
                    sql: SELECT 1
                """);
    }

    private void assertCode(String expected, String yaml) {
        ConfigurationException exception =
                assertThrows(ConfigurationException.class, () -> loader.load(yaml));
        assertEquals(expected, exception.code());
    }
}
