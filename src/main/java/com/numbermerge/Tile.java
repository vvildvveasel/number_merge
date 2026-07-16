package com.numbermerge;

import java.util.concurrent.atomic.AtomicInteger;

/** A single tile. Has a stable id so the UI can track it across moves for animation. */
public class Tile {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    public final int id;
    public int rungIndex;

    public Tile(int rungIndex) {
        this.id = NEXT_ID.getAndIncrement();
        this.rungIndex = rungIndex;
    }

    public long value() {
        return Ladder.valueAt(rungIndex);
    }

    public String label() {
        return Ladder.label(rungIndex);
    }
}
