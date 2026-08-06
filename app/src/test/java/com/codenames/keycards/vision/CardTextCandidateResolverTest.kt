package com.codenames.keycards.vision

import com.codenames.keycards.model.CandidateAgreement
import org.junit.Assert.assertEquals
import org.junit.Test

class CardTextCandidateResolverTest {
  @Test
  fun matchingDistinctPhysicalRegions_agree() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("Goldilocks", 91, "upper", 0),
          OcrTextCandidate("GOLDILOCKS", 75, "lower", 180),
        ),
      )

    assertEquals("Goldilocks", cell.text)
    assertEquals(CandidateAgreement.AGREED, cell.candidateAgreement)
    assertEquals(83, cell.confidence)
  }

  @Test
  fun repeatedPreprocessingOfOneRegion_isOnlyASingleRead() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("STEEL", 91, "upper", 0),
          OcrTextCandidate("STEEL", 80, "upper", 180),
        ),
      )

    assertEquals(CandidateAgreement.SINGLE_READ, cell.candidateAgreement)
    assertEquals(91, cell.confidence)
  }

  @Test
  fun equivalentPreprocessingReadsPreferCleanDisplayWithoutOcrBoundaryMarks() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("UNIVERSITY. |", 94, "upper", 0),
          OcrTextCandidate("UNIVERSITY", 91, "upper", 0),
          OcrTextCandidate("¥ UNIVERSITY", 90, "upper", 0),
        ),
      )

    assertEquals("UNIVERSITY", cell.text)
    assertEquals(94, cell.confidence)
  }

  @Test
  fun detachedOcrSymbolsAreRemovedWithoutStrippingLegitimateAttachedPunctuation() {
    val artifact = CardTextCandidateResolver.resolve(listOf(OcrTextCandidate("UNIVERSITY. |", 94, "upper", 0)))
    val legitimate = CardTextCandidateResolver.resolve(listOf(OcrTextCandidate("C++", 94, "upper", 0)))

    assertEquals("UNIVERSITY", artifact.text)
    assertEquals("C++", legitimate.text)
  }

  @Test
  fun stablePreprocessingClusterCanBeatOneHigherConfidenceConflictingRead() {
    val resolution =
      CardTextCandidateResolver.resolveWithEvidence(
        listOf(
          OcrTextCandidate("O1AVA", 75, "upper", 0),
          OcrTextCandidate("ora", 29, "upper", 0),
          OcrTextCandidate("RADIO", 61, "lower", 0, "lower-original"),
          OcrTextCandidate("RADIO", 42, "lower", 0, "lower-contrast"),
          OcrTextCandidate("RADIO", 18, "lower", 0, "lower-threshold"),
        ),
      )

    assertEquals("RADIO", resolution.cell.text)
    assertEquals(CandidateAgreement.CONFLICT, resolution.cell.candidateAgreement)
    assertEquals(1, resolution.physicalRegionSupport)
    assertEquals(3, resolution.preprocessingSupport)
  }

  @Test
  fun pageModeRetriesOnTheSamePixelsDoNotInflateSupport() {
    val resolution =
      CardTextCandidateResolver.resolveWithEvidence(
        listOf(
          OcrTextCandidate("LABEL", 30, "center", 0, "original"),
          OcrTextCandidate("LABEL", 42, "center", 0, "original"),
        ),
      )

    assertEquals(1, resolution.preprocessingSupport)
  }

  @Test
  fun oppositeOrientationAttemptsDoNotInflatePreprocessingSupport() {
    val resolution =
      CardTextCandidateResolver.resolveWithEvidence(
        listOf(
          OcrTextCandidate("LABEL", 80, "center", 0, "0:original"),
          OcrTextCandidate("LABEL", 75, "center", 0, "0:contrast"),
          OcrTextCandidate("LABEL", 70, "center", 180, "180:original"),
          OcrTextCandidate("LABEL", 65, "center", 180, "180:contrast"),
        ),
      )

    assertEquals(2, resolution.preprocessingSupport)
  }

  @Test
  fun alphanumericCustomLabelIsNotPenalized() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("R2D2", 82, "center", 0, "center-original"),
          OcrTextCandidate("R2D2", 79, "center", 0, "center-contrast"),
          OcrTextCandidate("ROD", 91, "other", 0),
        ),
      )

    assertEquals("R2D2", cell.text)
  }

  @Test
  fun overlappingCropsOfOnePhysicalRegionCannotCreateAgreement() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("LABEL", 89, "upper-band", 0, "line-crop"),
          OcrTextCandidate("LABEL", 84, "upper-band", 180, "broad-crop"),
        ),
      )

    assertEquals(CandidateAgreement.SINGLE_READ, cell.candidateAgreement)
  }

  @Test
  fun matchingSameDirectionRegions_doNotReceiveFalseOfficialCopyAgreement() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("LABEL", 89, "upper", 0),
          OcrTextCandidate("LABEL", 84, "lower", 0),
        ),
      )

    assertEquals(CandidateAgreement.SINGLE_READ, cell.candidateAgreement)
    assertEquals(89, cell.confidence)
  }

  @Test
  fun oneCustomCardLabel_isAcceptedWithoutAnOppositeCopy() {
    val cell = CardTextCandidateResolver.resolve(listOf(OcrTextCandidate("Café-au-lait", 88, "full", 180)))

    assertEquals("Café-au-lait", cell.text)
    assertEquals(CandidateAgreement.SINGLE_READ, cell.candidateAgreement)
  }

  @Test
  fun distinctNonblankRegionsThatDisagree_requireReview() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("RAM", 90, "upper", 0),
          OcrTextCandidate("LAMB", 80, "lower", 180),
        ),
      )

    assertEquals("RAM", cell.text)
    assertEquals(CandidateAgreement.CONFLICT, cell.candidateAgreement)
  }

  @Test
  fun repeatedEquivalentReads_areOneInterpretationRatherThanAlternatives() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("STEEL", 92, "upper", 0, "upper-original"),
          OcrTextCandidate("steel.", 89, "lower", 180, "lower-original"),
          OcrTextCandidate("STEEL", 87, "lower", 180, "lower-contrast"),
          OcrTextCandidate("STELL", 70, "lower", 180, "lower-threshold"),
        ),
      )

    assertEquals("STEEL", cell.text)
    assertEquals(listOf("STELL"), cell.alternatives.map { it.text })
  }

  @Test
  fun unselectedTesseractReads_areRetainedAsRankedFixingAlternatives() {
    val cell =
      CardTextCandidateResolver.resolve(
        listOf(
          OcrTextCandidate("i", 94, "upper", 0, "upper-original"),
          OcrTextCandidate("i", 90, "upper", 0, "upper-contrast"),
          OcrTextCandidate("CAMP", 48, "lower", 180, "lower-original"),
          OcrTextCandidate("CAMP", 35, "lower", 180, "lower-contrast"),
          OcrTextCandidate("CAME", 51, "lower", 180, "lower-threshold"),
        ),
      )

    assertEquals("i", cell.text)
    assertEquals("CAMP", cell.alternatives.first().text)
    assertEquals(48, cell.alternatives.first().confidence)
  }
}
