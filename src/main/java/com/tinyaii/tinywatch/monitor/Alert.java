package com.tinyaii.tinywatch.monitor;

/**
 * 一条预警。
 *
 * <p>预警不是「日志」：它只描述**现在不正常**的地方（TPS 掉、内存满、实体爆、有人疯狂破坏），
 * 网页顶部挂一条横幅。恢复正常后自动消失，不需要人工清。
 */
public final class Alert {

    /** warn = 黄，bad = 红。 */
    public final String level;
    public final String text;

    public Alert(String level, String text) {
        this.level = level;
        this.text = text;
    }
}
