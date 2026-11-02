package com.tinyaii.tinywatch.web;

import com.tinyaii.tinywatch.TinyWatch;
import com.tinyaii.tinywatch.config.Settings;
import com.tinyaii.tinywatch.events.EventStore;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 网页上能执行的管理操作。
 *
 * <p><b>为什么每个操作都要绕一圈回主线程</b>
 * Web 请求跑在 HTTP 线程池里，而 Bukkit 的 Player/World 只能在服务端主线程操作。
 * 直接调会抛异常；就算某些版本不抛，改出来的状态也是不安全的。
 * 所以这里统一 {@code runTask} 调度到主线程，Web 线程拿 latch 等结果。
 *
 * <p><b>为什么每个操作都要写审计</b>
 * 网页登录只有一个共享密码，没有「谁干的」这个概念。
 * 一旦出事，唯一能追溯的就是操作日志 —— 所以这里每执行一个操作，
 * 都往操作日志流里写一条 {@code ADMIN} 记录，写明做了什么、对谁做的。
 * 没这条，面板本身就是个查不到痕迹的后门。
 *
 * <p><b>为什么危险操作默认关闭</b>
 * 见 config.yml 里 web.actions 那段的注释。
 */
public final class ActionHandler {

    private final TinyWatch plugin;
    private final EventStore events;

    /** 切旁观前的原模式，用于切回来。 */
    private final Map<UUID, GameMode> spectateBackup = new HashMap<UUID, GameMode>();

    /** 撤销最多留这么多条，超过 5 分钟的自动作废。 */
    private static final int UNDO_MAX = 30;
    private static final long UNDO_TTL_MS = 5 * 60 * 1000L;
    private final java.util.Deque<Undo> undos = new java.util.ArrayDeque<Undo>();

    /** 一条可撤销记录：id 给前端，action 是"反着做一遍"。 */
    private static final class Undo {
        final String id;
        final String player;
        final String label;
        final long expires;
        final Callable<String> action;

        Undo(String id, String player, String label, long expires, Callable<String> action) {
            this.id = id;
            this.player = player;
            this.label = label;
            this.expires = expires;
            this.action = action;
        }
    }

    public ActionHandler(TinyWatch plugin, EventStore events) {
        this.plugin = plugin;
        this.events = events;
    }

    private Settings cfg() {
        return plugin.settings();
    }

    /**
     * 执行一个操作。
     *
     * @param action  操作名（英文，前端传）
     * @param uuid    目标玩家 UUID（部分操作可为空）
     * @param arg1    参数一（原因 / 坐标 / 消息内容…）
     * @param arg2    参数二
     */
    public String execute(String action, String uuid, String arg1, String arg2) {
        Settings s = cfg();
        if (s == null || !s.actionsEnabled) {
            return Api.error("网页操作功能已在配置里关闭（web.actions.enabled: false）");
        }
        if (action == null || action.isEmpty()) {
            return Api.error("缺少 action 参数");
        }
        final String a = action.toLowerCase(Locale.ROOT);

        // 先做配置与参数的静态校验，能省掉一次主线程调度
        String deny = denyReason(a, s);
        if (deny != null) {
            return Api.error(deny);
        }

        final String u = uuid;
        final String p1 = arg1 == null ? "" : arg1;
        final String p2 = arg2 == null ? "" : arg2;

        return onMain(new Callable<String>() {
            @Override
            public String call() {
                return run(a, u, p1, p2);
            }
        });
    }

    /** 配置层面是否允许这个操作。 */
    private String denyReason(String action, Settings s) {
        if ("message".equals(action) && !s.actionMessage) return "「发消息」未开启";
        if ("teleport".equals(action) && !s.actionTeleport) return "「传送」未开启";
        if ("kick".equals(action) && !s.actionKick) return "「踢出」未开启";
        if ("vanish".equals(action) && !s.actionVanish) return "「旁观」未开启";
        if ("heal".equals(action) && !s.actionHeal) return "「治疗」未开启";
        if ("gamemode".equals(action) && !s.actionGamemode) return "「改模式」未开启";
        if ("clearitems".equals(action) && !s.actionClearItems) return "「清理掉落物」未开启";
        if ("ban".equals(action) && !s.actionBan) return "「封禁」未开启（默认关闭）";
        if ("command".equals(action)) {
            if (!s.actionCommand) return "「执行命令」未开启";
            if (s.actionCommandWhitelist.isEmpty()) {
                return "命令白名单是空的，该功能不可用（在 config.yml 的 web.actions.command-whitelist 里配置）";
            }
        }
        // 新加的这些按「组」开关，免得 config.yml 里堆二十多个开关
        if (("cleareffects".equals(action) || "giveeffect".equals(action)
                || "exp".equals(action) || "fly".equals(action)
                || "invisible".equals(action)) && !s.actState) {
            return "「状态类操作」未开启（web.actions.state）";
        }
        if (("clearinv".equals(action) || "giveitem".equals(action)
                || "repair".equals(action) || "takeslot".equals(action)
                || "itemnear".equals(action)) && !s.actItems) {
            return "「物品类操作」未开启（web.actions.items）";
        }
        if (("tpplayer".equals(action) || "spawn".equals(action)
                || "freeze".equals(action) || "saveloc".equals(action)
                || "recall".equals(action)) && !s.actPosition) {
            return "「位置类操作」未开启（web.actions.position）";
        }
        if (("title".equals(action) || "actionbar".equals(action)
                || "sound".equals(action)) && !s.actNotify) {
            return "「沟通类操作」未开启（web.actions.notify）";
        }
        if (("mute".equals(action) || "whitelist".equals(action)
                || "op".equals(action)) && !s.actModerate) {
            return "「处罚类操作」未开启（web.actions.moderate）";
        }
        if (("balance".equals(action) || "econ".equals(action)) && !s.actEconomy) {
            return "「经济类操作」未开启（web.actions.economy）";
        }
        return null;
    }

    // ────────────────────────── 主线程执行 ──────────────────────────

    private String run(String action, String uuid, String a1, String a2) {
        // 清空日志不需要玩家
        if ("clearlog".equals(action)) {
            int n = events.size();
            events.clear();
            audit(null, "清空了操作日志（" + n + " 条）");
            return Api.okWith("已清空 " + n + " 条日志");
        }

        Player target = find(uuid, a1);
        if (target == null) {
            return Api.error("找不到这个玩家（可能已经离线）");
        }
        String name = target.getName();

        if ("message".equals(action)) {
            if (a1.trim().isEmpty()) return Api.error("消息内容不能为空");
            target.sendMessage("§8[§b管理员§8] §f" + a1);
            audit(name, "收到网页发来的消息：" + a1);
            return Api.okWith("已发给 " + name);
        }

        if ("kick".equals(action)) {
            String reason = a1.trim().isEmpty() ? "被管理员移出服务器" : a1;
            target.kickPlayer("§c" + reason);
            audit(name, "被网页踢出：" + reason);
            return Api.okWith("已踢出 " + name);
        }

        if ("heal".equals(action)) {
            double max;
            try {
                max = target.getMaxHealth();
            } catch (Throwable t) {
                max = 20.0;
            }
            target.setHealth(max);
            target.setFoodLevel(20);
            target.setSaturation(20f);
            target.setFireTicks(0);
            // 清掉负面效果（中毒、凋零这类），不然治好了又掉
            removeHarmfulEffects(target);
            audit(name, "被网页治疗（回满血 + 喂饱 + 清负面效果）");
            return Api.okWith("已治疗 " + name);
        }

        if ("gamemode".equals(action)) {
            GameMode gm;
            try {
                gm = GameMode.valueOf(a1.trim().toUpperCase(Locale.ROOT));
            } catch (Throwable t) {
                return Api.error("游戏模式只能是 SURVIVAL / CREATIVE / ADVENTURE / SPECTATOR");
            }
            final GameMode beforeGm = target.getGameMode();
            target.setGameMode(gm);
            spectateBackup.remove(target.getUniqueId());
            audit(name, "被网页切换到 " + gm.name());
            String uGm = pushUndo(name, "切换游戏模式", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    p.setGameMode(beforeGm);
                    return Api.okWith("已撤销：模式恢复为 " + gmLabel(beforeGm));
                }
            });
            return Api.okWith(name + " 现在是 " + gmLabel(gm), uGm);
        }

        if ("vanish".equals(action)) {
            UUID id = target.getUniqueId();
            // 玩家直接下线不会通知这里，备份 map 会慢慢攒。攒到一定量就清一次。
            if (spectateBackup.size() > 64) {
                spectateBackup.keySet().removeIf(uid -> Bukkit.getPlayer(uid) == null);
            }
            if (target.getGameMode() == GameMode.SPECTATOR) {
                GameMode back = spectateBackup.remove(id);
                if (back == null) back = GameMode.SURVIVAL;
                target.setGameMode(back);
                audit(name, "退出旁观模式，恢复为 " + back.name());
                return Api.okWith(name + " 已退出旁观");
            }
            spectateBackup.put(id, target.getGameMode());
            target.setGameMode(GameMode.SPECTATOR);
            audit(name, "被网页切到旁观模式（原本 " + spectateBackup.get(id).name() + "）");
            return Api.okWith(name + " 已进入旁观模式");
        }

        if ("teleport".equals(action)) {
            // a1 = 世界名（空 = 当前世界），a2 = "x y z"
            String[] parts = a2.trim().split("\\s+");
            if (parts.length < 3) {
                return Api.error("坐标格式应为：x y z");
            }
            double x, y, z;
            try {
                x = Double.parseDouble(parts[0]);
                y = Double.parseDouble(parts[1]);
                z = Double.parseDouble(parts[2]);
            } catch (NumberFormatException e) {
                return Api.error("坐标必须是数字");
            }
            World w = a1.trim().isEmpty() ? target.getWorld() : Bukkit.getWorld(a1.trim());
            if (w == null) {
                return Api.error("找不到世界 " + a1);
            }
            // 取该位置的地表高度，避免传进方块里或掉虚空
            int maxY = w.getMaxHeight();
            if (y < 0 || y > maxY) {
                return Api.error("Y 坐标超出范围（0 ~ " + maxY + "）");
            }
            final Location beforeTp = target.getLocation().clone();
            target.teleport(new Location(w, x, y, z));
            audit(name, "被网页传送到 " + w.getName() + " " + x + "," + y + "," + z);
            String uTp = pushUndo(name, "传送坐标", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    p.teleport(beforeTp);
                    return Api.okWith("已撤销：传回原位");
                }
            });
            return Api.okWith("已把 " + name + " 传送到 " + w.getName(), uTp);
        }

        if ("clearitems".equals(action)) {
            Location loc = target.getLocation();
            World w = loc.getWorld();
            if (w == null) return Api.error("玩家所在世界无效");
            int radius = 32;
            int removed = 0;
            for (Entity e : w.getNearbyEntities(loc, radius, radius, radius)) {
                if (e instanceof Item) {
                    e.remove();
                    removed++;
                }
            }
            audit(name, "网页清理了 " + name + " 周围 " + radius + " 格的掉落物（" + removed + " 个）");
            return Api.okWith("清理了 " + removed + " 个掉落物");
        }

        if ("ban".equals(action)) {
            String reason = a1.trim().isEmpty() ? "被管理员封禁" : a1;
            Bukkit.getBanList(BanList.Type.NAME)
                    .addBan(name, reason, (Date) null, "TinyWatch(网页)");
            try {
                plugin.punishments().add(name, target.getUniqueId().toString(), "BAN", 0L, reason);
            } catch (Throwable ignored) {
                // 记录失败不影响封禁本身
            }
            target.kickPlayer("§c你已被封禁：\n§f" + reason);
            audit(name, "被网页封禁：" + reason);
            return Api.okWith("已封禁 " + name + "（可用 /pardon " + name + " 解除）");
        }

        if ("unban".equals(action)) {
            Bukkit.getBanList(BanList.Type.NAME).pardon(name);
            try {
                plugin.punishments().revoke(target.getUniqueId().toString(), "BAN");
            } catch (Throwable ignored) {
                // 忽略
            }
            audit(name, "被网页解除封禁");
            return Api.okWith("已解除 " + name + " 的封禁");
        }

        // ────────────────────────── 状态 ──────────────────────────

        if ("cleareffects".equals(action)) {
            int n = 0;
            try {
                for (PotionEffect eff : target.getActivePotionEffects()) {
                    target.removePotionEffect(eff.getType());
                    n++;
                }
            } catch (Throwable ignored) {
                // 某些版本不支持遍历，尽力而为
            }
            audit(name, "被网页清除全部药水效果（" + n + " 个）");
            return Api.okWith("已清除 " + n + " 个药水效果");
        }

        if ("giveeffect".equals(action)) {
            // a1 = 效果键（SPEED 之类），a2 = "秒:等级"
            PotionEffectType type = effectOf(a1.trim());
            if (type == null) {
                return Api.error("不认识的药水效果：" + a1);
            }
            int secs = 60;
            int amp = 0;
            String[] parts = a2.trim().split(":");
            try {
                if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                    secs = Integer.parseInt(parts[0].trim());
                }
                if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                    amp = Integer.parseInt(parts[1].trim());
                }
            } catch (NumberFormatException e) {
                return Api.error("时长和等级必须填数字");
            }
            if (secs < 1 || secs > 3600) return Api.error("时长要在 1 ~ 3600 秒之间");
            if (amp < 0) amp = 0;
            if (amp > 9) amp = 9;
            target.addPotionEffect(new PotionEffect(type, secs * 20, amp));
            audit(name, "被网页附加药水效果 " + type.getName() + " " + secs + " 秒（等级 " + (amp + 1) + "）");
            return Api.okWith("已给 " + name + " 加上 " + type.getName() + "（" + secs + " 秒）");
        }

        if ("exp".equals(action)) {
            int lv;
            try {
                lv = Integer.parseInt(a1.trim());
            } catch (NumberFormatException e) {
                return Api.error("等级要填数字");
            }
            if (lv < 0 || lv > 100000) return Api.error("等级要在 0 ~ 100000 之间");
            target.setLevel(lv);
            target.setExp(0f);
            audit(name, "经验等级被网页设为 " + lv);
            return Api.okWith(name + " 的等级现在是 " + lv);
        }

        if ("fly".equals(action)) {
            boolean on = "on".equalsIgnoreCase(a1.trim()) || "true".equalsIgnoreCase(a1.trim())
                    || "1".equals(a1.trim());
            target.setAllowFlight(on);
            if (!on) {
                target.setFlying(false);
            }
            audit(name, "飞行被网页" + (on ? "开启" : "关闭"));
            return Api.okWith(name + (on ? " 可以飞了" : " 不能再飞了"));
        }

        if ("invisible".equals(action)) {
            PotionEffectType inv = effectOf("INVISIBILITY");
            if (inv == null) return Api.error("这个服务端不支持隐身效果");
            boolean has;
            try {
                has = target.hasPotionEffect(inv);
            } catch (Throwable t) {
                has = false;
            }
            if (has) {
                target.removePotionEffect(inv);
                audit(name, "隐身在网页上被解除");
                return Api.okWith(name + " 已现身");
            }
            target.addPotionEffect(new PotionEffect(inv, 20 * 300, 0));
            audit(name, "被网页隐身（5 分钟）");
            return Api.okWith(name + " 已隐身（5 分钟）");
        }

        // ────────────────────────── 物品 ──────────────────────────

        if ("clearinv".equals(action)) {
            int n = 0;
            try {
                for (ItemStack st : target.getInventory().getContents()) {
                    if (st != null && st.getType() != Material.AIR) n++;
                }
            } catch (Throwable ignored) {
                // 数不清也要清
            }
            final ItemStack[] beforeInv = target.getInventory().getContents().clone();
            target.getInventory().clear();
            try {
                target.updateInventory();
            } catch (Throwable ignored) {
                // 老版本没有这个方法
            }
            audit(name, "背包被网页清空（" + n + " 格物品）");
            String uInv = pushUndo(name, "清空背包", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    p.getInventory().setContents(beforeInv);
                    try {
                        p.updateInventory();
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    return Api.okWith("已撤销：背包物品放回去了");
                }
            });
            return Api.okWith("已清空 " + name + " 的背包（" + n + " 格）", uInv);
        }

        if ("giveitem".equals(action)) {
            Material mat = materialOf(a1.trim());
            if (mat == null) {
                return Api.error("找不到物品：" + a1);
            }
            if (!mat.isItem()) {
                return Api.error(mat.name() + " 不是能拿在手里的物品");
            }
            int amount;
            try {
                amount = a2.trim().isEmpty() ? 1 : Integer.parseInt(a2.trim());
            } catch (NumberFormatException e) {
                return Api.error("数量要填数字");
            }
            if (amount < 1 || amount > 2304) return Api.error("数量要在 1 ~ 2304 之间（36 组）");

            int max = Math.max(1, mat.getMaxStackSize());
            int left = amount;
            int dropped = 0;
            while (left > 0) {
                int n = Math.min(max, left);
                ItemStack stack = new ItemStack(mat, n);
                Map<Integer, ItemStack> rest = target.getInventory().addItem(stack);
                if (rest != null) {
                    for (ItemStack r : rest.values()) {
                        if (r != null && r.getType() != Material.AIR) {
                            target.getWorld().dropItemNaturally(target.getLocation(), r);
                            dropped += r.getAmount();
                        }
                    }
                }
                left -= n;
            }
            try {
                target.updateInventory();
            } catch (Throwable ignored) {
                // 忽略
            }
            String cn = com.tinyaii.tinywatch.util.Names.of(mat);
            audit(name, "网页给了 " + cn + " × " + amount
                    + (dropped > 0 ? "（背包满，" + dropped + " 个掉在脚下）" : ""));
            return Api.okWith("已给 " + name + " " + cn + " × " + amount
                    + (dropped > 0 ? "（" + dropped + " 个掉在脚下）" : ""));
        }

        if ("repair".equals(action)) {
            int fixed = 0;
            try {
                ItemStack[] contents = target.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    fixed += repairOne(contents[i], target, i);
                }
            } catch (Throwable ignored) {
                // 修不了的就不修
            }
            try {
                target.updateInventory();
            } catch (Throwable ignored) {
                // 忽略
            }
            audit(name, "网页修复了装备耐久（" + fixed + " 件）");
            return Api.okWith("已修复 " + fixed + " 件装备");
        }

        if ("takeslot".equals(action)) {
            int slot;
            try {
                slot = Integer.parseInt(a1.trim());
            } catch (NumberFormatException e) {
                return Api.error("槽位要填数字");
            }
            if (slot < 0 || slot > 40) return Api.error("槽位要在 0 ~ 40 之间");
            ItemStack cur = target.getInventory().getItem(slot);
            if (cur == null || cur.getType() == Material.AIR) {
                return Api.error("这一格是空的");
            }
            String cn = com.tinyaii.tinywatch.util.Names.of(cur.getType());
            int n = cur.getAmount();
            final int takeSlot = slot;
            final ItemStack taken = cur.clone();
            target.getInventory().setItem(slot, null);
            try {
                target.updateInventory();
            } catch (Throwable ignored) {
                // 忽略
            }
            audit(name, "网页拿走了第 " + slot + " 格的 " + cn + " × " + n);
            String uSlot = pushUndo(name, "拿走背包物品", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    p.getInventory().setItem(takeSlot, taken);
                    return Api.okWith("已撤销：物品放回第 " + takeSlot + " 格");
                }
            });
            return Api.okWith("已拿走 " + cn + " × " + n, uSlot);
        }

        if ("itemnear".equals(action)) {
            int radius;
            try {
                radius = a1.trim().isEmpty() ? 16 : Integer.parseInt(a1.trim());
            } catch (NumberFormatException e) {
                return Api.error("半径要填数字");
            }
            if (radius < 1 || radius > 64) return Api.error("半径要在 1 ~ 64 之间");
            Location loc = target.getLocation();
            World w = loc.getWorld();
            if (w == null) return Api.error("玩家所在世界无效");
            int removed = 0;
            for (Entity e : w.getNearbyEntities(loc, radius, radius, radius)) {
                if (e instanceof Item) {
                    e.remove();
                    removed++;
                }
            }
            audit(name, "网页清理了 " + name + " 周围 " + radius + " 格的掉落物（" + removed + " 个）");
            return Api.okWith("清理了 " + removed + " 个掉落物");
        }

        // ────────────────────────── 位置 ──────────────────────────

        if ("tpplayer".equals(action)) {
            String other = a1.trim();
            if (other.isEmpty()) return Api.error("要填另一个玩家的名字");
            Player to = Bukkit.getPlayerExact(other);
            if (to == null) return Api.error("玩家 " + other + " 不在线");
            if (to.getUniqueId().equals(target.getUniqueId())) {
                return Api.error("目标就是他自己");
            }
            final Location beforeTp2 = target.getLocation().clone();
            target.teleport(to.getLocation());
            audit(name, "被网页传送到 " + to.getName() + " 身边");
            String uTp2 = pushUndo(name, "传送到玩家", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    p.teleport(beforeTp2);
                    return Api.okWith("已撤销：传回原位");
                }
            });
            return Api.okWith("已把 " + name + " 传到 " + to.getName() + " 身边", uTp2);
        }

        if ("spawn".equals(action)) {
            Location spawn = target.getWorld().getSpawnLocation();
            final Location beforeSpawn = target.getLocation().clone();
            target.teleport(spawn);
            audit(name, "被网页送回出生点");
            String uSp = pushUndo(name, "送回出生点", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    p.teleport(beforeSpawn);
                    return Api.okWith("已撤销：传回原位");
                }
            });
            return Api.okWith("已把 " + name + " 送回出生点", uSp);
        }

        if ("freeze".equals(action)) {
            boolean now = plugin.guard().toggleFreeze(target);
            audit(name, now ? "被网页冻结（原地锁定）" : "冻结被网页解除");
            String uFz = pushUndo(name, now ? "冻结" : "解冻", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    plugin.guard().toggleFreeze(p);
                    return Api.okWith("已撤销：冻结状态取反");
                }
            });
            return Api.okWith(now ? ("已冻结 " + name) : (name + " 已解冻"), uFz);
        }

        if ("saveloc".equals(action)) {
            Location loc = plugin.guard().saveLocation(target);
            audit(name, "网页记录了 " + name + " 的坐标 " + f(loc));
            return Api.okWith("已记录：" + f(loc));
        }

        if ("recall".equals(action)) {
            Location loc = plugin.guard().savedLocation(target);
            if (loc == null || loc.getWorld() == null) {
                return Api.error("还没有记录过坐标");
            }
            target.teleport(loc);
            audit(name, "被网页拉回记录点 " + f(loc));
            return Api.okWith("已把 " + name + " 拉回 " + f(loc));
        }

        // ────────────────────────── 沟通 ──────────────────────────

        if ("title".equals(action)) {
            String main = colorize(a1);
            String sub = colorize(a2);
            if (main.trim().isEmpty() && sub.trim().isEmpty()) {
                return Api.error("标题内容不能为空");
            }
            boolean ok = showTitle(target, main, sub);
            if (!ok) {
                return Api.error("这个服务端不支持标题，已改为普通消息");
            }
            audit(name, "收到网页标题：" + strip(main) + (sub.trim().isEmpty() ? "" : " / " + strip(sub)));
            return Api.okWith("已发送标题");
        }

        if ("actionbar".equals(action)) {
            String text = colorize(a1);
            if (strip(text).trim().isEmpty()) return Api.error("内容不能为空");
            boolean ok = showActionBar(target, text);
            if (!ok) {
                target.sendMessage(text);
                audit(name, "动作栏不可用，改为普通消息：" + strip(text));
                return Api.okWith("已改为普通消息发送");
            }
            audit(name, "收到网页动作栏提示：" + strip(text));
            return Api.okWith("已发送动作栏提示");
        }

        if ("sound".equals(action)) {
            String key = a1.trim();
            if (key.isEmpty()) return Api.error("要选择一个音效");
            try {
                target.playSound(target.getLocation(), key, 1f, 1f);
            } catch (Throwable t) {
                plugin.getLogger().warning("[操作] 播放音效失败：" + t);
                return Api.error("播放失败（详情见服务器日志）");
            }
            audit(name, "网页给他播了音效 " + key);
            return Api.okWith("已播放音效 " + key);
        }

        // ────────────────────────── 处罚 ──────────────────────────

        if ("mute".equals(action)) {
            int mins;
            try {
                mins = a1.trim().isEmpty() ? 0 : Integer.parseInt(a1.trim());
            } catch (NumberFormatException e) {
                return Api.error("分钟数要填数字");
            }
            if (mins < 0 || mins > 100000) return Api.error("分钟数不合理");
            plugin.guard().mute(target, mins);
            if (mins <= 0) {
                try {
                    plugin.punishments().revoke(target.getUniqueId().toString(), "MUTE");
                } catch (Throwable ignored) {
                    // 忽略
                }
                audit(name, "禁言被网页解除");
                return Api.okWith(name + " 的禁言已解除");
            }
            try {
                plugin.punishments().add(name, target.getUniqueId().toString(), "MUTE",
                        System.currentTimeMillis() + mins * 60_000L, "网页禁言 " + mins + " 分钟");
            } catch (Throwable ignored) {
                // 记录失败不影响禁言本身
            }
            audit(name, "被网页禁言 " + mins + " 分钟");
            String uMute = pushUndo(name, "禁言", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    plugin.guard().mute(p, 0);
                    return Api.okWith("已撤销：解除禁言");
                }
            });
            return Api.okWith("已禁言 " + name + " " + mins + " 分钟", uMute);
        }

        if ("whitelist".equals(action)) {
            // 1.13 的 API 里没有 Whitelist 对象，这些全是 Bukkit 上的静态方法；
            // 加/移单人走 OfflinePlayer#setWhitelisted
            String mode = a1.trim().toLowerCase(Locale.ROOT);
            if ("toggle".equals(mode)) {
                boolean on = !Bukkit.hasWhitelist();
                Bukkit.setWhitelist(on);
                audit(null, "网页" + (on ? "开启" : "关闭") + "了白名单");
                return Api.okWith("白名单现在是" + (on ? "开启" : "关闭")
                        + "状态（" + Bukkit.getWhitelistedPlayers().size() + " 个玩家在名单里）");
            }
            if ("on".equals(mode) || "off".equals(mode)) {
                boolean on = "on".equals(mode);
                Bukkit.setWhitelist(on);
                audit(null, "网页" + (on ? "开启" : "关闭") + "了白名单");
                return Api.okWith("白名单已" + (on ? "开启" : "关闭"));
            }
            if ("add".equals(mode)) {
                target.setWhitelisted(true);
                audit(name, "被网页加入白名单");
                String uWl = pushUndo(name, "加入白名单", new Callable<String>() {
                    @Override
                    public String call() {
                        Player p = Bukkit.getPlayerExact(name);
                        if (p == null) return Api.error("玩家已离线，撤销不了");
                        p.setWhitelisted(false);
                        return Api.okWith("已撤销：从白名单移出");
                    }
                });
                return Api.okWith("已把 " + name + " 加入白名单"
                        + (Bukkit.hasWhitelist() ? "" : "（注意：白名单本身还没开启，现在不影响进服）"), uWl);
            }
            if ("remove".equals(mode)) {
                target.setWhitelisted(false);
                audit(name, "被网页移出白名单");
                String uWl = pushUndo(name, "移出白名单", new Callable<String>() {
                    @Override
                    public String call() {
                        Player p = Bukkit.getPlayerExact(name);
                        if (p == null) return Api.error("玩家已离线，撤销不了");
                        p.setWhitelisted(true);
                        return Api.okWith("已撤销：重新加入白名单");
                    }
                });
                return Api.okWith("已把 " + name + " 移出白名单", uWl);
            }
            return Api.error("白名单操作只能是 add / remove / on / off");
        }

        if ("op".equals(action)) {
            boolean on = "on".equalsIgnoreCase(a1.trim());
            if (target.isOp() == on) {
                return Api.okWith(name + (on ? " 本来就是 OP" : " 本来就不是 OP"));
            }
            target.setOp(on);
            audit(name, on ? "被网页授予 OP" : "被网页撤销 OP");
            String uOp = pushUndo(name, "OP 变更", new Callable<String>() {
                @Override
                public String call() {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p == null) return Api.error("玩家已离线，撤销不了");
                    p.setOp(!on);
                    return Api.okWith("已撤销：OP 状态改回" + (on ? "无" : "有"));
                }
            });
            return Api.okWith(name + (on ? " 现在是 OP" : " 已撤销 OP"), uOp);
        }

        // ────────────────────────── 经济（Vault） ──────────────────────────

        if ("balance".equals(action)) {
            Object econ = vaultEconomy();
            if (econ == null) {
                return Api.error("没检测到 Vault 经济服务（需要装 Vault 且经济插件已注册）");
            }
            try {
                double bal = (Double) econ.getClass()
                        .getMethod("getBalance", OfflinePlayer.class).invoke(econ, (OfflinePlayer) target);
                String fmt = formatMoney(econ, bal);
                return Api.okWith(name + " 的余额：" + fmt);
            } catch (Throwable t) {
                plugin.getLogger().warning("[操作] 查询余额失败：" + t);
                return Api.error("查询失败（详情见服务器日志）");
            }
        }

        if ("econ".equals(action)) {
            Object econ = vaultEconomy();
            if (econ == null) {
                return Api.error("没检测到 Vault 经济服务（需要装 Vault 且经济插件已注册）");
            }
            String mode = a1.trim().toLowerCase(Locale.ROOT);
            double amount;
            try {
                amount = Double.parseDouble(a2.trim());
            } catch (NumberFormatException e) {
                return Api.error("金额要填数字");
            }
            if (amount <= 0 || amount > 100000000D) return Api.error("金额要在 0.01 ~ 1 亿之间");
            try {
                Object resp;
                if ("give".equals(mode)) {
                    resp = econ.getClass()
                            .getMethod("depositPlayer", OfflinePlayer.class, double.class)
                            .invoke(econ, (OfflinePlayer) target, amount);
                } else if ("take".equals(mode)) {
                    resp = econ.getClass()
                            .getMethod("withdrawPlayer", OfflinePlayer.class, double.class)
                            .invoke(econ, (OfflinePlayer) target, amount);
                } else {
                    return Api.error("只能是 give 或 take");
                }
                boolean ok = resp != null
                        && Boolean.TRUE.equals(resp.getClass().getMethod("transactionSuccess").invoke(resp));
                String fmt = formatMoney(econ, amount);
                if (!ok) {
                    return Api.error("经济插件拒绝了这次操作（余额不足？）");
                }
                audit(name, ("give".equals(mode) ? "被网页给予 " : "被网页扣除 ") + fmt);
                return Api.okWith(("give".equals(mode) ? "已给 " : "已扣 ") + name + " " + fmt);
            } catch (Throwable t) {
                plugin.getLogger().warning("[操作] 经济操作失败：" + t);
                return Api.error("执行失败（详情见服务器日志）");
            }
        }

        if ("command".equals(action)) {
            String tmpl = a1.trim();
            if (tmpl.isEmpty()) return Api.error("命令内容不能为空");
            int sp = tmpl.indexOf(' ');
            String cmdName = (sp > 0 ? tmpl.substring(0, sp) : tmpl)
                    .toLowerCase(Locale.ROOT);
            if (cmdName.startsWith("/")) cmdName = cmdName.substring(1);
            int colon = cmdName.indexOf(':');
            if (colon >= 0) cmdName = cmdName.substring(colon + 1);

            List<String> wl = cfg().actionCommandWhitelist;
            boolean allowed = false;
            for (String w : wl) {
                if (w != null && w.trim().equalsIgnoreCase(cmdName)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return Api.error("命令 " + cmdName + " 不在白名单里");
            }
            String cmd = tmpl.replace("{player}", name);
            if (cmd.startsWith("/")) cmd = cmd.substring(1);
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            audit(name, "网页执行命令：" + cmd + (ok ? "" : "（返回 false）"));
            return ok ? Api.okWith("已执行：/" + cmd) : Api.error("命令执行返回 false：/" + cmd);
        }

        return Api.error("不认识的操作：" + action);
    }

    /**
     * 清掉负面效果，保留正面效果（力量、加速这些不动的）。
     *
     * <p><b>为什么用名字字符串比对而不是 Enum 常量</b>：
     * {@code PotionEffectType.BAD_OMEN} / {@code UNLUCK} 都是 1.14 才加入的常量，
     * 用 1.13 的 API 编译时根本不存在，写 {@code type == PotionEffectType.BAD_OMEN}
     * 会直接编译不过。字符串比对既能编译，又能在各版本上正确识别。
     */
    private void removeHarmfulEffects(Player p) {
        try {
            for (org.bukkit.potion.PotionEffect eff : p.getActivePotionEffects()) {
                org.bukkit.potion.PotionEffectType t = eff.getType();
                if (t == null) {
                    continue;
                }
                String n = t.getName() == null ? "" : t.getName().toLowerCase(Locale.ROOT);
                if (n.equals("poison") || n.equals("wither") || n.equals("slow")
                        || n.equals("slow_digging") || n.equals("mining_fatigue")
                        || n.equals("weakness") || n.equals("hunger")
                        || n.equals("confusion") || n.equals("nausea")
                        || n.equals("blindness") || n.equals("unluck")
                        || n.equals("bad_omen") || n.equals("darkness")
                        || n.equals("levitation")) {
                    p.removePotionEffect(t);
                }
            }
        } catch (Throwable ignored) {
            // 效果类型在不同版本有差异，清不掉就算了，血已经回满了
        }
    }

    // ────────────────────────── 撤销 ──────────────────────────

    /**
     * 登记一条可撤销记录，返回 id（前端拿它显示「撤销」按钮）。
     *
     * <p>只登记**能反着做**的操作：给了 OP 就撤 OP、清了背包就把内容放回去、传走了就传回来。
     * 删掉的东西（掉落物实体）没法还原，就不给撤销，免得给个假按钮。
     */
    /**
     * 登记一条可撤销记录（给 WorldHandler 这类外部调用方用）。
     */
    public String remember(String player, String label, Callable<String> undo) {
        return pushUndo(player, label, undo);
    }

    private String pushUndo(String player, String label, Callable<String> undo) {
        long now = System.currentTimeMillis();
        // 顺手清掉过期的
        java.util.Iterator<Undo> it = undos.iterator();
        while (it.hasNext()) {
            if (it.next().expires < now) {
                it.remove();
            }
        }
        while (undos.size() >= UNDO_MAX) {
            undos.removeFirst();
        }
        String id = Long.toHexString(System.nanoTime());
        undos.addLast(new Undo(id, player, label, now + UNDO_TTL_MS, undo));
        return id;
    }

    /**
     * 网页上点某条处罚记录的「解除」。
     *
     * <p>禁言直接放人；封禁走 pardon（会写回 banned-players.json）。
     */
    public String revokePunishment(final String id) {
        if (id == null || id.trim().isEmpty()) {
            return Api.error("缺少 id");
        }
        return onMain(new Callable<String>() {
            @Override
            public String call() {
                com.tinyaii.tinywatch.moderation.Punishments.Record r =
                        plugin.punishments().find(id.trim());
                if (r == null) {
                    return Api.error("找不到这条记录");
                }
                if (r.revoked) {
                    return Api.error("这条已经解除过了");
                }
                plugin.punishments().revokeById(r.id);
                if ("MUTE".equalsIgnoreCase(r.type)) {
                    Player p = Bukkit.getPlayerExact(r.name);
                    if (p != null) {
                        plugin.guard().mute(p, 0);
                    }
                    audit(r.name, "处罚记录解除（禁言）");
                    return Api.okWith("已解除 " + r.name + " 的禁言");
                }
                Bukkit.getBanList(BanList.Type.NAME).pardon(r.name);
                audit(r.name, "处罚记录解除（封禁）");
                return Api.okWith("已解除 " + r.name + " 的封禁");
            }
        });
    }

    /** 执行撤销（网页 POST /api/undo）。 */
    public String undo(final String id) {
        if (id == null || id.trim().isEmpty()) {
            return Api.error("缺少 id");
        }
        return onMain(new Callable<String>() {
            @Override
            public String call() {
                long now = System.currentTimeMillis();
                java.util.Iterator<Undo> it = undos.iterator();
                while (it.hasNext()) {
                    Undo u = it.next();
                    if (u.id.equals(id.trim())) {
                        it.remove();
                        if (u.expires < now) {
                            return Api.error("这条操作已经超过 5 分钟，不能撤销了");
                        }
                        try {
                            String r = u.action.call();
                            audit(u.player, "撤销了「" + u.label + "」");
                            return r;
                        } catch (Throwable t) {
                            plugin.getLogger().warning("[操作] 撤销失败：" + t);
                            return Api.error("撤销失败（详情见服务器日志）");
                        }
                    }
                }
                return Api.error("找不到这条记录（可能已过期或刷新过）");
            }
        });
    }

    // ────────────────────────── 小工具 ──────────────────────────

    /**
     * 修一格物品的耐久，返回修了几件。
     *
     * <p>用 {@code Damageable} 而不是老的 {@code ItemMeta.setDamage}：
     * 后者在 1.13 之后就废了，但 Damageable 从 1.13 起就有，正好卡在支持下限上。
     */
    private int repairOne(ItemStack stack, Player owner, int slot) {
        if (stack == null || stack.getType() == Material.AIR) {
            return 0;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable)) {
            return 0;
        }
        Damageable d = (Damageable) meta;
        if (d.getDamage() <= 0) {
            return 0;
        }
        d.setDamage(0);
        stack.setItemMeta(meta);
        owner.getInventory().setItem(slot, stack);
        return 1;
    }

    /**
     * 按名字找材质，先认英文常量名，再认中文名（网页上两种都能填）。
     */
    private Material materialOf(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return null;
        }
        String key = s.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            Material m = Material.matchMaterial(key);
            if (m != null) {
                return m;
            }
        } catch (Throwable ignored) {
            // 新版本把 matchMaterial 的签名换过，失败就走中文匹配
        }
        // 中文名 / 带空格的英文名：线性扫一遍（只在网页点「给物品」时触发，频率极低）
        try {
            for (Material m : Material.values()) {
                String cn = com.tinyaii.tinywatch.util.Names.of(m);
                if (cn != null && cn.equalsIgnoreCase(s)) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
            // 找不到就算了
        }
        return null;
    }

    /**
     * 按名字找药水效果。
     *
     * <p>先试静态字段（SPEED / JUMP_BOOST …），再试 {@code getByName}。
     * 两条都失败就返回 null —— 因为不同版本对这些名字改过好几轮，
     * 写死任何一个都可能在新版上炸。
     */
    private PotionEffectType effectOf(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        String k = key.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        // 老版本叫 JUMP / SLOW，新版本叫 JUMP_BOOST / SLOWNESS，两个都试
        String[] candidates = {k, alias(k)};
        for (String c : candidates) {
            if (c == null) continue;
            try {
                Field f = PotionEffectType.class.getField(c);
                Object v = f.get(null);
                if (v instanceof PotionEffectType) {
                    return (PotionEffectType) v;
                }
            } catch (Throwable ignored) {
                // 换下一个
            }
            try {
                java.lang.reflect.Method m = PotionEffectType.class.getMethod("getByName", String.class);
                Object v = m.invoke(null, c);
                if (v instanceof PotionEffectType) {
                    return (PotionEffectType) v;
                }
            } catch (Throwable ignored) {
                // 换下一个
            }
        }
        return null;
    }

    private static String alias(String k) {
        if ("JUMP".equals(k)) return "JUMP_BOOST";
        if ("JUMP_BOOST".equals(k)) return "JUMP";
        if ("SLOW".equals(k)) return "SLOWNESS";
        if ("SLOWNESS".equals(k)) return "SLOW";
        if ("HASTE".equals(k)) return "FAST_DIGGING";
        if ("FAST_DIGGING".equals(k)) return "HASTE";
        if ("STRENGTH".equals(k)) return "INCREASE_DAMAGE";
        if ("INCREASE_DAMAGE".equals(k)) return "STRENGTH";
        if ("RESISTANCE".equals(k)) return "DAMAGE_RESISTANCE";
        if ("DAMAGE_RESISTANCE".equals(k)) return "RESISTANCE";
        if ("MINING_FATIGUE".equals(k)) return "SLOW_DIGGING";
        return null;
    }

    /** 把 &amp; 颜色码转成 §。 */
    private static String colorize(String s) {
        if (s == null) return "";
        return s.replace('&', '§');
    }

    private static String strip(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    private static String f(Location loc) {
        return loc.getWorld().getName() + " " + Math.round(loc.getX()) + ","
                + Math.round(loc.getY()) + "," + Math.round(loc.getZ());
    }

    /**
     * 发标题。{@code sendTitle} 在新版本里被标了废弃但还在，
     * 万一哪天真的删了，这里会抛 NoSuchMethodError，所以用反射 + 兜底消息。
     */
    private boolean showTitle(Player p, String main, String sub) {
        try {
            p.getClass().getMethod("sendTitle", String.class, String.class,
                    int.class, int.class, int.class).invoke(p, main, sub, 10, 70, 20);
            return true;
        } catch (Throwable ignored) {
            // 退回普通聊天消息
        }
        try {
            p.sendMessage(main);
            if (sub != null && !sub.trim().isEmpty()) {
                p.sendMessage(sub);
            }
        } catch (Throwable ignored) {
            // 实在不行就算了
        }
        return false;
    }

    /** 动作栏：走 Spigot 的 ChatMessageType，失败就退回聊天消息。 */
    private boolean showActionBar(Player p, String text) {
        try {
            p.spigot().sendMessage(
                    net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(text));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 取 Vault 的经济服务；没装 Vault 就返回 null（不报错、不崩）。 */
    private Object vaultEconomy() {
        try {
            Class<?> econCls = Class.forName("net.milkbowl.vault.economy.Economy");
            org.bukkit.plugin.RegisteredServiceProvider<?> rsp =
                    Bukkit.getServicesManager().getRegistration(econCls);
            return rsp == null ? null : rsp.getProvider();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String formatMoney(Object econ, double amount) {
        try {
            Object s = econ.getClass().getMethod("format", double.class).invoke(econ, amount);
            if (s != null) {
                return s.toString();
            }
        } catch (Throwable ignored) {
            // 没有 format 就直接给数字
        }
        return String.valueOf(Math.round(amount * 100D) / 100D);
    }

    private static String gmLabel(GameMode g) {        switch (g) {
            case SURVIVAL:
                return "生存";
            case CREATIVE:
                return "创造";
            case ADVENTURE:
                return "冒险";
            case SPECTATOR:
                return "旁观";
            default:
                return g.name();
        }
    }

    private Player find(String uuid, String fallbackName) {
        if (uuid != null && !uuid.trim().isEmpty()) {
            try {
                Player p = Bukkit.getPlayer(UUID.fromString(uuid.trim()));
                if (p != null && p.isOnline()) {
                    return p;
                }
            } catch (IllegalArgumentException ignored) {
                // 不是合法 UUID，走名字查找
            }
        }
        if (fallbackName != null && !fallbackName.trim().isEmpty()) {
            Player p = Bukkit.getPlayerExact(fallbackName.trim());
            if (p != null && p.isOnline()) {
                return p;
            }
        }
        return null;
    }

    /** 写审计记录。失败绝不影响操作本身。 */
    private void audit(String targetName, String what) {
        try {
            events.addNoPos(System.currentTimeMillis(),
                    targetName == null ? "【网页】" : targetName,
                    "", "ADMIN", what);
        } catch (Throwable ignored) {
            // 日志满了或者缓冲区正在重建，忽略
        }
    }

    // ────────────────────────── 调度 ──────────────────────────

    /**
     * 把任务丢回服务端主线程执行，最多等 3 秒。
     *
     * <p>为什么要有超时：如果服务器卡得很厉害（主线程 5 秒没动），
     * 网页这边不能一直挂着等 —— 否则 SSE 线程和 HTTP 线程一起被拖死。
     */
    public String onMain(Callable<String> task) {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        final Callable<String> c = task;
        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        out[0] = c.call();
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[操作] 执行出错：" + t);
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
                return Api.error("服务器主线程 3 秒没有响应，操作可能已超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Api.error("等待被中断");
        }
        return out[0] == null ? Api.error("没有拿到结果") : out[0];
    }
}
