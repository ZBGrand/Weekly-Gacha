package com.weeklygacha;

import com.weeklygacha.command.GachaAdminCommand;
import com.weeklygacha.command.GachaCommand;
import com.weeklygacha.gui.GachaMenu;
import com.weeklygacha.gui.MenuListener;
import com.weeklygacha.listener.PlayerListener;
import com.weeklygacha.model.Banner;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.service.BannerManager;
import com.weeklygacha.service.GachaService;
import com.weeklygacha.service.MessageService;
import com.weeklygacha.service.RewardFactory;
import com.weeklygacha.storage.Database;
import com.weeklygacha.util.Text;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
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
    private Economy economy;
    private String lastSignature = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        messages = new MessageService(this);
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

        if (economy() == null) {
            getLogger().warning("Chưa tìm thấy nhà cung cấp kinh tế qua Vault (cần EssentialsX/CMI... + Vault). "
                    + "Sẽ thử lại mỗi khi có người quay.");
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

    /** Nạp lại config + banner (giữ nguyên mốc chu kỳ và ghi đè đã lưu trong DB). */
    public void reloadAll() {
        reloadConfig();
        banners.load();
        banners.ensureStart();
        rewards.clearCache();
        menu.closeAll();
        lastSignature = banners.signature();
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

    /** Dịch vụ kinh tế của Vault; tra cứu lười vì plugin kinh tế có thể nạp sau WeeklyGacha. */
    public Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    public Map<UUID, PlayerData> players() { return players; }
    public Database database() { return database; }
    public MessageService messages() { return messages; }
    public BannerManager banners() { return banners; }
    public RewardFactory rewards() { return rewards; }
    public GachaService gacha() { return gacha; }
    public GachaMenu menu() { return menu; }
}
