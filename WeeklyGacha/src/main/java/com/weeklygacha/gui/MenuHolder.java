package com.weeklygacha.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Holder đánh dấu inventory của plugin + lưu hành động theo từng slot. */
public final class MenuHolder implements InventoryHolder {

    private Inventory inventory;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    void fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, item);
        }
    }

    void set(int slot, ItemStack item, MenuAction action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    MenuAction action(int slot) {
        return actions.get(slot);
    }
}
