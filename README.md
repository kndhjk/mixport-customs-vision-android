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

## Current MVP boundaries

- The vision pipeline is scaffolded but still uses demo buttons instead of a trained detector/OCR model.
- Session data is stored locally first.
- Sync to the company server is documented but not yet wired to a production API.
- No secrets are stored in the repo.

## Next build steps

1. Plug a custom object detector and OCR stack into the CameraX analysis stream.
2. Add wrap-detection confirmation rules based on consecutive frames.
3. Build the PHP sync endpoints on the Mixport server.
4. Add authenticated upload of session summaries and video references.
5. Add a review screen for disputed counts.

## Project map

- `app/`: Android app source
- `docs/api-contract.md`: proposed pilot API contract for the Mixport server
- `docs/sql/pilot_schema.sql`: proposed MySQL tables on the shared company database


