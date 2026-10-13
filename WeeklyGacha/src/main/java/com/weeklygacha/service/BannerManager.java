package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.BannerType;
import com.weeklygacha.model.PityConfig;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.model.Reward;
import com.weeklygacha.model.RewardProvider;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Nạp banner từ config và quản lý chu kỳ xoay vòng.
 *
 * <p>Banner NORMAL luôn mở. Banner LIMITED xoay vòng: chu kỳ thứ n (n bắt đầu từ 0) dùng
 * {@code rotation[n % rotation.size()]}, mỗi chu kỳ dài {@code cycle.length-days} ngày kể từ mốc bắt đầu.
 * Admin có thể ghi đè banner LIMITED hiện tại bằng tay.
 */
public final class BannerManager {

    private final WeeklyGachaPlugin plugin;
    private final Map<String, Banner> banners = new LinkedHashMap<>();
    private final List<Banner> rotation = new ArrayList<>();

    private long cycleMillis = 14L * 24 * 60 * 60 * 1000;
    private ZoneId zone = ZoneId.systemDefault();
    private long configStart = -1;
    private boolean clearOverrideOnNewCycle = true;

    // trạng thái lưu trong DB (bảng meta)
    private volatile Long metaStart;
    private volatile String overrideId;
    private volatile int overrideCycle = -1;

    public BannerManager(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ nạp

    /** Nạp (hoặc nạp lại) banner + thông số chu kỳ từ config. */
    public void load() {
        banners.clear();
        rotation.clear();
        FileConfiguration cfg = plugin.getConfig();

        int days = Math.max(1, cfg.getInt("cycle.length-days", 14));
        cycleMillis = days * 24L * 60 * 60 * 1000;
        try {
            zone = ZoneId.of(cfg.getString("cycle.timezone", "Asia/Ho_Chi_Minh"));
        } catch (DateTimeException e) {
            plugin.getLogger().warning("cycle.timezone không hợp lệ, dùng múi giờ của máy chủ.");
            zone = ZoneId.systemDefault();
        }
        clearOverrideOnNewCycle = cfg.getBoolean("cycle.clear-override-on-new-cycle", true);
        configStart = parseDate(cfg.getString("cycle.start-date", ""));

        ConfigurationSection sec = cfg.getConfigurationSection("banners");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                Banner b = parseBanner(id.toLowerCase(Locale.ROOT), s);
                if (b != null) banners.put(b.id(), b);
            }
        }

        for (String id : cfg.getStringList("cycle.rotation")) {
            Banner b = banners.get(id.toLowerCase(Locale.ROOT));
            if (b != null && b.type() == BannerType.LIMITED) {
                rotation.add(b);
            } else {
                plugin.getLogger().warning("cycle.rotation: '" + id + "' không tồn tại hoặc không phải LIMITED.");
            }
        }
        if (rotation.isEmpty()) {
            for (Banner b : banners.values()) {
                if (b.type() == BannerType.LIMITED) rotation.add(b);
            }
        }
        plugin.getLogger().info("Đã nạp " + banners.size() + " banner (" + rotation.size() + " banner trong vòng xoay).");
    }

    /** Đọc mốc chu kỳ + ghi đè thủ công đã lưu trong DB. Gọi một lần lúc khởi động. */
    public void loadState() {
        try {
            String s = plugin.database().getMeta("cycle_start");
            metaStart = s == null ? null : Long.parseLong(s);
            overrideId = plugin.database().getMeta("override_banner");
            String oc = plugin.database().getMeta("override_cycle");
            overrideCycle = oc == null ? -1 : Integer.parseInt(oc);
        } catch (Exception e) {
            plugin.getLogger().warning("Không đọc được trạng thái chu kỳ: " + e.getMessage());
        }
    }

    /** Nếu chưa có mốc bắt đầu hợp lệ thì lấy thời điểm hiện tại và lưu lại. */
    public void ensureStart() {
        if (metaStart == null && configStart < 0) {
            long now = System.currentTimeMillis();
            metaStart = now;
            plugin.database().setMeta("cycle_start", String.valueOf(now));
            plugin.getLogger().info("cycle.start-date để trống: chu kỳ đầu tiên bắt đầu từ bây giờ.");
        }
    }

    private Banner parseBanner(String id, ConfigurationSection s) {
        BannerType type = "LIMITED".equalsIgnoreCase(s.getString("type", "NORMAL")) ? BannerType.LIMITED : BannerType.NORMAL;
        String name = s.getString("display-name", id);
        Material icon = material(s.getString("icon", "NETHER_STAR"), Material.NETHER_STAR);
        List<String> desc = s.getStringList("description");
        double single = Math.max(0, s.getDouble("cost.single", 1000));
        double ten = Math.max(0, s.getDouble("cost.ten", single * 10));

        List<Reward> rewards = new ArrayList<>();
        ConfigurationSection rs = s.getConfigurationSection("rewards");
        if (rs != null) {
            for (String rid : rs.getKeys(false)) {
                Reward r = parseReward(id, rid, rs.getConfigurationSection(rid));
                if (r != null) rewards.add(r);
            }
        }
        if (rewards.isEmpty()) {
            plugin.getLogger().warning("Banner '" + id + "' không có vật phẩm hợp lệ nên bị bỏ qua.");
            return null;
        }
        double sum = rewards.stream().mapToDouble(Reward::chance).sum();
        if (Math.abs(sum - 100.0) > 0.01) {
            plugin.getLogger().warning(String.format(Locale.ROOT,
                    "Banner '%s': tổng tỷ lệ = %.3f%% (khác 100%%). Plugin sẽ tự chuẩn hoá.", id, sum));
        }

        Rarity pityRarity = Rarity.parse(s.getString("pity.rarity", "LEGENDARY"), Rarity.LEGENDARY);
        boolean pityEnabled = s.getBoolean("pity.enabled", true);
        int hard = Math.max(1, s.getInt("pity.hard", 90));
        int soft = Math.min(hard, Math.max(1, s.getInt("pity.soft-start", Math.max(1, hard - 16))));
        double boost = Math.max(0, s.getDouble("pity.soft-boost", 0.5));
        boolean guarantee = s.getBoolean("pity.guarantee-featured", type == BannerType.LIMITED);
        if (pityEnabled && rewards.stream().noneMatch(r -> r.rarity() == pityRarity)) {
            plugin.getLogger().warning("Banner '" + id + "': không có vật phẩm độ hiếm " + pityRarity + ", tắt pity.");
            pityEnabled = false;
        }
        PityConfig pity = new PityConfig(pityEnabled, hard, soft, boost, pityRarity, guarantee);
        return new Banner(id, name, type, icon, desc, single, ten, pity, rewards);
    }

    private Reward parseReward(String bannerId, String id, ConfigurationSection s) {
        String where = "Banner '" + bannerId + "' vật phẩm '" + id + "': ";
        if (s == null) return null;
        RewardProvider provider = RewardProvider.parse(s.getString("provider", "VANILLA"));
        if (provider == null) {
            plugin.getLogger().warning(where + "provider không hợp lệ.");
            return null;
        }
        double chance = s.getDouble("chance", 0);
        if (chance <= 0) {
            plugin.getLogger().warning(where + "chance phải > 0.");
            return null;
        }
        String itemId = s.getString("item");
        String itemType = s.getString("type");
        List<String> commands = s.getStringList("commands");

        Material icon;
        switch (provider) {
            case VANILLA -> {
                Material m = itemId == null ? null : Material.matchMaterial(itemId);
                if (m == null || !m.isItem()) {
                    plugin.getLogger().warning(where + "Material '" + itemId + "' không hợp lệ.");
                    return null;
                }
                icon = m;
            }
            case MMOITEMS -> {
                if (itemId == null || itemType == null) {
                    plugin.getLogger().warning(where + "MMOITEMS cần cả 'type' và 'item'.");
                    return null;
                }
                icon = material(s.getString("icon", "IRON_SWORD"), Material.CHEST);
            }
            case ITEMSADDER -> {
                if (itemId == null) {
                    plugin.getLogger().warning(where + "ITEMSADDER cần 'item' (namespace:id).");
                    return null;
                }
                icon = material(s.getString("icon", "PAPER"), Material.PAPER);
            }
            default -> {
                if (commands.isEmpty()) {
                    plugin.getLogger().warning(where + "COMMAND cần danh sách 'commands'.");
                    return null;
                }
                icon = material(s.getString("icon", "CHEST"), Material.CHEST);
            }
        }

        return new Reward(
                id,
                provider,
                itemType,
                itemId,
                Math.max(1, s.getInt("amount", 1)),
                Rarity.parse(s.getString("rarity", "COMMON"), Rarity.COMMON),
                chance,
                s.getBoolean("featured", false),
                s.getString("name"),
                s.getStringList("lore"),
                icon,
                commands,
                s.contains("broadcast") ? s.getBoolean("broadcast") : null);
    }

    private static Material material(String name, Material def) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null ? m : def;
    }

    /** yyyy-MM-dd hoặc "yyyy-MM-dd HH:mm" theo múi giờ cấu hình; -1 nếu không hợp lệ/để trống. */
    public long parseDate(String s) {
        if (s == null || s.isBlank()) return -1;
        try {
            String t = s.trim();
            LocalDateTime ldt = t.length() <= 10
                    ? LocalDate.parse(t).atStartOfDay()
                    : LocalDateTime.parse(t.replace(' ', 'T'));
            return ldt.atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("Không đọc được ngày '" + s + "' (dùng yyyy-MM-dd hoặc yyyy-MM-dd HH:mm).");
            return -1;
        }
    }

    // ------------------------------------------------------------------ truy vấn banner

    public Banner get(String id) {
        return id == null ? null : banners.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Banner> all() {
        return List.copyOf(banners.values());
    }

    public List<Banner> rotation() {
        return List.copyOf(rotation);
    }

    /** Banner đang mở: banner giới hạn hiện tại (nếu có) đứng đầu, sau đó các banner thường. */
    public List<Banner> activeBanners() {
        List<Banner> out = new ArrayList<>();
        Banner limited = currentLimited();
        if (limited != null) out.add(limited);
        for (Banner b : banners.values()) {
            if (b.type() == BannerType.NORMAL) out.add(b);
        }
        return out;
    }

    public boolean isActive(Banner banner) {
        if (banner == null) return false;
        if (banner.type() == BannerType.NORMAL) return banners.containsKey(banner.id());
        Banner limited = currentLimited();
        return limited != null && limited.id().equals(banner.id());
    }

    // ------------------------------------------------------------------ chu kỳ

    public long startMillis() {
        Long m = metaStart;
        return m != null ? m : configStart;
    }

    public int cycleIndex() {
        long diff = System.currentTimeMillis() - startMillis();
        if (diff < 0) return 0;
        return (int) (diff / cycleMillis);
    }

    /** Số thứ tự chu kỳ bắt đầu từ 1 (dùng cho lịch sử). */
    public int cycleNumber() {
        return cycleIndex() + 1;
    }

    public long endMillis() {
        return startMillis() + (long) (cycleIndex() + 1) * cycleMillis;
    }

    public long remainingMillis() {
        return Math.max(0, endMillis() - System.currentTimeMillis());
    }

    public int lengthDays() {
        return (int) (cycleMillis / (24L * 60 * 60 * 1000));
    }

    public ZoneId zone() {
        return zone;
    }

    /** Banner theo vòng xoay tự động. */
    public Banner autoLimited() {
        if (rotation.isEmpty()) return null;
        return rotation.get(Math.floorMod(cycleIndex(), rotation.size()));
    }

    /** Banner giới hạn đang mở (tính cả ghi đè thủ công). */
    public Banner currentLimited() {
        String ov = overrideId;
        if (ov != null) {
            Banner b = banners.get(ov);
            if (b != null && b.type() == BannerType.LIMITED
                    && (!clearOverrideOnNewCycle || overrideCycle == cycleIndex())) {
                return b;
            }
        }
        return autoLimited();
    }

    public boolean hasOverride() {
        String ov = overrideId;
        return ov != null && banners.containsKey(ov)
                && (!clearOverrideOnNewCycle || overrideCycle == cycleIndex());
    }

    /** Chữ ký trạng thái để phát hiện khi banner/chu kỳ thay đổi. */
    public String signature() {
        Banner b = currentLimited();
        return cycleIndex() + ":" + (b == null ? "-" : b.id());
    }

    /** Dọn ghi đè đã hết hạn (sang chu kỳ mới). */
    public void refresh() {
        if (overrideId != null && clearOverrideOnNewCycle && overrideCycle != cycleIndex()) {
            clearOverride();
        }
    }

    // ------------------------------------------------------------------ lệnh admin

    public void setOverride(Banner banner) {
        overrideId = banner.id();
        overrideCycle = cycleIndex();
        plugin.database().setMeta("override_banner", overrideId);
        plugin.database().setMeta("override_cycle", String.valueOf(overrideCycle));
    }

    public void clearOverride() {
        overrideId = null;
        overrideCycle = -1;
        plugin.database().setMeta("override_banner", null);
        plugin.database().setMeta("override_cycle", null);
    }

    /** Chuyển sang banner kế tiếp trong vòng xoay so với banner đang mở. */
    public Banner next() {
        if (rotation.isEmpty()) return null;
        Banner current = currentLimited();
        int idx = current == null ? -1 : rotation.indexOf(current);
        Banner next = rotation.get((idx + 1) % rotation.size());
        setOverride(next);
        return next;
    }

    /** Đặt mốc bắt đầu chu kỳ mới; null = quay về giá trị trong config. Luôn xoá ghi đè. */
    public void setCycleStart(Long millis) {
        metaStart = millis;
        plugin.database().setMeta("cycle_start", millis == null ? null : String.valueOf(millis));
        clearOverride();
        ensureStart();
    }
}
