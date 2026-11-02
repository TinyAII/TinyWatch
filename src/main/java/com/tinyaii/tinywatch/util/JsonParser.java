package com.tinyaii.tinywatch.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（只解析，不生成）。
 *
 * <p>为什么自己写：插件是**零依赖单 jar**，不能为了读一个版本检查响应就引入 Gson/Jackson。
 * 也不能用正则硬抠 —— release_notes 里完全可能有引号、换行、转义字符。
 *
 * <p>解析结果类型映射：
 * <ul>
 *   <li>object -&gt; {@code Map<String, Object>}（LinkedHashMap，保留顺序）</li>
 *   <li>array  -&gt; {@code List<Object>}</li>
 *   <li>string -&gt; {@code String}（已处理转义）</li>
 *   <li>number -&gt; {@code Double}</li>
 *   <li>true/false -&gt; {@code Boolean}</li>
 *   <li>null -&gt; {@code null}</li>
 * </ul>
 *
 * <p>解析失败抛 {@link IllegalArgumentException}，调用方自己 catch —— 网络返回从来不可信。
 */
public final class JsonParser {

    private final String src;
    private int pos;

    private JsonParser(String src) {
        this.src = src;
    }

    /** 解析任意 JSON 值。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("空输入");
        }
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw new IllegalArgumentException("多余内容，位置 " + parser.pos);
        }
        return value;
    }

    /**
     * 解析 JSON 对象，并转成 {@code Map<String, Object>}。
     *
     * @throws IllegalArgumentException 不是对象或格式错误
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("顶层不是对象");
        }
        return (Map<String, Object>) value;
    }

    // ────────────────────────── 内部 ──────────────────────────

    private Object parseValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("意外的结尾");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObjectValue();
            case '[':
                return parseArrayValue();
            case '"':
                return parseStringValue();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return parseNumberValue();
        }
    }

    private Map<String, Object> parseObjectValue() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        pos++; // {
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw new IllegalArgumentException("对象键必须是字符串，位置 " + pos);
            }
            String key = parseStringValue();
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw new IllegalArgumentException("缺少冒号，位置 " + pos);
            }
            pos++;
            Object value = parseValue();
            map.put(key, value);
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("对象未闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new IllegalArgumentException("对象里出现意外字符 '" + c + "'，位置 " + pos);
        }
    }

    private List<Object> parseArrayValue() {
        List<Object> list = new ArrayList<Object>();
        pos++; // [
        skipWhitespace();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("数组未闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new IllegalArgumentException("数组里出现意外字符 '" + c + "'，位置 " + pos);
        }
    }

    private String parseStringValue() {
        pos++; // 开头的引号
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                if (pos >= src.length()) {
                    throw new IllegalArgumentException("转义未完成");
                }
                char esc = src.charAt(pos);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 >= src.length()) {
                            throw new IllegalArgumentException("\\u 转义不完整");
                        }
                        String hex = src.substring(pos + 1, pos + 5);
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("未知转义 \\" + esc);
                }
                pos++;
                continue;
            }
            sb.append(c);
            pos++;
        }
        throw new IllegalArgumentException("字符串未闭合");
    }

    private Double parseNumberValue() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw new IllegalArgumentException("不是合法的值，位置 " + start);
        }
        try {
            return Double.valueOf(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数字格式错误: " + src.substring(start, pos));
        }
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("期望 " + literal + "，位置 " + pos);
        }
        pos += literal.length();
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    // ────────────────────────── 取值辅助 ──────────────────────────

    /** 取字符串字段，不存在或类型不对返回 fallback。 */
    public static String getString(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    /** 取布尔字段。 */
    public static boolean getBoolean(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    /** 取整数字段（JSON 数字统一是 Double）。 */
    public static int getInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }
}
