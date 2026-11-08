"""WebSocket client for a single Android TV HA Bridge device.

The bridge PUSHES its full state over a WebSocket (now-playing + sensors) and accepts
control commands over the same socket — a persistent connection so navigation has no
per-key handshake latency. Falls back to HTTP POST for commands if the socket is down.
"""
from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Callable

import aiohttp

from homeassistant.components import zeroconf
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import DOMAIN, EVENT_UPDATED

_LOGGER = logging.getLogger(__name__)

RECONNECT_SECONDS = 5
SERVICE_TYPE = "_atvhabridge._tcp.local."


def ssl_param(fingerprint: str | None):
    """aiohttp `ssl=` value: pin the cert SHA-256 when known, else trust-on-first-use.

    The bridge is a LAN device with a self-signed cert (no CA), so we pin its
    fingerprint instead of validating a chain. `False` still uses TLS — it just skips
    validation — which is only used to learn the fingerprint at pairing time.
    """
    return aiohttp.Fingerprint(bytes.fromhex(fingerprint)) if fingerprint else False


async def async_get_info(
    session: aiohttp.ClientSession, host: str, port: int, fingerprint: str | None = None
) -> dict | None:
    """GET /api/info (open, no auth) — used by discovery + the config flow.

    The response body carries the bridge's own `fingerprint`, so a TOFU call
    (fingerprint=None) can learn what to pin thereafter.
    """
    try:
        async with session.get(
            f"https://{host}:{port}/api/info",
            ssl=ssl_param(fingerprint),
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            if resp.status == 200:
                return await resp.json()
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
        return None
    return None


async def async_pair(
    session: aiohttp.ClientSession, host: str, port: int, code: str, fingerprint: str | None = None
) -> str | None:
    """POST the 6-digit code; return a long-lived token on success."""
    try:
        async with session.post(
            f"https://{host}:{port}/api/pair",
            json={"code": code},
            ssl=ssl_param(fingerprint),
            timeout=aiohttp.ClientTimeout(total=5),
        ) as resp:
            if resp.status == 200:
                return (await resp.json()).get("token")
    except (aiohttp.ClientError, asyncio.TimeoutError, ValueError):
        return None
    return None


class BridgeClient:
    """Maintains the live WebSocket to one bridge and sends it commands."""

    def __init__(
        self,
        hass: HomeAssistant,
        host: str,
        port: int,
        token: str,
        device_id: str,
        fingerprint: str | None = None,
    ) -> None:
        self._hass = hass
        self.host = host
        self.port = port
        self._token = token
        self.device_id = device_id
        self.device_name = ""
        self._ssl = ssl_param(fingerprint)
        self._session = async_get_clientsession(hass)
        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._task: asyncio.Task | None = None
        self._listeners: list[Callable[[], None]] = []
        self.state: dict = {}
        self.available = False

    @property
    def base_url(self) -> str:
        return f"https://{self.host}:{self.port}"

    def art_url(self, rel: str | None) -> str | None:
        if not rel:
            return None
        if rel.startswith("/"):
            return f"{self.base_url}{rel}"
        # Only follow absolute URLs that point back at this bridge, so a bad state
        # payload can't make Home Assistant fetch arbitrary hosts.
        return rel if rel.startswith(f"{self.base_url}/") else None

    async def async_fetch_image(self, url: str) -> tuple[bytes | None, str | None]:
        """Fetch poster bytes over the pinned TLS connection (HA can't validate the
        self-signed cert on its own, so the entity proxies the image through here)."""
        try:
            async with self._session.get(
                url, ssl=self._ssl, timeout=aiohttp.ClientTimeout(total=10)
            ) as resp:
                if resp.status == 200:
                    return await resp.read(), resp.headers.get("Content-Type")
        except (aiohttp.ClientError, asyncio.TimeoutError):
            pass
        return None, None

    async def async_screen_frame(self) -> bytes | None:
        """One JPEG of the screen mirror (/screen.jpg) — None while capture is off."""
        image, _ = await self.async_fetch_image(f"{self.base_url}/screen.jpg?token={self._token}")
        return image

    async def async_open_screen_stream(self) -> aiohttp.ClientResponse | None:
        """Open the bridge's live /screen.mjpeg over the pinned TLS connection. Returns
        the raw, still-open response for the caller to pipe through (and release when
        done) — a never-ending multipart body, so unlike async_fetch_image this has no
        total timeout, only a connect timeout so a dead bridge doesn't hang forever."""
        url = f"{self.base_url}/screen.mjpeg?token={self._token}"
        try:
            resp = await self._session.get(
                url, ssl=self._ssl, timeout=aiohttp.ClientTimeout(total=None, connect=8)
            )
        except (aiohttp.ClientError, asyncio.TimeoutError):
            return None
        if resp.status != 200:
            await resp.release()
            return None
        return resp

    @callback
    def add_listener(self, cb: Callable[[], None]) -> Callable[[], None]:
        self._listeners.append(cb)
        return lambda: self._listeners.remove(cb)

    def _notify(self) -> None:
        for cb in list(self._listeners):
            cb()

    async def async_start(self) -> None:
        self._task = self._hass.async_create_background_task(self._run(), f"{DOMAIN}_ws_{self.device_id}")

    async def async_stop(self) -> None:
        if self._task:
            self._task.cancel()
        if self._ws and not self._ws.closed:
            await self._ws.close()

    async def _run(self) -> None:
        fails = 0
        while True:
            url = f"wss://{self.host}:{self.port}/ws?token={self._token}"
            try:
                async with self._session.ws_connect(url, ssl=self._ssl, heartbeat=30) as ws:
                    self._ws = ws
                    self.available = True
                    fails = 0
                    self._notify()
                    _LOGGER.debug("Connected to bridge %s at %s:%s", self.device_id, self.host, self.port)
                    async for msg in ws:
                        if msg.type is aiohttp.WSMsgType.TEXT:
                            try:
                                self.state = json.loads(msg.data)
                            except ValueError:
                                continue
                            # Re-broadcast the full document on the HA event bus so a
                            # separate integration can map it without touching our entities.
                            self._hass.bus.async_fire(
                                EVENT_UPDATED,
                                {"device_id": self.device_id, "name": self.device_name, "data": self.state},
                            )
                            self._notify()
                        elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                            break
            except asyncio.CancelledError:
                raise
            except (aiohttp.ClientError, asyncio.TimeoutError) as err:
                _LOGGER.debug("Bridge %s WS error: %s", self.device_id, err)
            self._ws = None
            if self.available:
                self.available = False
                self._notify()
            fails += 1
            # The bridge's bound port can change across restarts (it advertises the port
            # it actually got). After a couple of misses, re-resolve it over mDNS and
            # reconnect to the new address — no re-pair needed.
            if fails >= 2:
                found = await self._rediscover()
                if found and found != (self.host, self.port):
                    _LOGGER.info(
                        "Bridge %s moved %s:%s -> %s:%s", self.device_id, self.host, self.port, *found
                    )
                    self.host, self.port = found
                    fails = 0
                    continue
            await asyncio.sleep(RECONNECT_SECONDS)

    async def _rediscover(self) -> tuple[str, int] | None:
        """Resolve the bridge's current host/port from its mDNS advertisement, so a
        port change (or a moved IP) heals itself without a re-pair."""
        try:
            from zeroconf.asyncio import AsyncServiceInfo

            aiozc = await zeroconf.async_get_async_instance(self._hass)
            name = f"MediaBridge-{self.device_id[:6]}.{SERVICE_TYPE}"
            info = AsyncServiceInfo(SERVICE_TYPE, name)
            if not await info.async_request(aiozc.zeroconf, 3000):
                return None
            # Guard against a 6-char service-name collision with another bridge.
            adv_id = info.properties.get(b"id", b"").decode(errors="ignore")
            if adv_id and adv_id != self.device_id:
                return None
            addrs = info.parsed_addresses()
            if addrs and info.port:
                return addrs[0], int(info.port)
        except Exception as err:  # noqa: BLE001 - discovery must never crash the loop
            _LOGGER.debug("Bridge %s rediscover failed: %s", self.device_id, err)
        return None

    async def async_send(self, action: str, **extra) -> None:
        """Send a control command — over the open socket first, HTTP as fallback."""
        payload = {"action": action, **extra}
        if self._ws is not None and not self._ws.closed:
            try:
                await self._ws.send_str(json.dumps(payload))
                return
            except (aiohttp.ClientError, RuntimeError) as err:
                _LOGGER.debug("WS send failed (%s), using HTTP", err)
        try:
            await self._session.post(
                f"{self.base_url}/api/command?token={self._token}",
                json=payload,
                ssl=self._ssl,
                timeout=aiohttp.ClientTimeout(total=5),
            )
        except (aiohttp.ClientError, asyncio.TimeoutError) as err:
            _LOGGER.warning("Command %s failed: %s", action, err)
