"""Measure decoded scene stability before comparing Samsung/DiveControl LOG clips.

Requires PyAV and NumPy. Code values are measured directly, without a display LUT.
This diagnoses temporal variation; it does not establish curve or scene equivalence.
"""
import argparse
import json
from pathlib import Path

import av
import numpy as np


def inspect(path, skip_seconds):
    samples, times = [], []
    with av.open(str(path)) as container:
        stream = container.streams.video[0]
        metadata = {"profile": stream.codec_context.profile, "fps": str(stream.average_rate)}
        for frame in container.decode(stream):
            if frame.time is None or frame.time < skip_seconds:
                continue
            if frame.format.name != "yuv420p10le":
                raise ValueError(f"Expected decoded 10-bit planar video, got {frame.format.name}")
            plane = frame.planes[0]
            y = np.frombuffer(plane, dtype="<u2").reshape(frame.height, plane.line_size // 2)[:, :frame.width]
            # Fixed spatial tiles let a later comparison exclude moving areas explicitly.
            tiles = np.array([[tile.mean() for tile in np.array_split(row, 8, axis=1)]
                              for row in np.array_split(y, 8, axis=0)])
            samples.append(tiles)
            times.append(frame.time)
            metadata.update(width=frame.width, height=frame.height, transfer=frame.color_trc,
                            color_space=frame.colorspace, color_range=frame.color_range)
    if len(samples) < 10:
        raise ValueError("Need at least ten frames after the settling interval")
    values = np.stack(samples)
    levels = values.mean(axis=(1, 2))
    return {"path": str(path.resolve()), "metadata": metadata, "frames": len(samples),
            "skip_seconds": skip_seconds, "times_seconds": times,
            "frame_mean_y10": levels.tolist(),
            "frame_mean_p95_minus_p05_y10": float(np.percentile(levels, 95) - np.percentile(levels, 5)),
            "tile_median_y10": np.median(values, axis=0).tolist(),
            "tile_temporal_p95_minus_p05_y10": (np.percentile(values, 95, axis=0) - np.percentile(values, 5, axis=0)).tolist(),
            "limitation": "Variation may be illumination, motion or auto exposure. Matched fixed settings and scene registration are required for curve comparison."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("clip", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--skip-seconds", type=float, default=1.0)
    args = parser.parse_args()
    args.output.write_text(json.dumps(inspect(args.clip, args.skip_seconds), indent=2), encoding="utf-8")
