# GimbalBlur: IMU-Paired Multi-Device Deblurring Benchmark in the Wild

Akio Muragishi and Masahiro Okuda, Doshisha University
WACV 2027

[Project page](https://akionu.github.io/GimbalBlur/) | Paper (coming soon) | Supplementary (coming soon)

GimbalBlur is a real-world smartphone deblurring dataset. Three gimbals replay recorded hand-shake trajectories while carrying three smartphones, so the blurred and sharp images of a pair come from the same sensor and ISP, and all three devices see the same scene under the same blur. Every capture comes with its IMU recording.

## Dataset

| Version | Content | Download |
| --- | --- | --- |
| GimbalBlur | Evaluation version. GT aligned to the blurred image, central 61% crop, long edge 1920, PNG, and IMU CSVs | [Hugging Face](https://huggingface.co/datasets/akiomu/GimbalBlur) |
| GimbalBlur-Fullres | Full-resolution JPEG, DNG and developed DNG PNG (Oppo, Xiaomi), and IMU CSVs. | [Hugging Face](https://huggingface.co/datasets/akiomu/GimbalBlur-Fullres) |

200 scenes, 17,499 JPEG pairs and 11,498 DNG(RAW) pairs. See `DATASHEET.md` for collection and composition details.

## Splits

Scene-level splits shared across devices: 141 train, 16 val, 43 test scenes (`splits/GimbalBlur_{train,val,test}_scenes.txt`). Pair lists are in RealBlur format (`<gt path> <blur path>`, relative to the dataset root): `splits/GimbalBlur/` for GimbalBlur and `splits/GimbalBlur-Fullres/` for GimbalBlur-Fullres. `train_full` is train + val.

| Modality | train | val | test |
| --- | ---: | ---: | ---: |
| J (JPEG, 3 phones) | 12,276 | 1,403 | 3,820 |
| D (developed DNG, Oppo and Xiaomi) | 8,035 | 947 | 2,516 |

## Evaluation

```bash
pip install -r evaluation/requirements.txt
python evaluation/evaluate.py --root GimbalBlur --restored results/ --pairs splits/GimbalBlur/GimbalBlur_J_test_list.txt --modality J --lpips
```

## Pretrained models

Models fine-tuned on the GimbalBlur J training split are on [Hugging Face](https://huggingface.co/datasets/akiomu/GimbalBlur/tree/main/trained_models):

- `NAFNet-width64-GimbalBlur-J-ft.pth` (BasicSR format, key `params`)
- `Restormer-GimbalBlur-J-ft.pth` (BasicSR format, key `params`)
- `GyroDeblurNet-GimbalBlur-J-ft.pt` (key `model_state_dict`)

## Repository

- `app/`: Android capture app (JPEG + DNG + IMU CSV, gimbal-triggered shutter).
- `hardware/`: gimbal CAD (STEP), control code, and the 100 replayed hand-shake trajectories.
- `calibration/`: checkerboard photos from each phone and the estimated intrinsics (`intrinsics.json`).
- `dataset/`: DNG development (`develop_raw.py`) and GT alignment + central crop (`align_crop.py`).
- `evaluation/`: PSNR / SSIM / LPIPS evaluation.
- `splits/`: official scene splits and pair lists.
- `docs/`: project page.

## Citation

```bibtex
@inproceedings{muragishi2027gimbalblur,
  author    = {Muragishi, Akio and Okuda, Masahiro},
  title     = {{GimbalBlur}: {IMU}-Paired Multi-Device Deblurring Benchmark in the Wild},
  booktitle = {Proceedings of the IEEE/CVF Winter Conference on Applications of Computer Vision (WACV)},
  year      = {2027},
}
```

## License

Code: BSD-3-Clause (`LICENSE`). Dataset: CC BY 4.0.
