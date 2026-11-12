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
    /** % theo độ hiếm (đã cộng phần thiếu cho độ hiếm thấp nhất); null = kiểu cũ (chance tự chuẩn hoá). */
    private final java.util.Map<Rarity, Double> tierRates;
    /** % thật của từng vật phẩm. */
    private final java.util.Map<Reward, Double> effective = new java.util.HashMap<>();
    private final List<Reward> topPool;
    private final List<Reward> featuredPool;
    private final List<Reward> sparkPool;

    public Banner(String id, String displayName, BannerType type, Material icon, List<String> description,
                  CostConfig cost, SparkConfig spark, int maxPulls, Rarity alwaysRarity, List<Reward> rewards) {
        this(id, displayName, type, icon, description, cost, spark, maxPulls, alwaysRarity, rewards, null);
    }

    /**
     * @param tierRates % cho từng độ hiếm (tổng = 100). Các vật phẩm cùng độ hiếm chia phần đó theo
     *                  {@code chance} của chúng (ở chế độ này {@code chance} là trọng số "weight", mặc định 1).
     */
    public Banner(String id, String displayName, BannerType type, Material icon, List<String> description,
                  CostConfig cost, SparkConfig spark, int maxPulls, Rarity alwaysRarity, List<Reward> rewards,
                  java.util.Map<Rarity, Double> tierRates) {
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
        this.tierRates = tierRates == null ? null : java.util.Collections.unmodifiableMap(new java.util.EnumMap<>(tierRates));
        java.util.Map<Rarity, Double> weightSum = new java.util.EnumMap<>(Rarity.class);
        for (Reward r : this.rewards) weightSum.merge(r.rarity(), r.chance(), Double::sum);
        for (Reward r : this.rewards) {
            double pct;
            if (this.tierRates == null) pct = totalChance <= 0 ? 0 : r.chance() / totalChance * 100.0;
            else pct = this.tierRates.getOrDefault(r.rarity(), 0.0) * r.chance() / weightSum.get(r.rarity());
            effective.put(r, pct);
        }
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

    /** Tỷ lệ thật (%) của một vật phẩm mỗi lượt quay. */
    public double ratePercent(Reward r) {
        Double v = effective.get(r);
        return v == null ? 0 : v;
    }

    /** % của cả một độ hiếm (tổng các vật phẩm thuộc độ hiếm đó). */
    public double tierPercent(Rarity rarity) {
        double sum = 0;
        for (Reward r : rewards) if (r.rarity() == rarity) sum += ratePercent(r);
        return sum;
    }

    /** true nếu banner đặt tỷ lệ theo độ hiếm (mục "rates" trong banners.yml). */
    public boolean usesTierRates() {
        return tierRates != null;
    }

    public Reward find(String rewardId) {
        for (Reward r : rewards) {
            if (r.id().equals(rewardId)) return r;
        }
        return null;
    }
}
