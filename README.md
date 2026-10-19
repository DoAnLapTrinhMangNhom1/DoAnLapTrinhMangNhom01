# UDP Audio Streaming (VoIP mô phỏng)

Ứng dụng Java Swing truyền âm thanh một chiều trực tiếp từ một server tới nhiều client qua UDP. Phần âm thanh sử dụng Java Sound API, gói media theo header RTP version 2 (sequence number, timestamp theo clock audio, SSRC), bộ đệm jitter để sắp xếp gói và phát đều theo nhịp 20 ms. Các lệnh JOIN/LEAVE/heartbeat dùng control datagram riêng của ứng dụng, không phải RTCP.

## Yêu cầu

- JDK 17 trở lên
- Maven 3.8 trở lên
- Microphone/loa nếu chọn nguồn micro hoặc nghe trực tiếp

## Build và chạy

```powershell
mvn package
java -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar
```

Ở màn hình chính, nhập IP của server và cổng UDP (mặc định `8888`). Mở **Phát âm thanh** trên máy server, chọn microphone trong danh sách thiết bị đầu vào hoặc chọn tệp WAV; trên từng máy client, nhập cùng IP/cổng và chọn **Bắt đầu nghe**. Server chỉ gửi luồng sau khi client JOIN và hai đầu thương lượng thành công cùng codec/chế độ mã hóa. Khi đóng cửa sổ client, client gửi LEAVE để server bỏ địa chỉ khỏi danh sách.

Chọn thiết bị đầu vào trên server trước khi bắt đầu phát. Danh sách thiết bị bị khóa trong khi server đang phát; dừng phát để chọn thiết bị khác rồi khởi động lại. Khi phát WAV, danh sách microphone cũng bị khóa. Server hiển thị mức RMS của tín hiệu đầu vào; client hiển thị mức RMS của dữ liệu âm thanh đang đưa ra thiết bị phát.

Hai ứng dụng phải dùng cùng một codec và cùng khóa mã hóa (hoặc đều không đặt khóa). Cấu hình bằng system properties khi khởi chạy:

```powershell
# G.711 mu-law (mỗi mẫu PCM 16-bit thành 1 byte)
java -Dvoip.codec=ulaw -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar

# AES-GCM; đặt cùng passphrase trên server và mọi client
java -Dvoip.key="shared-demo-key" -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar

# Kết hợp
java -Dvoip.codec=ulaw -Dvoip.key="shared-demo-key" -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar
```

Mặc định là PCM signed 16-bit, mono, 16 kHz, big-endian; mỗi gói gồm 20 ms/640 byte âm thanh. G.711 mu-law giảm payload còn 320 byte/gói ở cấu hình mẫu này. Jitter buffer mặc định 100 ms; client có thể chọn 50, 75 hoặc 100 ms. Không có tính năng lưu hoặc tua lại luồng.

## Giao thức và vận hành

- UDP port server mặc định: `8888`; cần cho phép UDP inbound trên firewall của server.
- Gói media dùng RTP version 2, header cơ bản 12 byte, sequence 16-bit, timestamp 32-bit theo clock 16 kHz và SSRC. PCM dùng payload type động 96; G.711 mu-law dùng payload type chuẩn 0.
- Server phát unicast trực tiếp tới các client đã JOIN.
- Client gửi heartbeat mỗi 5 giây; server phản hồi PONG và loại các client im lặng quá 15 giây.
- Bảng client, số gói/byte gửi, gói nhận/mất, tỷ lệ mất, latency gần đúng và jitter được cập nhật trong giao diện.
- WAV được chuyển đổi bằng Java Sound sang định dạng truyền thống; định dạng WAV cần được Java Sound hỗ trợ chuyển đổi.
- AES-GCM bảo vệ payload, đồng thời xác thực header RTP; passphrase được dẫn xuất khóa bằng PBKDF2-HMAC-SHA-256. Client và server phải cùng đặt `voip.key`; nếu khóa sai, các gói không xác thực sẽ bị loại. Đây là triển khai phục vụ học tập, không thay thế SRTP/DTLS-SRTP cho sản phẩm thực tế; không dùng passphrase yếu hoặc truyền khóa trên mạng.
- JOIN thương lượng payload type và có/không mã hóa; cấu hình không khớp sẽ bị server từ chối thay vì phát luồng không tương thích.
- Latency hiển thị là ước lượng một chiều từ RTT/2; chỉ báo thêm RTT/2 + một frame + jitter buffer, không bao gồm đầy đủ độ trễ thiết bị thu/phát và không thể bảo đảm dưới 150 ms trên mọi mạng. Đồng hồ hệ thống không cần đồng bộ cho phép đo RTT này.
- Khi WAV phát hết, giao diện báo kết thúc nguồn; cần dừng server trước khi khởi động phiên phát mới.

## 6. Bảng đóng góp của thành viên

Thông tin thành viên và công việc được tổng hợp theo bảng phân công/đánh giá của nhóm. Bổ sung số hoặc liên kết Merge Request tương ứng trước khi nộp.

| MSSV | Họ và Tên | Công việc đã thực hiện
|---|---|---|
| 2380601558 | Phạm Minh Nhật | Xây dựng và xử lý logic phía client.<br>Bổ sung, sửa chữa lớp `JitterBuffer`. |
| 2380601059 | Nguyễn Anh Khoa | Xây dựng và xử lý logic phía server.<br>Xây dựng package protocol, gồm RTP, G.711 và các thành phần liên quan. |
| 2380600860 | Nguyễn Quốc Huy | Thiết kế UI/UX cho server và client.<br>Kết nối, xử lý các logic cơ bản giữa giao diện và chương trình. |
| 2380600125 | Đặng Quốc Bảo | Xây dựng lớp `JitterBuffer`.<br>Hỗ trợ thiết kế Canva. |
