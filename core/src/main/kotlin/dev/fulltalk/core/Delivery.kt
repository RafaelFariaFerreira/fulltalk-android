package dev.fulltalk.core

/** What currently has input focus, as far as the accessibility tree can tell. */
enum class FocusTarget {
    /** An editable text field. */
    TEXT_INPUT,

    /** Something that does not take text, or nothing at all. */
    OTHER,
}

enum class DeliveryPlan {
    /** Leave the text in the clipboard for the user to paste later. */
    CLIPBOARD_ONLY,

    /** Copy to the clipboard, then paste into the focused field. */
    PASTE,
}

/**
 * Picks how a transcription reaches the user. The text always goes to the
 * clipboard; it is only pasted when a text field is known to have focus.
 */
fun deliveryPlan(target: FocusTarget, autoPaste: Boolean): DeliveryPlan =
    if (autoPaste && target == FocusTarget.TEXT_INPUT) DeliveryPlan.PASTE else DeliveryPlan.CLIPBOARD_ONLY
