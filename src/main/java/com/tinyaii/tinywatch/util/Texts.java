package com.tinyaii.tinywatch.util;

import org.bukkit.ChatColor;

/**
 * 文本工具：颜色码 + 占位符。
 *
 * <p>统一用 {@code &} 作为颜色码前缀（比 {@code §} 好编辑）。
 */
public final class Texts {

    private Texts() {
    }

    /** 把 &a 转成 §a。 */
    public static String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    /**
     * 依次替换 {0} {1} ...
     *
     * <p>先替换占位符再上色，避免颜色码里的 {n} 被误替换。
     */
    public static String format(String template, Object... args) {
        if (template == null) {
            return "";
        }
        String out = template;
        for (int i = 0; i < args.length; i++) {
            String value = args[i] == null ? "" : String.valueOf(args[i]);
            out = out.replace("{" + i + "}", value);
        }
        return color(out);
    }

    /** 去掉颜色码（写日志用）。 */
    public static String plain(String text) {
        return text == null ? "" : ChatColor.stripColor(color(text));
    }
}
