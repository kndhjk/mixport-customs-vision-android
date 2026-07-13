# Private Sync Interface Summary

This document keeps only the public-facing contract shape for the Android scanner sync workflow.

Concrete deployment URLs, auth sources, dashboard integration details, and server file layout are intentionally omitted from this public repository.

## Base URL

`https://private-deployment.example/api/`

## Auth

Use deployment-specific server credentials that are provisioned outside the public repo. Do not ship database credentials in the Android app and do not expose private sync credentials in the worker-facing UI.

## Endpoints

### `POST /barcode/verify`

Resolve a scanned HBL or child HBL before creating a live unloading session.

Request:

```json
{
  "barcode": "VAN1413050612"
}
```

### `GET /scanner-sync/bootstrap`

Download the current parent-HBL and child-HBL scanner dataset for local offline lookup on the Hikrobot device.

Response:

```json
{
  "ok": true,
  "count": 2,
  "rows": [
    {
      "barcode_key": "VAN2026061615",
      "cargo_tracking_id": 1284,
      "parent_hbl_no": "VAN2026061615",
      "matched_child_hbl": "",
      "matched_by": "hbl_no",
      "child_hbls": "VAN1413050612\nVAN1413050054",
      "status": "available",
      "container_no": "MSCU1234567",
      "vessel_name": "Tauranga Express",
      "company": "Pilot Customer"
    },
    {
      "barcode_key": "VAN1413050612",
      "cargo_tracking_id": 1284,
      "parent_hbl_no": "VAN2026061615",
      "matched_child_hbl": "VAN1413050612",
      "matched_by": "child_hbl",
      "child_hbls": "VAN1413050612\nVAN1413050054",
      "status": "available",
      "container_no": "MSCU1234567",
      "vessel_name": "Tauranga Express",
      "company": "Pilot Customer"
    }
  ],
  "synced_at": "2026-07-12T09:14:33Z"
}
```

### `POST /scanner-sync/upload`

Upload a completed local scanner batch after staff decide the offline queue is ready to sync back into the private deployment environment.

Request:

```json
{
  "deviceId": "hik-7C91B2AA",
  "operatorName": "Shift A",
  "workflowMode": "TRIGGER_ONCE",
  "uploadedAt": "2026-07-12T09:18:02Z",
  "records": [
    {
      "localId": 41,
      "scannedBarcode": "VAN1413050612",
      "databaseRecord": "VAN2026061615",
      "matchStatus": "MATCHED",
      "status": "available",
      "source": "SERVER_CACHE",
      "scannedAt": "2026-07-12T09:17:31Z",
      "cargoTrackingId": 1284,
      "parentHblNo": "VAN2026061615",
      "matchedChildHbl": "VAN1413050612",
      "matchedBy": "child_hbl",
      "containerNo": "MSCU1234567",
      "vesselName": "Tauranga Express",
      "company": "Pilot Customer",
      "location": "A-12"
    }
  ]
}
```

Response:

```json
{
  "ok": true,
  "batch_id": 17,
  "batch_uuid": "5bf8f4d3-54e1-43fe-bdb0-149ebd885d5a",
  "uploaded_count": 1,
  "matched_count": 1,
  "mismatch_count": 0,
  "error_count": 0,
  "uploaded_at": "2026-07-12T09:18:03Z"
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
    "company": "Pilot Customer",
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

## Recommended server-side validations

- only authenticated staff accounts can create or close sessions
- pallet sequence numbers must be unique within a session
- event timestamps cannot predate the parent session start time
- closed sessions cannot accept more pallet updates
- video references should be immutable once a session is completed

