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
   - **`ManualActivity`**: Giao diện chụp thủ công (Pro/Manual). Cho phép can thiệp sâu vào các thông số máy ảnh như ISO, Tốc độ màn trập (Shutter Speed), Lấy nét tay (Manual Focus), Bù trừ sáng (EV), Tỉ lệ khung hình, v.v.

3. **Khởi tạo và Quản lý Camera (`CameraManagerHelper.kt`)**:
   - Khi Activity được mở (`onResume`), luồng nền (Background Thread) được khởi động.
   - Mở luồng Camera (Camera2 API), khởi tạo `AutoFitTextureView` để hiển thị bản xem trước (Preview).
   - Khởi tạo các Session chụp và các luồng đọc dữ liệu (`ImageReader` cho JPEG/RAW).

4. **Thực hiện Chụp ảnh / Quay video**:
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
  - **Capture Flow**: Xử lý tính toán thông số Manual (ISO, S) hoặc Auto, gửi request `CaptureRequest.TEMPLATE_STILL_CAPTURE`.
  - **Focus Flow**: Hỗ trợ AF (chạm lấy nét, lấy nét theo pha/hành vi) và MF (truyền tham số `LENS_FOCUS_DISTANCE`). Lấy nét tracking thông qua `FaceTracker` / `ObjectTracker`.
  - **Exposure (EV) Flow**: Cho phép ghi đè `CONTROL_AE_EXPOSURE_COMPENSATION` để bù trừ sáng khi thông số là Auto.

- **`GlVideoProcessor.kt` / `EglCore.kt`**: 
  - Quản lý luồng xử lý đồ hoạ OpenGL để ghi lại frame từ Camera thành Video, có thể chèn các bộ lọc (filters) thời gian thực.

### B. Giao Diện (Custom Views)
Nằm trong package `com.example.optik.view.*`

- **`AutoFitTextureView.kt`**: Surface hiển thị hình ảnh từ Camera, tự động scale theo đúng tỉ lệ khung hình (4:3, 16:9, 1:1) mà không bị méo.
- **`EvSliderView.kt`**: Giao diện thanh trượt bù trừ sáng (EV). Cho phép kéo thả mượt mà, snap vào các mức 1/3 stop. Gửi callback `onEvChangeListener` về Activity.
- **`FocusSliderView.kt` / Focus UI**: Giao diện chọn AF/MF. Khi ở chế độ MF, hiển thị thanh trượt khoảng cách lấy nét từ gần đến vô cực (infinity).
- **`CaptureProgressView.kt`**: Vòng quay xử lý hiển thị khi thời gian phơi sáng > 1/8s. Đồng bộ với tốc độ màn trập (Shutter Speed) thực tế.
- **`LevelIndicatorView.kt`**: Thước thủy cân bằng điện tử. Lấy dữ liệu từ cảm biến gia tốc/trọng lực của điện thoại để vẽ UI báo hiệu điện thoại đang bị nghiêng/lệch.

### C. Tiện Ích (Helpers / Managers)
Nằm trong package `com.example.optik.settings.*` và package chung.

- **`SettingsManager.kt`**: 
  - Quản lý trạng thái lưu trữ của ứng dụng bằng `SharedPreferences` (Lưu độ phân giải, tỉ lệ khung hình, ISO, chế độ Flash, Grid, Level, Haptic, định dạng RAW/JPEG, v.v.).
- **`SoundHelper.kt`**: 
  - Tải các file `.wav` từ thư mục `res/raw/`. 
  - Quản lý `SoundPool` để phát ngay lập tức các âm thanh: `shutter_sound` (chụp), `rec_start` / `rec_stop` (quay video), `beep` (đếm ngược 3s).
- **`HapticHelper.kt`**: 
  - Rung phản hồi (Haptic feedback) cho các thao tác trượt, bấm nút, cảnh báo.
- **`ExposureHelper.kt`**: 
  - Chuyển đổi các thông số kỹ thuật (như nanoseconds) sang chuỗi định dạng dễ đọc cho con người (vd: `1/250`, `1/8`, `1"`, `ISO 800`).

---

## 3. Luồng Xử Lý Sự Kiện (Event Loop) Ở Chế Độ Manual

1. Người dùng bấm vào cụm `Quick Settings` để chỉnh sửa (ví dụ: Tốc độ màn trập).
2. Panel chọn S hiện lên. Người dùng chọn `1/125`.
3. `ManualActivity` gọi `cameraHelper.updateManualShutter(shutterNs)`.
4. `CameraManagerHelper` lưu giá trị, tắt cờ AE (Auto Exposure) trên cảm biến (chuyển sang `CONTROL_AE_MODE_OFF`).
5. Gửi lại `CaptureRequest` cho luồng preview với Shutter và ISO mới.
6. Khi người dùng bấm chụp, `cameraHelper.captureImage()` sẽ lấy đúng thông số đang cấu hình để build lệnh chụp (Still Capture) gửi xuống phần cứng máy ảnh.
7. ImageReader nhận byte mảng hình ảnh, tạo một luồng (thread) mới ghi thẳng ra thẻ nhớ. Cùng lúc, `SoundHelper` kêu cạch, `CaptureProgressView` quay (nếu chụp chậm), và Thumbnail ở góc cập nhật khi file đã lưu xong.

---
_Tài liệu được tạo tự động để hỗ trợ cho việc bảo trì và mở rộng tính năng sau này._
