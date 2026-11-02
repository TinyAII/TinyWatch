package com.tinyaii.tinywatch.events;

import com.tinyaii.tinywatch.TinyWatch;
import org.bukkit.Bukkit;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把「游戏里聊天框会看到的东西」也收进来。
 *
 * <p><b>为什么不能只用聊天事件</b>：
 * {@code AsyncChatEvent} 只覆盖玩家说话。聊天框里还有大量东西不是玩家说的 ——
 * 服务器公告、成就播报、死亡播报、控制台 {@code /say}、插件广播……
 * 这些在 Bukkit 里没有统一的「发到聊天框」事件，只能从**服务端日志**里捞。
 *
 * <p><b>为什么是反射挂 log4j2</b>：
 * 本插件按 1.13 的 API 编译，拿不到 log4j 的类型；而且一旦编译期依赖上 log4j，
 * 在没有 log4j2 的服务端（很老的或换了日志实现的）上会直接 NoClassDefFoundError。
 * 所以运行时用 {@link Proxy} 造一个 {@code Appender} 塞进 root logger，
 * 挂不上就静静地算了，功能降级成「只有玩家聊天」。
 *
 * <p><b>怎么不刷屏</b>：
 * 插件自己的 INFO 日志（比如雷达每 20 秒三条扫描进度）虽然也会进 root logger，
 * 但那不是聊天框里的东西。所以插件来源只收 WARN 及以上；
 * 服务器来源（日志名不是插件名）才按 INFO 收。另外还有每秒条数上限兜底。
 */
public final class WorldFeed {

    /** 单条最多这么长，超了截断（异常栈那种多行文本会被压成一行）。 */
    private static final int MAX_LEN = 300;

    /** 每秒最多收多少条，防止某个插件疯狂打日志把环形缓冲冲干净。 */
    private static final int MAX_PER_SECOND = 12;

    /** 纯控制台内部日志，聊天框里根本看不到，直接丢。 */
    private static final String[] INTERNAL = {
            "UUID of player ", "logged in with entity id", "lost connection:",
            "Starting minecraft server", "Loading properties", "Default game type",
            "Generating keypair", "Starting Minecraft server on", "Preparing level",
            "Preparing start region", "Preparing spawn area", "Time elapsed:",
            "Saving chunks", "Saving worlds", "Stopping server", "Stopping the server",
            "This server is running", "Done (", "Reloading ResourceManager",
            "Loaded ", "Enabling ", "Disabling ", "Loading server plugin",
            "Using ", "Paper ", "Server Ping", "***", "Running delayed init tasks",
            "Starting background profiler", "placeholder hook(s) registered",
            "Unloading ", "Flushing", "Waiting for", "Awaiting "
    };

    private final TinyWatch plugin;
    private final EventStore store;
    private final Map<String, Boolean> pluginNames = new ConcurrentHashMap<String, Boolean>();

    private long windowStart;
    private int windowCount;
    private long onlineCheckedAt;
    private boolean onlineCached;

    private WorldFeed(TinyWatch plugin, EventStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /**
     * 挂上控制台捕获。
     *
     * @return true 表示挂上了；false 表示这个服务端没有可用的 log4j2（功能降级）
     */
    public static boolean install(TinyWatch plugin, EventStore store) {
        final WorldFeed feed = new WorldFeed(plugin, store);
        try {
            Class<?> logManager = Class.forName("org.apache.logging.log4j.LogManager");
            Object root = logManager.getMethod("getRootLogger").invoke(null);
            if (root == null) {
                return false;
            }
            Class<?> coreLogger = Class.forName("org.apache.logging.log4j.core.Logger");
            if (!coreLogger.isInstance(root)) {
                return false;
            }
            Class<?> appenderCls = Class.forName("org.apache.logging.log4j.core.Appender");
            Object appender = Proxy.newProxyInstance(
                    WorldFeed.class.getClassLoader(),
                    new Class<?>[]{appenderCls},
                    feed.new Bridge());
            coreLogger.getMethod("addAppender", appenderCls).invoke(root, appender);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().info("[世界消息] 挂不上控制台捕获（" + t + "），只保留玩家聊天。");
            return false;
        }
    }

    // ────────────────────────── 日志事件处理 ──────────────────────────

    private void onLogEvent(Object event) {
        String logger = str(call(event, "getLoggerName"));
        String level = str(call(event, "getLevel"));
        String raw = message(event, logger);

        if (raw == null) {
            return;
        }
        String text = oneLine(raw);
        if (text.isEmpty()) {
            return;
        }
        if (logger != null && logger.startsWith("TinyWatch")) {
            return;   // 自己的日志，不进
        }
        if (isLowLevel(level)) {
            return;   // DEBUG/TRACE 不要
        }
        if (isPlayerChat(text)) {
            return;   // 玩家聊天有专门的事件（带 uuid 和坐标），这里不重复记
        }
        if (text.startsWith("[")) {
            String bare = text;
            int close = bare.indexOf(']');
            if (close > 0 && close < 40) {
                // "[Not Secure] <Steve> hi" 这种，剥掉前缀再判断一次
                String inner = bare.substring(1, close).trim();
                if ("Not Secure".equalsIgnoreCase(inner) || "Secure".equalsIgnoreCase(inner)) {
                    String rest = bare.substring(close + 1).trim();
                    if (isPlayerChat(rest)) {
                        return;
                    }
                }
            }
        }
        for (int i = 0; i < INTERNAL.length; i++) {
            if (text.startsWith(INTERNAL[i])) {
                return;
            }
        }
        if (text.endsWith("joined the game") || text.endsWith("left the game")
                || text.contains("加入了游戏") || text.contains("离开了游戏")) {
            return;   // 进出服有专门的 JOIN/QUIT 事件
        }

        // 没人在线时聊天框根本不存在，开机那一堆自检日志没必要进缓冲
        if (!anyoneOnline()) {
            return;
        }

        if (text.length() > MAX_LEN) {
            text = text.substring(0, MAX_LEN) + "…";
        }

        boolean fromPlugin = isPluginLogger(logger);
        if (fromPlugin) {
            // 插件自己的 INFO 日志基本都是控制台噪声（扫描进度、定时任务之类），
            // 只有 WARN / ERROR 才值得放进「插件提示」里
            if (!isWarnOrWorse(level)) {
                return;
            }
            push("PLUGIN", logger, text);
        } else {
            push("WORLD", "服务器", text);
        }
    }

    private void push(String type, String who, String text) {
        if (!allow()) {
            return;
        }
        store.addNoPos(System.currentTimeMillis(), who, "", type, text);
    }

    /** 每秒条数上限。 */
    private synchronized boolean allow() {
        long now = System.currentTimeMillis();
        if (now - windowStart > 1000L) {
            windowStart = now;
            windowCount = 0;
        }
        windowCount++;
        return windowCount <= MAX_PER_SECOND;
    }

    /**
     * 有没有人在线。
     *
     * <p>没人在线的时候，聊天框是空的 —— 这时候把开机自检、插件加载那堆日志收进去纯属噪声。
     * 500ms 缓存一下，避免每条日志都去拿一次在线列表。
     */
    private boolean anyoneOnline() {
        long now = System.currentTimeMillis();
        if (now - onlineCheckedAt < 500L) {
            return onlineCached;
        }
        boolean any;
        try {
            any = !Bukkit.getOnlinePlayers().isEmpty();
        } catch (Throwable ignored) {
            any = false;
        }
        onlineCached = any;
        onlineCheckedAt = now;
        return any;
    }

    /** 日志名是不是某个已加载插件的名字。按名字缓存，避免每条日志都去问插件管理器。 */
    private boolean isPluginLogger(String logger) {
        if (logger == null || logger.isEmpty() || logger.indexOf('.') >= 0) {
            return false;   // 带点的是类名（服务端内部），不是插件
        }
        Boolean cached = pluginNames.get(logger);
        if (cached != null) {
            return cached;
        }
        boolean isPlugin;
        try {
            isPlugin = Bukkit.getPluginManager().getPlugin(logger) != null;
        } catch (Throwable ignored) {
            isPlugin = false;
        }
        pluginNames.put(logger, isPlugin);
        return isPlugin;
    }

    // ────────────────────────── 文本处理 ──────────────────────────

    private static boolean isPlayerChat(String text) {
        // "<Steve> hi" —— 名字里不会出现 '>'，长度也不会太长
        if (text.isEmpty() || text.charAt(0) != '<') {
            return false;
        }
        int close = text.indexOf('>');
        return close > 1 && close <= 18 && text.length() > close + 1;
    }

    private static boolean isLowLevel(String level) {
        if (level == null) {
            return false;
        }
        String l = level.toUpperCase(Locale.ROOT);
        return l.contains("DEBUG") || l.contains("TRACE") || l.contains("FINER")
                || l.contains("FINEST") || l.contains("CONFIG");
    }

    private static boolean isWarnOrWorse(String level) {
        if (level == null) {
            return false;
        }
        String l = level.toUpperCase(Locale.ROOT);
        return l.contains("WARN") || l.contains("ERROR") || l.contains("FATAL")
                || l.contains("SEVERE");
    }

    /** 多行文本压成一行，顺便去掉颜色符号。 */
    private static String oneLine(String s) {
        String t = s.replace('\r', ' ').replace('\n', ' ');
        t = t.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        t = t.replaceAll("\\s{2,}", " ").trim();
        return t;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取日志正文：优先 getFormattedMessage()，失败退回 getFormat()。 */
    private static String message(Object event, String logger) {
        Object msg = call(event, "getMessage");
        if (msg == null) {
            return null;
        }
        Object formatted = call(msg, "getFormattedMessage");
        if (formatted != null) {
            return formatted.toString();
        }
        Object format = call(msg, "getFormat");
        return format == null ? null : format.toString();
    }

    /**
     * 让 log4j 认为我们是一个正经 Appender 的动态代理。
     *
     * <p>只需要真的实现 {@code append}，其余方法按返回类型给个无害的默认值 ——
     * log4j 内部会调用 getName()/isStarted()/start() 之类，返回 null 会 NPE。
     */
    private final class Bridge implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("append".equals(name)) {
                if (args != null && args.length == 1) {
                    try {
                        onLogEvent(args[0]);
                    } catch (Throwable ignored) {
                        // 捕获失败绝不能影响服务端打日志
                    }
                }
                return null;
            }
            if ("getName".equals(name)) {
                return "TinyWatchWorldFeed";
            }
            if ("isStarted".equals(name)) {
                return Boolean.TRUE;
            }
            if ("start".equals(name) || "stop".equals(name)) {
                return null;
            }
            if ("toString".equals(name)) {
                return "TinyWatchWorldFeed";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class) {
                return Boolean.FALSE;
            }
            if (r == int.class) {
                return Integer.valueOf(0);
            }
            if (r == long.class) {
                return Long.valueOf(0L);
            }
            return null;
        }
    }
}
