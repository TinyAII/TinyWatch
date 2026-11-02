package com.tinyaii.tinywatch.monitor;

/** 一个世界的概况（不可变）。 */
public final class WorldView {

    public final String name;
    /** NORMAL / NETHER / THE_END / CUSTOM */
    public final String env;
    public final int players;
    public final int chunks;
    public final int entities;

    public WorldView(String name, String env, int players, int chunks, int entities) {
        this.name = name;
        this.env = env;
        this.players = players;
        this.chunks = chunks;
        this.entities = entities;
    }
}
