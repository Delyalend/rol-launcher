package rol.devkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON (objects, strings, numbers, null, arrays — enough for the
 * snapshot and manifest formats). No external dependencies.
 */
final class Json {

    private Json() {}

    /** Parse a JSON object into Map&lt;String,Object&gt; (values: Map/List/String/Long/Double/null). */
    static Map<String, Object> parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("Trailing data after JSON at position " + p.pos);
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("JSON root must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) m;
        return res;
    }

    /** Serialize a Map to JSON (values: Map/List/String/Long/Double/null). */
    static String write(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, map, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('\n').append("  ".repeat(indent + 1));
                sb.append(quote(String.valueOf(e.getKey()))).append(": ");
                writeValue(sb, e.getValue(), indent + 1);
            }
            if (!m.isEmpty()) {
                sb.append('\n').append("  ".repeat(indent));
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(' ');
                writeValue(sb, list.get(i), indent);
            }
            if (!list.isEmpty()) sb.append(' ');
            sb.append(']');
        } else if (v instanceof String s) {
            sb.append(quote(s));
        } else {
            sb.append(String.valueOf(v));
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean atEnd() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object parseValue() {
            skipWs();
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        List<Object> parseArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw err("expected ',' or ']'");
            }
        }

        Map<String, Object> parseObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (s.charAt(pos) != ':') throw err("expected ':'");
                pos++;
                map.put(key, parseValue());
                skipWs();
                char c = s.charAt(pos);
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw err("expected ',' or '}'");
            }
        }

        String parseString() {
            if (s.charAt(pos) != '"') throw err("expected string");
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        default -> throw err("unknown escape \\" + e);
                    }
                    if (e == 'u') pos += 4;
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseLiteral(String lit, Object value) {
            if (!s.startsWith(lit, pos)) throw err("expected " + lit);
            pos += lit.length();
            return value;
        }

        Object parseNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String num = s.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            return Long.parseLong(num);
        }

        IllegalArgumentException err(String msg) {
            return new IllegalArgumentException(msg + " (position " + pos + ")");
        }
    }
}
