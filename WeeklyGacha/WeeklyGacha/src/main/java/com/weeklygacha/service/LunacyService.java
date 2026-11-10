package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lunacy - đơn vị tiền riêng của WeeklyGacha.
 *
 * <p>Số dư lưu trong data.db (bảng {@code lunacy}, mọi thay đổi ghi vào {@code lunacy_log}).
 * Người chơi đang online có bản sao trong bộ nhớ ({@link PlayerData#lunacy()}) để GUI và PlaceholderAPI đọc ngay;
 * mọi thay đổi được ghi xuống DB dưới dạng cộng/trừ chênh lệch theo đúng thứ tự, nên cộng cho người chơi offline
 * (vd plugin vote chạy lệnh lúc người chơi chưa vào) cũng an toàn. Các thao tác thay đổi số dư được đồng bộ
 * (synchronized) để bản trong bộ nhớ không bị ghi đè bởi kết quả DB cũ.
 */
public final class LunacyService {

    /** Một gói trong cửa hàng (config.yml, lunacy.shop.packages). */
    public record ShopPackage(String id, long amount, long bonus, int price, int slot, Material material,
                              String name, List<String> lore, boolean glow) {
        public long total() {
            return amount + bonus;
        }
    }

    private final WeeklyGachaPlugin plugin;
    private final Map<UUID, AtomicInteger> inflight = new ConcurrentHashMap<>();
    private final Map<String, ShopPackage> packages = new LinkedHashMap<>();
    private volatile List<String[]> top = List.of();

    public LunacyService(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
        reload();
        // bảng xếp hạng cho PlaceholderAPI: làm mới mỗi phút
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshTop, 40L, 20L * 60);
    }

    public void reload() {
        packages.clear();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("lunacy.shop.packages");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection p = s.getConfigurationSection(id);
            if (p == null) continue;
            long amount = p.getLong("amount", 0);
            int price = p.getInt("price", 0);
            if (amount <= 0 || price <= 0) {
                plugin.getLogger().warning("config.yml lunacy.shop.packages." + id + ": amount và price phải > 0. Bỏ qua gói.");
                continue;
            }
            Material m = Material.matchMaterial(p.getString("material", "AMETHYST_SHARD"));
            packages.put(id.toLowerCase(Locale.ROOT), new ShopPackage(id, amount, Math.max(0, p.getLong("bonus", 0)),
                    price, p.getInt("slot", -1), m == null || !m.isItem() ? Material.AMETHYST_SHARD : m,
                    p.getString("name"), p.getStringList("lore"), p.getBoolean("glow", false)));
        }
    }

    // ------------------------------------------------------------------ cấu hình hiển thị

    /** Tên đơn vị (MiniMessage), vd "<#c9a7ff>Lunacy". */
    public String currencyName() {
        return plugin.getConfig().getString("lunacy.name", "<#c9a7ff>Lunacy");
    }

    public String symbol() {
        return plugin.getConfig().getString("lunacy.symbol", "☾");
    }

    public Material icon() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("lunacy.icon", "AMETHYST_SHARD"));
        return m == null || !m.isItem() ? Material.AMETHYST_SHARD : m;
    }

    /** 12345 -> "12,345" (dấu phân cách trong config.yml, lunacy.thousands-separator). */
    public String number(long amount) {
        String sep = plugin.getConfig().getString("lunacy.thousands-separator", ",");
        if (sep.isEmpty()) return String.valueOf(amount);
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(sep.charAt(0));
        return new DecimalFormat("#,##0", sym).format(amount);
    }

    /** 1234 -> "1.2K", 2500000 -> "2.5M". */
    public String shortNumber(long amount) {
        if (amount < 1000) return String.valueOf(amount);
        String[] units = {"K", "M", "B", "T"};
        double v = amount;
        int i = -1;
        while (v >= 1000 && i < units.length - 1) {
            v /= 1000;
            i++;
        }
        String s = String.format(Locale.ROOT, v >= 100 ? "%.0f" : "%.1f", v);
        if (s.equals("1000") && i < units.length - 1) {
            s = "1";
            i++;
        }
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + units[i];
    }

    /** Văn bản thuần, vd "1,200 ☾" (mẫu: gui.yml, format.lunacy). */
    public String plain(long amount) {
        return plugin.gui().lunacy(number(amount), symbol());
    }

    // ------------------------------------------------------------------ số dư

    /** Số dư của người chơi đã được nạp dữ liệu (online); -1 nếu chưa nạp. */
    public long balance(UUID uuid) {
        PlayerData d = plugin.players().get(uuid);
        return d == null ? -1 : d.lunacy().get();
    }

    /** Số dư đọc thẳng từ DB (dùng cho người chơi offline). */
    public CompletableFuture<Long> balanceAsync(UUID uuid) {
        PlayerData d = plugin.players().get(uuid);
        if (d != null) return CompletableFuture.completedFuture(d.lunacy().get());
        return plugin.database().lunacyOf(uuid);
    }

    public boolean has(Player player, long amount) {
        return amount <= 0 || balance(player.getUniqueId()) >= amount;
    }

    /** Trừ Lunacy của người chơi online (luồng chính). false nếu không đủ hoặc chưa nạp dữ liệu - không mất gì. */
    public synchronized boolean take(Player player, long amount, String reason) {
        if (amount <= 0) return true;
        PlayerData d = plugin.players().get(player.getUniqueId());
        if (d == null) return false;
        AtomicLong bal = d.lunacy();
        if (bal.get() < amount) return false;
        bal.addAndGet(-amount);
        write(player.getUniqueId(), player.getName(), -amount, reason, null);
        return true;
    }

    /** Cộng Lunacy (online hoặc offline). */
    public synchronized void give(UUID uuid, String name, long amount, String reason) {
        if (amount <= 0) return;
        PlayerData d = plugin.players().get(uuid);
        if (d != null) d.lunacy().addAndGet(amount);
        write(uuid, name, amount, reason, null);
    }

    /** Trừ Lunacy cho cả người chơi offline (admin); không xuống dưới 0. Trả về số dư mới. */
    public synchronized CompletableFuture<Long> takeAny(UUID uuid, String name, long amount, String reason) {
        PlayerData d = plugin.players().get(uuid);
        if (d != null) d.lunacy().updateAndGet(v -> Math.max(0, v - amount));
        CompletableFuture<Long> f = new CompletableFuture<>();
        write(uuid, name, -amount, reason, f);
        return f;
    }

    /** Đặt số dư (admin). Trả về số dư mới. */
    public synchronized CompletableFuture<Long> set(UUID uuid, String name, long value, String reason) {
        PlayerData d = plugin.players().get(uuid);
        if (d != null) d.lunacy().set(Math.max(0, value));
        AtomicInteger n = inflight.computeIfAbsent(uuid, k -> new AtomicInteger());
        n.incrementAndGet();
        return plugin.database().setLunacy(uuid, name, value, reason).whenComplete((v, ex) -> settle(uuid, v));
    }

    /** Ghi chênh lệch xuống DB; khi không còn thao tác nào đang chờ thì đồng bộ bản trong bộ nhớ theo DB. */
    private void write(UUID uuid, String name, long delta, String reason, CompletableFuture<Long> out) {
        AtomicInteger n = inflight.computeIfAbsent(uuid, k -> new AtomicInteger());
        n.incrementAndGet();
        plugin.database().addLunacy(uuid, name, delta, reason).whenComplete((v, ex) -> {
            settle(uuid, v);
            if (out != null) {
                if (ex != null) out.completeExceptionally(ex);
                else out.complete(v);
            }
        });
    }

    private synchronized void settle(UUID uuid, Long dbValue) {
        AtomicInteger n = inflight.get(uuid);
        if (n == null) return;
        if (n.decrementAndGet() <= 0) {
            inflight.remove(uuid, n);
            if (dbValue != null) {
                PlayerData d = plugin.players().get(uuid);
                if (d != null) d.lunacy().set(dbValue);
            }
        }
    }

    /** Báo cho người chơi online khi nhận Lunacy từ phần thưởng / admin / plugin khác. */
    public void notifyReceived(UUID uuid, long amount, String source) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !plugin.getConfig().getBoolean("lunacy.notify-receive", true)) return;
        long bal = balance(uuid);
        plugin.messages().send(p, "lunacy-received",
                MessageService.ph("amount", plain(amount)),
                MessageService.ph("balance", plain(Math.max(0, bal))),
                MessageService.ph("source", source == null ? "" : source));
    }

    // ------------------------------------------------------------------ cửa hàng (PlayerPoints -> Lunacy)

    public boolean shopEnabled() {
        return plugin.getConfig().getBoolean("lunacy.shop.enabled", true);
    }

    public List<ShopPackage> packages() {
        return new ArrayList<>(packages.values());
    }

    public ShopPackage pack(String id) {
        return id == null ? null : packages.get(id.toLowerCase(Locale.ROOT));
    }

    /** Số điểm PlayerPoints cho 1 Lunacy khi mua số lượng tuỳ ý (/lunacy buy <số>); 0 = tắt. */
    public double pointsPerLunacy() {
        return plugin.getConfig().getDouble("lunacy.shop.custom.points-per-lunacy", 1.0);
    }

    public boolean customEnabled() {
        return plugin.getConfig().getBoolean("lunacy.shop.custom.enabled", true) && pointsPerLunacy() > 0;
    }

    public long customMin() {
        return Math.max(1, plugin.getConfig().getLong("lunacy.shop.custom.min", 10));
    }

    public long customMax() {
        return Math.max(customMin(), plugin.getConfig().getLong("lunacy.shop.custom.max", 100000));
    }

    /** Giá (điểm PlayerPoints) để mua {@code amount} Lunacy theo tỷ lệ tuỳ ý (làm tròn lên). */
    public int customPrice(long amount) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.ceil(amount * pointsPerLunacy()));
    }

    public enum BuyResult { OK, DISABLED, NO_PLAYERPOINTS, NOT_ENOUGH_POINTS, FAILED, NOT_LOADED }

    /**
     * Mua Lunacy bằng PlayerPoints: trừ điểm trước, chỉ cộng Lunacy khi trừ điểm thành công.
     * Gọi trên luồng chính.
     */
    public BuyResult buy(Player player, long lunacy, int price, String reason) {
        if (!shopEnabled()) return BuyResult.DISABLED;
        if (plugin.players().get(player.getUniqueId()) == null) return BuyResult.NOT_LOADED;
        Object api = pointsApi();
        if (api == null) return BuyResult.NO_PLAYERPOINTS;
        if (points(api, player.getUniqueId()) < price) return BuyResult.NOT_ENOUGH_POINTS;
        if (!takePoints(api, player.getUniqueId(), price)) return BuyResult.FAILED;
        give(player.getUniqueId(), player.getName(), lunacy, reason);
        return BuyResult.OK;
    }

    /** Điểm PlayerPoints hiện có; -1 nếu PlayerPoints chưa bật. */
    public int points(Player player) {
        Object api = pointsApi();
        return api == null ? -1 : points(api, player.getUniqueId());
    }

    private Object pointsApi() {
        try {
            Plugin pp = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (pp == null || !pp.isEnabled()) return null;
            return pp.getClass().getMethod("getAPI").invoke(pp);
        } catch (Throwable t) {
            return null;
        }
    }

    private int points(Object api, UUID uuid) {
        try {
            Object r = api.getClass().getMethod("look", UUID.class).invoke(api, uuid);
            return r instanceof Number n ? n.intValue() : 0;
        } catch (Throwable t) {
            plugin.getLogger().warning("Không đọc được điểm PlayerPoints: " + t);
            return 0;
        }
    }

    private boolean takePoints(Object api, UUID uuid, int amount) {
        try {
            Method take = api.getClass().getMethod("take", UUID.class, int.class);
            Object r = take.invoke(api, uuid, amount);
            return !(r instanceof Boolean b) || b;
        } catch (Throwable t) {
            plugin.getLogger().warning("Không trừ được điểm PlayerPoints: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ bảng xếp hạng

    private void refreshTop() {
        int size = Math.max(0, plugin.getConfig().getInt("lunacy.leaderboard-size", 10));
        if (size == 0) {
            top = List.of();
            return;
        }
        plugin.database().lunacyTop(size).whenComplete((list, ex) -> {
            if (list != null) top = Collections.unmodifiableList(list);
        });
    }

    /** Hạng {@code rank} (từ 1): [tên, số dư] hoặc null. */
    public String[] top(int rank) {
        List<String[]> t = top;
        return rank >= 1 && rank <= t.size() ? t.get(rank - 1) : null;
    }

    /** Tên để lưu cùng số dư (cho bảng xếp hạng). */
    public static String nameOf(OfflinePlayer p) {
        return p == null ? null : p.getName();
    }
}
