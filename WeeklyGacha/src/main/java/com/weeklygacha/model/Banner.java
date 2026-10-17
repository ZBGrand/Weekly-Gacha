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
    private final SparkConfig spark;
    private final List<Reward> rewards;
    private final double totalChance;
    private final List<Reward> topPool;
    private final List<Reward> featuredPool;
    private final List<Reward> sparkPool;

    public Banner(String id, String displayName, BannerType type, Material icon, List<String> description,
                  double singleCost, double tenCost, SparkConfig spark, List<Reward> rewards) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.icon = icon;
        this.description = List.copyOf(description);
        this.singleCost = singleCost;
        this.tenCost = tenCost;
        this.spark = spark;
        this.rewards = List.copyOf(rewards);
        this.totalChance = rewards.stream().mapToDouble(Reward::chance).sum();
        this.topPool = rewards.stream().filter(r -> r.rarity() == spark.rarity()).toList();
        this.featuredPool = topPool.stream().filter(Reward::featured).toList();
        // Vật phẩm cho phép đổi bằng spark: ưu tiên featured, không có thì mọi vật phẩm độ hiếm đỉnh
        this.sparkPool = !spark.enabled() ? List.of() : (featuredPool.isEmpty() ? topPool : featuredPool);
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public BannerType type() { return type; }
    public Material icon() { return icon; }
    public List<String> description() { return description; }
    public double singleCost() { return singleCost; }
    public double tenCost() { return tenCost; }
    public SparkConfig spark() { return spark; }
    public List<Reward> rewards() { return rewards; }
    /** Mọi vật phẩm thuộc độ hiếm đỉnh của banner. */
    public List<Reward> topPool() { return topPool; }
    public List<Reward> featuredPool() { return featuredPool; }
    /** Vật phẩm có thể chọn khi đổi spark (rỗng nếu spark tắt). */
    public List<Reward> sparkPool() { return sparkPool; }

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
