package com.codenames.keycards.vision

import com.codenames.keycards.model.CandidateAgreement
import com.codenames.keycards.model.OcrAlternative
import com.codenames.keycards.model.RecognizedCell
import java.text.Normalizer
import java.util.Locale

/** One OCR observation. [regionId] identifies a physical printed text region, not a preprocessing pass. */
data class OcrTextCandidate(
  val text: String,
  val confidence: Int?,
  val regionId: String,
  val rotationDegrees: Int,
  /** Identifies one image transform; page-segmentation retries on the same pixels share this id. */
  val preprocessingId: String = "$rotationDegrees:$text",
)

/**
 * Resolves OCR alternatives into one editable cell without allowing repeated processing of one
 * printed band to masquerade as independent agreement.
 */
data class ResolvedOcrRead(
  val cell: RecognizedCell,
  /** Number of spatially distinct regions supporting [cell]. */
  val physicalRegionSupport: Int,
  /** Number of matching preprocessing alternatives; useful for ranking, but not agreement. */
  val preprocessingSupport: Int,
)

object CardTextCandidateResolver {
  fun resolve(candidates: List<OcrTextCandidate>): RecognizedCell = resolveWithEvidence(candidates).cell

  fun resolveWithEvidence(candidates: List<OcrTextCandidate>): ResolvedOcrRead {
    val readable = candidates.mapNotNull { candidate ->
      val display = candidate.text.normalizedDisplayText()
      val comparison = display.comparisonKey()
      comparison.takeIf(String::isNotBlank)?.let { Read(candidate.copy(text = display), it) }
    }
    if (readable.isEmpty()) {
      return ResolvedOcrRead(
        cell = RecognizedCell(text = "", confidence = null, candidateAgreement = CandidateAgreement.SINGLE_READ),
        physicalRegionSupport = 0,
        preprocessingSupport = 0,
      )
    }

    // First cluster equivalent preprocessing reads within each physical region. Prefer values that
    // survive more distinct transforms, then confidence, and finally the cleanest display spelling
    // so a slightly higher-confidence trailing OCR mark does not enter the reviewed word.
    val bestByRegion = readable
      .groupBy { it.candidate.regionId }
      .mapValues { (_, alternatives) ->
        val winningCluster =
          alternatives
            .groupBy(Read::key)
            .values
            .maxWithOrNull(
              compareBy<List<Read>> { cluster -> cluster.preprocessingSupport() }
                .thenBy { cluster -> cluster.maxOf { it.candidate.confidence ?: Int.MIN_VALUE } },
            )!!
        winningCluster.minWithOrNull(displayComparator)!!.copy(
          supportCount = winningCluster.preprocessingSupport(),
        )
      }
      .values
      .toList()
    val winning = bestByRegion.maxWithOrNull(readComparator)!!
    val sameReadRegions = bestByRegion.filter { it.key == winning.key }
    val disagreeingRegions = bestByRegion.filter { it.key != winning.key }
    val hasOppositeRegionAgreement =
      sameReadRegions.indices.any { first ->
        ((first + 1) until sameReadRegions.size).any { second ->
          val firstRead = sameReadRegions[first].candidate
          val secondRead = sameReadRegions[second].candidate
          firstRead.regionId != secondRead.regionId &&
            rotationDifference(firstRead.rotationDegrees, secondRead.rotationDegrees) == 180
        }
      }
    val agreement =
      when {
        // Official copies are spatially distinct and face opposite directions.
        hasOppositeRegionAgreement -> CandidateAgreement.AGREED
        // Different nonblank printed regions are never silently merged. Matching same-direction
        // labels are retained as a single read rather than receiving a false confidence boost.
        disagreeingRegions.isNotEmpty() -> CandidateAgreement.CONFLICT
        else -> CandidateAgreement.SINGLE_READ
      }
    val confidence =
      if (agreement == CandidateAgreement.AGREED) {
        sameReadRegions.mapNotNull { it.candidate.confidence }.averageOrNull()?.toInt()
      } else {
        winning.candidate.confidence
      }
    val alternatives =
      readable
        .filter { it.key != winning.key }
        .groupBy(Read::key)
        .values
        .map { cluster ->
          val display = cluster.minWithOrNull(displayComparator)!!
          RankedAlternative(
            alternative = OcrAlternative(display.candidate.text, cluster.mapNotNull { it.candidate.confidence }.maxOrNull()),
            support = cluster.map { "${it.candidate.regionId}:${it.candidate.preprocessingId}" }.distinct().size,
          )
        }
        .sortedWith(
          compareByDescending<RankedAlternative> { it.support }
            .thenByDescending { it.alternative.confidence ?: Int.MIN_VALUE },
        )
        .take(MAX_ALTERNATIVES)
        .map(RankedAlternative::alternative)

    return ResolvedOcrRead(
      cell = RecognizedCell(
        text = winning.candidate.text,
        confidence = confidence,
        candidateAgreement = agreement,
        alternatives = alternatives,
      ),
      physicalRegionSupport = sameReadRegions.size,
      preprocessingSupport = sameReadRegions.sumOf(Read::supportCount),
    )
  }

  private data class Read(val candidate: OcrTextCandidate, val key: String, val supportCount: Int = 1)

  private data class RankedAlternative(val alternative: OcrAlternative, val support: Int)

  private const val MAX_ALTERNATIVES = 4

  private val readComparator =
    compareBy<Read> { it.supportCount }
      .thenBy { it.candidate.confidence ?: Int.MIN_VALUE }
      .thenByDescending { it.candidate.text.boundaryPunctuationCount() }

  private val displayComparator =
    compareBy<Read> { it.candidate.text.boundaryPunctuationCount() }
      .thenBy { it.candidate.text.length }
      .thenByDescending { it.candidate.confidence ?: Int.MIN_VALUE }

  private fun String.normalizedDisplayText(): String {
    val collapsed = trim().replace(Regex("\\s+"), " ")
    val hasDetachedLeadingMark = collapsed.matches(Regex("^[\\p{P}\\p{S}]+\\s+.*"))
    val hasDetachedTrailingMark = collapsed.matches(Regex(".*\\s+[\\p{P}\\p{S}]+$"))
    return if (hasDetachedLeadingMark || hasDetachedTrailingMark) {
      collapsed.trim { character ->
        character.isWhitespace() ||
          isComparisonPunctuation(character) ||
          Character.getType(character) in symbolCharacterTypes
      }
    } else {
      collapsed
    }
  }

  /** Comparison-only normalization deliberately leaves the displayed spelling unchanged. */
  private fun List<Read>.preprocessingSupport(): Int =
    groupBy { it.candidate.rotationDegrees }
      .maxOf { (_, sameOrientation) -> sameOrientation.map { it.candidate.preprocessingId }.distinct().size }

  private fun String.boundaryPunctuationCount(): Int {
    val clean = trim().trim { character -> character.isWhitespace() || isComparisonPunctuation(character) }
    return length - clean.length
  }

  private fun isComparisonPunctuation(character: Char): Boolean =
    character.toString().matches(Regex("[\\p{Punct}]"))

  private val symbolCharacterTypes =
    setOf(
      Character.CURRENCY_SYMBOL.toInt(),
      Character.MATH_SYMBOL.toInt(),
      Character.MODIFIER_SYMBOL.toInt(),
      Character.OTHER_SYMBOL.toInt(),
    )

  private fun String.comparisonKey(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC)
      .lowercase(Locale.ROOT)
      .trim { character -> character.isWhitespace() || isComparisonPunctuation(character) }
      .replace(Regex("\\s+"), " ")

  private fun rotationDifference(first: Int, second: Int): Int {
    val difference = kotlin.math.abs(first - second) % 360
    return minOf(difference, 360 - difference)
  }

  private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
