package com.tinyaii.tinywatch.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.config.Settings;
import com.tinyaii.tinywatch.events.Action;
import com.tinyaii.tinywatch.events.EventStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内置的 HTTP 服务。
 *
 * <p>用 JDK 自带的 {@code com.sun.net.httpserver}：不用 Jetty、不用 NanoHTTPD，
 * jar 里不带任何第三方 Web 库。代价是它只支持阻塞模型，所以 SSE 长连接会各占一个线程
 * —— 管理员面板最多几个人同时看，这个开销可以接受。
 *
 * <p><b>默认只监听 127.0.0.1</b>。想让外网访问，正路是 nginx 反代 + HTTPS，
 * 而不是把这里改成 0.0.0.0。面板上能看到所有玩家的坐标和操作记录，
 * 直接裸露在公网上等于给服务器装了个没锁的监控摄像头。
 */
public final class WebServer {

    private static final String COOKIE_NAME = "tw_session";
    private static final int MAX_BODY_BYTES = 4096;

    /**
     * SSE 长连接上限，必须小于线程池大小。
     *
     * <p>每个 SSE 连接会独占一个线程（JDK 自带的 HttpServer 是阻塞模型）。
     * 如果上限等于线程池大小，几个人同时开着面板就能把线程占满，
     * 这时候连登录页都打不开 —— 典型的「监控把自己监控死了」。
     * 所以留出一半给普通请求。
     */
    private static final int MAX_SSE_CLIENTS = 12;

    private final TinyWatch plugin;
    private final SseHub sse = new SseHub();
    private final ActionHandler actions;
    private final WorldHandler worlds;
    private final PluginBrowser browser;

    private HttpServer server;
    private ExecutorService pool;
    private Auth auth;
    private volatile byte[] indexHtml;

    private String boundHost = "127.0.0.1";
    private int boundPort = 0;
    private int sessionHours = 12;

    public WebServer(TinyWatch plugin) {
        this.plugin = plugin;
        this.actions = new ActionHandler(plugin, plugin.events());
        this.worlds = new WorldHandler(plugin, this.actions);
        this.browser = new PluginBrowser(plugin, this.actions);
    }

    public SseHub sseHub() {
        return sse;
    }

    public boolean isRunning() {
        return server != null;
    }

    public String boundHost() {
        return boundHost;
    }

    public int boundPort() {
        return boundPort;
    }

    public Auth auth() {
        return auth;
    }

    // ────────────────────────── 生命周期 ──────────────────────────

    public synchronized boolean start(Settings s) {
        stop();
        if (!s.webEnabled) {
            plugin.getLogger().info("网页服务已在配置里关闭。");
            return false;
        }
        // 密码现在存在 password-hash 里（PBKDF2），web.password 正常情况下是空的。
        // 两个都没有才算「真没设密码」，这时宁可不启动，也不要开一个没密码的面板。
        boolean hasHash = s.webPasswordHash != null && !s.webPasswordHash.trim().isEmpty();
        boolean hasPlain = s.webPassword != null && !s.webPassword.trim().isEmpty();
        if (!hasHash && !hasPlain) {
            plugin.getLogger().severe("网页登录密码为空，出于安全考虑拒绝启动网页服务。");
            plugin.getLogger().severe("删掉 web.password-hash 那一项让插件自动生成即可。");
            return false;
        }

        this.auth = new Auth(s.webPasswordHash, s.sessionHours);
        this.sessionHours = s.sessionHours;
        this.indexHtml = loadIndex();
        // reload 时 sse 还是上一次那个实例，要先恢复可用状态
        sse.reset();

        try {
            InetSocketAddress addr = new InetSocketAddress(s.webHost, s.webPort);
            server = HttpServer.create(addr, 16);
        } catch (Throwable t) {
            plugin.getLogger().severe("网页服务启动失败：" + t.getMessage());
            plugin.getLogger().severe("端口 " + s.webPort + " 可能被占用，或者绑定地址 "
                    + s.webHost + " 不存在。");
            server = null;
            return false;
        }

        pool = Executors.newFixedThreadPool(24, new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "TinyWatch-Web-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
        server.setExecutor(pool);
        server.createContext("/", new Router());

        boundHost = s.webHost;
        boundPort = s.webPort;
        server.start();

        plugin.getLogger().info("网页面板已启动：http://" + boundHost + ":" + boundPort + "/");
        if ("0.0.0.0".equals(boundHost) || "::".equals(boundHost)) {
            plugin.getLogger().warning("注意：网页服务监听在所有网卡（0.0.0.0）。");
            plugin.getLogger().warning("如果服务器有公网 IP，任何人都能访问登录页。");
            plugin.getLogger().warning("建议改成 127.0.0.1 并用 nginx 反代，或至少用防火墙限制来源。");
        } else {
            plugin.getLogger().info("只监听本机。外网访问请用 nginx 反代或 SSH 隧道。");
        }
        return true;
    }

    public synchronized void stop() {
        if (server != null) {
            sse.shutdown();
            try {
                server.stop(0);
            } catch (Throwable ignored) {
                // 已在关闭过程中
            }
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        boundPort = 0;
    }

    private byte[] loadIndex() {
        InputStream in = null;
        try {
            in = plugin.getResource("web/index.html");
            if (in == null) {
                plugin.getLogger().severe("jar 里找不到 web/index.html，网页将无法打开。");
                return "<h1>TinyWatch</h1><p>index.html 缺失，请重新下载插件。</p>"
                        .getBytes(StandardCharsets.UTF_8);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            plugin.getLogger().severe("读取网页资源失败：" + t.getMessage());
            return "<h1>TinyWatch</h1><p>网页资源读取失败。</p>"
                    .getBytes(StandardCharsets.UTF_8);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    // ────────────────────────── 路由 ──────────────────────────

    private final class Router implements HttpHandler {

        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                route(ex);
            } catch (Throwable t) {
                plugin.getLogger().warning("网页请求出错 " + ex.getRequestURI()
                        + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                try {
                    sendJson(ex, 500, Api.error("服务器内部错误"));
                } catch (Throwable ignored) {
                    // 响应可能已经开始写了
                }
            } finally {
                try {
                    ex.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }

        private void route(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();

            // 首页 / 静态资源不需要登录，登录页本身就在这个文件里
            if ("/".equals(path) || "/index.html".equals(path)) {
                // 只读分享链接：?share=token → 种下会话 cookie 后跳到 /，地址栏就不带 token 了
                Map<String, String> q0 = parseQuery(ex.getRequestURI().getQuery());
                String share = q0.get("share");
                if (share != null && auth != null && auth.valid(share) && auth.isReadOnly(share)) {
                    ex.getResponseHeaders().add("Set-Cookie",
                            COOKIE_NAME + "=" + share + "; Path=/; HttpOnly; SameSite=Strict");
                    ex.getResponseHeaders().add("Location", "/");
                    ex.sendResponseHeaders(302, -1);
                    return;
                }
                sendBytes(ex, 200, "text/html; charset=utf-8", indexHtml);
                return;
            }
            if ("/favicon.ico".equals(path)) {
                // 没有图标，返回 204 而不是 404，避免浏览器每次访问都刷一条错误日志
                sendBytes(ex, 204, "image/x-icon", new byte[0]);
                return;
            }

            if ("/api/ping".equals(path)) {
                sendJson(ex, 200, "{\"ok\":true,\"plugin\":\"TinyWatch\"}");
                return;
            }

            if ("/api/login".equals(path)) {
                handleLogin(ex, method);
                return;
            }

            if ("/api/stream".equals(path)) {
                // SSE 自己管理响应，不经过 sendJson
                if (!authorized(ex)) {
                    sendJson(ex, 401, Api.error("未登录"));
                    return;
                }
                if (sse.size() >= MAX_SSE_CLIENTS) {
                    sendJson(ex, 429, Api.error("同时查看的人数已到上限（" + MAX_SSE_CLIENTS + "）"));
                    return;
                }
                String backfill = buildBackfill(ex);
                sse.serve(ex, backfill);
                return;
            }

            // 以下都是需要登录的接口
            if (!authorized(ex)) {
                sendJson(ex, 401, Api.error("未登录或登录已过期"));
                return;
            }

            // 只读分享链接：能看，但所有会改东西的接口一律拒绝
            if (readOnly(ex) && ("/api/action".equals(path) || "/api/undo".equals(path)
                    || "/api/punish-revoke".equals(path) || "/api/world-action".equals(path)
                    || "/api/ban-revoke".equals(path) || "/api/share".equals(path))) {
                sendJson(ex, 403, Api.error("这是只读分享链接，不能执行操作"));
                return;
            }

            if ("/api/logout".equals(path)) {
                auth.logout(tokenOf(ex));
                sendJson(ex, 200, Api.ok());
                return;
            }

            if ("/api/snapshot".equals(path)) {
                sendJson(ex, 200, Api.snapshot(plugin.monitor().getLatest(), plugin.events()));
                return;
            }

            if ("/api/history".equals(path)) {
                sendJson(ex, 200, Api.history(plugin.monitor().getHistory()));
                return;
            }

            if ("/api/events".equals(path)) {
                Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
                EventStore store = plugin.events();
                int limit = intOf(q.get("limit"), 200);
                // types=CHAT,PLUGIN,COMMAND 可多选；type=CHAT 单选（老参数，保留兼容）
                List<Action> list = store.query(
                        q.get("player"), parseTypes(q.get("types"), q.get("type")),
                        q.get("keyword"), limit);
                sendJson(ex, 200, Api.events(list, store.maxId()));
                return;
            }

            if ("/api/trail".equals(path)) {
                // 玩家轨迹：只读内存里的环形队列，不用回主线程
                Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
                String key = q.get("uuid") != null ? q.get("uuid") : q.get("name");
                if (key == null || key.trim().isEmpty()) {
                    sendJson(ex, 400, Api.error("缺少 uuid 参数"));
                    return;
                }
                List<com.tinyaii.tinywatch.monitor.TrailPoint> pts =
                        plugin.monitor().trailOf(java.util.UUID.fromString(key.trim()));
                sendJson(ex, 200, Api.trail(pts));
                return;
            }

            if ("/api/export".equals(path)) {
                // 导出 CSV：浏览器直接下载，所以 Content-Disposition 用 attachment
                Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
                EventStore store = plugin.events();
                int limit = intOf(q.get("limit"), 1000);
                Set<String> types = parseTypes(q.get("types"), q.get("type"));
                List<Action> list = store.query(q.get("player"), types, q.get("keyword"), limit);
                byte[] body = Api.csv(list).getBytes(StandardCharsets.UTF_8);
                Headers h = ex.getResponseHeaders();
                h.add("Content-Type", "text/csv; charset=utf-8");
                h.add("Cache-Control", "no-store");
                h.add("Content-Disposition", "attachment; filename=\"tinywatch-"
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                        .format(new java.util.Date()) + ".csv\"");
                ex.sendResponseHeaders(200, body.length);
                OutputStream os = ex.getResponseBody();
                os.write(body);
                os.flush();
                return;
            }

            if ("/api/undo".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(ex, 405, Api.error("请用 POST"));
                    return;
                }
                String body = readBody(ex);
                sendJson(ex, 200, actions.undo(field(body, "id")));
                return;
            }

            if ("/api/punishments".equals(path)) {
                sendJson(ex, 200, plugin.punishments() == null
                        ? "{\"ok\":true,\"records\":[]}" : plugin.punishments().json(60));
                return;
            }

            if ("/api/punish-revoke".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(ex, 405, Api.error("请用 POST"));
                    return;
                }
                String body = readBody(ex);
                sendJson(ex, 200, actions.revokePunishment(field(body, "id")));
                return;
            }

            if ("/api/share".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(ex, 405, Api.error("请用 POST"));
                    return;
                }
                String body = readBody(ex);
                int minutes = intField(body, "minutes", 60);
                String tok = auth.createShare(minutes);
                String host = ex.getRequestHeaders().getFirst("Host");
                String url = "http://" + (host == null ? "127.0.0.1" : host) + "/?share=" + tok;
                sendJson(ex, 200, "{\"ok\":true,\"url\":"
                        + com.tinyaii.tinywatch.util.Json.str(url)
                        + ",\"minutes\":" + minutes + "}");
                return;
            }

            if ("/api/worlds".equals(path)) {
                // 读世界状态要碰 Bukkit 对象，回主线程拿
                sendJson(ex, 200, actionsSafeWorlds());
                return;
            }

            if ("/api/world-action".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(ex, 405, Api.error("请用 POST"));
                    return;
                }
                String body = readBody(ex);
                sendJson(ex, 200, worlds.execute(field(body, "world"),
                        field(body, "action"), field(body, "arg1"), field(body, "arg2")));
                return;
            }

            if ("/api/plugins".equals(path)) {
                // 要遍历 HandlerList / 调度器，回主线程
                sendJson(ex, 200, onMain(new java.util.concurrent.Callable<String>() {
                    @Override
                    public String call() {
                        return browser.listPlugins();
                    }
                }));
                return;
            }

            if ("/api/bans".equals(path)) {
                sendJson(ex, 200, browser.listBans());
                return;
            }

            if ("/api/ban-revoke".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(ex, 405, Api.error("请用 POST"));
                    return;
                }
                String body = readBody(ex);
                sendJson(ex, 200, browser.revokeBan(field(body, "name")));
                return;
            }

            if ("/api/update".equals(path)) {
                sendJson(ex, 200, updateJson());
                return;
            }

            if ("/api/update-check".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(ex, 405, Api.error("请用 POST"));
                    return;
                }
                com.tinyaii.tinywatch.service.UpdateChecker uc = plugin.updateChecker();
                if (uc == null) {
                    sendJson(ex, 200, Api.error("更新检查没有初始化"));
                    return;
                }
                // 同步查：内部有 3 秒超时，查完直接把结果给前端，省一次轮询
                uc.check();
                sendJson(ex, 200, updateJson());
                return;
            }

            if ("/api/tasks".equals(path)) {
                sendJson(ex, 200, plugin.tasks() == null ? "{\"ok\":true,\"jobs\":[]}"
                        : plugin.tasks().json());
                return;
            }

            if ("/api/items".equals(path)) {
                // 网页「给物品」的自动补全用。拼一次就缓存住，不重复算。
                sendJson(ex, 200, Api.itemList());
                return;
            }

            if ("/api/player".equals(path)) {
                // 背包和周围实体按需查询：点开某个玩家才拉一次，不点就没有任何开销
                Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
                final String key = q.get("uuid") != null ? q.get("uuid") : q.get("name");
                if (key == null || key.trim().isEmpty()) {
                    sendJson(ex, 400, Api.error("缺少 uuid 或 name 参数"));
                    return;
                }
                String json = onMain(new Callable<String>() {
                    @Override
                    public String call() {
                        return Api.playerDetail(findPlayer(key.trim()));
                    }
                });
                sendJson(ex, 200, json);
                return;
            }

            if ("/api/action".equals(path)) {
                if (!"POST".equalsIgnoreCase(method)) {
                    sendJson(ex, 405, Api.error("请用 POST"));
                    return;
                }
                String body = readBody(ex);
                String json = actions.execute(
                        field(body, "action"),
                        field(body, "uuid"),
                        field(body, "arg1"),
                        field(body, "arg2"));
                sendJson(ex, 200, json);
                return;
            }

            if ("/api/config".equals(path)) {
                // 前端要用到的少量配置，不含任何敏感信息
                Settings s = plugin.settings();
                StringBuilder b = new StringBuilder(192);
                b.append("{\"ok\":true");
                b.append(",\"itemIcons\":").append(com.tinyaii.tinywatch.util.Json.str(s.itemIcons));
                b.append(",\"itemIconBase\":").append(com.tinyaii.tinywatch.util.Json.str(s.itemIconBase));
                b.append(",\"itemIconCdn\":").append(com.tinyaii.tinywatch.util.Json.str(s.itemIconCdn));
                b.append(",\"showIp\":").append(com.tinyaii.tinywatch.util.Json.bool(s.showIp));
                b.append(",\"actionsEnabled\":").append(com.tinyaii.tinywatch.util.Json.bool(s.actionsEnabled));
                b.append(",\"readOnly\":").append(com.tinyaii.tinywatch.util.Json.bool(readOnly(ex)));
                b.append("}");
                sendJson(ex, 200, b.toString());
                return;
            }

            if ("/api/iconmap".equals(path)) {
                // 原样吐 gzip 字节。服务器这边不解析、不建 Map，零常驻内存；
                // 浏览器缓存一天，第二次打开面板不会再下。
                byte[] gz = plugin.iconMap();
                if (gz == null) {
                    sendJson(ex, 404, Api.error("图标映射表缺失（jar 不完整？）"));
                    return;
                }
                Headers h = ex.getResponseHeaders();
                h.add("Content-Type", "application/json; charset=utf-8");
                h.add("Content-Encoding", "gzip");
                h.add("Cache-Control", "public, max-age=86400");
                ex.sendResponseHeaders(200, gz.length);
                OutputStream os = ex.getResponseBody();
                os.write(gz);
                os.flush();
                return;
            }

            if ("/api/status".equals(path)) {
                sendJson(ex, 200, statusJson());
                return;
            }

            sendJson(ex, 404, Api.error("没有这个接口"));
        }

        private void handleLogin(HttpExchange ex, String method) throws IOException {
            if (!"POST".equalsIgnoreCase(method)) {
                sendJson(ex, 405, Api.error("请用 POST"));
                return;
            }
            String body = readBody(ex);
            String pw = extractPassword(body);
            String ip = clientIp(ex);

            long locked = auth.lockedFor(ip);
            if (locked > 0) {
                long secs = locked / 1000L + 1;
                plugin.getLogger().warning("网页登录被限流，来源 " + ip + "，还要等 " + secs + " 秒");
                sendJson(ex, 429, Api.error("密码错误次数太多，请 " + secs + " 秒后再试"));
                return;
            }

            String token = auth.login(pw, ip);
            if (token == null) {
                // 故意不区分「密码错」和「没设密码」，不泄露服务端状态
                plugin.getLogger().warning("网页登录失败，来源 " + ip);
                sendJson(ex, 401, Api.error("密码不正确"));
                return;
            }
            plugin.getLogger().info("网页登录成功，来源 " + ip);
            Headers h = ex.getResponseHeaders();
            h.add("Set-Cookie", COOKIE_NAME + "=" + token
                    + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (sessionHours * 3600L));
            sendJson(ex, 200, "{\"ok\":true,\"token\":\"" + token + "\"}");
        }

        private String buildBackfill(HttpExchange ex) {
            Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
            long since = 0;
            String s = q.get("lastId");
            if (s != null) {
                try {
                    since = Long.parseLong(s);
                } catch (NumberFormatException ignored) {
                    since = 0;
                }
            }
            if (since <= 0) {
                // 首次连接不回补：页面打开时已经用 /api/events 拉了最近的历史，
                // 这里再补一遍会重复。只有断线重连（带 lastId）才需要补。
                return null;
            }
            List<Action> missed = plugin.events().since(since, 100);
            if (missed.isEmpty()) {
                return null;
            }
            StringBuilder b = new StringBuilder(missed.size() * 160);
            for (Action a : missed) {
                b.append("data: ").append(Api.singleAction(a)).append("\n\n");
            }
            return b.toString();
        }

        private String statusJson() {
            StringBuilder b = new StringBuilder(256);
            b.append("{\"ok\":true");
            b.append(",\"running\":").append(isRunning());
            b.append(",\"host\":").append(com.tinyaii.tinywatch.util.Json.str(boundHost));
            b.append(",\"port\":").append(boundPort);
            b.append(",\"sseClients\":").append(sse.size());
            b.append(",\"sessions\":").append(auth == null ? 0 : auth.sessionCount());
            b.append(",\"events\":").append(plugin.events().size());
            b.append(",\"eventCapacity\":").append(plugin.events().capacity());
            b.append("}");
            return b.toString();
        }
    }

    // ────────────────────────── 鉴权 ──────────────────────────

    /**
     * 是否已登录。
     *
     * <p>认三种携带方式：Cookie（浏览器）、{@code X-Token} 头（curl 方便）、
     * {@code ?token=} 查询参数（给不能设头的客户端用）。
     */
    private boolean authorized(HttpExchange ex) {
        if (auth == null) {
            return false;
        }
        return auth.valid(tokenOf(ex));
    }

    /** 更新检查的状态。只读，任意线程可调。 */
    private String updateJson() {
        com.tinyaii.tinywatch.service.UpdateChecker uc = plugin.updateChecker();
        if (uc == null) {
            return Api.error("更新检查没有初始化");
        }
        com.tinyaii.tinywatch.service.UpdateChecker.Result r = uc.result();
        StringBuilder b = new StringBuilder(512);
        b.append("{\"ok\":true,\"enabled\":").append(uc.enabled())
                .append(",\"current\":").append(com.tinyaii.tinywatch.util.Json.str(uc.current()))
                .append(",\"checking\":").append(uc.isChecking())
                .append(",\"lastCheck\":").append(uc.lastCheckAt())
                .append(",\"lastError\":").append(com.tinyaii.tinywatch.util.Json.str(uc.lastError()));
        if (r == null) {
            b.append(",\"checked\":false}");
            return b.toString();
        }
        b.append(",\"checked\":true")
                .append(",\"hasUpdate\":").append(r.hasUpdate)
                .append(",\"latest\":").append(com.tinyaii.tinywatch.util.Json.str(r.latestVersion))
                .append(",\"notes\":").append(com.tinyaii.tinywatch.util.Json.str(r.notes))
                .append(",\"force\":").append(r.forceUpdate)
                .append(",\"url\":").append(com.tinyaii.tinywatch.util.Json.str(r.downloadUrl))
                .append(",\"published\":").append(com.tinyaii.tinywatch.util.Json.str(r.publishTime))
                .append('}');
        return b.toString();
    }

    /** /api/worlds 要读世界对象，得在主线程里拼 JSON。 */
    private String actionsSafeWorlds() {
        try {
            return onMain(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return worlds.listWorlds();
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[世界] 读取失败：" + t);
            return Api.error("读取世界失败（详情见服务器日志）");
        }
    }

    /**
     * 取来源 IP。
     *
     * <p>默认用 TCP 层的地址；只有配置里 trust-proxy 为 true 时才信 X-Forwarded-For ——
     * 不设这个开关的话，任何人都能伪造这个头绕过登录限流。
     */
    private String clientIp(HttpExchange ex) {
        try {
            Settings cfg = plugin.settings();
            if (cfg != null && cfg.trustProxy) {
                String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
                if (xff != null && !xff.trim().isEmpty()) {
                    int comma = xff.indexOf(',');
                    return (comma > 0 ? xff.substring(0, comma) : xff).trim();
                }
            }
            return ex.getRemoteAddress() == null
                    ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 当前请求是不是来自「只读分享链接」。 */
    private boolean readOnly(HttpExchange ex) {
        return auth != null && auth.isReadOnly(tokenOf(ex));
    }

    private String tokenOf(HttpExchange ex) {
        Headers h = ex.getRequestHeaders();
        String t = h.getFirst("X-Token");
        if (t != null && !t.isEmpty()) {
            return t;
        }
        Map<String, String> q = parseQuery(ex.getRequestURI().getQuery());
        t = q.get("token");
        if (t != null && !t.isEmpty()) {
            return t;
        }
        return cookie(ex, COOKIE_NAME);
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> raw = ex.getRequestHeaders().get("Cookie");
        if (raw == null) {
            return null;
        }
        for (String line : raw) {
            if (line == null) {
                continue;
            }
            for (String part : line.split(";")) {
                String p = part.trim();
                int eq = p.indexOf('=');
                if (eq > 0 && p.substring(0, eq).equals(name)) {
                    return p.substring(eq + 1);
                }
            }
        }
        return null;
    }

    // ────────────────────────── 主线程调度 ──────────────────────────

    /**
     * 把任务丢回服务端主线程执行，最多等 3 秒。
     *
     * <p>Bukkit 的 Player / World / ItemStack 只能在主线程碰，Web 线程直接调会抛异常。
     * 但「等 3 秒」也是必须的：服务器卡到主线程 5 秒没动的时候，
     * Web 线程不能一直挂着，否则 HTTP 线程池会被拖死，连页面都打不开。
     */
    private String onMain(final Callable<String> task) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        out[0] = task.call();
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[网页] 主线程任务出错：" + t);
                        out[0] = Api.error("执行出错，详情见服务器日志");
                    } finally {
                        latch.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            return Api.error("无法调度到主线程（插件正在卸载？）");
        }
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                return Api.error("服务器主线程 3 秒没有响应");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Api.error("等待被中断");
        }
        return out[0] == null ? Api.error("没有拿到结果") : out[0];
    }

    /** 按 UUID 或名字找在线玩家。必须在主线程调用。 */
    private static Player findPlayer(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        try {
            Player p = Bukkit.getPlayer(UUID.fromString(key));
            if (p != null && p.isOnline()) {
                return p;
            }
        } catch (IllegalArgumentException ignored) {
            // 不是 UUID，走名字
        }
        Player p = Bukkit.getPlayerExact(key);
        return (p != null && p.isOnline()) ? p : null;
    }

    // ────────────────────────── 工具 ──────────────────────────

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        sendBytes(ex, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", type);
        h.add("Cache-Control", "no-store");
        h.add("X-Content-Type-Options", "nosniff");
        // 内容安全策略：禁掉一切外来的脚本/对象，顺带挡点击劫持。
        // 页面本身用了内联 script/style，所以这两项必须留 unsafe-inline ——
        // 它挡不住内联 XSS，但能挡住「注入了 <script src=外域>」这一类，聊胜于无。
        h.add("Content-Security-Policy",
                "default-src 'self'; img-src 'self' https: data:; "
                        + "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'none'; "
                        + "frame-ancestors 'none'");
        if (body == null || body.length == 0) {
            ex.sendResponseHeaders(code, -1);
            return;
        }
        ex.sendResponseHeaders(code, body.length);
        OutputStream os = ex.getResponseBody();
        os.write(body);
        os.flush();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > MAX_BODY_BYTES) {
                break;
            }
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 从请求体里取密码。支持两种格式：
     * <pre>
     *   {"password":"xxx"}      ← 网页用这个
     *   password=xxx            ← curl 用这个更方便
     * </pre>
     */
    private static String extractPassword(String body) {
        if (body == null) {
            return null;
        }
        String s = body.trim();
        if (s.isEmpty()) {
            return null;
        }
        int key = s.indexOf("\"password\"");
        if (key >= 0) {
            int colon = s.indexOf(':', key);
            if (colon > 0) {
                int q1 = s.indexOf('"', colon + 1);
                if (q1 > 0) {
                    int q2 = s.indexOf('"', q1 + 1);
                    if (q2 > q1) {
                        return unescape(s.substring(q1 + 1, q2));
                    }
                }
            }
        }
        if (s.startsWith("password=")) {
            return unescape(s.substring("password=".length()));
        }
        return null;
    }

    /**
     * 从 JSON 请求体里取一个字符串字段。
     *
     * <p>只处理扁平结构 —— 这几个接口的请求体就是 {@code {"action":"kick","uuid":"...","arg1":"..."}}，
     * 为此引入一个 JSON 解析库不值得。字段不存在时返回 null。
     */
    /**
     * 取 JSON 里的整数（不带引号的那种）。
     *
     * <p>{@link #field} 只会取带引号的字符串，前端发 {"minutes":5} 这种数字会取到 null，
     * 于是永远走默认值 —— 所以数字参数单独走这里。
     */
    private static int intField(String body, String key, int def) {
        if (body == null || key == null || key.isEmpty()) {
            return def;
        }
        int i = body.indexOf("\"" + key + "\"");
        if (i < 0) {
            return def;
        }
        int colon = body.indexOf(':', i + key.length() + 2);
        if (colon < 0) {
            return def;
        }
        int j = colon + 1;
        while (j < body.length() && (body.charAt(j) == ' ' || body.charAt(j) == '"')) {
            j++;
        }
        int start = j;
        while (j < body.length() && Character.isDigit(body.charAt(j))) {
            j++;
        }
        if (j <= start) {
            return def;
        }
        try {
            return Integer.parseInt(body.substring(start, j));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String field(String body, String key) {
        if (body == null || key == null || key.isEmpty()) {
            return null;
        }
        int i = body.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = body.indexOf(':', i + key.length() + 2);
        if (colon < 0) {
            return null;
        }
        int q1 = body.indexOf('"', colon + 1);
        if (q1 < 0) {
            return null;
        }
        int q2 = body.indexOf('"', q1 + 1);
        if (q2 < q1) {
            return null;
        }
        return unescape(body.substring(q1 + 1, q2));
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char nx = s.charAt(i + 1);
                if (nx == '"' || nx == '\\' || nx == '/') {
                    b.append(nx);
                    i++;
                    continue;
                }
                if (nx == 'n') {
                    b.append('\n');
                    i++;
                    continue;
                }
                if (nx == 't') {
                    b.append('\t');
                    i++;
                    continue;
                }
            }
            b.append(ch);
        }
        return b.toString();
    }

    /** 解析 types=CHAT,PLUGIN / type=CHAT 两种写法，合并成一个集合。 */
    private static Set<String> parseTypes(String multi, String single) {
        Set<String> types = new HashSet<String>();
        if (multi != null) {
            for (String s : multi.split(",")) {
                if (s != null && !s.trim().isEmpty()) {
                    types.add(s.trim().toUpperCase());
                }
            }
        }
        if (single != null && !single.trim().isEmpty()) {
            types.add(single.trim().toUpperCase());
        }
        return types;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> map = new HashMap<String, String>();
        if (q == null || q.isEmpty()) {
            return map;
        }
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) {
                    map.put(URLDecoder.decode(pair, "UTF-8"), "");
                } else {
                    map.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                }
            } catch (Throwable ignored) {
                // 编码坏掉的参数直接跳过
            }
        }
        return map;
    }

    private static int intOf(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
