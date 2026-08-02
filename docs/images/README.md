# Screenshot provenance

The six PNGs beside this note are sanitized native Android documentation captures. They show
fixture data only; they contain no real training, route, heart-rate, or backup data.

The tracked copies were refreshed on 2026-08-01 from the source and fixture stored in the same Git
commit as these PNGs, using
[`NativeDocumentationScreenshotsInstrumentedTest`](../../android/app/src/androidTest/java/dev/deftmartian/runway/NativeDocumentationScreenshotsInstrumentedTest.kt).
The fixture deliberately displays `0.0.0-screenshot` rather than a release build identity.

To refresh them, run the current standalone app's device suite on an emulator or connected device:

```sh
android/gradlew -p android --no-daemon --max-workers=1 :app:connectedDebugAndroidTest
```

Copy only these sanitized files from the app-private `files/documentation-screenshots/` directory:

| Generated file | Tracked file |
| --- | --- |
| `calendar-dark.png` | `runway-android-calendar-dark.png` |
| `calendar-expanded-light.png` | `runway-android-calendar-expanded-light.png` |
| `inbox-light.png` | `runway-android-inbox-light.png` |
| `history-light.png` | `runway-android-history-light.png` |
| `stats-light.png` | `runway-android-stats-light.png` |
| `settings-dark.png` | `runway-android-settings-dark.png` |

The test also creates `calendar-large-text.png` and `settings-about-dark.png` for review; those are
evidence, not README assets. Review compact and expanded widths, both themes, and increased font
scale before updating README.

## Repository social preview

`runway-social-preview.svg` is the editable source for `runway-social-preview.png`, the image used
for GitHub's repository social preview. It reuses the launcher mark and fixed launcher colors rather
than introducing a separate logo. Render it at 1280 by 640 pixels and keep the PNG under 1 MB.
