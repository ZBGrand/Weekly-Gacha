package com.weeklygacha.model;

import java.util.List;

/**
 * Kết quả một lần quay. Phần thưởng CHƯA được trao: gọi {@code GachaService.deliver} sau khi hiển thị xong.
 *
 * @param batch mã lô quà đang chờ trao (null khi thất bại)
 */
public record PullResult(boolean success, List<PullEntry> entries, double cost, String batch) {

    public static final PullResult FAIL = new PullResult(false, List.of(), 0, null);
}
