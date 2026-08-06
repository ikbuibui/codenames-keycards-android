package com.codenames.keycards.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class LatticeFitterTest {
  @Test
  fun fitsRotatedUnevenHumanGridWithJitterAndOutliers() {
    val cards = humanGrid(rows = 5, columns = 5, angle = 23.0 * PI / 180.0).toMutableList()
    cards += CardObservation(100, -400.0, 900.0, 104.0, 61.0, .4, 6_300.0)
    cards += CardObservation(101, 1_600.0, -300.0, 99.0, 58.0, .35, 5_800.0)

    val fit = LatticeFitter().fit(cards)

    assertTrue(fit is LatticeFit.Verified)
    fit as LatticeFit.Verified
    assertEquals(5, fit.rows)
    assertEquals(5, fit.columns)
    assertEquals(25, fit.assignments.size)
    assertEquals((0 until 25).toSet(), fit.assignments.map(LatticeAssignment::observationId).toSet())
    assertTrue(fit.confidence >= .52)
  }

  @Test
  fun rectangularGridKeepsItsIndependentlyDetectedDimensions() {
    val fit = LatticeFitter().fit(humanGrid(rows = 4, columns = 6, angle = -17.0 * PI / 180.0))

    assertTrue(fit is LatticeFit.Verified)
    fit as LatticeFit.Verified
    assertEquals(4, fit.rows)
    assertEquals(6, fit.columns)
  }

  @Test
  fun missingInteriorCardFailsClosedInsteadOfAcceptingASmallerRectangle() {
    val incomplete = humanGrid(rows = 5, columns = 5, angle = .1).filterNot { it.id == 12 }

    assertTrue(LatticeFitter().fit(incomplete) is LatticeFit.Uncertain)
  }

  @Test
  fun duplicatePlausibleCardInOneCellIsAmbiguous() {
    val cards = humanGrid(rows = 4, columns = 4, angle = .2).toMutableList()
    val original = cards[5]
    cards += original.copy(id = 99, centerX = original.centerX + 3.0, centerY = original.centerY - 2.0)

    assertTrue(LatticeFitter().fit(cards) is LatticeFit.Uncertain)
  }

  private fun humanGrid(rows: Int, columns: Int, angle: Double): List<CardObservation> {
    val columnGaps = listOf(0.0, 132.0, 279.0, 421.0, 579.0, 716.0, 870.0, 1_018.0, 1_169.0, 1_321.0)
    val rowGaps = listOf(0.0, 82.0, 171.0, 249.0, 345.0, 438.0, 529.0, 622.0, 714.0, 807.0)
    val horizontalX = cos(angle)
    val horizontalY = sin(angle)
    val verticalX = -horizontalY
    val verticalY = horizontalX
    return buildList {
      repeat(rows) { row ->
        repeat(columns) { column ->
          val id = row * columns + column
          val horizontalJitter = ((id * 17) % 13 - 6) * 1.7
          val verticalJitter = ((id * 11) % 9 - 4) * 1.8
          val x = columnGaps[column] + horizontalJitter
          val y = rowGaps[row] + verticalJitter
          add(
            CardObservation(
              id = id,
              centerX = 500.0 + horizontalX * x + verticalX * y,
              centerY = 300.0 + horizontalY * x + verticalY * y,
              longSide = 104.0 + (id % 5 - 2) * 1.4,
              shortSide = 61.0 + (id % 3 - 1) * 1.2,
              longAxisRadians = angle + ((id % 7) - 3) * .012,
              area = 6_250.0 + (id % 4 - 2) * 90.0,
            ),
          )
        }
      }
    }
  }
}
