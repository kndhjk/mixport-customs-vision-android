# Pilot API Contract

This document describes the server contract for syncing the Android pilot app into the same Mixport company environment that already hosts the cargo dashboard.

## Base URL

`https://private-deployment.example/api/`

## Auth

For the pilot, use server-issued bearer tokens tied to a authorized ops account. Do not ship database credentials in the Android app.

The current private PHP implementation also allows an authenticated authorized ops web session for browser-side testing. Bearer tokens can be supplied from either:

- the server environment variable `CUSTOMS_SYNC_BEARER_TOKEN`
- the private company setting row `private deployment setting row`

## Endpoints

### `POST /barcode/verify`

Resolve a scanned HBL or child HBL before creating a live unloading session.

Request:

```json
{
  "barcode": "VAN1413050612"
}
```

Response:

```json
{
  "ok": true,
  "found": true,
  "data": {
    "id": 1284,
    "parent_hbl_no": "VAN2026061615",
    "matched_child_hbl": "VAN1413050612",
    "child_hbls": "VAN1413050612\nVAN1413050054",
    "matched_by": "child_hbl",
    "status": "available",
    "container_no": "MSCU1234567",
    "vessel_name": "Tauranga Express",
    "company": "Mixport Pilot",
    "location": "A-12"
  }
}
```

### `POST /sessions`

Create a session header when unloading begins.

Request:

```json
{
  "containerCode": "MSCU1234567",
  "vesselName": "Tauranga Express",
  "operatorName": "Shift A",
  "notes": "Pilot run",
  "startedAt": "2026-06-24T10:35:22Z",
  "deviceId": "android-pilot-01"
}
```

Response:

```json
{
  "sessionId": 481,
  "status": "ACTIVE"
}
```

### `POST /sessions/{sessionId}/pallets`

Create or update a pallet aggregate when items are detected.

Request:

```json
{
  "sequenceNumber": 2,
  "status": "LOADING",
  "wrapDetected": false,
  "items": [
    {
      "itemLabel": "Wrapped export box",
      "colorName": "Blue",
      "markerText": "NZCS",
      "quantity": 14
    }
  ],
  "observedAt": "2026-06-24T10:40:03Z"
}
```

### `POST /sessions/{sessionId}/events`

Store event logs for audit and debugging.

### `POST /sessions/{sessionId}/close`

Close the unloading session.

Request:

```json
{
  "status": "COMPLETED",
  "endedAt": "2026-06-24T11:12:54Z",
  "containerHasRemainingCargo": false,
  "recordingUri": "content://media/external/video/media/12345"
}
```

### `POST /sessions/{sessionId}/video-upload-ticket`

Optional future endpoint returning a pre-signed upload target for long videos if the pilot moves off direct MediaStore-only handling.

## Recommended PHP placement

- `private-sync/index.php`
- `private-sync/.htaccess`
- shared auth bootstrap from the existing Mixport PHP stack
- MySQL access through the same shared DB connection layer pattern used by `shared/db-connection.php`

## Recommended server-side validations

- only authenticated staff accounts can create or close sessions
- pallet sequence numbers must be unique within a session
- event timestamps cannot predate the parent session start time
- closed sessions cannot accept more pallet updates
- video references should be immutable once a session is completed

