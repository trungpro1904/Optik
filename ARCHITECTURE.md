# Optik - Tổng quan Kiến trúc & Luồng Hoạt Động (Architecture & Workflow)

Tài liệu này mô tả luồng hoạt động tổng thể và vai trò của từng thành phần trong dự án ứng dụng máy ảnh **Optik**.

---

## 1. Luồng Hoạt Động Tổng Thể (Overall Workflow)

1. **Khởi chạy ứng dụng (`MainActivity.kt`)**: 
   - Ứng dụng bắt đầu tại đây. Kiểm tra các quyền (Permissions) cần thiết: Camera, Audio, Storage.
   - Nếu đã cấp đủ quyền, sẽ đọc cấu hình từ `SettingsManager` (chế độ khởi chạy: Basic hoặc Manual).
   - Chuyển hướng người dùng sang `BasicActivity` hoặc `ManualActivity` tương ứng.

2. **Giao diện Người dùng (UI)**:
   - **`BasicActivity`**: Giao diện chụp tự động (Auto). Cung cấp trải nghiệm đơn giản, point-and-shoot.
   - **`ManualActivity`**: Giao diện chụp thủ công (Pro/Manual). Cho phép can thiệp sâu vào các thông số máy ảnh như ISO, Tốc độ màn trập (Shutter Speed), Cân bằng trắng (White Balance), Lấy nét tay (Manual Focus), Bù trừ sáng (EV), Tỉ lệ khung hình, v.v.

3. **Khởi tạo và Quản lý Camera (`CameraManagerHelper.kt`)**:
   - Khi Activity được mở (`onResume`), luồng nền (Background Thread) được khởi động.
   - Mở luồng Camera (Camera2 API), khởi tạo `AutoFitTextureView` để hiển thị bản xem trước (Preview).
   - Khởi tạo các Session chụp và các luồng đọc dữ liệu (`ImageReader` cho JPEG/RAW).

4. **Thực hiện Chụp ảnh / Quay video**:
   - **Hẹn giờ chụp (Timer)**: Nếu bật hẹn giờ (3s, 10s), đếm ngược trên UI, phát tiếng `beep` qua `SoundHelper`, sau đó kích hoạt chụp.
   - **Chụp ảnh**: Gọi `captureImage()` từ `CameraManagerHelper`. AE/AF được khóa, gửi yêu cầu chụp chất lượng cao, ghi dữ liệu từ `ImageReader` xuống bộ nhớ máy qua luồng nền, phát âm thanh `shutter_sound`.
   - **Quay video**: Khởi tạo `MediaRecorder` và OpenGL ES surface (`GlVideoProcessor.kt`) để render frame, ghi âm thanh (`rec_start` và `rec_stop`), lưu file `.mp4`.

5. **Lưu trữ & Phản hồi (`MediaStore` & `SoundHelper`, `HapticHelper`)**:
   - Ảnh/Video sau khi chụp được lưu vào thư mục hệ thống (DCIM/Optik) bằng `MediaStore`.
   - Cập nhật Thumbnail thu nhỏ ở góc màn hình.
   - Rung phản hồi (`HapticHelper`) và phát âm thanh tương ứng (`SoundHelper`).

---

## 2. Luồng Hoạt Động Của Từng Thành Phần (Component Workflows)

### A. Lõi Camera (Camera Core)
Nằm trong package `com.example.optik.camera.*`

- **`CameraManagerHelper.kt`**: Trái tim của ứng dụng.
  - **Quản lý Vòng đời**: `openCamera()`, `closeCamera()`, `startBackgroundThread()`, `stopBackgroundThread()`.
  - **Preview Flow**: Tạo `CameraCaptureSession`, liên tục đẩy các request `CaptureRequest.TEMPLATE_PREVIEW` lên sensor.
  - **Capture Flow**: Xử lý tính toán thông số Manual (ISO, S, WB) hoặc Auto, gửi request `CaptureRequest.TEMPLATE_STILL_CAPTURE`.
  - **Focus Flow**: Hỗ trợ AF (chạm lấy nét, lấy nét theo pha/hành vi) và MF (truyền tham số `LENS_FOCUS_DISTANCE`).
  - **Exposure (EV) Flow**: Ghi đè `CONTROL_AE_EXPOSURE_COMPENSATION` để bù trừ sáng khi thông số ISO/S là Auto.
  - **White Balance (WB) Flow**: Tắt `CONTROL_AWB_MODE`, thay thế bằng ma trận chỉnh màu `COLOR_CORRECTION_TRANSFORM` và hệ số `COLOR_CORRECTION_GAINS` được tính toán thủ công.

- **`GlVideoProcessor.kt` / `EglCore.kt`**: 
  - Quản lý luồng xử lý đồ hoạ OpenGL để ghi lại frame từ Camera thành Video, có thể chèn các bộ lọc (filters) thời gian thực.

### B. Giao Diện (Custom Views)
Nằm trong package `com.example.optik.view.*`

- **`AutoFitTextureView.kt`**: Surface hiển thị hình ảnh từ Camera, tự động scale theo đúng tỉ lệ khung hình (4:3, 16:9, 1:1) mà không bị méo.
- **`EvSliderView.kt`**: Giao diện thanh trượt bù trừ sáng (EV). Cho phép kéo thả mượt mà, snap vào các mức 1/3 stop. Gửi callback `onEvChangeListener` về Activity.
- **`WhitebalanceGrid.kt`**: Mặt lưới tọa độ màu 2D để tinh chỉnh WB. Cho phép kéo thả tự do chấm cam (Cursor) và báo tọa độ Tint (A-B, G-M) về Activity. Tự vẽ background map màu với góc nghiêng 45 độ.
- **`FocusSliderView.kt` / Focus UI**: Giao diện chọn AF/MF. Thanh trượt lấy nét từ gần đến vô cực (infinity).
- **`CaptureProgressView.kt`**: Vòng quay xử lý hiển thị khi phơi sáng > 1/8s. Đồng bộ với tốc độ màn trập thực tế.
- **`LevelIndicatorView.kt`**: Thước thủy cân bằng điện tử.

### C. Tiện Ích (Helpers / Managers)
Nằm trong package `com.example.optik.settings.*` và package chung.

- **`SettingsManager.kt`**: 
  - Quản lý trạng thái lưu trữ của ứng dụng bằng `SharedPreferences` (Lưu độ phân giải, tỉ lệ khung hình, ISO, chế độ Flash, Drive Mode, Grid, Level, Haptic, định dạng RAW/JPEG, v.v.).
- **`WhitebalanceHelper.kt`**: 
  - Chứa thuật toán và dữ liệu màu để quy đổi dải nhiệt độ Kelvin (2000K-10000K) thành phổ RGB. 
  - Xử lý quay trục hệ màu 45 độ (CCW) và bù trừ khử nhiễu ám xanh (Green Bias) trên cảm biến.
- **`SoundHelper.kt`**: 
  - Quản lý `SoundPool` phát âm thanh shutter, beep đếm ngược, rec start/stop.
- **`HapticHelper.kt`**: 
  - Rung phản hồi (Haptic feedback) cho thao tác UI và Capture.
- **`ExposureHelper.kt`**: 
  - Chuyển đổi các thông số kỹ thuật sang text như `1/250`, `ISO 800`.

---

## 3. Luồng Xử Lý Sự Kiện (Event Loop) Ở Chế Độ Manual

1. Người dùng bấm vào cụm `Quick Settings` (Ví dụ: Cân bằng trắng - WB).
2. Panel UI tuỳ chỉnh WB hiện lên (gồm Slider Kelvin và Grid Tint 2D). Người dùng trượt đến `5500K` và kéo lưới màu.
3. `WhitebalanceGrid` và thanh Kelvin gửi event callback về `ManualActivity`. 
4. UI Quick Settings hiển thị tức thì chữ `K1`. `ManualActivity` gọi `cameraHelper.updateManualWb(mode, kelvin, ab, gm)`.
5. `CameraManagerHelper` tính toán Gains và ma trận Transform thông qua `WhitebalanceHelper`, tắt AWB (chuyển sang chế độ Manual WB).
6. Gửi lại `CaptureRequest` cho luồng preview với hệ màu mới.
7. Khi người dùng bấm chụp, nếu có **Hẹn Giờ (Timer)**, `ManualActivity` đếm ngược và kêu *beep* (thông qua `SoundHelper`).
8. Hết giờ, `cameraHelper.captureImage()` sẽ lấy đúng thông số đang cấu hình (WB, ISO, S, MF) để build lệnh chụp (Still Capture).
9. Cảm biến thực thi chụp. Nếu thời gian chậm, `CaptureProgressView` xoay. Haptic rung nhẹ.
10. ImageReader nhận byte mảng hình ảnh gốc, tạo luồng ghi JPEG/RAW thẻ nhớ. Thumbnail góc màn hình được cập nhật ngay khi lưu xong.

---
_Tài liệu được cập nhật mới nhất cho Phiên bản Pro/Manual_
