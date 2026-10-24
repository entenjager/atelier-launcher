# Atelier

A home screen launcher for Android, written in Kotlin and Jetpack Compose.

**Download:** get `Atelier.apk` from [Releases](../../releases/latest), open it on your phone and choose Atelier as your home app.

- **Building blocks:** the home screen is a grid of blocks (clock, app list, alphabet, music, notes, buttons, frames …).
  Long-press the home screen to zoom out slightly and arrange everything right there.
- **Alphabet:** swipe along the letters to jump through your apps.
- **Designs:** themes, backgrounds, icon styles, clock fonts (some animated, e.g. *Drip*).
- **Optional extension:** edge gestures and a custom volume slider (accessibility service, off by default).
- **Languages:** German and English.

## Build

Open the project in Android Studio, or:

```
./gradlew assembleOeffentlichRelease
```

Minimum: Android 8. Some effects need newer Android versions.

## License

Code: MIT – see `LICENSE`.
Fonts in `app/src/main/res/font`: SIL Open Font License 1.1 – see `FONTS-LICENSE.txt`.
