"""
Detection + metric depth -> a list of Targets.

Two models run per frame:
  - a detector, telling you WHAT and WHERE in the image
  - a depth model, telling you HOW FAR every pixel is, in metres

Neither alone is enough. Detection gives no distance; depth gives no labels.
"""

from __future__ import annotations

import time
from typing import List, Optional, Sequence

import numpy as np

from .announcer import Target
from .geometry import DEFAULT_HFOV_DEG, horizontal_angle_deg, sample_object_depth


class StepsPipeline:
    def __init__(
        self,
        classes: Optional[Sequence[str]] = None,
        detect_weights: str = "yolo26n.pt",
        depth_weights: str = "yolo26n-depth.pt",
        conf: float = 0.35,
        imgsz: int = 640,
        device: Optional[str] = None,
    ):
        """
        classes: pass a list like ["door", "stairs"] to use the open-vocabulary
        detector, which accepts arbitrary text. Leave it None to use the stock
        COCO detector, which is much faster but has a fixed 80-class list that
        does NOT include doors.
        """
        from ultralytics import YOLO  # imported lazily; heavy

        self.conf = conf
        self.imgsz = imgsz
        self.device = device
        self.open_vocab = classes is not None

        if self.open_vocab:
            from ultralytics import YOLOE

            self.detector = YOLOE("yoloe-11s-seg.pt")
            self.detector.set_classes(list(classes), self.detector.get_text_pe(list(classes)))
        else:
            self.detector = YOLO(detect_weights)

        self.depther = YOLO(depth_weights)

    def process(
        self,
        frame: np.ndarray,
        hfov_deg: float = DEFAULT_HFOV_DEG,
        timestamp: Optional[float] = None,
    ) -> List[Target]:
        if timestamp is None:
            timestamp = time.time()

        height, width = frame.shape[:2]

        det = self.detector.predict(
            frame, conf=self.conf, imgsz=self.imgsz, device=self.device, verbose=False
        )[0]
        dep = self.depther.predict(
            frame, imgsz=self.imgsz, device=self.device, verbose=False
        )[0]

        depth_map = dep.depth.data
        if hasattr(depth_map, "cpu"):
            depth_map = depth_map.cpu().numpy()
        depth_map = np.squeeze(np.asarray(depth_map))

        if det.boxes is None or len(det.boxes) == 0:
            return []

        boxes = det.boxes.xyxy.cpu().numpy()
        confs = det.boxes.conf.cpu().numpy()
        cls_ids = det.boxes.cls.cpu().numpy().astype(int)

        # Segmentation masks, when the model produces them, give a far cleaner
        # depth sample than a rectangle does.
        masks = None
        if getattr(det, "masks", None) is not None and det.masks is not None:
            try:
                masks = det.masks.data.cpu().numpy()
            except Exception:
                masks = None

        targets: List[Target] = []
        for i, box in enumerate(boxes):
            mask = None
            if masks is not None and i < len(masks):
                m = masks[i]
                if m.shape != depth_map.shape:
                    import cv2

                    m = cv2.resize(
                        m.astype(np.float32),
                        (depth_map.shape[1], depth_map.shape[0]),
                        interpolation=cv2.INTER_NEAREST,
                    )
                mask = m > 0.5

            distance = sample_object_depth(depth_map, box=tuple(box), mask=mask)
            if distance is None:
                continue

            cx = (box[0] + box[2]) / 2.0
            targets.append(
                Target(
                    label=det.names[cls_ids[i]],
                    distance_m=distance,
                    angle_deg=horizontal_angle_deg(cx, width, hfov_deg),
                    confidence=float(confs[i]),
                    timestamp=timestamp,
                )
            )

        targets.sort(key=lambda t: t.distance_m)
        return targets


def pick_target(
    targets: Sequence[Target],
    wanted: Optional[str] = None,
    max_angle_deg: float = 25.0,
) -> Optional[Target]:
    """
    Choose the one target worth talking about.

    Prefers things roughly ahead of the user, because that is what they are
    walking into. Only falls back to off-axis detections when nothing is ahead.
    """
    pool = list(targets)
    if wanted:
        w = wanted.lower()
        pool = [t for t in pool if w in t.label.lower()]
    if not pool:
        return None

    ahead = [t for t in pool if abs(t.angle_deg) <= max_angle_deg]
    return min(ahead or pool, key=lambda t: t.distance_m)
