package com.weeklygacha.listener;

import com.weeklygacha.WeeklyGachaPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.logging.Level;

/** Nạp dữ liệu pity khi đăng nhập (ngoài luồng chính) và dọn bộ nhớ khi thoát. */
public final class PlayerListener implements Listener {

    private final WeeklyGachaPlugin plugin;

    public PlayerListener(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            plugin.players().put(event.getUniqueId(), plugin.database().loadPlayer(event.getUniqueId()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Không nạp được dữ liệu gacha của " + event.getName(), e);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // phòng khi pre-login thất bại hoặc plugin được nạp lại lúc đang chạy
        if (!plugin.players().containsKey(uuid)) {
            plugin.loadPlayerAsync(uuid);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.players().remove(uuid);
        plugin.gacha().forget(uuid);
        plugin.menu().forget(uuid);
    }
}
