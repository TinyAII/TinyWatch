package com.tinyaii.tinywatch;

import com.tinyaii.tinywatch.command.WatchCommand;
import com.tinyaii.tinywatch.config.Settings;
import com.tinyaii.tinywatch.events.Action;
import com.tinyaii.tinywatch.events.ActionListener;
import com.tinyaii.tinywatch.events.EventStore;
import com.tinyaii.tinywatch.listener.UpdateNotifyListener;
import com.tinyaii.tinywatch.monitor.Monitor;
import com.tinyaii.tinywatch.service.UpdateChecker;
import com.tinyaii.tinywatch.web.Api;
import com.tinyaii.tinywatch.web.WebServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;

/**
 * TinyWatch —— 网页监控面板。
 *
 * <p>看三样东西：服务器现在什么状态、每个玩家现在在哪、刚才谁做了什么。
 * 只有读，不写游戏世界。
 */
public final class TinyWatch extends JavaPlugin {

    private Settings settings;
    private Monitor monitor;
    private EventStore events;
    private com.tinyaii.tinywatch.moderation.PlayerGuard guard;
    private com.tinyaii.tinywatch.task.TaskRunner tasks;
    private com.tinyaii.tinywatch.moderation.Punishments punishments;
    private WebServer web;
    private UpdateChecker updateChecker;

    /** 物品名 -> 图标相对路径，gzip 压缩后约 22 KB。启动时读一次，之后只读不解析。 */
    private volatile byte[] iconMap;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // 老版本的 config.yml 不会自动长出新项，这里按内置模板补一遍（先备份、校验通过才写）
        String migrated = com.tinyaii.tinywatch.config.ConfigMigrator.migrate(this);
        if (migrated != null) {
            reloadConfig();
            getLogger().info("[配置升级] 已把 config.yml 升到新版结构（原文件备份为 config.yml.bak）。" + migrated);
        }

        settings = new Settings(getConfig());

        // 密码留空就自动生成一个：默认有密码，比默认没密码安全得多
        ensurePassword();

        monitor = new Monitor(this);
        monitor.setSettings(settings);
        monitor.start();

        events = new EventStore(settings.eventBufferSize);
        wireEvents();

        // 冻结 / 禁言 / 记录坐标这些「要一直生效」的状态
        punishments = new com.tinyaii.tinywatch.moderation.Punishments(this);

        guard = new com.tinyaii.tinywatch.moderation.PlayerGuard(this);
        guard.start();

        // 计划任务（定时广播 / 保存 / 清掉落物 / 关机）
        tasks = new com.tinyaii.tinywatch.task.TaskRunner(this);
        tasks.start(settings);

        iconMap = loadResourceBytes("web/iconmap.json.gz");

        getServer().getPluginManager().registerEvents(new ActionListener(this, events), this);

        // 新版 Paper 上老聊天事件可能不触发，再挂一个现代聊天事件（没有就跳过）
        boolean modernChat = com.tinyaii.tinywatch.events.ChatCapture.installModern(this, events);
        if (modernChat) {
            getLogger().info("[聊天] 已挂上 Paper 现代聊天事件（AsyncChatEvent），并同时兼容老的 AsyncPlayerChatEvent。");
        }

        // 聊天框里的系统消息（公告 / 广播 / 控制台输出）只能从服务端日志里捞
        if (settings.eventsEnabled && settings.captureConsole) {
            boolean consoleOk = com.tinyaii.tinywatch.events.WorldFeed.install(this, events);
            if (consoleOk) {
                getLogger().info("[世界消息] 已接管控制台输出，聊天框里的系统消息会一起显示在网页「聊天」页签。");
            }
        }

        web = new WebServer(this);
        boolean started = web.start(settings);
        if (!started) {
            getLogger().warning("网页面板没有启动，插件其余部分仍可正常工作。");
        }

        WatchCommand cmd = new WatchCommand(this);
        if (getCommand("watch") != null) {
            getCommand("watch").setExecutor(cmd);
            getCommand("watch").setTabCompleter(cmd);
        }

        updateChecker = new UpdateChecker(this, settings);
        updateChecker.start();
        getServer().getPluginManager().registerEvents(
                new UpdateNotifyListener(this, updateChecker), this);

        printBanner();
    }

    @Override
    public void onDisable() {
        if (tasks != null) {
            tasks.stop();
        }
        if (guard != null) {
            guard.stop();
        }
        if (updateChecker != null) {
            updateChecker.stop();
        }
        if (web != null) {
            web.stop();
        }
        if (monitor != null) {
            monitor.stop();
        }
    }

    private void printBanner() {
        getLogger().info("================ TinyWatch v" + getDescription().getVersion() + " ================");
        getLogger().info("网页面板 : " + (web != null && web.isRunning()
                ? "http://" + web.boundHost() + ":" + web.boundPort() + "/" : "未启动"));
        if (settings.eventsEnabled) {
            getLogger().info("操作日志 : 已开启，保留最近 " + settings.eventBufferSize
                    + " 条，监听 " + enabledEventKinds() + " 类事件");
        } else {
            getLogger().info("操作日志 : 已关闭");
        }
        getLogger().info("支持版本 : 1.13 ~ 26.x（纯 Bukkit API，不用 NMS）");
        // 别再说「只读」了：v1.1 起网页能做 22 项操作，包括踢人、封禁、清实体。
        // 写清楚状态和边界，免得上管理员误以为这插件碰不了人。
        getLogger().info("网页操作 : " + (settings.actionsEnabled
                ? "已开启（按组开关，全部写审计日志）" : "已关闭"));
        getLogger().info("=====================================================");
    }

    /** 数一下配置里开了几类事件 —— 排查「日志怎么一条都没有」时先看这个数。 */
    private int enabledEventKinds() {
        int n = 0;
        if (settings.recordBlockBreak) n++;
        if (settings.recordBlockPlace) n++;
        if (settings.recordContainer) n++;
        if (settings.recordContainerClick) n++;
        if (settings.recordCommand) n++;
        if (settings.recordChat) n++;
        if (settings.recordDeath) n++;
        if (settings.recordJoinQuit) n++;
        return n;
    }

    // ────────────────────────── 装配 ──────────────────────────

    /**
     * 把「新事件」接到「实时推送」上。
     *
     * <p>每次重建 EventStore 都要重新接一次，所以单独抽出来，不要在构造里写死。
     */
    private void wireEvents() {
        events.setSink(new EventStore.Sink() {
            @Override
            public void onAction(Action a) {
                WebServer w = web;
                // 没有客户端连着的时候直接跳过 —— 不然每条事件都要拼一次 JSON 再丢掉。
                // 空闲服务器上这一点点开销看不出来，但服里一刷怪就是几百条事件。
                if (w != null && w.isRunning() && w.sseHub().size() > 0) {
                    w.sseHub().broadcastJson(Api.singleAction(a));
                }
            }
        });
    }

    /**
     * 保证有密码。
     *
     * <p>宁可每次启动都提醒一次，也不要「默认没有密码」——
     * 一个能看全服玩家坐标的面板如果空着密码，扫描器几分钟就能翻出来。
     */
    private void ensurePassword() {
        String plain = getConfig().getString("web.password", "");
        plain = plain == null ? "" : plain.trim();
        String hash = getConfig().getString("web.password-hash", "");
        hash = hash == null ? "" : hash.trim();

        // ① config 里还有明文（老版本遗留，或者用户手填）→ 转成哈希，把明文抹掉
        if (!plain.isEmpty()) {
            backupConfigFile();
            getConfig().set("web.password", "");
            getConfig().set("web.password-hash",
                    com.tinyaii.tinywatch.web.Password.hash(plain));
            saveConfig();
            settings.reload(getConfig());
            getLogger().info("登录密码已改为哈希存储：config.yml 里的明文已清空，"
                    + "原文件备份为 config.yml.bak。以后改密码用 /watch 新密码。");
            return;
        }

        // ② 已经有哈希 → 直接用
        if (!hash.isEmpty()) {
            settings.webPasswordHash = hash;
            return;
        }

        // ③ 第一次部署（两者都空）→ 随机生成，只存哈希，明文只在控制台出现这一次
        String generated = com.tinyaii.tinywatch.web.Password.random(16);
        getConfig().set("web.password-hash",
                com.tinyaii.tinywatch.web.Password.hash(generated));
        saveConfig();
        settings.reload(getConfig());

        getLogger().warning("=====================================================");
        getLogger().warning(" 首次启动，已自动生成一个登录密码（下面只显示这一次）：");
        getLogger().warning("");
        getLogger().warning("     " + generated);
        getLogger().warning("");
        getLogger().warning(" 服务器上只存了它的哈希，配置文件里看不到明文。");
        getLogger().warning(" 忘了就用 /watch 新密码 重新生成一个。");
        getLogger().warning("=====================================================");
    }

    /** 改配置之前先把原文件留一份，出问题能翻回去看。 */
    private void backupConfigFile() {
        try {
            java.io.File src = new java.io.File(getDataFolder(), "config.yml");
            if (!src.isFile()) {
                return;
            }
            java.nio.file.Files.copy(src.toPath(),
                    new java.io.File(getDataFolder(), "config.yml.bak").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable ignored) {
            // 备份失败不影响迁移
        }
    }

    private String randomPassword() {
        // 去掉容易看错的 0/O、1/l/I，手抄到浏览器时不容易出错
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 重新读配置并重建受影响的组件。不重启服务器即可生效。 */
    public void reloadAll() {
        reloadConfig();
        Settings fresh = new Settings(getConfig());
        // 注意：密码现在存在 web.password-hash 里，web.password 正常情况下是空的。
        // 两个都空才是「真的没设密码」，否则每次 reload 都会重新生成一个。
        boolean noPlain = fresh.webPassword == null || fresh.webPassword.trim().isEmpty();
        boolean noHash = fresh.webPasswordHash == null || fresh.webPasswordHash.trim().isEmpty();
        if (noPlain && noHash) {
            // 有人把密码删了，重新生成一个
            settings = fresh;
            ensurePassword();
            fresh = settings;
        }
        settings = fresh;

        monitor.setSettings(settings);
        monitor.stop();
        monitor.start();

        if (events.capacity() != settings.eventBufferSize) {
            events = new EventStore(settings.eventBufferSize);
            wireEvents();
        }

        if (web != null) {
            web.stop();
            web.start(settings);
        }
    }

    public Settings settings() {
        return settings;
    }

    public Monitor monitor() {
        return monitor;
    }

    public EventStore events() {
        return events;
    }

    public com.tinyaii.tinywatch.moderation.PlayerGuard guard() {
        return guard;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    public com.tinyaii.tinywatch.task.TaskRunner tasks() {
        return tasks;
    }

    public com.tinyaii.tinywatch.moderation.Punishments punishments() {
        return punishments;
    }

    public WebServer web() {
        return web;
    }

    /** 图标映射表（已 gzip 压缩的 JSON 字节）。没有资源时返回 null。 */
    public byte[] iconMap() {
        return iconMap;
    }

    /**
     * 从 jar 里读一个资源成字节数组。
     *
     * <p>不解析、不转字符串 —— 原样存着，HTTP 发的时候加个
     * {@code Content-Encoding: gzip} 就行，服务器这边零解析开销。
     */
    private byte[] loadResourceBytes(String path) {
        java.io.InputStream in = null;
        try {
            in = getResource(path);
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(32768);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            getLogger().warning("读取资源 " + path + " 失败：" + t.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }
}
