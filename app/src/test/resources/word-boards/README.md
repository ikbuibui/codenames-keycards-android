# Word-board scanner fixtures

These supplied, consented test photos were stripped of EXIF metadata before being added here. `ground-truth.json` records each board's row-major physical-card label; each label appears twice on its card in opposite directions.

- `official-5x5.jpg` verifies a 5 × 5 lattice.
- `official-4x6.jpg` verifies a rectangular 4 × 6 lattice.

They are test-only resources, never packaged in release APKs. `WordBoardFixtureTest` packages them only in the instrumentation-test APK, verifies dimensions from card geometry rather than OCR count, and compares every recognized row-major cell with the manifest. Each board must exceed 90% exact recognition, and every miss must be classified for mandatory review.
