# WeeklyGacha

Plugin gacha cho **Paper 1.21+** (Java 21): banner giới hạn xoay vòng 14 ngày, banner thường, tỷ lệ riêng từng vật phẩm,
spark kiểu Limbus Company, hoạt ảnh lật thẻ, GUI, lệnh admin đổi banner thủ công, lưu lịch sử và số lượt quay (SQLite).

- Phần thưởng: **MMOItems**, **ItemsAdder**, vật phẩm vanilla, hoặc lệnh console
- Chi phí: tiền trong game qua **Vault** (cần Vault + một plugin kinh tế như EssentialsX)

## Build ra file JAR

Yêu cầu: **JDK 21** (hoặc mới hơn) và **Maven 3.9+**, máy build truy cập được `repo.papermc.io`, `jitpack.io` và Maven Central.

```bash
java -version      # phải là 21 trở lên
mvn -version

cd WeeklyGacha
mvn clean package
```

File thành phẩm: `target/WeeklyGacha-1.1.0.jar`.
Không cần shade vì plugin không đóng gói thư viện nào (Paper 1.21+ đã có sẵn SQLite JDBC, Adventure và MiniMessage; MMOItems/ItemsAdder gọi bằng reflection).

Nếu IDE (IntelliJ/Eclipse) báo thiếu thư viện: mở `pom.xml`, bấm *Reload Maven project*.
Bản này biên dịch với `paper-api 1.21.1-R0.1-SNAPSHOT` để chạy được trên mọi Paper 1.21 trở lên. Cần Paper (không phải Spigot thuần) vì dùng Adventure/MiniMessage.

## Cài đặt

1. Cài **Vault** và một plugin kinh tế (EssentialsX, CMI...) trên server Paper 1.21 trở lên, Java 21.
2. (Tuỳ chọn) cài MMOItems (kèm MythicLib) và/hoặc ItemsAdder.
3. Chép `WeeklyGacha-1.1.0.jar` vào `plugins/`, khởi động server.
4. Sửa `plugins/WeeklyGacha/config.yml`, rồi chạy `/gachaadmin reload`.
5. Chạy `/gachaadmin check` để kiểm tra mọi vật phẩm đã khai báo có tạo được không.

## Lệnh

| Lệnh | Mô tả |
|---|---|
| `/gacha` | Mở GUI |
| `/gacha pull [1\|10] [banner]` | Quay nhanh |
| `/gacha rates [banner]` | Xem tỷ lệ từng vật phẩm |
| `/gacha history` | Lịch sử quay |
| `/gacha spark [banner]` | Xem tiến độ spark |
| `/gacha animation` | Bật/tắt hoạt ảnh quay (trong phiên) |
| `/gacha info` | Các banner đang mở |
| `/gachaadmin info` | Thông tin chu kỳ hiện tại |
| `/gachaadmin list` | Danh sách banner |
| `/gachaadmin setbanner <id>` | Đổi banner giới hạn thủ công |
| `/gachaadmin auto` | Quay lại xoay vòng tự động |
| `/gachaadmin next` | Chuyển sang banner kế tiếp trong vòng xoay |
| `/gachaadmin restartcycle [config\|yyyy-MM-dd]` | Đặt lại mốc bắt đầu chu kỳ (mặc định: ngay bây giờ) |
| `/gachaadmin reload` | Nạp lại config |
| `/gachaadmin resetspark <người chơi> [banner]` | Xoá điểm spark |
| `/gachaadmin history <người chơi> [số dòng]` | Xem lịch sử của người chơi |
| `/gachaadmin check` | Kiểm tra vật phẩm cấu hình |

Quyền: `weeklygacha.use` (mặc định mọi người), `weeklygacha.admin` (mặc định OP).

## Cách hoạt động

**Chu kỳ 14 ngày.** Chu kỳ thứ *n* kể từ `cycle.start-date` dùng `rotation[n % số_banner]`. Banner `NORMAL` luôn mở.
Admin có thể ghi đè bằng `/gachaadmin setbanner`; mặc định ghi đè chỉ có hiệu lực đến hết chu kỳ hiện tại
(`cycle.clear-override-on-new-cycle`). Khi chu kỳ đổi, server tự thông báo.

**Tỷ lệ riêng.** Mỗi vật phẩm có `chance` (%) riêng. Tổng lệch 100% thì plugin tự chuẩn hoá và cảnh báo trong log.

**Spark (kiểu Limbus Company).** Mỗi lượt quay cộng 1 điểm spark cho banner đó. Đủ `spark.threshold` điểm (mặc định 200,
banner thường 300) thì người chơi bấm vào nút Spark trong GUI, **tự chọn** một vật phẩm thuộc `spark.rarity`
(chỉ trong nhóm `featured` nếu banner có) rồi xác nhận để đổi; đổi xong bị trừ đúng `threshold` điểm.
Ra độ hiếm cao khi quay thường **không** làm mất điểm spark, và spark không ảnh hưởng tỷ lệ roll.
Quay x10 chắc chắn có ít nhất một vật phẩm từ `spark.ten-guarantee` trở lên (mặc định RARE).

**Hoạt ảnh quay.** Màn hình kết quả có hoạt ảnh: thẻ úp xáo màu, từng thẻ sáng theo màu độ hiếm rồi lật ra vật phẩm
(âm thanh khác nhau theo độ hiếm; EPIC/LEGENDARY làm viền nhấp nháy). Có nút "Bỏ qua"; đóng GUI giữa chừng thì
kết quả vẫn nằm trong túi đồ và được báo bằng chat. Thời gian chỉnh ở mục `animation` trong config;
người chơi tắt/bật riêng bằng `/gacha animation` hoặc nút trong menu (chỉ nhớ trong phiên).
Phần thưởng luôn được trao ngay lúc quay, hoạt ảnh chỉ để hiển thị; thông báo toàn server chờ đến khi lật xong.

**An toàn khi quay.** Nếu vật phẩm chưa sẵn sàng (sai ID MMOItems/ItemsAdder, plugin chưa tải xong) thì lượt quay bị huỷ
**trước khi trừ tiền** và dữ liệu không đổi. Túi đồ đầy thì vật phẩm rơi xuống đất cạnh người chơi.

## Dữ liệu

`plugins/WeeklyGacha/data.db` (SQLite): bảng `player_banner` (spark, bộ đếm hiển thị, tổng lượt), `pull_history` (mọi lượt quay),
`meta` (mốc chu kỳ và banner admin ghi đè). Mọi thao tác DB chạy ở luồng riêng, không làm lag server.

## Cấu trúc mã nguồn

```
src/main/java/com/weeklygacha/
  WeeklyGachaPlugin.java        lớp chính, Vault, theo dõi đổi chu kỳ
  model/                        Banner, Reward, Rarity, SparkConfig, PlayerData, HistoryEntry...
  service/BannerManager.java    nạp banner, chu kỳ 14 ngày, ghi đè thủ công
  service/GachaService.java     kiểm tra, trừ tiền, roll, đổi spark, trao thưởng, lịch sử
  service/RewardFactory.java    tạo item (Vanilla / MMOItems / ItemsAdder / Command)
  service/MessageService.java   tin nhắn MiniMessage từ config
  storage/Database.java         SQLite
  gui/                          GachaMenu (các màn hình), GachaAnimation (hoạt ảnh), MenuHolder, MenuListener
  command/                      GachaCommand, GachaAdminCommand
  listener/PlayerListener.java  nạp/dọn dữ liệu người chơi
src/main/resources/             plugin.yml, config.yml
```
