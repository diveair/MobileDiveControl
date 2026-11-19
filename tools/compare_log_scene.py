"""Register temporal averages of two 10-bit scene clips without applying a grading LUT."""
import argparse
import json
from pathlib import Path
import av
import cv2
import numpy as np


def frames(path):
    collected = []
    with av.open(str(path)) as c:
        c.seek(1_000_000)
        next_time = 1.5
        for f in c.decode(video=0):
            if f.time is None or f.time < next_time:
                continue
            if f.time > 5.5:
                break
            if f.format.name != 'yuv420p10le':
                raise ValueError(f.format.name)
            planes = []
            for p in f.planes:
                a = np.frombuffer(p, '<u2').reshape(p.height, p.line_size // 2)[:, :p.width]
                planes.append(cv2.resize(a.astype(np.float32), (960, 540), interpolation=cv2.INTER_AREA))
            collected.append(np.stack(planes, axis=-1))
            next_time = f.time + .2
    if len(collected) < 8:
        raise ValueError('Too few settled frames')
    stack = np.stack(collected)
    return stack.mean(axis=0), stack.std(axis=0), len(stack)


def feature_image(y):
    return cv2.createCLAHE(2, (8, 8)).apply(np.clip(y / 4, 0, 255).astype(np.uint8))


def compare(reference, candidate, output):
    native, ns, nn = frames(reference)
    dive, ds, dn = frames(candidate)
    sift = cv2.SIFT_create(4000)
    nk, nd = sift.detectAndCompute(feature_image(native[:, :, 0]), None)
    dk, dd = sift.detectAndCompute(feature_image(dive[:, :, 0]), None)
    if nd is None or dd is None:
        raise ValueError('Insufficient scene features; capture a new stationary pair')
    pairs = cv2.BFMatcher().knnMatch(dd, nd, k=2)
    matches = [a for a, b in pairs if a.distance < .72 * b.distance]
    if len(matches) < 20:
        raise ValueError('Scene changed or too few matching features; comparison rejected')
    src = np.float32([dk[m.queryIdx].pt for m in matches])
    dst = np.float32([nk[m.trainIdx].pt for m in matches])
    matrix, inliers = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=2)
    if matrix is None or inliers.sum() < 20:
        raise ValueError('Insufficient scene registration')
    aligned = cv2.warpAffine(dive, matrix, (960, 540))
    valid = cv2.warpAffine(np.ones((540, 960), np.uint8), matrix, (960, 540)) > 0
    valid[:40] = False; valid[-40:] = False; valid[:, :40] = False; valid[:, -40:] = False
    # Avoid edges where lens geometry, focus, and registration affect the apparent level.
    smooth = cv2.GaussianBlur(native, (9, 9), 0)
    smooth_dive = cv2.GaussianBlur(aligned, (9, 9), 0)
    for source in (smooth, smooth_dive):
        y = source[:, :, 0]
        gradient = np.hypot(cv2.Sobel(y, cv2.CV_32F, 1, 0), cv2.Sobel(y, cv2.CV_32F, 0, 1))
        valid &= gradient < 12
    difference = smooth_dive - smooth
    direct_difference = cv2.GaussianBlur(dive, (9, 9), 0) - smooth
    x, y = smooth[:, :, 0][valid], smooth_dive[:, :, 0][valid]
    slope, offset = np.polyfit(x, y, 1)
    bins = []
    for low in range(64, 940, 40):
        mask = valid & (smooth[:, :, 0] >= low) & (smooth[:, :, 0] < low + 40)
        if mask.sum() > 200:
            bins.append({'reference_y10': float(smooth[:, :, 0][mask].mean()),
                         'candidate_y10': float(smooth_dive[:, :, 0][mask].mean()),
                         'pixels': int(mask.sum())})
    report = {'reference': str(reference), 'candidate': str(candidate), 'reference_frames': nn,
              'candidate_frames': dn, 'registration_inliers': int(inliers.sum()),
              'candidate_to_reference_affine': matrix.tolist(), 'valid_pixels': int(valid.sum()),
              'framing_scale': float(np.hypot(matrix[0, 0], matrix[1, 0])),
              'direct_mae_yuv10_without_registration': np.abs(direct_difference[valid]).mean(axis=0).tolist(),
              'mean_difference_yuv10': difference[valid].mean(axis=0).tolist(),
              'mae_yuv10': np.abs(difference[valid]).mean(axis=0).tolist(),
              'rmse_yuv10': np.sqrt((difference[valid] ** 2).mean(axis=0)).tolist(),
              'luma_regression_slope': float(slope), 'luma_regression_offset': float(offset),
              'reference_median_temporal_std_y10': float(np.median(ns[:, :, 0])),
              'candidate_median_temporal_std_y10': float(np.median(ds[:, :, 0])), 'luma_bins': bins,
              'limitations': 'Single scene, sequential captures, residual flicker, no calibrated reflectance chart. Registration and smoothing isolate broad tone differences, not sharpness equivalence.'}
    output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    def rgb(a):
        yy = (a[:, :, 0] - 64) / 876
        u, v = (a[:, :, 1] - 512) / 896, (a[:, :, 2] - 512) / 896
        return np.clip(np.stack((yy + 1.4746*v, yy - .164553*u - .571353*v, yy + 1.8814*u), axis=-1)*255, 0, 255).astype(np.uint8)
    cv2.imwrite(str(output.with_suffix('.png')), cv2.cvtColor(np.concatenate((rgb(native), rgb(aligned)), axis=1), cv2.COLOR_RGB2BGR))
    print(json.dumps({k: v for k, v in report.items() if k != 'luma_bins'}, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('reference', type=Path); p.add_argument('candidate', type=Path); p.add_argument('output', type=Path)
    a = p.parse_args()
    compare(a.reference, a.candidate, a.output)
