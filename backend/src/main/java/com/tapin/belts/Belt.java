package com.tapin.belts;

/** The five belts of the club, in ascending order (spec §3.3). There are deliberately no stripes (AC-31). */
public enum Belt {
    WHITE,
    BLUE,
    PURPLE,
    BROWN,
    BLACK;

    boolean isHigherThan(Belt other) {
        return ordinal() > other.ordinal();
    }
}
