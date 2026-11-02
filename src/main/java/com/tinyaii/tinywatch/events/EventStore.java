package com.tinyaii.tinywatch.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 操作日志的存储。
 *
 * <p><b>为什么是环形缓冲而不是列表</b>：
 * 服务器能连续跑几个月，如果日志一直往 List 里加，内存会一路涨到 OOM。
 * 环形数组定长，写满就覆盖最旧的一条，内存占用从一开始就是常数。
 *
 * <p><b>为什么日志不落盘</b>：
 * 落盘（写文件）要考虑磁盘 IO、文件轮转、并发写。这不是一个监控面板该背的负担，
 * 市面上的日志审计插件（CoreProtect 那类）已经把这件事做到了极致。
 * 本插件定位于「现在正在发生什么」，重启清空是可以接受的。
 * 需要长期存档的，请配合 CoreProtect 使用。
 *
 * <p>所有方法都 synchronized：写入来自服务端主线程，读取来自 Web 线程。
 * 日志量每秒最多几十条，锁竞争可以忽略。
 */
public final class EventStore {

    /** 新事件到达时的回调（用于实时推送）。 */
    public interface Sink {
        void onAction(Action a);
    }

    private final Action[] ring;
    private int head;      // 下一个写入位置
    private int count;     // 当前条数
    private long seq;      // 单调递增的序号，从 1 开始

    private volatile Sink sink;

    public EventStore(int capacity) {
        this.ring = new Action[Math.max(16, capacity)];
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    /** 有坐标的事件（方块、容器）。 */
    public Action addPos(long time, String name, String uuid, String type, String detail,
                         String world, int x, int y, int z) {
        return add(time, name, uuid, type, detail, true, world, x, y, z);
    }

    /** 没有坐标的事件（命令、进出服、死亡按玩家位置算，用 addPos 也行）。 */
    public Action addNoPos(long time, String name, String uuid, String type, String detail) {
        return add(time, name, uuid, type, detail, false, null, 0, 0, 0);
    }

    private Action add(long time, String name, String uuid, String type, String detail,
                       boolean hasPos, String world, int x, int y, int z) {
        Action a;
        synchronized (this) {
            seq++;
            a = new Action(seq, time, name == null ? "?" : name,
                    uuid == null ? "" : uuid, type,
                    detail == null ? "" : detail,
                    hasPos, world, x, y, z);
            ring[head] = a;
            head = (head + 1) % ring.length;
            if (count < ring.length) {
                count++;
            }
        }
        // 回调必须放在锁外面：推送那边一旦堆积或被阻塞，不能把主线程一起拖住
        Sink s = sink;
        if (s != null) {
            try {
                s.onAction(a);
            } catch (Throwable ignored) {
                // 推送失败绝不能影响游戏主线程
            }
        }
        return a;
    }

    /**
     * 按条件查询，最新的在前。
     *
     * @param player  玩家名（模糊、忽略大小写），为空表示不限
     * @param type    事件类型，精确匹配，为空表示不限
     * @param keyword 详情关键词（模糊、忽略大小写），为空表示不限
     * @param limit   最多返回多少条
     */
    public synchronized List<Action> query(String player, String type, String keyword, int limit) {
        Set<String> types = null;
        if (type != null && !type.trim().isEmpty()) {
            types = new HashSet<String>();
            types.add(type.trim().toUpperCase());
        }
        return query(player, types, keyword, limit);
    }

    /**
     * 同上，但类型可以给多个（网页「世界消息」页签要同时看玩家、系统、插件、指令）。
     *
     * @param types 允许的类型集合，null 或空表示不限
     */
    public synchronized List<Action> query(String player, Set<String> types, String keyword, int limit) {
        int max = limit <= 0 ? 200 : Math.min(limit, 1000);
        String p = lower(player);
        String k = lower(keyword);
        boolean allTypes = types == null || types.isEmpty();

        List<Action> out = new ArrayList<Action>();
        // 从最新的一条往回走
        for (int i = 1; i <= count && out.size() < max; i++) {
            int idx = (head - i + ring.length) % ring.length;
            Action a = ring[idx];
            if (a == null) {
                continue;
            }
            if (!allTypes && !types.contains(a.type)) {
                continue;
            }
            if (p != null && !matchesPlayer(a, p)) {
                continue;
            }
            if (k != null) {
                // detail 可能是空字符串，lower() 对空串返回 null，这里必须防一下
                String d = lower(a.detail);
                if (d == null || !d.contains(k)) {
                    continue;
                }
            }
            out.add(a);
        }
        return out;
    }

    /**
     * 统计「某个时间段内，某类型事件按玩家分组的条数」。
     *
     * <p>给预警用：谁在一分钟里破坏了几百个方块，一眼就能看出来。
     * 环形数组本身是按时间连续的，倒着扫到时间不够就可以直接停。
     */
    public synchronized Map<String, Integer> countSince(String type, long sinceMs) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        if (type == null) {
            return out;
        }
        String t = type.trim().toUpperCase();
        for (int i = 1; i <= count; i++) {
            int idx = (head - i + ring.length) % ring.length;
            Action a = ring[idx];
            if (a == null) {
                continue;
            }
            if (a.time < sinceMs) {
                break;
            }
            if (!t.equals(a.type)) {
                continue;
            }
            Integer old = out.get(a.playerName);
            out.put(a.playerName, old == null ? 1 : old + 1);
        }
        return out;
    }

    /** 取 id 大于 given 的事件（按时间正序），用于 SSE 断线补发。 */
    public synchronized List<Action> since(long givenId, int max) {
        int cap = max <= 0 ? 200 : Math.min(max, 1000);
        List<Action> out = new ArrayList<Action>();
        // 从最新往回收集：万一条数超过上限，要保留的是最近的那些，而不是最旧的
        for (int i = 1; i <= count && out.size() < cap; i++) {
            int idx = (head - i + ring.length) % ring.length;
            Action a = ring[idx];
            if (a != null && a.id > givenId) {
                out.add(a);
            }
        }
        // 上面是「新 → 旧」，推送要按发生顺序，翻过来
        Collections.reverse(out);
        return out;
    }

    public synchronized long maxId() {
        return seq;
    }

    public synchronized int size() {
        return count;
    }

    public synchronized int capacity() {
        return ring.length;
    }

    public synchronized void clear() {
        for (int i = 0; i < ring.length; i++) {
            ring[i] = null;
        }
        head = 0;
        count = 0;
    }

    private static boolean matchesPlayer(Action a, String lowerNeedle) {
        String n = lower(a.playerName);
        if (n != null && n.contains(lowerNeedle)) {
            return true;
        }
        return a.playerUuid.toLowerCase().startsWith(lowerNeedle);
    }

    private static String lower(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.isEmpty() ? null : t.toLowerCase();
    }
}
