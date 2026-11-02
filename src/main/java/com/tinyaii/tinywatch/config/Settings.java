package com.tinyaii.tinywatch.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配置快照。
 *
 * <p>一次性把 config.yml 读成普通字段，之后代码只读字段、不再碰 FileConfiguration。
 * 好处：每次读配置不会反复解析 YAML，也不会因为 somebody 改了 config 而出现半新半旧的值。
 */
public final class Settings {

    /** 版本检查服务的默认地址与 app_key（公开标识，不是密钥）。 */
    private static final String DEFAULT_UPDATE_ENDPOINT = "https://bump.tinyaii.cn";
    private static final String DEFAULT_APP_KEY = "app_4192c1abdbfb0677b688f11f";

    // ---- web ----
    public boolean webEnabled = true;
    public String webHost = "127.0.0.1";
    public int webPort = 8123;
    /** 明文密码（只在用户手填 config、或旧版本遗留时才有值，启动后会被迁移成哈希）。 */
    public String webPassword = "";
    /** PBKDF2 哈希。磁盘上只存这个，反推不回明文。 */
    public String webPasswordHash = "";
    /** 是否信任反代传来的 X-Forwarded-For（默认不信任，防止伪造 IP 绕过登录限流）。 */
    public boolean trustProxy = false;
    public int sessionHours = 12;
    public boolean showIp = false;

    /** 背包图标：honghemc（素材站真图）/ svg（自绘）/ cdn（自定义图床）/ none（纯色块）。 */
    public String itemIcons = "honghemc";
    /** 真图模式下图片的前缀地址。 */
    public String itemIconBase = "https://honghemc.cn/thumbs/images/";
    /** 兼容旧配置：自定义 cdn 模式的模板。 */
    public String itemIconCdn = "https://mc-heads.net/item/{item}";

    // ---- monitor ----
    public int sampleIntervalTicks = 20;
    public int historySize = 300;
    /** 每采样几次才统计一次实体数/区块数（这两个要遍历全世界，比较贵）。 */
    public int slowStatEverySamples = 5;

    // ---- tasks（计划任务）----
    public boolean tasksEnabled = true;
    /** 原样的 jobs 列表，交给 TaskRunner 解析（配置格式的细节不影响这里的快照语义）。 */
    public final List<Map<?, ?>> taskJobs = new ArrayList<Map<?, ?>>();

    // ---- alerts（预警）----
    public boolean alertsEnabled = true;
    /** TPS 低于这个值（连续 3 次采样）就报警。 */
    public double alertTpsBelow = 15.0;
    /** 内存占用百分比超过这个值就报警。 */
    public int alertMemPercent = 85;
    /** 全服实体数超过这个值就报警（通常意味着掉落物堆积）。 */
    public int alertEntities = 4000;
    /** 单个玩家一分钟内破坏方块数超过这个值就报警。 */
    public int alertBreakPerMinute = 100;
    /** 聊天里出现这些词就标红（广告、拉人、脏话之类）。 */
    public final List<String> alertKeywords = new ArrayList<String>();
    /** 命中关键词自动禁言多少分钟（0 = 只标红不处理）。 */
    public int keywordMuteMinutes = 0;

    // ---- events ----
    public boolean eventsEnabled = true;
    public int eventBufferSize = 5000;
    public boolean recordBlockBreak = true;
    public boolean recordBlockPlace = true;
    public boolean recordContainer = true;
    public boolean recordContainerClick = false;
    public boolean recordCommand = true;
    public boolean recordChat = true;
    /** 抓服务端/插件的聊天框消息（控制台日志 + 广播）。 */
    public boolean captureConsole = true;
    public boolean recordDeath = true;
    public boolean recordJoinQuit = true;

    public final Set<String> ignoreCommands = new HashSet<String>();
    public final List<String> ignoreWorlds = new ArrayList<String>();

    // ---- web.actions（网页上能做的管理操作）----
    /** 总开关。关掉之后所有网页操作都不可用，面板退回纯只读。 */
    public boolean actionsEnabled = true;

    public boolean actionMessage = true;
    public boolean actionTeleport = true;
    public boolean actionKick = true;
    public boolean actionVanish = true;
    public boolean actionHeal = true;
    public boolean actionGamemode = true;
    public boolean actionClearItems = true;
    /** 封禁默认关闭：这是唯一不可逆的操作（要手动 /pardon 才能解）。 */
    public boolean actionBan = false;

    // ---- 新增操作按组开关（免得 config.yml 里堆二十多个布尔）----
    /** 状态类：药水效果 / 经验 / 飞行 / 隐身。 */
    public boolean actState = true;
    /** 物品类：清空背包 / 给物品 / 修耐久 / 拿走某格 / 清周围掉落物。 */
    public boolean actItems = true;
    /** 位置类：传送到玩家 / 送回出生点 / 冻结 / 记录与拉回。 */
    public boolean actPosition = true;
    /** 沟通类：标题 / 动作栏 / 音效。 */
    public boolean actNotify = true;
    /** 处罚类：禁言 / 白名单 / OP。 */
    public boolean actModerate = true;
    /** 经济类：查余额 / 增减（需要 Vault）。 */
    public boolean actEconomy = true;

    // ---- 世界操作 ----
    /** 世界修改总开关（时间/天气/难度/规则/出生点）。 */
    public boolean worldActionsEnabled = true;
    /** 清理类（掉落物 / 生物）—— 删了收不回来，单独开关。 */
    public boolean worldClearEnabled = true;
    /** 世界边界。 */
    public boolean worldBorderEnabled = true;
    /** 执行白名单命令，默认关闭。 */
    public boolean actionCommand = false;
    /** 白名单为空时，「执行命令」功能视为不可用。 */
    public final List<String> actionCommandWhitelist = new ArrayList<String>();

    // ---- update-check ----
    private boolean updateCheckEnabled = true;
    private String updateEndpoint = DEFAULT_UPDATE_ENDPOINT;
    private String updateAppKey = DEFAULT_APP_KEY;
    private String updateChannel = "stable";
    private int updateTimeoutSeconds = 3;
    private int updateIntervalHours = 6;
    private String updateNotifyPermission = "tinywatch.admin";

    public Settings(FileConfiguration c) {
        reload(c);
    }

    public void reload(FileConfiguration c) {
        webEnabled = c.getBoolean("web.enabled", true);
        webHost = c.getString("web.host", "127.0.0.1");
        webPort = c.getInt("web.port", 8123);
        webPassword = c.getString("web.password", "");
        webPasswordHash = c.getString("web.password-hash", "");
        trustProxy = c.getBoolean("web.trust-proxy", false);
        sessionHours = Math.max(1, c.getInt("web.session-hours", 12));
        showIp = c.getBoolean("web.show-ip", false);

        String icons = c.getString("web.item-icons", "honghemc");
        icons = icons == null ? "honghemc" : icons.trim().toLowerCase(Locale.ROOT);
        if (!icons.equals("cdn") && !icons.equals("none") && !icons.equals("svg")) {
            icons = "honghemc";
        }
        itemIcons = icons;
        String base = c.getString("web.item-icon-base", "https://honghemc.cn/thumbs/images/");
        itemIconBase = (base == null || base.trim().isEmpty())
                ? "https://honghemc.cn/thumbs/images/" : base.trim();
        String cdn = c.getString("web.item-icon-cdn", "https://mc-heads.net/item/{item}");
        itemIconCdn = (cdn == null || cdn.trim().isEmpty())
                ? "https://mc-heads.net/item/{item}" : cdn.trim();

        sampleIntervalTicks = Math.max(1, c.getInt("monitor.sample-interval-ticks", 20));
        historySize = Math.max(20, c.getInt("monitor.history-size", 300));
        slowStatEverySamples = Math.max(1, c.getInt("monitor.slow-stat-every-samples", 5));

        tasksEnabled = c.getBoolean("tasks.enabled", true);
        taskJobs.clear();
        try {
            for (Map<?, ?> m : c.getMapList("tasks.jobs")) {
                if (m != null) {
                    taskJobs.add(m);
                }
            }
        } catch (Throwable ignored) {
            // 配置写坏了就当没有任务，别让插件起不来
        }

        alertsEnabled = c.getBoolean("alerts.enabled", true);
        alertTpsBelow = c.getDouble("alerts.tps-below", 15.0D);
        alertMemPercent = Math.max(1, Math.min(100, c.getInt("alerts.memory-percent", 85)));
        alertEntities = Math.max(100, c.getInt("alerts.entities-above", 4000));
        alertBreakPerMinute = Math.max(10, c.getInt("alerts.break-per-minute", 100));
        alertKeywords.clear();
        for (String k : c.getStringList("alerts.keywords")) {
            if (k != null && !k.trim().isEmpty()) {
                alertKeywords.add(k.trim().toLowerCase(Locale.ROOT));
            }
        }
        keywordMuteMinutes = Math.max(0, c.getInt("alerts.keyword-mute-minutes", 0));

        eventsEnabled = c.getBoolean("events.enabled", true);
        eventBufferSize = Math.max(100, c.getInt("events.buffer-size", 5000));
        recordBlockBreak = c.getBoolean("events.record.block-break", true);
        recordBlockPlace = c.getBoolean("events.record.block-place", true);
        recordContainer = c.getBoolean("events.record.container", true);
        recordContainerClick = c.getBoolean("events.record.container-click", false);
        recordCommand = c.getBoolean("events.record.command", true);
        recordChat = c.getBoolean("events.record.chat", true);
        captureConsole = c.getBoolean("events.capture-console", true);
        recordDeath = c.getBoolean("events.record.death", true);
        recordJoinQuit = c.getBoolean("events.record.join-quit", true);

        ignoreCommands.clear();
        for (String s : c.getStringList("events.ignore-commands")) {
            if (s != null && !s.trim().isEmpty()) {
                ignoreCommands.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }

        ignoreWorlds.clear();
        for (String s : c.getStringList("events.ignore-worlds")) {
            if (s != null && !s.trim().isEmpty()) {
                ignoreWorlds.add(s.trim());
            }
        }

        actionsEnabled = c.getBoolean("web.actions.enabled", true);
        actionMessage = c.getBoolean("web.actions.message", true);
        actionTeleport = c.getBoolean("web.actions.teleport", true);
        actionKick = c.getBoolean("web.actions.kick", true);
        actionVanish = c.getBoolean("web.actions.vanish", true);
        actionHeal = c.getBoolean("web.actions.heal", true);
        actionGamemode = c.getBoolean("web.actions.gamemode", true);
        actionClearItems = c.getBoolean("web.actions.clear-items", true);
        actionBan = c.getBoolean("web.actions.ban", false);
        actionCommand = c.getBoolean("web.actions.command", false);
        actState = c.getBoolean("web.actions.state", true);
        actItems = c.getBoolean("web.actions.items", true);
        actPosition = c.getBoolean("web.actions.position", true);
        actNotify = c.getBoolean("web.actions.notify", true);
        actModerate = c.getBoolean("web.actions.moderate", true);
        actEconomy = c.getBoolean("web.actions.economy", true);
        worldActionsEnabled = c.getBoolean("web.world.enabled", true);
        worldClearEnabled = c.getBoolean("web.world.clear", true);
        worldBorderEnabled = c.getBoolean("web.world.border", true);
        actionCommandWhitelist.clear();
        for (String s : c.getStringList("web.actions.command-whitelist")) {
            if (s != null && !s.trim().isEmpty()) {
                actionCommandWhitelist.add(s.trim());
            }
        }

        updateCheckEnabled = c.getBoolean("update-check.enabled", true);
        updateEndpoint = c.getString("update-check.endpoint", DEFAULT_UPDATE_ENDPOINT);
        String key = c.getString("update-check.app-key", "");
        updateAppKey = (key == null || key.trim().isEmpty()) ? DEFAULT_APP_KEY : key.trim();
        updateChannel = c.getString("update-check.channel", "stable");
        updateTimeoutSeconds = Math.max(1, c.getInt("update-check.timeout-seconds", 3));
        updateIntervalHours = Math.max(1, c.getInt("update-check.interval-hours", 6));
        updateNotifyPermission = c.getString("update-check.notify-permission", "tinywatch.admin");
    }

    /** 这个世界是否被排除在日志之外。 */
    public boolean isWorldIgnored(String world) {
        if (world == null) {
            return false;
        }
        for (String s : ignoreWorlds) {
            if (s.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    // ---- update-check getters（UpdateChecker 用）----

    public boolean isUpdateCheckEnabled() { return updateCheckEnabled; }

    public String getUpdateEndpoint() { return updateEndpoint; }

    public String getUpdateAppKey() { return updateAppKey; }

    public String getUpdateChannel() { return updateChannel; }

    public int getUpdateTimeoutSeconds() { return updateTimeoutSeconds; }

    public int getUpdateIntervalHours() { return updateIntervalHours; }

    public String getUpdateNotifyPermission() { return updateNotifyPermission; }
}
