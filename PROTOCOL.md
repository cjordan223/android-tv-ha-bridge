# Data & Protocol Reference

Android TV HA Bridge exposes its data **generically** so you can build your own
dashboards, automations, or a **separate integration** on top of it — nothing is locked
to the bundled card. There are two layers:

1. **Home Assistant** — entities, attributes and an event bus event (easiest for HA users).
2. **The raw local API** — HTTPS + WebSocket on the TV itself (for non-HA clients, or to
   talk to a box directly).

---

## 1. Home Assistant surface

Each paired TV becomes one HA **device** with these entities (names are prefixed with the
name you gave the TV, e.g. `Bedroom TV`):

| Entity | What it carries |
| --- | --- |
| `media_player.<tv>` | State (`playing`/`paused`/`idle`), `media_title`, `media_series_title`, `app_name`, `app_id`, `media_position`, `media_duration`, `media_position_updated_at`, `entity_picture` (poster, proxied over TLS), `volume_level`, `is_volume_muted` — plus the extra attributes below. |
| `camera.<tv>_screen` | Live screen mirror (MJPEG via `/api/camera_proxy_stream/<entity>`) and still image (`/api/camera_proxy/<entity>`, from the bridge's `/screen.jpg`). Empty until the TV app holds its screen-capture grant (see [`/screen.jpg`](#2-the-raw-local-api)). |
| `remote.<tv>` | `send_command` with a bridge action (see command list); `turn_on`/`turn_off`; `turn_on` with `activity: <package>` launches an app. Commands that carry a value put it in the command: `launch:<package>`, `text:<text>`, `tap:<x>,<y>`. |
| `sensor.<tv>_current_app`, `_up_next`, `_battery`, `_storage_free`, `_memory_free`, `_volume`, `_network`, `_ip`, `_wifi_ssid`, `_wifi_signal`, `_last_boot` | One sensor per device metric. |

**Extra `media_player` attributes** (no standard field exists for these):
`episode_title`, `is_ad`, `ad_skippable`, `ad_skip_in`, `up_next_title`, `up_next_series_title`,
and `up_next_list` — the up-next picks (the current app's Watch-Next tiles, else the launcher's
cross-app *Continue watching* row; published while browsing too): a list of
`{ title, episode_title, season, episode, poster }`, where `poster` is an HA-relative
`/api/media_player_proxy/<entity>/browse_media/up_next/<i>?token=…` URL served exactly like
`entity_picture` (proxied over the pinned TLS connection). Picks are ordered *next episode*
tiles first, then most-recently-engaged, and never include what's playing (or paused) right now.
`text_input` (integration 0.1.21+) is present only while the TV's keyboard is up, or a text box was
tapped from a phone: `{ package, hint, text, password }` of the box it's for — `text` is never a
password's. It isn't kept in HA's history.

**Type on the TV:** `remote.send_command` with `text:<text>` makes the box that has input focus
hold exactly that text (send the whole text each time, e.g. as it's typed); `enter` is the
keyboard's Return (the box's own Search/Go/Done). `tap:<x>,<y>` (each 0–1, a fraction of the
screen) clicks what's under that point.

```yaml
action: remote.send_command
target: { entity_id: remote.bedroom_tv_remote }
data: { command: "text:bluey" }
```

**Play a pick:** `media_player.play_media` with `media_content_type: up_next` and
`media_content_id: "<i>"` (the pick's index in `up_next_list`) plays it on the TV through the
tile's own launch intent — the same thing the launcher does when you pick the tile.

```yaml
action: media_player.play_media
target: { entity_id: media_player.bedroom_tv }
data: { media_content_type: up_next, media_content_id: "0" }
```

**Open a link in an app:** `media_content_type: url`, the link as `media_content_id`, and the
app that must open it as `extra.package` (required — the link is always scoped to that app, so a
link two apps can handle never pops the TV's chooser). The TV refuses a package that isn't
installed and any scheme but `https`, `http`, `nextpvrtv`, `youtube`, `vnd.youtube`.

```yaml
action: media_player.play_media
target: { entity_id: media_player.bedroom_tv }
data:
  media_content_type: url
  media_content_id: "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
  extra: { package: app.smarttube.fdroid }
```

**Search on the TV:** `media_content_type: search`, the words as `media_content_id` (≤ 100
characters) — opens Google TV's universal search for them, on screen. `app` (a package) launches
an app.

Read any of it from a template, card or automation, e.g.:

```jinja
{{ state_attr('media_player.bedroom_tv', 'media_series_title') }}
{{ state_attr('media_player.bedroom_tv', 'up_next_title') }}
{{ states('sensor.bedroom_tv_up_next') }}
```

### The event (push, for a separate integration)

On **every** state push the integration fires an event on the HA bus:

- **Event type:** `androidtv_ha_bridge_updated`
- **Data:** `{ "device_id": "<stable id>", "name": "<friendly name>", "data": <full state document> }`

`data` is the complete document described in [§2](#the-state-document). This is the
intended hook for **mapping the data into another integration** (e.g. a remote): subscribe
to the event, key off `device_id`, and read `data` — no dependency on our entities.

```python
# In another custom integration
@callback
def _on_update(event):
    if event.data["device_id"] == MY_TV_ID:
        now_playing = event.data["data"].get("now_playing")
        ...

hass.bus.async_listen("androidtv_ha_bridge_updated", _on_update)
```

---

## 2. The raw local API

One port per TV (default `8099`, falling back to `8100`+ if busy), serving **HTTPS and
WebSocket over the same port**. The cert is self-signed; pin its SHA-256 fingerprint
(advertised three ways — see [Security](#security)). Discover a TV via mDNS
`_atvhabridge._tcp.` (TXT: `id`, `name`, `ver`, `fp`, `secure`).

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/api/info` | open | `{ id, name, model, api_version, paired, secure, fingerprint }` |
| POST | `/api/pair` | open | body `{ "code": "123456" }` → `{ "token": "…", "id": "…" }` |
| GET | `/api/state` | token | the current [state document](#the-state-document) |
| POST | `/api/command` | token | body `{ "action": "...", ... }` → `{ "ok": true }` |
| GET | `/api/apps` | token | `[ { package, name }, … ]` launchable apps |
| GET | `/art.jpg` | open | current poster JPEG |
| GET | `/art_next_<i>.jpg` | open | poster of `up_next_list[i]` (`/art_next.jpg` = index 0) |
| GET | `/screen.mjpeg` | token | live screen mirror, `multipart/x-mixed-replace` JPEG frames (~2.5fps, ~640px wide) |
| GET | `/screen.jpg` | token | one JPEG frame of the screen mirror (`Cache-Control: no-store`) |
| WS | `/ws?token=…` | token | live state pushes **and** commands (see below) |

`/screen.mjpeg` and `/screen.jpg` return `503` while the TV app holds no screen-capture grant
(a system MediaProjection consent). Grant it once on the TV with the app's *Enable screen
streaming* step (again after a restart); that grant is then kept.

Optionally, let the app take the grant by itself, with nothing shown on the TV — set appop
`PROJECT_MEDIA` once over adb. Opening the grant from the background also needs the app's
accessibility service enabled, or appop `SYSTEM_ALERT_WINDOW`:

```sh
adb shell appops set nz.mcnabb.atvhabridge PROJECT_MEDIA allow
adb shell appops set nz.mcnabb.atvhabridge SYSTEM_ALERT_WINDOW allow   # only if accessibility is off
```

Capture then only runs while someone watches: a request that finds none asks for a grant (answer
`503`, try again in a moment), and about 2 minutes after the last viewer leaves the grant is
released, so the TV's screen-capture indicator shows while someone is watching (steadily — a
viewer who looks away briefly comes back to the same grant) and not otherwise. The re-grant
briefly puts an invisible activity in front of the current app, and a paused player can close
itself (TVNZ+ did), so it is never taken while something plays or played in the last 15 s
(a media session playing or buffering, or a video app with sound), nor while the TV is asleep —
the request is answered `503` until then; and it's started without telling the app underneath
that the user is leaving. Apps with protected video capture as black anyway.
One capture display is held for the grant's whole life, but frames
are only composited while someone watches — a `/screen.mjpeg` client, or a `/screen.jpg` pull
(which keeps it on ~5 s, for pollers) — so an idle bridge costs nothing extra. The HA
integration's `camera.<device>_screen` entity proxies the stream byte-for-byte (HA's
`/api/camera_proxy_stream/<entity_id>`) and serves `/screen.jpg` as its still image.

**Auth:** pass the paired token as `?token=…` or `Authorization: Bearer …`. Get a token
by redeeming the 6-digit code shown in the TV app (`POST /api/pair`).

### WebSocket

Connect to `wss://<host>:<port>/ws?token=…`. The server:
- **pushes** the full state document (as a JSON text frame) on connect and on every change;
- **accepts commands** on the same socket — send `{"action":"dpad_up"}` etc. Because the
  socket is already open and authenticated, navigation has no per-key handshake latency.

### Commands (`action`)

`play` · `pause` · `playpause` · `stop` · `next` · `previous` · `seek` (`{"position": <seconds>}`)
· `dpad_up` · `dpad_down` · `dpad_left` · `dpad_right` · `ok` · `back` · `home`
· `volume_up` · `volume_down` · `volume_mute` · `volume_unmute` · `volume_set` (`{"level": 0-100}`)
· `sleep` · `wake` · `launch` (`{"package": "com.example"}`)
· `play_next` (`{"index": <i>}` — plays `up_next_list[i]` by firing that tile's own launch intent,
as read from the TV provider; the API takes an index, never an intent)
· `open` (`{"url": "…", "package": "…"}` — opens the link in that app only; refused unless the
package is installed and the scheme is `https`, `http`, `nextpvrtv`, `youtube` or `vnd.youtube`)
· `global_search` (`{"query": "…"}` — Google TV's universal search on screen; control characters
stripped, ≤ 100 characters)
· `text` (`{"text": "…"}` — the box that has input focus, or the one last tapped, holds exactly this
text: through the accessibility input method on Android 13+, else the box's own set-text action;
control characters stripped, ≤ 500 characters, empty clears it) · `enter` (the keyboard's Return:
the box's own action — Search, Go, Done — else an Enter key)
· `tap` (`{"x": 0-1, "y": 0-1}`, fractions of the screen — in the focused window, the smallest thing
under that point that's both selectable and clickable (else just clickable) is selected and OK'd, the
remote's way, or clicked where the selection can't be moved there; a text box there takes the next
`text`; with nothing clickable there, a tap gesture — apps that draw their own UI, like YouTube, take
neither, so use the D-pad there). Over Home Assistant's `remote.send_command`, which passes one
string per command, these are `text:<text>` and `tap:<x>,<y>`.

### The state document

```jsonc
{
  "api_version": 1,
  "device":   { "id": "…", "name": "Bedroom TV", "model": "…" },
  "state":    "playing",            // playing | paused | idle
  "app":      { "package": "com.example", "name": "TVNZ+" },
  "now_playing": {
    "title": "MasterChef Australia",
    "series_title": "MasterChef Australia",
    "episode_title": "Season 16 · Episode 42",
    "artist": null,
    "duration": 3600,               // seconds (omitted if unknown)
    "position": 1234,               // seconds
    "position_updated_at": "2026-09-06T01:23:45+00:00",  // project position from here
    "art": "/art.jpg?v=7",          // relative → prefix with the base URL
    "is_ad": false,
    "ad_skippable": false,          // present only while is_ad
    "ad_skip_in": 0                 // present only while is_ad
  },
  "up_next":  { "title": "…", "series_title": "…", "art": "/art_next.jpg?v=3" },
  "up_next_list": [                 // up to 6 picks: next episodes first, then most-recently-engaged; never what's playing
    { "title": "24", "episode_title": "Day 6: 1:00 A.M.-2:00 A.M.", "season": "6", "episode": "20",
      "art": "/art_next_0.jpg?v=1a2b3c", "duration": 2520, "position": 1653 }
  ],
  "sensors":  { "model": "…", "manufacturer": "…", "android_version": "…", "sdk": 34,
                "uptime": "…", "screen_on": true, "battery_level": 100, "battery_state": "…",
                "storage_free_gb": 12.3, "memory_free_mb": 512, "volume_level": 40,
                "volume_muted": false, "ip": "…", "network_type": "…",
                "wifi_ssid": "…", "wifi_rssi": -55 },
  "text_input": {                   // only while the TV's keyboard is up (or a box was tapped)
    "package": "org.jellyfin.androidtv", "hint": "Search", "text": "mayd", "password": false
  }
}
```

**Progress that lines up:** `position` is measured at `position_updated_at`. To render a
live progress bar, project it: `now = position + (wall_clock_now − position_updated_at)`
while `state == "playing"`.

Fields are absent (not null) when unavailable, except where noted. `title`/`series`/
`episode`/`art` for apps that publish no media session (e.g. TVNZ+, ThreeNow) are recovered
from the Android TV **Watch-Next** provider — the tile the player's own on-screen labels name;
failing that, with an S:E label on screen, the same season's neighbouring episode (autoplay);
otherwise the item is unknown (an app rewrites its row only when playback stops, so recency
would name the previous item). With no overlay read at all, the most recently engaged tile.

For such apps `state` comes from the device rather than the session: audio playing →
`playing`; no audio but the player's on-screen scrubber still present (read via the
accessibility overlay scrape) → `paused`, with `now_playing` kept and `position` frozen;
neither (the app's menus are up) → `idle`, with `now_playing` cleared to just the app.

`up_next_list` is the current app's Watch-Next tiles (its *Next episode / Continue watching*
row), falling back to the launcher's cross-app *Continue watching* row when the app has none.
Tiles the app flags as the **next episode** (`watch_next_type` NEXT) come first, then the rest
by recency; the item that's playing or paused is left out (matched on show + season/episode,
or title). It is published whether playing or idle — browsing is exactly when it's useful —
and re-published the moment an app rewrites its row (the bridge watches the provider). A pick's
`art` is present only once its poster resolved; `duration`/`position` (seconds) when known.
Play one with the `play_next` command (index into this list).

---

## Security

Every connection is **TLS 1.3, encrypted and MITM-proof** via cert-fingerprint pinning
(no CA). The SHA-256 fingerprint (lowercase hex) is advertised in `/api/info`
(`fingerprint`), the mDNS TXT record (`fp`), and the pairing QR (`&fp=`). Pin it:
- **aiohttp / HA:** `ssl=aiohttp.Fingerprint(bytes.fromhex(fp))`
- **OkHttp / Android:** a custom `X509TrustManager` that accepts the leaf iff its DER
  SHA-256 equals `fp` (OkHttp's `CertificatePinner` pins the SPKI, not the cert — don't use it).
