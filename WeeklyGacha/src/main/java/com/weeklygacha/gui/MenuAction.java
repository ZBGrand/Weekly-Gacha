package com.weeklygacha.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

@FunctionalInterface
public interface MenuAction {

    void run(Player player, ClickType click);
}
