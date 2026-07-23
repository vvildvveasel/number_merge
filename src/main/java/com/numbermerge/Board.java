package com.numbermerge;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Holds the grid state and the rules for chaining/merging, the sliding legal-value
 * window, and refilling. Null in a cell means empty (only ever transient, never left
 * that way between moves).
 */
public class Board {
    public static final int COLS = 5;
    public static final int ROWS = 7;

    // Width of a tier: once the highest tile ever achieved reaches this many rungs
    // above the current floor, the floor advances by one and anything below the new
    // floor is swept. Smaller = more aggressive pressure to keep merging instead of
    // letting low tiles sit. Intended to become a player-facing difficulty setting later.
    private static final int DIFFICULTY_RANGE = 14;

    // Relative likelihood of spawning at floor, floor+1, floor+2, floor+3, floor+4 -
    // a linear taper (rather than a steep halving) so higher tiers show up more
    // often, widening the spread of values in play at once and making matches harder
    // to find. Floor is still the most common, just less dominant than before.
    private static final int[] SPAWN_WEIGHTS = {5, 4, 3, 2, 1};

    private final Tile[][] grid = new Tile[ROWS][COLS];
    private final Random rnd = new Random();
    private BigInteger score = BigInteger.ZERO;
    private int highestEverRungIndex = 0;
    private int windowFloor = 0;

    public Board() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                grid[r][c] = spawnTile();
            }
        }
    }

    public Tile get(int row, int col) {
        return grid[row][col];
    }

    public BigInteger getScore() {
        return score;
    }

    public int getHighestEverRungIndex() {
        return highestEverRungIndex;
    }

    public int getWindowFloor() {
        return windowFloor;
    }

    /** Overwrites this board with previously-saved state, e.g. loaded from disk. */
    public void loadState(int[][] rungIndices, BigInteger score, int highestEverRungIndex, int windowFloor) {
        restoreSnapshot(new Snapshot(rungIndices, score, highestEverRungIndex, windowFloor));
    }

    /** Captures everything needed to restore the board to its exact current state. */
    public Snapshot captureSnapshot() {
        int[][] rungIndices = new int[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                rungIndices[r][c] = grid[r][c].rungIndex;
            }
        }
        return new Snapshot(rungIndices, score, highestEverRungIndex, windowFloor);
    }

    /** Restores a previously captured snapshot, replacing the current state entirely. */
    public void restoreSnapshot(Snapshot snapshot) {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                grid[r][c] = new Tile(snapshot.rungIndices[r][c]);
            }
        }
        score = snapshot.score;
        highestEverRungIndex = snapshot.highestEverRungIndex;
        windowFloor = snapshot.windowFloor;
    }

    /**
     * Whether the given in-progress chain can legally be extended by candidate. The
     * very first link (starting tile to second tile) must be an exact value match;
     * every link after that may be an exact match or exactly the next rung up.
     */
    public boolean canExtend(List<Pos> chain, Pos candidate) {
        if (chain.isEmpty()) return true;
        if (chain.contains(candidate)) return false;
        Pos last = chain.get(chain.size() - 1);
        if (!last.isAdjacentTo(candidate)) return false;
        int prevRung = grid[last.row][last.col].rungIndex;
        int candidateRung = grid[candidate.row][candidate.col].rungIndex;
        if (chain.size() == 1) {
            return candidateRung == prevRung;
        }
        return candidateRung == prevRung || candidateRung == prevRung + 1;
    }

    /** Computes what merging this chain would produce, without mutating the board. */
    public int previewMergeRungIndex(List<Pos> chain) {
        BigInteger sum = BigInteger.ZERO;
        for (Pos p : chain) {
            sum = sum.add(grid[p.row][p.col].value());
        }
        return Ladder.rungIndexForValue(sum);
    }

    /**
     * Sums the chain's values and rounds to the nearest legal rung, clears every cell in
     * the chain except the last (which gets the merged result), then - if that result is a
     * new personal best - sweeps any tile that has fallen below the newly raised legal
     * window. Finally applies gravity and refills empty cells from the top.
     */
    public MergeResult commitMerge(List<Pos> chain) {
        int resultRung = previewMergeRungIndex(chain);

        for (int i = 0; i < chain.size() - 1; i++) {
            Pos p = chain.get(i);
            grid[p.row][p.col] = null;
        }
        Pos last = chain.get(chain.size() - 1);
        grid[last.row][last.col].rungIndex = resultRung;

        score = score.add(Ladder.valueAt(resultRung));

        List<SweptTile> swept = new ArrayList<>();
        if (resultRung > highestEverRungIndex) {
            highestEverRungIndex = resultRung;
            int oldFloor = windowFloor;
            advanceWindowIfNeeded();
            if (windowFloor != oldFloor) {
                swept = sweepObsoleteTiles();
            }
        }

        List<TileMove> moves = new ArrayList<>();
        List<TileSpawn> spawns = new ArrayList<>();
        applyGravityAndRefill(moves, spawns);

        return new MergeResult(last, resultRung, moves, spawns, swept);
    }

    private List<SweptTile> sweepObsoleteTiles() {
        List<SweptTile> swept = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Tile t = grid[r][c];
                if (t != null && t.rungIndex < windowFloor) {
                    swept.add(new SweptTile(new Pos(r, c), t.rungIndex));
                    grid[r][c] = null;
                }
            }
        }
        return swept;
    }

    /** Advances the floor by one for every full DIFFICULTY_RANGE the record has climbed. */
    private void advanceWindowIfNeeded() {
        while (highestEverRungIndex >= windowFloor + DIFFICULTY_RANGE - 1) {
            windowFloor++;
        }
    }

    private void applyGravityAndRefill(List<TileMove> moves, List<TileSpawn> spawns) {
        for (int c = 0; c < COLS; c++) {
            List<Tile> existing = new ArrayList<>();
            List<Integer> fromRows = new ArrayList<>();
            for (int r = 0; r < ROWS; r++) {
                if (grid[r][c] != null) {
                    existing.add(grid[r][c]);
                    fromRows.add(r);
                }
                grid[r][c] = null;
            }
            int emptyCount = ROWS - existing.size();

            for (int i = 0; i < existing.size(); i++) {
                int toRow = emptyCount + i;
                int fromRow = fromRows.get(i);
                grid[toRow][c] = existing.get(i);
                if (fromRow != toRow) {
                    moves.add(new TileMove(existing.get(i), c, fromRow, toRow));
                }
            }
            for (int k = 0; k < emptyCount; k++) {
                Tile tile = spawnTile();
                int toRow = k;
                int fromRow = k - emptyCount;
                grid[toRow][c] = tile;
                spawns.add(new TileSpawn(tile, c, fromRow, toRow));
            }
        }
    }

    // Each offset visits an adjacent pair exactly once (right, down, down-right, down-left).
    private static final int[][] HALF_NEIGHBOR_OFFSETS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    /**
     * True if at least one adjacent pair on the board could start a legal chain. Since
     * the first link of any chain must be an exact value match, this only needs to
     * look for equal-valued neighbors - a chain can never begin on an off-by-one pair.
     */
    public boolean hasValidMove() {
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int rung = grid[r][c].rungIndex;
                for (int[] offset : HALF_NEIGHBOR_OFFSETS) {
                    int nr = r + offset[0];
                    int nc = c + offset[1];
                    if (nr < 0 || nr >= ROWS || nc < 0 || nc >= COLS) continue;
                    int otherRung = grid[nr][nc].rungIndex;
                    if (otherRung == rung) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Draws from the current tier's spawn range, heavily weighted toward the floor. */
    private Tile spawnTile() {
        int totalWeight = 0;
        for (int w : SPAWN_WEIGHTS) totalWeight += w;
        int roll = rnd.nextInt(totalWeight);
        int cumulative = 0;
        for (int i = 0; i < SPAWN_WEIGHTS.length; i++) {
            cumulative += SPAWN_WEIGHTS[i];
            if (roll < cumulative) {
                return new Tile(windowFloor + i);
            }
        }
        return new Tile(windowFloor);
    }

    /** An existing tile that shifted from one row to another within a column due to gravity. */
    public static final class TileMove {
        public final Tile tile;
        public final int col;
        public final int fromRow;
        public final int toRow;

        TileMove(Tile tile, int col, int fromRow, int toRow) {
            this.tile = tile;
            this.col = col;
            this.fromRow = fromRow;
            this.toRow = toRow;
        }
    }

    /** A brand-new tile that fell in from above the visible grid to refill a column. */
    public static final class TileSpawn {
        public final Tile tile;
        public final int col;
        public final int fromRow;
        public final int toRow;

        TileSpawn(Tile tile, int col, int fromRow, int toRow) {
            this.tile = tile;
            this.col = col;
            this.fromRow = fromRow;
            this.toRow = toRow;
        }
    }

    /** A tile removed because it fell below the legal window after a new record was set. */
    public static final class SweptTile {
        public final Pos pos;
        public final int rungIndex;

        SweptTile(Pos pos, int rungIndex) {
            this.pos = pos;
            this.rungIndex = rungIndex;
        }
    }

    /** Everything the UI needs to animate the result of a merge. */
    public static final class MergeResult {
        public final Pos mergedPos;
        public final int mergedRungIndex;
        public final List<TileMove> moves;
        public final List<TileSpawn> spawns;
        public final List<SweptTile> swept;

        MergeResult(Pos mergedPos, int mergedRungIndex, List<TileMove> moves,
                    List<TileSpawn> spawns, List<SweptTile> swept) {
            this.mergedPos = mergedPos;
            this.mergedRungIndex = mergedRungIndex;
            this.moves = moves;
            this.spawns = spawns;
            this.swept = swept;
        }
    }

    /** An opaque, immutable capture of the board's full state for a single-step undo. */
    public static final class Snapshot {
        private final int[][] rungIndices;
        private final BigInteger score;
        private final int highestEverRungIndex;
        private final int windowFloor;

        private Snapshot(int[][] rungIndices, BigInteger score, int highestEverRungIndex, int windowFloor) {
            this.rungIndices = rungIndices;
            this.score = score;
            this.highestEverRungIndex = highestEverRungIndex;
            this.windowFloor = windowFloor;
        }
    }
}
