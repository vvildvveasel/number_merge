package com.numbermerge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Persists a single save slot as a plain-text file in the user's home directory. */
public final class GameStorage {
    private static final Path SAVE_FILE =
            Paths.get(System.getProperty("user.home"), ".number-merge", "save.txt");

    private GameStorage() {
    }

    public static boolean saveExists() {
        return Files.isRegularFile(SAVE_FILE);
    }

    public static void save(Board board, long bestScore) {
        StringBuilder sb = new StringBuilder();
        sb.append("score=").append(board.getScore()).append('\n');
        sb.append("bestScore=").append(bestScore).append('\n');
        sb.append("highestEverRungIndex=").append(board.getHighestEverRungIndex()).append('\n');
        sb.append("windowFloor=").append(board.getWindowFloor()).append('\n');
        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                if (c > 0) sb.append(' ');
                sb.append(board.get(r, c).rungIndex);
            }
            sb.append('\n');
        }
        try {
            Files.createDirectories(SAVE_FILE.getParent());
            Files.writeString(SAVE_FILE, sb.toString());
        } catch (IOException e) {
            throw new RuntimeException("Could not save game", e);
        }
    }

    /** Returns null if there's no save file, or it can't be read/parsed. */
    public static Loaded load() {
        try {
            List<String> lines = Files.readAllLines(SAVE_FILE);
            if (lines.size() < 4 + Board.ROWS) return null;

            long score = parseLongValue(lines.get(0), "score=");
            long bestScore = parseLongValue(lines.get(1), "bestScore=");
            int highestEverRungIndex = (int) parseLongValue(lines.get(2), "highestEverRungIndex=");
            int windowFloor = (int) parseLongValue(lines.get(3), "windowFloor=");

            int[][] rungIndices = new int[Board.ROWS][Board.COLS];
            for (int r = 0; r < Board.ROWS; r++) {
                String[] parts = lines.get(4 + r).trim().split("\\s+");
                if (parts.length != Board.COLS) return null;
                for (int c = 0; c < Board.COLS; c++) {
                    rungIndices[r][c] = Integer.parseInt(parts[c]);
                }
            }
            return new Loaded(rungIndices, score, bestScore, highestEverRungIndex, windowFloor);
        } catch (IOException | NumberFormatException | IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static long parseLongValue(String line, String prefix) {
        if (line == null || !line.startsWith(prefix)) {
            throw new NumberFormatException("Malformed save line: " + line);
        }
        return Long.parseLong(line.substring(prefix.length()).trim());
    }

    /** Parsed save-file contents, ready to hand to Board.loadState(...). */
    public static final class Loaded {
        public final int[][] rungIndices;
        public final long score;
        public final long bestScore;
        public final int highestEverRungIndex;
        public final int windowFloor;

        Loaded(int[][] rungIndices, long score, long bestScore, int highestEverRungIndex, int windowFloor) {
            this.rungIndices = rungIndices;
            this.score = score;
            this.bestScore = bestScore;
            this.highestEverRungIndex = highestEverRungIndex;
            this.windowFloor = windowFloor;
        }
    }
}
