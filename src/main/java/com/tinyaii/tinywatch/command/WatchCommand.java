package com.tinyaii.tinywatch.command;

import com.tinyaii.tinywatch.TinyWatch;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /watch —— 管理指令。
 *
 * <p>只有 op 能用。所有输出都带中文，子命令中英文都认。
 */
public final class WatchCommand implements CommandExecutor, TabCompleter {

    private final TinyWatch plugin;

    public WatchCommand(TinyWatch plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("tinywatch.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限使用这个命令。");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase();

        if (match(sub, "status", "状态", "信息")) {
            status(sender);
            return true;
        }
        if (match(sub, "reload", "重载", "reloadconfig")) {
            plugin.reloadAll();
            sender.sendMessage(ChatColor.GREEN + "✔ 配置已重载。");
            if (plugin.web() != null && plugin.web().isRunning()) {
                sender.sendMessage(ChatColor.GRAY + "  网页面板：http://"
                        + plugin.web().boundHost() + ":" + plugin.web().boundPort() + "/");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "  注意：网页面板当前没有运行。");
            }
            return true;
        }
        if (match(sub, "password", "密码", "查看密码")) {
            showPassword(sender);
            return true;
        }
        if (match(sub, "newpassword", "新密码", "重置密码")) {
            resetPassword(sender);
            return true;
        }
        if (match(sub, "clear", "清空", "清空日志", "clearlog")) {
            plugin.events().clear();
            sender.sendMessage(ChatColor.GREEN + "✔ 操作日志已清空。");
            return true;
        }
        if (match(sub, "help", "帮助", "?")) {
            help(sender, label);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "未知命令：" + args[0]);
        help(sender, label);
        return true;
    }

    private void status(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "========== TinyWatch ==========");
        if (plugin.web() != null && plugin.web().isRunning()) {
            s.sendMessage(ChatColor.YELLOW + "网页面板  " + ChatColor.WHITE
                    + "http://" + plugin.web().boundHost() + ":" + plugin.web().boundPort() + "/");
            s.sendMessage(ChatColor.YELLOW + "实时连接  " + ChatColor.WHITE
                    + plugin.web().sseHub().size() + " 个");
            s.sendMessage(ChatColor.YELLOW + "已登录    " + ChatColor.WHITE
                    + (plugin.web().auth() == null ? 0 : plugin.web().auth().sessionCount()) + " 个会话");
        } else {
            s.sendMessage(ChatColor.RED + "网页面板未运行（看控制台日志里的原因）");
        }
        s.sendMessage(ChatColor.YELLOW + "操作日志  " + ChatColor.WHITE
                + plugin.events().size() + " / " + plugin.events().capacity() + " 条");
        s.sendMessage(ChatColor.YELLOW + "采样间隔  " + ChatColor.WHITE
                + plugin.settings().sampleIntervalTicks + " tick");
        s.sendMessage(ChatColor.DARK_GRAY + "面板只读，不会改动服务器。");
    }

    private void showPassword(CommandSender s) {
        String hash = plugin.settings().webPasswordHash;
        if (hash == null || hash.isEmpty()) {
            s.sendMessage(ChatColor.RED + "当前没有设置密码，网页面板不会启动。用 /watch 新密码 生成一个。");
            return;
        }
        s.sendMessage(ChatColor.GOLD + "网页登录密码已设置。");
        s.sendMessage(ChatColor.GRAY + "服务器上只存哈希，看不到明文（这样配置文件被盗也拿不到密码）。");
        s.sendMessage(ChatColor.GRAY + "忘了就执行 /watch 新密码 重新生成一个，旧密码立刻作废。");
    }

    private void resetPassword(CommandSender s) {
        String np = com.tinyaii.tinywatch.web.Password.random(16);
        plugin.getConfig().set("web.password", "");
        plugin.getConfig().set("web.password-hash",
                com.tinyaii.tinywatch.web.Password.hash(np));
        plugin.saveConfig();
        plugin.settings().reload(plugin.getConfig());

        // 密码变了，所有已登录的会话都必须失效 —— 重建 Web 服务会把 token 全清掉
        if (plugin.web() != null) {
            plugin.web().stop();
            plugin.web().start(plugin.settings());
        }

        s.sendMessage(ChatColor.GREEN + "✔ 新密码已生成，所有已登录的页面需要重新登录。");
        s.sendMessage(ChatColor.GOLD + "新密码：" + ChatColor.WHITE + np);
        s.sendMessage(ChatColor.GRAY + "这一行只显示这一次，服务器上只存哈希。");
        if (s instanceof org.bukkit.entity.Player) {
            s.sendMessage(ChatColor.RED + "⚠ 聊天频道可见，请立刻保存并忘掉这条消息。");
        }
    }

    private String randomPassword() {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private void help(CommandSender s, String label) {
        s.sendMessage(ChatColor.GOLD + "========== TinyWatch 网页监控 ==========");
        row(s, label, "status", "状态", "看面板地址、连接数、日志条数");
        row(s, label, "password", "密码", "查看当前登录密码");
        row(s, label, "newpassword", "新密码", "重置密码（所有人需重新登录）");
        row(s, label, "clear", "清空", "清空内存里的操作日志");
        row(s, label, "reload", "重载", "重载配置");
        s.sendMessage(ChatColor.DARK_GRAY + "中英文都可以，例如 /" + label + " 状态 = /" + label + " status");
        s.sendMessage(ChatColor.DARK_GRAY + "面板只读：不杀实体、不改方块、不踢人。");
    }

    private void row(CommandSender s, String label, String en, String cn, String desc) {
        s.sendMessage(ChatColor.YELLOW + "/" + label + " " + en
                + ChatColor.DARK_GRAY + "  |  "
                + ChatColor.YELLOW + "/" + label + " " + cn
                + ChatColor.GRAY + "  " + desc);
    }

    private static boolean match(String sub, String... names) {
        for (String n : names) {
            if (n.equals(sub)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("tinywatch.admin")) {
            return new ArrayList<String>();
        }
        if (args.length == 1) {
            List<String> all = Arrays.asList(
                    "status", "reload", "password", "newpassword", "clear", "help",
                    "状态", "重载", "密码", "新密码", "清空", "帮助");
            List<String> out = new ArrayList<String>();
            String prefix = args[0].toLowerCase();
            for (String s : all) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        return new ArrayList<String>();
    }
}
