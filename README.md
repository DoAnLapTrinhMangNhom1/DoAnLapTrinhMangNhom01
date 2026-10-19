# UDP Audio Streaming (VoIP mô phỏng)

Ứng dụng Java Swing truyền âm thanh một chiều trực tiếp từ một server tới nhiều client qua UDP. Phần âm thanh sử dụng Java Sound API, gói RTP rút gọn gồm sequence number/timestamp, bộ đệm jitter để sắp xếp gói và phát đều theo nhịp 20 ms.

## Yêu cầu

- JDK 17 trở lên
- Maven 3.8 trở lên
- Microphone/loa nếu chọn nguồn micro hoặc nghe trực tiếp

## Build và chạy

```powershell
mvn package
java -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar
```

Ở màn hình chính, nhập IP của server và cổng UDP (mặc định `8888`). Mở **Phát âm thanh** trên máy server, chọn Microphone hoặc tệp WAV; trên từng máy client, nhập cùng IP/cổng và chọn **Bắt đầu nghe**. Server chỉ gửi luồng sau khi client JOIN. Khi đóng cửa sổ client, client gửi LEAVE để server bỏ địa chỉ khỏi danh sách.

Hai ứng dụng phải dùng cùng một codec và cùng khóa mã hóa (hoặc đều không đặt khóa). Cấu hình bằng system properties khi khởi chạy:

```powershell
# G.711 mu-law (mỗi mẫu PCM 16-bit thành 1 byte)
java -Dvoip.codec=ulaw -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar

# AES-CTR; đặt cùng khóa trên server và mọi client
java -Dvoip.key="shared-demo-key" -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar

# Kết hợp
java -Dvoip.codec=ulaw -Dvoip.key="shared-demo-key" -jar target\VoipAudioStreaming-1.0-SNAPSHOT.jar
```

Mặc định là PCM signed 16-bit, mono, 16 kHz, big-endian; mỗi gói gồm 20 ms/640 byte âm thanh. G.711 mu-law giảm payload còn 320 byte/gói ở cấu hình mẫu này. Jitter buffer mặc định 100 ms để ưu tiên phát liên tục qua Wi-Fi; có thể điều chỉnh 50/60/75/100 ms ở client. Không có tính năng lưu hoặc tua lại luồng.

## Giao thức và vận hành

- UDP port server mặc định: `8888`; cần cho phép UDP inbound trên firewall của server.
- Header RTP mô phỏng dài 12 byte: version, loại gói, sequence 16-bit, timestamp millisecond 32-bit, độ dài payload.
- Server phát unicast trực tiếp tới các client đã JOIN.
- Client gửi heartbeat mỗi 5 giây; server phản hồi PONG và loại các client im lặng quá 15 giây.
- Bảng client, số gói/byte gửi, gói nhận/mất, tỷ lệ mất, latency gần đúng và jitter được cập nhật trong giao diện.
- WAV được chuyển đổi bằng Java Sound sang định dạng truyền thống; định dạng WAV cần được Java Sound hỗ trợ chuyển đổi.
- AES-CTR chỉ mã hóa payload và yêu cầu cùng khóa ở hai đầu. CTR không xác thực tính toàn vẹn, vì vậy đây là cơ chế minh họa học tập, không dùng thay cho SRTP trên môi trường thực tế. Không truyền khóa trên mạng.
- Latency ước lượng dựa trên timestamp đồng hồ hệ thống hai máy; để đo có ý nghĩa, đồng hồ nên được đồng bộ.
