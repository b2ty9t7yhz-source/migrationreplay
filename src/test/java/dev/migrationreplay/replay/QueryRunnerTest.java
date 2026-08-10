package dev.migrationreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.migrationreplay.config.ConfigLoader;
import dev.migrationreplay.replay.ReplayModels.CanonicalValue;
import dev.migrationreplay.replay.ReplayModels.QueryOutcome;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;

final class QueryRunnerTest {
    @Test
    void bindsAndCanonicalizesEverySupportedRuntimeType() throws Exception {
        String yaml = """
                version: 1
                queries:
                  - id: typed-values
                    sql: |
                      SELECT
                        :null_value AS null_value,
                        :integer_value AS integer_value,
                        :real_value AS real_value,
                        :text_value AS text_value,
                        :blob_value AS blob_value
                    parameters:
                      null_value:
                        type: null
                        value: null
                      integer_value:
                        type: integer
                        value: 42
                      real_value:
                        type: real
                        value: 0.5
                      text_value:
                        type: text
                        value: "exact text"
                      blob_value:
                        type: blob
                        value: "AP8="
                """;
        var query = new ConfigLoader().load(yaml).queries().getFirst();

        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var observation = new QueryRunner().run(connection, query);

            assertEquals(QueryOutcome.SUCCESS, observation.outcome());
            assertEquals(
                    List.of("null_value", "integer_value", "real_value", "text_value", "blob_value"),
                    observation.columns());
            assertEquals(
                    List.of(
                            new CanonicalValue("null", null),
                            new CanonicalValue("integer", "42"),
                            new CanonicalValue("real", "0x1.0p-1"),
                            new CanonicalValue("text", "exact text"),
                            new CanonicalValue("blob", "AP8=")),
                    observation.rows().getFirst().values());
        }
    }
}
