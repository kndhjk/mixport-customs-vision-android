from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
DEFAULT_PROFILE_ASSET = Path("app/src/main/assets/inspection_tuning_profile.json")


@dataclass(frozen=True)
class DatasetPaths:
    root: Path
    raw_pallet_images: Path
    raw_cargo_images: Path
    raw_container_scene_images: Path
    pallet_annotations: Path
    cargo_annotations: Path
    container_scene_annotations: Path
    manifests_dir: Path
    reports_dir: Path
    templates_dir: Path


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare Mixport pallet/cargo image folders for local calibration and training.",
    )
    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=Path("training-data"),
        help="Workspace root that will hold raw images, manifests, reports, and templates.",
    )
    parser.add_argument(
        "--init",
        action="store_true",
        help="Create the expected directory structure and CSV templates before validation.",
    )
    parser.add_argument(
        "--fail-on-missing-annotations",
        action="store_true",
        help="Exit non-zero when pallet metadata or cargo labels are missing.",
    )
    args = parser.parse_args()

    paths = build_paths(args.dataset_root)
    if args.init:
        initialize_workspace(paths)

    manifest = build_manifest(paths)
    write_outputs(paths, manifest)

    summary = manifest["summary"]
    missing_total = (
        summary["missingPalletAnnotations"]
        + summary["missingCargoLabels"]
        + summary["missingCargoAnnotations"]
        + summary["missingContainerSceneAnnotations"]
    )
    if args.fail_on_missing_annotations and missing_total > 0:
        return 2
    return 0


def build_paths(root: Path) -> DatasetPaths:
    return DatasetPaths(
        root=root,
        raw_pallet_images=root / "raw" / "pallets" / "images",
        raw_cargo_images=root / "raw" / "cargo" / "images",
        raw_container_scene_images=root / "raw" / "container-scenes" / "images",
        pallet_annotations=root / "raw" / "pallets" / "annotations.csv",
        cargo_annotations=root / "raw" / "cargo" / "annotations.csv",
        container_scene_annotations=root / "raw" / "container-scenes" / "annotations.csv",
        manifests_dir=root / "manifests",
        reports_dir=root / "reports",
        templates_dir=root / "templates",
    )


def initialize_workspace(paths: DatasetPaths) -> None:
    for directory in (
        paths.raw_pallet_images,
        paths.raw_cargo_images,
        paths.raw_container_scene_images,
        paths.manifests_dir,
        paths.reports_dir,
        paths.templates_dir,
    ):
        directory.mkdir(parents=True, exist_ok=True)

    write_if_missing(
        paths.templates_dir / "pallet_annotations.template.csv",
        "\n".join(
            [
                "image,length_mm,width_mm,height_mm,angle_deg,view,deck_board_count,entry_points,notes",
                "pallet-001.jpg,1200,1000,144,18,front,7,4,Reference pallet from pilot site",
            ],
        )
        + "\n",
    )
    write_if_missing(
        paths.templates_dir / "cargo_annotations.template.csv",
        "\n".join(
            [
                "image,label,color,marker_text,width_mm,length_mm,height_mm,notes",
                "kettle-001.jpg,Electric kettle,White,KT-01,210,210,260,Kitchen appliance sample",
            ],
        )
        + "\n",
    )
    write_if_missing(
        paths.templates_dir / "container_scene_annotations.template.csv",
        "\n".join(
            [
                "image,container_id,pallet_present,pallet_count,estimated_visible_items,wrap_stage,notes",
                "scene-001.jpg,MSCU1234567,true,1,24,loading,Wide shot of the first pallet build inside container",
            ],
        )
        + "\n",
    )
    write_if_missing(
        paths.root / "README.txt",
        "\n".join(
            [
                "Mixport dataset intake workspace",
                "",
                "1. Put pallet images in raw/pallets/images/",
                "2. Put cargo images in raw/cargo/images/<label>/... or flat files plus annotations.csv",
                "3. Put container scene images in raw/container-scenes/images/",
                "4. Fill raw/pallets/annotations.csv, raw/cargo/annotations.csv, and raw/container-scenes/annotations.csv when available",
                "5. Run: python tools/dataset_intake.py --dataset-root training-data --init",
            ],
        )
        + "\n",
    )


def build_manifest(paths: DatasetPaths) -> dict[str, Any]:
    pallet_annotations = load_annotation_table(paths.pallet_annotations)
    cargo_annotations = load_annotation_table(paths.cargo_annotations)
    container_scene_annotations = load_annotation_table(paths.container_scene_annotations)
    pallet_images = collect_images(paths.raw_pallet_images)
    cargo_images = collect_images(paths.raw_cargo_images)
    container_scene_images = collect_images(paths.raw_container_scene_images)

    duplicate_basenames = find_duplicate_basenames(pallet_images + cargo_images + container_scene_images)
    cargo_label_counts: Counter[str] = Counter()
    missing_pallet_annotations = 0
    missing_cargo_labels = 0
    missing_cargo_annotations = 0
    missing_container_scene_annotations = 0
    issues: list[str] = []

    pallets: list[dict[str, Any]] = []
    for image_path in pallet_images:
        relative_path = image_path.relative_to(paths.root).as_posix()
        annotation = find_annotation(pallet_annotations, image_path, relative_path)
        if annotation is None:
            missing_pallet_annotations += 1
            issues.append(f"Missing pallet annotation for {relative_path}")
        pallets.append(
            {
                "image": image_path.name,
                "relativePath": relative_path,
                "annotation": annotation,
                "hasAnnotation": annotation is not None,
            },
        )

    cargo: list[dict[str, Any]] = []
    for image_path in cargo_images:
        relative_path = image_path.relative_to(paths.root).as_posix()
        annotation = find_annotation(cargo_annotations, image_path, relative_path)
        inferred_label = infer_cargo_label(paths.raw_cargo_images, image_path)
        final_label = (annotation or {}).get("label") or inferred_label
        if annotation is None:
            missing_cargo_annotations += 1
            issues.append(f"Missing cargo annotation for {relative_path}")
        if not final_label:
            missing_cargo_labels += 1
            issues.append(f"Missing cargo label for {relative_path}")
        else:
            cargo_label_counts[final_label] += 1
        cargo.append(
            {
                "image": image_path.name,
                "relativePath": relative_path,
                "annotation": annotation,
                "inferredLabel": inferred_label,
                "finalLabel": final_label,
                "hasAnnotation": annotation is not None,
            },
        )

    container_scenes: list[dict[str, Any]] = []
    for image_path in container_scene_images:
        relative_path = image_path.relative_to(paths.root).as_posix()
        annotation = find_annotation(container_scene_annotations, image_path, relative_path)
        if annotation is None:
            missing_container_scene_annotations += 1
            issues.append(f"Missing container-scene annotation for {relative_path}")
        container_scenes.append(
            {
                "image": image_path.name,
                "relativePath": relative_path,
                "annotation": annotation,
                "hasAnnotation": annotation is not None,
            },
        )

    dataset_id = paths.root.name
    manifest = {
        "metadata": {
            "datasetId": dataset_id,
            "generatedAt": iso_now(),
            "datasetRoot": str(paths.root.resolve()),
            "palletImageCount": len(pallets),
            "cargoImageCount": len(cargo),
            "containerSceneImageCount": len(container_scenes),
        },
        "pallets": pallets,
        "cargo": cargo,
        "containerScenes": container_scenes,
        "summary": {
            "missingPalletAnnotations": missing_pallet_annotations,
            "missingCargoAnnotations": missing_cargo_annotations,
            "missingCargoLabels": missing_cargo_labels,
            "missingContainerSceneAnnotations": missing_container_scene_annotations,
            "duplicateBasenames": sorted(duplicate_basenames),
            "cargoLabelCounts": dict(sorted(cargo_label_counts.items())),
            "issues": issues,
        },
    }

    tuning_profile = load_default_tuning_profile(dataset_id, manifest["metadata"]["generatedAt"])
    manifest["generatedTuningProfile"] = tuning_profile
    return manifest


def write_outputs(paths: DatasetPaths, manifest: dict[str, Any]) -> None:
    paths.manifests_dir.mkdir(parents=True, exist_ok=True)
    paths.reports_dir.mkdir(parents=True, exist_ok=True)

    manifest_path = paths.manifests_dir / "inspection_dataset_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    tuning_path = paths.manifests_dir / "inspection_tuning_profile.generated.json"
    tuning_path.write_text(
        json.dumps(manifest["generatedTuningProfile"], indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    report_path = paths.reports_dir / "inspection_dataset_summary.md"
    report_path.write_text(render_report(manifest, manifest_path, tuning_path), encoding="utf-8")


def load_annotation_table(path: Path) -> dict[str, dict[str, str]]:
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        records: dict[str, dict[str, str]] = {}
        for row in reader:
            image_key = normalize_annotation_key(row.get("image", ""))
            if not image_key:
                continue
            cleaned = {
                key: value.strip()
                for key, value in row.items()
                if key and value is not None and value.strip()
            }
            records[image_key] = cleaned
        return records


def collect_images(root: Path) -> list[Path]:
    if not root.exists():
        return []
    return sorted(
        path
        for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    )


def find_annotation(
    annotations: dict[str, dict[str, str]],
    image_path: Path,
    relative_path: str,
) -> dict[str, str] | None:
    keys = [
        normalize_annotation_key(relative_path),
        normalize_annotation_key(image_path.name),
    ]
    for key in keys:
        if key in annotations:
            return annotations[key]
    return None


def infer_cargo_label(cargo_root: Path, image_path: Path) -> str:
    try:
        relative_parts = image_path.relative_to(cargo_root).parts
    except ValueError:
        return ""
    if len(relative_parts) >= 2:
        return relative_parts[0].strip()
    return ""


def find_duplicate_basenames(images: list[Path]) -> set[str]:
    counts = Counter(path.name for path in images)
    return {name for name, count in counts.items() if count > 1}


def load_default_tuning_profile(
    dataset_id: str,
    generated_at: str,
) -> dict[str, Any]:
    if DEFAULT_PROFILE_ASSET.exists():
        profile = json.loads(DEFAULT_PROFILE_ASSET.read_text(encoding="utf-8"))
    else:
        profile = {
            "metadata": {},
            "tracking": {},
            "cargoLabeling": {},
            "palletReference": {},
        }
    profile.setdefault("metadata", {})
    profile["metadata"]["datasetId"] = dataset_id
    profile["metadata"]["generatedAt"] = generated_at
    profile["metadata"]["notes"] = (
        "Generated by tools/dataset_intake.py. Replace threshold values after reviewing "
        "the new pallet/cargo dataset statistics."
    )
    return profile


def render_report(
    manifest: dict[str, Any],
    manifest_path: Path,
    tuning_path: Path,
) -> str:
    summary = manifest["summary"]
    metadata = manifest["metadata"]
    lines = [
        "# Inspection Dataset Summary",
        "",
        f"- Dataset root: `{metadata['datasetRoot']}`",
        f"- Generated at: `{metadata['generatedAt']}`",
        f"- Pallet images: `{metadata['palletImageCount']}`",
        f"- Cargo images: `{metadata['cargoImageCount']}`",
        f"- Container scene images: `{metadata['containerSceneImageCount']}`",
        f"- Missing pallet annotations: `{summary['missingPalletAnnotations']}`",
        f"- Missing cargo annotations: `{summary['missingCargoAnnotations']}`",
        f"- Missing cargo labels: `{summary['missingCargoLabels']}`",
        f"- Missing container scene annotations: `{summary['missingContainerSceneAnnotations']}`",
        f"- Manifest: `{manifest_path.as_posix()}`",
        f"- Tuning profile template: `{tuning_path.as_posix()}`",
        "",
        "## Cargo label counts",
    ]

    cargo_counts = summary["cargoLabelCounts"]
    if cargo_counts:
        for label, count in cargo_counts.items():
            lines.append(f"- `{label}`: `{count}`")
    else:
        lines.append("- No cargo labels were resolved yet.")

    lines.extend(["", "## Duplicate basenames"])
    duplicates = summary["duplicateBasenames"]
    if duplicates:
        for name in duplicates:
            lines.append(f"- `{name}`")
    else:
        lines.append("- None")

    lines.extend(["", "## Issues"])
    if summary["issues"]:
        for issue in summary["issues"]:
            lines.append(f"- {issue}")
    else:
        lines.append("- No missing metadata detected.")

    lines.append("")
    return "\n".join(lines)


def write_if_missing(path: Path, content: str) -> None:
    if not path.exists():
        path.write_text(content, encoding="utf-8")


def normalize_annotation_key(value: str) -> str:
    return value.replace("\\", "/").strip().lower()


def iso_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


if __name__ == "__main__":
    raise SystemExit(main())
