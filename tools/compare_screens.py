#!/usr/bin/env python3
"""Compare original same-device screenshots without shifting/scaling either image.

Usage: python3 tools/compare_screens.py official.png candidate.png output-prefix
System bar masks affect metrics only; overlays retain the entire original frame.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageEnhance


def compare(reference, candidate, prefix, top=108, bottom=0):
    a = Image.open(reference).convert('RGB')
    b = Image.open(candidate).convert('RGB')
    if a.size != b.size:
        raise ValueError(f'Original dimensions differ: {a.size} != {b.size}; do not resize to hide layout errors')
    prefix = Path(prefix)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    Image.blend(a, b, 0.5).save(str(prefix) + '-overlay.png')
    difference = ImageChops.difference(a, b)
    ImageEnhance.Contrast(difference).enhance(4).save(str(prefix) + '-diff-x4.png')
    aa, bb = np.asarray(a).astype(np.int16), np.asarray(b).astype(np.int16)
    mask = np.ones(aa.shape[:2], dtype=bool)
    mask[:top] = False
    if bottom:
        mask[-bottom:] = False
    delta = abs(aa - bb)
    changed = delta.max(axis=2) > 16
    # This is NOT a similarity score: blank backgrounds dominate whole-frame metrics.
    foreground = ((aa.min(axis=2) < 220) | (bb.min(axis=2) < 220)) & mask
    metrics = {
        'reference': str(reference), 'candidate': str(candidate), 'size': a.size,
        'excluded_system_top_px': top, 'excluded_system_bottom_px': bottom,
        'raw_mae_rgb': float(delta[mask].mean()),
        'pixels_over_16_rgb': int((changed & mask).sum()),
        'foreground_pixels': int(foreground.sum()),
        'foreground_changed_fraction': float(changed[foreground].mean()) if foreground.any() else 0,
        'note': 'No registration, scaling, translation or UI masking. Text/state differences remain visible. JPEG reference compression is not exact-pixel evidence.',
    }
    Path(str(prefix) + '-metrics.json').write_text(json.dumps(metrics, indent=2, ensure_ascii=False))
    return metrics


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('reference')
    p.add_argument('candidate')
    p.add_argument('prefix')
    p.add_argument('--system-top', type=int, default=108)
    p.add_argument('--system-bottom', type=int, default=0)
    args = p.parse_args()
    print(json.dumps(compare(args.reference, args.candidate, args.prefix, args.system_top, args.system_bottom), indent=2, ensure_ascii=False))
