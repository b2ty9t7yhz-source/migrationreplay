package dev.migrationreplay.database;

import java.util.ArrayList;
import java.util.List;

final class SqlStatements {
    private SqlStatements() {}

    static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        Mode mode = Mode.NORMAL;
        int statementStart = 0;
        boolean hasSql = false;

        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            switch (mode) {
                case NORMAL -> {
                    if (current == ';') {
                        addStatement(statements, sql, statementStart, index, hasSql);
                        statementStart = index + 1;
                        hasSql = false;
                    } else if (current == '\'') {
                        hasSql = true;
                        mode = Mode.SINGLE_QUOTE;
                    } else if (current == '"') {
                        hasSql = true;
                        mode = Mode.DOUBLE_QUOTE;
                    } else if (current == '`') {
                        hasSql = true;
                        mode = Mode.BACKTICK;
                    } else if (current == '[') {
                        hasSql = true;
                        mode = Mode.BRACKET;
                    } else if (current == '-' && next == '-') {
                        index++;
                        mode = Mode.LINE_COMMENT;
                    } else if (current == '/' && next == '*') {
                        index++;
                        mode = Mode.BLOCK_COMMENT;
                    } else if (!Character.isWhitespace(current)) {
                        hasSql = true;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (current == '\'' && next == '\'') {
                        index++;
                    } else if (current == '\'') {
                        mode = Mode.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (current == '"' && next == '"') {
                        index++;
                    } else if (current == '"') {
                        mode = Mode.NORMAL;
                    }
                }
                case BACKTICK -> {
                    if (current == '`' && next == '`') {
                        index++;
                    } else if (current == '`') {
                        mode = Mode.NORMAL;
                    }
                }
                case BRACKET -> {
                    if (current == ']' && next == ']') {
                        index++;
                    } else if (current == ']') {
                        mode = Mode.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        mode = Mode.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        index++;
                        mode = Mode.NORMAL;
                    }
                }
            }
        }

        addStatement(statements, sql, statementStart, sql.length(), hasSql);
        return List.copyOf(statements);
    }

    private static void addStatement(
            List<String> statements, String sql, int start, int end, boolean hasSql) {
        if (hasSql) {
            statements.add(sql.substring(start, end).trim());
        }
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
