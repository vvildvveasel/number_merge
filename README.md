# Number Merge

A grid-based number merging puzzle game, built in Java with a Swing GUI. Sweep a chain
through adjacent tiles to merge them into higher values, keep the board clear as new
tiles drop in, and see how high up the ladder you can climb before you run out of moves.

Related to games sometimes called "number connect" or "number string."

> **Note:** this project was built through AI-assisted development with Claude
> (Anthropic) - a human directed the design and testing, but the large majority of
> the code, game logic, and packaging setup was written by the AI.

## How it plays

- The board is a 5-wide by 7-tall grid, one number per cell.
- Click and drag through adjacent tiles (including diagonals) to build a chain. Each
  next tile must be either the **same value** as the last one in the chain, or the
  **next value up** on the ladder (see below).
- Release with a chain of 2+ tiles to merge them: they sum together and round to the
  nearest legal value, replacing the last tile in the chain. Everything else in the
  chain clears, tiles above drop down to fill the gap, and new tiles fall in from the
  top to refill the board.
- Tile values are drawn from a fixed ladder: `2, 4, 8, 16, ..., 1024`, then `2k, 4k,
  ..., 1024k`, then `2m, 4m, ...` and so on through billions/trillions/beyond.
- The board tracks the highest value you've ever reached. Climbing far enough above
  the current "legal" range advances the game to the next tier, sweeping away any
  tiles that have fallen too far behind - so the game keeps pushing you to merge
  upward instead of letting low tiles pile up.
- One-step undo (the "Back" button) lets you take back your last move.
- Progress autosaves after every move; relaunching the game offers to continue right
  where you left off.
- The game ends when no legal merge exists anywhere on the board.

## Running it

Requires a Java 17+ JDK.

With Maven:

```
mvn package
java -jar target/number-merge.jar
```

Without Maven, plain `javac`/`jar` works fine too, since the project has no external
dependencies - it's pure Java Swing.

## Packaging as a native app

The project includes a game icon at `packaging/icon.ico`, intended for use with the
JDK's built-in [`jpackage`](https://docs.oracle.com/en/java/javase/17/jpackage/)
tool to build a self-contained Windows app (no separate Java install required to run
it) or a full installer (via the [WiX Toolset](https://wixtoolset.org/)).

## License

MIT - see [LICENSE](LICENSE). Fork it, learn from it, build your own thing on top of
it.
