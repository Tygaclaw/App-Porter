# App Porter

An Android app (built for Android 16 / One UI 8.5) that copies any installed app off your phone so you can install it on your other Android devices.

- Lists every installed app (search + optional system apps)
- **Save to phone** or **Share** (Quick Share, Drive, Bluetooth…)
  - single-file apps → `.apk`
  - split apps (most Play Store apps) → one `.apks` bundle
- **Install app file** → installs `.apk`, `.apks`, `.xapk` or `.zip` bundles on the other device

Note: Android keeps each app's private data (logins, saves, settings) locked; App Porter copies the full app itself, not that private data.

GitHub Actions builds the APK automatically on every push — see the **Releases** page.
