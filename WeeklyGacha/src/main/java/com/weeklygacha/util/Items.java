package com.weeklygacha.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Tiện ích dựng ItemStack cho GUI. */
public final class Items {

    private Items() {
    }

    public static ItemStack of(Material material, int amount, String name, List<String> lore) {
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.mm(name));
            meta.lore(Text.mmList(lore));
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Sao chép item gốc nhưng thay tên + lore (giữ nguyên model/texture). */
    public static ItemStack decorate(ItemStack base, Component name, List<Component> lore) {
        ItemStack item = base.clone();
        item.setAmount(Math.max(1, Math.min(item.getAmount(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE, 1, " ", List.of());
    }
}
