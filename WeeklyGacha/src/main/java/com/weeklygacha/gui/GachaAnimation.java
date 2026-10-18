package com.weeklygacha.gui;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.PullEntry;
import com.weeklygacha.model.PullResult;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.util.Items;
import com.weeklygacha.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static com.weeklygacha.service.MessageService.comp;
import static com.weeklygacha.service.MessageService.ph;

/**
 * Hoạt ảnh quay kiểu Limbus Company, chạy trong một chest GUI:
 * <ol>
 *   <li><b>Xáo thẻ</b>: các thẻ úp nhấp nháy đổi màu liên tục, tiếng "tách" tăng dần.</li>
 *   <li><b>Phát sáng</b>: từng thẻ lần lượt sáng theo màu độ hiếm (trước khi lật) - thẻ hiếm dừng lâu hơn.</li>
 *   <li><b>Lật thẻ</b>: hiện vật phẩm thật kèm âm thanh theo độ hiếm; độ hiếm cao làm viền nhấp nháy.</li>
 * </ol>
 * Quà chỉ được trao khi hoạt ảnh kết thúc, hoặc ngay khi người chơi bấm "Bỏ qua"/đóng GUI/thoát game;
 * phần thưởng đã tính tiền được lưu trong DB nên không mất nếu server sập giữa chừng.
 * Hình ảnh, màu, âm thanh lấy từ gui.yml (mục {@code animation} và {@code sounds}); thời gian lấy từ config.yml.
 */
final class GachaAnimation extends BukkitRunnable {

    private enum Phase { SPIN, TINT, REVEAL }

    /** Chu kỳ chạy của task (tick). */
    static final long PERIOD = 2L;

    private final WeeklyGachaPlugin plugin;
    private final GuiConfig gui;
    private final GachaMenu menu;
    private final Player player;
    private final MenuHolder holder;
    private final Banner banner;
    private final PullResult result;
    private final int count;
    private final int[] slots;
    private final int skipSlot;

    private final int spinTotal;
    private final int delayRuns;
    private final int topPauseRuns;

    private final List<Material> spinPalette = new ArrayList<>();
    private final List<Material> flashMaterials = new ArrayList<>();
    private final List<Integer> flashSlots;

    private Phase phase = Phase.SPIN;
    private int spinLeft;
    private int wait;
    private int index;
    private int flashLeft;
    private boolean finished;

    GachaAnimation(WeeklyGachaPlugin plugin, GachaMenu menu, Player player, MenuHolder holder, Banner banner,
                   PullResult result, int count, int[] slots) {
        this.plugin = plugin;
        this.gui = plugin.gui();
        this.menu = menu;
        this.player = player;
        this.holder = holder;
        this.banner = banner;
        this.result = result;
        this.count = count;
        this.slots = slots;
        this.skipSlot = gui.slot("result.skip", 31);
        this.spinTotal = Math.max(1, plugin.getConfig().getInt("animation.spin-ticks", 24) / (int) PERIOD);
        this.delayRuns = Math.max(0, plugin.getConfig().getInt("animation.reveal-delay-ticks", 6) / (int) PERIOD);
        this.topPauseRuns = Math.max(delayRuns, plugin.getConfig().getInt("animation.top-pause-ticks", 14) / (int) PERIOD);
        this.spinLeft = spinTotal;

        for (String name : gui.stringList("animation.spin-palette")) addMaterial(spinPalette, name);
        if (spinPalette.isEmpty()) spinPalette.add(Material.GRAY_STAINED_GLASS_PANE);
        for (String name : gui.stringList("animation.flash.materials")) addMaterial(flashMaterials, name);
        if (flashMaterials.isEmpty()) flashMaterials.add(Material.ORANGE_STAINED_GLASS_PANE);
        this.flashSlots = gui.intList("animation.flash.slots", List.of());
    }

    private static void addMaterial(List<Material> list, String name) {
        Material m = Material.matchMaterial(name);
        if (m != null && m.isItem() && !m.isAir()) list.add(m);
    }

    /** Đặt các thẻ úp lên GUI trước khi mở. */
    void prepare() {
        for (int i = 0; i < result.entries().size() && i < slots.length; i++) {
            holder.set(slots[i], gui.item("animation.card-back"), null);
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
        if (flashLeft > 0) {
            flash(flashMaterials.get(flashLeft % flashMaterials.size()));
            flashLeft--;
            if (flashLeft == 0) restoreBorder();
        }

        switch (phase) {
            case SPIN -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                for (int i = 0; i < result.entries().size() && i < slots.length; i++) {
                    Material m = spinPalette.get(rnd.nextInt(spinPalette.size()));
                    holder.set(slots[i], Items.of(m, 1, " ", List.of()), null);
                }
                playSpin(spinTotal - spinLeft);
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
                String name = gui.fmt(gui.string("animation.glow-pane-name", "{rarity-color}✦"),
                        GuiConfig.vars("rarity-color", rarity.color()));
                holder.set(slots[index], Items.of(rarity.pane(), 1, name, List.of()), null);
                gui.play(player, "sounds.tint." + rarity.name());
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
            holder.set(slots[i], menu.cardFor(result.entries().get(i), banner), null);
        }
        index = result.entries().size();
        finish();
    }

    private void reveal(int i) {
        PullEntry entry = result.entries().get(i);
        holder.set(slots[i], menu.cardFor(entry, banner), null);
        Rarity rarity = entry.reward().rarity();
        gui.play(player, "sounds.reveal." + rarity.name());
        flashLeft = Math.max(0, gui.integer("animation.flash.count." + rarity.name(), 0));
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
        // Trao quà ngay (nếu đã offline, quà được trao lúc thoát game hoặc lần đăng nhập sau)
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

    /** Viền nhấp nháy (trừ ô nút Bỏ qua). */
    private void flash(Material material) {
        ItemStack pane = Items.of(material, 1, " ", List.of());
        for (int s : flashSlots) {
            if (s != skipSlot) holder.set(s, pane, null);
        }
    }

    private void restoreBorder() {
        ItemStack bg = gui.background();
        for (int s : flashSlots) {
            if (s != skipSlot) holder.set(s, bg, null);
        }
    }

    // ------------------------------------------------------------------ âm thanh

    private void playSpin(int step) {
        String sound = gui.string("sounds.spin.sound", "block.note_block.hat");
        if (sound.equalsIgnoreCase("none")) return;
        float volume = (float) gui.number("sounds.spin.volume", 0.6);
        float start = (float) gui.number("sounds.spin.pitch-start", 0.7);
        float stepSize = (float) gui.number("sounds.spin.pitch-step", 0.05);
        float max = (float) gui.number("sounds.spin.pitch-max", 2.0);
        gui.playRaw(player, sound, volume, Math.min(max, start + step * stepSize));
    }
}
