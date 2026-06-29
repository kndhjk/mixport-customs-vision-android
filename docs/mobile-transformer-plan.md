# Mobile Transformer Plan

## Goal

Keep the Android path fully runnable on a real phone before custom training data exists, while still leaving a clean upgrade path to a transformer-based classifier later.

## Why not a heavy full-frame transformer detector first

For this project, the phone has to do all of this at once:

- live camera preview
- full-session video recording
- green-box tracking overlay
- OCR on packaging text
- counting logic
- pallet-state logic

A heavy full-frame transformer detector would push latency, heat, and battery too hard on mid-range phones. So the safer architecture is:

1. Run a cheap full-frame proposal detector continuously.
2. Track stable objects across frames.
3. Only crop those stable targets.
4. Run the richer classifier on the crops, not on the whole frame.

## Current runtime optimization now in code

The app now includes mobile runtime tuning for:

- `mobileRuntime.liveAnalysisMinIntervalMs`
- `mobileRuntime.maxSnapshotDetectionsPerPass`
- `mobileRuntime.recognitionDownsampleMaxEdgePx`
- `mobileRuntime.minRecognitionCropEdgePx`

And a future transformer block for:

- `transformer.preferredFamily`
- `transformer.modelInputSizePx`
- `transformer.quantized`
- `transformer.runOnStableTracksOnly`
- `transformer.preferNnapi`
- `transformer.cpuThreadCount`
- `transformer.maxTracksPerPass`
- `transformer.targetLatencyMs`

## Default recommendation

Before training data exists, use this model strategy:

- live proposals: keep the current low-cost stream detector
- crop classifier family: `MobileViTv2`
- quantization: `INT8`
- input size: `256 x 256`
- inference scope: stable cropped objects only
- per-pass crop limit: `2` to `4` depending on device tier

## Device tiers

The app now derives a phone tier from available RAM:

- `ENTRY`: tighter cadence, smaller crop budget, fewer targets per pass
- `MID`: default cadence and crop budget
- `HIGH`: faster cadence and one extra crop per pass

That tier feeds the live analysis interval and transformer crop budget automatically.

## What to train first when data arrives

Do not start by training one giant "recognize everything" detector.

Train in this order:

1. Crop classifier for high-frequency cargo categories
2. Pallet state classifier:
   - empty pallet
   - loading
   - near full
   - full
   - wrapping
   - wrapped
3. Container-scene classifier:
   - much cargo left
   - some cargo left
   - few cargo left
   - container empty

## Export target

When the first custom classifier is trained, export a mobile build with:

- quantized weights
- fixed input size
- stable category list
- top-k output only
- NNAPI preference enabled where supported

## Practical rule

If a model cannot run while recording video and keeping the preview responsive, it is too heavy for this app, even if accuracy looks better on a desktop GPU.
