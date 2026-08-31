"""
Tests for everything that doesn't need a camera or a model.

Run: python -m pytest test_logic.py -v
"""

import numpy as np
import pytest

from steps.announcer import StepAnnouncer, Target
from steps.geometry import (
    clock_hour,
    distance_to_steps,
    focal_px_from_hfov,
    horizontal_angle_deg,
    sample_object_depth,
    step_length_from_height,
)


# --- geometry ---------------------------------------------------------------

def test_focal_length_matches_known_fov():
    # At 90 deg HFOV, focal length equals half the image width.
    assert focal_px_from_hfov(1000, 90.0) == pytest.approx(500.0)


def test_centred_object_is_straight_ahead():
    assert horizontal_angle_deg(320, 640, 70.0) == pytest.approx(0.0)


def test_angle_sign_is_left_negative_right_positive():
    assert horizontal_angle_deg(100, 640, 70.0) < 0
    assert horizontal_angle_deg(540, 640, 70.0) > 0


def test_frame_edge_equals_half_the_fov():
    assert horizontal_angle_deg(640, 640, 70.0) == pytest.approx(35.0)


def test_clock_face_mapping():
    assert clock_hour(0) == 12
    assert clock_hour(30) == 1
    assert clock_hour(-30) == 11
    assert clock_hour(90) == 3
    assert clock_hour(-90) == 9
    assert clock_hour(180) == 6


def test_clock_wraps_without_producing_zero_or_thirteen():
    for angle in range(-359, 360, 7):
        assert 1 <= clock_hour(angle) <= 12


def test_steps_always_round_down():
    # 5.9m at 0.72m per step is 8.19 steps -> 8, never 9.
    assert distance_to_steps(5.9, 0.72) == 8
    assert distance_to_steps(0.71, 0.72) == 0
    assert distance_to_steps(1.44, 0.72) == 2


def test_step_length_estimate_is_plausible():
    assert step_length_from_height(1.75) == pytest.approx(0.7175)


def test_invalid_inputs_raise():
    with pytest.raises(ValueError):
        distance_to_steps(5.0, 0.0)
    with pytest.raises(ValueError):
        step_length_from_height(0)


# --- depth sampling ---------------------------------------------------------

def test_background_leaking_into_box_does_not_drag_the_estimate():
    """
    The failure mode this guards against: a box around a chair also contains
    floor visible between the legs. That floor is farther away. A mean would
    be pulled outward and report the chair as farther than it is.
    """
    depth = np.full((100, 100), 10.0, dtype=np.float32)  # far background
    depth[40:60, 40:60] = 2.0                            # near object
    box = (40, 40, 60, 60)

    d = sample_object_depth(depth, box=box)
    assert d == pytest.approx(2.0, abs=0.1)


def test_mask_beats_box_when_object_is_sparse():
    depth = np.full((100, 100), 10.0, dtype=np.float32)
    mask = np.zeros((100, 100), dtype=bool)
    # A sparse object (think chair legs) inside a large box.
    mask[45:55, 20:80] = True
    depth[mask] = 3.0

    assert sample_object_depth(depth, mask=mask) == pytest.approx(3.0, abs=0.1)


def test_zero_and_nan_depths_are_discarded():
    depth = np.full((50, 50), np.nan, dtype=np.float32)
    depth[20:30, 20:30] = 4.0
    assert sample_object_depth(depth, box=(15, 15, 35, 35)) == pytest.approx(4.0, abs=0.1)


def test_returns_none_when_too_few_valid_pixels():
    depth = np.zeros((50, 50), dtype=np.float32)
    assert sample_object_depth(depth, box=(10, 10, 20, 20)) is None


# --- announcer --------------------------------------------------------------

def _target(distance, t, label="door", angle=0.0):
    return Target(label=label, distance_m=distance, angle_deg=angle,
                  confidence=0.9, timestamp=t)


def test_announces_on_first_acquisition():
    a = StepAnnouncer(step_length_m=0.72)
    msg = a.update(_target(8.0, 100.0), 100.0)
    assert msg is not None
    assert "step" in msg


def test_does_not_repeat_within_the_same_band():
    a = StepAnnouncer(step_length_m=0.72)
    assert a.update(_target(5.0, 100.0), 100.0) is not None
    # Still in the same band a moment later -- silence.
    assert a.update(_target(4.9, 101.0), 101.0) is None
    assert a.update(_target(4.8, 102.0), 102.0) is None


def test_announces_again_when_crossing_into_a_closer_band():
    a = StepAnnouncer(step_length_m=0.72, min_interval_s=0.0)
    a.update(_target(8.0, 100.0), 100.0)          # ~11 steps
    msg = a.update(_target(3.0, 105.0), 105.0)    # ~4 steps, tighter band
    assert msg is not None


def test_stays_silent_when_walking_away():
    a = StepAnnouncer(step_length_m=0.72, min_interval_s=0.0)
    a.update(_target(2.0, 100.0), 100.0)
    assert a.update(_target(4.0, 101.0), 101.0) is None
    assert a.update(_target(6.0, 102.0), 102.0) is None


def test_stale_frames_are_never_announced():
    a = StepAnnouncer(step_length_m=0.72, max_frame_age_s=0.5)
    # Frame captured at t=100 but only processed at t=101.
    assert a.update(_target(3.0, 100.0), 101.0) is None


def test_rate_limit_suppresses_chatter():
    a = StepAnnouncer(step_length_m=0.72, min_interval_s=2.0)
    assert a.update(_target(20.0, 100.0), 100.0) is not None
    # Crosses a band but far too soon after the last message.
    assert a.update(_target(6.0, 100.3), 100.3) is None


def test_closest_band_bypasses_the_rate_limit():
    """Arriving at the object is the one message that must always get through."""
    a = StepAnnouncer(step_length_m=0.72, min_interval_s=5.0)
    assert a.update(_target(20.0, 100.0), 100.0) is not None
    msg = a.update(_target(0.5, 100.2), 100.2)
    assert msg is not None
    assert "reach" in msg


def test_new_label_resets_and_announces():
    a = StepAnnouncer(step_length_m=0.72, min_interval_s=0.0)
    a.update(_target(3.0, 100.0, label="door"), 100.0)
    msg = a.update(_target(3.0, 101.0, label="chair"), 101.0)
    assert msg is not None and "Chair" in msg


def test_message_includes_direction_and_hedged_distance():
    a = StepAnnouncer(step_length_m=0.72)
    msg = a.update(_target(5.0, 100.0, angle=32.0), 100.0)
    assert "one o'clock" in msg
    assert "about" in msg


def test_singular_step_is_not_pluralised():
    a = StepAnnouncer(step_length_m=0.72)
    msg = a.update(_target(0.9, 100.0), 100.0)
    assert "one step." in msg


def test_hysteresis_prevents_flapping_on_a_boundary():
    """A reading jittering across a threshold must not fire repeatedly."""
    a = StepAnnouncer(step_length_m=1.0, bands=(10, 5, 3, 1), min_interval_s=0.0)
    a.update(_target(5.0, 100.0), 100.0)           # exactly 5 steps
    fired = 0
    for i, d in enumerate([5.2, 4.9, 5.1, 4.8, 5.05]):
        if a.update(_target(d, 101.0 + i, ), 101.0 + i):
            fired += 1
    assert fired == 0


def test_bands_must_be_descending():
    with pytest.raises(ValueError):
        StepAnnouncer(bands=(1, 5, 10))
