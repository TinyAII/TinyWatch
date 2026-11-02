package com.tinyaii.tinywatch.monitor;

/**
 * 一个轨迹点（俯视用，只关心 x/z，y 留着给提示用）。
 */
public final class TrailPoint {

    public final long time;
    public final String world;
    public final double x;
    public final double y;
    public final double z;

    public TrailPoint(long time, String world, double x, double y, double z) {
        this.time = time;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
