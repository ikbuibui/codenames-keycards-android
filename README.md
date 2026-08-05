# Codenames Keycards for Android

Create [Codenames](https://en.wikipedia.org/wiki/Codenames_(board_game)) keycards on your Android device. Configure the board size, number of teams (two to four), tiles per team, and starting team, then generate a new card whenever you need one. The starting team receives one extra tile; every card also includes an assassin tile.

Codenames Keycards is an independent, unofficial companion app and is not affiliated with or endorsed by Czech Games Edition.

## How it works

- Generate keycards entirely on your device.
- Choose board sizes from 2×2 to 10×10, subject to the selected teams and tile counts fitting on the board.
- Freeze a card to keep the exact same card when you close and reopen the app. Unfreeze it to change settings or generate another card.
- Use the app offline: it has no network permission, web view, analytics SDK, remote API, or downloaded game data. Frozen-card settings are stored only in the app's private storage, and cloud backup is disabled.

## Related project

This Android app was inspired by the [Codenames Key Card Generator](https://github.com/MikhaD/codenames-keycard-generator), a separate web project.

## Build from source

To build the app yourself, install JDK 17 or newer and the Android SDK. From this directory, run:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device or emulator, use:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
