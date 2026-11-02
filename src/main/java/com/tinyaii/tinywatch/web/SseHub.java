package com.tinyaii.tinywatch.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SSE（Server-Sent Events）推送。
 *
 * <p><b>为什么不用 WebSocket</b>
 * SSE 就是「一个不关闭的 HTTP 响应，服务端往里面一行行写文本」。浏览器端用原生
 * {@code EventSource} 几行代码就能接，还自带断线重连。手写 WebSocket 要自己做握手、
 * 帧分片、掩码、ping/pong，出问题很难查。对「服务端单向推数据」这个场景，SSE 完全够用，
 * 而且不需要引入任何库。
 *
 * <p><b>为什么每个连接占一个线程</b>
 * JDK 自带的 HttpServer 没有 NIO 事件循环，长连接只能靠阻塞写维持。
 * 一个管理员面板最多几个人同时看，十几个线程的开销可以忽略。
 */
public final class SseHub {

    /** 单连接最多堆积多少条未发送事件，超了丢最旧的。 */
    private static final int QUEUE_CAP = 256;

    /**
     * 没有数据时多久发一次心跳。
     *
     * <p>两个作用：① 让 nginx 之类的代理不要因为「太久没数据」把连接掐掉
     * （默认 proxy_read_timeout 60 秒）；② <b>探测客户端是否已经断开</b> ——
     * 浏览器关掉页面时服务端不会收到通知，只有下一次往里写的时候才会抛 IOException。
     * 所以这个值同时也是「检测玩家关页面」的最大延迟，不能设太大。
     * 5 秒是个折中：断线最多 5 秒就被清理，心跳流量也可以忽略。
     */
    private static final int HEARTBEAT_SECONDS = 5;

    private final List<Client> clients = new CopyOnWriteArrayList<Client>();
    private volatile boolean running = true;

    /**
     * 处理一次 SSE 请求。这个方法会一直阻塞到客户端断开，所以它在 handler 线程里跑。
     *
     * @param backfill 建立连接后立刻补发的历史事件（可为 null）
     */
    public void serve(HttpExchange ex, String backfill) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache, no-transform");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        // 关键：nginx 默认会把响应缓冲起来，不设这个头的话事件会卡在代理里不往下发
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);   // 0 = chunked，长度未知

        OutputStream os = ex.getResponseBody();
        Client c = new Client(os);
        clients.add(c);

        try {
            write(os, "retry: 3000\n\n");
            if (backfill != null && !backfill.isEmpty()) {
                write(os, backfill);
            }
            while (running && !c.closed) {
                String msg = c.queue.poll(HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                if (msg == null) {
                    // 注释行，客户端会忽略，作用只是让连接保持活跃
                    write(os, ": ping\n\n");
                } else if (c.closed) {
                    break;
                } else {
                    write(os, msg);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 客户端关了页面/断网，属于正常现象
        } finally {
            c.closed = true;
            clients.remove(c);
            try {
                os.close();
            } catch (Throwable ignored) {
                // exchange 可能已被 HttpServer 回收
            }
        }
    }

    /** 把一条 JSON 事件推给所有在线客户端。任意线程可调，不阻塞。 */
    public void broadcastJson(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        // 单行化：SSE 的 data 字段不允许裸换行
        String safe = json.replace("\r", " ").replace("\n", " ");
        String payload = "data: " + safe + "\n\n";
        for (Client c : clients) {
            if (c.closed) {
                continue;
            }
            if (!c.queue.offer(payload)) {
                // 队列满了说明客户端读得太慢（或者卡住了），丢最旧的一条再塞
                c.queue.poll();
                c.queue.offer(payload);
            }
        }
    }

    public int size() {
        return clients.size();
    }

    /**
     * 重新启用。{@link #shutdown()} 会把 running 置 false，
     * 而 WebServer 在 reload 时是「先 stop 再 start」的同一个实例 ——
     * 不重置的话，reload 之后所有 SSE 请求会立刻返回、页面永远连不上。
     */
    public void reset() {
        clients.clear();
        running = true;
    }

    /** 关服务时叫醒所有阻塞的线程。 */
    public void shutdown() {
        running = false;
        for (Client c : clients) {
            c.closed = true;
            c.queue.offer(": bye\n\n");
        }
    }

    private static void write(OutputStream os, String s) throws IOException {
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static final class Client {
        final OutputStream out;
        final BlockingQueue<String> queue = new LinkedBlockingQueue<String>(QUEUE_CAP);
        volatile boolean closed = false;

        Client(OutputStream out) {
            this.out = out;
        }
    }
}
