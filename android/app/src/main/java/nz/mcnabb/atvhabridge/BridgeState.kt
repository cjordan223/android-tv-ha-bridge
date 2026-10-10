package nz.mcnabb.atvhabridge

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Single source of truth for what the bridge is currently playing. The media
 * listener [publish]es into it; the HTTP/WebSocket server reads [currentJson] and
 * subscribes for live pushes. No Home Assistant coupling — just a state document.
 */
object BridgeState {
    data class Device(val id: String, val name: String, val model: String = Build.MODEL)

    @Volatile private var snap: NowPlayingSnapshot? = null
    @Volatile private var artUrl: String? = null
    @Volatile private var upNext: UpNext? = null
    @Volatile private var upNextArtUrl: String? = null
    @Volatile private var upNextList: List<UpNextItem> = emptyList()
    @Volatile var device: Device = Device(id = "", name = Build.MODEL)

    /** Device sensors (battery, storage, network, …), refreshed on the heartbeat. */
    @Volatile var sensors: JSONObject? = null

    /** The text box the TV's keyboard is up for (`text_input`), or null while no keyboard is up. */
    @Volatile private var textInput: JSONObject? = null

    /** The port the server actually bound (may differ from Config.PORT if it was busy). */
    @Volatile var port: Int = Config.PORT

    /** SHA-256 hex of the TLS cert HA pins. Shown in the QR + returned by /api/info. */
    @Volatile var fingerprint: String = ""

    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    /** Subscribe to live JSON pushes. The caller sends the initial snapshot itself,
     *  deferred off the WebSocket handshake (see StateSocket.onOpen). */
    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) = listeners.remove(l)

    fun publish(
        s: NowPlayingSnapshot?, art: String?, next: UpNext?, nextArt: String?,
        picks: List<UpNextItem> = emptyList(),
    ) {
        snap = s; artUrl = art; upNext = next; upNextArtUrl = nextArt; upNextList = picks
        val json = currentJson()
        listeners.forEach { runCatching { it(json) } }
    }

    /** Set by the accessibility service as the keyboard comes and goes; pushed only when it changes. */
    fun setTextInput(t: JSONObject?) {
        if (t?.toString() == textInput?.toString()) return
        textInput = t
        val json = currentJson()
        listeners.forEach { runCatching { it(json) } }
    }

    fun currentJson(): String {
        val s = snap
        val root = JSONObject()
        root.put("api_version", Config.API_VERSION)
        root.put("device", JSONObject().apply {
            put("id", device.id)
            put("name", device.name)
            put("model", device.model)
        })
        root.put("state", s?.state ?: "idle")
        if (s != null) {
            root.put("app", JSONObject().apply {
                put("package", s.appPackage)
                put("name", s.appName)
            })
            root.put("now_playing", JSONObject().apply {
                put("title", s.title ?: JSONObject.NULL)
                put("series_title", s.seriesTitle ?: JSONObject.NULL)
                put("episode_title", s.episodeTitle ?: JSONObject.NULL)
                put("season", s.season ?: JSONObject.NULL)
                put("episode", s.episode ?: JSONObject.NULL)
                put("artist", s.artist ?: JSONObject.NULL)
                if (s.durationMs > 0) put("duration", s.durationMs / 1000)
                put("position", s.positionMs / 1000)
                put("position_updated_at", iso(s.positionUpdatedAt))
                put("art", artUrl ?: JSONObject.NULL)
                put("is_ad", s.ad.isAd)
                if (s.ad.isAd) {
                    put("ad_skippable", s.ad.skippable)
                    put("ad_skip_in", s.ad.skipInSec)
                }
            })
        }
        upNext?.let { n ->
            root.put("up_next", JSONObject().apply {
                put("title", n.title ?: JSONObject.NULL)
                put("series_title", n.seriesTitle ?: JSONObject.NULL)
                put("art", upNextArtUrl ?: JSONObject.NULL)
            })
        }
        val picks = upNextList
        if (picks.isNotEmpty()) {
            root.put("up_next_list", JSONArray().apply {
                picks.forEach { n ->
                    put(JSONObject().apply {
                        put("title", n.title ?: JSONObject.NULL)
                        put("episode_title", n.episodeTitle ?: JSONObject.NULL)
                        put("season", n.season ?: JSONObject.NULL)
                        put("episode", n.episode ?: JSONObject.NULL)
                        put("art", n.artPath ?: JSONObject.NULL)
                        if (n.durationMs > 0) put("duration", n.durationMs / 1000)
                        if (n.positionMs > 0) put("position", n.positionMs / 1000)
                    })
                }
            })
        }
        sensors?.let { root.put("sensors", it) }
        textInput?.let { root.put("text_input", it) }
        return root.toString()
    }

    private fun iso(epochMs: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
