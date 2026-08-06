package com.codenames.keycards.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometry-only description of a detected card, suitable for local unit tests. */
data class CardObservation(
  val id: Int,
  val centerX: Double,
  val centerY: Double,
  val longSide: Double,
  val shortSide: Double,
  /** Direction of a long edge in image coordinates. Values differing by PI are equivalent. */
  val longAxisRadians: Double,
  val area: Double,
)

data class LatticeAssignment(val observationId: Int, val row: Int, val column: Int)

sealed interface LatticeFit {
  data class Verified(
    val rows: Int,
    val columns: Int,
    val assignments: List<LatticeAssignment>,
    val confidence: Double,
    val meanResidual: Double,
    val rowSpacingVariation: Double,
    val columnSpacingVariation: Double,
  ) : LatticeFit

  data class Uncertain(
    val candidateRows: Int? = null,
    val candidateColumns: Int? = null,
    val confidence: Double = 0.0,
  ) : LatticeFit
}

/**
 * Fits a topological card lattice in the board's coordinate system.
 *
 * The fitter intentionally allows uneven gaps, local placement jitter, small card rotations, and a
 * few unrelated rectangular outliers. It still fails closed unless it can make one unambiguous
 * card assignment for every cell in a rectangular row/column lattice.
 */
class LatticeFitter(
  private val maximumDimension: Int = 10,
  private val minimumConfidence: Double = .52,
) {
  fun fit(input: List<CardObservation>): LatticeFit {
    val observations =
      input.filter {
        it.centerX.isFinite() &&
          it.centerY.isFinite() &&
          it.longSide.isFinite() &&
          it.shortSide.isFinite() &&
          it.longSide > 0.0 &&
          it.shortSide > 0.0 &&
          it.area > 0.0
      }
    if (observations.size < 4) return LatticeFit.Uncertain()

    val typicalLong = median(observations.map(CardObservation::longSide)) ?: return LatticeFit.Uncertain()
    val typicalShort = median(observations.map(CardObservation::shortSide)) ?: return LatticeFit.Uncertain()
    val boardAngle = meanUndirectedAngle(observations.map(CardObservation::longAxisRadians))
    val horizontalX = cos(boardAngle)
    val horizontalY = sin(boardAngle)
    val verticalX = -horizontalY
    val verticalY = horizontalX

    val projected =
      observations.map { card ->
        ProjectedCard(
          card = card,
          columnCoordinate = card.centerX * horizontalX + card.centerY * horizontalY,
          rowCoordinate = card.centerX * verticalX + card.centerY * verticalY,
          angleError = undirectedAngleDifference(card.longAxisRadians, boardAngle),
        )
      }
    val rowCenters = cluster(projected.map(ProjectedCard::rowCoordinate), typicalShort * .68)
    val columnCenters = cluster(projected.map(ProjectedCard::columnCoordinate), typicalLong * .68)
    if (rowCenters.size < 2 || columnCenters.size < 2) {
      return LatticeFit.Uncertain(rowCenters.size.takeIf { it > 1 }, columnCenters.size.takeIf { it > 1 })
    }

    val cells = mutableMapOf<Pair<Int, Int>, MutableList<ProjectedCard>>()
    projected.forEach { card ->
      val row = nearest(card.rowCoordinate, rowCenters)
      val column = nearest(card.columnCoordinate, columnCenters)
      val rowResidual = abs(card.rowCoordinate - rowCenters[row]) / typicalShort
      val columnResidual = abs(card.columnCoordinate - columnCenters[column]) / typicalLong
      if (rowResidual <= .72 && columnResidual <= .72) {
        cells.getOrPut(row to column, ::mutableListOf) += card
      }
    }

    val candidates = mutableListOf<Candidate>()
    for (rowStart in rowCenters.indices) {
      for (rowEnd in rowStart + 1 until minOf(rowCenters.size, rowStart + maximumDimension)) {
        for (columnStart in columnCenters.indices) {
          for (columnEnd in columnStart + 1 until minOf(columnCenters.size, columnStart + maximumDimension)) {
            createCandidate(
              observations = observations,
              cells = cells,
              rowCenters = rowCenters,
              columnCenters = columnCenters,
              rowStart = rowStart,
              rowEnd = rowEnd,
              columnStart = columnStart,
              columnEnd = columnEnd,
              typicalLong = typicalLong,
              typicalShort = typicalShort,
            )?.let(candidates::add)
          }
        }
      }
    }

    val best =
      candidates.maxWithOrNull(
        compareBy<Candidate> { it.assignments.size }
          .thenBy { it.confidence }
          .thenByDescending { it.outlierCount },
      ) ?: return LatticeFit.Uncertain(rowCenters.size, columnCenters.size)

    // Different equally large explanations indicate that positional mapping is not trustworthy.
    val competing =
      candidates.any {
        it !== best &&
          it.assignments.size == best.assignments.size &&
          (it.rows != best.rows || it.columns != best.columns) &&
          it.confidence >= best.confidence - .04
      }
    if (competing || best.confidence < minimumConfidence) {
      return LatticeFit.Uncertain(best.rows, best.columns, best.confidence)
    }

    return LatticeFit.Verified(
      rows = best.rows,
      columns = best.columns,
      assignments = best.assignments,
      confidence = best.confidence,
      meanResidual = best.meanResidual,
      rowSpacingVariation = best.rowSpacingVariation,
      columnSpacingVariation = best.columnSpacingVariation,
    )
  }

  private fun createCandidate(
    observations: List<CardObservation>,
    cells: Map<Pair<Int, Int>, List<ProjectedCard>>,
    rowCenters: List<Double>,
    columnCenters: List<Double>,
    rowStart: Int,
    rowEnd: Int,
    columnStart: Int,
    columnEnd: Int,
    typicalLong: Double,
    typicalShort: Double,
  ): Candidate? {
    val rows = rowEnd - rowStart + 1
    val columns = columnEnd - columnStart + 1
    val expectedCount = rows * columns
    val assignments = mutableListOf<LatticeAssignment>()
    val selectedCards = mutableListOf<ProjectedCard>()
    val residuals = mutableListOf<Double>()

    for (row in rowStart..rowEnd) {
      for (column in columnStart..columnEnd) {
        val choices = cells[row to column].orEmpty()
        if (choices.isEmpty()) return null
        val ranked =
          choices.sortedBy { card ->
            normalizedResidual(card, rowCenters[row], columnCenters[column], typicalShort, typicalLong)
          }
        val selected = ranked.first()
        val selectedResidual = normalizedResidual(selected, rowCenters[row], columnCenters[column], typicalShort, typicalLong)
        if (selectedResidual > .78) return null
        if (ranked.size > 1) {
          val secondResidual = normalizedResidual(ranked[1], rowCenters[row], columnCenters[column], typicalShort, typicalLong)
          val comparableArea = ranked[1].card.area / selected.card.area in .55..1.8
          if (comparableArea && secondResidual <= selectedResidual + .18) return null
        }
        assignments += LatticeAssignment(selected.card.id, row - rowStart, column - columnStart)
        selectedCards += selected
        residuals += selectedResidual
      }
    }

    if (assignments.size != expectedCount) return null
    // A nearly complete adjacent row/column is evidence for a larger but incomplete board, not a
    // harmless outlier. Accepting the smaller rectangle would silently shift positional mapping.
    val fringeRows =
      cells.keys
        .filter { (row, column) -> row !in rowStart..rowEnd && column in columnStart..columnEnd }
        .groupingBy(Pair<Int, Int>::first)
        .eachCount()
    val fringeColumns =
      cells.keys
        .filter { (row, column) -> row in rowStart..rowEnd && column !in columnStart..columnEnd }
        .groupingBy(Pair<Int, Int>::second)
        .eachCount()
    if (fringeRows.values.any { it >= max(2, columns / 2) } || fringeColumns.values.any { it >= max(2, rows / 2) }) return null

    val outlierCount = observations.size - selectedCards.size
    val coverage = selectedCards.size.toDouble() / observations.size
    if (coverage < .62 || outlierCount > max(4, expectedCount / 3)) return null

    val rowSpacings = rowCenters.slice(rowStart..rowEnd).successiveDifferences()
    val columnSpacings = columnCenters.slice(columnStart..columnEnd).successiveDifferences()
    if (rowSpacings.any { it < typicalShort * .62 } || columnSpacings.any { it < typicalLong * .62 }) return null
    val rowVariation = relativeSpacingVariation(rowSpacings)
    val columnVariation = relativeSpacingVariation(columnSpacings)
    if (rowVariation > 1.6 || columnVariation > 1.6) return null

    val meanResidual = residuals.average()
    val residualScore = (1.0 - meanResidual / .72).coerceIn(0.0, 1.0)
    val spacingScore = (1.0 - (rowVariation + columnVariation) / 3.2).coerceIn(0.0, 1.0)
    val angleError = selectedCards.map(ProjectedCard::angleError).average()
    val angleScore = (1.0 - angleError / (PI / 4.0)).coerceIn(0.0, 1.0)
    val confidence = (.42 * residualScore + .22 * spacingScore + .18 * angleScore + .18 * coverage).coerceIn(0.0, 1.0)

    return Candidate(
      rows = rows,
      columns = columns,
      assignments = assignments,
      confidence = confidence,
      meanResidual = meanResidual,
      rowSpacingVariation = rowVariation,
      columnSpacingVariation = columnVariation,
      outlierCount = outlierCount,
    )
  }

  private fun normalizedResidual(
    card: ProjectedCard,
    rowCenter: Double,
    columnCenter: Double,
    typicalShort: Double,
    typicalLong: Double,
  ): Double {
    val row = abs(card.rowCoordinate - rowCenter) / typicalShort
    val column = abs(card.columnCoordinate - columnCenter) / typicalLong
    return sqrt(row * row + column * column)
  }

  private data class ProjectedCard(
    val card: CardObservation,
    val columnCoordinate: Double,
    val rowCoordinate: Double,
    val angleError: Double,
  )

  private data class Candidate(
    val rows: Int,
    val columns: Int,
    val assignments: List<LatticeAssignment>,
    val confidence: Double,
    val meanResidual: Double,
    val rowSpacingVariation: Double,
    val columnSpacingVariation: Double,
    val outlierCount: Int,
  )
}

private fun meanUndirectedAngle(angles: List<Double>): Double {
  val x = angles.sumOf { cos(it * 2.0) }
  val y = angles.sumOf { sin(it * 2.0) }
  return atan2(y, x) / 2.0
}

private fun undirectedAngleDifference(first: Double, second: Double): Double {
  var difference = abs(first - second) % PI
  if (difference > PI / 2.0) difference = PI - difference
  return difference
}

private fun cluster(values: List<Double>, tolerance: Double): List<Double> {
  if (values.isEmpty() || tolerance <= 0.0) return emptyList()
  val clusters = mutableListOf<MutableList<Double>>()
  values.sorted().forEach { value ->
    val nearest = clusters.indices.minByOrNull { abs(value - clusters[it].average()) }
    if (nearest != null && abs(value - clusters[nearest].average()) <= tolerance) {
      clusters[nearest] += value
    } else {
      clusters += mutableListOf(value)
    }
  }
  return clusters.map { it.average() }.sorted()
}

private fun nearest(value: Double, centers: List<Double>): Int =
  centers.indices.minBy { abs(value - centers[it]) }

private fun List<Double>.successiveDifferences(): List<Double> = zipWithNext { first, second -> second - first }

/** Zero is perfectly even; one means the largest deviation equals the median gap. */
private fun relativeSpacingVariation(spacings: List<Double>): Double {
  if (spacings.size < 2) return 0.0
  val typical = median(spacings) ?: return Double.POSITIVE_INFINITY
  if (typical <= 0.0) return Double.POSITIVE_INFINITY
  return spacings.maxOf { abs(it - typical) } / typical
}
