package dev.android.dynamicisland.island.view;

/** Presentation state of the island. */
public enum Mode {
    /** Nothing is shown. */
    HIDDEN,
    /** An empty, visible pill around the camera hole. */
    IDLE,
    /** The pill around the camera hole. */
    COMPACT,
    /** The stack of cards. */
    EXPANDED
}
