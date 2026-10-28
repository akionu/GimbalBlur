#!/usr/bin/env python3
"""Build GimbalBlur pairs from the GimbalBlur-Fullres captures.

For each (GT, blur) pair:
  1. Estimate a GT->blur homography with kornia's ImageRegistrator on copies
     downscaled to a 768 px long edge. kornia homographies live in normalized
     [-1, 1] coordinates, so the same matrix applies at full resolution.
  2. If the warped GT leaves invalid pixels deeper than 20% of the image size
     inside the border, registration is treated as diverged and the GT is kept
     unwarped (identity fallback).
  3. Warp the full-resolution GT into the blurred frame (bicubic, reflection padding).
  4. Center-crop GT and blur to 61% per axis, resize to a 1920 px long edge, save PNG.

  # one pair
  python align_crop.py --gt gt_200_8.jpg --blur blur_3_200_8.jpg --out out/
  # a whole tree: <root>/<device>/scene_xxx/{gt_jpg,blur_jpg,gt_dev,blur_dev,imu}
  python align_crop.py --root GimbalBlur-Fullres --out GimbalBlur
"""
import argparse
import shutil
from pathlib import Path

import cv2
import kornia.geometry as KG
import numpy as np
import torch
import torch.nn.functional as F

REG_LONG_EDGE = 768
REG_ITERATIONS = 200
MAX_MARGIN = 0.20  # identity fallback if the valid region needs a larger margin per side
CROP_RATIO = 0.61
SUBDIRS = {"J": ("gt_jpg", "blur_jpg"), "D": ("gt_dev", "blur_dev")}
IMAGE_EXTS = (".jpg", ".jpeg", ".png")


def read_image(path, device):
    rgb = cv2.cvtColor(cv2.imread(str(path), cv2.IMREAD_COLOR), cv2.COLOR_BGR2RGB)
    return torch.from_numpy(rgb.astype(np.float32) / 255.0).permute(2, 0, 1)[None].to(device)


def write_image(img, path):
    rgb = (img[0].clamp(0, 1).permute(1, 2, 0).cpu().numpy() * 255.0 + 0.5).astype(np.uint8)
    path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(path), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))


def resize_long_edge(img, long_edge, **kwargs):
    h, w = img.shape[2:]
    scale = long_edge / max(h, w)
    return F.interpolate(img, size=(max(1, round(h * scale)), max(1, round(w * scale))), align_corners=False, **kwargs)


def sampling_grid(H, h, w):
    """grid_sample grid that maps each output pixel through H (normalized coordinates)."""
    ys, xs = torch.meshgrid(torch.linspace(-1, 1, h, device=H.device), torch.linspace(-1, 1, w, device=H.device),
                            indexing="ij")
    mapped = H[0] @ torch.stack([xs.flatten(), ys.flatten(), torch.ones_like(xs).flatten()])
    return (mapped[:2] / mapped[2:].clamp(min=1e-8)).reshape(2, h, w).permute(1, 2, 0)[None]


def required_margin(valid):
    """Smallest per-side margin m such that the central (1-2m) box contains no invalid pixel."""
    h, w = valid.shape[2:]
    invalid = valid[0, 0] < 0.5
    if not invalid.any():
        return 0.0
    ys = ((torch.arange(h, device=valid.device).float() + 0.5) / h - 0.5).abs()
    xs = ((torch.arange(w, device=valid.device).float() + 0.5) / w - 0.5).abs()
    dist = torch.maximum(ys[:, None], xs[None, :])  # Chebyshev distance from the image center
    return float((0.5 - dist)[invalid].max().clamp(min=0.0))


def estimate_homography(gt, blur):
    """GT->blur homography, or None if the registration diverged."""
    if max(gt.shape[2:]) > REG_LONG_EDGE:
        gt, blur = (resize_long_edge(x, REG_LONG_EDGE, mode="bilinear") for x in (gt, blur))
    scale = (blur * gt).sum() / (gt * gt).sum().clamp(min=1e-8)  # intensity matching
    registrator = KG.ImageRegistrator("homography", num_iterations=REG_ITERATIONS, tolerance=1e-10).to(gt.device)
    H = registrator.register((gt * scale).clamp(0, 1), blur).detach()
    h, w = gt.shape[2:]
    valid = F.grid_sample(torch.ones(1, 1, h, w, device=gt.device), sampling_grid(H, h, w), mode="nearest",
                          padding_mode="zeros", align_corners=True)
    return None if required_margin(valid) > MAX_MARGIN else H


def center_crop(img):
    h, w = img.shape[2:]
    ch, cw = round(h * CROP_RATIO), round(w * CROP_RATIO)
    top, left = (h - ch) // 2, (w - cw) // 2
    return img[:, :, top:top + ch, left:left + cw]


def align_crop(gt_path, blur_path, gt_out, blur_out, long_edge, device):
    gt, blur = read_image(gt_path, device), read_image(blur_path, device)
    if gt.shape != blur.shape:
        gt = F.interpolate(gt, size=blur.shape[2:], mode="bicubic", align_corners=False)
    H = estimate_homography(gt, blur)
    if H is None:
        print(f"identity fallback: {blur_path}")
    else:
        h, w = gt.shape[2:]
        with torch.no_grad():
            gt = F.grid_sample(gt, sampling_grid(H, h, w), mode="bicubic", padding_mode="reflection",
                               align_corners=True).clamp(0, 1)
    for img, path in ((gt, gt_out), (blur, blur_out)):
        img = center_crop(img)
        if long_edge and max(img.shape[2:]) != long_edge:
            img = resize_long_edge(img, long_edge, mode="bicubic", antialias=True)
        write_image(img, path)


def find_gt(gt_dir, iso, ss):
    for ext in IMAGE_EXTS:
        for p in (gt_dir / f"gt_{iso}_{ss}{ext}", gt_dir / f"gt_{iso}_{ss}{ext.upper()}"):
            if p.is_file():
                return p
    return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", type=Path, help="original dataset root (tree mode)")
    ap.add_argument("--gt", type=Path, help="GT image (single-pair mode)")
    ap.add_argument("--blur", type=Path, help="blurred image (single-pair mode)")
    ap.add_argument("--out", type=Path, required=True, help="output root (tree mode) or directory (single pair)")
    ap.add_argument("--modality", nargs="+", choices=SUBDIRS, default=["J", "D"])
    ap.add_argument("--long-edge", type=int, default=1920, help="output long edge; 0 keeps the cropped resolution")
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    args = ap.parse_args()

    if args.gt and args.blur:
        align_crop(args.gt, args.blur, args.out / f"{args.gt.stem}.png", args.out / f"{args.blur.stem}.png",
                   args.long_edge, args.device)
        return
    if not args.root:
        ap.error("give --root, or --gt and --blur")

    for modality in args.modality:
        gt_sub, blur_sub = SUBDIRS[modality]
        for blur_path in sorted(args.root.glob(f"*/scene_*/{blur_sub}/blur_*")):
            if blur_path.suffix.lower() not in IMAGE_EXTS:
                continue
            _, kernel, iso, ss = blur_path.stem.split("_")
            scene_dir = blur_path.parent.parent
            gt_path = find_gt(scene_dir / gt_sub, iso, ss)
            if gt_path is None:
                print(f"no GT for {blur_path}")
                continue
            out_scene = args.out / scene_dir.relative_to(args.root)
            name = f"{kernel}_{iso}_{ss}"
            align_crop(gt_path, blur_path, out_scene / gt_sub / f"gt_{name}.png",
                       out_scene / blur_sub / f"blur_{name}.png", args.long_edge, args.device)
            imu = scene_dir / "imu" / f"{blur_path.stem}.csv"
            if imu.is_file():
                (out_scene / "imu").mkdir(parents=True, exist_ok=True)
                shutil.copy2(imu, out_scene / "imu" / f"blur_{name}.csv")


if __name__ == "__main__":
    main()
