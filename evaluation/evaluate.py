#!/usr/bin/env python3
"""Evaluate restored images on GimbalBlur.

Restored images mirror the dataset layout: the restoration of
<root>/<device>/scene_xxx/blur_jpg/blur_{k}_{iso}_{ss}.png is stored at
<restored>/<device>/scene_xxx/blur_jpg/blur_{k}_{iso}_{ss}.png (blur_dev for D).

Default: plain PSNR / SSIM (and LPIPS with --lpips) on the pre-aligned
GimbalBlur pairs. --ecc: RealBlur protocol (Rim et al., ECCV 2020),
which aligns each restored image to its GT with ECC and computes masked
PSNR / SSIM; use it for unaligned full-frame pairs.

  python evaluate.py --root GimbalBlur --restored results/ --pairs ../splits/GimbalBlur/GimbalBlur_J_test_list.txt
"""
import argparse
import csv
import warnings
from collections import defaultdict
from concurrent.futures import ProcessPoolExecutor
from functools import partial
from multiprocessing import get_context
from pathlib import Path

import cv2
import numpy as np
from skimage.metrics import structural_similarity

SUBDIRS = {"J": ("gt_jpg", "blur_jpg"), "D": ("gt_dev", "blur_dev")}


def read_rgb(path):
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    return cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0


def ecc_align(restored, gt):
    """Align the restored image to the GT exactly as RealBlur's evaluation code does."""
    restored = (np.sum(gt * restored) / np.sum(restored * restored)) * restored  # intensity matching
    warp = np.eye(3, 3, dtype=np.float32)
    criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 100, 0)
    try:
        _, warp = cv2.findTransformECC(cv2.cvtColor(gt, cv2.COLOR_RGB2GRAY), cv2.cvtColor(restored, cv2.COLOR_RGB2GRAY),
                                       warp, cv2.MOTION_HOMOGRAPHY, criteria, None, 5)
        converged = True
    except cv2.error:  # RealBlur's script stops here; we fall back to the identity warp
        warp, converged = np.eye(3, 3, dtype=np.float32), False
    size = (gt.shape[1], gt.shape[0])
    aligned = cv2.warpPerspective(restored, warp, size, flags=cv2.INTER_CUBIC + cv2.WARP_INVERSE_MAP,
                                  borderMode=cv2.BORDER_REFLECT)
    mask = cv2.warpPerspective(np.ones_like(restored), warp, size, flags=cv2.INTER_NEAREST + cv2.WARP_INVERSE_MAP,
                               borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return aligned * mask, gt * mask, mask, converged


def masked_psnr(gt, restored, mask):
    mse = np.sum((gt - restored) ** 2, dtype=np.float64) / np.sum(mask)
    return 10 * np.log10(1.0 / mse)


def masked_ssim(gt, restored, mask):
    # Gaussian SSIM (sigma 1.5, 11x11 window) per channel, averaged over the valid pixels
    # after dropping the 5 px border, then averaged over RGB. With an all-ones mask this
    # equals skimage's mean SSIM.
    _, ssim_map = structural_similarity(gt.astype(np.float64), restored.astype(np.float64), channel_axis=2,
                                        gaussian_weights=True, use_sample_covariance=False, data_range=1.0, full=True)
    pad = 5
    ssim_map = (ssim_map * mask)[pad:-pad, pad:-pad]
    mask = mask[pad:-pad, pad:-pad]
    return float(np.mean(ssim_map.sum(axis=0).sum(axis=0) / mask.sum(axis=0).sum(axis=0)))


_lpips_net = None


def lpips_distance(restored, gt):
    """LPIPS (AlexNet) on [-1, 1] inputs; the network is built once per worker process."""
    global _lpips_net
    import lpips
    import torch

    if _lpips_net is None:
        warnings.filterwarnings("ignore", module="torchvision")
        _lpips_net = lpips.LPIPS(net="alex", verbose=False).eval()
        _lpips_net.to("cuda" if torch.cuda.is_available() else "cpu")
    device = next(_lpips_net.parameters()).device
    with torch.no_grad():
        x, y = (torch.from_numpy(im).permute(2, 0, 1)[None].to(device) * 2 - 1 for im in (restored, gt))
        return _lpips_net(x, y).item()


def evaluate_pair(gt_path, restored_path, ecc, use_lpips):
    gt, restored = read_rgb(gt_path), read_rgb(restored_path)
    if gt.shape != restored.shape:
        raise ValueError(f"size mismatch: {restored_path} {restored.shape[:2]} vs GT {gt.shape[:2]}")
    result = {}
    if ecc:
        restored, gt, mask, result["ecc_converged"] = ecc_align(restored, gt)
    else:
        mask = np.ones_like(gt)
    result["psnr"] = masked_psnr(gt, restored, mask)
    result["ssim"] = masked_ssim(gt, restored, mask)
    if use_lpips:
        result["lpips"] = lpips_distance(restored, gt)
    return result


def read_pairs(args):
    """Return (gt, blur) paths relative to --root, from --pairs or from the restored tree."""
    gt_sub, blur_sub = SUBDIRS[args.modality]
    if args.pairs:
        lines = args.pairs.read_text().splitlines()
        pairs = [line.split()[:2] for line in lines if line.strip() and not line.startswith("#")]
    else:  # GimbalBlur naming: blur_{k}_{iso}_{ss} <-> gt_{k}_{iso}_{ss}
        pairs = []
        for p in sorted(args.restored.glob(f"*/scene_*/{blur_sub}/blur_*")):
            rel = p.relative_to(args.restored)
            pairs.append(((rel.parent.parent / gt_sub / f"gt{p.stem[4:]}.png").as_posix(), rel.as_posix()))
    for gt, blur in pairs:
        if Path(gt).parts[2] != gt_sub or Path(blur).parts[2] != blur_sub:
            raise SystemExit(f"pair {gt} {blur} does not belong to modality {args.modality}")
    if not pairs:
        raise SystemExit("no pairs found")
    return pairs


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, required=True, help="dataset root (contains <device>/scene_xxx/...)")
    ap.add_argument("--restored", type=Path, required=True, help="restored images, mirroring the dataset layout")
    ap.add_argument("--pairs", type=Path, help="pair list '<gt> <blur>' relative to --root (e.g. the test split)")
    ap.add_argument("--modality", choices=SUBDIRS, default="J", help="J: native JPEG, D: developed DNG")
    ap.add_argument("--ecc", action="store_true", help="RealBlur protocol: ECC-align to GT, masked PSNR/SSIM")
    ap.add_argument("--lpips", action="store_true", help="also compute LPIPS (AlexNet)")
    ap.add_argument("--csv", type=Path, help="write per-image results to this CSV file")
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    pairs = read_pairs(args)
    gt_paths, restored_paths = [], []
    for gt_rel, blur_rel in pairs:
        restored_path = args.restored / blur_rel
        if not restored_path.is_file():  # allow PNG outputs for JPEG inputs
            restored_path = restored_path.with_suffix(".png")
        if not restored_path.is_file():
            raise SystemExit(f"missing restored image for {blur_rel}")
        gt_paths.append(args.root / gt_rel)
        restored_paths.append(restored_path)

    metrics = ["psnr", "ssim"] + (["lpips"] if args.lpips else [])
    rows = []
    with ProcessPoolExecutor(args.workers, mp_context=get_context("spawn")) as pool:
        results = pool.map(partial(evaluate_pair, ecc=args.ecc, use_lpips=args.lpips), gt_paths, restored_paths)
        for (gt_rel, blur_rel), result in zip(pairs, results):
            rows.append({"device": Path(blur_rel).parts[0], "gt": gt_rel, "restored": blur_rel, **result})
            line = "  ".join([blur_rel] + [f"{m.upper()} {result[m]:.4f}" for m in metrics])
            print(line if result.get("ecc_converged", True) else line + "  (ECC failed, identity warp)")

    groups = defaultdict(list)
    for row in rows:
        groups[row["device"]].append(row)
    print(f"\n{'device':<16}{'n':>6}" + "".join(f"{m.upper():>9}" for m in metrics))
    for name, group in sorted(groups.items()) + [("all", rows)]:
        print(f"{name:<16}{len(group):>6}" + "".join(f"{np.mean([r[m] for r in group]):>9.4f}" for m in metrics))
    if args.ecc:
        print(f"ECC did not converge for {sum(not r['ecc_converged'] for r in rows)} pairs (identity warp used)")

    if args.csv:
        with open(args.csv, "w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)


if __name__ == "__main__":
    main()
