package com.tinyaii.tinywatch.monitor;

/**
 * JVM 健康状态（不可变）。
 *
 * <p>全部来自 JDK 自带的 {@code java.lang.management}，不需要任何第三方库。
 *
 * <p><b>为什么关心 GC 次数</b>：TPS 掉下来有两种原因 —— 服务端 tick 逻辑太重，
 * 或者 GC 在频繁停顿。如果 GC 次数在短时间内猛涨、而实体数和区块数没变，
 * 那多半是内存问题（比如某个插件在漏），而不是玩法问题。
 * 面板上把「GC 次数」和「TPS」摆在一起看，很容易分辨。
 */
public final class JvmView {

    /** 服务器已运行多少毫秒。 */
    public final long uptimeMs;

    /** 累计 GC 次数与耗时。 */
    public final long gcCount;
    public final long gcTimeMs;

    public final long heapUsedMb;
    public final long heapCommittedMb;
    public final long heapMaxMb;
    public final long nonHeapUsedMb;

    /** 存活线程数。 */
    public final int threads;

    public JvmView(long uptimeMs, long gcCount, long gcTimeMs,
                   long heapUsedMb, long heapCommittedMb, long heapMaxMb,
                   long nonHeapUsedMb, int threads) {
        this.uptimeMs = uptimeMs;
        this.gcCount = gcCount;
        this.gcTimeMs = gcTimeMs;
        this.heapUsedMb = heapUsedMb;
        this.heapCommittedMb = heapCommittedMb;
        this.heapMaxMb = heapMaxMb;
        this.nonHeapUsedMb = nonHeapUsedMb;
        this.threads = threads;
    }
}
