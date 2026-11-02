package com.tinyaii.tinywatch.monitor;

import java.util.Collections;
import java.util.List;

/**
 * 服务器某一瞬间的完整快照（不可变）。
 *
 * <p>Web 线程读的就是这个对象。它由主线程整体构造后一次性发布，
 * 所以永远看不到「一半新一半旧」的状态 —— 要么是上一份，要么是这一份。
 */
public final class Snapshot {

    /** 采样时刻（毫秒时间戳）。 */
    public final long time;

    /** 每秒 tick 数，满值 20。 */
    public final double tps;

    /** 平均 tick 耗时（毫秒）。拿不到时为 -1，界面显示「—」而不是编一个数。 */
    public final double mspt;

    public final int online;
    public final int maxPlayers;

    public final long usedMemMb;
    public final long maxMemMb;

    /** 实体总数（节流统计，可能比实际晚几秒）。 */
    public final int entities;
    /** 已加载区块总数（节流统计）。 */
    public final int chunks;

    public final String serverType;
    public final String serverVersion;
    public final String motd;

    public final int cores;

    /** 当前所有预警（TPS / 内存 / 实体 / 破坏速率）。正常时是空表。 */
    public final List<Alert> alerts;

    public final List<PlayerView> players;
    public final List<WorldView> worlds;
    public final JvmView jvm;

    public Snapshot(long time, double tps, double mspt,
                    int online, int maxPlayers,
                    long usedMemMb, long maxMemMb,
                    int entities, int chunks,
                    String serverType, String serverVersion, String motd,
                    int cores,
                    List<Alert> alerts,
                    List<PlayerView> players, List<WorldView> worlds, JvmView jvm) {
        this.time = time;
        this.tps = tps;
        this.mspt = mspt;
        this.online = online;
        this.maxPlayers = maxPlayers;
        this.usedMemMb = usedMemMb;
        this.maxMemMb = maxMemMb;
        this.entities = entities;
        this.chunks = chunks;
        this.serverType = serverType;
        this.serverVersion = serverVersion;
        this.motd = motd;
        this.cores = cores;
        this.alerts = Collections.unmodifiableList(alerts);
        // 包一层只读视图：万一有调用方想 add，会立刻抛异常而不是悄悄污染快照
        this.players = Collections.unmodifiableList(players);
        this.worlds = Collections.unmodifiableList(worlds);
        this.jvm = jvm;
    }
}
