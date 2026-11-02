package com.tinyaii.tinywatch.events;

import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.config.Settings;
import com.tinyaii.tinywatch.util.Names;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.inventory.Inventory;

import java.util.Locale;

/**
 * 把 Bukkit 事件翻译成操作日志。
 *
 * <p><b>全部用 MONITOR 优先级</b>：这是最后执行的优先级，
 * 这时候其他插件（领地、保护、反作弊）都已经决定过要不要取消这个事件了。
 * 用更高优先级的话，会把「本来会被拦下的操作」也记进去 —— 日志就会骗人。
 *
 * <p><b>ignoreCancelled = true</b>：被取消的事件说明操作没真的发生，不该记。
 */
public final class ActionListener implements Listener {

    /** 命令详情最多记这么长，防止有人粘贴一整段脚本把缓冲刷满。 */
    private static final int MAX_COMMAND_LEN = 200;

    private final TinyWatch plugin;
    private final EventStore store;

    public ActionListener(TinyWatch plugin, EventStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    private Settings cfg() {
        return plugin.settings();
    }

    private boolean off() {
        Settings s = cfg();
        return s == null || !s.eventsEnabled;
    }

    // ────────────────────────── 方块 ──────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (off() || !cfg().recordBlockBreak) {
            return;
        }
        Block b = e.getBlock();
        if (b == null || b.getWorld() == null || cfg().isWorldIgnored(b.getWorld().getName())) {
            return;
        }
        Player p = e.getPlayer();
        store.addPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "BREAK", Names.of(b.getType()),
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (off() || !cfg().recordBlockPlace) {
            return;
        }
        Block b = e.getBlockPlaced();
        if (b == null || b.getWorld() == null || cfg().isWorldIgnored(b.getWorld().getName())) {
            return;
        }
        Player p = e.getPlayer();
        store.addPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "PLACE", Names.of(b.getType()),
                b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    // ────────────────────────── 容器 ──────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (off() || !cfg().recordContainer) {
            return;
        }
        // 打开自己的背包不算「操作」，而且会刷屏
        if (!(e.getPlayer() instanceof Player)) {
            return;
        }
        Inventory inv = e.getInventory();
        if (inv == null || inv.getHolder() instanceof Player) {
            return;
        }
        Player p = (Player) e.getPlayer();
        Location loc = p.getLocation();
        if (loc.getWorld() == null || cfg().isWorldIgnored(loc.getWorld().getName())) {
            return;
        }
        store.addPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "CONTAINER", containerName(inv.getType()),
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * 容器内取放物品。事件量很大（翻一页背包就是十几次），默认关闭。
     *
     * <p>打开这个开关之前先想清楚：一个玩家整理背包就能产生几十条日志，
     * 5000 条的缓冲几秒钟就被冲干净了。人多的时候这个开关基本不能用。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (off() || !cfg().recordContainerClick) {
            return;
        }
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        // 只记「顶部背包」是容器的情况；玩家在自己背包里整理东西不记
        Inventory top = e.getView() == null ? null : e.getView().getTopInventory();
        if (top == null || top.getType() == InventoryType.PLAYER) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        Location loc = p.getLocation();
        if (loc.getWorld() == null || cfg().isWorldIgnored(loc.getWorld().getName())) {
            return;
        }
        Material item = e.getCurrentItem() == null ? null : e.getCurrentItem().getType();
        String action;
        switch (e.getClick()) {
            case LEFT:
                action = "左键";
                break;
            case RIGHT:
                action = "右键";
                break;
            case SHIFT_LEFT:
            case SHIFT_RIGHT:
                action = "Shift";
                break;
            default:
                action = e.getClick().name();
        }
        store.addPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "CONTAINER_CLICK",
                containerName(top.getType()) + " · " + action + " · "
                        + (item == null ? "空" : Names.of(item)),
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * 容器类型的中文名。
     *
     * <p><b>为什么用字符串比较而不是 switch</b>：
     * {@code InventoryType} 里 SMOKER / BLAST_FURNACE / BARREL / LECTERN 这些都是 1.14 才加的枚举值。
     * 本插件用 1.13 的 API 编译，直接写 {@code case SMOKER:} 会编译不过。
     * 用 name() 比字符串，既能在 1.13 编译，又能在高版本正确识别新容器。
     */
    private static String containerName(InventoryType type) {
        if (type == null) {
            return "容器";
        }
        String n = type.name();
        if ("CHEST".equals(n)) return "箱子";
        if ("ENDER_CHEST".equals(n)) return "末影箱";
        if ("SHULKER_BOX".equals(n)) return "潜影盒";
        if ("BARREL".equals(n)) return "木桶";
        if ("FURNACE".equals(n)) return "熔炉";
        if ("BLAST_FURNACE".equals(n)) return "高炉";
        if ("SMOKER".equals(n)) return "烟熏炉";
        if ("DISPENSER".equals(n)) return "发射器";
        if ("DROPPER".equals(n)) return "投掷器";
        if ("HOPPER".equals(n)) return "漏斗";
        if ("BREWING".equals(n)) return "酿造台";
        if ("ENCHANTING".equals(n)) return "附魔台";
        if ("ANVIL".equals(n)) return "铁砧";
        if ("BEACON".equals(n)) return "信标";
        if ("WORKBENCH".equals(n)) return "工作台";
        if ("MERCHANT".equals(n)) return "村民交易";
        if ("LECTERN".equals(n)) return "讲台";
        if ("CARTOGRAPHY".equals(n)) return "制图台";
        if ("STONECUTTER".equals(n)) return "切石机";
        if ("GRINDSTONE".equals(n)) return "砂轮";
        if ("LOOM".equals(n)) return "织布机";
        if ("SMITHING".equals(n)) return "锻造台";
        return "容器(" + n + ")";
    }

    // ────────────────────────── 命令 ──────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (off() || !cfg().recordCommand) {
            return;
        }
        String raw = e.getMessage();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String body = raw.charAt(0) == '/' ? raw.substring(1) : raw;
        int sp = body.indexOf(' ');
        String name = (sp > 0 ? body.substring(0, sp) : body).toLowerCase(Locale.ROOT);
        // 去掉命名空间：minecraft:tp -> tp
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }

        // ⚠️ 登录类命令的参数里是明文密码。
        // 记下来等于把密码明文写在网页上，任何能打开面板的人都能看到。
        // 这一步不能省，也不能只靠"提醒用户自己加配置"。
        if (cfg().ignoreCommands.contains(name)) {
            return;
        }

        String shown = raw.length() > MAX_COMMAND_LEN
                ? raw.substring(0, MAX_COMMAND_LEN) + "…"
                : raw;

        Player p = e.getPlayer();
        Location loc = p.getLocation();
        if (loc.getWorld() != null && cfg().isWorldIgnored(loc.getWorld().getName())) {
            return;
        }
        store.addPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "COMMAND", shown,
                loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    // ────────────────────────── 聊天 ──────────────────────────

    /**
     * 玩家发言。
     *
     * <p>这是 1.13 就有的老事件。新版 Paper 上它可能不再触发，
     * 所以另外还挂了现代事件（见 {@link ChatCapture#installModern}），两条路共用去重。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        ChatCapture.submit(plugin, store, e.getPlayer(), e.getMessage());
    }

    /**
     * 插件/命令发给全服的广播（{@code Bukkit.broadcastMessage}）。
     *
     * <p>这是聊天框里最常见的「系统提示」来源：商店提示、领地道具提醒、活动公告都走这里。
     * 直接 sendMessage 给单个玩家的不算 —— Bukkit 没有对应事件。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent e) {
        if (off() || !cfg().captureConsole) {
            return;
        }
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            return;
        }
        String text = msg.trim();
        if (text.length() > 300) {
            text = text.substring(0, 300) + "…";
        }
        store.addNoPos(System.currentTimeMillis(), "广播", "", "BROADCAST", text);
    }

    // ────────────────────────── 死亡 / 进出 ──────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        if (off() || !cfg().recordDeath) {
            return;
        }
        Player p = e.getEntity();
        if (p == null) {
            return;
        }
        Player killer = p.getKiller();
        String detail = killer == null ? "死亡" : "被 " + killer.getName() + " 击杀";
        Location loc = p.getLocation();
        if (loc.getWorld() != null && cfg().isWorldIgnored(loc.getWorld().getName())) {
            return;
        }
        store.addPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "DEATH", detail,
                loc.getWorld() == null ? "?" : loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.monitor().onJoin(p);
        // 重启后禁言要续上：从持久化记录里捞一条还没到期的
        try {
            if (plugin.punishments() != null) {
                long end = plugin.punishments().muteEnd(p.getUniqueId().toString());
                if (end >= 0) {
                    plugin.guard().muteUntil(p, end);
                }
            }
        } catch (Throwable ignored) {
            // 记录读失败不影响进服
        }
        if (off() || !cfg().recordJoinQuit) {
            return;
        }
        store.addNoPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "JOIN", "进入服务器");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.monitor().onQuit(p);
        if (off() || !cfg().recordJoinQuit) {
            return;
        }
        store.addNoPos(System.currentTimeMillis(), p.getName(), p.getUniqueId().toString(),
                "QUIT", "离开服务器");
    }
}
