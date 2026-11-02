package com.tinyaii.tinywatch.task;

import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/**
 * 计划任务：定时广播 / 保存 / 清掉落物 / 关机。
 *
 * <p>配置写在 config.yml 的 {@code tasks.jobs} 里，两种触发方式：
 * <ul>
 *   <li>{@code at: "04:00"} —— 每天这个点（服务器本地时间）</li>
 *   <li>{@code every: "30m"} —— 每隔一段时间（支持 s / m / h）</li>
 * </ul>
 *
 * <p><b>为什么不做 cron 表达式</b>：这套需求就「每天几点」和「每隔多久」两种，
 * 上 cron 既难配也容易写错。真需要复杂排程，用别的插件更合适。
 */
public final class TaskRunner {

    /** 一条计划任务。 */
    public static final class Job {
        public String label = "";
        public String action = "";
        public String text = "";
        public int radius;

        /** 每天的分钟数（0~1439），-1 表示不用这种触发方式。 */
        public int atMinute = -1;
        /** 间隔秒数，0 表示不用。 */
        public int everySeconds;

        public long nextRun;

        public String describe() {
            if (atMinute >= 0) {
                return String.format("每天 %02d:%02d", atMinute / 60, atMinute % 60);
            }
            if (everySeconds > 0 && everySeconds % 60 == 0) {
                return "每 " + (everySeconds / 60) + " 分钟";
            }
            return everySeconds > 0 ? ("每 " + everySeconds + " 秒") : "未设置时间";
        }
    }

    private final TinyWatch plugin;
    private final List<Job> jobs = new ArrayList<Job>();
    private BukkitTask task;
    private Settings settings;

    public TaskRunner(TinyWatch plugin) {
        this.plugin = plugin;
    }

    public void start(Settings s) {
        stop();
        this.settings = s;
        jobs.clear();
        if (s == null || !s.tasksEnabled) {
            return;
        }
        for (Map<?, ?> raw : s.taskJobs) {
            Job j = parse(raw);
            if (j != null) {
                jobs.add(j);
            }
        }
        if (jobs.isEmpty()) {
            return;
        }
        // 每秒看一眼。任务本身很轻（比大小），真正干活时才动世界。
        task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 卸载途中取消失败无所谓
            }
            task = null;
        }
        jobs.clear();
    }

    private Job parse(Map<?, ?> raw) {
        if (raw == null) {
            return null;
        }
        Job j = new Job();
        Object doObj = raw.get("do");
        j.action = doObj == null ? "" : String.valueOf(doObj).trim().toLowerCase();
        if (!"broadcast".equals(j.action) && !"save".equals(j.action)
                && !"clear-items".equals(j.action) && !"restart".equals(j.action)) {
            plugin.getLogger().warning("[计划任务] 不认识的动作：" + j.action + "（跳过）");
            return null;
        }
        Object text = raw.get("text");
        j.text = text == null ? "" : String.valueOf(text);
        j.radius = intOf(raw.get("radius"), 0);
        j.label = j.action + (j.text.isEmpty() ? "" : "：" + cut(j.text));

        Object at = raw.get("at");
        if (at != null) {
            j.atMinute = parseClock(String.valueOf(at));
            if (j.atMinute < 0) {
                plugin.getLogger().warning("[计划任务] at 格式应为 HH:mm：" + at);
                return null;
            }
        }
        Object every = raw.get("every");
        if (every != null) {
            j.everySeconds = parseDuration(String.valueOf(every));
            if (j.everySeconds <= 0) {
                plugin.getLogger().warning("[计划任务] every 格式应为 30s / 10m / 2h：" + every);
                return null;
            }
        }
        if (j.atMinute < 0 && j.everySeconds <= 0) {
            plugin.getLogger().warning("[计划任务] 缺少 at 或 every（跳过）");
            return null;
        }
        j.nextRun = j.everySeconds > 0
                ? System.currentTimeMillis() + j.everySeconds * 1000L
                : nextDaily(j.atMinute);
        return j;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Job j : jobs) {
            if (now < j.nextRun) {
                continue;
            }
            // 先把下一次排好，再执行 —— 万一执行抛异常，也不会每秒重试一次
            j.nextRun = j.everySeconds > 0
                    ? now + j.everySeconds * 1000L
                    : nextDaily(j.atMinute);
            try {
                run(j);
            } catch (Throwable t) {
                plugin.getLogger().warning("[计划任务] " + j.label + " 执行失败：" + t);
            }
        }
    }

    private void run(Job j) {
        if ("broadcast".equals(j.action)) {
            if (!j.text.isEmpty()) {
                Bukkit.broadcastMessage(colorize(j.text));
                plugin.getLogger().info("[计划任务] 已广播：" + j.text);
            }
            return;
        }
        if ("save".equals(j.action)) {
            for (World w : Bukkit.getWorlds()) {
                try {
                    w.save();
                } catch (Throwable ignored) {
                    // 某个世界正在卸载就算了
                }
            }
            plugin.getLogger().info("[计划任务] 已保存所有世界");
            return;
        }
        if ("clear-items".equals(j.action)) {
            int removed = 0;
            for (World w : Bukkit.getWorlds()) {
                try {
                    for (Entity e : w.getEntities()) {
                        if (e instanceof Item) {
                            e.remove();
                            removed++;
                        }
                    }
                } catch (Throwable ignored) {
                    // 跳过这个世界的异常
                }
            }
            plugin.getLogger().info("[计划任务] 清理掉落物：" + removed + " 个");
            return;
        }
        if ("restart".equals(j.action)) {
            plugin.getLogger().warning("[计划任务] 按配置关闭服务器");
            Bukkit.shutdown();
        }
    }

    /** 下一次「每天 HH:mm」的时间戳。 */
    private static long nextDaily(int minuteOfDay) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return c.getTimeInMillis();
    }

    private static int parseClock(String s) {
        String t = s.trim();
        int colon = t.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            int h = Integer.parseInt(t.substring(0, colon).trim());
            int m = Integer.parseInt(t.substring(colon + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseDuration(String s) {
        String t = s.trim().toLowerCase();
        if (t.isEmpty()) {
            return 0;
        }
        char unit = t.charAt(t.length() - 1);
        int mul = 1;
        String num = t;
        if (unit == 's' || unit == 'm' || unit == 'h') {
            num = t.substring(0, t.length() - 1).trim();
            mul = unit == 's' ? 1 : (unit == 'm' ? 60 : 3600);
        }
        try {
            return Integer.parseInt(num) * mul;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int intOf(Object o, int def) {
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String colorize(String s) {
        return s.replace('&', '§');
    }

    private static String cut(String s) {
        return s.length() > 20 ? s.substring(0, 20) + "…" : s;
    }

    /** 给网页「系统」页签看：任务列表 + 下次执行时间。 */
    public String json() {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"ok\":true,\"enabled\":").append(settings != null && settings.tasksEnabled);
        b.append(",\"jobs\":[");
        for (int i = 0; i < jobs.size(); i++) {
            Job j = jobs.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"label\":").append(com.tinyaii.tinywatch.util.Json.str(j.label))
                    .append(",\"action\":").append(com.tinyaii.tinywatch.util.Json.str(j.action))
                    .append(",\"when\":").append(com.tinyaii.tinywatch.util.Json.str(j.describe()))
                    .append(",\"next\":").append(j.nextRun).append('}');
        }
        b.append("]}");
        return b.toString();
    }
}
