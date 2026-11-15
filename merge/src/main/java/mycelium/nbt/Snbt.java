package mycelium.nbt;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Renders tags as text in the style of the game's {@code /data get} output, for reports and
 * test failures. Compound keys are sorted and long arrays are cut short, so this is for people
 * to read, not for parsing back.
 */
public final class Snbt {

    private static final int MAX_ARRAY_ELEMENTS = 16;
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9._+-]+");

    private Snbt() {
    }

    public static String format(Tag tag) {
        StringBuilder out = new StringBuilder();
        append(tag, out);
        return out.toString();
    }

    private static void append(Tag tag, StringBuilder out) {
        switch (tag) {
            case ByteTag t -> out.append(t.value()).append('b');
            case ShortTag t -> out.append(t.value()).append('s');
            case IntTag t -> out.append(t.value());
            case LongTag t -> out.append(t.value()).append('L');
            case FloatTag t -> out.append(t.value()).append('f');
            case DoubleTag t -> out.append(t.value()).append('d');
            case StringTag t -> quote(t.value(), out);
            case ByteArrayTag t -> {
                out.append("[B; ");
                byte[] values = t.bytes();
                for (int i = 0; i < Math.min(values.length, MAX_ARRAY_ELEMENTS); i++) {
                    out.append(i == 0 ? "" : ", ").append(values[i]).append('b');
                }
                closeArray(values.length, out);
            }
            case IntArrayTag t -> {
                out.append("[I; ");
                int[] values = t.ints();
                for (int i = 0; i < Math.min(values.length, MAX_ARRAY_ELEMENTS); i++) {
                    out.append(i == 0 ? "" : ", ").append(values[i]);
                }
                closeArray(values.length, out);
            }
            case LongArrayTag t -> {
                out.append("[L; ");
                long[] values = t.longs();
                for (int i = 0; i < Math.min(values.length, MAX_ARRAY_ELEMENTS); i++) {
                    out.append(i == 0 ? "" : ", ").append(values[i]).append('L');
                }
                closeArray(values.length, out);
            }
            case ListTag t -> {
                out.append('[');
                boolean first = true;
                for (Tag element : t) {
                    out.append(first ? "" : ", ");
                    append(element, out);
                    first = false;
                }
                out.append(']');
            }
            case CompoundTag t -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Tag> entry : new TreeMap<>(t.entries()).entrySet()) {
                    out.append(first ? "" : ", ");
                    if (BARE_KEY.matcher(entry.getKey()).matches()) {
                        out.append(entry.getKey());
                    } else {
                        quote(entry.getKey(), out);
                    }
                    out.append(": ");
                    append(entry.getValue(), out);
                    first = false;
                }
                out.append('}');
            }
        }
    }

    private static void closeArray(int length, StringBuilder out) {
        if (length > MAX_ARRAY_ELEMENTS) {
            out.append(", ... ").append(length).append(" in total");
        }
        out.append(']');
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
    }
}
