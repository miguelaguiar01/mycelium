package mycelium.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for block reports, rules files and the conflict report.
 *
 * <p>Objects are maps in document order, arrays are lists, and numbers are Integer, Long or
 * Double. Strings, Boolean and null stand for themselves.
 */
public final class Json {

    /** Documents nested deeper than this are refused instead of overflowing the stack. */
    private static final int MAX_DEPTH = 256;

    private final String text;
    private int pos;
    private int depth;

    private Json(String text) {
        this.text = text;
    }

    /** Parses one JSON document. */
    public static Object parse(String text) {
        Json json = new Json(text);
        try {
            Object value = json.value();
            json.skipSpace();
            if (json.pos != text.length()) {
                throw json.error("unexpected text after the document");
            }
            return value;
        } catch (StringIndexOutOfBoundsException e) {
            throw json.error("document ends early");
        }
    }

    /** The value as an object, or an error naming {@code what}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String what) {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException(what + " must be a JSON object");
    }

    /** The value as an array, or an error naming {@code what}. */
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value, String what) {
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        throw new IllegalArgumentException(what + " must be a JSON array");
    }

    /** The value as a list of strings, or an error naming {@code what}. A missing value is an empty list. */
    public static List<String> strings(Object value, String what) {
        if (value == null) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object element : array(value, what)) {
            strings.add(string(element, what));
        }
        return strings;
    }

    public static String string(Object value, String what) {
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalArgumentException(what + " must be a string");
    }

    /** Writes a value built from maps, lists, strings, numbers, booleans and null, indented by two spaces. */
    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, 0, out);
        return out.append('\n').toString();
    }

    private static void write(Object value, int depth, StringBuilder out) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(first ? "\n" : ",\n").append("  ".repeat(depth + 1));
                quote(String.valueOf(entry.getKey()), out);
                out.append(": ");
                write(entry.getValue(), depth + 1, out);
                first = false;
            }
            out.append('\n').append("  ".repeat(depth)).append('}');
        } else if (value instanceof List<?> list) {
            boolean flat = list.stream().noneMatch(e -> e instanceof Map<?, ?> || e instanceof List<?>);
            if (flat) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    out.append(i == 0 ? "" : ", ");
                    write(list.get(i), depth, out);
                }
                out.append(']');
                return;
            }
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                out.append(i == 0 ? "\n" : ",\n").append("  ".repeat(depth + 1));
                write(list.get(i), depth + 1, out);
            }
            out.append('\n').append("  ".repeat(depth)).append(']');
        } else if (value instanceof String string) {
            quote(string, out);
        } else if (value == null || value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            throw new IllegalArgumentException("cannot write a " + value.getClass().getName() + " as JSON");
        }
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private Object value() {
        if (depth >= MAX_DEPTH) {
            throw error("nested deeper than " + MAX_DEPTH);
        }
        depth++;
        try {
            return nested();
        } finally {
            depth--;
        }
    }

    private Object nested() {
        skipSpace();
        char c = text.charAt(pos);
        if (c == '{') {
            pos++;
            Map<String, Object> object = new LinkedHashMap<>();
            skipSpace();
            if (text.charAt(pos) == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                object.put(key, value());
                skipSpace();
                if (text.charAt(pos) == ',') {
                    pos++;
                } else {
                    expect('}');
                    return object;
                }
            }
        }
        if (c == '[') {
            pos++;
            List<Object> array = new ArrayList<>();
            skipSpace();
            if (text.charAt(pos) == ']') {
                pos++;
                return array;
            }
            while (true) {
                array.add(value());
                skipSpace();
                if (text.charAt(pos) == ',') {
                    pos++;
                } else {
                    expect(']');
                    return array;
                }
            }
        }
        if (c == '"') {
            return string();
        }
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        int start = pos;
        while (pos < text.length() && "+-.eE0123456789".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw error("unexpected character '" + c + "'");
        }
        String number = text.substring(start, pos);
        try {
            if (number.matches("-?\\d+")) {
                long value = Long.parseLong(number);
                return value == (int) value ? (Object) Integer.valueOf((int) value) : (Object) Long.valueOf(value);
            }
            return Double.valueOf(number);
        } catch (NumberFormatException e) {
            throw error("malformed number " + number);
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(pos++);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> out.append(escaped);
            }
        }
    }

    private void skipSpace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private void expect(char c) {
        if (text.charAt(pos) != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("invalid JSON: " + message + " at offset " + pos);
    }
}
