"""
Pure geometry. No camera, no model, no I/O -- so all of this is unit-testable
and portable straight to Dart when you move it into the Flutter app.
"""

from __future__ import annotations

import math
from typing import Optional

import numpy as np

# Typical laptop webcam. Phone rear cameras are usually 60-70. Measure yours
# with calibrate.py if the distances feel consistently off.
DEFAULT_HFOV_DEG = 70.0

# Step length as a fraction of body height. Starting guess only -- always
# prefer a measured value from calibrate.py.
HEIGHT_TO_STEP_RATIO = 0.41

_HOUR_WORDS = {
    1: "one", 2: "two", 3: "three", 4: "four", 5: "five", 6: "six",
    7: "seven", 8: "eight", 9: "nine", 10: "ten", 11: "eleven", 12: "twelve",
}


def focal_px_from_hfov(image_width: int, hfov_deg: float) -> float:
    """Pinhole focal length in pixels, derived from horizontal field of view."""
    if image_width <= 0:
        raise ValueError("image_width must be positive")
    if not 0 < hfov_deg < 180:
        raise ValueError("hfov_deg must be between 0 and 180")
    return (image_width / 2.0) / math.tan(math.radians(hfov_deg) / 2.0)


def horizontal_angle_deg(
    center_x: float, image_width: int, hfov_deg: float = DEFAULT_HFOV_DEG
) -> float:
    """
    Angle of an object off the optical axis. Negative is left, positive is right.

    Uses the proper arctan projection rather than a linear pixel->degree map.
    The linear approximation is fine near the centre but drifts badly at the
    frame edges, which is exactly where you need direction to be right.
    """
    f = focal_px_from_hfov(image_width, hfov_deg)
    return math.degrees(math.atan((center_x - image_width / 2.0) / f))


def clock_hour(angle_deg: float) -> int:
    """
    Map an angle to an orientation-and-mobility clock face.
    12 is straight ahead, 3 is hard right, 9 is hard left. One hour = 30 degrees.
    """
    hour = 12 + int(round(angle_deg / 30.0))
    while hour > 12:
        hour -= 12
    while hour < 1:
        hour += 12
    return hour


def hour_word(hour: int) -> str:
    return _HOUR_WORDS[hour]


def step_length_from_height(height_m: float) -> float:
    """Rough starting estimate. Calibration beats this every time."""
    if height_m <= 0:
        raise ValueError("height_m must be positive")
    return height_m * HEIGHT_TO_STEP_RATIO


def distance_to_steps(distance_m: float, step_length_m: float) -> int:
    """
    Metres to whole steps, always rounding DOWN.

    Deliberate: arriving a step early and reaching out is safe. Overshooting
    into a door is not.
    """
    if step_length_m <= 0:
        raise ValueError("step_length_m must be positive")
    if distance_m < 0:
        raise ValueError("distance_m must be non-negative")
    return max(0, int(distance_m / step_length_m))


def sample_object_depth(
    depth_map: np.ndarray,
    box: Optional[tuple] = None,
    mask: Optional[np.ndarray] = None,
    shrink: float = 0.5,
    percentile: float = 25.0,
    min_valid_px: int = 20,
) -> Optional[float]:
    """
    Pull a single distance in metres out of the depth map for one detection.

    Two decisions worth understanding:

    1. A segmentation mask is used when available, otherwise the box is shrunk
       toward its centre. Bounding boxes always leak background -- floor seen
       between chair legs, wall beside a door frame -- and that background is
       FARTHER than the object, so it drags any average outward.

    2. We take a low percentile (default 25th), not the median. What matters for
       walking is the near face of the object, since that is the surface you
       actually reach. The low percentile leans toward the near side, which is
       both more accurate and fails safe.
    """
    if depth_map.ndim != 2:
        raise ValueError("depth_map must be 2D (H, W)")

    if mask is not None:
        if mask.shape != depth_map.shape:
            raise ValueError("mask shape must match depth_map shape")
        values = depth_map[mask.astype(bool)]
    elif box is not None:
        h, w = depth_map.shape
        x1, y1, x2, y2 = box
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        half_w = max((x2 - x1) * shrink / 2.0, 1.0)
        half_h = max((y2 - y1) * shrink / 2.0, 1.0)
        x0 = max(0, int(cx - half_w))
        x3 = min(w, int(cx + half_w) + 1)
        y0 = max(0, int(cy - half_h))
        y3 = min(h, int(cy + half_h) + 1)
        if x3 <= x0 or y3 <= y0:
            return None
        values = depth_map[y0:y3, x0:x3].ravel()
    else:
        raise ValueError("provide either box or mask")

    values = values[np.isfinite(values) & (values > 0)]
    if values.size < min_valid_px:
        return None
    return float(np.percentile(values, percentile))
