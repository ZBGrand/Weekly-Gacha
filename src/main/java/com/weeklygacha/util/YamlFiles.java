package com.weeklygacha.util;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Nạp các file yml phụ (messages.yml, banners.yml, gui.yml) trong thư mục dữ liệu của plugin. */
public final class YamlFiles {

    private YamlFiles() {
    }

    /**
     * Nạp file; nếu chưa có thì chép bản mặc định từ trong JAR ra.
     * Nếu file hỏng cú pháp YAML thì ghi lỗi vào log và dùng bản mặc định trong JAR (file của bạn không bị ghi đè).
     *
     * @param mergeDefaults true: khoá nào thiếu trong file sẽ lấy từ bản mặc định (dùng cho messages/gui để
     *                      nâng cấp plugin không làm mất mục mới). banners.yml dùng false để không "hồi sinh" banner đã xoá.
     */
    public static YamlConfiguration load(JavaPlugin plugin, String name, boolean mergeDefaults) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration defaults = defaults(plugin, name);

        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("File " + name + " bị lỗi cú pháp YAML nên tạm dùng bản mặc định: " + e.getMessage());
            return defaults != null ? defaults : new YamlConfiguration();
        }
        if (mergeDefaults && defaults != null) {
            cfg.setDefaults(defaults);
        }
        return cfg;
    }

    private static YamlConfiguration defaults(JavaPlugin plugin, String name) {
        InputStream in = plugin.getResource(name);
        if (in == null) return null;
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
