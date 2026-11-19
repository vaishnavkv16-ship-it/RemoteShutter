# Remote Shutter

Use one phone as a remote shutter + live viewfinder for a second phone on a tripod.
Android, Kotlin, CameraX + Google Nearby Connections (Bluetooth/Wi-Fi, no internet needed).

## Get the APK
1. Push this repo to GitHub.
2. Open the **Actions** tab, then **Build APK**, then the latest run.
3. Download the **RemoteShutter-apk** artifact, unzip it, and install `app-debug.apk` on both phones.

## Use
- Phone on tripod: open app, tap **CAMERA**.
- Phone in hand: open app, tap **REMOTE**. They connect automatically.
- Tap **CLICK** to take a full-resolution photo (saved in Pictures/RemoteShutter on the camera phone).

Both phones need Google Play Services, Bluetooth, and Wi-Fi turned on.
