package id.infinia.porta.service.glasses

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Custom [Presentation] that renders Porta AI content on the Rokid glasses display.
 *
 * Designed for Micro-OLED / Micro-LED displays:
 * - Dark background (pure black saves power on OLED)
 * - High-contrast white text with large readable fonts
 * - Accent color status bar
 * - Auto-scrolling for long responses
 *
 * Usage:
 * ```
 * val presentation = GlassesPresentation(context, display)
 * presentation.show()
 * presentation.updateContent("Porta AI", "Here's the response...")
 * ```
 */
class GlassesPresentation(
    context: Context,
    display: Display
) : Presentation(context, display) {

    companion object {
        // Colors optimized for Micro-OLED
        private const val BG_COLOR = 0xFF0A0A0F.toInt()        // Near-black
        private const val TITLE_COLOR = 0xFF818CF8.toInt()      // Indigo-400 (Porta accent)
        private const val BODY_COLOR = 0xFFF1F5F9.toInt()       // Slate-100
        private const val STATUS_COLOR = 0xFF64748B.toInt()     // Slate-500
        private const val THINKING_COLOR = 0xFFFBBF24.toInt()   // Amber-400
        private const val DIVIDER_COLOR = 0xFF1E293B.toInt()    // Slate-800
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var statusView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var thinkingView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build layout programmatically (no XML dependency)
        rootLayout = FrameLayout(context).apply {
            setBackgroundColor(BG_COLOR)
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }

        val mainLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Status bar (top) ──
        statusView = TextView(context).apply {
            setTextColor(STATUS_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text = "● Porta Connected"
            setPadding(0, 0, 0, dp(8))
        }
        mainLayout.addView(statusView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── Divider ──
        val divider = View(context).apply {
            setBackgroundColor(DIVIDER_COLOR)
        }
        mainLayout.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { bottomMargin = dp(16) })

        // ── Title ──
        titleView = TextView(context).apply {
            setTextColor(TITLE_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
            visibility = View.GONE
        }
        mainLayout.addView(titleView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── Thinking indicator ──
        thinkingView = TextView(context).apply {
            val ssb = SpannableStringBuilder("⏳ Thinking...")
            ssb.setSpan(
                ForegroundColorSpan(THINKING_COLOR),
                0, ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            ssb.setSpan(
                StyleSpan(Typeface.ITALIC),
                2, ssb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            text = ssb
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        mainLayout.addView(thinkingView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── Scrollable body ──
        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }

        bodyView = TextView(context).apply {
            setTextColor(BODY_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(dp(4).toFloat(), 1f)
            gravity = Gravity.START or Gravity.TOP
        }

        scrollView.addView(bodyView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        mainLayout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, // weight-based
            1f  // take remaining space
        ))

        rootLayout.addView(mainLayout)
        setContentView(rootLayout)

        // Show initial welcome
        showWelcome()
    }

    // ── Public API ──

    /**
     * Update the main display content.
     */
    fun updateContent(title: String, body: String) {
        titleView.post {
            if (title.isNotBlank()) {
                titleView.text = title
                titleView.visibility = View.VISIBLE
            } else {
                titleView.visibility = View.GONE
            }

            thinkingView.visibility = View.GONE
            bodyView.text = body
            bodyView.visibility = View.VISIBLE

            // Auto-scroll to bottom for streaming responses
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    /**
     * Show "Thinking..." state while AI is processing.
     */
    fun showThinking() {
        thinkingView.post {
            thinkingView.visibility = View.VISIBLE
            bodyView.visibility = View.GONE
            titleView.visibility = View.GONE
        }
    }

    /**
     * Clear the display and show welcome screen.
     */
    fun clear() {
        titleView.post {
            showWelcome()
        }
    }

    /**
     * Update the status bar text.
     */
    fun updateStatus(status: String) {
        statusView.post {
            statusView.text = status
        }
    }

    // ── Internal ──

    private fun showWelcome() {
        titleView.visibility = View.GONE
        thinkingView.visibility = View.GONE
        bodyView.visibility = View.VISIBLE

        val ssb = SpannableStringBuilder()
        ssb.append("Porta AI\n")
        ssb.setSpan(
            ForegroundColorSpan(TITLE_COLOR),
            0, ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        ssb.setSpan(
            StyleSpan(Typeface.BOLD),
            0, ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val start = ssb.length
        ssb.append("\nReady. Send a message from your phone.")
        ssb.setSpan(
            ForegroundColorSpan(STATUS_COLOR),
            start, ssb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        bodyView.text = ssb
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
