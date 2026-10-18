package com.weeklygacha.gui;

import com.weeklygacha.WeeklyGachaPlugin;
import com.weeklygacha.model.Rarity;
import com.weeklygacha.util.Items;
import com.weeklygacha.util.Text;
import com.weeklygacha.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc gui.yml: tiêu đề, vật phẩm (chất liệu, tên, lore, ô), âm thanh, hoạt ảnh, định dạng.
 *
 * <p>Quy ước trong gui.yml:
 * <ul>
 *   <li>{@code {ten}}: biến do plugin điền vào (xem chú thích đầu mỗi mục trong gui.yml).</li>
 *   <li>{@code [if:dieu-kien]} đầu dòng lore: chỉ hiện dòng khi điều kiện bật; {@code [if:!dieu-kien]}: hiện khi điều kiện tắt.</li>
 *   <li>Dòng lore chỉ gồm {@code {khoi}} (ví dụ {@code {description}}) sẽ được thay bằng nhiều dòng do plugin cung cấp.</li>
 *   <li>{@code enabled: false} trong một vật phẩm sẽ ẩn nó khỏi GUI.</li>
 * </ul>
 */
public final class GuiConfig {

    private static final Pattern VAR = Pattern.compile("\\{([a-z0-9-]+)\\}");
    private static final Pattern COND = Pattern.compile("^\\[if:(!?)([a-z0-9-]+)\\]\\s*(.*)$");

    private final WeeklyGachaPlugin plugin;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile YamlConfiguration cfg = new YamlConfiguration();

    public GuiConfig(WeeklyGachaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Nạp (lại) gui.yml. */
    public void reload(YamlConfiguration newConfig) {
        this.cfg = newConfig;
        warned.clear();
        Rarity.configure(newConfig.getConfigurationSection("rarity"));
        TimeUtil.configure(
                newConfig.getString("format.day", "ngày"),
                newConfig.getString("format.hour", "giờ"),
                newConfig.getString("format.minute", "phút"),
                newConfig.getString("format.date", "dd/MM/yyyy HH:mm"));
    }

    // ------------------------------------------------------------------ biến / điều kiện

    public static Map<String, String> vars(String... keyValue) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i + 1 < keyValue.length; i += 2) {
            map.put(keyValue[i], keyValue[i + 1]);
        }
        return map;
    }

    public static Set<String> conds(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /** Thay {@code {ten}} bằng giá trị trong {@code vars}; biến lạ được giữ nguyên. */
    public String fmt(String text, Map<String, String> vars) {
        if (text == null || text.isEmpty() || vars == null || vars.isEmpty()) return text == null ? "" : text;
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ giá trị đơn giản

    public String string(String path, String def) {
        return cfg.getString(path, def);
    }

    /** Chuỗi trong gui.yml đã thay biến, dùng cho tiêu đề. */
    public String text(String path, Map<String, String> vars) {
        return fmt(cfg.getString(path, ""), vars);
    }

    public int integer(String path, int def) {
        return cfg.getInt(path, def);
    }

    public boolean bool(String path, boolean def) {
        return cfg.getBoolean(path, def);
    }

    public double number(String path, double def) {
        return cfg.getDouble(path, def);
    }

    /** Số hàng (1-6) của một menu, đọc từ {@code <path>.rows}. */
    public int rows(String path, int def) {
        return Math.max(1, Math.min(6, cfg.getInt(path + ".rows", def)));
    }

    /** Ô của một vật phẩm, đọc từ {@code <path>.slot}. */
    public int slot(String path, int def) {
        return cfg.getInt(path + ".slot", def);
    }

    public List<Integer> intList(String path, List<Integer> def) {
        List<Integer> list = cfg.getIntegerList(path);
        return list.isEmpty() ? def : list;
    }

    public List<String> stringList(String path) {
        return cfg.getStringList(path);
    }

    // ------------------------------------------------------------------ vật phẩm

    /** Vật phẩm dựng từ mục {@code path}; null nếu mục đó có {@code enabled: false}. */
    public ItemStack item(String path, Set<String> conds, Map<String, String> vars, Map<String, List<String>> blocks) {
        return build(path, null, conds, vars, blocks);
    }

    public ItemStack item(String path) {
        return build(path, null, Set.of(), Map.of(), Map.of());
    }

    /** Giữ nguyên hình dạng/model của {@code base}, chỉ thay tên + lore theo mục {@code path}. */
    public ItemStack decorate(String path, ItemStack base, Set<String> conds, Map<String, String> vars,
                              Map<String, List<String>> blocks) {
        return build(path, base, conds, vars, blocks);
    }

    private ItemStack build(String path, ItemStack base, Set<String> conds, Map<String, String> vars,
                            Map<String, List<String>> blocks) {
        ConfigurationSection s = cfg.getConfigurationSection(path);
        if (s == null) {
            warnOnce("missing:" + path, "gui.yml thiếu mục '" + path + "'.");
            return base != null ? base : Items.of(Material.BARRIER, 1, "<red>?", List.of());
        }
        if (!s.getBoolean("enabled", true)) return null;

        String name = fmt(s.getString("name", " "), vars);
        List<String> lore = process(s.getStringList("lore"), conds, vars, blocks);

        ItemStack result;
        if (base == null) {
            Material m = Material.matchMaterial(s.getString("material", "STONE"));
            if (m == null || !m.isItem() || m.isAir()) {
                warnOnce("material:" + path, "gui.yml: '" + path + ".material' = '" + s.getString("material")
                        + "' không hợp lệ, dùng BARRIER.");
                m = Material.BARRIER;
            }
            int amount = Math.max(1, Math.min(64, s.getInt("amount", 1)));
            result = Items.of(m, amount, name, lore);
        } else {
            List<Component> comps = Text.mmList(lore);
            result = Items.decorate(base, Text.mm(name), comps);
        }

        Object g = s.get("glow");
        boolean glow = g instanceof Boolean b ? b : (g instanceof String c && conds.contains(c));
        if (glow) Items.glow(result);
        return result;
    }

    public ItemStack filler() {
        return item("general.filler");
    }

    public ItemStack background() {
        return item("general.background");
    }

    // ------------------------------------------------------------------ lore

    /** Các dòng của danh sách {@code path} sau khi xử lý điều kiện, khối và biến. */
    public List<String> lines(String path, Set<String> conds, Map<String, String> vars,
                              Map<String, List<String>> blocks) {
        return process(cfg.getStringList(path), conds, vars, blocks);
    }

    private List<String> process(List<String> raw, Set<String> conds, Map<String, String> vars,
                                 Map<String, List<String>> blocks) {
        List<String> out = new ArrayList<>(raw.size());
        for (String original : raw) {
            String line = original;
            boolean skip = false;
            Matcher m = COND.matcher(line);
            while (m.matches()) {
                boolean negate = !m.group(1).isEmpty();
                boolean on = conds.contains(m.group(2));
                if (on == negate) {
                    skip = true;
                    break;
                }
                line = m.group(3);
                m = COND.matcher(line);
            }
            if (skip) continue;

            String trimmed = line.trim();
            if (trimmed.length() > 2 && trimmed.startsWith("{") && trimmed.endsWith("}")) {
                List<String> block = blocks.get(trimmed.substring(1, trimmed.length() - 1));
                if (block != null) {
                    out.addAll(block);
                    continue;
                }
            }
            out.add(fmt(line, vars));
        }
        return out;
    }

    // ------------------------------------------------------------------ định dạng

    /** Thanh tiến độ, ví dụ ▮▮▮▮▯▯▯▯. */
    public String bar(int value, int max) {
        int cells = Math.max(1, cfg.getInt("format.bar.cells", 20));
        int filled = max <= 0 ? 0 : Math.min(cells, (int) Math.round((double) value / max * cells));
        return cfg.getString("format.bar.filled", "<green>▮").repeat(filled)
                + cfg.getString("format.bar.empty", "<dark_gray>▮").repeat(cells - filled);
    }

    /** Ví dụ "100 điểm". */
    public String points(long amount) {
        return fmt(cfg.getString("format.points", "{amount} điểm"), vars("amount", String.valueOf(amount)));
    }

    /** Ví dụ "3x Chìa khoá". */
    public String keys(long amount, String name) {
        return fmt(cfg.getString("format.key", "{amount}x {name}"),
                vars("amount", String.valueOf(amount), "name", name));
    }

    public String infinite() {
        return cfg.getString("format.infinite", "∞");
    }

    // ------------------------------------------------------------------ âm thanh

    /**
     * Phát các âm thanh trong danh sách {@code path}. Mỗi phần tử có dạng {@code "khoa-am-thanh âm-lượng cao-độ"},
     * ví dụ {@code "block.note_block.pling 0.8 1.2"} (âm lượng và cao độ có thể bỏ). Ghi {@code none} để tắt.
     */
    public void play(Player player, String path) {
        List<String> entries = cfg.isString(path) ? List.of(cfg.getString(path, "")) : cfg.getStringList(path);
        for (String entry : entries) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty() || parts[0].equalsIgnoreCase("none")) continue;
            float volume = parseFloat(parts, 1, 1f);
            float pitch = parseFloat(parts, 2, 1f);
            playRaw(player, parts[0], volume, pitch);
        }
    }

    public void playRaw(Player player, String key, float volume, float pitch) {
        String id = key.toLowerCase(Locale.ROOT);
        if (!id.contains(":")) id = "minecraft:" + id;
        player.playSound(player.getLocation(), id, volume, pitch);
    }

    private static float parseFloat(String[] parts, int index, float def) {
        if (index >= parts.length) return def;
        try {
            return Float.parseFloat(parts[index]);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) plugin.getLogger().warning(message);
    }
}
