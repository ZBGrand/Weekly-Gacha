# WeeklyGacha

Plugin gacha cho **Paper 1.21.x** (Java 21): banner giới hạn xoay vòng 14 ngày, banner thường, tỷ lệ riêng từng vật phẩm,
pity / bảo hiểm, GUI, lệnh admin đổi banner thủ công, lưu lịch sử và số lượt quay (SQLite).

- Phần thưởng: **MMOItems**, **ItemsAdder**, vật phẩm vanilla, hoặc lệnh console
- Chi phí: tiền trong game qua **Vault** (cần Vault + một plugin kinh tế như EssentialsX)

## Build ra file JAR

Yêu cầu: **JDK 21** và **Maven 3.9+**, máy build truy cập được `repo.papermc.io`, `jitpack.io` và Maven Central.

```bash
java -version      # phải là 21 trở lên
mvn -version

cd WeeklyGacha
mvn clean package
```

File thành phẩm: `target/WeeklyGacha-1.0.0.jar`.
Không cần shade vì plugin không đóng gói thư viện nào (Paper đã có sẵn SQLite JDBC và Adventure; MMOItems/ItemsAdder gọi bằng reflection).

Nếu IDE (IntelliJ/Eclipse) báo thiếu thư viện: mở `pom.xml`, bấm *Reload Maven project*.
Nếu muốn build cho bản Paper khác, đổi `paper-api` trong `pom.xml`
(mặc định `1.21.1-R0.1-SNAPSHOT`, chạy được trên mọi 1.21.x).

## Cài đặt

1. Cài **Vault** và một plugin kinh tế (EssentialsX, CMI...) trên server Paper 1.21.x, Java 21.
2. (Tuỳ chọn) cài MMOItems (kèm MythicLib) và/hoặc ItemsAdder.
3. Chép `WeeklyGacha-1.0.0.jar` vào `plugins/`, khởi động server.
4. Sửa `plugins/WeeklyGacha/config.yml`, rồi chạy `/gachaadmin reload`.
5. Chạy `/gachaadmin check` để kiểm tra mọi vật phẩm đã khai báo có tạo được không.

## Lệnh

| Lệnh | Mô tả |
|---|---|
| `/gacha` | Mở GUI |
| `/gacha pull [1\|10] [banner]` | Quay nhanh |
| `/gacha rates [banner]` | Xem tỷ lệ từng vật phẩm |
| `/gacha history` | Lịch sử quay |
| `/gacha pity [banner]` | Xem trạng thái bảo hiểm |
| `/gacha info` | Các banner đang mở |
| `/gachaadmin info` | Thông tin chu kỳ hiện tại |
| `/gachaadmin list` | Danh sách banner |
| `/gachaadmin setbanner <id>` | Đổi banner giới hạn thủ công |
| `/gachaadmin auto` | Quay lại xoay vòng tự động |
| `/gachaadmin next` | Chuyển sang banner kế tiếp trong vòng xoay |
| `/gachaadmin restartcycle [config\|yyyy-MM-dd]` | Đặt lại mốc bắt đầu chu kỳ (mặc định: ngay bây giờ) |
| `/gachaadmin reload` | Nạp lại config |
| `/gachaadmin resetpity <người chơi> [banner]` | Xoá pity |
| `/gachaadmin history <người chơi> [số dòng]` | Xem lịch sử của người chơi |
| `/gachaadmin check` | Kiểm tra vật phẩm cấu hình |

Quyền: `weeklygacha.use` (mặc định mọi người), `weeklygacha.admin` (mặc định OP).

## Cách hoạt động

**Chu kỳ 14 ngày.** Chu kỳ thứ *n* kể từ `cycle.start-date` dùng `rotation[n % số_banner]`. Banner `NORMAL` luôn mở.
Admin có thể ghi đè bằng `/gachaadmin setbanner`; mặc định ghi đè chỉ có hiệu lực đến hết chu kỳ hiện tại
(`cycle.clear-override-on-new-cycle`). Khi chu kỳ đổi, server tự thông báo.

**Tỷ lệ riêng.** Mỗi vật phẩm có `chance` (%) riêng. Tổng lệch 100% thì plugin tự chuẩn hoá và cảnh báo trong log.

**Pity** (tính riêng cho từng người chơi và từng banner):
- *Soft pity*: từ lượt `soft-start`, trọng số các vật phẩm thuộc độ hiếm `pity.rarity` nhân với `1 + soft-boost × (lượt − soft-start + 1)`.
- *Hard pity*: lượt thứ `hard` chắc chắn ra độ hiếm đó.
- *Guarantee featured*: nếu lần ra độ hiếm cao gần nhất không phải vật phẩm `featured`, lần kế tiếp chắc chắn là `featured`.

**An toàn khi quay.** Nếu vật phẩm chưa sẵn sàng (sai ID MMOItems/ItemsAdder, plugin chưa tải xong) thì lượt quay bị huỷ
**trước khi trừ tiền** và pity không đổi. Túi đồ đầy thì vật phẩm rơi xuống đất cạnh người chơi.

## Dữ liệu

`plugins/WeeklyGacha/data.db` (SQLite): bảng `player_banner` (pity, cờ đảm bảo, tổng lượt), `pull_history` (mọi lượt quay),
`meta` (mốc chu kỳ và banner admin ghi đè). Mọi thao tác DB chạy ở luồng riêng, không làm lag server.

## Cấu trúc mã nguồn

```
src/main/java/com/weeklygacha/
  WeeklyGachaPlugin.java        lớp chính, Vault, theo dõi đổi chu kỳ
  model/                        Banner, Reward, Rarity, PityConfig, PlayerData, HistoryEntry...
  service/BannerManager.java    nạp banner, chu kỳ 14 ngày, ghi đè thủ công
  service/GachaService.java     kiểm tra, trừ tiền, roll + pity, trao thưởng, lịch sử
  service/RewardFactory.java    tạo item (Vanilla / MMOItems / ItemsAdder / Command)
  service/MessageService.java   tin nhắn MiniMessage từ config
  storage/Database.java         SQLite
  gui/                          GachaMenu (các màn hình), MenuHolder, MenuListener
  command/                      GachaCommand, GachaAdminCommand
  listener/PlayerListener.java  nạp/dọn dữ liệu người chơi
src/main/resources/             plugin.yml, config.yml
```
