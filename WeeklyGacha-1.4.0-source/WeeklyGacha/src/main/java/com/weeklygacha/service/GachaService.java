package com.weeklygacha.service;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.HistoryEntry;
import com.weeklygacha.model.PendingRow;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.PullEntry;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.model.Reward;
import com.weeklygacha.model.SparkConfig;
import com.weeklygacha.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.weeklygacha.service.MessageService.comp;
import static com.weeklygacha.service.MessageService.ph;

/**
 * Logic quay kiểu Limbus Company: tỷ lệ riêng từng vật phẩm, mười lượt có bảo đảm độ hiếm tối thiểu,
 * và "spark" (tích đủ lượt thì đổi thẳng vật phẩm nổi bật).
 */
public final class GachaService {

    private final WeeklyGachaPlugin plugin;
    private final Map<UUID, Long> lastPull = new HashMap<>();
    /** Các lô quà đã tính tiền nhưng chưa trao (đang chạy hoạt ảnh). Khoá = mã lô. */
    private final Map<String, PendingBatch> pending = new ConcurrentHashMap<>();

    private record PendingBatch(UUID player, List<PullEntry> entries) {
    }

    public GachaService(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    public void forget(UUID uuid) {
        lastPull.remove(uuid);
    }

    /**
     * Quay {@code count} lượt (1 hoặc 10) trên banner. Gọi trên luồng chính.
     * Lỗi được gửi thẳng cho người chơi và trả về {@link PullResult#FAIL}; mọi lỗi xảy ra trước khi
     * trừ chi phí đều không làm mất điểm/key hay thay đổi dữ liệu.
     * Đã trừ chi phí và lưu dữ liệu nhưng CHƯA trao quà và chưa gửi thông báo: hãy gọi {@link #deliver} rồi
     * {@link #announce} sau khi hiển thị kết quả (hoạt ảnh).
     */
    public PullResult pull(Player player, Banner banner, int count) {
        MessageService msg = plugin.messages();

        if (!player.hasPermission("weeklygacha.use")) {
            msg.send(player, "no-permission");
            return PullResult.FAIL;
        }
        if (!plugin.banners().isActive(banner)) {
            msg.send(player, "banner-inactive");
            return PullResult.FAIL;
        }
        PlayerData data = plugin.players().get(player.getUniqueId());
        if (data == null) {
            msg.send(player, "data-loading");
            return PullResult.FAIL;
        }

        // Giới hạn số lượt quay suốt đời trên banner (vd Assured Extraction chỉ quay được 1 lần)
        if (banner.hasPullLimit()) {
            int used = data.get(banner.id()).getTotalPulls();
            int left = banner.pullsLeft(used);
            if (left <= 0) {
                msg.send(player, "limit-reached", ph("max", String.valueOf(banner.maxPulls())));
                return PullResult.FAIL;
            }
            if (count > left) {
                msg.send(player, "limit-exceeded", ph("left", String.valueOf(left)), ph("count", String.valueOf(count)));
                return PullResult.FAIL;
            }
        }

        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("settings.pull-cooldown-ms", 500);
        Long last = lastPull.get(player.getUniqueId());
        if (last != null && now - last < cooldown) {
            msg.send(player, "cooldown");
            return PullResult.FAIL;
        }
        lastPull.put(player.getUniqueId(), now);

        int cost = banner.costFor(count);
        CostService costs = plugin.costs();
        if (cost > 0) {
            String missing = costs.problem(banner);
            if (missing != null) {
                msg.send(player, "cost-provider-missing", ph("plugin", missing));
                return PullResult.FAIL;
            }
            if (!costs.has(player, banner, cost)) {
                msg.send(player, "not-enough-cost",
                        ph("cost", costs.plain(banner, cost)), ph("balance", costs.plainBalance(player, banner)));
                return PullResult.FAIL;
            }
        }

        // 1) Roll trên bản sao -> chưa đụng vào dữ liệu thật
        PlayerBannerData working = data.get(banner.id()).copy();
        Rarity guarantee = count == 10 ? banner.spark().tenGuarantee() : null;
        boolean guaranteeMet = false;
        List<PullEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int sinceTop = working.getPity() + 1;
            boolean forced = guarantee != null && !guaranteeMet && i == count - 1;
            Reward reward = roll(banner, working, strongest(forced ? guarantee : null, banner.alwaysRarity()));
            if (guarantee != null && reward.rarity().ordinal() >= guarantee.ordinal()) guaranteeMet = true;

            ItemStack item = null;
            if (reward.provider().givesItem()) {
                item = plugin.rewards().createItem(reward);
                if (item == null) {
                    msg.send(player, "reward-unavailable", ph("reward", reward.id()));
                    return PullResult.FAIL;
                }
            }
            entries.add(new PullEntry(reward, sinceTop, item));
        }

        // 2) Trừ chi phí (điểm PlayerPoints hoặc key)
        if (cost > 0 && !costs.take(player, banner, cost)) {
            msg.send(player, "charge-failed", ph("cost", costs.plain(banner, cost)));
            return PullResult.FAIL;
        }

        // 3) Chốt dữ liệu + ghi DB (kèm danh sách quà chờ trao).
        //    Quà CHƯA được trao ở đây: chỉ trao khi hoạt ảnh kết thúc (deliver), khi người chơi đóng GUI/thoát game,
        //    khi server tắt, hoặc lần đăng nhập kế tiếp nếu server sập giữa chừng.
        data.put(banner.id(), working);
        int cycle = plugin.banners().cycleNumber();
        List<HistoryEntry> history = new ArrayList<>(count);
        List<String> rewardIds = new ArrayList<>(count);
        for (PullEntry e : entries) {
            history.add(new HistoryEntry(0, banner.id(), cycle, e.reward().id(),
                    plugin.rewards().nameOf(e.reward()), e.reward().rarity(), e.pityAtPull(), now));
            rewardIds.add(e.reward().id());
        }
        String batch = UUID.randomUUID().toString();
        pending.put(batch, new PendingBatch(player.getUniqueId(), entries));
        plugin.database().recordPulls(player.getUniqueId(), player.getName(), banner.id(), working.copy(),
                history, batch, rewardIds);

        return new PullResult(true, entries, cost, batch);
    }

    /**
     * Đổi spark lấy vật phẩm {@code chosen} (phải nằm trong {@link Banner#sparkPool()}). Gọi trên luồng chính.
     *
     * @return true nếu đổi thành công
     */
    public boolean exchange(Player player, Banner banner, Reward chosen) {
        MessageService msg = plugin.messages();
        SparkConfig spark = banner.spark();

        if (!player.hasPermission("weeklygacha.use")) {
            msg.send(player, "no-permission");
            return false;
        }
        if (!plugin.banners().isActive(banner)) {
            msg.send(player, "banner-inactive");
            return false;
        }
        PlayerData data = plugin.players().get(player.getUniqueId());
        if (data == null) {
            msg.send(player, "data-loading");
            return false;
        }
        if (!spark.enabled() || !banner.sparkPool().contains(chosen)) {
            msg.send(player, "spark-disabled");
            return false;
        }
        PlayerBannerData current = data.get(banner.id());
        if (current.getSpark() < spark.threshold()) {
            msg.send(player, "spark-not-ready",
                    ph("current", String.valueOf(current.getSpark())), ph("threshold", String.valueOf(spark.threshold())));
            return false;
        }

        ItemStack item = null;
        if (chosen.provider().givesItem()) {
            item = plugin.rewards().createItem(chosen);
            if (item == null) {
                msg.send(player, "reward-unavailable", ph("reward", chosen.id()));
                return false;
            }
        }

        PlayerBannerData working = current.copy();
        working.setSpark(working.getSpark() - spark.threshold());
        data.put(banner.id(), working);

        plugin.rewards().give(player, chosen, item);
        String name = plugin.rewards().nameOf(chosen);
        // pityAtPull = 0 đánh dấu lượt này là đổi bằng spark
        HistoryEntry entry = new HistoryEntry(0, banner.id(), plugin.banners().cycleNumber(), chosen.id(), name,
                chosen.rarity(), 0, System.currentTimeMillis());
        plugin.database().recordPulls(player.getUniqueId(), player.getName(), banner.id(), working.copy(), List.of(entry));

        msg.send(player, "spark-exchanged",
                comp("item", Text.mm(chosen.rarity().color() + Text.escape(name))),
                comp("banner", Text.mm(banner.displayName())));
        return true;
    }

    // ------------------------------------------------------------------ trao quà (sau hoạt ảnh)

    /** Trao quà của một lần quay. An toàn khi gọi nhiều lần: mỗi lô chỉ được trao đúng một lần. */
    public void deliver(Player player, PullResult result) {
        if (result.batch() == null) return;
        PendingBatch batch = pending.remove(result.batch());
        if (batch == null) return;
        giveBatch(player, batch);
        plugin.database().deletePendingBatch(result.batch());
    }

    /** Trao mọi lô đang chờ của người chơi (gọi khi họ thoát game). */
    public void deliverAllFor(Player player) {
        for (Map.Entry<String, PendingBatch> e : new ArrayList<>(pending.entrySet())) {
            if (e.getValue().player().equals(player.getUniqueId()) && pending.remove(e.getKey(), e.getValue())) {
                giveBatch(player, e.getValue());
                plugin.database().deletePendingBatch(e.getKey());
            }
        }
    }

    /** Trao mọi lô đang chờ cho người đang online (gọi khi tắt plugin/server). */
    public void deliverEveryone() {
        for (Map.Entry<String, PendingBatch> e : new ArrayList<>(pending.entrySet())) {
            Player p = Bukkit.getPlayer(e.getValue().player());
            if (p != null && pending.remove(e.getKey(), e.getValue())) {
                giveBatch(p, e.getValue());
                plugin.database().deletePendingBatch(e.getKey());
            }
        }
    }

    private void giveBatch(Player player, PendingBatch batch) {
        for (PullEntry e : batch.entries()) {
            plugin.rewards().give(player, e.reward(), e.item() == null ? null : e.item().clone());
        }
    }

    /** Khôi phục quà còn sót trong DB (server sập trước khi trao) khi người chơi đăng nhập. */
    public void recoverPending(Player player) {
        plugin.database().loadPending(player.getUniqueId()).whenComplete((rows, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null || rows == null || rows.isEmpty() || !player.isOnline()) return;
                    List<Long> done = new ArrayList<>();
                    int given = 0;
                    for (PendingRow row : rows) {
                        if (pending.containsKey(row.batch())) continue; // đang chờ trao trong phiên này
                        Banner banner = plugin.banners().get(row.bannerId());
                        Reward reward = banner == null ? null : banner.find(row.rewardId());
                        if (reward == null) {
                            done.add(row.id()); // vật phẩm đã bị xoá khỏi config
                            continue;
                        }
                        ItemStack item = null;
                        if (reward.provider().givesItem()) {
                            item = plugin.rewards().createItem(reward);
                            if (item == null) continue; // chưa tạo được (vd ItemsAdder chưa tải) -> thử lại lần sau
                        }
                        plugin.rewards().give(player, reward, item);
                        done.add(row.id());
                        given++;
                    }
                    if (!done.isEmpty()) plugin.database().deletePendingIds(done);
                    if (given > 0) {
                        plugin.messages().send(player, "pending-recovered", ph("count", String.valueOf(given)));
                    }
                }));
    }

    /** Gửi tóm tắt cho người quay và thông báo toàn server cho vật phẩm hiếm. Gọi sau khi hiển thị kết quả. */
    public void announce(Player player, Banner banner, PullResult result) {
        if (!result.success()) return;
        String costText = result.cost() > 0 ? plugin.costs().plain(banner, result.cost()) : "0";
        if (player.isOnline()) {
            plugin.messages().send(player, "pull-summary", ph("count", String.valueOf(result.entries().size())),
                    ph("cost", costText), comp("banner", Text.mm(banner.displayName())));
        }

        Set<String> rarities = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("settings.broadcast-rarities")) {
            rarities.add(s.toUpperCase(Locale.ROOT));
        }
        for (PullEntry e : result.entries()) {
            Reward r = e.reward();
            boolean announce = r.broadcast() != null ? r.broadcast() : rarities.contains(r.rarity().name());
            if (!announce) continue;
            String name = plugin.rewards().nameOf(r);
            Bukkit.broadcast(plugin.messages().component("broadcast-rare",
                    ph("player", player.getName()),
                    comp("item", Text.mm(r.rarity().color() + Text.escape(name))),
                    comp("banner", Text.mm(banner.displayName())),
                    ph("pity", String.valueOf(e.pityAtPull()))));
        }
    }

    // ------------------------------------------------------------------ thuật toán roll

    /**
     * Chọn một vật phẩm theo tỷ lệ riêng và cập nhật trạng thái.
     * Tỷ lệ của mỗi vật phẩm = chance / tổng chance. Nếu {@code minRarity} khác null thì chỉ chọn
     * trong các vật phẩm có độ hiếm từ mức đó trở lên (dùng cho bảo đảm của lượt thứ 10).
     */
    Reward roll(Banner banner, PlayerBannerData state, Rarity minRarity) {
        List<Reward> pool = banner.rewards();
        if (minRarity != null) {
            List<Reward> filtered = pool.stream().filter(r -> r.rarity().ordinal() >= minRarity.ordinal()).toList();
            if (!filtered.isEmpty()) pool = filtered;
        }
        Reward result = pick(pool);

        state.setTotalPulls(state.getTotalPulls() + 1);
        state.setSpark(state.getSpark() + 1);
        state.setPity(result.rarity() == banner.spark().rarity() ? 0 : state.getPity() + 1);
        return result;
    }

    private static Rarity strongest(Rarity a, Rarity b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static Reward pick(List<Reward> pool) {
        double total = 0;
        for (Reward r : pool) total += r.chance();
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double acc = 0;
        for (Reward r : pool) {
            acc += r.chance();
            if (roll < acc) return r;
        }
        return pool.get(pool.size() - 1);
    }
}
