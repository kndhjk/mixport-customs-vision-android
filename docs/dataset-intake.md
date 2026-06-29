# Dataset Intake For Pallet, Cargo, And Container Scene Training

This repo now has a local intake pipeline for pallet images, cargo close-ups, and container-interior scene images you plan to drop onto this machine later.

## Target workspace

By default the intake tool expects a dataset root at:

`C:\Users\zyzmc\Documents\web\mixport-customs-vision-android\training-data`

You can use another path, but keeping everything under the repo makes iteration simpler.

## First-time scaffold

Run this once to create the expected folder structure and CSV templates:

```powershell
C:\Users\zyzmc\AppData\Local\Programs\Python\Python313\python.exe .\tools\dataset_intake.py --dataset-root .\training-data --init
```

That creates:

- `training-data/raw/pallets/images/`
- `training-data/raw/cargo/images/`
- `training-data/raw/container-scenes/images/`
- `training-data/templates/pallet_annotations.template.csv`
- `training-data/templates/cargo_annotations.template.csv`
- `training-data/templates/container_scene_annotations.template.csv`
- `training-data/manifests/`
- `training-data/reports/`

## Pallet image format

Put pallet images in:

`training-data/raw/pallets/images/`

Put pallet metadata in:

`training-data/raw/pallets/annotations.csv`

Recommended columns:

- `image`
- `length_mm`
- `width_mm`
- `height_mm`
- `angle_deg`
- `view`
- `deck_board_count`
- `entry_points`
- `notes`

Example:

```csv
image,length_mm,width_mm,height_mm,angle_deg,view,deck_board_count,entry_points,notes
pallet-001.jpg,1200,1000,144,18,front,7,4,Reference pallet from pilot site
```

## Cargo image format

You have two supported options.

Option 1: class-folder layout

- Put images in `training-data/raw/cargo/images/<label>/...`
- The first folder name under `images/` will be treated as the inferred cargo label

Option 2: flat images plus CSV metadata

- Put images in `training-data/raw/cargo/images/`
- Put metadata in `training-data/raw/cargo/annotations.csv`

Recommended cargo columns:

- `image`
- `label`
- `color`
- `marker_text`
- `width_mm`
- `length_mm`
- `height_mm`
- `notes`

Example:

```csv
image,label,color,marker_text,width_mm,length_mm,height_mm,notes
kettle-001.jpg,Electric kettle,White,KT-01,210,210,260,Kitchen appliance sample
```

## Container scene image format

Use this for wide shots from inside the container, especially frames that show pallet staging progress, wrap state, and remaining loose cargo.

Put scene images in:

`training-data/raw/container-scenes/images/`

Put scene metadata in:

`training-data/raw/container-scenes/annotations.csv`

Recommended container-scene columns:

- `image`
- `container_id`
- `pallet_present`
- `pallet_count`
- `estimated_visible_items`
- `wrap_stage`
- `notes`

Example:

```csv
image,container_id,pallet_present,pallet_count,estimated_visible_items,wrap_stage,notes
scene-001.jpg,MSCU1234567,true,1,24,loading,Wide shot of the first pallet build inside container
```

## Run validation and manifest generation

After you copy real images and annotations in:

```powershell
C:\Users\zyzmc\AppData\Local\Programs\Python\Python313\python.exe .\tools\dataset_intake.py --dataset-root .\training-data
```

Outputs:

- `training-data/manifests/inspection_dataset_manifest.json`
- `training-data/manifests/inspection_tuning_profile.generated.json`
- `training-data/reports/inspection_dataset_summary.md`

## Why this matters

The Android app now reads a structured tuning profile named:

`inspection_tuning_profile.json`

The generated profile file uses the same schema as the app's bundled profile:

- `tracking`
- `mobileRuntime`
- `cargoLabeling`
- `transformer`
- `palletReference`

So once we review your real dataset, we can adjust thresholds from evidence instead of guessing, then either:

- replace `app/src/main/assets/inspection_tuning_profile.json` and rebuild, or
- place an override file on-device later when we enable deployment-time tuning swaps

## Current limitations

- The intake tool validates folder structure and metadata completeness, but it does not train a custom detector yet.
- It generates a tuning-profile template, not a learned model.
- The fastest next step after you drop the dataset is: run intake, inspect the summary, then calibrate thresholds and label vocabulary against the real distribution.
