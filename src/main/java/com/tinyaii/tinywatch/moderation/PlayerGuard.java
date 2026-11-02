package com.tinyaii.tinywatch.moderation;

import com.tinyaii.tinywatch.TinyWatch;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 网页上那几个「持续生效」的操作状态：冻结、禁言、记录坐标。
 *
 * <p>这几个和「治疗一次」不一样 —— 它们要<strong>一直生效</strong>，
 * 所以状态得存在内存里，还要有东西持续执行（冻结每 5 tick 检查一次位置）。
 *
 * <p><b>为什么都用 UUID 而不是 Player 引用</b>：
 * Player 对象在玩家退出后就不能用了，长期持有还会拖住内存。
 * UUID 是稳定标识，用的时候再 {@code Bukkit.getPlayer(uuid)} 拿当前对象。
 *
 * <p><b>状态只存在内存里</b>：重启就清空。这是个监控面板，不是封禁系统 ——
 * 真要长期封禁请用 /ban（那个是写进 banned-players.json 的）。
 */
public final class PlayerGuard implements Listener {

    private final TinyWatch plugin;

    /** 被冻结的玩家 → 冻结时站的位置。 */
    private final Map<UUID, Location> frozenAt = new HashMap<UUID, Location>();
    /** 网页记下来的坐标，用于「拉回」。 */
    private final Map<UUID, Location> saved = new HashMap<UUID, Location>();
    /** 禁言到什么时候（毫秒时间戳）。 */
    private final Map<UUID, Long> mutedUntil = new HashMap<UUID, Long>();

    private BukkitTask ticker;

    public PlayerGuard(TinyWatch plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // 每 5 tick（0.25 秒）检查一次冻结的玩家有没有挪位置。
        // 冻结一般就一两个人，这个开销可以忽略。
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                enforceFreeze();
            }
        }, 5L, 5L);
    }

    public void stop() {
        if (ticker != null) {
            try {
                ticker.cancel();
            } catch (Throwable ignored) {
                // 卸载途中取消任务失败无所谓
            }
            ticker = null;
        }
        frozenAt.clear();
        saved.clear();
        mutedUntil.clear();
    }

    // ────────────────────────── 冻结 ──────────────────────────

    /** @return 冻结后的状态：true = 现在被冻住了 */
    public boolean toggleFreeze(Player p) {
        UUID id = p.getUniqueId();
        if (frozenAt.containsKey(id)) {
            frozenAt.remove(id);
            return false;
        }
        frozenAt.put(id, p.getLocation().clone());
        return true;
    }

    public boolean isFrozen(Player p) {
        return frozenAt.containsKey(p.getUniqueId());
    }

    private void enforceFreeze() {
        if (frozenAt.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Location>> it = frozenAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Location> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Location at = e.getValue();
            if (p == null || !p.isOnline() || at == null || at.getWorld() == null) {
                it.remove();
                continue;
            }
            Location now = p.getLocation();
            // 跨世界就直接拉回来；同世界比距离的平方（省一次开方）
            if (now.getWorld() != at.getWorld() || now.distanceSquared(at) > 1.0) {
                p.teleport(at);
                p.setFallDistance(0f);
            }
        }
    }

    // ────────────────────────── 禁言 ──────────────────────────

    /** @param minutes 0 或负数表示解除禁言 */
    public void mute(Player p, int minutes) {
        if (minutes <= 0) {
            mutedUntil.remove(p.getUniqueId());
            return;
        }
        mutedUntil.put(p.getUniqueId(), System.currentTimeMillis() + minutes * 60_000L);
    }

    /**
     * 按「截止时间」禁言，给持久化记录用（重启后还有效的那种）。
     *
     * @param untilMs 截止时间戳，0 表示永久（这里用 100 年近似）
     */
    public void muteUntil(Player p, long untilMs) {
        long until = untilMs <= 0 ? System.currentTimeMillis() + 100L * 365 * 24 * 3600 * 1000
                : untilMs;
        if (until <= System.currentTimeMillis()) {
            mutedUntil.remove(p.getUniqueId());
            return;
        }
        mutedUntil.put(p.getUniqueId(), until);
    }

    public boolean isMuted(Player p) {
        return muteLeftMs(p) > 0;
    }

    public long muteLeftMs(Player p) {
        Long until = mutedUntil.get(p.getUniqueId());
        if (until == null) {
            return 0L;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            mutedUntil.remove(p.getUniqueId());
            return 0L;
        }
        return left;
    }

    /**
     * 拦住被禁言玩家的发言。
     *
     * <p>用 LOWEST：要比任何插件都早做决定。这里取消之后，
     * 我们自己的聊天记录（MONITOR + ignoreCancelled）也就不会记下这条 —— 正是想要的。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        long left = muteLeftMs(p);
        if (left <= 0) {
            return;
        }
        e.setCancelled(true);
        final long mins = left / 60_000L + 1;
        // 聊天事件在异步线程，发消息还是丢回主线程稳一点
        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    p.sendMessage("§c你已被管理员禁言，还剩约 " + mins + " 分钟。");
                }
            });
        } catch (Throwable ignored) {
            // 关服途中调度失败，忽略
        }
    }

    // ────────────────────────── 坐标记录 ──────────────────────────

    public Location saveLocation(Player p) {
        Location loc = p.getLocation().clone();
        saved.put(p.getUniqueId(), loc);
        return loc;
    }

    public Location savedLocation(Player p) {
        return saved.get(p.getUniqueId());
    }

    // ────────────────────────── 清理 ──────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        // 人走了就别冻着了，不然他重进会被瞬间拉回原地
        frozenAt.remove(id);
        saved.remove(id);
        // 禁言保留：换客户端重进不该能绕开禁言
    }
}
