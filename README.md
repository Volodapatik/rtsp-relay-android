# RTSP Relay (Android 5.1+)

Додаток для старих телефонів (Android 5.1 / API 22).

**Цільовий пристрій:** Archos 45b Neon (Android 5.1)

## Що вміє (v0.3.0)

- Перевірка RTSP-з’єднання з камерою (OPTIONS + DESCRIBE)
- Foreground-сервіс + WakeLock
- **FFmpeg relay** (copy, без перекодування): RTSP → RTMP або RTSP
- Автоперезапуск FFmpeg при падінні
- GitHub Actions збирає debug APK

## FFmpeg binary (обов’язково для трансляції)

Щоб реальний push працював, потрібен статичний `ffmpeg` для **armeabi-v7a**:

1. Поклади файл сюди:
   ```
   app/src/main/assets/ffmpeg
   ```
2. Бінарник має вміти: RTSP input, RTMP/FLV output, `-c copy`
3. Перезбери APK

Без цього файлу додаток лише перевіряє камеру і пише в лог, що FFmpeg відсутній.

## Використання

1. RTSP камери, наприклад:
   ```
   rtsp://192.168.1.109:554/user=admin&password=&channel=1&stream=0.sdp
   ```
2. Адреса сервера (після Railway/MediaMTX):
   ```
   rtmp://HOST:PORT/cam
   ```
   або
   ```
   rtsp://HOST:PORT/cam
   ```
3. ЗАПУСТИТИ

## Архітектура

```
Камера (LAN) --RTSP--> Archos 5.1 --FFmpeg--> MediaMTX (Railway)
                                              |
                                         RTSP / HLS / RTMP
                                              |
                                         Ти дивишся
```

## Збірка

GitHub Actions → артефакт `rtsp-relay-debug`

```bash
gradle assembleDebug
```
