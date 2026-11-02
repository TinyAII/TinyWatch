package com.tinyaii.tinywatch.listener;

import com.tinyaii.tinywatch.service.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * 玩家上线时提示新版本。
 *
 * <p>延迟 10 秒：版本检查是异步的，刚上线那一刻结果可能还没回来。
 * 只对拥有配置里指定权限（默认 tinygrace.admin）的玩家提示。
 */
public final class UpdateNotifyListener implements Listener {

    private static final long DELAY_TICKS = 200L;

    private final Plugin plugin;
    private final UpdateChecker updateChecker;

    public UpdateNotifyListener(Plugin plugin, UpdateChecker updateChecker) {
        this.plugin = plugin;
        this.updateChecker = updateChecker;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline() && updateChecker != null) {
                    updateChecker.notifyPlayer(player);
                }
            }
        }, DELAY_TICKS);
    }
}
