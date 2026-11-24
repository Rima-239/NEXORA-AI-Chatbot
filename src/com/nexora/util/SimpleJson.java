package com.nexora.util;

import java.util.*;

/**
 * SimpleJson
 * 
 * A clean, lightweight, zero-dependency JSON parser and serializer written in pure Core Java.
 * Handles objects, arrays, strings (including Unicode/Bengali characters), numbers, booleans, and null.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class SimpleJson {

    // =========================================================================
    // JSON SERIALIZER
    // =========================================================================

    /**
     * Serializes any supported Java object (Map, List, String, Number, Boolean, null) to a JSON string.
     */
    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        serializeValue(value, sb);
        return sb.toString();
    }

    private static void serializeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            serializeString((String) value, sb);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Map<?, ?>) {
            serializeMap((Map<?, ?>) value, sb);
        } else if (value instanceof List<?>) {
            serializeList((List<?>) value, sb);
        } else if (value instanceof Object[]) {
            serializeList(Arrays.asList((Object[]) value), sb);
        } else {
            // Fallback: convert to string
            serializeString(value.toString(), sb);
        }
    }

    private static void serializeString(String str, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // Preserve native Unicode for Bengali and Latin scripts without excessive escaping
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void serializeMap(Map<?, ?> map, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            serializeString(String.valueOf(entry.getKey()), sb);
            sb.append(':');
            serializeValue(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void serializeList(List<?> list, StringBuilder sb) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            serializeValue(item, sb);
        }
        sb.append(']');
    }

    // =========================================================================
    // JSON PARSER
    // =========================================================================

    /**
     * Parses a JSON string into a Map, List, String, Number, Boolean, or null.
     */
    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        // Strip optional UTF-8 Byte Order Mark (BOM)
        if (trimmed.startsWith("\uFEFF")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        Parser parser = new Parser(trimmed);
        return parser.parseValue();
    }

    /**
     * Parses a JSON string specifically into a Map<String, Object>.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        if (result instanceof Map<?, ?>) {
            return (Map<String, Object>) result;
        }
        throw new IllegalArgumentException("Expected JSON object, got: " + (result == null ? "null" : result.getClass().getSimpleName()));
    }

    /**
     * Parses a JSON string specifically into a List<Object>.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        Object result = parse(json);
        if (result instanceof List<?>) {
            return (List<Object>) result;
        }
        throw new IllegalArgumentException("Expected JSON array, got: " + (result == null ? "null" : result.getClass().getSimpleName()));
    }

    // =========================================================================
    // HELPER GETTERS FOR MAPS
    // =========================================================================

    public static String getString(Map<String, Object> map, String key, String defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) {
            return defaultValue;
        }
        return String.valueOf(map.get(key));
    }

    public static int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) {
            return defaultValue;
        }
        Object val = map.get(key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) {
            return defaultValue;
        }
        Object val = map.get(key);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return Boolean.parseBoolean(String.valueOf(val));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) {
            return Collections.emptyList();
        }
        Object val = map.get(key);
        if (val instanceof List<?>) {
            return (List<Object>) val;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> map, String key) {
        if (map == null || !map.containsKey(key)) {
            return Collections.emptyMap();
        }
        Object val = map.get(key);
        if (val instanceof Map<?, ?>) {
            return (Map<String, Object>) val;
        }
        return Collections.emptyMap();
    }

    // =========================================================================
    // PARSER ENGINE
    // =========================================================================

    private static class Parser {
        private final String src;
        private int pos = 0;

        public Parser(String src) {
            this.src = src;
        }

        public Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                return null;
            }

            char c = src.charAt(pos);
            if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else if (c == '"') {
                return parseString();
            } else if (c == 't' || c == 'f') {
                return parseBoolean();
            } else if (c == 'n') {
                return parseNull();
            } else if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            } else {
                throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            consume('{');
            skipWhitespace();

            if (peek() == '}') {
                consume('}');
                return map;
            }

            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                consume(':');
                Object value = parseValue();
                map.put(key, value);

                skipWhitespace();
                char c = peek();
                if (c == '}') {
                    consume('}');
                    break;
                } else if (c == ',') {
                    consume(',');
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' at position " + pos + ", found '" + c + "'");
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            consume('[');
            skipWhitespace();

            if (peek() == ']') {
                consume(']');
                return list;
            }

            while (true) {
                Object value = parseValue();
                list.add(value);

                skipWhitespace();
                char c = peek();
                if (c == ']') {
                    consume(']');
                    break;
                } else if (c == ',') {
                    consume(',');
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' at position " + pos + ", found '" + c + "'");
                }
            }
            return list;
        }

        private String parseString() {
            consume('"');
            StringBuilder sb = new StringBuilder();

            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence in string");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape at position " + pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new IllegalArgumentException("Invalid unicode hex: \\u" + hex);
                            }
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string starting near position " + pos);
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean literal at position " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at position " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                pos++;
            }
            boolean isDecimal = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    pos++;
                }
            }

            String numStr = src.substring(start, pos);
            try {
                if (isDecimal) {
                    return Double.parseDouble(numStr);
                } else {
                    long l = Long.parseLong(numStr);
                    if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                        return (int) l;
                    }
                    return l;
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number format: " + numStr + " at position " + start);
            }
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private void consume(char expected) {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Expected '" + expected + "', reached end of string");
            }
            char c = src.charAt(pos++);
            if (c != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + (pos - 1) + ", found '" + c + "'");
            }
        }

        private char peek() {
            skipWhitespace();
            if (pos >= src.length()) {
                return '\0';
            }
            return src.charAt(pos);
        }
    }
}
