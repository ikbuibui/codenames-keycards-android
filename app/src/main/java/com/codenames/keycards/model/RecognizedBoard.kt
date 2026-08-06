package com.codenames.keycards.model

/** How independently observed printed regions resolved to a single cell value. */
enum class CandidateAgreement { AGREED, SINGLE_READ, CONFLICT }

/** A distinct Tesseract interpretation that was not selected as the primary read. */
data class OcrAlternative(
  val text: String,
  val confidence: Int? = null,
)

/** A reviewed word at one stable, row-major keycard position. */
data class RecognizedCell(
  val text: String,
  val confidence: Int? = null,
  val candidateAgreement: CandidateAgreement = CandidateAgreement.SINGLE_READ,
  val manuallyEdited: Boolean = false,
  val alternatives: List<OcrAlternative> = emptyList(),
)

/** Only reads likely to benefit from human action enter the default review queue. */
fun RecognizedCell.needsOcrAttention(): Boolean =
  text.isBlank() ||
    (!manuallyEdited &&
      (text.count(Char::isLetterOrDigit) <= 1 ||
        (confidence ?: 100) < 55 ||
        (candidateAgreement == CandidateAgreement.CONFLICT && (confidence ?: 0) < 80)))

/**
 * A word board in keycard orientation.  [cells] is always row-major and has exactly
 * [rows] × [columns] entries; its index is therefore the corresponding keycard index.
 */
data class RecognizedBoard(
  val rows: Int,
  val columns: Int,
  val cells: List<RecognizedCell>,
) {
  init {
    require(rows > 0 && columns > 0)
    require(cells.size == rows * columns)
  }

  val isComplete: Boolean get() = cells.all { it.text.isNotBlank() }

  fun cellAt(row: Int, column: Int): RecognizedCell = cells[row * columns + column]
}

enum class BoardRotation { CLOCKWISE, COUNTERCLOCKWISE, HALF_TURN }

/** An absolute transform from the geometry detector's stable source coordinates. */
enum class BoardOrientation {
  ORIGINAL,
  CLOCKWISE,
  HALF_TURN,
  COUNTERCLOCKWISE,
}

val BoardOrientation.rotation: BoardRotation?
  get() =
    when (this) {
      BoardOrientation.ORIGINAL -> null
      BoardOrientation.CLOCKWISE -> BoardRotation.CLOCKWISE
      BoardOrientation.HALF_TURN -> BoardRotation.HALF_TURN
      BoardOrientation.COUNTERCLOCKWISE -> BoardRotation.COUNTERCLOCKWISE
    }

/** Keeps the positional mapping correct when the reviewer changes board orientation. */
fun RecognizedBoard.rotate(rotation: BoardRotation): RecognizedBoard {
  val destinationRows = if (rotation == BoardRotation.HALF_TURN) rows else columns
  val destinationColumns = if (rotation == BoardRotation.HALF_TURN) columns else rows
  val destination = MutableList(cells.size) { RecognizedCell("") }

  for (sourceRow in 0 until rows) {
    for (sourceColumn in 0 until columns) {
      val (destinationRow, destinationColumn) =
        when (rotation) {
          BoardRotation.CLOCKWISE -> sourceColumn to (rows - 1 - sourceRow)
          BoardRotation.COUNTERCLOCKWISE -> (columns - 1 - sourceColumn) to sourceRow
          BoardRotation.HALF_TURN -> (rows - 1 - sourceRow) to (columns - 1 - sourceColumn)
        }
      destination[destinationRow * destinationColumns + destinationColumn] =
        cellAt(sourceRow, sourceColumn)
    }
  }
  return RecognizedBoard(destinationRows, destinationColumns, destination)
}

fun RecognizedBoard.orient(orientation: BoardOrientation): RecognizedBoard =
  orientation.rotation?.let(::rotate) ?: this

/** Rotations that preserve the configured positional dimensions. */
fun compatibleBoardOrientations(
  sourceRows: Int,
  sourceColumns: Int,
  expectedRows: Int,
  expectedColumns: Int,
): List<BoardOrientation> =
  BoardOrientation.entries.filter { orientation ->
    val swapsDimensions = orientation == BoardOrientation.CLOCKWISE || orientation == BoardOrientation.COUNTERCLOCKWISE
    val orientedRows = if (swapsDimensions) sourceColumns else sourceRows
    val orientedColumns = if (swapsDimensions) sourceRows else sourceColumns
    orientedRows == expectedRows && orientedColumns == expectedColumns
  }

/** Maps a source coordinate after a reviewer rotation without allocating a board. */
fun transformedCellIndex(
  sourceRow: Int,
  sourceColumn: Int,
  rows: Int,
  columns: Int,
  rotation: BoardRotation?,
): Int {
  require(sourceRow in 0 until rows && sourceColumn in 0 until columns)
  return when (rotation) {
    null -> sourceRow * columns + sourceColumn
    BoardRotation.CLOCKWISE -> sourceColumn * rows + (rows - 1 - sourceRow)
    BoardRotation.COUNTERCLOCKWISE -> (columns - 1 - sourceColumn) * rows + sourceRow
    BoardRotation.HALF_TURN -> (rows - 1 - sourceRow) * columns + (columns - 1 - sourceColumn)
  }
}

/** Returns the stable source index represented by one index in an oriented board. */
fun sourceCellIndex(
  destinationIndex: Int,
  sourceRows: Int,
  sourceColumns: Int,
  orientation: BoardOrientation,
): Int {
  require(destinationIndex in 0 until sourceRows * sourceColumns)
  val destinationColumns =
    if (orientation == BoardOrientation.CLOCKWISE || orientation == BoardOrientation.COUNTERCLOCKWISE) sourceRows else sourceColumns
  val destinationRow = destinationIndex / destinationColumns
  val destinationColumn = destinationIndex % destinationColumns
  val (sourceRow, sourceColumn) =
    when (orientation) {
      BoardOrientation.ORIGINAL -> destinationRow to destinationColumn
      BoardOrientation.CLOCKWISE -> (sourceRows - 1 - destinationColumn) to destinationRow
      BoardOrientation.HALF_TURN -> (sourceRows - 1 - destinationRow) to (sourceColumns - 1 - destinationColumn)
      BoardOrientation.COUNTERCLOCKWISE -> destinationColumn to (sourceColumns - 1 - destinationRow)
    }
  return sourceRow * sourceColumns + sourceColumn
}
