package com.weeklygacha.model;

/** Một phần thưởng đã tính tiền nhưng chưa được trao (đang chờ hoạt ảnh xong hoặc server sập giữa chừng). */
public record PendingRow(long id, String batch, String bannerId, String rewardId) {
}
