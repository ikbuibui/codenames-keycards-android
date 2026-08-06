package com.codenames.keycards.ui.scan

import com.codenames.keycards.model.BoardOrientation
import com.codenames.keycards.model.CandidateAgreement
import com.codenames.keycards.model.RecognizedBoard
import com.codenames.keycards.model.RecognizedCell
import com.codenames.keycards.model.needsOcrAttention
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
  @Test
  fun highConfidenceReadsDoNotCreateMandatoryReviewWork() {
    assertFalse(RecognizedCell("STEEL", 96, CandidateAgreement.CONFLICT).needsOcrAttention())
    assertFalse(RecognizedCell("MERMAID", 88, CandidateAgreement.SINGLE_READ).needsOcrAttention())
    assertFalse(RecognizedCell("EDITED", 10, CandidateAgreement.CONFLICT, manuallyEdited = true).needsOcrAttention())
  }

  @Test
  fun likelyOcrMissesAndBlanksRequireAttention() {
    assertTrue(RecognizedCell("EE", 39, CandidateAgreement.CONFLICT).needsOcrAttention())
    assertTrue(RecognizedCell("A", 22, CandidateAgreement.AGREED).needsOcrAttention())
    assertTrue(RecognizedCell("i", 96, CandidateAgreement.AGREED).needsOcrAttention())
    assertTrue(RecognizedCell("").needsOcrAttention())
  }

  @Test
  fun reviewIsReadyOnlyAfterEveryAttentionCellIsResolved() {
    val board =
      RecognizedBoard(
        1,
        3,
        listOf(
          RecognizedCell("READY", 95, CandidateAgreement.AGREED),
          RecognizedCell("UNCERTAIN", 42, CandidateAgreement.CONFLICT),
          RecognizedCell("FIXED", 20, CandidateAgreement.CONFLICT, manuallyEdited = true),
        ),
      )
    val review =
      ScanStage.Review(
        sourceBoard = board,
        compatibleOrientations = listOf(BoardOrientation.ORIGINAL),
        orientation = BoardOrientation.ORIGINAL,
      )

    assertFalse(review.isReadyToUse)
    assertTrue(review.copy(acknowledgedSourceIndices = setOf(1)).isReadyToUse)
  }
}
