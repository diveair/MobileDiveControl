"""Independently decode qualified LOG clips and check format, timestamps, and A/V endings.

Requires PyAV. This validates recording integrity, not full colorimetric equivalence.
"""
import argparse
import hashlib
import json
import statistics
from pathlib import Path

import av


def verify(path):
    with av.open(str(path)) as container:
        video = container.streams.video[0]
        assert video.codec_context.profile == 'Main 10', 'Not Main10'
        assert video.codec_context.color_primaries == 9, 'Not BT.2020 primaries'
        tracks = [dict(type=s.type, start_s=float((s.start_time or 0) * s.time_base),
                       duration_s=float(s.duration * s.time_base)) for s in container.streams]
        times = []
        for frame in container.decode(video=0):
            assert frame.format.name == 'yuv420p10le', 'Not decoded 10-bit YUV'
            assert (frame.colorspace, frame.color_trc, frame.color_range) == (9, 2, 1), 'Native BT.2020/unspecified/limited tags differ'
            assert frame.time is not None, 'Missing video timestamp'
            times.append(frame.time)
        assert len(times) >= 2, 'Too few video frames'
        gaps = [b - a for a, b in zip(times, times[1:])]
        assert min(gaps) > 0, 'Non-increasing timestamps'
        assert max(gaps) < .15, 'Video timestamp gap exceeds 150 ms'
        if video.frames:
            assert len(times) == video.frames, 'Not every indexed frame decoded'
        effective_fps = (len(times) - 1) / (times[-1] - times[0])
        if times[-1] - times[0] > 2:
            assert 29 <= effective_fps <= 31, 'Sustained cadence differs from 30 fps'
        end_skew = None
        audio = next((s for s in tracks if s['type'] == 'audio'), None)
        if audio:
            v = next(s for s in tracks if s['type'] == 'video')
            end_skew = abs(v['start_s'] + v['duration_s'] - audio['start_s'] - audio['duration_s'])
            assert end_skew < .08, 'Audio/video end skew exceeds 80 ms'
        with path.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        return dict(file=str(path), sha256=digest,
                    decoded_frames=len(times), width=video.width, height=video.height,
                    effective_fps=effective_fps, median_frame_interval_s=statistics.median(gaps),
                    largest_frame_gap_s=max(gaps), av_end_skew_s=end_skew, tracks=tracks,
                    profile='Main10', bit_depth=10, primaries_matrix='BT.2020',
                    transfer='unspecified (2)', range='limited', passed=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('clips', nargs='+', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    reports = []
    for clip in args.clips:
        try:
            reports.append(verify(clip))
        except Exception as error:
            reports.append(dict(file=str(clip), passed=False, error=str(error)))
    args.output.write_text(json.dumps(reports, indent=2), encoding='utf-8')
    print(json.dumps(dict(total=len(reports), passed=sum(r['passed'] for r in reports),
                         failures=[r for r in reports if not r['passed']]), indent=2))
    raise SystemExit(0 if all(r['passed'] for r in reports) else 1)
