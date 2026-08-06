# Third-party notices

The optional on-device word-board scanner includes these components:

- OpenCV 4.12.0 — Apache-2.0 (`OpenCV_LICENSE.txt`).
- Tesseract4Android 4.9.0 and Tesseract OCR 5.5.1 — Apache-2.0 (`Tesseract4Android_LICENSE.txt`).
- Leptonica 1.85.0 — BSD-2-Clause (`Leptonica_LICENSE.txt`).
- libjpeg 9f — IJG license (`libjpeg_README.txt`). This software is based in part on the work of the Independent JPEG Group.
- libpng 1.6.48 — libpng license (`libpng_LICENSE.txt`).
- `eng.traineddata` from `tessdata_fast` — Apache-2.0 (`tessdata_fast_LICENSE.txt`).

The English trained model is packaged with the APK. It is copied only to the app's private files directory for Tesseract to read; the app does not download OCR models or send captured images over a network.

Model SHA-256: `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
