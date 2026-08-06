# Codenames Keycards for Android

Create [Codenames](https://en.wikipedia.org/wiki/Codenames_(board_game)) keycards and run a game on your Android device. Configure the board size, number of teams (two to four), tiles per team, turn order, optional first-team bonus, and turn timer. Every card includes an assassin tile.

Codenames Keycards is an independent, unofficial companion app and is not affiliated with or endorsed by Czech Games Edition (CGE).

Support CGE and the official Codenames games. These are high-quality games at very affordable prices.

## How it works

- Generate keycards entirely on your device, with every valid arrangement equally likely.
- Choose rows and columns independently from 2 to 10 (for example, 4×6), or link them to keep the grid square, subject to the selected teams and tile counts fitting on the board.
- Drag teams into the desired turn order. The first team begins the game, and can optionally receive one extra tile.
- Play with a countdown selected through rolling minute and second pickers, or choose No timer and tap `∞` to advance turns.
- Optionally scan a physical word-card board in portrait or landscape, or choose an existing photo. Grid dimensions are independently checked before bounded parallel local OCR. Review uses a compact board and shows only uncertain words by default; every recognized word remains editable, with alternate Tesseract reads offered alongside manual retyping. The source image is kept only in memory; after a grid mismatch it is retained briefly so the app can update the board size and reread that same image.
- When a reviewed word board is attached, start a spymaster/cluegiver view that keeps the full keycard beside the active team's targets. Targets can be marked guessed, undone, and shuffled without changing their board positions.
- Start a focused game screen with the active team, keycard, and pause menu. The display stays awake only while an unpaused game is running.
- Your generated board, setup, and game progress are always saved in the app's private storage. An interrupted game returns paused, so time never elapses while the app is away.
- Use the app offline: it has no network permission, web view, analytics SDK, remote API, or downloaded game data. The English OCR model is bundled in the APK; cloud backup is disabled.

See [Keycard generation and target-list shuffle](KEYCARD_AND_SHUFFLE_IMPLEMENTATION.md) for the role-count construction, uniformity argument, positional target identity, shuffle lifecycle, and exhaustive tests.

## Scanner privacy and limits

The scanner requests `CAMERA` only after you choose camera capture; choosing an existing image uses Android's system photo picker and requires no media/storage permission. Capture, grid detection, and OCR are performed locally. The OCR functionality is specially adapted for official Codenames cards, though it should be robust enough to accept custom cards as well. The app does not write to the gallery or persist a source photograph. When the detected grid does not match, it holds that image only in memory until you retake, leave settings, or reread it after updating the board size. Uneven spacing and small card offsets are allowed, but a scan is rejected when the app cannot assign exactly one independently detected card to every grid position or when its dimensions do not match the configured keycard.

A reviewed word board reveals the color key, so the combined target view is for the cluegiver/spymaster only and must not be handed to guessers.

See [Scanner implementation details](SCANNER_IMPLEMENTATION.md) for the contour filtering, board-space lattice fitting, OCR preprocessing, evidence-resolution rules, failure behavior, and scanner test strategy.

## Inspiration and credits

This Android app was initially inspired by the [Codenames Key Card Generator](https://github.com/MikhaD/codenames-keycard-generator), a separate web project. The optional word-card OCR feature was inspired by these Codenames print-and-play resources:

- [Codenames PnP on BoardGameGeek](https://boardgamegeek.com/filepage/121220/codenames-pnp)
- [Associated Codenames print-and-play video](https://www.youtube.com/watch?v=LpdIfJtCNUU)

## Build from source

To build the app yourself, install JDK 17 and the Android SDK. From this directory, run:

```sh
./gradlew testDebugUnitTest assembleDebug
```

Per-architecture APKs and a larger universal APK are written to `app/build/outputs/apk/debug/`. To install the universal build on a connected device or emulator, use:

```sh
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
