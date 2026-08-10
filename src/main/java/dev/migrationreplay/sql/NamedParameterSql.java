package dev.migrationreplay.sql;

import dev.migrationreplay.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;

public record NamedParameterSql(String jdbcSql, List<String> parameterNames) {
    public static NamedParameterSql compile(String sql) throws ConfigurationException {
        String masked = SqlPolicy.maskLiteralsCommentsAndQuotedIdentifiers(sql);
        StringBuilder jdbc = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();

        for (int index = 0; index < sql.length(); index++) {
            char current = masked.charAt(index);
            if (current == ':'
                    && (index == 0 || masked.charAt(index - 1) != ':')
                    && index + 1 < masked.length()
                    && isNameStart(masked.charAt(index + 1))) {
                int end = index + 2;
                while (end < masked.length() && isNamePart(masked.charAt(end))) {
                    end++;
                }
                names.add(sql.substring(index + 1, end));
                jdbc.append('?');
                index = end - 1;
            } else {
                jdbc.append(sql.charAt(index));
            }
        }

        return new NamedParameterSql(jdbc.toString(), List.copyOf(names));
    }

    private static boolean isNameStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean isNamePart(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }
}
