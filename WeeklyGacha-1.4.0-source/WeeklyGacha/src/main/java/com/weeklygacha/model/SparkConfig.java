package com.weeklygacha.model;

/**
 * Cấu hình "spark" kiểu Limbus Company: tích đủ {@code threshold} lượt quay trên banner thì được
 * đổi thẳng một vật phẩm thuộc {@code rarity} (ưu tiên vật phẩm nổi bật). Không ảnh hưởng tỷ lệ roll.
 *
 * @param enabled      bật/tắt spark
 * @param threshold    số lượt cần để đổi một lần
 * @param rarity       độ hiếm của vật phẩm có thể đổi / độ hiếm "đỉnh" của banner
 * @param tenGuarantee mười lượt chắc chắn có ít nhất một vật phẩm từ độ hiếm này trở lên (null = tắt)
 */
public record SparkConfig(boolean enabled, int threshold, Rarity rarity, Rarity tenGuarantee) {
}
