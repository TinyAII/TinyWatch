package com.tinyaii.tinywatch.util;

import java.util.Locale;

/**
 * JSON 输出工具。
 *
 * <p>只做「生成」，不做解析 —— 本插件的所有 JSON 都是往外发的，
 * 没有一处需要读 JSON。所以不需要引入任何 JSON 库。
 *
 * <p>拼接方式故意做成手写 StringBuilder：对象结构都是固定的，
 * 用反射或通用 builder 反而更慢、更难读。
 */
public final class Json {

    private Json() {
    }

    /** 转义字符串内容（不含两侧引号）。 */
    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
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
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (ch < 0x20) {
                        // 控制字符必须转义，否则浏览器解析 JSON 会失败
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    /** 字符串字面量（带引号）；null 输出 JSON 的 null。 */
    public static String str(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }

    /** 数字。整数不带小数点，其余保留两位；NaN/Infinity 输出 null。 */
    public static String num(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.format(Locale.ROOT, "%.2f", d);
    }

    public static String bool(boolean b) {
        return b ? "true" : "false";
    }
}
