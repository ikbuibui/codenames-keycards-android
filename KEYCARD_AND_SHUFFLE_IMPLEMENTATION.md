# Keycard generation and target-list shuffle

This document summarizes how the app generates keycard grids and shuffles recognized target-word lists. Both operations use descending Fisher–Yates, but they operate on different values and have different state rules.

## Invariants

- A generated keycard always has exactly the configured role counts.
- Every valid placement of those roles is equally likely.
- Word-list shuffling changes display order only; it never changes the keycard, word-to-cell mapping, guessed state, or another team's order.
- Target entries are identified by stable grid indices, not displayed text, so duplicate words remain distinct.
- Saved permutations remain stable until a new game or explicit shuffle replaces them.

## Keycard grid generation

The implementation is in [`KeycardGenerator.kt`](app/src/main/java/com/codenames/keycards/model/KeycardGenerator.kt).

### Role multiset

Roles use integer values:

- teams: `1..teamCount`;
- bystander: `0`;
- assassin: `-1`.

For a configured board, the generator first builds an unordered role list containing:

1. `tilesPerTeam` entries for every team;
2. one additional entry for the first team when the bonus is enabled;
3. exactly one assassin;
4. enough bystanders to fill `rows × columns` cells.

Settings are validated before generation. Boards may be rectangular, dimensions are constrained to `2..10`, team count to `2..4`, and role counts must fit the available cells. Persisted or interactively changed settings are repaired by `normalized`, which clamps dimensions/counts and preserves as much of the requested turn order as possible.

### Fisher–Yates placement

The role list is shuffled in place from the final index down to index one:

```kotlin
for (last in card.lastIndex downTo 1) {
  val selected = random.nextInt(last + 1) // 0..last
  swap(card, last, selected)
}
```

Production keycard generation uses `SecureRandom.nextInt(bound)`. The security strength is not required by the game, but it provides an unbiased bounded source and avoids manual modulo arithmetic.

If every bounded draw is uniform, descending Fisher–Yates produces every permutation of the list with probability `1 / n!`. Duplicate role values do not bias distinct keycards: every visible role layout corresponds to the same number of underlying permutations—one factorial for each repeated role count—so all distinct valid layouts remain equally likely.

`isValidKeycard` independently checks list length, team counts, bonus ownership, one assassin, bystander count, and unknown role values when state is restored.

## Target-word display shuffle

The implementation is in [`TargetDisplayOrder.kt`](app/src/main/java/com/codenames/keycards/model/TargetDisplayOrder.kt).

### Stable positional identity

A recognized word remains stored at its row-major keycard index:

```text
cellIndex = row × columns + column
```

For team `t`, its targets are the indices where:

```kotlin
keycard[cellIndex] == t
```

The app shuffles these integer indices, never the word strings. Two cells may both display `SAME`, but their indices still identify separate targets with separate guessed state.

### Pure Fisher–Yates helper

`fisherYates` copies its input and applies the same descending algorithm:

```kotlin
fun fisherYates(indices: List<Int>, random: BoundedRandom): List<Int> {
  val result = indices.toMutableList()
  for (index in result.lastIndex downTo 1) {
    val selected = random.nextInt(index + 1)
    swap(result, index, selected)
  }
  return result
}
```

The input list is never modified. `BoundedRandom` is injectable for exhaustive tests; production target shuffling uses `Random.Default.nextInt(bound)`. Out-of-range results from a custom source are rejected rather than silently folded with `%`.

For `n` distinct cell indices, each legal draw sequence corresponds to exactly one permutation, and each permutation has probability:

```text
1/n × 1/(n-1) × ... × 1/2 = 1/n!
```

## Shuffle state lifecycle

When a game starts with a reviewed word board, `startGame` creates and stores one complete shuffled target-index permutation for every team. A scan-free game stores none.

During play:

- `targetDisplayOrder` returns the persisted complete permutation;
- `remainingTargetCellIndices` filters guessed indices from it;
- filtering does not mutate the saved order;
- undoing a guess restores the target at its prior relative position;
- **Shuffle words** replaces only the active team's complete permutation;
- shuffle is disabled when fewer than two unguessed active-team targets remain.

Shuffling the complete permutation and then filtering guessed entries leaves the remaining targets in a uniformly random relative order. Keeping the full permutation is also what makes undo stable.

The permutations and guessed cell indices are persisted with `GameState`. Normalization rejects stored orders whose membership or size no longer matches that team's keycard cells.

Generating a new keycard or changing board dimensions clears the recognized board, guessed indices, and target orders because their positional interpretation is no longer valid. Turn changes, pause/resume, recomposition, and process restart do not reorder targets.

## Tests

[`KeycardGeneratorTest`](app/src/test/java/com/codenames/keycards/model/KeycardGeneratorTest.kt) verifies:

- role counts for standard and rectangular boards;
- first-team bonus behavior;
- settings normalization and validation;
- exhaustive four-cell generation, including equal frequency for layouts with duplicate team roles.

[`WordBoardGameTest`](app/src/test/java/com/codenames/keycards/model/WordBoardGameTest.kt) verifies:

- all `8! = 40,320` permutations are produced exactly once by all legal eight-item draw sequences;
- Fisher–Yates does not mutate its input or change membership;
- duplicate displayed words retain distinct cell identities;
- guess/undo preserves relative order;
- active-team shuffle leaves the keycard, recognized board, guessed state, and other teams unchanged;
- new keycards and dimension changes clear stale positional state.

## Important files

| File | Responsibility |
|---|---|
| [`KeycardGenerator.kt`](app/src/main/java/com/codenames/keycards/model/KeycardGenerator.kt) | Settings normalization, role counts, keycard generation, and validation |
| [`TargetDisplayOrder.kt`](app/src/main/java/com/codenames/keycards/model/TargetDisplayOrder.kt) | Fisher–Yates helper, target-index selection, shuffle, guess, and undo |
| [`GameState.kt`](app/src/main/java/com/codenames/keycards/model/GameState.kt) | Game-start initialization, persistence normalization, and positional-state invalidation |
| [`KeycardGeneratorTest.kt`](app/src/test/java/com/codenames/keycards/model/KeycardGeneratorTest.kt) | Keycard counts and uniform-layout tests |
| [`WordBoardGameTest.kt`](app/src/test/java/com/codenames/keycards/model/WordBoardGameTest.kt) | Exhaustive permutation and target-state tests |
