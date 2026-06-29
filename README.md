# Mixport Customs Vision Android

Android MVP for a pilot customs/warehouse inspection workflow at Mixport. The app is designed for a fixed-camera unloading station and keeps the first release offline-first so it can be tested on an emulator and then on a real Android device.

## What is in this repo

- CameraX preview and MP4 recording pipeline for the unloading session
- Pallet workflow state machine for `waiting -> loading -> sealed -> next pallet / container complete`
- Local SQLite evidence store for sessions, pallets, item summaries, and event logs
- Demo controls that simulate vision detections on an emulator before the production ML model is plugged in
- Server and SQL contract docs for syncing to the same company server/database stack later

## Why it is structured this way

The current Mixport web stack is PHP + MySQL on the company server. The Android app should not connect to that database directly. Instead:

- the app captures video, counts, and event logs locally
- a future PHP API on the same Mixport server receives the structured session data
- the shared MySQL database stores synced session and pallet records for auditing

That keeps the pilot safe and gives us a path to resell the service to other companies later by swapping config and branding, not rewriting the core workflow.

## Local run

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run `app` on an emulator or device with a camera.
4. Start a session, then use the demo controls to simulate pallet detection, item counting, wrap completion, and container empty detection.
5. Use the record button to save an MP4 evidence clip to `Movies/MixportCustoms`.

## Local validation

```powershell
.\gradlew test
.\gradlew assembleDebug
```

## Dataset intake for future pallet / cargo / container-scene training

When you are ready to drop real pallet images, cargo close-ups, and container-interior scene images on this machine, use the intake scaffold first:

```powershell
C:\Users\zyzmc\AppData\Local\Programs\Python\Python313\python.exe .\tools\dataset_intake.py --dataset-root .\training-data --init
```

Then place images and annotations into `training-data/raw/...` and run:

```powershell
C:\Users\zyzmc\AppData\Local\Programs\Python\Python313\python.exe .\tools\dataset_intake.py --dataset-root .\training-data
```

That produces:

- `training-data/manifests/inspection_dataset_manifest.json`
- `training-data/manifests/inspection_tuning_profile.generated.json`
- `training-data/reports/inspection_dataset_summary.md`

See [docs/dataset-intake.md](docs/dataset-intake.md) for the exact folder contract.

## Mobile transformer path

This repo is now tuned for a phone-safe two-stage vision path:

- stage 1: low-cost live proposal tracking on the full camera frame
- stage 2: only stable cropped targets are sent into richer OCR/label logic and a future quantized transformer classifier

The default transformer target is a lightweight `MobileViTv2`-style crop classifier, not a heavy full-frame detector. That keeps Android latency and thermal load in range before custom training data exists.

See [docs/mobile-transformer-plan.md](docs/mobile-transformer-plan.md) for the exact runtime budgets and rollout path.

## Current MVP boundaries

- The live mobile path is runtime-optimized, but there is still no custom trained pallet/cargo transformer model in the repo yet.
- Session data is stored locally first.
- Sync to the company server is documented but not yet wired to a production API.
- No secrets are stored in the repo.

## Next build steps

1. Train and export a quantized crop classifier from the incoming pallet/cargo dataset.
2. Keep live proposals cheap and only escalate stable tracks into the transformer stage.
3. Add wrap-detection confirmation rules based on consecutive frames.
4. Build the PHP sync endpoints on the Mixport server.
5. Add authenticated upload of session summaries and video references.
6. Add a review screen for disputed counts.

## Project map

- `app/`: Android app source
- `docs/api-contract.md`: proposed pilot API contract for the Mixport server
- `docs/sql/pilot_schema.sql`: proposed MySQL tables on the shared company database

