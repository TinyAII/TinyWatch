package com.tinyaii.tinywatch.moderation;

import com.tinyaii.tinywatch.TinyWatch;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 处罚记录（禁言 / 封禁），落到 {@code plugins/TinyWatch/punishments.yml}。
 *
 * <p><b>为什么要落盘</b>：禁言原来只存在内存里，服务器一重启就全没了 ——
 * 玩家换个时间照样说话。这里把「谁、什么时候、为什么、到什么时候」记下来，
 * 重启后还能续上，也能在网页上看到历史。
 *
 * <p><b>为什么用 YAML 而不是数据库</b>：几十上百条的规模，YAML 足够；
 * 引入数据库会让这个插件的部署复杂度上一个台阶。文件很小，改一次存一次也不会卡。
 */
public final class Punishments {

    /** 一条处罚。 */
    public static final class Record {
        public String id = "";
        public String name = "";
        public String uuid = "";
        /** MUTE / BAN */
        public String type = "";
        public long start;
        /** 0 表示永久。 */
        public long end;
        public String reason = "";
        public String by = "网页";
        public boolean revoked;

        public boolean active(long now) {
            return !revoked && (end <= 0 || end > now);
        }

        public String state(long now) {
            if (revoked) {
                return "已解除";
            }
            if (end > 0 && end <= now) {
                return "已到期";
            }
            return end <= 0 ? "生效中（永久）" : "生效中";
        }
    }

    private static final int MAX_RECORDS = 500;

    private final TinyWatch plugin;
    private final File file;
    private final List<Record> records = new ArrayList<Record>();
    private long seq;

    public Punishments(TinyWatch plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
        load();
    }

    private synchronized void load() {
        records.clear();
        if (!file.exists()) {
            return;
        }
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = y.getConfigurationSection("records");
            if (root == null) {
                return;
            }
            for (String key : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                Record r = new Record();
                r.id = key;
                r.name = s.getString("name", "?");
                r.uuid = s.getString("uuid", "");
                r.type = s.getString("type", "MUTE");
                r.start = s.getLong("start", 0L);
                r.end = s.getLong("end", 0L);
                r.reason = s.getString("reason", "");
                r.by = s.getString("by", "网页");
                r.revoked = s.getBoolean("revoked", false);
                records.add(r);
                try {
                    long n = Long.parseLong(key);
                    if (n > seq) {
                        seq = n;
                    }
                } catch (NumberFormatException ignored) {
                    // 手改过的 id，不影响自增
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[处罚记录] 读取 punishments.yml 失败：" + t);
        }
    }

    private synchronized void save() {
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                return;
            }
            YamlConfiguration y = new YamlConfiguration();
            for (Record r : records) {
                String base = "records." + r.id + ".";
                y.set(base + "name", r.name);
                y.set(base + "uuid", r.uuid);
                y.set(base + "type", r.type);
                y.set(base + "start", r.start);
                y.set(base + "end", r.end);
                y.set(base + "reason", r.reason);
                y.set(base + "by", r.by);
                y.set(base + "revoked", r.revoked);
            }
            y.save(file);
        } catch (Throwable t) {
            plugin.getLogger().warning("[处罚记录] 保存 punishments.yml 失败：" + t);
        }
    }

    /** 记一条处罚，返回记录。 */
    public synchronized Record add(String name, String uuid, String type,
                                   long end, String reason) {
        Record r = new Record();
        seq++;
        r.id = String.valueOf(seq);
        r.name = name == null ? "?" : name;
        r.uuid = uuid == null ? "" : uuid;
        r.type = type;
        r.start = System.currentTimeMillis();
        r.end = end;
        r.reason = reason == null ? "" : reason;
        records.add(0, r);
        while (records.size() > MAX_RECORDS) {
            records.remove(records.size() - 1);
        }
        save();
        return r;
    }

    /** 解除：把某个玩家该类型的所有生效记录标成已解除。 */
    public synchronized int revoke(String uuid, String type) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (Record r : records) {
            if (r.active(now) && r.type.equalsIgnoreCase(type)
                    && (uuid == null || r.uuid.equals(uuid))) {
                r.revoked = true;
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    /**
     * 按玩家名解除某类型的生效记录。
     *
     * <p>黑名单页只能拿到玩家名（原版 ban 记录里没有 uuid），所以按名字匹配。
     */
    public synchronized int revokeByName(String name, String type) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (Record r : records) {
            if (r.active(now) && r.type.equalsIgnoreCase(type)
                    && r.name.equalsIgnoreCase(name)) {
                r.revoked = true;
                n++;
            }
        }
        if (n > 0) {
            save();
        }
        return n;
    }

    /** 按 id 解除（网页上点某一条的「解除」）。 */
    public synchronized boolean revokeById(String id) {
        for (Record r : records) {
            if (r.id.equals(id)) {
                if (r.revoked) {
                    return false;
                }
                r.revoked = true;
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized Record find(String id) {
        for (Record r : records) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }

    /** 最近 N 条（新的在前）。 */
    public synchronized List<Record> list(int limit) {
        int n = Math.min(Math.max(1, limit), records.size());
        return new ArrayList<Record>(records.subList(0, n));
    }

    /** 这个玩家当前还有没有效的禁言，返回结束时间（0 = 永久，-1 = 没有）。 */
    public synchronized long muteEnd(String uuid) {
        long now = System.currentTimeMillis();
        for (Record r : records) {
            if (r.active(now) && "MUTE".equalsIgnoreCase(r.type) && r.uuid.equals(uuid)) {
                return r.end;
            }
        }
        return -1L;
    }

    public synchronized String json(int limit) {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder(512);
        b.append("{\"ok\":true,\"records\":[");
        List<Record> list = list(limit);
        for (int i = 0; i < list.size(); i++) {
            Record r = list.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"id\":").append(com.tinyaii.tinywatch.util.Json.str(r.id))
                    .append(",\"name\":").append(com.tinyaii.tinywatch.util.Json.str(r.name))
                    .append(",\"uuid\":").append(com.tinyaii.tinywatch.util.Json.str(r.uuid))
                    .append(",\"type\":").append(com.tinyaii.tinywatch.util.Json.str(r.type))
                    .append(",\"start\":").append(r.start)
                    .append(",\"end\":").append(r.end)
                    .append(",\"reason\":").append(com.tinyaii.tinywatch.util.Json.str(r.reason))
                    .append(",\"state\":").append(com.tinyaii.tinywatch.util.Json.str(r.state(now)))
                    .append(",\"active\":").append(r.active(now))
                    .append('}');
        }
        b.append("]}");
        return b.toString();
    }

    /** 只读视图，给别处遍历用。 */
    public synchronized List<Record> all() {
        return Collections.unmodifiableList(new ArrayList<Record>(records));
    }
}
