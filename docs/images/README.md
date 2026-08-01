# Screenshot provenance

The six PNGs beside this note are sanitized native Android documentation captures. They show
fixture data only; they contain no real training, route, heart-rate, or backup data.

They were refreshed from the `0.8.6` standalone source tree using
[`NativeDocumentationScreenshotsInstrumentedTest`](../../android/app/src/androidTest/java/dev/deftmartian/runway/NativeDocumentationScreenshotsInstrumentedTest.kt).
The fixture deliberately displays `0.0.0-screenshot` rather than a release build identity.

To refresh them, run the current standalone app's device suite on an emulator or connected device:

```sh
android/gradlew -p android --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
```

Copy only the resulting sanitized files from the app-private
`files/documentation-screenshots/` directory to the six tracked filenames here. Review the captures
at compact and expanded widths, in both themes and at increased font scale before updating README.
