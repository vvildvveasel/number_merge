package com.numbermerge;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

public class GameFrame extends JFrame {

    private Board board;
    private GamePanel gamePanel;
    private final JLabel scoreLabel = new JLabel("Score: 0");
    private final JLabel bestLabel = new JLabel("Best: 0");
    private final JButton backButton = new JButton("Back");
    private long bestScore = 0;

    public GameFrame() {
        super("Number Merge");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        setLayout(new BorderLayout());

        add(buildTopBar(), BorderLayout.NORTH);
        if (GameStorage.saveExists() && promptContinue()) {
            continueGame();
        } else {
            startNewGame();
        }

        pack();
        setLocationRelativeTo(null);
    }

    private boolean promptContinue() {
        Object[] options = {"Continue", "New Game"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "A saved game was found.",
                "Number Merge",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
        return choice == 0;
    }

    private JPanel buildTopBar() {
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(new Color(0x1A1A1A));
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel("Number Merge", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setForeground(Color.WHITE);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        JPanel scores = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        scores.setOpaque(false);
        for (JLabel label : new JLabel[]{scoreLabel, bestLabel}) {
            label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
            label.setForeground(Color.WHITE);
            label.setHorizontalAlignment(SwingConstants.CENTER);
        }
        backButton.setEnabled(false);
        backButton.addActionListener(e -> gamePanel.undo());

        JButton newGameButton = new JButton("New Game");
        newGameButton.addActionListener(e -> startNewGame());

        scores.add(scoreLabel);
        scores.add(bestLabel);
        scores.add(backButton);
        scores.add(newGameButton);

        top.add(title, BorderLayout.NORTH);
        top.add(scores, BorderLayout.SOUTH);
        return top;
    }

    private void startNewGame() {
        board = new Board();
        attachGamePanel();
    }

    private void continueGame() {
        GameStorage.Loaded loaded = GameStorage.load();
        if (loaded == null) {
            startNewGame();
            return;
        }
        board = new Board();
        board.loadState(loaded.rungIndices, loaded.score, loaded.highestEverRungIndex, loaded.windowFloor);
        bestScore = Math.max(bestScore, loaded.bestScore);
        attachGamePanel();
    }

    private void attachGamePanel() {
        if (gamePanel != null) {
            remove(gamePanel);
        }
        gamePanel = new GamePanel(board);
        gamePanel.setOnScoreChange(this::updateScore);
        gamePanel.setOnGameOver(this::showGameOver);
        gamePanel.setOnUndoAvailabilityChange(backButton::setEnabled);
        add(gamePanel, BorderLayout.CENTER);
        bestLabel.setText("Best: " + bestScore);
        updateScore(board.getScore());
        backButton.setEnabled(false);
        revalidate();
        repaint();
    }

    private void updateScore(long score) {
        scoreLabel.setText("Score: " + score);
        if (score > bestScore) {
            bestScore = score;
            bestLabel.setText("Best: " + bestScore);
        }
        autosave();
    }

    private void autosave() {
        try {
            GameStorage.save(board, bestScore);
        } catch (RuntimeException e) {
            // Autosave failures shouldn't interrupt play; the last successful save just
            // stays on disk as the fallback if the game later closes or crashes.
        }
    }

    private void showGameOver() {
        Object[] options = {"New Game", "Back", "Quit"};
        int choice = JOptionPane.showOptionDialog(
                this,
                "No more legal merges left!\nFinal score: " + board.getScore(),
                "Game Over",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]);
        if (choice == 0) {
            startNewGame();
        } else if (choice == 1) {
            gamePanel.undo();
        } else if (choice == 2) {
            System.exit(0);
        }
        // Dialog dismissed without a choice (e.g. Esc) - leave the frozen board as-is;
        // the New Game / Back buttons in the top bar still work.
    }
}
