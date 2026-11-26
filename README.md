# Calendar for Android

Open this folder in Android Studio or run `./gradlew :app:assembleDebug` with JDK 17+ and Android SDK 36. The app runs on Android 8 or later.

Add **Calendar** from the launcher’s widget picker. It shows the current month and up to three events from the next seven days. The app updates the widget when the signed-in calendar syncs.

Select **Continue with Google** to open the same calendar as the web app. The account menu refreshes server events and signs out.

If an earlier version saved events on this device, select **Open local calendar** on the sign-in screen or in the account menu. Local events stay on this device.

Register an **Android** OAuth client for `dev.calendar.mobile.android` in the calendar's Google Cloud project. Use the SHA-1 for the certificate that signs the APK (`./gradlew :app:signingReport` for debug). Register each certificate you distribute with. Credential Manager requests an ID token for the production web OAuth client. Set the Gradle property `calendarGoogleWebClientId` to override that public ID.

Tap an open slot to create an event, or tap an event to edit it. Touch and hold an event to move it. Swipe horizontally in Day view to navigate. Wider layouts show the 3-day and Week controls.

## CI checks

GitHub Actions runs `./gradlew :app:lintDebug :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` with JDK 17 and Android SDK 36 on every push and pull request. Instrumentation tests are compiled; executing them still requires a device or emulator.

The icon catalog and license are bundled in `assets/icons`, so this repository builds without sibling directories.

The [publish workflow](.github/workflows/publish.yml) builds and signs the release APK on `main` and updates the landing page link. See [release setup](../shared/RELEASES.md).

Signed-in calendars update automatically through the server’s authenticated event stream. Create, edit, delete, and undo changes propagate across devices on the same account. Reconnect refreshes changes missed while offline. Mobile clients reconnect on foreground; background APNs/FCM notifications and offline writes are not implemented. Server setup requires the notification migration and direct database URL described in [shared backend setup](../shared/BACKEND.md).
