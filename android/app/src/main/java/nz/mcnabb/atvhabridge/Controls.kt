package nz.mcnabb.atvhabridge

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import org.json.JSONObject

/**
 * Executes control commands natively — no ADB. Media transport goes through the
 * active [MediaController] (we already have MediaSession access via the notification
 * listener); navigation goes through [ControlAccessibilityService]; app launch uses
 * a launcher intent. The [BridgeServer] calls [handle] for POST /api/command.
 */
object Controls {
    @Volatile var mediaController: MediaController? = null
    @Volatile var accessibility: ControlAccessibilityService? = null
    @Volatile var appContext: Context? = null

    /** Package whose player overlay the accessibility scraper reads (only while it is
     * foreground): the app owning the current media session — or null when that session
     * names its own item, in which case nothing is scraped. */
    @Volatile var currentMediaPackage: String? = null

    /** Launch intents of the last published up-next picks, by index — the only intents
     * `play_next` ever fires. They come from the TV provider (each app's own tile), never
     * from a client: the API takes an index, not an intent. */
    @Volatile var upNextIntents: List<String?> = emptyList()

    /** true if the command was dispatched. */
    fun handle(action: String, params: JSONObject): Boolean = when (action) {
        "play" -> transport { it.play() }
        "pause" -> transport { it.pause() }
        "playpause" -> togglePlayPause()
        "stop" -> transport { it.stop() }
        "next" -> transport { it.skipToNext() }
        "previous" -> transport { it.skipToPrevious() }
        "seek" -> transport { it.seekTo(params.optLong("position") * 1000) }
        "dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
        "ok", "center", "back", "home",
        -> accessibility?.doGlobal(action) ?: false
        "sleep", "screen_off" -> accessibility?.doGlobal("lock_screen") ?: false
        "wake", "screen_on" -> wake()
        "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
        "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
        "volume_mute" -> adjustVolume(AudioManager.ADJUST_MUTE)
        "volume_unmute" -> adjustVolume(AudioManager.ADJUST_UNMUTE)
        "volume_set" -> setVolume(params.optInt("level", -1))
        "launch" -> launch(params.optString("package"))
        "play_next" -> playNext(params.optInt("index", -1))
        "open" -> open(params.optString("url"), params.optString("package"))
        "global_search" -> globalSearch(params.optString("query"))
        "tap" -> tap(params.optDouble("x", Double.NaN), params.optDouble("y", Double.NaN))
        "text" -> type(params.optString("text"))
        "enter" -> accessibility?.enter() ?: false
        else -> withValue(action)
    }

    /** Home Assistant's remote.send_command passes each command as one string, so "tap:<x>,<y>"
     * and "text:<text>" carry their value in it. */
    private fun withValue(command: String): Boolean {
        tapPoint(command)?.let { (x, y) -> return tap(x, y) }
        if (command.startsWith(TEXT_PREFIX)) return type(command.removePrefix(TEXT_PREFIX))
        return false
    }

    private fun tap(x: Double, y: Double): Boolean =
        x in 0.0..1.0 && y in 0.0..1.0 && accessibility?.tap(x, y) == true

    private fun type(raw: String): Boolean {
        val text = typedText(raw) ?: return false
        return accessibility?.setText(text) ?: false
    }

    private fun audio(): AudioManager? = appContext?.getSystemService(AudioManager::class.java)

    private fun adjustVolume(direction: Int): Boolean {
        val am = audio() ?: return false
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI); true
        } catch (e: Exception) { false }
    }

    private fun setVolume(level: Int): Boolean {
        if (level < 0) return false
        val am = audio() ?: return false
        return try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max / 100).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            true
        } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun wake(): Boolean {
        val pm = appContext?.getSystemService(PowerManager::class.java) ?: return false
        return try {
            val flags = PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
            pm.newWakeLock(flags, "atvhabridge:wake").acquire(3000) // auto-released after the timeout
            true
        } catch (e: Exception) { false }
    }

    private inline fun transport(block: (MediaController.TransportControls) -> Unit): Boolean {
        val c = mediaController ?: return false
        return try { block(c.transportControls); true } catch (e: Exception) { false }
    }

    private fun togglePlayPause(): Boolean {
        val playing = mediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        return if (playing) transport { it.pause() } else transport { it.play() }
    }

    private fun launch(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val ctx = appContext ?: return false
        val intent = (ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: ctx.packageManager.getLaunchIntentForPackage(pkg)) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); true } catch (e: Exception) { false }
    }

    /** ACTION_VIEW [url] in [pkg] only — scoped, so a link two apps can open (SmartTube and
     * the official YouTube) never pops the chooser. See [openAllowed] for what may be opened. */
    private fun open(url: String, pkg: String): Boolean {
        val ctx = appContext ?: return false
        if (!openAllowed(url, pkg) || !installed(ctx, pkg)) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url).normalizeScheme())
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); true } catch (e: Exception) { false }
    }

    /** Google TV's universal search, on screen, across every app. */
    private fun globalSearch(raw: String): Boolean {
        val query = searchQuery(raw) ?: return false
        val ctx = appContext ?: return false
        val intent = Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (installed(ctx, GOOGLE_TV_SEARCH)) intent.setPackage(GOOGLE_TV_SEARCH)
        return try { ctx.startActivity(intent); true } catch (e: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Play up-next pick [index] the way the launcher would: fire the tile's own intent. If
     * the app that owns the tile is still inside its player, back out of it first and fire
     * a moment later — a deep link that lands on a live player is dropped by some apps
     * (Jellyfin ends up on Home), while the same link from any normal screen just works.
     * ponytail: "in the player" is the last overlay read; a stale one costs a Back press. */
    private fun playNext(index: Int): Boolean {
        val uri = upNextIntents.getOrNull(index) ?: return false
        val ctx = appContext ?: return false
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (e: Exception) { return false }
        val fire = { runCatching { ctx.startActivity(intent) }.isSuccess }
        val target = intent.component?.packageName ?: intent.`package`
        val leavePlayer = target != null && inPlayer(target) && accessibility?.doGlobal("back") == true
        if (!leavePlayer) return fire()
        Handler(Looper.getMainLooper()).postDelayed({ fire() }, LEAVE_PLAYER_MS)
        return true
    }

    /** true while [pkg]'s player is up: its overlay was last read with the scrubber in it,
     * or its own session — one that names its item is never scraped — reports live transport. */
    private fun inPlayer(pkg: String): Boolean {
        if (pkg == PlayerScrape.pkg && PlayerScrape.inPlayer) return true
        val c = mediaController?.takeIf { it.packageName == pkg } ?: return false
        return c.playbackState?.state in LIVE_STATES
    }

    private val LIVE_STATES = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING)

    // How long the app gets to close its player before the tile's intent is fired.
    private const val LEAVE_PLAYER_MS = 900L

    private const val GOOGLE_TV_SEARCH = "com.google.android.katniss"
}

/** Schemes `open` hands to an app: web links and the app deep links in use — never file:,
 * content:, intent: or javascript:. */
private val OPEN_SCHEMES = setOf("https", "http", "nextpvrtv", "youtube", "vnd.youtube")
private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
private const val MAX_URL_CHARS = 2048
private const val MAX_SEARCH_CHARS = 100

/** Whether `open` may fire [url] at [pkg]: an allow-listed scheme, no control characters, a
 * well-formed package name. That the package is installed is checked on the device. */
fun openAllowed(url: String, pkg: String): Boolean =
    url.length <= MAX_URL_CHARS &&
        url.none { it.isISOControl() } &&
        url.substringBefore(':', "").lowercase() in OPEN_SCHEMES &&
        PACKAGE_NAME.matches(pkg)

/** The `global_search` query with control characters stripped, or null when that leaves
 * nothing or more than [MAX_SEARCH_CHARS]. */
fun searchQuery(raw: String): String? =
    raw.filterNot { it.isISOControl() }.trim().takeIf { it.isNotEmpty() && it.length <= MAX_SEARCH_CHARS }

private const val TEXT_PREFIX = "text:"
private const val MAX_TEXT_CHARS = 500
private val TAP = Regex("""tap:([0-9]*\.?[0-9]+),([0-9]*\.?[0-9]+)""")

/** What `text` puts in the box: control characters out (a box is one line), or null past
 * [MAX_TEXT_CHARS]. Empty is allowed — it clears the box. */
fun typedText(raw: String): String? = raw.filterNot { it.isISOControl() }.takeIf { it.length <= MAX_TEXT_CHARS }

/** "tap:<x>,<y>", each a fraction of the screen from 0 to 1, as the point; anything else null. */
fun tapPoint(command: String): Pair<Double, Double>? {
    val m = TAP.matchEntire(command) ?: return null
    val x = m.groupValues[1].toDoubleOrNull() ?: return null
    val y = m.groupValues[2].toDoubleOrNull() ?: return null
    return if (x in 0.0..1.0 && y in 0.0..1.0) x to y else null
}
