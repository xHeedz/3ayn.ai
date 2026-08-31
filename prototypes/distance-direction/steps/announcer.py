"""
Deciding WHEN to speak. This is the part that makes the feature usable or
unbearable, and it has nothing to do with computer vision.

Three rules drive everything here:
  - Speak on threshold crossings, not continuously. Constant narration is
    exhausting and it masks the ambient sound a blind user navigates by.
  - Never speak from a stale frame. A confidently wrong distance is worse
    than silence.
  - Don't re-announce when walking away. Only closing distance is news.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional

from .geometry import clock_hour, distance_to_steps, hour_word


@dataclass
class Target:
    label: str
    distance_m: float
    angle_deg: float
    confidence: float
    timestamp: float  # when the frame was captured, not when it was processed


class StepAnnouncer:
    """
    Feed it a target per frame, get back a string to speak or None.

    bands: step counts at which to announce, descending. Default fires at
    10, 5, 3 and 1 step -- coarse when far, tighter as you close in.
    """

    def __init__(
        self,
        step_length_m: float = 0.72,
        bands: tuple = (10, 5, 3, 1, 0),
        min_interval_s: float = 2.0,
        max_frame_age_s: float = 0.5,
        lost_after_s: float = 1.5,
        hysteresis_steps: int = 1,
    ):
        if step_length_m <= 0:
            raise ValueError("step_length_m must be positive")
        if list(bands) != sorted(bands, reverse=True):
            raise ValueError("bands must be in descending order")

        self.step_length_m = step_length_m
        self.bands = tuple(bands)
        self.min_interval_s = min_interval_s
        self.max_frame_age_s = max_frame_age_s
        self.lost_after_s = lost_after_s
        self.hysteresis_steps = hysteresis_steps

        self._last_label: Optional[str] = None
        self._last_band: int = -1
        self._last_spoke_at: float = float("-inf")
        self._last_seen_at: float = float("-inf")

    def _band_index(self, steps: int) -> int:
        """0 = beyond the outermost band, len(bands) = at the object."""
        return sum(1 for t in self.bands if steps <= t)

    def update(self, target: Optional[Target], now: float) -> Optional[str]:
        # Target gone long enough that we should treat a reappearance as fresh.
        if target is None:
            if now - self._last_seen_at > self.lost_after_s:
                self._reset()
            return None

        # Stale frame: the reading no longer describes where the user is.
        if now - target.timestamp > self.max_frame_age_s:
            return None

        self._last_seen_at = now
        steps = distance_to_steps(target.distance_m, self.step_length_m)
        band = self._band_index(steps)

        is_new_target = target.label != self._last_label
        if is_new_target:
            self._reset()
            self._last_label = target.label

        should_speak = False
        if band > self._last_band:
            # Closed into a tighter band. This is the case we care about.
            should_speak = True
            self._last_band = band
        elif band < self._last_band:
            # Moving away. Stay quiet, but only relax the band once clearly
            # past the boundary, so a reading hovering on a threshold doesn't
            # flap back and forth and re-trigger.
            boundary = self.bands[self._last_band - 1] if 0 < self._last_band <= len(self.bands) else None
            if boundary is None or steps > boundary + self.hysteresis_steps:
                self._last_band = band

        if not should_speak:
            return None

        # The two innermost bands bypass the rate limit. "One step away" and
        # "you're at it" are the messages that must never be suppressed, even
        # if the user is moving fast enough to trip the chatter guard.
        at_closest = band >= len(self.bands) - 1
        if not at_closest and now - self._last_spoke_at < self.min_interval_s:
            return None

        self._last_spoke_at = now
        return self._message(target, steps)

    def _message(self, target: Target, steps: int) -> str:
        label = target.label.replace("_", " ").capitalize()
        hour = hour_word(clock_hour(target.angle_deg))
        if steps <= 0:
            return f"{label}, {hour} o'clock, arm's reach."
        if steps == 1:
            return f"{label}, {hour} o'clock, one step."
        # "about" matters: never imply precision the depth model doesn't have.
        return f"{label}, {hour} o'clock, about {steps} steps."

    def _reset(self) -> None:
        self._last_label = None
        self._last_band = -1
