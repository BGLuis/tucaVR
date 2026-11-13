#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - <<'PY'
import os
import re
import subprocess
import sys


def parse_props(text: str, source: str) -> dict[str, int | str]:
    raw: dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        raw[k.strip()] = v.strip()

    required = ("major", "minor", "patch", "versionName", "versionCode")
    missing = [k for k in required if not raw.get(k)]
    if missing:
        raise ValueError(f"{source}: missing required keys: {', '.join(missing)}")

    for k in ("major", "minor", "patch", "versionCode"):
        if not re.fullmatch(r"[0-9]+", raw[k]):
            raise ValueError(f"{source}: '{k}' must be a non-negative integer (got '{raw[k]}')")

    major = int(raw["major"])
    minor = int(raw["minor"])
    patch = int(raw["patch"])
    code = int(raw["versionCode"])
    name = raw["versionName"]

    expected_name = f"{major}.{minor}.{patch}"
    if name != expected_name:
        raise ValueError(
            f"{source}: versionName '{name}' does not match major.minor.patch '{expected_name}'"
        )
    if code <= 0:
        raise ValueError(f"{source}: versionCode must be > 0 (got {code})")

    return {
        "major": major,
        "minor": minor,
        "patch": patch,
        "name": name,
        "code": code,
        "tuple": (major, minor, patch),
    }


def read_base_props(base_ref: str) -> str:
    remote_ref = f"origin/{base_ref}"
    proc = subprocess.run(
        ["git", "show", f"{remote_ref}:version.properties"],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        subprocess.run(
            ["git", "fetch", "--no-tags", "--depth=1", "origin", base_ref],
            check=True,
        )
        proc = subprocess.run(
            ["git", "show", f"{remote_ref}:version.properties"],
            capture_output=True,
            text=True,
            check=True,
        )
    return proc.stdout


try:
    with open("version.properties", encoding="utf-8") as f:
        head = parse_props(f.read(), "HEAD version.properties")
except Exception as exc:
    print(f"::error::{exc}", file=sys.stderr)
    sys.exit(1)

print(f"HEAD version.properties: {head['name']} (versionCode={head['code']})")

event_name = os.environ.get("EVENT_NAME", "")
base_ref = os.environ.get("BASE_REF", "")

if event_name == "pull_request" and base_ref == "main":
    try:
        base_text = read_base_props(base_ref)
        base = parse_props(base_text, f"origin/{base_ref}:version.properties")
    except Exception as exc:
        print(f"::error::Failed to read base version.properties from '{base_ref}': {exc}", file=sys.stderr)
        sys.exit(1)

    print(f"Base ({base_ref}) version.properties: {base['name']} (versionCode={base['code']})")

    errors = []
    if head["tuple"] <= base["tuple"]:
        errors.append(
            f"versionName '{head['name']}' must be greater than '{base['name']}' on {base_ref}"
        )
    if head["code"] <= base["code"]:
        errors.append(
            f"versionCode ({head['code']}) must be greater than {base['code']} on {base_ref}"
        )

    if errors:
        msg = (
            "version.properties is stale relative to 'main': "
            + "; ".join(errors)
            + ". Bump major/minor/patch, versionName, and versionCode before merging into main."
        )
        print(f"::error::{msg}", file=sys.stderr)
        sys.exit(1)

    print(f"Version bump verified: {base['name']} ({base['code']}) -> {head['name']} ({head['code']})")
PY
