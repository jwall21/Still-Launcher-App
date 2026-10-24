# Still

A quiet home screen for Android TV / Google TV (Android 9+), ported from
[CleanFrame](https://github.com/nthnclrk/CleanFrame) (Tizen, MIT) and styled after tvOS / Apple HIG.

Plain Kotlin + framework Views, no AndroidX. ~36 MB PSS idle on a Formovie R1 Nano (Android 9, 3 GB).

## Build

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Make it the Home app

On stock Android TV: `adb shell cmd package set-home-activity com.kimtrinh.still/.HomeActivity`.

Formovie/Fengmi (and similar OEM ROMs) refuse that ("cannot be home on user 0"). Instead turn on
**Settings → Accessibility → Still Home Button**, or `../projector home-still`. The service filters
only the Home key; a boot receiver hands over from the stock launcher after power-on.
Undo with `../projector home-stock` or by turning the service off. Still's Settings also has
"Open TV's Own Launcher". Don't disable the stock launcher: it is this ROM's only real Home.

## Remote

- OK opens. Hold OK (or Menu) on an app: Move, Add/Remove from Home, Rename, Hide, App Info, Uninstall.
- Right from the list reaches Inputs / All Apps / Settings. Back closes one panel at a time.

## Layout of the code

- `Core.kt`: scaling (1920×1080 design canvas), fonts, prefs, app discovery, icon rendering,
  wallpaper decode + one-time blur (the "glass"), scroll and focus plate views.
- `HomeActivity.kt`: Home (List / Dock), overlays (Settings sidebar, All Apps, action sheets), move mode.

Credits: Inter and Manrope (OFL), Lucide icons (ISC), photos from Unsplash (see `assets/wallpapers/CREDITS.md`).
