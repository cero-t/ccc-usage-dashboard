package cero.ninja.ccc.db;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

final class NamedParameters {

    record Parsed(String sql, List<String> names) {}

    private static final ConcurrentHashMap<String, Parsed> CACHE = new ConcurrentHashMap<>();

    static Parsed parse(String sql) {
        return CACHE.computeIfAbsent(sql, NamedParameters::doParse);
    }

    private static Parsed doParse(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        List<String> names = new ArrayList<>();
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < len) {
                    char d = sql.charAt(i);
                    out.append(d);
                    i++;
                    if (d == '\'') {
                        if (i < len && sql.charAt(i) == '\'') {
                            out.append('\'');
                            i++;
                            continue;
                        }
                        break;
                    }
                }
                continue;
            }
            if (c == ':' && i + 1 < len && sql.charAt(i + 1) == ':') {
                out.append("::");
                i += 2;
                continue;
            }
            if (c == ':' && i + 1 < len && isNameStart(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < len && isNamePart(sql.charAt(j))) {
                    j++;
                }
                names.add(sql.substring(i + 1, j));
                out.append('?');
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return new Parsed(out.toString(), List.copyOf(names));
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private NamedParameters() {}
}
