package com.tinyaii.tinywatch.events;

import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

import java.lang.reflect.Method;

/**
 * 把玩家发言记成一条 CHAT 事件。
 *
 * <p><b>为什么要两个事件源</b>：
 * 本插件用 1.13 的 API 编译，只能写 {@code AsyncPlayerChatEvent}（老事件）。
 * 但 Paper 从 1.19 起主推 {@code AsyncChatEvent}，新版服务端上老事件不一定还会被触发
 * （社区里已经有插件因为只监听老事件而在新 Paper 上"聊天收不到"）。
 * 所以这里两手都挂：老事件直接监听，新事件用反射装一个（类不存在就跳过）。
 * 两条路都走同一个 {@link #submit}，靠 250ms 内的同人同文本去重，不会记两条。
 *
 * <p><b>为什么要 runTask 回主线程</b>：聊天事件是异步的，而事件推送那条链路是照着
 * 「主线程写入」设计的。回一次主线程的开销可以忽略（聊天频率本来就低），换来的是不用
 * 去猜 SSE 那边能不能从任意线程调用。
 */
public final class ChatCapture {

    /** 消息最多记这么长，防止有人刷超长文本把缓冲顶掉。 */
    private static final int MAX_CHAT_LEN = 300;

    /** 两个事件几乎同时触发，这个时间窗内「同一个人 + 同一句话」只算一次。 */
    private static final long DEDUPE_MS = 250;

    private static String lastKey = "";
    private static long lastAt = 0L;

    private ChatCapture() {
    }

    /**
     * 记一条聊天。
     *
     * @param text 已经转成纯文本的内容
     */
    public static void submit(final TinyWatch plugin, final EventStore store,
                              final Player player, String text) {
        if (player == null || text == null) {
            return;
        }
        Settings cfg = plugin.settings();
        if (cfg == null || !cfg.eventsEnabled || !cfg.recordChat) {
            return;
        }
        String msg = text.trim();
        if (msg.isEmpty()) {
            return;
        }
        if (msg.length() > MAX_CHAT_LEN) {
            msg = msg.substring(0, MAX_CHAT_LEN) + "…";
        }

        final String name = player.getName();
        final String uuid = player.getUniqueId().toString();
        Location loc = player.getLocation();
        final String world = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        if (loc.getWorld() != null && cfg.isWorldIgnored(world)) {
            return;
        }
        final int x = loc.getBlockX();
        final int y = loc.getBlockY();
        final int z = loc.getBlockZ();
        final String shown = msg;

        // 关键词命中：只算一次，后面主线程里既标红又（可选）自动禁言
        final String hit = matchKeyword(cfg, msg);
        final int autoMute = cfg.keywordMuteMinutes;

        if (!accept(uuid, msg)) {
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    store.addPos(now, name, uuid, "CHAT", shown, world, x, y, z);
                    if (hit == null) {
                        return;
                    }
                    store.addPos(now, name, uuid, "ALERT",
                            "聊天命中关键词「" + hit + "」：" + shown, world, x, y, z);
                    if (autoMute <= 0) {
                        return;
                    }
                    try {
                        java.util.UUID id = java.util.UUID.fromString(uuid);
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) {
                            plugin.guard().mute(p, autoMute);
                            if (plugin.punishments() != null) {
                                plugin.punishments().add(name, uuid, "MUTE",
                                        now + autoMute * 60_000L,
                                        "聊天命中关键词「" + hit + "」自动禁言");
                            }
                        }
                    } catch (Throwable ignored) {
                        // 自动处罚失败了也不影响记录
                    }
                }
            });
        } catch (Throwable ignored) {
            // 插件正在卸载时调度器会拒绝任务，丢一条日志无所谓
        }
    }

    /** 命中配置里的关键词就返回那个词，否则 null。 */
    private static String matchKeyword(Settings cfg, String msg) {
        if (cfg == null || cfg.alertKeywords.isEmpty() || msg == null || msg.isEmpty()) {
            return null;
        }
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        for (String k : cfg.alertKeywords) {
            if (lower.contains(k)) {
                return k;
            }
        }
        return null;
    }

    /** 同一个人 + 同一句话，250ms 内只放行一次（老事件和新事件会各来一次）。 */
    private static synchronized boolean accept(String uuid, String msg) {
        long now = System.currentTimeMillis();
        String key = uuid + "\u0000" + msg;
        if (key.equals(lastKey) && now - lastAt < DEDUPE_MS) {
            return false;
        }
        lastKey = key;
        lastAt = now;
        return true;
    }

    /**
     * 尝试挂上 Paper 的现代聊天事件。
     *
     * <p>1.13 API 里没有这个类，只能用反射。挂不上（Spigot / 老版本）就静静地算了，
     * 老事件那条路还在。返回 true 表示挂上了。
     */
    public static boolean installModern(final TinyWatch plugin, final EventStore store) {
        final Class<?> eventClass;
        try {
            eventClass = Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
        } catch (Throwable notPaper) {
            return false;
        }
        if (!Event.class.isAssignableFrom(eventClass)) {
            return false;
        }

        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> ev = (Class<? extends Event>) eventClass;

            final Method getPlayer = eventClass.getMethod("getPlayer");
            final Method message = eventClass.getMethod("message");

            EventExecutor executor = new EventExecutor() {
                @Override
                public void execute(Listener listener, Event event) throws EventException {
                    if (!eventClass.isInstance(event)) {
                        return;
                    }
                    try {
                        Player p = (Player) getPlayer.invoke(event);
                        String text = plain(message.invoke(event));
                        submit(plugin, store, p, text);
                    } catch (Throwable ignored) {
                        // 反射失败不能影响聊天本身
                    }
                }
            };
            Bukkit.getPluginManager().registerEvent(
                    ev, new Listener() {
                    }, EventPriority.MONITOR, executor, plugin);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().info("[聊天] 现代聊天事件挂载失败，只用老事件：" + t);
            return false;
        }
    }

    /** Adventure 的 Component 转纯文本；转不了就退回 toString()。 */
    private static String plain(Object component) {
        if (component == null) {
            return null;
        }
        try {
            Class<?> serCls = Class.forName(
                    "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
            Object ser = serCls.getMethod("plainText").invoke(null);
            Class<?> compCls = Class.forName("net.kyori.adventure.text.Component");
            Object out = ser.getClass().getMethod("serialize", compCls).invoke(ser, component);
            return out == null ? null : out.toString();
        } catch (Throwable ignored) {
            return String.valueOf(component);
        }
    }
}
