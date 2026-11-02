package com.tinyaii.tinywatch.web;

import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * 网页上的世界级操作：时间、天气、难度、游戏规则、出生点、边界、清理。
 *
 * <p><b>为什么单独一个类</b>：{@link ActionHandler} 那一套全是「对某个玩家做什么」，
 * 参数是 uuid；世界操作的目标是世界，参数是 world 名，混在一起会让两边的校验都变乱。
 *
 * <p><b>为什么每个都绕回主线程</b>：和玩家操作同一个原因，Web 请求跑在 HTTP 线程池里。
 *
 * <p><b>撤销</b>：时间、天气、难度、规则、出生点、边界都记旧值，
 * 5 分钟内可以一键改回去（清实体这种删掉东西的没法撤销，所以不登记）。
 */
public final class WorldHandler {

    /** 前端/网页能改的游戏规则，以及它们的中文名和类型。 */
    /**
     * 能改的游戏规则。
     *
     * <p>每一项 = {规范 key, 中文名, 类型, 候选名…}。
     *
     * <p><b>为什么要一串候选名</b>：规则在版本之间改过名，而且不是简单的大小写问题 ——
     * 26.x 的 {@code advance_time} 在 1.13 叫 {@code doDaylightCycle}，
     * {@code natural_health_regeneration} 以前叫 {@code naturalRegeneration}。
     * 读/写时按顺序试，哪个被接受用哪个，前端只认规范 key，不用关心版本差异。
     */
    private static final String[][] RULES = {
            {"daylight", "时间流动（白天黑夜自动走）", "bool", "advance_time", "doDaylightCycle"},
            {"weather", "天气变化（自动下雨放晴）", "bool", "advance_weather", "doWeatherCycle"},
            {"mobspawn", "自然刷怪", "bool", "spawn_mobs", "doMobSpawning", "do_mob_spawning"},
            {"monster", "刷怪物", "bool", "spawn_monsters", "doMobSpawning"},
            {"keepinv", "死亡不掉落", "bool", "keep_inventory", "keepInventory"},
            {"mobgrief", "生物破坏方块（苦力怕/末影人）", "bool", "mob_griefing", "mobGriefing"},
            {"regen", "自然回血", "bool", "natural_health_regeneration", "naturalRegeneration"},
            {"respawn", "死亡立即重生", "bool", "immediate_respawn", "doImmediateRespawn"},
            {"advmsg", "进度播报", "bool", "show_advancement_messages", "announceAdvancements"},
            {"deathmsg", "死亡消息", "bool", "show_death_messages", "showDeathMessages"},
            {"pvp", "玩家互相伤害（PVP）", "bool", "pvp"},
            {"tnt", "TNT 爆炸", "bool", "tnt_explodes"},
            {"falldamage", "摔落伤害", "bool", "fall_damage"},
            {"tickspeed", "随机刻速度（作物生长）", "int", "random_tick_speed", "randomTickSpeed"}
    };

    private final TinyWatch plugin;
    private final ActionHandler actions;

    public WorldHandler(TinyWatch plugin, ActionHandler actions) {
        this.plugin = plugin;
        this.actions = actions;
    }

    private Settings cfg() {
        return plugin.settings();
    }

    // ────────────────────────── 读取 ──────────────────────────

    /** 三个世界的状态，给网页渲染用。必须在主线程调用（会读世界对象）。 */
    public String listWorlds() {
        StringBuilder b = new StringBuilder(1024);
        b.append("{\"ok\":true,\"rules\":[");
        for (int i = 0; i < RULES.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"key\":").append(com.tinyaii.tinywatch.util.Json.str(RULES[i][0]))
                    .append(",\"label\":").append(com.tinyaii.tinywatch.util.Json.str(RULES[i][1]))
                    .append(",\"type\":").append(com.tinyaii.tinywatch.util.Json.str(RULES[i][2]))
                    .append('}');
        }
        b.append("],\"worlds\":[");
        List<World> worlds = Bukkit.getWorlds();
        for (int i = 0; i < worlds.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            appendWorld(b, worlds.get(i));
        }
        b.append("]}");
        return b.toString();
    }

    private void appendWorld(StringBuilder b, World w) {
        long time = w.getTime();
        long full = w.getFullTime();
        // 时间换成人话：tick 0 = 早上 6:00
        double hour = ((time / 1000.0) + 6.0) % 24.0;
        int hh = (int) hour;
        int mm = (int) ((hour - hh) * 60);

        Location spawn = w.getSpawnLocation();
        WorldBorder bd = w.getWorldBorder();

        int players = w.getPlayers().size();
        b.append("{\"name\":").append(com.tinyaii.tinywatch.util.Json.str(w.getName()))
                .append(",\"env\":").append(com.tinyaii.tinywatch.util.Json.str(w.getEnvironment().name()))
                .append(",\"envLabel\":").append(com.tinyaii.tinywatch.util.Json.str(envLabel(w)))
                .append(",\"time\":").append(time)
                .append(",\"fullTime\":").append(full)
                .append(",\"clock\":").append(com.tinyaii.tinywatch.util.Json.str(
                        (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm))
                .append(",\"storm\":").append(w.hasStorm())
                .append(",\"thundering\":").append(w.isThundering())
                .append(",\"difficulty\":").append(com.tinyaii.tinywatch.util.Json.str(
                        w.getDifficulty() == null ? "?" : w.getDifficulty().name()))
                .append(",\"players\":").append(players)
                .append(",\"chunks\":").append(safeChunks(w))
                .append(",\"entities\":").append(safeEntities(w))
                .append(",\"spawn\":{\"x\":").append(spawn.getBlockX())
                .append(",\"y\":").append(spawn.getBlockY())
                .append(",\"z\":").append(spawn.getBlockZ()).append('}')
                .append(",\"border\":{\"centerX\":").append(round(bd.getCenter().getX()))
                .append(",\"centerZ\":").append(round(bd.getCenter().getZ()))
                .append(",\"size\":").append(round(bd.getSize()))
                .append(",\"damage\":").append(round(bd.getDamageAmount())).append('}')
                .append(",\"ruleValues\":{");
        for (int i = 0; i < RULES.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(com.tinyaii.tinywatch.util.Json.str(RULES[i][0])).append(':')
                    .append(com.tinyaii.tinywatch.util.Json.str(ruleValue(w, RULES[i])));
        }
        b.append("}}");
    }

    /**
     * 读规则值：按候选名顺序试，第一个有值的就用。
     *
     * <p>传错名字不会报错，只会返回 null / 空串，所以必须自己兜 ——
     * 这就是为什么每条规则要带一串候选名。
     */
    private static String ruleValue(World w, String[] def) {
        for (int i = 3; i < def.length; i++) {
            try {
                String v = w.getGameRuleValue(def[i]);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 换下一个候选名
            }
        }
        return "";
    }

    /** 按候选名写规则，返回真正被接受的那个名字（都失败返回 null）。 */
    private static String applyRule(World w, String[] def, String value) {
        for (int i = 3; i < def.length; i++) {
            try {
                if (w.setGameRuleValue(def[i], value)) {
                    return def[i];
                }
            } catch (Throwable ignored) {
                // 换下一个候选名
            }
        }
        return null;
    }

    /** 按规范 key（或任一候选名）找规则定义，找不到返回 null。 */
    private static String[] findRule(String key) {
        for (String[] def : RULES) {
            for (int i = 0; i < def.length; i++) {
                if (def[i].equalsIgnoreCase(key)) {
                    return def;
                }
            }
        }
        return null;
    }

    // ────────────────────────── 执行 ──────────────────────────

    /**
     * 执行一个世界操作。
     *
     * @param worldName 世界名
     * @param action    动作
     * @param a1        参数一
     * @param a2        参数二
     */
    public String execute(String worldName, final String action, final String a1, final String a2) {
        Settings s = cfg();
        if (s == null) {
            return Api.error("配置还没加载完");
        }
        if (!s.worldActionsEnabled) {
            return Api.error("世界修改已在配置里关闭（web.world.enabled: false）");
        }
        final World w = worldName == null ? null : Bukkit.getWorld(worldName.trim());
        if (w == null) {
            return Api.error("找不到世界：" + worldName);
        }
        if (action == null || action.trim().isEmpty()) {
            return Api.error("缺少 action");
        }
        final String a = action.trim().toLowerCase(Locale.ROOT);
        final String p1 = a1 == null ? "" : a1.trim();
        final String p2 = a2 == null ? "" : a2.trim();

        boolean clear = "clear-items".equals(a) || "clear-entities".equals(a);
        if (clear && !s.worldClearEnabled) {
            return Api.error("「清理」类操作已在配置里关闭（web.world.clear: false）");
        }
        if ("border".equals(a) && !s.worldBorderEnabled) {
            return Api.error("「世界边界」已在配置里关闭（web.world.border: false）");
        }

        return actions.onMain(new Callable<String>() {
            @Override
            public String call() {
                return run(w, a, p1, p2);
            }
        });
    }

    private String run(final World w, final String action, final String a1, final String a2) {
        String wn = w.getName();

        // ── 时间 ──
        if ("time".equals(action)) {
            long before = w.getTime();
            long target;
            String key = a1.toLowerCase(Locale.ROOT);
            if ("day".equals(key)) {
                target = 1000L;
            } else if ("noon".equals(key)) {
                target = 6000L;
            } else if ("night".equals(key)) {
                target = 13000L;
            } else if ("midnight".equals(key)) {
                target = 18000L;
            } else {
                try {
                    target = Long.parseLong(key);
                } catch (NumberFormatException e) {
                    return Api.error("时间只能是 day / noon / night / midnight 或一个 tick 数");
                }
                if (target < 0 || target > 24000L) {
                    return Api.error("tick 要在 0 ~ 24000 之间");
                }
            }
            w.setTime(target);
            audit(wn, "时间设为 " + clockOf(target) + "（tick " + target + "）");
            String u = actions.remember("世界 " + wn, "改时间", new Callable<String>() {
                @Override
                public String call() {
                    World ww = Bukkit.getWorld(wn);
                    if (ww == null) return Api.error("世界已卸载");
                    ww.setTime(before);
                    return Api.okWith("已撤销：时间回到 " + clockOf(before));
                }
            });
            return Api.okWith(wn + " 的时间现在是 " + clockOf(target), u);
        }

        // ── 天气 ──
        if ("weather".equals(action)) {
            final boolean beforeStorm = w.hasStorm();
            final boolean beforeThunder = w.isThundering();
            final int beforeWeather = w.getWeatherDuration();
            final int beforeThunder2 = w.getThunderDuration();

            String key = a1.toLowerCase(Locale.ROOT);
            int minutes = 10;
            if (!a2.isEmpty()) {
                try {
                    minutes = Integer.parseInt(a2);
                } catch (NumberFormatException e) {
                    return Api.error("时长要填分钟数");
                }
            }
            if (minutes < 1 || minutes > 1440) {
                return Api.error("时长要在 1 ~ 1440 分钟之间");
            }
            int ticks = minutes * 1200;

            if ("clear".equals(key)) {
                w.setStorm(false);
                w.setThundering(false);
                w.setWeatherDuration(ticks);
            } else if ("rain".equals(key)) {
                w.setStorm(true);
                w.setThundering(false);
                w.setWeatherDuration(ticks);
            } else if ("thunder".equals(key)) {
                w.setStorm(true);
                w.setThundering(true);
                w.setThunderDuration(ticks);
                w.setWeatherDuration(ticks);
            } else {
                return Api.error("天气只能是 clear / rain / thunder");
            }
            String label = "clear".equals(key) ? "放晴" : ("rain".equals(key) ? "下雨" : "雷雨");
            audit(wn, "天气改为 " + label + "（" + minutes + " 分钟）");
            String u = actions.remember("世界 " + wn, "改天气", new Callable<String>() {
                @Override
                public String call() {
                    World ww = Bukkit.getWorld(wn);
                    if (ww == null) return Api.error("世界已卸载");
                    ww.setStorm(beforeStorm);
                    ww.setThundering(beforeThunder);
                    ww.setWeatherDuration(beforeWeather);
                    ww.setThunderDuration(beforeThunder2);
                    return Api.okWith("已撤销：天气恢复原样");
                }
            });
            return Api.okWith(wn + " 已经" + label, u);
        }

        // ── 难度 ──
        if ("difficulty".equals(action)) {
            final Difficulty before = w.getDifficulty();
            Difficulty d;
            try {
                d = Difficulty.valueOf(a1.toUpperCase(Locale.ROOT));
            } catch (Throwable t) {
                return Api.error("难度只能是 PEACEFUL / EASY / NORMAL / HARD");
            }
            w.setDifficulty(d);
            audit(wn, "难度改为 " + diffLabel(d));
            String u = actions.remember("世界 " + wn, "改难度", new Callable<String>() {
                @Override
                public String call() {
                    World ww = Bukkit.getWorld(wn);
                    if (ww == null) return Api.error("世界已卸载");
                    ww.setDifficulty(before);
                    return Api.okWith("已撤销：难度恢复为 " + diffLabel(before));
                }
            });
            return Api.okWith(wn + " 的难度现在是 " + diffLabel(d), u);
        }

        // ── 游戏规则 ──
        if ("rule".equals(action)) {
            final String[] def = findRule(a1);
            if (def == null) {
                return Api.error("不支持这条规则：" + a1);
            }
            final String name = def[0];
            final String before = ruleValue(w, def);
            String val = a2.trim();
            if (val.isEmpty()) {
                return Api.error("缺少值");
            }
            if ("true".equalsIgnoreCase(val) || "false".equalsIgnoreCase(val)) {
                val = val.toLowerCase(Locale.ROOT);
            }
            if ("int".equals(def[2])) {
                try {
                    int n = Integer.parseInt(val);
                    if (n < 0 || n > 4096) {
                        return Api.error("这个数值要在 0 ~ 4096 之间");
                    }
                    val = String.valueOf(n);
                } catch (NumberFormatException e) {
                    return Api.error("这一项要填数字");
                }
            }
            final String v = val;
            final String applied = applyRule(w, def, v);
            if (applied == null) {
                return Api.error("这个服务端不接受规则「" + name + "」（可能版本改了名字）");
            }
            audit(wn, "规则 " + name + " = " + v + "（原 " + before + "，实际写入 " + applied + "）");
            String u = actions.remember("世界 " + wn, "改规则 " + name, new Callable<String>() {
                @Override
                public String call() {
                    World ww = Bukkit.getWorld(wn);
                    if (ww == null) return Api.error("世界已卸载");
                    if (before == null || before.isEmpty()) {
                        return Api.error("原来是空的，撤销不了");
                    }
                    ww.setGameRuleValue(applied, before);
                    return Api.okWith("已撤销：" + name + " 改回 " + before);
                }
            });
            return Api.okWith(name + " 现在是 " + v, u);
        }

        // ── 保存 ──
        if ("save".equals(action)) {
            w.save();
            audit(wn, "手动保存了这个世界");
            return Api.okWith(wn + " 已保存");
        }

        // ── 出生点 ──
        if ("spawn".equals(action)) {
            Location beforeSpawn = w.getSpawnLocation().clone();
            double x, y, z;
            // 参数可以是 "x y z"，也可以是一个在线玩家名（用他脚下那格）
            String[] parts = a1.split("\\s+");
            if (parts.length >= 3) {
                try {
                    x = Double.parseDouble(parts[0]);
                    y = Double.parseDouble(parts[1]);
                    z = Double.parseDouble(parts[2]);
                } catch (NumberFormatException e) {
                    return Api.error("坐标格式应为：x y z");
                }
            } else if (!a1.isEmpty()) {
                Player p = Bukkit.getPlayerExact(a1);
                if (p == null) {
                    return Api.error("既不是坐标，也找不到玩家：" + a1);
                }
                Location pl = p.getLocation();
                x = pl.getX();
                y = pl.getY();
                z = pl.getZ();
            } else {
                return Api.error("要填坐标或一个在线玩家的名字");
            }
            w.setSpawnLocation(new Location(w, x, y, z));
            audit(wn, "出生点设为 " + Math.round(x) + "," + Math.round(y) + "," + Math.round(z));
            String u = actions.remember("世界 " + wn, "改出生点", new Callable<String>() {
                @Override
                public String call() {
                    World ww = Bukkit.getWorld(wn);
                    if (ww == null) return Api.error("世界已卸载");
                    ww.setSpawnLocation(beforeSpawn);
                    return Api.okWith("已撤销：出生点改回原处");
                }
            });
            return Api.okWith(wn + " 的出生点已设置", u);
        }

        // ── 边界 ──
        if ("border".equals(action)) {
            WorldBorder bd = w.getWorldBorder();
            final double beforeSize = bd.getSize();
            final Location beforeCenter = bd.getCenter().clone();

            String mode = a1.toLowerCase(Locale.ROOT);
            if ("size".equals(mode)) {
                double size;
                try {
                    size = Double.parseDouble(a2);
                } catch (NumberFormatException e) {
                    return Api.error("直径要填数字");
                }
                if (size < 16 || size > 59999968D) {
                    return Api.error("边界直径要在 16 ~ 59999968 之间");
                }
                bd.setSize(size);
                audit(wn, "边界直径设为 " + Math.round(size));
                String u = actions.remember("世界 " + wn, "改边界", new Callable<String>() {
                    @Override
                    public String call() {
                        World ww = Bukkit.getWorld(wn);
                        if (ww == null) return Api.error("世界已卸载");
                        ww.getWorldBorder().setSize(beforeSize);
                        return Api.okWith("已撤销：边界直径改回 " + Math.round(beforeSize));
                    }
                });
                return Api.okWith("边界直径已设为 " + Math.round(size), u);
            }
            if ("center".equals(mode)) {
                String[] parts = a2.split("\\s+");
                if (parts.length < 2) {
                    return Api.error("中心点格式应为：x z");
                }
                double cx, cz;
                try {
                    cx = Double.parseDouble(parts[0]);
                    cz = Double.parseDouble(parts[1]);
                } catch (NumberFormatException e) {
                    return Api.error("中心点要填数字");
                }
                bd.setCenter(cx, cz);
                audit(wn, "边界中心设为 " + Math.round(cx) + "," + Math.round(cz));
                String u = actions.remember("世界 " + wn, "改边界中心", new Callable<String>() {
                    @Override
                    public String call() {
                        World ww = Bukkit.getWorld(wn);
                        if (ww == null) return Api.error("世界已卸载");
                        ww.getWorldBorder().setCenter(beforeCenter);
                        return Api.okWith("已撤销：边界中心改回原处");
                    }
                });
                return Api.okWith("边界中心已移动", u);
            }
            return Api.error("边界操作只能是 size / center");
        }

        // ── 清理掉落物 ──
        if ("clear-items".equals(action)) {
            int removed = 0;
            for (Entity e : snapshot(w)) {
                if (e instanceof Item) {
                    e.remove();
                    removed++;
                }
            }
            audit(wn, "清理掉落物 " + removed + " 个");
            return Api.okWith("清理了 " + removed + " 个掉落物");
        }

        // ── 清理生物 ──
        if ("clear-entities".equals(action)) {
            String mode = a1.toLowerCase(Locale.ROOT);
            boolean mobs = "mobs".equals(mode) || "all".equals(mode);
            boolean items = "items".equals(mode) || "all".equals(mode);
            if (!mobs && !items) {
                return Api.error("清理类型只能是 items / mobs / all");
            }
            int rm = 0;
            int ri = 0;
            for (Entity e : snapshot(w)) {
                if (e instanceof Player) {
                    continue;   // 绝不碰玩家
                }
                if (e instanceof Item) {
                    if (items) {
                        e.remove();
                        ri++;
                    }
                } else if (e instanceof LivingEntity) {
                    if (mobs) {
                        e.remove();
                        rm++;
                    }
                }
            }
            audit(wn, "清理实体：生物 " + rm + " 个，掉落物 " + ri + " 个");
            return Api.okWith("清理完成：生物 " + rm + " 个，掉落物 " + ri + " 个");
        }

        return Api.error("不认识的世界操作：" + action);
    }

    /** 先拷一份再删：边遍历边 remove 会抛 ConcurrentModificationException。 */
    private static List<Entity> snapshot(World w) {
        return new ArrayList<Entity>(w.getEntities());
    }

    private static int safeChunks(World w) {
        try {
            return w.getLoadedChunks().length;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int safeEntities(World w) {
        try {
            return w.getEntities().size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String envLabel(World w) {
        switch (w.getEnvironment()) {
            case NETHER:
                return "下界";
            case THE_END:
                return "末地";
            default:
                return "主世界";
        }
    }

    private static String clockOf(long tick) {
        double hour = ((tick / 1000.0) + 6.0) % 24.0;
        int hh = (int) hour;
        int mm = (int) ((hour - hh) * 60);
        return (hh < 10 ? "0" : "") + hh + ":" + (mm < 10 ? "0" : "") + mm;
    }

    private static String diffLabel(Difficulty d) {
        if (d == null) {
            return "?";
        }
        switch (d) {
            case PEACEFUL:
                return "和平";
            case EASY:
                return "简单";
            case HARD:
                return "困难";
            default:
                return "普通";
        }
    }

    private void audit(String world, String what) {
        try {
            plugin.events().addNoPos(System.currentTimeMillis(),
                    "【世界】" + world, "", "ADMIN", what);
        } catch (Throwable ignored) {
            // 日志满了不影响操作
        }
    }
}
