package com.weeklygacha.model;

import org.bukkit.Material;

import java.util.List;

public final class Banner {

    private final String id;
    private final String displayName;
    private final BannerType type;
    private final Material icon;
    private final List<String> description;
    private final CostConfig cost;
    private final SparkConfig spark;
    private final int maxPulls;
    private final Rarity alwaysRarity;
    private final List<Reward> rewards;
    private final double totalChance;
    private final List<Reward> topPool;
    private final List<Reward> featuredPool;
    private final List<Reward> sparkPool;

    public Banner(String id, String displayName, BannerType type, Material icon, List<String> description,
                  CostConfig cost, SparkConfig spark, int maxPulls, Rarity alwaysRarity, List<Reward> rewards) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.icon = icon;
        this.description = List.copyOf(description);
        this.cost = cost;
        this.spark = spark;
        this.maxPulls = Math.max(0, maxPulls);
        this.alwaysRarity = alwaysRarity;
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
    public CostConfig cost() { return cost; }
    public int singleCost() { return cost.single(); }
    public int tenCost() { return cost.ten(); }
    /** Chi phí của {@code count} lượt (10 lượt dùng giá riêng). */
    public int costFor(int count) { return count == 10 ? cost.ten() : cost.single() * count; }
    public SparkConfig spark() { return spark; }
    /** Số lượt tối đa mỗi người chơi được quay trên banner này suốt đời (0 = không giới hạn). */
    public int maxPulls() { return maxPulls; }
    public boolean hasPullLimit() { return maxPulls > 0; }
    /** Mọi lượt quay của banner này chắc chắn ra độ hiếm này trở lên (null = không có). */
    public Rarity alwaysRarity() { return alwaysRarity; }
    /** Banner có cho quay x10 không (bị chặn nếu giới hạn lượt nhỏ hơn 10). */
    public boolean allowsTen() { return maxPulls == 0 || maxPulls >= 10; }
    /** Số lượt còn lại của người chơi đã quay {@code used} lượt (Integer.MAX_VALUE nếu không giới hạn). */
    public int pullsLeft(int used) { return maxPulls == 0 ? Integer.MAX_VALUE : Math.max(0, maxPulls - used); }
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
