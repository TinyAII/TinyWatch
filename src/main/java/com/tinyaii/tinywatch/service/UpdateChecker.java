package com.tinyaii.tinywatch.service;

import com.tinyaii.tinywatch.config.Settings;
import com.tinyaii.tinywatch.util.JsonParser;
import com.tinyaii.tinywatch.util.Texts;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线版本检查（BumpAPI）。
 *
 * <p>设计原则（每一条都是硬要求）：
 * <ul>
 *   <li><b>异步 + 短超时</b>：绝不阻塞主线程，也绝不因为网络慢拖慢启动</li>
 *   <li><b>失败静默</b>：服务器连不上外网是常态，不能刷屏报错</li>
 *   <li><b>只提示，不下载、不替换</b>：Bukkit 运行时 jar 被 JVM 锁着，
 *       而且「偷偷换 jar」对服主来说是不可接受的行为</li>
 *   <li><b>同一版本只提醒一次</b>：玩家不会被反复刷</li>
 * </ul>
 */
public final class UpdateChecker {

    /** 启动后延迟多久做第一次检查（tick）。避开启动瞬间的资源竞争。 */
    private static final long FIRST_DELAY_TICKS = 100L;

    private final Plugin plugin;
    private final Settings config;
    private volatile Result result;
    /** 上次检查时刻（毫秒），0 表示还没查过。 */
    private volatile long lastCheckAt;
    /** 正在检查中，避免网页连点触发多次请求。 */
    private volatile boolean checking;
    /** 上次失败的原因（给网页显示"连不上版本服务"，而不是一片空白）。 */
    private volatile String lastError = "";
    private BukkitTask task;
    /** 已经提醒过该版本的玩家，避免反复弹。 */
    private final Map<UUID, String> notified = new ConcurrentHashMap<UUID, String>();
    private volatile String notifiedConsoleVersion = "";

    public UpdateChecker(Plugin plugin, Settings config) {
        this.plugin = plugin;
        this.config = config;
    }

    // ────────────────────────── 生命周期 ──────────────────────────

    public void start() {
        stop();
        if (!config.isUpdateCheckEnabled()) {
            plugin.getLogger().info("[更新检查] 已关闭");
            return;
        }
        if (config.getUpdateAppKey() == null || config.getUpdateAppKey().trim().isEmpty()) {
            plugin.getLogger().warning("[更新检查] 未配置 app-key，跳过");
            return;
        }

        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin, new Runnable() {
                    @Override
                    public void run() {
                        check();
                    }
                },
                FIRST_DELAY_TICKS,
                Math.max(1L, config.getUpdateIntervalHours()) * 60L * 60L * 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ────────────────────────── 检查 ──────────────────────────

    /** 立即检查一次（异步执行）。 */
    /** 检查一次。失败也会更新 lastCheckAt，网页据此显示"刚查过但连不上"。 */
    public void check() {
        checking = true;
        try {
            check0();
        } finally {
            checking = false;
            lastCheckAt = System.currentTimeMillis();
        }
    }

    private void check0() {
        lastError = "";
        String url = buildUrl();
        if (url == null) {
            return;
        }
        String body;
        try {
            body = httpGet(url);
        } catch (Throwable t) {
            // 静默：离线 / DNS 失败 / 超时都是正常情况
            lastError = "连不上版本服务（" + t.getClass().getSimpleName() + "）";
            plugin.getLogger().info("[更新检查] 无法连接版本服务（" + t.getClass().getSimpleName() + "），已跳过");
            return;
        }
        if (body == null || body.trim().isEmpty()) {
            return;
        }

        final Result parsed;
        try {
            Map<String, Object> json = JsonParser.parseObject(body);
            parsed = new Result(
                    JsonParser.getBoolean(json, "has_update", false),
                    JsonParser.getString(json, "latest_version", ""),
                    JsonParser.getString(json, "release_notes", ""),
                    JsonParser.getBoolean(json, "force_update", false),
                    JsonParser.getString(json, "download_url", ""),
                    JsonParser.getString(json, "publish_time", "")
            );
        } catch (Throwable t) {
            lastError = "版本服务返回的内容看不懂";
            plugin.getLogger().warning("[更新检查] 响应解析失败: " + t.getMessage());
            return;
        }
        this.result = parsed;

        // 回主线程打日志（getLogger 本身线程安全，但保持一致的处理路径）
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                logResult(parsed);
            }
        });
    }

    private void logResult(Result r) {
        if (!r.hasUpdate) {
            plugin.getLogger().info("[更新检查] 已是最新版本 " + currentVersion());
            return;
        }
        if (r.latestVersion.equals(notifiedConsoleVersion)) {
            return;
        }
        notifiedConsoleVersion = r.latestVersion;

        plugin.getLogger().warning("========================================");
        plugin.getLogger().warning(" " + plugin.getName() + " 有新版本可用");
        plugin.getLogger().warning(" 当前: " + currentVersion() + "  最新: " + r.latestVersion
                + (r.forceUpdate ? "  (强制更新)" : ""));
        if (!r.publishTime.isEmpty()) {
            plugin.getLogger().warning(" 发布日期: " + r.publishTime);
        }
        if (!r.notes.isEmpty()) {
            for (String line : r.notes.split("\n")) {
                plugin.getLogger().warning("   " + line);
            }
        }
        if (!r.downloadUrl.isEmpty()) {
            plugin.getLogger().warning(" 下载: " + r.downloadUrl);
        }
        plugin.getLogger().warning(" 提示：本插件不会自动替换 jar，请手动下载后替换并重启");
        plugin.getLogger().warning("========================================");
    }

    private String currentVersion() {
        try {
            String v = plugin.getDescription().getVersion();
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        } catch (Throwable ignored) {
            // 极新核心可能移除该方法，回落到常量
        }
        return "unknown";
    }

    private String buildUrl() {
        String endpoint = config.getUpdateEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return null;
        }
        String base = endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            String appKey = URLEncoder.encode(config.getUpdateAppKey().trim(), "UTF-8");
            String version = URLEncoder.encode(currentVersion(), "UTF-8");
            String channel = URLEncoder.encode(config.getUpdateChannel().trim(), "UTF-8");
            return base + "/api/v1/check?app_key=" + appKey
                    + "&current_version=" + version
                    + "&channel=" + channel;
        } catch (Throwable t) {
            return null;
        }
    }

    private String httpGet(String urlText) throws Exception {
        int timeout = Math.max(1, config.getUpdateTimeoutSeconds()) * 1000;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("User-Agent", plugin.getName() + "/" + currentVersion());
            conn.setRequestProperty("Accept", "application/json");

            int code = conn.getResponseCode();
            if (code != 200) {
                InputStream errStream = conn.getErrorStream();
                String errBody = readAll(errStream);
                plugin.getLogger().info("[更新检查] 服务返回 HTTP " + code
                        + (errBody.isEmpty() ? "" : " " + errBody));
                return null;
            }
            return readAll(conn.getInputStream());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String readAll(InputStream stream) {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, Charset.forName("UTF-8")));
            char[] buf = new char[2048];
            int read;
            while ((read = reader.read(buf)) > 0) {
                sb.append(buf, 0, read);
                if (sb.length() > 64 * 1024) {
                    break; // 防御：响应不该这么大
                }
            }
            reader.close();
        } catch (Exception ignored) {
            // 读失败就当空
        }
        return sb.toString();
    }

    // ────────────────────────── 玩家提示 ──────────────────────────

    /** 玩家登录时调用（主线程）。只有具备提醒权限且该版本没提醒过才弹。 */
    public void notifyPlayer(Player player) {
        Result r = result;
        if (r == null || !r.hasUpdate) {
            return;
        }
        if (!player.hasPermission(config.getUpdateNotifyPermission())) {
            return;
        }
        UUID id = player.getUniqueId();
        if (r.latestVersion.equals(notified.get(id))) {
            return;
        }
        notified.put(id, r.latestVersion);

        player.sendMessage(Texts.color("&6[" + plugin.getName() + "] &e有新版本可用：&f"
                + currentVersion() + " &7→ &a" + r.latestVersion));
        if (!r.notes.isEmpty()) {
            String firstLine = r.notes.split("\n")[0];
            player.sendMessage(Texts.color("&7  " + firstLine));
        }
        if (!r.downloadUrl.isEmpty()) {
            player.sendMessage(Texts.color("&7  下载：&f" + r.downloadUrl));
        }
        player.sendMessage(Texts.color("&8  本插件不会自动替换 jar，请手动替换后重启"));
    }

    public Result result() {
        return result;
    }

    /** 这是我们自己现在的版本号。 */
    public String current() {
        return currentVersion();
    }

    /** 上次检查时刻（毫秒），0 = 从没查过。 */
    public long lastCheckAt() {
        return lastCheckAt;
    }

    /** 是否正在检查。 */
    public boolean isChecking() {
        return checking;
    }

    /** 上次失败的原因，空串表示上次没出错。 */
    public String lastError() {
        return lastError;
    }

    /** 更新检查功能本身开着没有。 */
    public boolean enabled() {
        return config != null && config.isUpdateCheckEnabled();
    }

    /** 一次检查的结果。 */
    public static final class Result {
        public final boolean hasUpdate;
        public final String latestVersion;
        public final String notes;
        public final boolean forceUpdate;
        public final String downloadUrl;
        public final String publishTime;

        Result(boolean hasUpdate, String latestVersion, String notes,
               boolean forceUpdate, String downloadUrl, String publishTime) {
            this.hasUpdate = hasUpdate;
            this.latestVersion = latestVersion == null ? "" : latestVersion;
            this.notes = notes == null ? "" : notes;
            this.forceUpdate = forceUpdate;
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
            this.publishTime = publishTime == null ? "" : publishTime;
        }
    }
}
