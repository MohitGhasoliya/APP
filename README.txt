MobiPC Connect - Android app (Android Studio project)

1. Open Android Studio -> File -> Open -> choose this MobiPC-Android folder.
2. Wait for "Gradle sync" to finish (needs internet the first time).
3. Connect your phone with USB debugging on, press the green Run button.
   Or: Build -> Build Bundle(s) / APK(s) -> Build APK(s) to get an .apk file.
4. In the app: sign in, tap "Set up this device", then "Start hosting this screen".
5. For remote control: Settings -> Accessibility -> MobiPC Remote Control -> turn on.
   (If Android says "restricted setting": App info -> three dots -> Allow restricted settings.)

The website lives in app/src/main/assets/index.html. Update it by replacing that file.
