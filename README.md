# RTSP Relay (Android 5.1+)

Додаток для старих телефонів (Android 5.1 / API 22), який:

1. Підключається до китайської IP-камери по RTSP
2. (наступний крок) пересилає потік на сервер Railway + MediaMTX
3. Працює у фоні через foreground-сервіс

## Телефон

Протестовано / цільовий пристрій:
- **Archos 45b Neon**
- Android **5.1**
- Kernel 3.10.65

## Що вже працює

- Поля для RTSP-адреси камери та адреси сервера
- Кнопки ЗАПУСТИТИ / ЗУПИНИТИ
- Foreground-сервіс + WakeLock (щоб Android не вбивав процес)
- Перевірка реального TCP/RTSP з’єднання з камерою (OPTIONS + DESCRIBE)
- Лог прямо в додатку
- GitHub Actions збирає debug APK

## Що ще треба зробити

Справжній **relay** (отримання RTP-пакетів → відправка на MediaMTX).

Через обмеження API 22 найкращий варіант — **FFmpeg LTS** (`-c copy`, без перекодування).

План:
1. Додати старий FFmpegKit LTS (API 16+) або бандл `ffmpeg` binary
2. Команда приблизно така:
   ```
   ffmpeg -rtsp_transport tcp -i "rtsp://камера" -c copy -f rtsp "rtsp://railway/cam"
   ```
   або через RTMP:
   ```
   ffmpeg -rtsp_transport tcp -i "rtsp://камера" -c copy -f flv "rtmp://railway/live/cam"
   ```
3. Підняти MediaMTX на Railway з TCP Proxy

## Як зібрати APK

GitHub Actions автоматично збирає APK на кожен push.

Артефакт: `rtsp-relay-debug`

Або локально (потрібен Android SDK):

```bash
./gradlew assembleDebug
```

## Приклад RTSP URL

Багато китайських камер:

```
rtsp://192.168.1.109:554/user=admin&password=&channel=1&stream=0.sdp
```

або

```
rtsp://admin:password@192.168.1.109:554/Streaming/Channels/101
```

## Архітектура

```
Китайська камера (LAN)
        │ RTSP
        ▼
  Archos 45b Neon
  (цей додаток)
        │ інтернет
        ▼
     Railway
   MediaMTX / FFmpeg
        │
        ▼
  Публічний RTSP / HLS
```

## Важливо про трафік

Камера ~2 Мбіт/с ≈ 21 ГБ/добу в один бік.  
Railway може стати дорогим при 24/7 трансляції. Спочатку тестуйте коротко.
