# WeeklyGacha

Plugin gacha cho **Paper 1.21+** (Java 21): banner giới hạn xoay vòng 14 ngày, banner thường, tỷ lệ riêng từng vật phẩm,
spark kiểu Limbus Company, hoạt ảnh lật thẻ, GUI, lệnh admin đổi banner thủ công, lưu lịch sử và số lượt quay (SQLite).

- Phần thưởng: **MMOItems**, **ItemsAdder**, vật phẩm vanilla, hoặc lệnh console
- Chi phí quay: **điểm PlayerPoints** hoặc **vật phẩm key** (MMOItems, ItemsAdder hoặc vật phẩm/key của plugin khác) — không còn dùng Vault

## Build ra file JAR

Yêu cầu: **JDK 21** (hoặc mới hơn) và **Maven 3.9+**, máy build truy cập được `repo.papermc.io`, `jitpack.io` và Maven Central.

```bash
java -version      # phải là 21 trở lên
mvn -version

cd WeeklyGacha
mvn clean package
```

File thành phẩm: `target/WeeklyGacha-1.4.0.jar`.
Không cần shade vì plugin không đóng gói thư viện nào (Paper 1.21+ đã có sẵn SQLite JDBC, Adventure và MiniMessage; MMOItems/ItemsAdder gọi bằng reflection).

Nếu IDE (IntelliJ/Eclipse) báo thiếu thư viện: mở `pom.xml`, bấm *Reload Maven project*.
Bản này biên dịch với `paper-api 1.21.1-R0.1-SNAPSHOT` để chạy được trên mọi Paper 1.21 trở lên. Cần Paper (không phải Spigot thuần) vì dùng Adventure/MiniMessage.

## Cài đặt

1. Server Paper 1.21 trở lên, Java 21. Nếu dùng chi phí điểm: cài **PlayerPoints**. Nếu dùng key: không cần plugin phụ trợ (trừ MMOItems/ItemsAdder nếu key lấy từ đó).
2. (Tuỳ chọn) cài MMOItems (kèm MythicLib) và/hoặc ItemsAdder.
3. Chép `WeeklyGacha-1.4.0.jar` vào `plugins/`, khởi động server.
4. Lần chạy đầu plugin tạo 4 file cấu hình trong `plugins/WeeklyGacha/` (xem mục *Các file cấu hình*). Sửa xong chạy `/gachaadmin reload`.
5. Chạy `/gachaadmin check` để kiểm tra mọi vật phẩm đã khai báo có tạo được không.

## Các file cấu hình

| File | Nội dung |
|---|---|
| `config.yml` | Cài đặt chung (cooldown, thông báo toàn server), thời gian hoạt ảnh, chu kỳ và vòng xoay banner |
| `banners.yml` | Danh sách banner: vật phẩm, tỷ lệ, chi phí, spark, giới hạn lượt, bảo đảm độ hiếm |
| `messages.yml` | Mọi tin nhắn chat / phản hồi lệnh |
| `gui.yml` | Toàn bộ giao diện: tiêu đề, chữ, vật phẩm của từng nút (chất liệu, tên, lore, số lượng, hiệu ứng phát sáng), **vị trí ô**, số hàng, độ hiếm (tên + màu), âm thanh, màu hoạt ảnh, định dạng thời gian/chi phí |

Sửa bất kỳ file nào rồi chạy `/gachaadmin reload` là áp dụng ngay, không cần build lại plugin. Khoá nào thiếu trong
`messages.yml` hoặc `gui.yml` sẽ tự lấy giá trị mặc định của plugin, nên nâng cấp plugin không làm mất mục mới.
Nếu một file bị lỗi cú pháp YAML, plugin ghi lỗi vào log và tạm dùng bản mặc định (file của bạn không bị ghi đè).

**Chỉnh giao diện trong `gui.yml`** (đầu file có hướng dẫn chi tiết):
- `{ten}` là biến do plugin điền (mỗi mục ghi rõ có những biến nào), ví dụ `{cost}`, `{balance}`, `{banner}`.
- Dòng lore bắt đầu bằng `[if:dieu-kien]` chỉ hiện khi điều kiện bật, `[if:!dieu-kien]` hiện khi điều kiện tắt
  (ví dụ `[if:limit]` chỉ hiện ở banner có giới hạn lượt). Có thể ghép `[if:a][if:!b]`.
- Dòng chỉ gồm một khối như `{description}`, `{spark-lines}` được thay bằng nhiều dòng do plugin cung cấp.
- Mỗi nút có `slot` (ô trong GUI, từ 0), nên bạn có thể dời nút; `enabled: false` để ẩn một nút.
- `main.rows`, `result.rows`... đổi số hàng của menu; `result.slots-single/slots-multi` đổi vị trí các thẻ kết quả.
- Âm thanh viết dạng `"khoa-am-thanh am-luong cao-do"`, ví dụ `"block.note_block.pling 0.8 1.2"`; ghi `none` để tắt.
- Hai màn hình tỷ lệ và lịch sử luôn dùng 45 ô đầu cho vật phẩm và hàng cuối cho nút điều hướng.

**Nâng cấp từ bản cũ chỉ có một `config.yml`:** nếu `banners.yml` và `messages.yml` chưa tồn tại, plugin tự chuyển mục
`banners` và `messages` trong `config.yml` cũ sang hai file mới (mất chú thích) và tạo `gui.yml` mặc định. Các mục cũ
trong `config.yml` không còn được dùng, bạn có thể xoá.

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
| `/gachaadmin resetlimit <người chơi> [banner]` | Xoá số lượt đã dùng (banner có `limit.max-pulls`) |
| `/gachaadmin history <người chơi> [số dòng]` | Xem lịch sử của người chơi |
| `/gachaadmin check` | Kiểm tra vật phẩm và chi phí cấu hình |
| `/gachaadmin givekey <người chơi> <banner> [số lượng]` | Phát key quay (banner dùng `cost.type: ITEM`) |

Quyền: `weeklygacha.use` (mặc định mọi người), `weeklygacha.admin` (mặc định OP).

## Cách hoạt động

**Chu kỳ 14 ngày.** Chu kỳ thứ *n* kể từ `cycle.start-date` dùng `rotation[n % số_banner]`. Banner `NORMAL` luôn mở.
Admin có thể ghi đè bằng `/gachaadmin setbanner`; mặc định ghi đè chỉ có hiệu lực đến hết chu kỳ hiện tại
(`cycle.clear-override-on-new-cycle`). Khi chu kỳ đổi, server tự thông báo.

**Ba banner mẫu** trong `banners.yml`:
- **Standard Extraction** (`NORMAL`, luôn mở): quay bằng điểm PlayerPoints, có spark 300 lượt.
- **New Item Target Extraction** (`LIMITED`, xoay vòng 14 ngày): tăng tỷ lệ vật phẩm mới (`featured`), quay bằng key,
  có spark 200 lượt. Hiện `cycle.rotation` chỉ có một banner; thêm banner `LIMITED` khác vào danh sách để xoay vòng.
- **Assured Extraction** (`NORMAL`, luôn mở): mỗi người chơi chỉ quay được **1 lần duy nhất** và chắc chắn ra vật phẩm
  Huyền thoại. Không có spark, không quay x10. Cấu hình bằng `limit.max-pulls: 1` và `guarantee.rarity: LEGENDARY`.

**Giới hạn lượt và bảo đảm độ hiếm** (áp dụng cho bất kỳ banner nào):
- `limit.max-pulls`: số lượt tối đa mỗi người chơi được quay trên banner suốt đời (0 = không giới hạn). Dựa trên tổng lượt
  đã quay lưu trong DB nên vẫn đúng sau khi khởi động lại; đặt lại cho một người bằng `/gachaadmin resetlimit`.
- `guarantee.rarity`: mọi lượt quay của banner chắc chắn ra độ hiếm này trở lên (banner phải có vật phẩm đạt độ hiếm đó,
  nếu không banner bị bỏ qua khi nạp và có cảnh báo trong log).

**Chi phí quay** (đặt riêng cho từng banner trong `cost`):
- `type: PLAYERPOINTS`: trừ điểm PlayerPoints (`single` điểm cho 1 lượt, `ten` điểm cho 10 lượt).
- `type: ITEM`: trừ vật phẩm key trong túi đồ (`single`/`ten` là số key). Key khai báo trong `cost.item`:
  `provider: MMOITEMS` (type + item), `ITEMSADDER` (namespace:id) hoặc `VANILLA` (material, có thể khớp thêm `name`
  và `custom-model-data` để nhận key do plugin khác tạo hoặc key chế tạo bằng recipe). Key được nhận diện theo ID thật
  của MMOItems/ItemsAdder nên đổi tên hiển thị không làm hỏng key. Phát key cho người chơi bằng `/gachaadmin givekey`.
- Điểm/key chỉ bị trừ khi mọi bước kiểm tra đã qua; nếu có lỗi thì người chơi không mất gì.

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
**Quà chỉ được trao sau khi hoạt ảnh chạy xong.** Điểm/key bị trừ và kết quả được chốt ngay lúc quay, nhưng vật phẩm
chỉ vào túi đồ khi thẻ cuối cùng đã lật, hoặc ngay khi người chơi bấm "Bỏ qua", đóng GUI, thoát game hay server tắt.
Quà chờ trao được lưu trong DB (bảng `pending_reward`) nên nếu server sập giữa chừng, người chơi nhận lại quà
ở lần đăng nhập kế tiếp. Thông báo toàn server cũng chờ đến khi lật xong.

**An toàn khi quay.** Nếu vật phẩm chưa sẵn sàng (sai ID MMOItems/ItemsAdder, plugin chưa tải xong) thì lượt quay bị huỷ
**trước khi trừ điểm/key** và dữ liệu không đổi. Túi đồ đầy thì vật phẩm rơi xuống đất cạnh người chơi.

## Dữ liệu

`plugins/WeeklyGacha/data.db` (SQLite): bảng `player_banner` (spark, bộ đếm hiển thị, tổng lượt), `pull_history` (mọi lượt quay),
`meta` (mốc chu kỳ và banner admin ghi đè). Mọi thao tác DB chạy ở luồng riêng, không làm lag server.

## Cấu trúc mã nguồn

```
src/main/java/com/weeklygacha/
  WeeklyGachaPlugin.java        lớp chính, theo dõi đổi chu kỳ
  model/                        Banner, Reward, Rarity, SparkConfig, PlayerData, HistoryEntry...
  service/BannerManager.java    nạp banner, chu kỳ 14 ngày, ghi đè thủ công
  service/CostService.java      chi phí: PlayerPoints hoặc key (MMOItems/ItemsAdder/vanilla)
  service/GachaService.java     kiểm tra, trừ chi phí, roll, đổi spark, trao thưởng, lịch sử
  service/RewardFactory.java    tạo item (Vanilla / MMOItems / ItemsAdder / Command)
  service/MessageService.java   tin nhắn MiniMessage từ messages.yml
  storage/Database.java         SQLite
  gui/                          GachaMenu (logic các màn hình), GuiConfig (đọc gui.yml), GachaAnimation (hoạt ảnh), MenuHolder, MenuListener
  command/                      GachaCommand, GachaAdminCommand
  listener/PlayerListener.java  nạp/dọn dữ liệu người chơi
src/main/resources/             plugin.yml, config.yml, banners.yml, messages.yml, gui.yml
```
