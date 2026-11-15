# Yuzu Reader

Yuzu is an Android manga, webtoon, and comics reader based on [Mihon v0.21.3](https://github.com/mihonapp/mihon/tree/v0.21.3).

## Library sync

Open **More → Settings → Data and storage → Library sync** to choose SyncYomi, Google Drive, or OneDrive. Yuzu synchronizes your library and reading progress across devices.

- **SyncYomi** uses your server URL and API key.
- **Google Drive** uses the app data folder. The Android app's package name and signing certificate must be registered for Google OAuth, and the Drive API must be enabled.
- **OneDrive** uses the app folder. Select **OneDrive account**, then sign in with your personal Microsoft account and grant access to the app folder. Use the same Microsoft account on each device.

## Build

Use the Gradle wrapper to build a debug APK with `./gradlew :app:assembleDebug` (`.\gradlew.bat :app:assembleDebug` on Windows). See the [Mihon contribution guide](https://github.com/mihonapp/mihon/blob/v0.21.3/CONTRIBUTING.md) for Android development setup.

## License and credits

Yuzu is distributed under the [Apache License 2.0](./LICENSE). Thanks to the [Mihon contributors](https://github.com/mihonapp/mihon/graphs/contributors).

- Copyright © 2015 Javier Tomás
- Copyright © 2024 Mihon Open Source Project
