package dev.migrationreplay.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NamedParameterSqlTest {
    @Test
    void compilesRepeatedNamedParametersInBindingOrder() throws Exception {
        NamedParameterSql compiled = NamedParameterSql.compile(
                "SELECT :value, :other, :value");

        assertEquals("SELECT ?, ?, ?", compiled.jdbcSql());
        assertEquals(List.of("value", "other", "value"), compiled.parameterNames());
    }

    @Test
    void ignoresParameterLikeTextInsideQuotedAndCommentedRegions() throws Exception {
        String sql = "SELECT ':ignored', \"quoted:name\", :actual -- :commented\n";
        NamedParameterSql compiled = NamedParameterSql.compile(sql);

        assertEquals("SELECT ':ignored', \"quoted:name\", ? -- :commented\n", compiled.jdbcSql());
        assertEquals(List.of("actual"), compiled.parameterNames());
    }
}
