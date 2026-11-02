package com.tinyaii.tinywatch.web;

import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.moderation.Punishments;
import com.tinyaii.tinywatch.util.Json;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 「插件」和「黑名单」两个页签的数据来源。
 *
 * <p><b>关于「占用」</b>：Bukkit 没有 per-plugin 内存 API，Java 也测不准，
 * 所以这里不编一个假的内存数字，只给真正能测到的：
 * jar 大小、注册的事件监听器数量、命令数量、待执行的调度任务数量。
 * 监听器数量最能说明「这个插件吃不吃 tick」—— 每来一个事件都要调用一次。
 */
public final class PluginBrowser {

    private final TinyWatch plugin;
    private final ActionHandler actions;

    public PluginBrowser(TinyWatch plugin, ActionHandler actions) {
        this.plugin = plugin;
        this.actions = actions;
    }

    // ────────────────────────── 插件 ──────────────────────────

    /** 所有已装插件的概况。必须在主线程调用（要遍历 HandlerList / 调度器）。 */
    public String listPlugins() {
        // ① 事件监听器：遍历一次全表，按插件累加
        Map<String, Integer> listeners = new HashMap<String, Integer>();
        try {
            for (HandlerList hl : HandlerList.getHandlerLists()) {
                RegisteredListener[] arr = hl.getRegisteredListeners();
                if (arr == null) {
                    continue;
                }
                for (RegisteredListener rl : arr) {
                    Plugin p = rl == null ? null : rl.getPlugin();
                    if (p == null) {
                        continue;
                    }
                    String n = p.getName();
                    Integer old = listeners.get(n);
                    listeners.put(n, old == null ? 1 : old + 1);
                }
            }
        } catch (Throwable ignored) {
            // 统计失败就当没有，别影响页面
        }

        // ② 待执行任务
        Map<String, Integer> tasks = new HashMap<String, Integer>();
        try {
            for (BukkitTask t : Bukkit.getScheduler().getPendingTasks()) {
                Plugin p = t == null ? null : t.getOwner();
                if (p == null) {
                    continue;
                }
                String n = p.getName();
                Integer old = tasks.get(n);
                tasks.put(n, old == null ? 1 : old + 1);
            }
        } catch (Throwable ignored) {
            // 同上
        }

        Plugin[] all = Bukkit.getPluginManager().getPlugins();
        int enabledCount = 0;
        long totalJar = 0L;
        int totalListeners = 0;

        StringBuilder items = new StringBuilder(1024);
        List<String> blocks = new ArrayList<String>();
        for (Plugin p : all) {
            if (p == null) {
                continue;
            }
            String name = p.getName();
            File jar = jarOf(p);
            long size = jar != null && jar.isFile() ? jar.length() : 0L;
            int lst = num(listeners.get(name));
            int tsk = num(tasks.get(name));
            int cmds = countCommands(p, jar);

            if (p.isEnabled()) {
                enabledCount++;
            }
            totalJar += size;
            totalListeners += lst;

            String version = "?";
            String desc = "";
            String load = "?";
            String api = "";
            List<String> authors = new ArrayList<String>();
            List<String> depend = new ArrayList<String>();
            List<String> soft = new ArrayList<String>();
            try {
                if (p.getDescription() != null) {
                    version = safe(p.getDescription().getVersion());
                    desc = safe(p.getDescription().getDescription());
                    load = p.getDescription().getLoad() == null
                            ? "?" : p.getDescription().getLoad().name();
                    // 老版本的 spigot 没有 getAPIVersion 之外的问题，统一 try 一次
                    String a = p.getDescription().getAPIVersion();
                    api = a == null ? "" : a;
                    if (p.getDescription().getAuthors() != null) {
                        authors.addAll(p.getDescription().getAuthors());
                    }
                    if (p.getDescription().getDepend() != null) {
                        depend.addAll(p.getDescription().getDepend());
                    }
                    if (p.getDescription().getSoftDepend() != null) {
                        soft.addAll(p.getDescription().getSoftDepend());
                    }
                }
            } catch (Throwable ignored) {
                // 插件元数据有问题也要能列出来
            }

            blocks.add("{\"name\":" + Json.str(name)
                    + ",\"version\":" + Json.str(version)
                    + ",\"desc\":" + Json.str(desc)
                    + ",\"enabled\":" + p.isEnabled()
                    + ",\"load\":" + Json.str(load)
                    + ",\"api\":" + Json.str(api)
                    + ",\"jar\":" + size
                    + ",\"listeners\":" + lst
                    + ",\"commands\":" + cmds
                    + ",\"tasks\":" + tsk
                    + ",\"authors\":" + strArray(authors)
                    + ",\"depend\":" + strArray(depend)
                    + ",\"softDepend\":" + strArray(soft)
                    + ",\"path\":" + Json.str(jar == null ? "" : jar.getName())
                    + "}");
        }

        StringBuilder b = new StringBuilder(items);
        b.append("{\"ok\":true,\"totals\":{\"count\":").append(all.length)
                .append(",\"enabled\":").append(enabledCount)
                .append(",\"jarBytes\":").append(totalJar)
                .append(",\"listeners\":").append(totalListeners)
                .append("},\"plugins\":[");
        for (int i = 0; i < blocks.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(blocks.get(i));
        }
        b.append("]}");
        return b.toString();
    }

    /** 插件 jar 的实际路径（用保护域拿，不用扫目录猜文件名）。 */
    private static File jarOf(Plugin p) {
        try {
            java.security.CodeSource cs = p.getClass().getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                return new File(cs.getLocation().toURI());
            }
        } catch (Throwable ignored) {
            // 拿不到就算了
        }
        return null;
    }

    /** 从 jar 里的 plugin.yml / paper-plugin.yml 数命令个数。 */
    private static int countCommands(Plugin p, File jar) {
        if (jar == null || !jar.isFile()) {
            return 0;
        }
        String[] files = {"plugin.yml", "paper-plugin.yml"};
        for (String f : files) {
            try (JarFile jf = new JarFile(jar)) {
                JarEntry e = jf.getJarEntry(f);
                if (e == null) {
                    continue;
                }
                YamlConfiguration y = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(jf.getInputStream(e), StandardCharsets.UTF_8));
                ConfigurationSection sec = y.getConfigurationSection("commands");
                return sec == null ? 0 : sec.getKeys(false).size();
            } catch (Throwable ignored) {
                // 换下一个文件
            }
        }
        return 0;
    }

    private static int num(Integer v) {
        return v == null ? 0 : v;
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('\n', ' ').trim();
    }

    private static String strArray(List<String> list) {
        StringBuilder b = new StringBuilder(32);
        b.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(Json.str(list.get(i)));
        }
        b.append(']');
        return b.toString();
    }

    // ────────────────────────── 黑名单 ──────────────────────────

    /**
     * 黑名单 = 原版封禁名单 + 我们记录里的封禁，按玩家名合并。
     *
     * <p>为什么要合并：/ban 或用别的插件封的人只存在于 banned-players.json，
     * 而网页封的会同时写两边。只看一边都会漏。
     */
    public String listBans() {
        // 用 LinkedHashMap 保序，前端不用再排
        Map<String, String[]> merged = new LinkedHashMap<String, String[]>();
        // 值 = {name, reason, source, by, created, expires}

        try {
            for (BanEntry e : Bukkit.getBanList(BanList.Type.NAME).getBanEntries()) {
                if (e == null || e.getTarget() == null) {
                    continue;
                }
                long created = e.getCreated() == null ? 0L : e.getCreated().getTime();
                long expires = e.getExpiration() == null ? 0L : e.getExpiration().getTime();
                merged.put(key(e.getTarget()), new String[]{
                        e.getTarget(), safe(e.getReason()), "server",
                        safe(e.getSource()), String.valueOf(created), String.valueOf(expires)});
            }
        } catch (Throwable ignored) {
            // 拿不到原版名单就只显示我们自己的
        }

        if (plugin.punishments() != null) {
            for (Punishments.Record r : plugin.punishments().all()) {
                if (!"BAN".equalsIgnoreCase(r.type) || r.revoked) {
                    continue;
                }
                String k = key(r.name);
                String[] exist = merged.get(k);
                if (exist != null) {
                    exist[2] = "both";
                    continue;
                }
                merged.put(k, new String[]{
                        r.name, safe(r.reason), "web", "网页",
                        String.valueOf(r.start), String.valueOf(r.end)});
            }
        }

        StringBuilder b = new StringBuilder(512);
        b.append("{\"ok\":true,\"bans\":[");
        boolean first = true;
        for (String[] v : merged.values()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append("{\"name\":").append(Json.str(v[0]))
                    .append(",\"reason\":").append(Json.str(v[1]))
                    .append(",\"source\":").append(Json.str(v[2]))
                    .append(",\"by\":").append(Json.str(v[3]))
                    .append(",\"created\":").append(v[4])
                    .append(",\"expires\":").append(v[5])
                    .append('}');
        }
        b.append("]}");
        return b.toString();
    }

    /** 解除封禁：原版名单删掉，我们的记录标记解除。 */
    public String revokeBan(final String name) {
        if (name == null || name.trim().isEmpty()) {
            return Api.error("缺少玩家名");
        }
        final String n = name.trim();
        return actions.onMain(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                try {
                    Bukkit.getBanList(BanList.Type.NAME).pardon(n);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[黑名单] 解除失败：" + t);
                    return Api.error("解除失败（详情见服务器日志）");
                }
                try {
                    if (plugin.punishments() != null) {
                        plugin.punishments().revokeByName(n, "BAN");
                    }
                } catch (Throwable ignored) {
                    // 记录清不掉不影响解封
                }
                try {
                    plugin.events().addNoPos(System.currentTimeMillis(),
                            n, "", "ADMIN", "从黑名单解除封禁");
                } catch (Throwable ignored) {
                    // 忽略
                }
                return Api.okWith("已解除 " + n + " 的封禁");
            }
        });
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
