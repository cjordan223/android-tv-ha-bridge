"""Remote entity for Android TV HA Bridge — key events + app launch."""
from __future__ import annotations

from collections.abc import Iterable
from typing import Any

from homeassistant.components.remote import RemoteEntity, RemoteEntityFeature
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import BridgeConfigEntry
from .entity import BridgeEntity


async def async_setup_entry(
    hass: HomeAssistant, entry: BridgeConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    async_add_entities([BridgeRemote(entry.runtime_data)])


class BridgeRemote(BridgeEntity, RemoteEntity):
    """Send navigation keys and launch apps over the low-latency socket."""

    _attr_name = "Remote"
    _attr_supported_features = RemoteEntityFeature.ACTIVITY

    def __init__(self, client) -> None:
        super().__init__(client)
        self._attr_unique_id = f"{client.device_id}_remote"

    @property
    def is_on(self) -> bool:
        return (self._client.state.get("sensors") or {}).get("screen_on", self._client.available)

    async def async_send_command(self, command: Iterable[str], **kwargs: Any) -> None:
        # Commands are bridge actions (dpad_up, dpad_down, ok, back, home, enter, …). A
        # "launch:<package>" command opens an app; "text:<text>" and "tap:<x>,<y>" go to the
        # bridge as written (it reads the value out of the command).
        for cmd in command:
            if cmd.startswith("launch:"):
                await self._client.async_send("launch", package=cmd.split(":", 1)[1])
            else:
                await self._client.async_send(cmd)

    async def async_turn_on(self, activity: str | None = None, **kwargs: Any) -> None:
        if activity:
            await self._client.async_send("launch", package=activity)
        else:
            await self._client.async_send("wake")

    async def async_turn_off(self, **kwargs: Any) -> None:
        await self._client.async_send("sleep")
