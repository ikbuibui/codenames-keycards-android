# Scanner implementation details

This document describes how the Android scanner turns a camera capture or selected image into a positionally stable `RecognizedBoard`. It focuses on the implemented grid-recognition and OCR algorithms. For product behavior, build instructions, and privacy guarantees, see [README.md](README.md). For design decisions and remaining hardening work, see [PLAN.md](PLAN.md).

The implementation is fully local. OpenCV performs geometry and image preprocessing, while Tesseract4Android uses the English `tessdata_fast` model bundled in the APK. OCR text is never used to infer the grid shape or card positions.

## Core invariants

The scanner is designed around several fail-closed rules:

1. **Geometry establishes position.** A card receives a row and column before OCR runs.
2. **Exactly one card must map to every accepted lattice cell.** Missing, duplicate, or ambiguous assignments make the grid uncertain.
3. **OCR cannot make an uncertain grid valid.** Text count and recognized words never affect grid dimensions.
4. **One physical card always produces one editable cell.** The two opposite text copies on an official card are observations of one cell, not extra cards.
5. **Correlated OCR retries are not independent agreement.** Page-segmentation modes, preprocessing variants, and opposite orientation attempts are tracked separately.
6. **Review is the final correctness boundary.** Empty, low-confidence, and conflicting reads are visibly marked and every value is editable.

The main entry point is [`BoardScanner`](app/src/main/java/com/codenames/keycards/vision/BoardScanner.kt). It coordinates [`OpenCvGridDetector`](app/src/main/java/com/codenames/keycards/vision/OpenCvGridDetector.kt), per-card perspective correction, and [`TesseractOcrEngine`](app/src/main/java/com/codenames/keycards/vision/OcrEngine.kt).

## End-to-end pipeline

```text
camera still or selected image
        │
        ▼
OpenCV contour extraction at bounded resolution
        │
        ▼
robust board-space lattice fit
        │
        ├── uncertain ──► fail closed with capture guidance
        ├── wrong size ─► show expected/found dimensions
        │
        ▼
full-resolution quadrilateral for every row/column cell
        │
        ▼
convert the accepted source to one full-resolution OpenCV matrix
        │
        ▼
bounded perspective-rectification queue
        │
        ▼
generic text regions + lazy broad official-card bands
        │
        ▼
0°/180° OCR with preprocessing alternatives
        │
        ├── weak result ─► whole-card and lazy 90°/270° fallback
        │
        ▼
evidence-aware candidate resolution
        │
        ▼
compact board + attention-first editable review
```

Grid detection is downsampled for bounded memory and processing cost. The resulting quadrilateral corners are mapped back to the original image, so the authoritative OCR crops still come from the high-resolution still image.

## Live guidance versus authoritative recognition

Camera preview analysis is advisory only. CameraX uses `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`, and analysis is throttled to approximately one frame every 650 ms. The preview detector produces hints such as:

- `More light`
- `Reduce glare`
- `Too blurry — hold still`
- `Move closer and keep every card visible`
- `Hold the phone more level`
- `All 25 cards found`

The user may capture even when preview detection is uncertain. The captured still is independently analyzed again, and only that full still can produce an accepted grid and OCR result. The camera overlay uses loose corner brackets rather than square grid cells: it does not prescribe card aspect ratio or spacing. Portrait and landscape captures are accepted, CameraX capture/analysis rotations follow physical orientation, and exact or transposed configured dimensions both produce the successful live hint.

## Grid recognition

### 1. Detection image and diagnostics

[`OpenCvGridDetection`](app/src/main/java/com/codenames/keycards/vision/OpenCvGridDetector.kt) limits the longest detection-image dimension to 1400 pixels. It then:

1. Converts RGBA to grayscale.
2. Applies CLAHE local-contrast normalization (`clipLimit = 2.2`, `8 × 8` tiles).
3. Applies a `3 × 3` Gaussian blur.
4. Records mean grayscale exposure.
5. Estimates blur from the mean absolute difference between the normalized and blurred images.

Blur, exposure, card count, perspective skew, dimensions, and lattice confidence are returned as `GridDiagnostics`. These values drive guidance; no single diagnostic is an acceptance Boolean.

### 2. Multiple contour images

Lighting, card borders, and table surfaces vary, so the detector does not depend on one threshold. It extracts contours from:

- adaptive Gaussian threshold with block size 21 and constant 5;
- adaptive Gaussian threshold with block size 31 and constant 7;
- adaptive Gaussian threshold with block size 51 and constant 5;
- Canny edges (`35`, `105`) followed by `3 × 3` dilation.

Contours are retrieved with `RETR_LIST`, allowing useful card outlines even when they are nested inside a larger board/table contour.

### 3. Quadrilateral filtering

Each contour is approximated to a polygon using 2.5% of its perimeter. A candidate must:

- have exactly four vertices;
- be convex;
- cover at least 0.025% of the image;
- have long-side/short-side ratio in `1.12..3.2`.

Very large candidates covering at least 22% of the image are treated as likely board/table outlines and removed before card-scale selection.

Candidates from different threshold variants commonly describe the same card. They are sorted by area, and a candidate is suppressed when an already-kept candidate has nearly the same center (within 38% of the shorter side). The detector then chooses the most populated mutually compatible area band; areas may differ by roughly a factor of two (`0.48..2.05`) to tolerate perspective and imperfect contours.

### 4. Board-space coordinate system

Raw screen-space X/Y clustering is unreliable when a board is rotated. [`LatticeFitter`](app/src/main/java/com/codenames/keycards/vision/LatticeFitter.kt) instead estimates the board's undirected horizontal axis from the mean long-edge orientation of all card candidates.

Every center is projected onto two orthogonal board axes:

```text
columnCoordinate = center · horizontalAxis
rowCoordinate    = center · verticalAxis
```

The fitter clusters projected row coordinates with tolerance `0.68 × typical short side` and projected column coordinates with tolerance `0.68 × typical long side`. This allows board rotation, local card jitter, and uneven spacing without forcing points into raw image-space bands.

### 5. Candidate lattice search

The fitter evaluates contiguous rectangular subsets of the row and column clusters, up to the configured maximum dimension of 10. For each candidate rectangle it requires:

- one observation for every row/column cell;
- normalized selected-card residual no greater than `0.78`;
- no second similarly sized card with nearly equal residual in the same cell;
- no nearly complete adjacent fringe row or column suggesting a larger but incomplete board;
- at least 62% of observations included;
- no more than `max(4, expectedCells / 3)` outliers;
- adjacent row/column gaps at least 62% of the typical card dimension;
- bounded spacing variation.

Residual distance is normalized by typical card size:

```text
rowError    = abs(projectedRow - rowCenter) / typicalShortSide
columnError = abs(projectedColumn - columnCenter) / typicalLongSide
residual    = sqrt(rowError² + columnError²)
```

A candidate's confidence combines:

- assignment residual: 42%;
- spacing consistency: 22%;
- card-angle consistency: 18%;
- observation coverage: 18%.

The minimum accepted confidence is 0.52. If a differently shaped lattice with the same number of assigned cards is within 0.04 confidence of the best result, the mapping is considered ambiguous and rejected.

These thresholds deliberately tolerate harmless visual imperfections while rejecting uncertainty that could shift positional mapping.

### 6. Full-resolution assignments and dimensions

A verified fit returns one `LatticeAssignment` per card. The detector attaches each card's actual contour quadrilateral and maps its corners from detection resolution back to source resolution.

`BoardScanner` then compares independently detected dimensions with the configured keycard:

- exact non-square `rows × columns`: continue with 0° and 180° as compatible logical orientations;
- exact non-square `columns × rows`: continue with 90° clockwise and counterclockwise as compatible logical orientations;
- exact square dimensions: continue with all four quarter-turn orientations;
- any other verified dimensions: return `GridMismatch`;
- no unambiguous fit: return `GridUncertain`.

The recognized cells remain in stable detector/source coordinates during review. Orientation choices are absolute transforms rather than accumulated mutations, so rotating after an edit cannot detach text from its physical card. Only the final confirmed board is materialized in keycard orientation.

A mismatch screen shows expected and found dimensions and offers **Retake** or **Change board size**. An uncertain result instead says that the grid could not be verified; it does not claim a dimension mismatch.

## Card rectification and shape assumptions

After geometry succeeds, the full-resolution source bitmap is converted to one shared OpenCV matrix instead of being reconverted for every card. Each detected quadrilateral is perspective-warped from that matrix. Landscape cards are normalized to `900 × 563`; portrait detections are normalized to `563 × 900` and rotated to landscape before OCR. Rectified cards are produced and consumed through a bounded worker queue rather than all being retained.

The geometry stage therefore assumes cards are approximately rectangular, convex, similarly sized objects with visible enough outer boundaries. It accepts a broad range of rectangular aspect ratios and does not require official artwork. It does not promise reliable detection for:

- circular or strongly irregular cards;
- truly borderless cards on a same-color surface;
- heavily overlapping cards;
- cards whose outer edges cannot be separated from internal artwork;
- arbitrary perspective where individual cards no longer resemble quadrilaterals.

Once a card has been rectified, OCR does not require the official card border, logo, font, or two-copy layout.

## OCR recognition

### 1. Card inset and generic text-region detection

[`TesseractOcrEngine`](app/src/main/java/com/codenames/keycards/vision/OcrEngine.kt) removes 6% from each edge to suppress the rectified border. [`CardTextRegionDetector`](app/src/main/java/com/codenames/keycards/vision/CardTextRegionDetector.kt) searches the remaining card for probable text lines:

1. grayscale conversion;
2. CLAHE normalization;
3. adaptive inverted threshold;
4. horizontal morphological closing to connect letters into line-like components;
5. contour filtering by relative width, height, area, and aspect ratio;
6. overlap suppression and ranking, retaining at most four regions.

Regions near the upper and lower official-card positions receive a small ranking bonus, but central custom-card regions remain valid.

Generic regions clearly located in the upper or lower part of the card share a canonical physical-region ID with the corresponding broad-band crop. This allows overlapping crops to improve candidate ranking without falsely counting them as independent printed copies.

### 2. Broad-band evidence

Generic text detection can miss faint print or merge a logo with a word. The engine can therefore examine two broad fallback regions:

- horizontal range 8%–92%, vertical range 7%–46%;
- horizontal range 8%–92%, vertical range 54%–93%.

These are evidence and fallback regions, not a requirement. A broad band is skipped when a sufficiently useful precise region already covers that physical band. A custom card with one central label can still be recognized by generic or whole-card OCR.

### 3. Orientation and preprocessing alternatives

Focused regions are tested at 0° and 180° and upscaled to at least 110 pixels high. OCR is staged instead of unconditionally running every transform:

1. try the upscaled original pixels with `PSM_SINGLE_LINE`;
2. accept a word-sized read at confidence 85 or above when it leads every other unique interpretation by at least 15 points;
3. if the original pass is inconclusive, try broad official-card bands;
4. only then create CLAHE local-contrast (`clipLimit = 2.6`, `8 × 8` tiles) and adaptive Gaussian monochrome (block size 31, constant 9) variants.

A blank original-pixel result is retried with `PSM_SINGLE_WORD`. During the slower refinement path, a result below confidence 45 also receives the single-word interpretation. Clean cards therefore usually require only two Tesseract calls for the first useful region, while difficult cards retain the preprocessing and page-mode fallbacks.

Tesseract is not treated as thread-safe. Instead, a bounded pool gives each OCR worker its own reused `TessBaseAPI` instance and a dedicated native-work dispatcher. Low-RAM devices use one worker; other devices use up to three while leaving a processor available when possible. Jobs carry their source index, so out-of-order completion cannot alter row-major identity. Progress reports completed-card count, and temporary bitmaps/OpenCV matrices are released promptly. The same implementation supports a one-worker serial mode for tests and constrained devices.

### 4. Lazy fallback

Focused text regions are preferred and processed in rank order. Recognition stops as soon as regions independently agree or one word-sized result decisively outranks low-confidence artwork/noise. If the original focused and broad-band passes are inconclusive, focused and broad crops receive preprocessing refinements. OCR runs over the complete inset card at 0° and 180° only after those cheaper paths fail. Only if normal orientations also fail does it pay the cost of 90° and 270° whole-card OCR. Preprocessing bitmaps are created one at a time rather than retaining every alternative simultaneously.

This ordering keeps the common path smaller while retaining support for custom short-axis text.

## OCR evidence and candidate resolution

Each `OcrTextCandidate` records:

- raw recognized text;
- Tesseract confidence;
- physical text-region ID;
- card rotation;
- preprocessing ID.

The preprocessing ID identifies one transformed image. A single-line and single-word page-mode attempt on the same pixels share an ID. Likewise, support is measured within the strongest orientation rather than summed across 0° and 180°, because orientation attempts are mutually exclusive hypotheses.

[`CardTextCandidateResolver`](app/src/main/java/com/codenames/keycards/vision/CardTextCandidateResolver.kt) resolves candidates in two stages.

### Within one physical region

1. Collapse whitespace and remove detached leading/trailing OCR symbols for display.
2. Build a comparison-only key using Unicode NFKC normalization, locale-independent lowercase, surrounding punctuation removal, and whitespace collapse.
3. Cluster candidates with the same comparison key.
4. Prefer the cluster reproduced by the most distinct preprocessing transforms.
5. Use Tesseract confidence as the next tie-breaker.
6. Choose the cleanest display spelling from the winning cluster without destructively uppercasing or removing legitimate attached punctuation such as `C++`.

This makes stable reads stronger than one isolated, overconfident OCR artifact while avoiding a dictionary, character allowlist, or fixture-specific correction.

### Across physical regions

The best value is selected by transform support and confidence. The resulting `CandidateAgreement` is:

- `AGREED` when matching values come from distinct physical regions and their selected rotations differ by 180°;
- `CONFLICT` when distinct nonblank physical regions disagree;
- `SINGLE_READ` otherwise, including a valid custom card with only one label.

Matching preprocessing variants never produce `AGREED`. Overlapping generic/broad crops use the same physical ID, and matching same-direction regions are not promoted to official two-copy agreement.

For `AGREED`, confidence is averaged across matching physical regions. Otherwise the selected observation's confidence is retained. If focused and whole-card results differ, source selection considers independent agreement, physical-region support, preprocessing stability, confidence, and text coverage when one result is a strict substring of the other.

No word list or language-model correction is applied. Uncertain spelling remains visible for human review.

## Review states

The final `RecognizedCell` stores text, confidence, agreement state, ranked alternate Tesseract reads, and whether it was manually edited. Review is attention-first:

- a compact, fixed-shape board preserves spatial context and controls logical orientation;
- blank cells, one-character OCR reads, confidence-below-55 cells, and conflicting cells below confidence 80 enter the mandatory attention list;
- high-confidence reads, including a high-confidence winner with weaker conflicting evidence, remain visible in the compact board but do not create a checklist item;
- the full row-major word list is available behind **Review all recognized words**;
- a nonblank attention value can be edited or explicitly kept; a blank must be edited;
- independently agreed cells are labeled **Matched**, usable single-region reads **One read**, and corrections **Edited manually**;
- the fixing dialog offers up to four distinct alternate Tesseract reads, including lower-confidence interpretations, as one-tap choices alongside manual retyping;
- every cell remains editable, and duplicate words are warned about rather than rejected;
- confirmation requires matching oriented dimensions, no blanks, and no unresolved attention items.

Confidence affects review guidance, not geometry acceptance or automatic spelling changes. The native fixture gate requires more than 90% exact recognition for each supplied board and requires every miss to satisfy the attention policy, so users are not expected to inspect all confident cells to find known fixture errors.

## Offline and memory behavior

- The app has no `INTERNET` permission.
- `eng.traineddata` is copied from APK assets into app-private storage on first OCR initialization; it is never downloaded.
- Camera and selected-image paths share the same still-image scanner.
- Selected images are decoded with bounded dimensions and corrected from EXIF orientation.
- Detection uses a downsampled bitmap, while OCR crops use the source image.
- Cards use bounded one-to-three-worker OCR; only approximately one rectified job per worker is in flight.
- Source and temporary bitmaps are recycled after use. A source image whose grid dimensions mismatch is kept only in memory until the user retakes, leaves the flow, or asks to update the board size and reread it; workflow dismissal and ViewModel cleanup close native OCR state.
- The accepted model contains recognized text only; the source photograph is not persisted.

## Tests

The scanner has two complementary test layers:

- [`LatticeFitterTest`](app/src/test/java/com/codenames/keycards/vision/LatticeFitterTest.kt) exercises pure geometry with jitter, uneven spacing, rotation/skew, outliers, missing cards, duplicate assignments, and ambiguous layouts.
- [`CardTextCandidateResolverTest`](app/src/test/java/com/codenames/keycards/vision/CardTextCandidateResolverTest.kt) verifies physical-region agreement, conflicts, preprocessing/page-mode correlation, orientation correlation, punctuation handling, and custom alphanumeric labels.
- [`WordBoardFixtureTest`](app/src/androidTest/java/com/codenames/keycards/vision/WordBoardFixtureTest.kt) runs native OpenCV and Tesseract on Android. It verifies exact dimensions, greater-than-90% row-major recognition with every miss visibly flagged, bounded parallel result/progress behavior, and a generated one-copy custom label at 0° and 180°.

The fixture manifest is [`app/src/test/resources/word-boards/ground-truth.json`](app/src/test/resources/word-boards/ground-truth.json). CI runs the native fixture gate in an Android emulator.

## Important implementation files

| File | Responsibility |
|---|---|
| [`GridDetector.kt`](app/src/main/java/com/codenames/keycards/vision/GridDetector.kt) | Grid result and diagnostics contracts |
| [`OpenCvGridDetector.kt`](app/src/main/java/com/codenames/keycards/vision/OpenCvGridDetector.kt) | Contours, quadrilateral filtering, and full-resolution corner mapping |
| [`LatticeFitter.kt`](app/src/main/java/com/codenames/keycards/vision/LatticeFitter.kt) | Pure board-space lattice fitting and confidence |
| [`BoardScanner.kt`](app/src/main/java/com/codenames/keycards/vision/BoardScanner.kt) | Dimension validation, one-source rectification, bounded OCR workers, and stable indexed mapping |
| [`CardTextRegionDetector.kt`](app/src/main/java/com/codenames/keycards/vision/CardTextRegionDetector.kt) | Generic probable text-line detection |
| [`OcrEngine.kt`](app/src/main/java/com/codenames/keycards/vision/OcrEngine.kt) | OCR preprocessing, orientation strategy, and fallbacks |
| [`CardTextCandidateResolver.kt`](app/src/main/java/com/codenames/keycards/vision/CardTextCandidateResolver.kt) | Evidence-aware text resolution |
| [`ScanWordBoardFlow.kt`](app/src/main/java/com/codenames/keycards/ui/scan/ScanWordBoardFlow.kt) | Mismatch, progress, orientation, compact board, and attention-first review UX |
