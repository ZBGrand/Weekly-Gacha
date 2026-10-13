package com.weeklygacha.storage;

import com.weeklygacha.model.HistoryEntry;
import com.weeklygacha.model.PlayerBannerData;
import com.weeklygacha.model.PlayerData;
import com.weeklygacha.model.Rarity;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite lưu pity, số lượt quay và lịch sử.
 * Toàn bộ truy cập đi qua MỘT luồng riêng nên không cần khoá và không chặn luồng chính.
 * Driver org.sqlite.JDBC đã được Paper đóng gói sẵn.
 */
public final class Database {

    private final File file;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WeeklyGacha-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection connection;

    public Database(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void open() throws Exception {
        executor.submit((Callable<Void>) () -> {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("CREATE TABLE IF NOT EXISTS player_banner ("
                        + "uuid TEXT NOT NULL, banner_id TEXT NOT NULL, "
                        + "pity INTEGER NOT NULL DEFAULT 0, guaranteed INTEGER NOT NULL DEFAULT 0, "
                        + "total_pulls INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, banner_id))");
                st.execute("CREATE TABLE IF NOT EXISTS pull_history ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, player_name TEXT NOT NULL, "
                        + "banner_id TEXT NOT NULL, cycle INTEGER NOT NULL, reward_id TEXT NOT NULL, "
                        + "reward_name TEXT NOT NULL, rarity TEXT NOT NULL, pity_at_pull INTEGER NOT NULL, "
                        + "ts INTEGER NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_history_uuid ON pull_history (uuid, id DESC)");
                st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
            }
            return null;
        }).get();
    }

    public void close() {
        executor.execute(() -> {
            try {
                if (connection != null) connection.close();
            } catch (SQLException ignored) {
                // đang tắt
            }
        });
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ người chơi

    /** Chặn luồng gọi cho tới khi xong -> chỉ gọi từ luồng async. */
    public PlayerData loadPlayer(UUID uuid) throws Exception {
        return executor.submit(() -> {
            PlayerData data = new PlayerData(uuid);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT banner_id, pity, guaranteed, total_pulls FROM player_banner WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PlayerBannerData d = new PlayerBannerData();
                        d.setPity(rs.getInt(2));
                        d.setGuaranteed(rs.getInt(3) != 0);
                        d.setTotalPulls(rs.getInt(4));
                        data.put(rs.getString(1), d);
                    }
                }
            }
            return data;
        }).get();
    }

    /** Ghi trạng thái pity + các dòng lịch sử trong một transaction (bất đồng bộ). */
    public void recordPulls(UUID uuid, String playerName, String bannerId,
                            PlayerBannerData snapshot, List<HistoryEntry> entries) {
        executor.execute(() -> {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement up = connection.prepareStatement(
                        "INSERT INTO player_banner (uuid, banner_id, pity, guaranteed, total_pulls) VALUES (?,?,?,?,?) "
                                + "ON CONFLICT(uuid, banner_id) DO UPDATE SET pity=excluded.pity, "
                                + "guaranteed=excluded.guaranteed, total_pulls=excluded.total_pulls");
                     PreparedStatement ins = connection.prepareStatement(
                             "INSERT INTO pull_history (uuid, player_name, banner_id, cycle, reward_id, reward_name, "
                                     + "rarity, pity_at_pull, ts) VALUES (?,?,?,?,?,?,?,?,?)")) {
                    up.setString(1, uuid.toString());
                    up.setString(2, bannerId);
                    up.setInt(3, snapshot.getPity());
                    up.setInt(4, snapshot.isGuaranteed() ? 1 : 0);
                    up.setInt(5, snapshot.getTotalPulls());
                    up.executeUpdate();

                    for (HistoryEntry e : entries) {
                        ins.setString(1, uuid.toString());
                        ins.setString(2, playerName);
                        ins.setString(3, bannerId);
                        ins.setInt(4, e.cycle());
                        ins.setString(5, e.rewardId());
                        ins.setString(6, e.rewardName());
                        ins.setString(7, e.rarity().name());
                        ins.setInt(8, e.pityAtPull());
                        ins.setLong(9, e.timestamp());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) {
                logger.log(Level.SEVERE, "Không thể ghi lượt quay của " + playerName, ex);
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // bỏ qua
                }
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // bỏ qua
                }
            }
        });
    }

    /** Đặt pity + cờ bảo đảm về 0 (giữ nguyên tổng lượt quay). bannerId = null: mọi banner. */
    public void resetPity(UUID uuid, String bannerId) {
        executor.execute(() -> {
            String sql = "UPDATE player_banner SET pity = 0, guaranteed = 0 WHERE uuid = ?"
                    + (bannerId != null ? " AND banner_id = ?" : "");
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                if (bannerId != null) ps.setString(2, bannerId);
                ps.executeUpdate();
            } catch (SQLException ex) {
                logger.log(Level.SEVERE, "Không thể reset pity", ex);
            }
        });
    }

    public CompletableFuture<List<HistoryEntry>> history(UUID uuid, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> {
            List<HistoryEntry> out = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, banner_id, cycle, reward_id, reward_name, rarity, pity_at_pull, ts "
                            + "FROM pull_history WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new HistoryEntry(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                                rs.getString(5), Rarity.parse(rs.getString(6), Rarity.COMMON),
                                rs.getInt(7), rs.getLong(8)));
                    }
                }
            } catch (SQLException ex) {
                throw new CompletionException(ex);
            }
            return out;
        }, executor);
    }

    // ------------------------------------------------------------------ meta

    /** Chặn luồng gọi - dùng lúc khởi động. */
    public String getMeta(String key) throws Exception {
        return executor.submit(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }).get();
    }

    /** value = null -> xoá khoá. */
    public void setMeta(String key, String value) {
        executor.execute(() -> {
            try {
                if (value == null) {
                    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM meta WHERE k = ?")) {
                        ps.setString(1, key);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
                        ps.setString(1, key);
                        ps.setString(2, value);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException ex) {
                logger.log(Level.SEVERE, "Không thể ghi meta " + key, ex);
            }
        });
    }
}
