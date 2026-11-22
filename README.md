# Video/Audio Converter – Android

Android-versie van Video/Audio Converter: download video's als **MP4** of audio als **MP3**
van YouTube en honderden andere sites. Werkt volledig in de app (yt-dlp + FFmpeg via
[youtubedl-android](https://github.com/yausername/youtubedl-android)), geen server nodig.

Gebouwd op [android-app-template](https://github.com/basbas011/android-app-template).

© Bas Verbeek

## Functies
- Link plakken of **delen vanuit de YouTube-app** (Delen → Video/Audio Converter)
- MP4 (beste / 1080p / 720p / 480p / 360p) of MP3 (320 / 192 / 128 kbps)
- Opslaan in **Download/Video Audio Converter**
- yt-dlp werkt zichzelf 1× per dag bij (+ knop "Downloader bijwerken")
- **App werkt zichzelf bij** via GitHub Releases ("Zoek naar app-updates")

## Nieuwe versie uitbrengen
```
release.bat 1.0.1
```
Dit commit, tagt `v1.0.1` en pusht. GitHub Actions bouwt de APK's en zet ze in een
Release. De app ziet de update bij het opstarten of via "Zoek naar app-updates".

Per release komen er drie APK's:

| Bestand | Voor |
|---|---|
| `…-arm64-v8a.apk` | bijna alle telefoons |
| `…-armeabi-v7a.apk` | oude/goedkope telefoons |
| `…-x86_64.apk` | BlueStacks / emulators |

De in-app updater kiest automatisch de juiste.

## Lokaal bouwen
`build-local.bat 1.0.0` → `dist\VideoAudioConverter-1.0.0-<abi>.apk`
(vereist `keystore.properties` + `keystore\release.jks`, zie template-README).

## Secrets (eenmalig per repo)
Settings → Secrets and variables → Actions:
- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`

Gebruik dezelfde keystore als bij je andere apps.
