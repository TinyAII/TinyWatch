package com.tinyaii.tinywatch.events;

/**
 * 一条玩家操作记录（不可变）。
 *
 * <p>{@link #id} 是单调递增的序号，用来给实时推送做去重：
 * 客户端记住收到的最大 id，重连时只要 id 比它大的，不会漏也不会重。
 */
public final class Action {

    public final long id;
    /** 发生时刻（毫秒时间戳）。 */
    public final long time;

    public final String playerName;
    public final String playerUuid;
    /** BREAK / PLACE / CONTAINER / CONTAINER_CLICK / COMMAND / DEATH / JOIN / QUIT。 */
    public final String type;
    /** 人类可读的补充说明，例如「钻石矿石」或「/gamemode creative」。 */
    public final String detail;

    /** 是否有坐标。命令、进出服这类事件没有意义坐标，用 false 标记。 */
    public final boolean hasPos;
    public final String world;
    public final int x;
    public final int y;
    public final int z;

    public Action(long id, long time, String playerName, String playerUuid,
                  String type, String detail,
                  boolean hasPos, String world, int x, int y, int z) {
        this.id = id;
        this.time = time;
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.type = type;
        this.detail = detail;
        this.hasPos = hasPos;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
