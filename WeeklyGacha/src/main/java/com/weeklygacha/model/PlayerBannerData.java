package com.weeklygacha.model;

/** Trạng thái pity của một người chơi trên một banner. */
public final class PlayerBannerData {

    private int pity;
    private boolean guaranteed;
    private int totalPulls;

    public int getPity() { return pity; }
    public void setPity(int pity) { this.pity = pity; }
    public boolean isGuaranteed() { return guaranteed; }
    public void setGuaranteed(boolean guaranteed) { this.guaranteed = guaranteed; }
    public int getTotalPulls() { return totalPulls; }
    public void setTotalPulls(int totalPulls) { this.totalPulls = totalPulls; }

    public PlayerBannerData copy() {
        PlayerBannerData c = new PlayerBannerData();
        c.pity = pity;
        c.guaranteed = guaranteed;
        c.totalPulls = totalPulls;
        return c;
    }
}
