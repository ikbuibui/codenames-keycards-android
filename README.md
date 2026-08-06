# Codenames Keycards for Android

Create [Codenames](https://en.wikipedia.org/wiki/Codenames_(board_game)) keycards and run a game on your Android device. Configure the board size, number of teams (two to four), tiles per team, turn order, optional first-team bonus, and turn timer. Every card includes an assassin tile.

Codenames Keycards is an independent, unofficial companion app and is not affiliated with or endorsed by Czech Games Edition.

## How it works

- Generate keycards entirely on your device, with every valid arrangement equally likely.
- Choose rows and columns independently from 2 to 10 (for example, 4×6), or link them to keep the grid square, subject to the selected teams and tile counts fitting on the board.
- Drag teams into the desired turn order. The first team begins the game, and can optionally receive one extra tile.
- Play with a countdown selected through rolling minute and second pickers, or choose No timer and tap `∞` to advance turns.
- Start a focused game screen with the active team, keycard, and pause menu. The display stays awake only while an unpaused game is running.
- Your generated board, setup, and game progress are always saved in the app's private storage. An interrupted game returns paused, so time never elapses while the app is away.
- Use the app offline: it has no network permission, web view, analytics SDK, remote API, or downloaded game data. Cloud backup is disabled.

## Related project

This Android app was inspired by the [Codenames Key Card Generator](https://github.com/MikhaD/codenames-keycard-generator), a separate web project.

## Build from source

To build the app yourself, install JDK 17 and the Android SDK. From this directory, run:

```sh
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected device or emulator, use:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
