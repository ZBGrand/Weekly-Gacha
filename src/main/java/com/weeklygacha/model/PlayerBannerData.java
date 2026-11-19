package com.weeklygacha.model;

/** Trạng thái của một người chơi trên một banner. */
public final class PlayerBannerData {

    /** Số lượt kể từ lần ra độ hiếm đỉnh gần nhất (chỉ để hiển thị). */
    private int pity;
    /** Điểm spark hiện có (bị trừ khi đổi). */
    private int spark;
    /** Tổng số lượt đã quay trên banner (không bao giờ giảm). */
    private int totalPulls;

    public int getPity() { return pity; }
    public void setPity(int pity) { this.pity = pity; }
    public int getSpark() { return spark; }
    public void setSpark(int spark) { this.spark = spark; }
    public int getTotalPulls() { return totalPulls; }
    public void setTotalPulls(int totalPulls) { this.totalPulls = totalPulls; }

    public PlayerBannerData copy() {
        PlayerBannerData c = new PlayerBannerData();
        c.pity = pity;
        c.spark = spark;
        c.totalPulls = totalPulls;
        return c;
    }
}
