package id.infinia.porta.auto

/**
 * Utility functions for sanitizing text displayed on Android Auto.
 *
 * Android Auto head units have strict requirements:
 * - No emoji or non-printable characters (can cause IllegalArgumentException)
 * - Row text should be concise
 * - Template row counts are limited
 */
object CarTextUtils {

    // Regex that matches emoji and other non-basic characters.
    // Covers: emoticons, symbols, dingbats, variation selectors,
    // supplementary multilingual plane chars, etc.
    private val EMOJI_PATTERN = Regex(
        "[" +
            "\u00a9\u00ae" +                    // © ®
            "\u2000-\u27BF" +                   // symbols, arrows, dingbats
            "\uD83C[\uDC00-\uDFFF]" +           // emoticons block 1
            "\uD83D[\uDC00-\uDFFF]" +           // emoticons block 2
            "\uD83E[\uDD00-\uDFFF]" +           // supplemental symbols
            "\uFE00-\uFE0F" +                   // variation selectors
            "\u200D" +                          // zero-width joiner
            "\u20E3" +                          // combining enclosing keycap
            "\u2600-\u26FF" +                   // misc symbols
            "\u2700-\u27BF" +                   // dingbats
            "]"
    )

    /**
     * Strips emoji and non-printable characters from text.
     * Safe to pass to Android Auto Row.setTitle() and Row.addText().
     */
    fun sanitize(text: String): String {
        return text
            .replace(EMOJI_PATTERN, "")
            .replace(Regex("[\\p{Cntrl}&&[^\n\r\t]]"), "") // strip control chars
            .trim()
    }

    /**
     * Sanitize and truncate text for car display.
     * @param maxLength Maximum character count
     */
    fun sanitize(text: String, maxLength: Int): String {
        val clean = sanitize(text)
        return if (clean.length > maxLength) {
            clean.take(maxLength - 1) + "…"
        } else {
            clean
        }
    }

    /**
     * Safe truncation for car template text.
     */
    fun truncate(text: String, maxLength: Int): String {
        return if (text.length > maxLength) {
            text.take(maxLength - 1) + "…"
        } else {
            text
        }
    }
}
