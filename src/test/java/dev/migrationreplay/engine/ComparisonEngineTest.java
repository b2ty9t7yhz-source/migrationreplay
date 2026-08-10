package dev.migrationreplay.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.migrationreplay.config.ConfigModels.RowOrder;
import dev.migrationreplay.replay.ReplayModels.CanonicalRow;
import dev.migrationreplay.replay.ReplayModels.CanonicalValue;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ComparisonEngineTest {
    @Test
    void unorderedComparisonIgnoresPositionButPreservesMultiplicity() {
        CanonicalRow one = row("integer", "1");
        CanonicalRow two = row("integer", "2");

        assertTrue(ComparisonEngine.rowsEquivalent(
                List.of(one, two, one), List.of(two, one, one), RowOrder.UNORDERED));
        assertFalse(ComparisonEngine.rowsEquivalent(
                List.of(one, two, one), List.of(two, one), RowOrder.UNORDERED));
    }

    @Test
    void orderedComparisonPreservesPosition() {
        CanonicalRow one = row("integer", "1");
        CanonicalRow two = row("integer", "2");

        assertFalse(ComparisonEngine.rowsEquivalent(
                List.of(one, two), List.of(two, one), RowOrder.ORDERED));
    }

    @Test
    void canonicalTypesRemainDistinct() {
        assertFalse(ComparisonEngine.rowsEquivalent(
                List.of(row("integer", "1")),
                List.of(row("text", "1")),
                RowOrder.UNORDERED));
    }

    private static CanonicalRow row(String type, String value) {
        return new CanonicalRow(List.of(new CanonicalValue(type, value)));
    }
}
