package com.weeklygacha.model;

import org.bukkit.Material;

import java.util.List;

public final class Banner {

    private final String id;
    private final String displayName;
    private final BannerType type;
    private final Material icon;
    private final List<String> description;
    private final double singleCost;
    private final double tenCost;
    private final PityConfig pity;
    private final List<Reward> rewards;
    private final double totalChance;
    private final List<Reward> pityPool;
    private final List<Reward> featuredPool;

    public Banner(String id, String displayName, BannerType type, Material icon, List<String> description,
                  double singleCost, double tenCost, PityConfig pity, List<Reward> rewards) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.icon = icon;
        this.description = List.copyOf(description);
        this.singleCost = singleCost;
        this.tenCost = tenCost;
        this.pity = pity;
        this.rewards = List.copyOf(rewards);
        this.totalChance = rewards.stream().mapToDouble(Reward::chance).sum();
        this.pityPool = pity.enabled()
                ? rewards.stream().filter(r -> r.rarity() == pity.rarity()).toList()
                : List.of();
        this.featuredPool = pityPool.stream().filter(Reward::featured).toList();
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public BannerType type() { return type; }
    public Material icon() { return icon; }
    public List<String> description() { return description; }
    public double singleCost() { return singleCost; }
    public double tenCost() { return tenCost; }
    public PityConfig pity() { return pity; }
    public List<Reward> rewards() { return rewards; }
    public List<Reward> pityPool() { return pityPool; }
    public List<Reward> featuredPool() { return featuredPool; }

    /** Có cơ chế "lần sau chắc chắn nổi bật" hay không. */
    public boolean hasGuarantee() {
        return pity.enabled() && pity.guaranteeFeatured() && !featuredPool.isEmpty();
    }

    /** Tỷ lệ cơ bản (%) sau khi chuẩn hoá theo tổng. */
    public double ratePercent(Reward r) {
        return totalChance <= 0 ? 0 : r.chance() / totalChance * 100.0;
    }

    public Reward find(String rewardId) {
        for (Reward r : rewards) {
            if (r.id().equals(rewardId)) return r;
        }
        return null;
    }
}
