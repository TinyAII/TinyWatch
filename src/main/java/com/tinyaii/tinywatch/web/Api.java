package com.tinyaii.tinywatch.web;

import com.tinyaii.tinywatch.events.Action;
import com.tinyaii.tinywatch.events.EventStore;
import com.tinyaii.tinywatch.monitor.Alert;
import com.tinyaii.tinywatch.monitor.JvmView;
import com.tinyaii.tinywatch.monitor.PlayerView;
import com.tinyaii.tinywatch.monitor.Snapshot;
import com.tinyaii.tinywatch.monitor.WorldView;
import com.tinyaii.tinywatch.util.Json;
import com.tinyaii.tinywatch.util.Names;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * 把内存里的对象拼成 JSON。
 *
 * <p>全部手写 StringBuilder：结构固定、字段少，比通用序列化更可控也更快。
 * 每秒只跑几次，性能不是瓶颈，但可读性比反射方案好很多。
 */
public final class Api {

    private Api() {
    }

    public static String error(String msg) {
        return "{\"ok\":false,\"error\":" + Json.str(msg) + "}";
    }

    public static String ok() {
        return "{\"ok\":true}";
    }

    public static String okWith(String msg) {
        return "{\"ok\":true,\"msg\":" + Json.str(msg) + "}";
    }

    /** 带撤销 id 的成功返回：前端拿 undo 显示「撤销」按钮。 */
    public static String okWith(String msg, String undoId) {
        if (undoId == null || undoId.isEmpty()) {
            return okWith(msg);
        }
        return "{\"ok\":true,\"msg\":" + Json.str(msg)
                + ",\"undo\":" + Json.str(undoId) + "}";
    }

    // ────────────────────────── 快照 ──────────────────────────

    public static String snapshot(Snapshot s, EventStore store) {
        if (s == null) {
            return "{\"ok\":false,\"error\":\"还没有采到数据（插件刚启动？）\"}";
        }
        StringBuilder b = new StringBuilder(3072);
        b.append("{\"ok\":true");
        b.append(",\"time\":").append(s.time);
        b.append(",\"tps\":").append(Json.num(s.tps));
        // mspt 为 -1 表示服务端没提供这个数据，输出 null 让前端显示「—」
        b.append(",\"mspt\":").append(s.mspt < 0 ? "null" : Json.num(s.mspt));
        b.append(",\"online\":").append(s.online);
        b.append(",\"maxPlayers\":").append(s.maxPlayers);
        b.append(",\"memUsed\":").append(s.usedMemMb);
        b.append(",\"memMax\":").append(s.maxMemMb);
        b.append(",\"entities\":").append(s.entities);
        b.append(",\"chunks\":").append(s.chunks);
        b.append(",\"cores\":").append(s.cores);
        b.append(",\"server\":{");
        b.append("\"type\":").append(Json.str(s.serverType));
        b.append(",\"version\":").append(Json.str(s.serverVersion));
        b.append(",\"motd\":").append(Json.str(s.motd));
        b.append("}");
        b.append(",\"alerts\":[");
        for (int i = 0; i < s.alerts.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Alert al = s.alerts.get(i);
            b.append("{\"level\":").append(Json.str(al.level))
                    .append(",\"text\":").append(Json.str(al.text)).append('}');
        }
        b.append(']');
        b.append(",\"eventCount\":").append(store == null ? 0 : store.size());
        b.append(",\"eventCapacity\":").append(store == null ? 0 : store.capacity());

        b.append(",\"jvm\":");
        appendJvm(b, s.jvm);

        b.append(",\"worlds\":[");
        for (int i = 0; i < s.worlds.size(); i++) {
            if (i > 0) b.append(',');
            appendWorld(b, s.worlds.get(i));
        }
        b.append("]");

        b.append(",\"players\":[");
        for (int i = 0; i < s.players.size(); i++) {
            if (i > 0) b.append(',');
            appendPlayer(b, s.players.get(i));
        }
        b.append("]}");
        return b.toString();
    }

    private static void appendJvm(StringBuilder b, JvmView j) {
        if (j == null) {
            b.append("null");
            return;
        }
        b.append("{\"uptimeMs\":").append(j.uptimeMs);
        b.append(",\"gcCount\":").append(j.gcCount);
        b.append(",\"gcTimeMs\":").append(j.gcTimeMs);
        b.append(",\"heapUsed\":").append(j.heapUsedMb);
        b.append(",\"heapCommitted\":").append(j.heapCommittedMb);
        b.append(",\"heapMax\":").append(j.heapMaxMb);
        b.append(",\"nonHeapUsed\":").append(j.nonHeapUsedMb);
        b.append(",\"threads\":").append(j.threads);
        b.append("}");
    }

    private static void appendWorld(StringBuilder b, WorldView w) {
        b.append("{\"name\":").append(Json.str(w.name));
        b.append(",\"env\":").append(Json.str(w.env));
        b.append(",\"players\":").append(w.players);
        b.append(",\"chunks\":").append(w.chunks);
        b.append(",\"entities\":").append(w.entities);
        b.append("}");
    }

    private static void appendPlayer(StringBuilder b, PlayerView p) {
        b.append("{\"uuid\":").append(Json.str(p.uuid));
        b.append(",\"name\":").append(Json.str(p.name));
        b.append(",\"world\":").append(Json.str(p.world));
        b.append(",\"x\":").append(Json.num(p.x));
        b.append(",\"y\":").append(Json.num(p.y));
        b.append(",\"z\":").append(Json.num(p.z));
        b.append(",\"yaw\":").append(Json.num(p.yaw));
        b.append(",\"bx\":").append(p.blockX);
        b.append(",\"bz\":").append(p.blockZ);
        b.append(",\"health\":").append(Json.num(p.health));
        b.append(",\"maxHealth\":").append(Json.num(p.maxHealth));
        b.append(",\"food\":").append(p.food);
        b.append(",\"level\":").append(p.level);
        b.append(",\"gamemode\":").append(Json.str(p.gamemode));
        // -1 = 拿不到延迟，前端显示「—」
        b.append(",\"ping\":").append(p.ping);
        b.append(",\"onlineFor\":").append(p.onlineSeconds);
        b.append(",\"firstPlayed\":").append(p.firstPlayed);
        b.append(",\"onFire\":").append(Json.bool(p.onFire));
        b.append(",\"sneaking\":").append(Json.bool(p.sneaking));
        b.append(",\"flying\":").append(Json.bool(p.flying));
        b.append(",\"invulnerable\":").append(Json.bool(p.invulnerable));
        b.append(",\"sprinting\":").append(Json.bool(p.sprinting));
        b.append(",\"ip\":").append(p.ip == null ? "null" : Json.str(p.ip));
        b.append("}");
    }

    // ────────────────────────── 玩家详情（按需查询）──────────────────────────

    /**
     * 单个玩家的详细信息，含背包。
     *
     * <p>这份数据不在每秒采样里 —— 41 格物品每次抄一遍太浪费。
     * 只有前端点开某个玩家的抽屉时才会调到这里，所以每调一次查一次是划算的。
     *
     * <p>必须在主线程调用（读了 Player 和 ItemStack）。
     */
    public static String playerDetail(Player p) {
        if (p == null || !p.isOnline()) {
            return error("玩家已离线");
        }
        StringBuilder b = new StringBuilder(4096);
        b.append("{\"ok\":true");
        b.append(",\"uuid\":").append(Json.str(p.getUniqueId().toString()));
        b.append(",\"name\":").append(Json.str(p.getName()));

        b.append(",\"inventory\":[");
        ItemStack[] storage = p.getInventory().getStorageContents();
        boolean first = true;
        for (int i = 0; i < storage.length; i++) {
            if (isEmpty(storage[i])) {
                continue;
            }
            if (!first) b.append(',');
            first = false;
            appendItem(b, i, storage[i]);
        }
        b.append("]");

        b.append(",\"armor\":[");
        ItemStack[] armor = p.getInventory().getArmorContents();
        first = true;
        for (int i = 0; i < armor.length; i++) {
            if (isEmpty(armor[i])) {
                continue;
            }
            if (!first) b.append(',');
            first = false;
            appendItem(b, i, armor[i]);
        }
        b.append("]");

        b.append(",\"hand\":");
        appendMaybeItem(b, p.getInventory().getItemInMainHand());

        b.append(",\"offhand\":");
        appendMaybeItem(b, p.getInventory().getItemInOffHand());

        // 周围实体统计：一眼看出谁蹲在刷怪塔
        int total = 0;
        int items = 0;
        int mobs = 0;
        try {
            Location loc = p.getLocation();
            if (loc.getWorld() != null) {
                for (Entity e : loc.getWorld().getNearbyEntities(loc, 32, 32, 32)) {
                    total++;
                    if (e instanceof Item) {
                        items++;
                    } else if (!(e instanceof Player)) {
                        mobs++;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 区块正在卸载，跳过
        }
        b.append(",\"nearby\":{");
        b.append("\"total\":").append(total);
        b.append(",\"items\":").append(items);
        b.append(",\"mobs\":").append(mobs);
        b.append(",\"radius\":32");
        b.append("}");

        b.append("}");
        return b.toString();
    }

    /**
     * 是不是「空」。
     *
     * <p>不能只判 null：Bukkit 的空槽位常常返回一个 <b>AIR 的 ItemStack</b> 而不是 null
     * （尤其是 getItemInMainHand / getItemInOffHand）。只判 null 的话，
     * 空手会传回一个 type=AIR 的"物品"，前端照着画出一个方块 —— 看起来就像手里拿着东西。
     */
    private static boolean isEmpty(ItemStack it) {
        if (it == null) {
            return true;
        }
        try {
            return it.getType() == null || it.getType() == Material.AIR || it.getAmount() <= 0;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void appendMaybeItem(StringBuilder b, ItemStack it) {
        if (isEmpty(it)) {
            b.append("null");
            return;
        }
        appendItem(b, -1, it);
    }

    private static void appendItem(StringBuilder b, int slot, ItemStack it) {
        String type = it.getType() == null ? "AIR" : it.getType().name();
        String cn = Names.of(type);
        String custom = null;
        try {
            if (it.hasItemMeta()) {
                ItemMeta meta = it.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    custom = meta.getDisplayName();
                }
            }
        } catch (Throwable ignored) {
            // 少数物品没有 meta
        }
        b.append("{\"slot\":").append(slot);
        b.append(",\"type\":").append(Json.str(type));
        b.append(",\"cn\":").append(Json.str(cn));
        b.append(",\"amount\":").append(it.getAmount());
        b.append(",\"custom\":").append(custom == null ? "null" : Json.str(stripColor(custom)));
        long dmg = 0;
        try {
            dmg = it.getDurability();
        } catch (Throwable ignored) {
            // 老版本可能没有
        }
        b.append(",\"durability\":").append(dmg);
        b.append("}");
    }

    private static String stripColor(String s) {
        try {
            return org.bukkit.ChatColor.stripColor(s);
        } catch (Throwable t) {
            return s;
        }
    }

    // ────────────────────────── 曲线 ──────────────────────────

    /**
     * 曲线数据。为了体积小，用数组而不是对象：
     * {@code [[时刻, tps, mspt, 内存, 在线数, 实体数], ...]}
     */
    public static String history(List<double[]> rows) {
        StringBuilder b = new StringBuilder(rows.size() * 48 + 32);
        b.append("{\"ok\":true,\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            double[] r = rows.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append('[');
            for (int j = 0; j < r.length; j++) {
                if (j > 0) {
                    b.append(',');
                }
                // mspt 可能是 -1，前端据此断开曲线
                b.append(Json.num(r[j]));
            }
            b.append(']');
        }
        b.append("]}");
        return b.toString();
    }

    // ────────────────────────── 事件 ──────────────────────────

    /** 玩家轨迹（俯视图用）。 */
    public static String trail(List<com.tinyaii.tinywatch.monitor.TrailPoint> points) {
        StringBuilder b = new StringBuilder(points.size() * 48 + 32);
        b.append("{\"ok\":true,\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            com.tinyaii.tinywatch.monitor.TrailPoint p = points.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"t\":").append(p.time)
                    .append(",\"w\":").append(Json.str(p.world))
                    .append(",\"x\":").append(p.x)
                    .append(",\"y\":").append(p.y)
                    .append(",\"z\":").append(p.z).append('}');
        }
        b.append("]}");
        return b.toString();
    }

    /**
     * 把事件导成 CSV。
     *
     * <p>开头加 UTF-8 BOM：不加的话 Excel 打开是乱码（它默认按 GBK 猜）。
     * 内容里的逗号、引号、换行都按 RFC4180 转义。
     */
    public static String csv(List<Action> list) {
        StringBuilder b = new StringBuilder(list.size() * 96 + 64);
        b.append('\uFEFF');
        b.append("时间,类型,玩家,内容,世界,X,Y,Z\n");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (Action a : list) {
            b.append(csvCell(f.format(new java.util.Date(a.time)))).append(',');
            b.append(csvCell(a.type)).append(',');
            b.append(csvCell(a.playerName)).append(',');
            b.append(csvCell(a.detail)).append(',');
            b.append(csvCell(a.world == null ? "" : a.world)).append(',');
            b.append(a.hasPos ? String.valueOf(a.x) : "").append(',');
            b.append(a.hasPos ? String.valueOf(a.y) : "").append(',');
            b.append(a.hasPos ? String.valueOf(a.z) : "").append('\n');
        }
        return b.toString();
    }

    private static String csvCell(String v) {
        if (v == null) {
            return "";
        }
        String out = v.replace("\r", " ").replace("\n", " ");
        // 防公式注入：Excel / Sheets 会把 = + - @ 开头的单元格当公式执行，
        // 玩家名和聊天内容都是别人可控的，必须挡掉
        if (!out.isEmpty()) {
            char c = out.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t') {
                out = "'" + out;
            }
        }
        if (out.indexOf(',') >= 0 || out.indexOf('"') >= 0) {
            out = '"' + out.replace("\"", "\"\"") + '"';
        }
        return out;
    }

    /** 物品清单缓存：一千多个材质拼一遍 JSON 不便宜，拼一次就够了。 */
    private static volatile String itemCache;

    /**
     * 网页「给物品」用的物品清单。
     *
     * <p>只给能拿到手里的（{@code isItem()}），带上中文名方便搜索 ——
     * 管理员在网页上打「钻石」或者「DIAMOND」都能匹配。
     */
    public static String itemList() {
        String cached = itemCache;
        if (cached != null) {
            return cached;
        }
        StringBuilder b = new StringBuilder(65536);
        b.append("{\"ok\":true,\"items\":[");
        boolean first = true;
        try {
            for (Material m : Material.values()) {
                if (m == null || !m.isItem()) {
                    continue;
                }
                String n = m.name();
                if (n.endsWith("_AIR") || "AIR".equals(n)) {
                    continue;
                }
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append("{\"n\":").append(Json.str(n))
                        .append(",\"c\":").append(Json.str(Names.of(m)))
                        .append('}');
            }
        } catch (Throwable ignored) {
            // 某些服务端 Material.values() 会抛，能给多少给多少
        }
        b.append("]}");
        String out = b.toString();
        itemCache = out;
        return out;
    }

    public static String events(List<Action> list, long maxId) {
        StringBuilder b = new StringBuilder(list.size() * 160 + 64);
        b.append("{\"ok\":true,\"maxId\":").append(maxId).append(",\"events\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            appendAction(b, list.get(i));
        }
        b.append("]}");
        return b.toString();
    }

    public static String singleAction(Action a) {
        StringBuilder b = new StringBuilder(192);
        b.append("{\"ok\":true,\"event\":");
        appendAction(b, a);
        b.append('}');
        return b.toString();
    }

    private static void appendAction(StringBuilder b, Action a) {
        b.append("{\"id\":").append(a.id);
        b.append(",\"t\":").append(a.time);
        b.append(",\"player\":").append(Json.str(a.playerName));
        b.append(",\"uuid\":").append(Json.str(a.playerUuid));
        b.append(",\"type\":").append(Json.str(a.type));
        b.append(",\"detail\":").append(Json.str(a.detail));
        if (a.hasPos) {
            b.append(",\"world\":").append(Json.str(a.world));
            b.append(",\"x\":").append(a.x);
            b.append(",\"y\":").append(a.y);
            b.append(",\"z\":").append(a.z);
        } else {
            b.append(",\"world\":null");
        }
        b.append("}");
    }
}
