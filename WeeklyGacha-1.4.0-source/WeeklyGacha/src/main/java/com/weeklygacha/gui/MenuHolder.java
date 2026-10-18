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

    int size() {
        return inventory.getSize();
    }

    void fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, item);
        }
    }

    /** Đặt item vào ô; bỏ qua nếu slot nằm ngoài GUI (cấu hình gui.yml sai) thay vì gây lỗi. */
    void set(int slot, ItemStack item, MenuAction action) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    MenuAction action(int slot) {
        return actions.get(slot);
    }
}
