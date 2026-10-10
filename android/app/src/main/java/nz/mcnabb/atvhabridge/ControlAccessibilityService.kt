package nz.mcnabb.atvhabridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * D-pad / OK / Back / Home navigation without ADB, AND a generic now-playing scraper.
 *
 * The scraper is the last resort for players that publish nothing to their MediaSession
 * (e.g. Jellyfin): while the on-screen player controls are visible it reads the live
 * position, duration and title straight off the overlay's accessibility nodes — no
 * per-app integration. It only reads while the current media app is foreground; the
 * reader ([MediaListenerService]) projects the position forward between reads.
 *
 * It also says whether the user is *inside* the player at all: the scrubber stays in the
 * tree for as long as the player is up (Jellyfin only fades its overlay to alpha 0, it never
 * removes it) and is gone the moment the app's menus replace it — which is what tells paused
 * from browsing, the one thing audio alone cannot.
 *
 * And the phone's keyboard and touch: while the TV's keyboard is up (an input-method window is
 * showing — Google TV's own, or its "use the keyboard on your mobile device" panel) it publishes the
 * text box ([BridgeState.setTextInput]); [setText] and [enter] type into whatever has input focus,
 * through the accessibility input method (Android 13+, works where the box isn't an accessible
 * node) or the focused node itself; [tap] clicks what's under a point of the screen.
 */
class ControlAccessibilityService : AccessibilityService() {

    private var lastScrapeMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val trailingScrape = Runnable { scrapeNow() }
    private val textCheck = Runnable { checkTextInput() }

    override fun onServiceConnected() {
        Controls.accessibility = this
    }

    override fun onDestroy() {
        handler.removeCallbacks(trailingScrape)
        handler.removeCallbacks(textCheck)
        if (Controls.accessibility === this) Controls.accessibility = null
        BridgeState.setTextInput(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // The keyboard comes and goes with windows; the box's contents change with focus and typing.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) tappedAt = null
        handler.removeCallbacks(textCheck)
        handler.postDelayed(textCheck, TEXT_CHECK_MS)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != Controls.currentMediaPackage) return
        // At most one read per SCRAPE_THROTTLE_MS — but a burst always gets one more read
        // once it settles: backing out of the player is a burst whose first event can land
        // while the player is still in the tree, and dropping the rest would leave us
        // believing it's up until the next event (the overlay clock, up to a minute later).
        handler.removeCallbacks(trailingScrape)
        if (SystemClock.elapsedRealtime() - lastScrapeMs >= SCRAPE_THROTTLE_MS) scrapeNow()
        else handler.postDelayed(trailingScrape, SCRAPE_THROTTLE_MS)
    }

    override fun onInterrupt() {}

    /** Read the overlay now. The reader calls this the instant audio starts/stops so the
     * paused/playing decision sees the transport as it is, not as of the last event. */
    fun scrapeNow() {
        val pkg = Controls.currentMediaPackage ?: return
        lastScrapeMs = SystemClock.elapsedRealtime()
        runCatching { scrapePlayer(pkg) }
    }

    /** Walk the foreground window's nodes for the player overlay's time/title texts. */
    private fun scrapePlayer(pkg: String) {
        val root = rootInActiveWindow ?: return
        val screenH = resources.displayMetrics.heightPixels
        val times = ArrayList<Long>()            // media times (seconds) in the lower half
        val longTexts = ArrayList<String>()
        var transport: String? = null            // what the play/pause control says we're doing
        val bounds = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            // Read both text and content-description — players often expose the title as a
            // content-desc, and it's the content-desc that survives in the a11y tree.
            for (s in listOfNotNull(n.text?.toString(), n.contentDescription?.toString())) {
                val secs = parseTime(s)
                if (secs != null) {
                    n.getBoundsInScreen(bounds)
                    // The scrubber's position/duration sit in the lower half of the screen;
                    // the wall clock lives at the top — exclude it by bounds.
                    if (bounds.top > screenH / 2) times.add(secs)
                } else if (s.length in 3..90) {
                    longTexts.add(s)
                }
                // The transport control is named for the action it OFFERS: "Pause" while
                // playing, "Play" while paused. Exact match only — "Playback speed" is a
                // neighbouring button, and a details page has a "Play" of its own (discarded
                // below along with everything else read without a scrubber).
                when (s.trim().lowercase()) {
                    "pause" -> transport = "playing"
                    "play" -> transport = "paused"
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        if (times.size < 2) {
            // No scrubber. In the window that last held it the player is gone (the app's
            // menus replaced it); in any other window this is only a popup over the player.
            if (PlayerScrape.inPlayer && root.windowId == PlayerScrape.windowId) {
                PlayerScrape.inPlayer = false
                PlayerScrape.transport = null
                Log.i(TAG, "scrape($pkg): player gone")
                PlayerScrape.onScrape?.invoke()
            } else {
                Log.d(TAG, "scrape($pkg): only ${times.size} time(s); texts=${longTexts.take(8)}")
            }
            return                               // keep the last scrape
        }
        times.sort()
        val positionSec = times.first()
        val durationSec = times.last()
        if (durationSec <= 0 || positionSec > durationSec) return
        // A new duration is a new item: drop the previous item's S:E label so a movie or
        // special (no S:E on its overlay) can't inherit the last episode's identity.
        // ponytail: two consecutive items of identical length keep the old label until an S:E shows.
        if (PlayerScrape.durationMs != durationSec * 1000L) {
            PlayerScrape.title = null; PlayerScrape.season = null; PlayerScrape.episode = null
        }
        // Position/duration update whenever the scrubber is visible (even behind a menu).
        PlayerScrape.pkg = pkg
        PlayerScrape.texts = longTexts
        PlayerScrape.positionMs = positionSec * 1000L
        PlayerScrape.durationMs = durationSec * 1000L
        PlayerScrape.scrapedAtElapsed = SystemClock.elapsedRealtime()
        PlayerScrape.inPlayer = true
        PlayerScrape.transport = transport
        PlayerScrape.windowId = root.windowId
        // Take the title ONLY from a season/episode-tagged label, so a settings overlay
        // ("Quality profile", "Audio" …) can never overwrite the real episode; the last
        // good title persists. A movie has no S:E here and keeps its Watch-Next title.
        longTexts.firstOrNull { SEASON_EP.containsMatchIn(it) }?.let { titled ->
            val se = SEASON_EP.find(titled)
            PlayerScrape.title = titled
            PlayerScrape.season = se?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            PlayerScrape.episode = se?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
        }
        Log.i(TAG, "scrape($pkg): pos=${positionSec}s dur=${durationSec}s transport=$transport title=${PlayerScrape.title} s=${PlayerScrape.season} e=${PlayerScrape.episode} texts=${longTexts.take(8)}")
        PlayerScrape.onScrape?.invoke()
    }

    private fun parseTime(s: String): Long? {
        val m = TIME.matchEntire(s.trim()) ?: return null
        val g = m.groupValues
        val a = g[1].toLongOrNull() ?: return null
        val b = g[2].toLongOrNull() ?: return null
        val c = g.getOrNull(3)?.takeIf { it.isNotBlank() }?.toLongOrNull()
        return if (c != null) a * 3600 + b * 60 + c else a * 60 + b
    }

    // ---- the phone's keyboard ---------------------------------------------------------------

    /** Android 13+: an input method of our own beside the TV's, so text reaches any box that takes
     * input — including apps that draw their own UI and expose no accessible text field. */
    override fun onCreateInputMethod(): InputMethod = object : InputMethod(this) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            handler.post(textCheck)
        }

        override fun onFinishInput() {
            super.onFinishInput()
            handler.post(textCheck)
        }
    }

    private fun connection(): InputMethod.AccessibilityInputConnection? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) inputMethod?.currentInputConnection else null

    private fun editorInfo(): EditorInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) inputMethod?.currentInputEditorInfo else null

    /** Where the phone last tapped a text box: typing goes to the box there when nothing has input
     * focus. Looked up afresh each time (apps rebuild their nodes); dropped when the screen changes. */
    @Volatile private var tappedAt: Pair<Int, Int>? = null

    private fun tappedField(): AccessibilityNodeInfo? = tappedAt?.let { (x, y) ->
        rootInActiveWindow?.let { clickableAt(it, x, y) }?.takeIf { it.isEditable }
    }

    private fun focusedField(): AccessibilityNodeInfo? =
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }
            ?: tappedField()

    /** The text box the TV's keyboard is up for, or null when no keyboard is showing. A password's
     * contents are never read. */
    private fun checkTextInput() {
        val keyboardUp = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            .getOrDefault(false)
        if (!keyboardUp && tappedField() == null) { BridgeState.setTextInput(null); return }
        val node = focusedField()
        val info = editorInfo()
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val password = node?.isPassword == true || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val hint = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node?.hintText else null) ?: info?.hintText
        val text = when {
            password -> null
            node != null -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) "" else node.text?.toString()
            else -> connection()?.getSurroundingText(MAX_FIELD, MAX_FIELD, 0)?.text?.toString()
        }
        BridgeState.setTextInput(JSONObject().apply {
            put("package", node?.packageName?.toString() ?: info?.packageName ?: JSONObject.NULL)
            put("hint", hint?.toString()?.take(MAX_HINT) ?: JSONObject.NULL)
            put("text", text?.take(MAX_FIELD) ?: JSONObject.NULL)
            put("password", password)
        })
    }

    /** Make the box hold exactly [text], cursor at the end. */
    fun setText(text: String): Boolean {
        connection()?.let { ic ->
            ic.deleteSurroundingText(MAX_FIELD, MAX_FIELD)
            ic.commitText(text, 1, null)
            Log.i(TAG, "text: ${text.length} chars via the input connection")
            handler.post(textCheck)
            return true
        }
        val node = focusedField() ?: return false.also { Log.i(TAG, "text: no box has input focus") }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val done = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (done) node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        })
        Log.i(TAG, "text: ${text.length} chars via the focused node (${if (done) "ok" else "refused"})")
        handler.post(textCheck)
        return done
    }

    /** The keyboard's Return: the box's own action (Search, Go, Done…), else an Enter key. */
    fun enter(): Boolean {
        connection()?.let { ic ->
            val action = (editorInfo()?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
            } else {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            Log.i(TAG, "enter: editor action $action via the input connection")
            tappedAt = null
            return true
        }
        val node = focusedField() ?: return false
        tappedAt = null   // typing's done: the keyboard's job ends with its Return
        handler.post(textCheck)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            .also { Log.i(TAG, "enter: the box's own action via the node (${if (it) "ok" else "refused"})") }
    }

    // ---- touch ----------------------------------------------------------------------------------

    /** Activate what's at ([fx], [fy]) — fractions of the screen. TV apps act on selection and OK, not
     * touch, so what's under the point in the window that has focus ([clickableAt]) is selected and
     * OK'd, or clicked; a text box takes the phone's typing; with nothing clickable there (an app that
     * draws its own UI) it's a tap gesture. */
    fun tap(fx: Double, fy: Double): Boolean {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java)?.defaultDisplay?.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return false
        val x = (fx * metrics.widthPixels).roundToInt().coerceIn(0, metrics.widthPixels - 1)
        val y = (fy * metrics.heightPixels).roundToInt().coerceIn(0, metrics.heightPixels - 1)
        val target = rootInActiveWindow?.let { clickableAt(it, x, y) }
        if (target != null && target.isEditable) {
            // A text box: the phone's keyboard types straight into it (a click doesn't open the TV's
            // keyboard in every app, and focusing it isn't always allowed).
            tappedAt = x to y
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            handler.post(textCheck)
            Log.i(TAG, "tap ($x,$y): a text box — the phone's keyboard types into it")
            return true
        }
        if (target != null) {
            // The remote's way first — move the selection there, press OK — which every TV app answers (TVNZ+ ignores a
            // click); a click where the selection can't be moved there (some Compose screens).
            if (target.isFocusable && !target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (target.refresh() && target.isFocused && doGlobal("dpad_center")) {
                Log.i(TAG, "tap ($x,$y): selected ${target.className}, OK")
                return true
            }
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "tap ($x,$y): clicked ${target.className}")
                return true
            }
        }
        Log.i(TAG, "tap ($x,$y): nothing clickable there — tap gesture")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build(), null, null,
        )
    }

    /** What a finger at (x, y) means: of everything on screen under it, a text box, else the smallest
     * thing that's both selectable and clickable — the card a remote would select (apps nest clickable
     * parts inside cards, and lay full-screen scrims over them) — else the smallest clickable thing. */
    private fun clickableAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        val under = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            n.getBoundsInScreen(bounds)
            if (!n.isVisibleToUser || !bounds.contains(x, y)) continue
            under.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        fun area(n: AccessibilityNodeInfo): Long { n.getBoundsInScreen(bounds); return bounds.width().toLong() * bounds.height() }
        return under.filter { it.isEditable }.minByOrNull(::area)
            ?: under.filter { it.isClickable && it.isFocusable }.minByOrNull(::area)
            ?: under.filter { it.isClickable }.minByOrNull(::area)
    }

    fun doGlobal(action: String): Boolean {
        val dpad = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return false
            "dpad_up" -> if (dpad) GLOBAL_ACTION_DPAD_UP else return false
            "dpad_down" -> if (dpad) GLOBAL_ACTION_DPAD_DOWN else return false
            "dpad_left" -> if (dpad) GLOBAL_ACTION_DPAD_LEFT else return false
            "dpad_right" -> if (dpad) GLOBAL_ACTION_DPAD_RIGHT else return false
            "dpad_center", "ok", "center" -> if (dpad) GLOBAL_ACTION_DPAD_CENTER else return false
            else -> return false
        }
        return try { performGlobalAction(id) } catch (e: Exception) { false }
    }

    companion object {
        private const val TAG = "PlayerScrape"
        private const val SCRAPE_THROTTLE_MS = 900L
        private const val TEXT_CHECK_MS = 150L
        private const val TAP_MS = 60L
        private const val MAX_FIELD = 500
        private const val MAX_HINT = 120
        private val TIME = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")
        private val SEASON_EP = Regex("""(?i)S(\d{1,3}):?\s*E(\d{1,3})""")
    }
}
