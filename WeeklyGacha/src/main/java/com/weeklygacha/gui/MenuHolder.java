package com.weeklygacha.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Holder đánh dấu inventory của plugin + lưu hành động theo từng ô.
 *
 * <p>Từ 1.5.0 holder còn nhớ lớp trang trí (decor) do {@code layout} trong gui.yml vẽ ra:
 * <ul>
 *   <li>vật phẩm nền gốc của từng ô (để khôi phục sau khi viền nhấp nháy),</li>
 *   <li>các ô thuộc từng ký hiệu của layout,</li>
 *   <li>các decor động (viền chạy, nhấp nháy...) được {@link GachaMenu} gọi {@link #tick(long)} mỗi 2 tick.</li>
 * </ul>
 * Ô nào đã được đặt nút/vật phẩm thật bằng {@link #set} thì decor động không vẽ đè lên nữa.
 */
public final class MenuHolder implements InventoryHolder {

    private Inventory inventory;
    private final Map<Integer, MenuAction> actions = new HashMap<>();

    private final Map<Integer, ItemStack> base = new HashMap<>();
    private final Map<Character, List<Integer>> symbols = new HashMap<>();
    private final Set<Integer> occupied = new HashSet<>();
    private final List<Animated> animated = new ArrayList<>();
    private boolean paused;

    /** Một nhóm ô cùng chạy một decor động. */
    static final class Animated {
        final char symbol;
        final List<Integer> slots;      // đã sắp theo thứ tự chạy
        final int[] columns;            // cột của từng ô (cho kiểu wave)
        final ItemStack[] shown;        // vật phẩm đang hiện ở từng ô (null = chưa biết) -> chỉ gửi ô thật sự đổi
        List<ItemStack> frames;
        String mode;
        int speed;

        Animated(char symbol, List<Integer> slots, List<ItemStack> frames, String mode, int speed) {
            this.symbol = symbol;
            this.slots = slots;
            this.columns = slots.stream().mapToInt(s -> s % 9).toArray();
            this.shown = new ItemStack[slots.size()];
            this.frames = frames;
            this.mode = mode;
            this.speed = Math.max(1, speed);
        }

        void forget(int slot) {
            for (int i = 0; i < slots.size(); i++) if (slots.get(i) == slot) shown[i] = null;
        }
    }

    void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    int size() {
        return inventory.getSize();
    }

    void fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, item);
            if (item != null) base.put(i, item);
        }
    }

    /** Đặt nút / vật phẩm thật vào ô; bỏ qua nếu ô nằm ngoài GUI (gui.yml sai) thay vì gây lỗi. */
    void set(int slot, ItemStack item, MenuAction action) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        inventory.setItem(slot, item);
        occupied.add(slot);
        if (action != null) actions.put(slot, action);
        else actions.remove(slot);
    }

    /** Như {@link #set} nhưng chỉ đặt nếu ô chưa có nút nào. */
    boolean setIfFree(int slot, ItemStack item, MenuAction action) {
        if (slot < 0 || slot >= inventory.getSize() || occupied.contains(slot)) return false;
        set(slot, item, action);
        return true;
    }

    /** Trả ô về lớp trang trí (xoá nút đang ở đó). */
    void restore(int slot) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        occupied.remove(slot);
        actions.remove(slot);
        inventory.setItem(slot, base.get(slot));
        for (Animated a : animated) a.forget(slot);
    }

    // ------------------------------------------------------------------ decor

    /** Vẽ decor tĩnh vào ô (không tính là nút). */
    void decor(int slot, char symbol, ItemStack item) {
        if (slot < 0 || slot >= inventory.getSize()) return;
        symbols.computeIfAbsent(symbol, k -> new ArrayList<>()).add(slot);
        if (item != null) {
            base.put(slot, item);
            inventory.setItem(slot, item);
        }
    }

    void addAnimated(Animated a) {
        animated.add(a);
        tick(0);
    }

    /** Các ô của những ký hiệu trong chuỗi {@code chars}. */
    List<Integer> slotsOf(String chars) {
        List<Integer> out = new ArrayList<>();
        if (chars == null) return out;
        for (char c : chars.toCharArray()) {
            List<Integer> l = symbols.get(c);
            if (l != null) out.addAll(l);
        }
        return out;
    }

    /** Đổi khung hình của decor động mang ký hiệu {@code symbol} (dùng cho viền màn kết quả đổi màu theo độ hiếm). */
    boolean retint(char symbol, List<ItemStack> frames, String mode, int speed) {
        boolean found = false;
        for (Animated a : animated) {
            if (a.symbol != symbol) continue;
            a.frames = frames;
            if (mode != null) a.mode = mode;
            if (speed > 0) a.speed = speed;
            java.util.Arrays.fill(a.shown, null);
            found = true;
        }
        if (!found) {
            List<Integer> slots = symbols.get(symbol);
            if (slots != null && !slots.isEmpty()) {
                animated.add(new Animated(symbol, GuiConfig.ringOrder(slots, inventory.getSize() / 9), frames,
                        mode == null ? "chase" : mode, speed));
                found = true;
            }
        }
        tick(0);
        return found;
    }

    /** Vẽ tạm (không đổi nền gốc) - dùng cho viền nhấp nháy. */
    void paint(int slot, ItemStack item) {
        if (slot < 0 || slot >= inventory.getSize() || occupied.contains(slot)) return;
        inventory.setItem(slot, item);
    }

    /** Trả các ô decor về hình gốc (sau khi nhấp nháy). */
    void unpaint(List<Integer> slots) {
        for (int s : slots) {
            if (!occupied.contains(s)) inventory.setItem(s, base.get(s));
            for (Animated a : animated) a.forget(s);
        }
        tick(0);
    }

    void pause(boolean pause) {
        this.paused = pause;
    }

    boolean animated() {
        return !animated.isEmpty();
    }

    /** Gọi định kỳ; {@code step} tăng dần theo thời gian (đơn vị 2 tick). */
    void tick(long step) {
        if (paused || inventory == null) return;
        for (Animated a : animated) {
            if (a.frames.isEmpty()) continue;
            if (step != 0 && step % a.speed != 0) continue;
            long phase = step / a.speed;
            int n = a.frames.size();
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = 0; i < a.slots.size(); i++) {
                int slot = a.slots.get(i);
                if (occupied.contains(slot)) continue;
                int frame = switch (a.mode) {
                    case "blink" -> (int) (phase % n);
                    case "wave" -> (int) Math.floorMod(phase - a.columns[i], (long) n);
                    case "random" -> rnd.nextInt(n);
                    default -> (int) Math.floorMod(phase - i, (long) n); // chase
                };
                ItemStack item = a.frames.get(frame);
                if (a.shown[i] == item) continue;       // cùng vật phẩm -> không gửi gói tin
                a.shown[i] = item;
                inventory.setItem(slot, item);
            }
        }
    }

    MenuAction action(int slot) {
        return actions.get(slot);
    }
}
