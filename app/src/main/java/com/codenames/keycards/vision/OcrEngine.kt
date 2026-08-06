package com.codenames.keycards.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Trace
import com.codenames.keycards.model.CandidateAgreement
import com.codenames.keycards.model.OcrAlternative
import com.codenames.keycards.model.RecognizedCell
import com.googlecode.tesseract.android.TessBaseAPI
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import kotlin.math.max

/** OCR is isolated so language packs and worker pooling do not affect game-state code. */
interface OcrEngine : Closeable {
  /** One engine instance is owned by one scanner worker. */
  fun recognize(card: Bitmap): RecognizedCell
}

fun interface OcrEngineFactory {
  fun create(): OcrEngine
}

/** Fully local Tesseract implementation backed by the APK's bundled tessdata model. */
class TesseractOcrEngine(
  context: Context,
  private val language: String = "eng",
) : OcrEngine {
  private val context = context.applicationContext
  private var tess: TessBaseAPI? = null

  @Synchronized
  override fun recognize(card: Bitmap): RecognizedCell {
    if (!openCvAvailable) return RecognizedCell("")
    val inset = card.inset()
    val focusedInputs = mutableListOf<RegionInput>()
    try {
      CardTextRegionDetector.detect(inset)
        .groupBy { it.physicalRegionId(inset.height) }
        .mapNotNull { (regionId, alternatives) ->
          alternatives.maxByOrNull(CardTextRegion::score)?.let { region ->
            RegionInput(
              image = inset.crop(region.bounds.left, region.bounds.top, region.bounds.right, region.bounds.bottom),
              regionId = regionId,
            )
          }
        }
        .sortedByDescending { input -> detectedBandPriority(input.regionId) }
        .take(MAX_FOCUSED_REGIONS)
        .forEach(focusedInputs::add)

      // Most clean cards need only the original pixels from one detected line. Resolve each cheap
      // pass before paying for another crop or contrast/threshold retries. Previously every region
      // was unconditionally OCRed with all three transforms.
      val focusedCandidates = mutableListOf<OcrTextCandidate>()
      var primaryRead = CardTextCandidateResolver.resolveWithEvidence(emptyList())
      for (input in focusedInputs) {
        focusedCandidates += input.readVariants(QUICK_VARIANTS)
        primaryRead = CardTextCandidateResolver.resolveWithEvidence(focusedCandidates)
        val isOfficialBand = input.regionId == UPPER_BAND || input.regionId == LOWER_BAND
        if (
          primaryRead.cell.candidateAgreement == CandidateAgreement.AGREED ||
            (isOfficialBand && primaryRead.isDecisiveQuickRead())
        ) {
          return primaryRead.cell
        }
      }

      val hasOfficialBandRegion = focusedInputs.any { it.regionId == UPPER_BAND || it.regionId == LOWER_BAND }
      if (!hasOfficialBandRegion && primaryRead.isStableSingleRead()) return primaryRead.cell

      // Try unmodified broad bands before creating any enhanced images. This cheap pass often
      // recovers a line detector miss and avoids all CLAHE/threshold OCR calls for that card.
      val broadInputs = broadInputsNeeded(inset, focusedInputs, focusedCandidates, primaryRead)
      try {
        val bandCandidates = mutableListOf<OcrTextCandidate>()
        for (input in broadInputs) {
          bandCandidates += input.readVariants(QUICK_VARIANTS)
          val combined = CardTextCandidateResolver.resolveWithEvidence(focusedCandidates + bandCandidates)
          primaryRead = betterRead(primaryRead, combined)
          if (combined.cell.candidateAgreement == CandidateAgreement.AGREED || combined.isDecisiveQuickRead()) return combined.cell
        }

        for (input in focusedInputs) {
          focusedCandidates += input.readVariants(REFINEMENT_VARIANTS)
          val combined = CardTextCandidateResolver.resolveWithEvidence(focusedCandidates + bandCandidates)
          primaryRead = betterRead(primaryRead, combined)
          if (
            combined.cell.candidateAgreement == CandidateAgreement.AGREED ||
              (hasOfficialBandRegion && combined.isDecisiveQuickRead())
          ) {
            return combined.cell
          }
        }
        if (!hasOfficialBandRegion && primaryRead.isStableSingleRead()) return primaryRead.cell

        for (input in broadInputs) {
          bandCandidates += input.readVariants(REFINEMENT_VARIANTS)
          val combined = CardTextCandidateResolver.resolveWithEvidence(focusedCandidates + bandCandidates)
          primaryRead = betterRead(primaryRead, combined)
          if (combined.cell.candidateAgreement == CandidateAgreement.AGREED || combined.isDecisiveQuickRead()) return combined.cell
        }
      } finally {
        broadInputs.forEach(RegionInput::recycle)
      }

      // Whole-card reads remain a fallback for custom layouts and failed focused crops. Official
      // bands that disagree or have only one read are not trusted solely because preprocessing
      // variants repeated the same artifact.
      val normalCandidates = mutableListOf<OcrTextCandidate>()
      normalCandidates += readRegion(inset, FULL_REGION, NORMAL_ROTATIONS, QUICK_VARIANTS)
      var normalRead = CardTextCandidateResolver.resolveWithEvidence(normalCandidates)
      if (normalRead.cell.isPlausible()) return betterRead(primaryRead, normalRead).cell
      normalCandidates += readRegion(inset, FULL_REGION, NORMAL_ROTATIONS, REFINEMENT_VARIANTS)
      normalRead = CardTextCandidateResolver.resolveWithEvidence(normalCandidates)
      if (normalRead.cell.isPlausible()) return betterRead(primaryRead, normalRead).cell

      // Orthogonal text is uncommon, so pay its cost only when all normal directions failed.
      val orthogonalCandidates = mutableListOf<OcrTextCandidate>()
      orthogonalCandidates += readRegion(inset, FULL_REGION, ORTHOGONAL_ROTATIONS, QUICK_VARIANTS)
      var orthogonalRead = CardTextCandidateResolver.resolveWithEvidence(normalCandidates + orthogonalCandidates)
      if (!orthogonalRead.cell.isPlausible()) {
        orthogonalCandidates += readRegion(inset, FULL_REGION, ORTHOGONAL_ROTATIONS, REFINEMENT_VARIANTS)
        orthogonalRead = CardTextCandidateResolver.resolveWithEvidence(normalCandidates + orthogonalCandidates)
      }
      return listOf(primaryRead, normalRead, orthogonalRead).reduce(::betterRead).cell
    } finally {
      focusedInputs.forEach(RegionInput::recycle)
      inset.recycle()
    }
  }

  @Synchronized
  override fun close() {
    tess?.recycle()
    tess = null
  }

  private fun broadInputsNeeded(
    card: Bitmap,
    focused: List<RegionInput>,
    focusedCandidates: List<OcrTextCandidate>,
    focusedRead: ResolvedOcrRead,
  ): List<RegionInput> {
    val focusedIds = focused.map(RegionInput::regionId).toSet()
    if (focusedIds.none { it == UPPER_BAND || it == LOWER_BAND }) {
      return if (focused.isEmpty()) listOf(card.upperBand(), card.lowerBand()) else emptyList()
    }

    fun bandNeedsFallback(regionId: String): Boolean {
      if (focusedRead.cell.candidateAgreement == CandidateAgreement.CONFLICT) return true
      if (regionId !in focusedIds) return true
      return !CardTextCandidateResolver.resolve(focusedCandidates.filter { it.regionId == regionId }).isPlausible()
    }

    return buildList {
      if (bandNeedsFallback(UPPER_BAND)) add(card.upperBand())
      if (bandNeedsFallback(LOWER_BAND)) add(card.lowerBand())
    }
  }

  private fun Bitmap.upperBand(): RegionInput {
    val left = width * 8 / 100
    val right = width * 92 / 100
    return RegionInput(crop(left, height * 7 / 100, right, height * 46 / 100), UPPER_BAND)
  }

  private fun Bitmap.lowerBand(): RegionInput {
    val left = width * 8 / 100
    val right = width * 92 / 100
    return RegionInput(crop(left, height * 54 / 100, right, height * 93 / 100), LOWER_BAND)
  }

  private fun RegionInput.readVariants(variants: IntArray): List<OcrTextCandidate> =
    readRegion(image, regionId, NORMAL_ROTATIONS, variants)

  private fun readRegion(
    region: Bitmap,
    regionId: String,
    rotations: IntArray,
    variants: IntArray,
  ): List<OcrTextCandidate> =
    buildList {
      rotations.forEach { rotation ->
        val oriented = region.rotated(rotation)
        try {
          val prepared = oriented.upscaledForOcr()
          try {
            variants.forEach { variantIndex ->
              val image =
                when (variantIndex) {
                  0 -> prepared
                  1 -> prepared.localContrast()
                  2 -> prepared.adaptiveMonochrome()
                  else -> error("Unknown OCR preprocessing variant $variantIndex")
                }
              try {
                val preprocessingId = "$rotation:$variantIndex"
                val line = recognizeImage(image, regionId, rotation, preprocessingId, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
                if (line.text.isNotBlank()) add(line)
                if (line.text.isBlank() || (variantIndex != QUICK_VARIANT && (line.confidence ?: 0) < WORD_MODE_CONFIDENCE)) {
                  val word = recognizeImage(image, regionId, rotation, preprocessingId, TessBaseAPI.PageSegMode.PSM_SINGLE_WORD)
                  if (word.text.isNotBlank()) add(word)
                }
              } finally {
                if (image !== prepared) image.recycle()
              }
            }
          } finally {
            if (prepared !== oriented) prepared.recycle()
          }
        } finally {
          if (oriented !== region) oriented.recycle()
        }
      }
    }

  private fun recognizeImage(
    image: Bitmap,
    regionId: String,
    rotation: Int,
    preprocessingId: String,
    pageMode: Int,
  ): OcrTextCandidate {
    Trace.beginSection("Tesseract")
    return try {
      api().run {
        setPageSegMode(pageMode)
        setImage(image)
        try {
          OcrTextCandidate(
            text = getUTF8Text().orEmpty(),
            confidence = meanConfidence().takeIf { it >= 0 },
            regionId = regionId,
            rotationDegrees = rotation,
            preprocessingId = preprocessingId,
          )
        } finally {
          clear()
        }
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun api(): TessBaseAPI = tess ?: TessBaseAPI().also { created ->
    val dataPath = File(context.filesDir, "tesseract")
    ensureLanguageModel(dataPath, language)
    if (!created.init(dataPath.absolutePath, language)) {
      created.recycle()
      error("Could not initialize bundled OCR language $language")
    }
    tess = created
  }

  private fun RecognizedCell.isPlausible(): Boolean =
    text.any(Char::isLetterOrDigit) &&
      text.length <= 80 &&
      candidateAgreement != CandidateAgreement.CONFLICT &&
      (candidateAgreement == CandidateAgreement.AGREED || (confidence ?: 0) >= PLAUSIBLE_CONFIDENCE)

  private fun ResolvedOcrRead.isStableSingleRead(): Boolean =
    cell.isPlausible() &&
      cell.candidateAgreement == CandidateAgreement.SINGLE_READ &&
      preprocessingSupport >= 2 &&
      (cell.confidence ?: 0) >= STABLE_SINGLE_CONFIDENCE

  /** A strong word-sized read should not be held up by low-confidence artwork from another crop. */
  private fun ResolvedOcrRead.isDecisiveQuickRead(): Boolean {
    val confidence = cell.confidence ?: return false
    val strongestAlternative = cell.alternatives.mapNotNull { it.confidence }.maxOrNull() ?: 0
    return cell.text.count(Char::isLetterOrDigit) >= 2 &&
      confidence >= DECISIVE_QUICK_CONFIDENCE &&
      confidence - strongestAlternative >= DECISIVE_CONFIDENCE_MARGIN
  }

  private fun betterRead(first: ResolvedOcrRead, second: ResolvedOcrRead): ResolvedOcrRead {
    val firstText = first.cell.text.lowercase()
    val secondText = second.cell.text.lowercase()
    val selected =
      when {
        firstText.length > secondText.length && firstText.contains(secondText) -> first
        secondText.length > firstText.length && secondText.contains(firstText) -> second
        else -> maxOf(first, second, comparator = readComparator)
      }
    val selectedKey = selected.cell.text.trim().lowercase()
    val mergedAlternatives =
      buildList {
        addAll(first.cell.alternatives)
        addAll(second.cell.alternatives)
        if (first !== selected && first.cell.text.isNotBlank()) add(OcrAlternative(first.cell.text, first.cell.confidence))
        if (second !== selected && second.cell.text.isNotBlank()) add(OcrAlternative(second.cell.text, second.cell.confidence))
      }
        .filter { it.text.trim().lowercase() != selectedKey }
        .distinctBy { it.text.trim().lowercase() }
        .sortedByDescending { it.confidence ?: Int.MIN_VALUE }
        .take(MAX_REVIEW_ALTERNATIVES)
    return selected.copy(cell = selected.cell.copy(alternatives = mergedAlternatives))
  }

  private val readComparator =
    compareBy<ResolvedOcrRead> { it.cell.text.any(Char::isLetterOrDigit) }
      .thenBy { it.cell.candidateAgreement == CandidateAgreement.AGREED }
      .thenBy(ResolvedOcrRead::physicalRegionSupport)
      .thenBy(ResolvedOcrRead::preprocessingSupport)
      .thenBy { it.cell.confidence ?: Int.MIN_VALUE }
      .thenBy { it.cell.text.length }

  private fun CardTextRegion.physicalRegionId(cardHeight: Int): String {
    val centerFraction = bounds.centerY().toDouble() / cardHeight
    return when {
      centerFraction < .46 -> UPPER_BAND
      centerFraction > .54 -> LOWER_BAND
      else -> id
    }
  }

  private data class RegionInput(val image: Bitmap, val regionId: String) {
    fun recycle() {
      if (!image.isRecycled) image.recycle()
    }
  }

  private fun Bitmap.inset(): Bitmap {
    val horizontal = (width * .06f).toInt().coerceAtMost(width / 4)
    val vertical = (height * .06f).toInt().coerceAtMost(height / 4)
    return Bitmap.createBitmap(this, horizontal, vertical, width - horizontal * 2, height - vertical * 2)
  }

  private fun Bitmap.crop(left: Int, top: Int, right: Int, bottom: Int): Bitmap {
    val x = left.coerceIn(0, width - 1)
    val y = top.coerceIn(0, height - 1)
    return Bitmap.createBitmap(this, x, y, (right - x).coerceIn(1, width - x), (bottom - y).coerceIn(1, height - y))
  }

  private fun Bitmap.rotated(degrees: Int): Bitmap =
    if (degrees == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

  private fun Bitmap.upscaledForOcr(): Bitmap {
    val targetHeight = max(height, MINIMUM_OCR_REGION_HEIGHT)
    if (targetHeight == height) return this
    return Bitmap.createScaledBitmap(this, width * targetHeight / height, targetHeight, true)
  }

  private fun Bitmap.localContrast(): Bitmap = transformWithOpenCv { grayscale, output ->
    val clahe = Imgproc.createCLAHE(2.6, Size(8.0, 8.0))
    try {
      clahe.apply(grayscale, output)
    } finally {
      clahe.collectGarbage()
    }
  }

  private fun Bitmap.adaptiveMonochrome(): Bitmap = transformWithOpenCv { grayscale, output ->
    Imgproc.adaptiveThreshold(
      grayscale,
      output,
      255.0,
      Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
      Imgproc.THRESH_BINARY,
      31,
      9.0,
    )
  }

  private inline fun Bitmap.transformWithOpenCv(operation: (grayscale: Mat, output: Mat) -> Unit): Bitmap {
    val source = Mat()
    val grayscale = Mat()
    val output = Mat()
    try {
      Utils.bitmapToMat(this, source)
      Imgproc.cvtColor(source, grayscale, Imgproc.COLOR_RGBA2GRAY)
      operation(grayscale, output)
      val result = Bitmap.createBitmap(output.cols(), output.rows(), Bitmap.Config.ARGB_8888)
      Utils.matToBitmap(output, result)
      return result
    } finally {
      output.release()
      grayscale.release()
      source.release()
    }
  }

  private companion object {
    const val UPPER_BAND = "upper-band"
    const val LOWER_BAND = "lower-band"
    const val FULL_REGION = "full"
    const val MAX_FOCUSED_REGIONS = 3
    const val MINIMUM_OCR_REGION_HEIGHT = 110
    const val WORD_MODE_CONFIDENCE = 45
    const val PLAUSIBLE_CONFIDENCE = 45
    const val STABLE_SINGLE_CONFIDENCE = 60
    const val DECISIVE_QUICK_CONFIDENCE = 85
    const val DECISIVE_CONFIDENCE_MARGIN = 15
    const val MAX_REVIEW_ALTERNATIVES = 4
    val NORMAL_ROTATIONS = intArrayOf(0, 180)
    val ORTHOGONAL_ROTATIONS = intArrayOf(90, 270)
    const val QUICK_VARIANT = 0
    val QUICK_VARIANTS = intArrayOf(QUICK_VARIANT)
    val REFINEMENT_VARIANTS = intArrayOf(1, 2)
    val modelInstallLock = Any()
    val openCvAvailable: Boolean by lazy { runCatching { OpenCVLoader.initLocal() }.getOrDefault(false) }
  }

  private fun ensureLanguageModel(dataPath: File, language: String) {
    synchronized(modelInstallLock) {
      val tessdata = File(dataPath, "tessdata")
      check(dataPath.exists() || dataPath.mkdirs()) { "Could not create OCR data directory" }
      check(tessdata.exists() || tessdata.mkdirs()) { "Could not create OCR model directory" }
      val model = File(tessdata, "$language.traineddata")
      if (model.exists() && model.length() > 0L) return
      val temporary = File(tessdata, "$language.traineddata.tmp")
      temporary.delete()
      context.assets.open("tessdata/$language.traineddata").use { input ->
        temporary.outputStream().use { output -> input.copyTo(output) }
      }
      if (model.exists()) model.delete()
      check(temporary.renameTo(model)) { "Could not install bundled OCR language $language" }
    }
  }

  private fun detectedBandPriority(regionId: String): Int =
    when (regionId) {
      UPPER_BAND, LOWER_BAND -> 1
      else -> 0
    }
}
