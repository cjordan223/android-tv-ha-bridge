package nz.mcnabb.atvhabridge

import android.content.ComponentName
import android.database.ContentObserver
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import java.util.concurrent.Executors

/** MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT — support-lib key (no framework
 * constant); a non-zero long while the current item is an ad. */
const val METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"

/**
 * The heart of the bridge. Being a bound NotificationListenerService is what grants
 * [MediaSessionManager] access without ADB. It reads the active media session (+ the
 * Watch-Next tile for apps that publish no metadata), and publishes the result into
 * [BridgeState], which the [BridgeServer] serves to Home Assistant. It also hosts the
 * server + mDNS advertisement for their whole lifetime.
 */
class MediaListenerService : NotificationListenerService() {

    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var artResolver: ArtResolver
    private lateinit var pairing: Pairing
    private var server: BridgeServer? = null
    private var discovery: Discovery? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var audioManager: AudioManager? = null
    private var currentController: MediaController? = null

    private val adDetector = AdDetector()
    private var lastProbe: String? = null

    /** The Watch-Next tile last identified as on screen, kept while the same app's player
     * shows the same length: the overlay's title label isn't in every read (it fades on
     * its own), and one read missing it doesn't mean the item changed. */
    private var lastPick: WatchNextResolver.Info? = null
    private var lastPickPkg: String? = null
    private var lastPickPlayerMs = 0L

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            aimScraper(currentController)
            refresh()
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() {
            currentController = null
            refresh()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers -> selectController(controllers) }

    private val heartbeat = object : Runnable {
        override fun run() {
            BridgeState.sensors = DeviceSensors.collect(this@MediaListenerService)
            // Re-scan active sessions (not just refresh the current one) so a session that
            // came up without a change event — or after a lost attach — still gets picked up.
            val m = mediaSessionManager
            if (m != null) {
                runCatching {
                    selectController(
                        m.getActiveSessions(ComponentName(this@MediaListenerService, MediaListenerService::class.java))
                    )
                }.onFailure { refresh() }
            } else refresh()
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val adTick = Runnable { refresh() }

    /** Audio start/stop/pause is our instant play/pause signal for apps with an empty
     * session (Jellyfin) — republish the moment it changes instead of at the next heartbeat. */
    private val audioCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            // Read the overlay right now, so the paused/playing decision sees the transport
            // as it is at this instant rather than as of the last accessibility event — and
            // once more a moment later, because the app swaps its Pause/Play control a frame
            // or two after the audio stops (read it too early and pause publishes ~3 s late).
            Controls.accessibility?.scrapeNow()
            mainHandler.post { refresh() }
            mainHandler.removeCallbacks(audioSettle)
            mainHandler.postDelayed(audioSettle, AUDIO_SETTLE_MS)
        }
    }
    private val audioSettle = Runnable { Controls.accessibility?.scrapeNow(); refresh() }

    private var lastScrapeRefreshMs = 0L
    private val scrapeRefresh = Runnable { lastScrapeRefreshMs = SystemClock.elapsedRealtime(); refresh() }

    /** Republish the moment an app rewrites its Watch-Next row (Jellyfin re-issues its tiles as
     * episodes finish), so the up-next picks track the provider instead of the next heartbeat.
     * A rewrite is a burst of row deletes/inserts, hence the short settle before one refresh. */
    private val watchNextRefresh = Runnable { refresh() }
    private val watchNextObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            mainHandler.removeCallbacks(watchNextRefresh)
            mainHandler.postDelayed(watchNextRefresh, WATCH_NEXT_SETTLE_MS)
        }
    }

    /** Republish after an overlay scrape, at most once per SCRAPE_REFRESH_GAP_MS (a per-second
     * scrape while the controls are up must not re-query Watch-Next every tick) — but never
     * dropped: a throttled request is deferred to the end of the gap, so the last read of a
     * burst ("player gone" behind a Back press) is always published. */
    private fun requestScrapeRefresh() {
        val wait = SCRAPE_REFRESH_GAP_MS - (SystemClock.elapsedRealtime() - lastScrapeRefreshMs)
        mainHandler.removeCallbacks(scrapeRefresh)
        mainHandler.postDelayed(scrapeRefresh, wait.coerceAtLeast(0))
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ScreenGrantActivity.appContext = applicationContext   // BridgeServer's way to ask for a grant
        isConnected = true
        try {
            pairing = Pairing(this)
            Controls.appContext = applicationContext
            BridgeState.device = BridgeState.Device(id = pairing.deviceId, name = pairing.defaultName)
            BridgeState.sensors = DeviceSensors.collect(this)
            artResolver = ArtResolver(contentResolver)
            // TLS: self-signed cert from the AndroidKeyStore; HA pins its fingerprint.
            val tlsFactory = TlsProvider.serverSocketFactory()
            BridgeState.fingerprint = TlsProvider.fingerprintHex()
            // Bind the first free port from Config.PORT upward (robust if another app
            // holds it). The actual port is advertised over mDNS + shown in the QR.
            var bound = 0
            for (p in Config.PORT..(Config.PORT + 4)) {
                try {
                    val s = BridgeServer(p, pairing) { which ->
                        when {
                            which == "current" -> artResolver.bitmap()
                            which.startsWith("next_") ->
                                which.removePrefix("next_").toIntOrNull()?.let(artResolver::upNextBitmap)
                            else -> null
                        }
                    }
                    s.makeSecure(tlsFactory, null)
                    // timeout 0 = no socket read timeout: NanoHTTPD's default 5s would
                    // close an idle WebSocket between the client's keepalive pings. A
                    // real disconnect is still detected via EOF. daemon=true as default.
                    s.start(0, true)
                    server = s
                    bound = p
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "port $p busy, trying next: ${e.message}")
                }
            }
            // Only advertise a port that actually bound — never a phantom default, or the
            // mDNS advert points clients at a dead port (and clients can't self-heal).
            if (bound > 0) {
                BridgeState.port = bound
                discovery = Discovery(this, bound, pairing).also { it.start() }
            } else {
                Log.e(TAG, "no port bound in ${Config.PORT}..${Config.PORT + 4}; not advertising")
            }

            mediaSessionManager = getSystemService(MediaSessionManager::class.java)
            audioManager = getSystemService(AudioManager::class.java)
            audioManager?.registerAudioPlaybackCallback(audioCallback, mainHandler)
            PlayerScrape.onScrape = { requestScrapeRefresh() }
            runCatching { contentResolver.registerContentObserver(WatchNextResolver.WATCH_NEXT, true, watchNextObserver) }
                .onFailure { Log.w(TAG, "watch-next observer not registered: ${it.message}") }
            attachMediaSessions(attempt = 0)

            mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
        } catch (e: Exception) {
            Log.e(TAG, "onListenerConnected failed: ${e.message}")
        }
    }

    /** Wire up media-session reading, retrying through the transient "Missing permission
     * to control media" SecurityException the system throws for a few hundred ms right
     * after the notification listener (re)binds. Without the retry the first
     * getActiveSessions() throws once, selectController never runs, and a stable session
     * (Jellyfin fires no change event once it's up) is never picked up — so nothing ever
     * shows as now-playing. */
    private fun attachMediaSessions(attempt: Int) {
        val manager = mediaSessionManager ?: return
        val component = ComponentName(this, MediaListenerService::class.java)
        try {
            runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            selectController(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            if (attempt < MEDIA_ATTACH_MAX_RETRIES) {
                mainHandler.postDelayed({ attachMediaSessions(attempt + 1) }, MEDIA_ATTACH_RETRY_MS)
            } else {
                Log.e(TAG, "media session attach failed after $attempt retries: ${e.message}")
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            runCatching { audioManager?.unregisterAudioPlaybackCallback(audioCallback) }
            PlayerScrape.onScrape = null
            runCatching { contentResolver.unregisterContentObserver(watchNextObserver) }
            currentController?.unregisterCallback(controllerCallback)
            currentController = null
            mainHandler.removeCallbacks(heartbeat)
            mainHandler.removeCallbacks(adTick)
            mainHandler.removeCallbacks(scrapeRefresh)
            mainHandler.removeCallbacks(audioSettle)
            mainHandler.removeCallbacks(watchNextRefresh)
            // Stop each independently so a throw from one (NanoHTTPD.stop can) doesn't
            // skip the rest — especially requestRebind, without which we may not rebind.
            runCatching { discovery?.stop() }; discovery = null
            runCatching { server?.stop() }; server = null
            requestRebind(ComponentName(this, MediaListenerService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "onListenerDisconnected failed: ${e.message}")
        }
    }

    private fun selectController(controllers: List<MediaController>?) {
        val next = pickController(controllers.orEmpty())
        aimScraper(next)
        // Read the overlay on every (re)selection — also each heartbeat — so a bridge
        // restart mid-pause doesn't sit on "idle" until the paused overlay's next event.
        Controls.accessibility?.scrapeNow()
        if (next?.sessionToken == currentController?.sessionToken) {
            refresh()
            return
        }
        currentController?.unregisterCallback(controllerCallback)
        currentController = next
        Controls.mediaController = next
        next?.registerCallback(controllerCallback, mainHandler)
        Log.i(TAG, "session: ${next?.packageName} state=${next?.playbackState?.state} " +
            (if (next?.hasTitle == true) "names its item — overlay scrape off" else "scrape=${Controls.currentMediaPackage}"))
        refresh()
    }

    /** The best-ranked session (see [sessionRank]); the system's own priority order breaks
     * ties. A session that publishes neither a play-state nor metadata is still taken (last),
     * so buildSnapshot can name the app and recover the item from its Watch-Next tile. */
    private fun pickController(controllers: List<MediaController>): MediaController? =
        controllers.map { it to sessionRank(it.playbackState?.state, it.metadata != null) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first

    /** A session that names its own item — read as published, never scraped. */
    private val MediaController.hasTitle: Boolean
        get() = metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }.isNullOrBlank().not()

    /** Point the overlay scraper at [c]'s app — or at nothing when [c] names its own item:
     * the scrape (and the audio-based play state) is only the fallback for a session that
     * publishes no title, and must never clobber one that does. Re-aimed whenever the
     * session's metadata changes, as the title can land after the session is picked. */
    private fun aimScraper(c: MediaController?) {
        Controls.currentMediaPackage = c?.takeUnless { it.hasTitle }?.packageName
    }

    private fun refresh() {
        val controller = currentController
        bgExecutor.execute {
            try {
                // Up-next picks are independent of play/idle: buildSnapshot clears the
                // now-playing fields while the user browses, and browsing is exactly when
                // the picks are useful — so they're resolved outside that clear, but AFTER
                // the snapshot, so the item that's playing can be left out of them.
                if (controller != null) {
                    artResolver.update(controller.metadata)
                    val snap = buildSnapshot(controller)
                    if (snap.state == "playing") ScreenGrantActivity.notePlaying()
                    artResolver.updatePoster(snap.posterUrl)
                    val artUrl = if (artResolver.bitmap() != null) "/art.jpg?v=${artResolver.version}" else null
                    probe(controller)
                    scheduleAdTick(snap.ad)
                    BridgeState.publish(snap, artUrl, null, null, upNextList(controller.packageName, snap))
                } else {
                    artResolver.update(null)
                    BridgeState.publish(null, null, null, null, upNextList(null, null))
                }
            } catch (e: Exception) {
                Log.e(TAG, "refresh failed: ${e.message}")
            }
        }
    }

    /** Up-next picks: the current app's Watch-Next tiles, else the launcher's cross-app
     * "continue watching" row — next episodes first, then by recency, never the item in
     * [playing] (see [isNowPlaying]). Posters are fetched into [ArtResolver] and served as
     * /art_next_<i>.jpg; a pick only advertises `art` once its poster actually resolved. The
     * tiles' launch intents are kept by index for `play_next`.
     * ponytail: re-queries the provider on every refresh (one cursor over a few dozen rows). */
    private fun upNextList(pkg: String?, playing: NowPlayingSnapshot?): List<UpNextItem> {
        val skip: (WatchNextResolver.Info) -> Boolean = { t ->
            val (series, ep) = orderSeriesEpisode(t.title, t.episodeTitle)
            isNowPlaying(series, ep, t.season, t.episode, playing)
        }
        val tiles = pkg?.let { WatchNextResolver.resolveList(contentResolver, it, UP_NEXT_LIMIT, skip) }.orEmpty()
            .ifEmpty { WatchNextResolver.resolveList(contentResolver, null, UP_NEXT_LIMIT, skip) }
        Controls.upNextIntents = tiles.map { it.intentUri }
        artResolver.updateUpNext(tiles.map { it.posterUri })
        return tiles.mapIndexed { i, t ->
            val (series, ep) = orderSeriesEpisode(t.title, t.episodeTitle)
            UpNextItem(
                title = series,
                episodeTitle = ep,
                season = t.season,
                episode = t.episode,
                artPath = t.posterUri?.takeIf { artResolver.upNextBitmap(i) != null }
                    ?.let { "/art_next_$i.jpg?v=${Integer.toHexString(it.hashCode())}" },
                durationMs = t.durationMs,
                positionMs = t.positionMs,
            )
        }
    }

    private fun buildSnapshot(controller: MediaController): NowPlayingSnapshot {
        val metadata = controller.metadata
        val ps = controller.playbackState
        // The session's app isn't the one on screen and isn't playing (Jellyfin's always-empty
        // session, a paused YouTube): it isn't what's on the TV. Name the app that is, with nothing
        // playing named — any audio is that app's (Live TV publishes no session at all), and the
        // Watch-Next tile is only the last thing watched (it would name an old show over Live TV).
        val onScreen = Controls.accessibility?.rootInActiveWindow?.packageName?.toString()
            ?.takeUnless { it == packageName }
        if (onScreen != null && onScreen != controller.packageName && ps?.state != PlaybackState.STATE_PLAYING) {
            val musicActive = runCatching { audioManager?.isMusicActive == true }.getOrDefault(false)
            return NowPlayingSnapshot(
                title = null, seriesTitle = null, episodeTitle = null, artist = null, album = null,
                durationMs = 0, positionMs = 0, positionUpdatedAt = System.currentTimeMillis(),
                state = if (musicActive) "playing" else "idle",
                appPackage = onScreen, appName = resolveAppName(onScreen),
            )
        }
        val mediaTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val metaDurationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val adFlag = (metadata?.getLong(METADATA_KEY_ADVERTISEMENT) ?: 0L) != 0L
        var wnPositionMs = 0L
        var wnDurationMs = 0L

        // Apps like TVNZ+/ThreeNow publish a session with no title/art. Recover the
        // show + episode + poster from the Android TV Watch-Next tile.
        var title = mediaTitle
        var series: String? = null
        var episode: String? = null
        var season: String? = null
        var episodeNum: String? = null
        var posterUrl: String? = null
        if (mediaTitle.isNullOrBlank()) {
            // The overlay's own labels (with any "S6:E21 — " prefix stripped) and its S:E
            // label pick the tile that's on screen; without a usable scrape the most recent
            // tile stands (see pickPlaying).
            val freshScrape = PlayerScrape.pkg == controller.packageName && PlayerScrape.ageMs() < SCRAPE_MAX_AGE_MS
            val onScreen = if (freshScrape) PlayerScrape.texts.map { EP_PREFIX.replaceFirst(it, "").trim() } else emptyList()
            val tiles = WatchNextResolver.resolveAll(contentResolver, controller.packageName)
            val playerMs = if (freshScrape) PlayerScrape.durationMs else 0L
            val pkg = controller.packageName
            val pick = namedOnScreen(tiles, onScreen)
                ?: lastPick?.takeIf { lastPickPkg == pkg && playerMs > 0 && playerMs == lastPickPlayerMs }
                ?: pickPlaying(tiles, onScreen, PlayerScrape.season.takeIf { freshScrape }, PlayerScrape.episode.takeIf { freshScrape }, playerMs)
            if (pick != null) { lastPick = pick; lastPickPkg = pkg; lastPickPlayerMs = playerMs }
            Log.d(TAG, "watch-next pick: ${pick?.title} / ${pick?.episodeTitle} (S${pick?.season}E${pick?.episode}; label S${PlayerScrape.season}E${PlayerScrape.episode}, player ${playerMs}ms) onScreen=${onScreen.take(6)}")
            pick?.let { wn ->
                val (s, e) = orderSeriesEpisode(wn.title, wn.episodeTitle)
                series = s; episode = e
                season = wn.season; episodeNum = wn.episode
                title = listOfNotNull(s, e).joinToString(" — ").ifBlank { null }
                posterUrl = wn.posterUri // http OR content:// — ArtResolver fetches both
                wnPositionMs = wn.positionMs
                wnDurationMs = wn.durationMs
            }
        } else if (metadata != null) {
            // A session that names its item may number it too: season and episode travel in
            // the disc/track keys (there are no dedicated ones — the patched Jellyfin video
            // player puts them there, with the series as artist). Read them as published.
            // ponytail: a music player that numbers its tracks reads as S<disc>E<track> too.
            season = metadata.getLong(MediaMetadata.METADATA_KEY_DISC_NUMBER).takeIf { it > 0 }?.toString()
            episodeNum = metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).takeIf { it > 0 }?.toString()
            if (season != null || episodeNum != null) {
                series = artist
                episode = mediaTitle
            }
        }

        // Progress fix: playbackState.position is measured at ps.lastPositionUpdateTime
        // (elapsedRealtime). Project it to *now* so a consumer that extrapolates from a
        // wall-clock timestamp stays aligned with what's actually on screen.
        val rawPos = ps?.position ?: 0L
        val speed = ps?.playbackSpeed ?: 1f
        val livePos = if (ps?.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            rawPos + ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * speed).toLong()
        } else rawPos

        // Apps with an empty session (Jellyfin) report no position/duration — fall back to
        // the Watch-Next tile's saved position + episode duration so the card shows progress.
        var durationMs = if (metaDurationMs > 0) metaDurationMs else wnDurationMs
        var positionMs = (if (livePos > 0) livePos else wnPositionMs).coerceAtLeast(0)
        var state = playbackStateToString(ps?.state)

        if (mediaTitle.isNullOrBlank()) {
            // Empty-session apps (Jellyfin) sit at STATE_NONE whether watching, paused or
            // just browsing their menus. Audio (isMusicActive) is the honest "playing"
            // signal; the overlay scrape then tells paused from browsing — while the
            // player's scrubber is still in the tree, the user is inside the player.
            val musicActive = runCatching { audioManager?.isMusicActive == true }.getOrDefault(false)
            val scraped = PlayerScrape.pkg == controller.packageName
            val inPlayer = scraped && PlayerScrape.inPlayer && PlayerScrape.ageMs() < IN_PLAYER_MAX_AGE_MS
            state = emptySessionState(musicActive, inPlayer, PlayerScrape.transport, state)

            // Generic overlay scrape: the accessibility service reads the live
            // position/episode off the on-screen controls. Prefer it over the lagging
            // Watch-Next tile, projecting the position forward between reads while playing
            // (paused keeps the position exactly where the scrape last saw it).
            if (scraped && PlayerScrape.ageMs() < SCRAPE_MAX_AGE_MS) {
                PlayerScrape.season?.let { season = it }
                PlayerScrape.episode?.let { episodeNum = it }
                PlayerScrape.title?.let { scraped ->
                    EP_PREFIX.replaceFirst(scraped, "").trim().ifBlank { null }?.let { episode = it }
                }
                val composed = listOfNotNull(series, episode).joinToString(" — ")
                title = when {
                    composed.isNotBlank() -> composed
                    !title.isNullOrBlank() -> title
                    else -> PlayerScrape.title
                }
                if (PlayerScrape.durationMs > 0) durationMs = PlayerScrape.durationMs
                positionMs = (PlayerScrape.positionMs + if (musicActive) PlayerScrape.ageMs() else 0L).coerceAtLeast(0)
            }

            // Idle means idle: the Watch-Next / scraped title + poster are only the *last*
            // thing watched, so never publish them as now-playing while the user browses —
            // only the app remains, and the card shows a clean "browsing <app>" state.
            // Paused is NOT idle: still inside the media, the poster stays up, frozen.
            if (state == "idle") {
                title = null; series = null; episode = null; season = null; episodeNum = null
                durationMs = 0; positionMs = 0; posterUrl = null
            }
        }

        return NowPlayingSnapshot(
            title = title,
            seriesTitle = series,
            episodeTitle = episode,
            season = season,
            episode = episodeNum,
            artist = artist,
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = durationMs,
            positionMs = positionMs,
            positionUpdatedAt = System.currentTimeMillis(),
            state = state,
            appPackage = controller.packageName,
            appName = resolveAppName(controller.packageName),
            ad = adDetector.update(controller.packageName, mediaTitle, durationMs, adFlag),
            posterUrl = posterUrl,
        )
    }

    private fun resolveAppName(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            friendlyAppName(pkg)
        }

    private val SEASON_EP = Regex("(?i)(season|episode|\\bS\\d+\\b|\\bE\\d+\\b)")

    /** Split the two Watch-Next label columns into (series, episode) regardless of which
     * column each app used (TVNZ+ puts the show in episode_title + the season/ep in title). */
    private fun orderSeriesEpisode(a: String?, b: String?): Pair<String?, String?> {
        val x = a?.trim()?.takeIf { it.isNotBlank() }
        val y = b?.trim()?.takeIf { it.isNotBlank() }
        if (x == null || y == null) return (x ?: y) to null
        return if (SEASON_EP.containsMatchIn(x) && !SEASON_EP.containsMatchIn(y)) y to x else x to y
    }

    private fun scheduleAdTick(ad: AdState) {
        mainHandler.removeCallbacks(adTick)
        if (ad.isAd && !ad.skippable) {
            mainHandler.postDelayed(adTick, ad.skipInSec * 1000L + AD_TICK_SLACK_MS)
        }
    }

    private fun probe(controller: MediaController) {
        runCatching {
            val line = SessionProbe.describe(controller)
            if (line != lastProbe) {
                lastProbe = line
                Log.i(SessionProbe.TAG, "$line pos=${controller.playbackState?.position}")
            }
        }.onFailure { Log.w(SessionProbe.TAG, "probe failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "MediaListenerService"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val AD_TICK_SLACK_MS = 200L
        // How many up-next picks to publish (and posters to hold) — a remote shows a short rail.
        private const val UP_NEXT_LIMIT = 6
        // Retry window for the "Missing permission to control media" race right after (re)bind.
        private const val MEDIA_ATTACH_RETRY_MS = 800L
        private const val MEDIA_ATTACH_MAX_RETRIES = 8
        // How long an accessibility overlay scrape stays usable (the reader projects the
        // position forward from it while audio plays; a fresh scrape lands on each interaction).
        private const val SCRAPE_MAX_AGE_MS = 20 * 60 * 1000L
        // How long "the scrubber is still in the tree" is trusted without a re-read. Paused,
        // Jellyfin's overlay re-renders on every clock minute (a re-read each ≤60 s), so
        // this only bites if accessibility events stop entirely — e.g. Home pressed on a
        // paused player: the launcher's events aren't ours, so it reads as paused this long.
        // ponytail: ceiling — a pause with no events for 3 min reads as idle until the next.
        private const val IN_PLAYER_MAX_AGE_MS = 3 * 60 * 1000L
        // Minimum gap between scrape-triggered republishes (each re-queries Watch-Next).
        private const val SCRAPE_REFRESH_GAP_MS = 2500L
        // Second overlay read after an audio start/stop, once the app has redrawn its controls.
        private const val AUDIO_SETTLE_MS = 400L
        // One refresh per burst of Watch-Next row changes (an app rewriting its whole row).
        private const val WATCH_NEXT_SETTLE_MS = 750L
        // Strips a leading "S6:E13 — " so the episode label composes cleanly with the series.
        private val EP_PREFIX = Regex("""^S\d+:?\s*E\d+\s*[—–-]\s*""")

        @Volatile
        var isConnected = false
            private set
    }
}
