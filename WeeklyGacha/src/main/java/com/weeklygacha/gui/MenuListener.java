package com.weeklygacha.gui;

import com.weeklygacha.WeeklyGachaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class MenuListener implements Listener {

    private final WeeklyGachaPlugin plugin;

    public MenuListener(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        // Không cho lấy/đặt/shift-click bất cứ thứ gì trong menu
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != top) return;

        MenuAction action = holder.action(event.getSlot());
        if (action == null) return;

        ClickType click = event.getClick();
        // chạy ở tick kế tiếp để việc mở inventory mới không xung đột với sự kiện click hiện tại
        Bukkit.getScheduler().runTask(plugin, () -> action.run(player, click));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }
}
