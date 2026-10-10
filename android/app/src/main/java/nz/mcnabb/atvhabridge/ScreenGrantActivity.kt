package nz.mcnabb.atvhabridge

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Gets the screen-capture grant when a viewer asks for the screen and capture isn't running (it
 * stops itself once nobody's watching, so the TV's screen-capture indicator only shows while
 * someone is). When the app holds appop PROJECT_MEDIA (set once over adb), SystemUI's consent
 * activity returns RESULT_OK at once and shows nothing (AOSP 14 MediaProjectionPermissionActivity:
 * hasProjectionPermission → auto-grant), so this invisible activity just bounces through it.
 * Starting it from the background is allowed by the bridge's enabled accessibility service (or
 * appop SYSTEM_ALERT_WINDOW). Without PROJECT_MEDIA it never launches — the onboarding button stays
 * the way in, and ScreenCaptureService keeps that grant rather than letting it go when idle.
 *
 * The bounce still pauses whatever is on screen for a moment, and some players close themselves when
 * paused (TVNZ+), so it never runs while something is playing ([grantAllowed]); and it's started with
 * FLAG_ACTIVITY_NO_USER_ACTION so the app underneath isn't told the user is leaving it.
 */
class ScreenGrantActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_SCREEN)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Started here, while this activity is foreground (Android 14 requires that for a
        // mediaProjection foreground service).
        if (requestCode == REQ_SCREEN && resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, resultCode, data)
        } else {
            Log.w(TAG, "auto grant refused (result $resultCode)")
        }
        finish()
    }

    companion object {
        private const val TAG = "ScreenGrantActivity"
        private const val REQ_SCREEN = 1
        private const val OP_PROJECT_MEDIA = "android:project_media"

        /** Set by MediaListenerService so BridgeServer, which has no Context, can ask. */
        @Volatile var appContext: Context? = null

        private const val REQUEST_GAP_MS = 20_000L
        private const val PLAYING_GRACE_MS = 15_000L
        private var lastAttemptMs = 0L

        /** A viewer asked for the screen: request the grant if capture is down, the appop allows
         * it, the TV is awake and nothing is playing. */
        @Synchronized
        fun requestForViewer() {
            val ctx = appContext ?: return
            if (ScreenCaptureService.isActive() || !canAutoGrant(ctx)) return
            val now = SystemClock.elapsedRealtime()
            val interactive = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true
            val playing = somethingPlaying(ctx)
            if (!grantAllowed(interactive, playing, now - lastAttemptMs, lastAttemptMs != 0L, REQUEST_GAP_MS)) {
                if (playing && now - lastWaitLogMs > REQUEST_GAP_MS) {
                    lastWaitLogMs = now
                    Log.i(TAG, "a viewer wants the screen — waiting: something is playing")
                }
                return
            }
            lastAttemptMs = now
            Log.i(TAG, "a viewer wants the screen — requesting the grant")
            runCatching {
                ctx.startActivity(
                    Intent(ctx, ScreenGrantActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                    ),
                )
            }.onFailure { Log.w(TAG, "grant activity not started: ${it.message}") }
        }

        private var lastWaitLogMs = 0L
        @Volatile private var lastPlayingMs = 0L
        private val LIVE = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)

        /** The media listener saw something playing. */
        fun notePlaying() { lastPlayingMs = SystemClock.elapsedRealtime() }

        /** A player is going, or was a moment ago: its session says so, or (an app with no session)
         * a video app has sound going. "A moment ago" covers the gaps a player leaves between an ad
         * and the programme, when its session briefly goes away (YouTube, seen 2026-10-10). The home
         * screen's own trailers don't count — a grant there costs nothing. */
        private fun somethingPlaying(ctx: Context): Boolean {
            val now = SystemClock.elapsedRealtime()
            val live = Controls.mediaController?.playbackState?.state in LIVE ||
                (ctx.getSystemService(AudioManager::class.java)?.isMusicActive == true &&
                    Controls.accessibility?.rootInActiveWindow?.packageName?.toString().let { it == null || it != homePackage(ctx) })
            if (live) lastPlayingMs = now
            return lastPlayingMs != 0L && now - lastPlayingMs < PLAYING_GRACE_MS
        }

        private fun homePackage(ctx: Context): String? = runCatching {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName
        }.getOrNull()

        /** True when a grant can come back with nothing shown on the TV (appop PROJECT_MEDIA). */
        @Suppress("DEPRECATION")
        fun canAutoGrant(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
            val uid = ctx.applicationInfo.uid
            return runCatching { ops.checkOpNoThrow(OP_PROJECT_MEDIA, uid, ctx.packageName) }
                .getOrNull() == AppOpsManager.MODE_ALLOWED
        }
    }
}

/** Whether a viewer's request may bounce through the grant now: the TV is awake, nothing is playing
 * (the bounce pauses the app on screen, and some players close when paused), and the last attempt was
 * at least [gapMs] ago (a phone polls twice a second: one bounce per gap, not one per poll). */
fun grantAllowed(interactive: Boolean, playing: Boolean, sinceLastMs: Long, triedBefore: Boolean, gapMs: Long): Boolean =
    interactive && !playing && (!triedBefore || sinceLastMs >= gapMs)
