package com.weeklygacha;

import com.weeklygacha.command.GachaAdminCommand;
import com.weeklygacha.command.GachaCommand;
import com.weeklygacha.gui.GachaMenu;
import com.weeklygacha.gui.GuiConfig;
import com.weeklygacha.gui.MenuListener;
import com.weeklygacha.listener.PlayerListener;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.service.BannerManager;
import com.weeklygacha.service.CostService;
import com.weeklygacha.service.GachaService;
import com.weeklygacha.service.MessageService;
import com.weeklygacha.service.RewardFactory;
import com.weeklygacha.storage.Database;
import com.weeklygacha.util.Text;
import com.weeklygacha.util.YamlFiles;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static com.weeklygacha.service.MessageService.comp;

public final class WeeklyGachaPlugin extends JavaPlugin {

    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();

    private Database database;
    private MessageService messages;
    private BannerManager banners;
    private RewardFactory rewards;
    private GachaService gacha;
    private GachaMenu menu;
    private CostService costs;
    private GuiConfig gui;
    private YamlConfiguration messagesConfig = new YamlConfiguration();
    private YamlConfiguration bannersConfig = new YamlConfiguration();
    private String lastSignature = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        messages = new MessageService(this);
        gui = new GuiConfig(this);
        migrateLegacyConfig();
        loadFiles();
        database = new Database(new File(getDataFolder(), "data.db"), getLogger());
        try {
            database.open();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Không mở được cơ sở dữ liệu SQLite, tắt plugin.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        banners = new BannerManager(this);
        banners.load();
        banners.loadState();
        banners.ensureStart();

        rewards = new RewardFactory(this);
        costs = new CostService(this);
        gacha = new GachaService(this);
        menu = new GachaMenu(this);

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);

        GachaCommand gachaCommand = new GachaCommand(this);
        getCommand("gacha").setExecutor(gachaCommand);
        getCommand("gacha").setTabCompleter(gachaCommand);
        GachaAdminCommand adminCommand = new GachaAdminCommand(this);
        getCommand("gachaadmin").setExecutor(adminCommand);
        getCommand("gachaadmin").setTabCompleter(adminCommand);

        for (Banner b : banners.all()) {
            String missing = costs.problem(b);
            if (missing != null) {
                getLogger().warning("Banner '" + b.id() + "' cần plugin " + missing + " nhưng plugin này chưa được bật.");
            }
        }

        // người chơi đang online (khi nạp lại plugin lúc server chạy)
        for (Player p : Bukkit.getOnlinePlayers()) {
            loadPlayerAsync(p.getUniqueId());
        }

        // theo dõi đổi chu kỳ mỗi phút
        lastSignature = banners.signature();
        getServer().getScheduler().runTaskTimer(this, this::checkCycle, 20L * 60, 20L * 60);
    }

    @Override
    public void onDisable() {
        if (gacha != null) {
            gacha.deliverEveryone(); // phát nốt quà của hoạt ảnh đang chạy
        }
        if (database != null) {
            database.close();
        }
    }

    private void checkCycle() {
        banners.refresh();
        String signature = banners.signature();
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        menu.closeAll();
        Banner limited = banners.currentLimited();
        if (limited != null) {
            Bukkit.broadcast(messages.component("cycle-changed", comp("banner", Text.mm(limited.displayName()))));
        }
    }

    /** Nạp lại 4 file cấu hình + banner (giữ nguyên mốc chu kỳ và ghi đè đã lưu trong DB). */
    public void reloadAll() {
        reloadConfig();
        loadFiles();
        banners.load();
        banners.ensureStart();
        rewards.clearCache();
        menu.closeAll();
        lastSignature = banners.signature();
    }

    /** Nạp messages.yml, banners.yml, gui.yml (config.yml do JavaPlugin nạp). */
    private void loadFiles() {
        messagesConfig = YamlFiles.load(this, "messages.yml", true);
        bannersConfig = YamlFiles.load(this, "banners.yml", false);
        gui.reload(YamlFiles.load(this, "gui.yml", true));
    }

    /**
     * Nâng cấp từ bản cũ chỉ có một file config.yml: chuyển mục {@code banners} và {@code messages} sang
     * banners.yml và messages.yml (nếu hai file này chưa tồn tại). Các mục cũ trong config.yml không còn được dùng.
     */
    private void migrateLegacyConfig() {
        File bannersFile = new File(getDataFolder(), "banners.yml");
        File messagesFile = new File(getDataFolder(), "messages.yml");
        ConfigurationSection oldBanners = getConfig().getConfigurationSection("banners");
        ConfigurationSection oldMessages = getConfig().getConfigurationSection("messages");
        try {
            if (!bannersFile.exists() && oldBanners != null) {
                YamlConfiguration out = new YamlConfiguration();
                copySection(oldBanners, out, "banners");
                out.save(bannersFile);
                getLogger().info("Đã chuyển mục banners của config.yml cũ sang banners.yml.");
            }
            if (!messagesFile.exists() && oldMessages != null) {
                YamlConfiguration out = new YamlConfiguration();
                copySection(oldMessages, out, "");
                out.save(messagesFile);
                getLogger().info("Đã chuyển mục messages của config.yml cũ sang messages.yml.");
            }
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Không chuyển được cấu hình cũ sang file mới", e);
        }
    }

    private static void copySection(ConfigurationSection from, YamlConfiguration to, String prefix) {
        for (String key : from.getKeys(true)) {
            Object value = from.get(key);
            if (!(value instanceof ConfigurationSection)) {
                to.set(prefix.isEmpty() ? key : prefix + "." + key, value);
            }
        }
    }

    public void loadPlayerAsync(UUID uuid) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                players.put(uuid, database.loadPlayer(uuid));
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Không nạp được dữ liệu gacha của " + uuid, e);
            }
        });
    }

    public Map<UUID, PlayerData> players() { return players; }
    public Database database() { return database; }
    public MessageService messages() { return messages; }
    public BannerManager banners() { return banners; }
    public RewardFactory rewards() { return rewards; }
    public CostService costs() { return costs; }
    public GuiConfig gui() { return gui; }
    public YamlConfiguration messagesConfig() { return messagesConfig; }
    public YamlConfiguration bannersConfig() { return bannersConfig; }
    public GachaService gacha() { return gacha; }
    public GachaMenu menu() { return menu; }
}
