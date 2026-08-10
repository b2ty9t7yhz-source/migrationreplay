package dev.migrationreplay.sql;

import dev.migrationreplay.config.ConfigurationException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SqlPolicy {
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> QUERY_FORBIDDEN = Set.of(
            "INSERT", "UPDATE", "DELETE", "REPLACE", "CREATE", "DROP", "ALTER",
            "ATTACH", "DETACH", "VACUUM", "PRAGMA", "REINDEX", "ANALYZE",
            "LOAD_EXTENSION");
    private static final Set<String> SCRIPT_FORBIDDEN = Set.of(
            "ATTACH", "DETACH", "VACUUM", "PRAGMA", "BEGIN", "COMMIT", "ROLLBACK",
            "SAVEPOINT", "RELEASE", "TRIGGER", "VIRTUAL", "LOAD_EXTENSION",
            "WRITABLE_SCHEMA");
    private static final Set<String> NONDETERMINISTIC_KEYWORDS = Set.of(
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP");
    private static final Set<String> NONDETERMINISTIC_FUNCTIONS = Set.of(
            "RANDOM", "RANDOMBLOB", "CHANGES", "TOTAL_CHANGES", "LAST_INSERT_ROWID",
            "DATE", "TIME", "DATETIME", "JULIANDAY", "UNIXEPOCH", "STRFTIME");

    private SqlPolicy() {}

    public static void validateQuery(String sql) throws ConfigurationException {
        if (sql == null || sql.isBlank()) {
            throw new ConfigurationException("EMPTY_QUERY", "Query SQL must not be blank.");
        }

        String masked = maskLiteralsCommentsAndQuotedIdentifiers(sql);
        validateSingleStatement(masked);
        Matcher words = WORD.matcher(masked);
        if (!words.find()) {
            throw new ConfigurationException("EMPTY_QUERY", "Query SQL has no statement.");
        }
        String first = words.group().toUpperCase(Locale.ROOT);
        if (!first.equals("SELECT") && !first.equals("WITH")) {
            throw new ConfigurationException(
                    "QUERY_NOT_READ_ONLY", "Query must start with SELECT or WITH.");
        }

        words.reset();
        while (words.find()) {
            String token = words.group().toUpperCase(Locale.ROOT);
            if (QUERY_FORBIDDEN.contains(token)) {
                throw new ConfigurationException(
                        "QUERY_NOT_READ_ONLY", "Query contains forbidden token: " + token);
            }
            if (NONDETERMINISTIC_KEYWORDS.contains(token)) {
                throw new ConfigurationException(
                        "NONDETERMINISTIC_QUERY",
                        "Query contains nondeterministic keyword: " + token);
            }
            if (NONDETERMINISTIC_FUNCTIONS.contains(token)
                    && nextNonWhitespace(masked, words.end()) == '(') {
                throw new ConfigurationException(
                        "NONDETERMINISTIC_QUERY",
                        "Query contains nondeterministic function: " + token);
            }
        }
    }

    public static void validateScript(String label, String sql) throws ConfigurationException {
        if (sql == null || sql.isBlank()) {
            throw new ConfigurationException("EMPTY_SQL_SCRIPT", label + " must not be blank.");
        }
        String masked = maskLiteralsCommentsAndQuotedIdentifiers(sql);
        Matcher words = WORD.matcher(masked);
        while (words.find()) {
            String token = words.group().toUpperCase(Locale.ROOT);
            if (SCRIPT_FORBIDDEN.contains(token)) {
                throw new ConfigurationException(
                        "UNSAFE_SQL_SCRIPT",
                        label + " contains unsupported or unsafe token: " + token);
            }
        }
        if (Pattern.compile("(?i)(^|;)\\s*END\\s*(;|$)").matcher(masked).find()) {
            throw new ConfigurationException(
                    "UNSAFE_SQL_SCRIPT", label + " contains transaction control: END");
        }
    }

    public static boolean hasExplicitOrderBy(String sql) throws ConfigurationException {
        String masked = maskLiteralsCommentsAndQuotedIdentifiers(sql);
        int depth = 0;
        boolean topLevelOrder = false;
        for (int index = 0; index < masked.length(); ) {
            char current = masked.charAt(index);
            if (current == '(') {
                depth++;
                topLevelOrder = false;
                index++;
            } else if (current == ')') {
                depth = Math.max(0, depth - 1);
                topLevelOrder = false;
                index++;
            } else if (isWordStart(current)) {
                int end = index + 1;
                while (end < masked.length() && isWordPart(masked.charAt(end))) {
                    end++;
                }
                if (depth == 0) {
                    String word = masked.substring(index, end).toUpperCase(Locale.ROOT);
                    if (topLevelOrder && word.equals("BY")) {
                        return true;
                    }
                    topLevelOrder = word.equals("ORDER");
                }
                index = end;
            } else {
                index++;
            }
        }
        return false;
    }

    public static String maskLiteralsCommentsAndQuotedIdentifiers(String sql)
            throws ConfigurationException {
        StringBuilder masked = new StringBuilder(sql.length());
        Mode mode = Mode.NORMAL;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            switch (mode) {
                case NORMAL -> {
                    if (current == '\'' ) {
                        masked.append(' ');
                        mode = Mode.SINGLE_QUOTE;
                    } else if (current == '"') {
                        masked.append(' ');
                        mode = Mode.DOUBLE_QUOTE;
                    } else if (current == '`') {
                        masked.append(' ');
                        mode = Mode.BACKTICK;
                    } else if (current == '[') {
                        masked.append(' ');
                        mode = Mode.BRACKET;
                    } else if (current == '-' && next == '-') {
                        masked.append("  ");
                        index++;
                        mode = Mode.LINE_COMMENT;
                    } else if (current == '/' && next == '*') {
                        masked.append("  ");
                        index++;
                        mode = Mode.BLOCK_COMMENT;
                    } else {
                        masked.append(current);
                    }
                }
                case SINGLE_QUOTE -> {
                    masked.append(current == '\n' ? '\n' : ' ');
                    if (current == '\'' && next == '\'') {
                        masked.append(' ');
                        index++;
                    } else if (current == '\'') {
                        mode = Mode.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    masked.append(current == '\n' ? '\n' : ' ');
                    if (current == '"' && next == '"') {
                        masked.append(' ');
                        index++;
                    } else if (current == '"') {
                        mode = Mode.NORMAL;
                    }
                }
                case BACKTICK -> {
                    masked.append(current == '\n' ? '\n' : ' ');
                    if (current == '`' && next == '`') {
                        masked.append(' ');
                        index++;
                    } else if (current == '`') {
                        mode = Mode.NORMAL;
                    }
                }
                case BRACKET -> {
                    masked.append(current == '\n' ? '\n' : ' ');
                    if (current == ']' && next == ']') {
                        masked.append(' ');
                        index++;
                    } else if (current == ']') {
                        mode = Mode.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    masked.append(current == '\n' ? '\n' : ' ');
                    if (current == '\n') {
                        mode = Mode.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    masked.append(current == '\n' ? '\n' : ' ');
                    if (current == '*' && next == '/') {
                        masked.append(' ');
                        index++;
                        mode = Mode.NORMAL;
                    }
                }
            }
        }

        if (mode != Mode.NORMAL && mode != Mode.LINE_COMMENT) {
            throw new ConfigurationException(
                    "UNTERMINATED_SQL_TOKEN", "SQL contains an unterminated literal or comment.");
        }
        return masked.toString();
    }

    private static void validateSingleStatement(String masked) throws ConfigurationException {
        int semicolon = masked.indexOf(';');
        if (semicolon < 0) {
            return;
        }
        if (!masked.substring(semicolon + 1).isBlank()
                || masked.indexOf(';', semicolon + 1) >= 0) {
            throw new ConfigurationException(
                    "MULTIPLE_QUERY_STATEMENTS", "Each query must contain exactly one statement.");
        }
    }

    private static char nextNonWhitespace(String value, int start) {
        for (int index = start; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return value.charAt(index);
            }
        }
        return '\0';
    }

    private static boolean isWordStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean isWordPart(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }

    private enum Mode {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        BRACKET,
        LINE_COMMENT,
        BLOCK_COMMENT
    }
}
