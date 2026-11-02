package com.tinyaii.tinywatch.monitor;

/**
 * 一个玩家的瞬时快照（不可变）。
 *
 * <p><b>为什么是拷贝而不是直接引用 Player</b>：
 * Player 对象只能在服务端主线程安全访问，Web 线程碰到它会抛异常或者读到撕裂的数据。
 * 所以主线程把要展示的字段抄一份出来，Web 线程只读这份拷贝。
 *
 * <p>所有字段 final，构造完就不会变，多线程读天然安全。
 *
 * <p><b>背包不在这里</b>：41 格物品每次采样都抄一份，100 个玩家就是每秒 4100 个对象，
 * 纯浪费。背包改成「点开某个玩家时才查一次」（见 WebServer 的 /api/player），
 * 不点就不会产生任何开销。
 */
public final class PlayerView {

    public final String uuid;
    public final String name;

    public final String world;
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final int blockX;
    public final int blockZ;

    public final double health;
    public final double maxHealth;
    public final int food;
    public final int level;

    public final String gamemode;
    public final int ping;
    public final long onlineSeconds;

    /** 首次进服时间（毫秒时间戳，跨会话，0 = 未知）。 */
    public final long firstPlayed;

    /** 状态标记：着火 / 潜行 / 飞行 / 无敌 / 冲刺。 */
    public final boolean onFire;
    public final boolean sneaking;
    public final boolean flying;
    public final boolean invulnerable;
    public final boolean sprinting;

    /** 是否显示 IP，由配置决定；不显示时为 null。 */
    public final String ip;

    public PlayerView(String uuid, String name,
                      String world, double x, double y, double z, float yaw,
                      double health, double maxHealth, int food, int level,
                      String gamemode, int ping, long onlineSeconds, long firstPlayed,
                      boolean onFire, boolean sneaking, boolean flying,
                      boolean invulnerable, boolean sprinting,
                      String ip) {
        this.uuid = uuid;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.blockX = (int) Math.floor(x);
        this.blockZ = (int) Math.floor(z);
        this.health = health;
        this.maxHealth = maxHealth;
        this.food = food;
        this.level = level;
        this.gamemode = gamemode;
        this.ping = ping;
        this.onlineSeconds = onlineSeconds;
        this.firstPlayed = firstPlayed;
        this.onFire = onFire;
        this.sneaking = sneaking;
        this.flying = flying;
        this.invulnerable = invulnerable;
        this.sprinting = sprinting;
        this.ip = ip;
    }
}
