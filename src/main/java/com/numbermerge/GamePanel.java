package com.numbermerge;

import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Renders the grid, handles the click-and-drag chain gesture, and animates merges/drops. */
public class GamePanel extends JPanel {

    private static final int CELL_SIZE = 74;
    private static final int GAP = 6;
    private static final int MERGE_FADE_MS = 180;
    private static final int DROP_MS = 220;

    // How far (as a fraction of a cell) the cursor must move from the last chain
    // cell's center before a drag commits to a new direction. Avoids jitter near center.
    private static final double MIN_DRAG_FRACTION = 0.4;

    // 8 directions in screen-angle order (atan2(dy,dx), degrees), paired with their
    // row/col step. Cardinal (N/E/S/W) directions get a narrow angular window so they
    // require a deliberate straight motion; diagonals get a wide window so a slightly
    // wobbly diagonal swipe still registers as diagonal instead of snapping to H/V.
    private static final double[] DIRECTION_ANGLES = {0, 45, 90, 135, 180, -135, -90, -45};
    private static final int[][] DIRECTION_DELTAS = {
            {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}, {-1, 0}, {-1, 1}
    };
    private static final double CARDINAL_HALF_WIDTH_DEG = 15;
    private static final double DIAGONAL_HALF_WIDTH_DEG = 30;

    private final Board board;
    private final List<Pos> chain = new ArrayList<>();
    private boolean dragging = false;
    private boolean animating = false;

    private Consumer<BigInteger> onScoreChange;
    private Runnable onGameOver;
    private Consumer<Boolean> onUndoAvailabilityChange;
    private Consumer<String> onChainPreviewChange;
    private Board.Snapshot undoSnapshot;

    private enum Phase { MERGE_FADE, DROP }

    private Phase phase;
    private long phaseStartNanos;
    private int phaseDurationMs;
    private Timer animTimer;

    private List<Pos> fadingPositions = List.of();
    private Map<Pos, Integer> fadingRungIndex = Map.of();
    private Pos poppingPos;
    private int poppingRungIndex;
    private Set<Integer> phaseExcludedIds = Set.of();
    private List<Board.TileMove> activeMoves = List.of();
    private List<Board.TileSpawn> activeSpawns = List.of();

    public GamePanel(Board board) {
        this.board = board;
        setPreferredSize(new Dimension(Board.COLS * CELL_SIZE, Board.ROWS * CELL_SIZE));
        setBackground(Color.BLACK);

        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (animating) return;
                Pos p = posAt(e.getX(), e.getY());
                if (p == null) return;
                dragging = true;
                chain.clear();
                chain.add(p);
                updateChainPreview();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!dragging) return;
                dragging = false;
                if (chain.size() >= 2) {
                    startMergeAnimation(new ArrayList<>(chain));
                }
                chain.clear();
                updateChainPreview();
                repaint();
            }
        };
        addMouseListener(mouseHandler);

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (!dragging || animating || chain.isEmpty()) return;

                Pos lastPos = chain.get(chain.size() - 1);
                double lastCenterX = lastPos.col * CELL_SIZE + CELL_SIZE / 2.0;
                double lastCenterY = lastPos.row * CELL_SIZE + CELL_SIZE / 2.0;
                double dx = e.getX() - lastCenterX;
                double dy = e.getY() - lastCenterY;
                if (Math.hypot(dx, dy) < CELL_SIZE * MIN_DRAG_FRACTION) return;

                int dir = snapToDirection(Math.toDegrees(Math.atan2(dy, dx)));
                int[] delta = DIRECTION_DELTAS[dir];
                Pos p = new Pos(lastPos.row + delta[0], lastPos.col + delta[1]);
                if (p.row < 0 || p.row >= Board.ROWS || p.col < 0 || p.col >= Board.COLS) return;

                if (chain.size() >= 2 && chain.get(chain.size() - 2).equals(p)) {
                    chain.remove(chain.size() - 1);
                    updateChainPreview();
                    repaint();
                    return;
                }
                if (board.canExtend(chain, p)) {
                    chain.add(p);
                    updateChainPreview();
                    repaint();
                }
            }
        });
    }

    private void updateChainPreview() {
        if (onChainPreviewChange == null) return;
        if (chain.size() >= 2) {
            int previewRung = board.previewMergeRungIndex(chain);
            onChainPreviewChange.accept(Ladder.label(previewRung));
        } else {
            onChainPreviewChange.accept(null);
        }
    }

    public void setOnScoreChange(Consumer<BigInteger> listener) {
        this.onScoreChange = listener;
    }

    public void setOnGameOver(Runnable listener) {
        this.onGameOver = listener;
    }

    public void setOnChainPreviewChange(Consumer<String> listener) {
        this.onChainPreviewChange = listener;
    }

    public void setOnUndoAvailabilityChange(Consumer<Boolean> listener) {
        this.onUndoAvailabilityChange = listener;
    }

    /** Reverts to the state captured just before the last move, if one is available. */
    public void undo() {
        if (animating || undoSnapshot == null) return;
        dragging = false;
        chain.clear();
        updateChainPreview();
        board.restoreSnapshot(undoSnapshot);
        undoSnapshot = null;
        notifyUndoAvailability(false);
        if (onScoreChange != null) onScoreChange.accept(board.getScore());
        repaint();
    }

    private void notifyUndoAvailability(boolean available) {
        if (onUndoAvailabilityChange != null) onUndoAvailabilityChange.accept(available);
    }

    private Pos posAt(int x, int y) {
        int col = x / CELL_SIZE;
        int row = y / CELL_SIZE;
        if (row < 0 || row >= Board.ROWS || col < 0 || col >= Board.COLS) return null;
        return new Pos(row, col);
    }

    /** Picks the direction (index into DIRECTION_DELTAS) whose wedge contains angleDeg,
     *  where cardinal wedges are narrower than diagonal wedges. */
    private static int snapToDirection(double angleDeg) {
        int best = 0;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < DIRECTION_ANGLES.length; i++) {
            double halfWidth = (i % 2 == 0) ? CARDINAL_HALF_WIDTH_DEG : DIAGONAL_HALF_WIDTH_DEG;
            double score = angularDistanceDeg(angleDeg, DIRECTION_ANGLES[i]) / halfWidth;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static double angularDistanceDeg(double a, double b) {
        double diff = Math.abs(a - b) % 360;
        return diff > 180 ? 360 - diff : diff;
    }

    // ---- animation sequencing ----

    private void startMergeAnimation(List<Pos> chainSnapshot) {
        animating = true;

        List<Pos> fading = new ArrayList<>(chainSnapshot.subList(0, chainSnapshot.size() - 1));
        Map<Pos, Integer> fadeRungs = new HashMap<>();
        for (Pos p : fading) {
            fadeRungs.put(p, board.get(p.row, p.col).rungIndex);
        }
        poppingPos = chainSnapshot.get(chainSnapshot.size() - 1);

        undoSnapshot = board.captureSnapshot();
        notifyUndoAvailability(true);

        Board.MergeResult result = board.commitMerge(chainSnapshot);
        poppingRungIndex = result.mergedRungIndex;
        activeMoves = result.moves;
        activeSpawns = result.spawns;

        // Tiles swept for falling below the newly raised legal window fade out the
        // same way as the merged-away chain cells - just fold them into the same lists.
        for (Board.SweptTile s : result.swept) {
            fading.add(s.pos);
            fadeRungs.put(s.pos, s.rungIndex);
        }
        fadingPositions = fading;
        fadingRungIndex = fadeRungs;

        if (onScoreChange != null) onScoreChange.accept(board.getScore());

        Set<Integer> excluded = tileIdsOf(activeMoves, activeSpawns);
        excluded.add(board.get(poppingPos.row, poppingPos.col).id);
        phaseExcludedIds = excluded;

        beginPhase(Phase.MERGE_FADE, MERGE_FADE_MS);
    }

    private void beginPhase(Phase p, int durationMs) {
        phase = p;
        phaseDurationMs = durationMs;
        phaseStartNanos = System.nanoTime();
        if (animTimer != null) animTimer.stop();
        animTimer = new Timer(16, e -> tick());
        animTimer.start();
    }

    private void tick() {
        if (elapsedFraction() >= 1f) {
            animTimer.stop();
            if (phase == Phase.MERGE_FADE) {
                phaseExcludedIds = tileIdsOf(activeMoves, activeSpawns);
                beginPhase(Phase.DROP, DROP_MS);
            } else {
                finishAnimation();
            }
            return;
        }
        repaint();
    }

    private void finishAnimation() {
        animating = false;
        fadingPositions = List.of();
        fadingRungIndex = Map.of();
        poppingPos = null;
        activeMoves = List.of();
        activeSpawns = List.of();
        phaseExcludedIds = Set.of();
        repaint();
        if (!board.hasValidMove() && onGameOver != null) {
            onGameOver.run();
        }
    }

    private static Set<Integer> tileIdsOf(List<Board.TileMove> moves, List<Board.TileSpawn> spawns) {
        Set<Integer> ids = new HashSet<>();
        for (Board.TileMove m : moves) ids.add(m.tile.id);
        for (Board.TileSpawn s : spawns) ids.add(s.tile.id);
        return ids;
    }

    private float elapsedFraction() {
        long elapsedMs = (System.nanoTime() - phaseStartNanos) / 1_000_000L;
        return Math.min(1f, elapsedMs / (float) phaseDurationMs);
    }

    private static float easeOut(float t) {
        return 1 - (1 - t) * (1 - t);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ---- rendering ----

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (RenderTile rt : buildRenderList()) {
            drawTile(g2, rt);
        }
        drawChainLine(g2);
    }

    private List<RenderTile> buildRenderList() {
        List<RenderTile> list = new ArrayList<>();

        for (int r = 0; r < Board.ROWS; r++) {
            for (int c = 0; c < Board.COLS; c++) {
                Tile tile = board.get(r, c);
                if (animating && phaseExcludedIds.contains(tile.id)) continue;
                boolean selected = !animating && chain.contains(new Pos(r, c));
                list.add(new RenderTile(tile.label(), tile.rungIndex, r, c, 1f, 1f, selected));
            }
        }

        if (animating && phase == Phase.MERGE_FADE) {
            float t = elapsedFraction();
            for (Pos p : fadingPositions) {
                int rung = fadingRungIndex.get(p);
                list.add(new RenderTile(Ladder.label(rung), rung, p.row, p.col, 1f - t, 1f - 0.4f * t, false));
            }
            float pop = t < 0.5f ? 1f + 0.3f * (t / 0.5f) : 1f + 0.3f * (1f - (t - 0.5f) / 0.5f);
            list.add(new RenderTile(Ladder.label(poppingRungIndex), poppingRungIndex,
                    poppingPos.row, poppingPos.col, 1f, pop, false));
            for (Board.TileMove m : activeMoves) {
                list.add(new RenderTile(m.tile.label(), m.tile.rungIndex, m.fromRow, m.col, 1f, 1f, false));
            }
        } else if (animating && phase == Phase.DROP) {
            float t = easeOut(elapsedFraction());
            for (Board.TileMove m : activeMoves) {
                list.add(new RenderTile(m.tile.label(), m.tile.rungIndex,
                        lerp(m.fromRow, m.toRow, t), m.col, 1f, 1f, false));
            }
            for (Board.TileSpawn s : activeSpawns) {
                float alpha = Math.min(1f, 0.3f + 0.7f * t);
                list.add(new RenderTile(s.tile.label(), s.tile.rungIndex,
                        lerp(s.fromRow, s.toRow, t), s.col, alpha, 1f, false));
            }
        }

        return list;
    }

    private void drawTile(Graphics2D g2, RenderTile rt) {
        float centerX = rt.col * CELL_SIZE + CELL_SIZE / 2f;
        float centerY = rt.row * CELL_SIZE + CELL_SIZE / 2f;
        float size = (CELL_SIZE - GAP) * rt.scale;

        Composite oldComposite = g2.getComposite();
        if (rt.alpha < 1f) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(rt.alpha)));
        }

        Color base = colorForRung(rt.colorKey);
        if (rt.selected) base = base.brighter();
        g2.setColor(base);
        float x = centerX - size / 2f;
        float y = centerY - size / 2f;
        RoundRectangle2D.Float shape = new RoundRectangle2D.Float(x, y, size, size, 14, 14);
        g2.fill(shape);

        if (rt.selected) {
            g2.setColor(SELECTION_COLOR);
            g2.setStroke(new BasicStroke(3f));
            g2.draw(shape);
        }

        String text = rt.text;
        float baseFontSize = text.length() <= 3 ? 26f : Math.max(15f, 26f * 3f / text.length());
        g2.setFont(getFont().deriveFont(Font.BOLD, baseFontSize * Math.min(1.2f, rt.scale)));
        FontMetrics fm = g2.getFontMetrics();
        float textX = centerX - fm.stringWidth(text) / 2f;
        float textY = centerY - fm.getHeight() / 2f + fm.getAscent();
        g2.setColor(TEXT_COLOR);
        g2.drawString(text, textX, textY);

        g2.setComposite(oldComposite);
    }

    private void drawChainLine(Graphics2D g2) {
        if (chain.size() < 2) return;
        g2.setColor(new Color(255, 255, 255, 200));
        g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < chain.size() - 1; i++) {
            Pos a = chain.get(i);
            Pos b = chain.get(i + 1);
            int ax = a.col * CELL_SIZE + CELL_SIZE / 2;
            int ay = a.row * CELL_SIZE + CELL_SIZE / 2;
            int bx = b.col * CELL_SIZE + CELL_SIZE / 2;
            int by = b.row * CELL_SIZE + CELL_SIZE / 2;
            g2.drawLine(ax, ay, bx, by);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static final Color TEXT_COLOR = Color.WHITE;
    private static final Color SELECTION_COLOR = new Color(0xFF, 0xD1, 0x66);

    private static Color colorForRung(int rungIndex) {
        float hue = ((rungIndex * 33) % 360) / 360f;
        return Color.getHSBColor(hue, 0.45f, 0.5f);
    }

    /** A tile as it should appear this frame: possibly mid-fade, mid-pop, or mid-fall. */
    private static final class RenderTile {
        final String text;
        final int colorKey;
        final float row;
        final int col;
        final float alpha;
        final float scale;
        final boolean selected;

        RenderTile(String text, int colorKey, float row, int col, float alpha, float scale, boolean selected) {
            this.text = text;
            this.colorKey = colorKey;
            this.row = row;
            this.col = col;
            this.alpha = alpha;
            this.scale = scale;
            this.selected = selected;
        }
    }
}
