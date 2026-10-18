package com.weeklygacha.model;

import org.bukkit.inventory.ItemStack;

/**
 * Kết quả một lượt quay.
 *
 * @param item ItemStack đã trao (null nếu phần thưởng là COMMAND)
 */
public record PullEntry(Reward reward, int pityAtPull, ItemStack item) {
}
