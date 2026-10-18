package com.weeklygacha.gui;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.PullEntry;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.util.Items;
import com.weeklygacha.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.weeklygacha.service.MessageService.comp;
import static com.weeklygacha.service.MessageService.ph;

/**
 * Hoạt ảnh quay kiểu Limbus Company, chạy trong một chest GUI 36 ô:
 * <ol>
 *   <li><b>Xáo thẻ</b>: các thẻ úp nhấp nháy đổi màu liên tục, tiếng "tách" tăng dần.</li>
 *   <li><b>Phát sáng</b>: từng thẻ lần lượt sáng theo màu độ hiếm (trước khi lật) - thẻ hiếm dừng lâu hơn.</li>
 *   <li><b>Lật thẻ</b>: hiện vật phẩm thật kèm âm thanh theo độ hiếm; độ hiếm cao làm viền nhấp nháy.</li>
 * </ol>
 * Quà chỉ được trao khi hoạt ảnh kết thúc, hoặc ngay khi người chơi bấm "Bỏ qua"/đóng GUI/thoát game;
 * phần thưởng đã tính tiền được lưu trong DB nên không mất nếu server sập giữa chừng.
 */
final class GachaAnimation extends BukkitRunnable {

    private enum Phase { SPIN, TINT, REVEAL }

    private static final Material[] SPIN_PALETTE = {
            Material.LIGHT_GRAY_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE,
            Material.ORANGE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE,
            Material.CYAN_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE
    };

    /** Chu kỳ chạy của task (tick). */
    static final long PERIOD = 2L;
    /** Ô nút "Bỏ qua". */
    static final int SKIP_SLOT = 31;

    private final WeeklyGachaPlugin plugin;
    private final GachaMenu menu;
    private final Player player;
    private final MenuHolder holder;
    private final Banner banner;
    private final PullResult result;
    private final int count;
    private final int[] slots;

    private final int spinTotal;
    private final int delayRuns;
    private final int topPauseRuns;

    private Phase phase = Phase.SPIN;
    private int spinLeft;
    private int wait;
    private int index;
    private int flashLeft;
    private boolean finished;

    GachaAnimation(WeeklyGachaPlugin plugin, GachaMenu menu, Player player, MenuHolder holder, Banner banner,
                   PullResult result, int count, int[] slots) {
        this.plugin = plugin;
        this.menu = menu;
        this.player = player;
        this.holder = holder;
        this.banner = banner;
        this.result = result;
        this.count = count;
        this.slots = slots;
        this.spinTotal = Math.max(1, plugin.getConfig().getInt("animation.spin-ticks", 24) / (int) PERIOD);
        this.delayRuns = Math.max(0, plugin.getConfig().getInt("animation.reveal-delay-ticks", 6) / (int) PERIOD);
        this.topPauseRuns = Math.max(delayRuns, plugin.getConfig().getInt("animation.top-pause-ticks", 14) / (int) PERIOD);
        this.spinLeft = spinTotal;
    }

    /** Đặt các thẻ úp lên GUI trước khi mở. */
    void prepare() {
        for (int i = 0; i < result.entries().size() && i < slots.length; i++) {
            holder.getInventory().setItem(slots[i], cardBack());
        }
    }

    @Override
    public void run() {
        if (finished) {
            cancel();
            return;
        }
        // Người chơi đóng GUI / thoát game -> dừng, kết quả đã nằm trong túi đồ
        if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != holder) {
            abort();
            return;
        }
        Inventory inv = holder.getInventory();

        if (flashLeft > 0) {
            flash(flashLeft % 2 == 0 ? Material.ORANGE_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE);
            flashLeft--;
            if (flashLeft == 0) restoreBorder();
        }

        switch (phase) {
            case SPIN -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < result.entries().size() && i < slots.length; i++) {
                    Material m = SPIN_PALETTE[rnd.nextInt(SPIN_PALETTE.length)];
                    inv.setItem(slots[i], Items.of(m, 1, " ", List.of()));
                }
                float pitch = Math.min(2.0f, 0.7f + (spinTotal - spinLeft) * 0.05f);
                play(Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, pitch);
                if (--spinLeft <= 0) {
                    phase = Phase.TINT;
                    wait = 1;
                }
            }
            case TINT -> {
                if (wait > 0) {
                    wait--;
                    return;
                }
                if (index >= result.entries().size() || index >= slots.length) {
                    finish();
                    return;
                }
                Rarity rarity = result.entries().get(index).reward().rarity();
                inv.setItem(slots[index], Items.of(paneFor(rarity), 1, rarity.color() + "✦", List.of()));
                playTint(rarity);
                wait = rarity.ordinal() >= Rarity.EPIC.ordinal() ? topPauseRuns : delayRuns;
                phase = Phase.REVEAL;
            }
            case REVEAL -> {
                if (wait > 0) {
                    wait--;
                    return;
                }
                reveal(index);
                index++;
                wait = 1;
                phase = Phase.TINT;
            }
        }
    }

    /** Bỏ qua hoạt ảnh: lật hết mọi thẻ còn lại ngay lập tức. */
    void skip() {
        if (finished) return;
        for (int i = index; i < result.entries().size() && i < slots.length; i++) {
            holder.getInventory().setItem(slots[i], menu.cardFor(result.entries().get(i), banner));
        }
        index = result.entries().size();
        finish();
    }

    private void reveal(int i) {
        PullEntry entry = result.entries().get(i);
        holder.getInventory().setItem(slots[i], menu.cardFor(entry, banner));
        Rarity rarity = entry.reward().rarity();
        playReveal(rarity);
        if (rarity.ordinal() >= Rarity.LEGENDARY.ordinal()) {
            flashLeft = 6;
        } else if (rarity.ordinal() >= Rarity.EPIC.ordinal()) {
            flashLeft = 3;
        }
    }

    private void finish() {
        if (finished) return;
        finished = true;
        cancel();
        restoreBorder();
        plugin.gacha().deliver(player, result);   // hoạt ảnh xong -> mới phát quà
        menu.showFinal(holder, player, banner, result, count);
        plugin.gacha().announce(player, banner, result);
    }

    /** Đóng GUI giữa chừng: báo kết quả bằng chat để người chơi vẫn biết mình nhận gì. */
    private void abort() {
        if (finished) return;
        finished = true;
        cancel();
        // Đóng GUI giữa chừng: trao quà ngay (nếu đã offline, quà được trao lúc thoát game hoặc lần đăng nhập sau)
        if (player.isOnline()) plugin.gacha().deliver(player, result);
        if (player.isOnline()) {
            for (PullEntry e : result.entries()) {
                String name = plugin.rewards().nameOf(e.reward());
                plugin.messages().send(player, "pull-line",
                        comp("item", Text.mm(e.reward().rarity().color() + Text.escape(name))),
                        ph("amount", String.valueOf(e.reward().amount())));
            }
        }
        plugin.gacha().announce(player, banner, result);
    }

    // ------------------------------------------------------------------ hiển thị

    private static ItemStack cardBack() {
        return Items.of(Material.GRAY_STAINED_GLASS_PANE, 1, "<dark_gray>?", List.of());
    }

    static Material paneFor(Rarity rarity) {
        return switch (rarity) {
            case COMMON -> Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            case UNCOMMON -> Material.LIME_STAINED_GLASS_PANE;
            case RARE -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case EPIC -> Material.PURPLE_STAINED_GLASS_PANE;
            case LEGENDARY -> Material.ORANGE_STAINED_GLASS_PANE;
        };
    }

    /** Hàng trên và hàng dưới của GUI (trừ nút Bỏ qua). */
    private void flash(Material material) {
        ItemStack pane = Items.of(material, 1, " ", List.of());
        Inventory inv = holder.getInventory();
        for (int s = 0; s < 9; s++) inv.setItem(s, pane);
        for (int s = 27; s < 36; s++) {
            if (s != SKIP_SLOT) inv.setItem(s, pane);
        }
    }

    private void restoreBorder() {
        ItemStack bg = menu.background();
        Inventory inv = holder.getInventory();
        for (int s = 0; s < 9; s++) inv.setItem(s, bg);
        for (int s = 27; s < 36; s++) {
            if (s != SKIP_SLOT) inv.setItem(s, bg);
        }
    }

    // ------------------------------------------------------------------ âm thanh

    private void play(Sound sound, float volume, float pitch) {
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private void playTint(Rarity rarity) {
        switch (rarity) {
            case COMMON -> play(Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.9f);
            case UNCOMMON -> play(Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.1f);
            case RARE -> play(Sound.BLOCK_NOTE_BLOCK_PLING, 0.9f, 1.4f);
            case EPIC -> play(Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.8f);
            case LEGENDARY -> play(Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.2f);
        }
    }

    private void playReveal(Rarity rarity) {
        switch (rarity) {
            case COMMON, UNCOMMON -> play(Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.0f);
            case RARE -> play(Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f);
            case EPIC -> play(Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            case LEGENDARY -> {
                play(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                play(Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 1.0f);
            }
        }
    }
}
