package com.tinyaii.tinywatch.monitor;

import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 数据采集器。
 *
 * <p><b>线程模型（这是整个插件最关键的设计）</b>
 *
 * <pre>
 *   主线程（服务端 tick）                      Web 线程（HTTP）
 *   ─────────────────────                     ────────────────
 *   读 Player / World  ──拷贝──▶  Snapshot  ──▶  只读快照
 *                （不可变对象，一次发布）
 * </pre>
 *
 * <p>Bukkit 的绝大多数 API 只能在主线程调用。Web 请求跑在别的线程上，
 * 直接去碰 Player 会抛 {@code IllegalStateException}（Paper 会直接拦住），
 * 或者读到写了一半的数据。
 *
 * <p>所以这里只做一件事：主线程把所有要展示的字段**抄一份**，
 * 打包成一个不可变对象 {@link Snapshot} 一次性发布到 volatile 字段。
 * Web 线程永远只读这份拷贝，永远不接触 Bukkit 对象。
 */
public final class Monitor {

    /** Paper 专有的平均 tick 耗时 API。1.13 上没有，取到就是 null。 */
    private static final Method AVERAGE_TICK_TIME;

    static {
        Method m = null;
        try {
            m = Bukkit.class.getMethod("getAverageTickTime");
        } catch (Throwable ignored) {
            // Spigot / 老版本没有这个方法，mspt 显示为「—」
        }
        AVERAGE_TICK_TIME = m;
    }

    private final TinyWatch plugin;
    private Settings settings;

    /** 最新快照。volatile：写入后立刻对其他线程可见。 */
    private volatile Snapshot latest;

    /** 曲线数据，每项 = {time, tps, mspt, usedMemMb, online, entities}。 */
    private final Deque<double[]> history = new ArrayDeque<double[]>();
    private final Object historyLock = new Object();

    private BukkitTask task;

    /** 上一次采样时刻（纳秒），用于算这一段的实际 TPS。 */
    private long lastSampleNanos;

    private int sampleCounter;
    private int entities;
    private int chunks;

    /** 每个世界的 {区块数, 实体数}，和上面的总计数一起节流更新。 */
    private final Map<String, int[]> worldStats = new HashMap<String, int[]>();

    /** 本次在线时长的起点。没记录时（例如插件中途 reload）补一个。 */
    private final Map<UUID, Long> joinTimes = new HashMap<UUID, Long>();

    /**
     * 玩家轨迹：每人一个环形队列，最多 300 个点。
     * 采样间隔 1 秒的话就是最近 5 分钟的走位，只占几十 KB。
     */
    private static final int TRAIL_MAX = 300;
    private final Map<UUID, Deque<TrailPoint>> trails = new HashMap<UUID, Deque<TrailPoint>>();

    /** TPS 已经连续几次低于阈值（避免抖动一下就报警）。 */
    private int lowTpsStreak;

    // JVM 相关，只取一次即可（这些 bean 对象是常驻的）
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> gcBeans =
            ManagementFactory.getGarbageCollectorMXBeans();

    public Monitor(TinyWatch plugin) {
        this.plugin = plugin;
    }

    public void setSettings(Settings s) {
        this.settings = s;
    }

    public void start() {
        stop();
        lastSampleNanos = 0L;
        sampleCounter = 0;
        int period = Math.max(1, settings.sampleIntervalTicks);
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                sample();
            }
        }, period, period);
    }

    public void stop() {
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 插件正在卸载时 cancel 可能抛，忽略即可
            }
            task = null;
        }
        synchronized (historyLock) {
            history.clear();
        }
        latest = null;
    }

    /** 玩家进服时记一笔，用于算「本次在线时长」。必须在主线程调用。 */
    public void onJoin(Player p) {
        joinTimes.put(p.getUniqueId(), System.currentTimeMillis());
    }

    public void onQuit(Player p) {
        joinTimes.remove(p.getUniqueId());
        synchronized (trails) {
            trails.remove(p.getUniqueId());
        }
    }

    /** 某个玩家的轨迹（旧 → 新）。没有就返回空表。 */
    public List<TrailPoint> trailOf(UUID id) {
        synchronized (trails) {
            Deque<TrailPoint> q = trails.get(id);
            if (q == null || q.isEmpty()) {
                return new ArrayList<TrailPoint>();
            }
            return new ArrayList<TrailPoint>(q);
        }
    }

    private void pushTrail(UUID id, long nowMillis, Location loc) {
        synchronized (trails) {
            Deque<TrailPoint> q = trails.get(id);
            if (q == null) {
                q = new ArrayDeque<TrailPoint>();
                trails.put(id, q);
            }
            q.addLast(new TrailPoint(nowMillis,
                    loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                    round(loc.getX(), 2), round(loc.getY(), 2), round(loc.getZ(), 2)));
            while (q.size() > TRAIL_MAX) {
                q.removeFirst();
            }
        }
    }

    public Snapshot getLatest() {
        return latest;
    }

    /** 曲线数据副本（按时间从旧到新）。 */
    public List<double[]> getHistory() {
        synchronized (historyLock) {
            return new ArrayList<double[]>(history);
        }
    }

    // ────────────────────────── 采集 ──────────────────────────

    private void sample() {
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();

        double tps = 20.0;
        if (lastSampleNanos > 0) {
            double elapsedSec = (nowNanos - lastSampleNanos) / 1e9;
            if (elapsedSec > 0.05) {
                // 定时任务每 N tick 跑一次。实际隔了多久，就等于这段时间真实跑了多少 tick。
                // 卡服时任务被延后 → elapsedSec 变大 → tps 变小，正好反映真实情况。
                tps = settings.sampleIntervalTicks / elapsedSec;
                if (tps > 20.0) {
                    tps = 20.0;
                }
            }
        }
        lastSampleNanos = nowNanos;

        double mspt = readMspt();

        // 实体数 / 区块数遍历开销不小，隔几次才统计一次
        sampleCounter++;
        int throttle = Math.max(1, settings.slowStatEverySamples);
        if (sampleCounter % throttle == 0 || sampleCounter == 1) {
            int e = 0;
            int c = 0;
            for (World w : Bukkit.getWorlds()) {
                try {
                    int wc = w.getLoadedChunks().length;
                    int we = w.getEntities().size();
                    worldStats.put(w.getName(), new int[]{wc, we});
                    c += wc;
                    e += we;
                } catch (Throwable ignored) {
                    // 世界正在卸载时会抛，跳过
                }
            }
            entities = e;
            chunks = c;
        }

        // 用 MXBean 而不是 Runtime.getRuntime()：
        // Runtime 的 totalMemory 会随 JVM 扩缩而变，「已用」看起来忽高忽低；
        // MXBean 的 heap.getUsed() 就是真实的堆占用，和 jstat / jcmd 看到的数一致。
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long usedMem = toMb(heap.getUsed());
        long maxMem = toMb(heap.getMax());
        if (maxMem <= 0) {
            maxMem = toMb(heap.getCommitted());
        }

        List<PlayerView> players = collectPlayers(nowMillis);

        List<Alert> alerts = buildAlerts(tps, usedMem, maxMem, entities, nowMillis);

        Snapshot snap = new Snapshot(nowMillis, round(tps, 2), mspt,
                players.size(), Bukkit.getMaxPlayers(),
                usedMem, maxMem, entities, chunks,
                Bukkit.getName(), shortVersion(), cleanMotd(),
                Runtime.getRuntime().availableProcessors(),
                alerts, players, collectWorlds(), collectJvm(heap));

        latest = snap;
        pushHistory(snap);
    }

    /**
     * 算预警。
     *
     * <p>全部基于已经采到的数字，不额外遍历世界 —— 预警自己不能成为负担。
     * 恢复正常的条件也是「下一次采样不再满足」，所以不需要人工清除。
     */
    private List<Alert> buildAlerts(double tps, long usedMem, long maxMem, int entities, long nowMillis) {
        List<Alert> list = new ArrayList<Alert>();
        Settings s = settings;
        if (s == null || !s.alertsEnabled) {
            return list;
        }

        if (tps < s.alertTpsBelow) {
            lowTpsStreak++;
        } else {
            lowTpsStreak = 0;
        }
        if (lowTpsStreak >= 3) {
            list.add(new Alert(lowTpsStreak >= 10 ? "bad" : "warn",
                    "TPS " + round(tps, 1) + "，低于 " + s.alertTpsBelow
                            + "（已连续 " + lowTpsStreak + " 次采样）"));
        }

        if (maxMem > 0) {
            int pct = (int) (usedMem * 100L / maxMem);
            if (pct >= s.alertMemPercent) {
                list.add(new Alert(pct >= 95 ? "bad" : "warn",
                        "内存占用 " + pct + "%（" + usedMem + " / " + maxMem + " MB）"));
            }
        }

        if (entities > s.alertEntities) {
            list.add(new Alert("warn", "实体数 " + entities + "，超过阈值 "
                    + s.alertEntities + "，可能有掉落物堆积"));
        }

        // 破坏速率每次采样都扫一遍环形缓冲有点浪费，每 5 次算一次就够
        if (sampleCounter % 5 == 0) {
            try {
                Map<String, Integer> breaks = plugin.events().countSince("BREAK", nowMillis - 60_000L);
                for (Map.Entry<String, Integer> en : breaks.entrySet()) {
                    Integer n = en.getValue();
                    if (n != null && n >= s.alertBreakPerMinute) {
                        list.add(new Alert("bad", "玩家 " + en.getKey()
                                + " 一分钟内破坏 " + n + " 个方块"));
                    }
                }
            } catch (Throwable ignored) {
                // 事件库正在重建就跳过这一轮
            }
        }
        return list;
    }

    private List<PlayerView> collectPlayers(long nowMillis) {
        List<PlayerView> list = new ArrayList<PlayerView>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerView v;
            try {
                Location loc = p.getLocation();
                pushTrail(p.getUniqueId(), nowMillis, loc);

                Long joined = joinTimes.get(p.getUniqueId());
                if (joined == null) {
                    // 插件 reload 后再进来的老玩家不会补 join 事件，从此刻算起
                    joined = nowMillis;
                    joinTimes.put(p.getUniqueId(), joined);
                }

                String ip = null;
                if (settings.showIp && p.getAddress() != null
                        && p.getAddress().getAddress() != null) {
                    ip = p.getAddress().getAddress().getHostAddress();
                }

                GameMode mode = p.getGameMode();
                long firstPlayed = 0L;
                try {
                    firstPlayed = p.getFirstPlayed();
                } catch (Throwable ignored) {
                    // 极少数分支没有这个数据
                }

                v = new PlayerView(
                        p.getUniqueId().toString(),
                        p.getName(),
                        loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                        round(loc.getX(), 2),
                        round(loc.getY(), 2),
                        round(loc.getZ(), 2),
                        loc.getYaw(),
                        round(p.getHealth(), 1),
                        round(safeMaxHealth(p), 1),
                        p.getFoodLevel(),
                        p.getLevel(),
                        mode == null ? "?" : mode.name(),
                        com.tinyaii.tinywatch.util.Pings.of(p),
                        Math.max(0, (nowMillis - joined) / 1000L),
                        firstPlayed,
                        p.getFireTicks() > 0,
                        p.isSneaking(),
                        p.isFlying(),
                        p.isInvulnerable(),
                        p.isSprinting(),
                        ip);
            } catch (Throwable t) {
                // 玩家正好在这一 tick 退出，读字段会抛。跳过这一个，不要影响整次采样
                continue;
            }
            list.add(v);
        }
        return list;
    }

    private List<WorldView> collectWorlds() {
        List<WorldView> out = new ArrayList<WorldView>();
        for (World w : Bukkit.getWorlds()) {
            if (w == null) {
                continue;
            }
            int[] st = worldStats.get(w.getName());
            out.add(new WorldView(
                    w.getName(),
                    w.getEnvironment() == null ? "NORMAL" : w.getEnvironment().name(),
                    w.getPlayers().size(),
                    st == null ? 0 : st[0],
                    st == null ? 0 : st[1]));
        }
        return out;
    }

    private JvmView collectJvm(MemoryUsage heap) {
        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean g : gcBeans) {
            long c = g.getCollectionCount();
            long t = g.getCollectionTime();
            if (c > 0) {
                gcCount += c;
            }
            if (t > 0) {
                gcTime += t;
            }
        }
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        return new JvmView(
                runtimeBean.getUptime(), gcCount, gcTime,
                toMb(heap.getUsed()), toMb(heap.getCommitted()), toMb(heap.getMax()),
                toMb(nonHeap.getUsed()),
                threadBean.getThreadCount());
    }

    private static long toMb(long bytes) {
        return bytes <= 0 ? 0 : bytes / (1024L * 1024L);
    }

    @SuppressWarnings("deprecation")
    private double safeMaxHealth(Player p) {
        try {
            double m = p.getMaxHealth();
            return m > 0 ? m : 20.0;
        } catch (Throwable t) {
            return 20.0;
        }
    }

    private double readMspt() {
        if (AVERAGE_TICK_TIME == null) {
            return -1;
        }
        try {
            Object v = AVERAGE_TICK_TIME.invoke(null);
            if (v instanceof Number) {
                return round(((Number) v).doubleValue(), 2);
            }
        } catch (Throwable ignored) {
            // 反射失败就当作拿不到
        }
        return -1;
    }

    private void pushHistory(Snapshot s) {
        double[] row = new double[]{s.time, s.tps, s.mspt, s.usedMemMb, s.online, s.entities};
        synchronized (historyLock) {
            history.addLast(row);
            while (history.size() > settings.historySize) {
                history.removeFirst();
            }
        }
    }

    private String shortVersion() {
        String v = Bukkit.getBukkitVersion();
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    private String cleanMotd() {
        String m = "Minecraft Server";
        try {
            m = Bukkit.getMotd();
            if (m == null || m.trim().isEmpty()) {
                return "Minecraft Server";
            }
        } catch (Throwable ignored) {
            // 某些分支把 getMotd 挪走了，忽略
        }
        m = ChatColor.stripColor(m);
        int nl = m.indexOf('\n');
        if (nl >= 0) {
            m = m.substring(0, nl);
        }
        m = m.trim();
        return m.isEmpty() ? "Minecraft Server" : m;
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
