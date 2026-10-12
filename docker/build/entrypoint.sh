#!/bin/bash
# =============================================================================
# tucaVR — Build container entrypoint
#
# It only validates the environment and delegates to scripts/build.sh, the single source
# of truth of the pipeline (cargo ndk -> FFmpeg .so -> gradle). The container runs with
# the host's UID, so nothing here needs chown or touches the project's files.
# =============================================================================
set -euo pipefail

die() {
    echo "❌ $1" >&2
    shift
    for line in "$@"; do echo "   $line" >&2; done
    exit 1
}

cd /project

[ -f build.gradle.kts ] || die "Project source code not found in /project" \
    "Mount the project with: -v \"\$(pwd)\":/project"

[ -d sdk/meta-openxr-sdk/Samples/SampleXrFramework ] &&
[ -d sdk/meta-openxr-sdk/OpenXR ] || die "Meta OpenXR SDK not found in sdk/meta-openxr-sdk/" \
    "Download the latest public release with: ./scripts/setup-deps.sh" \
    "https://github.com/meta-quest/Meta-OpenXR-SDK/releases"

# In a git worktree .git is a file that points to a host path that does not
# exist here; Gradle uses git and would fail late, with a confusing error.
[ ! -f .git ] || die "/project is a git worktree (.git is a file)" \
    "Its gitdir path does not exist inside the container." \
    "Use the main checkout or a full clone (git clone)."

[ -d "${FFMPEG_MAKER_DIR}/build/ffmpeg/arm64-v8a/lib" ] || die "Prebuilt FFmpeg missing in ${FFMPEG_MAKER_DIR}" \
    "The image is corrupted; rebuild it with: scripts/docker-build.sh --rebuild"

echo "📋 NDK=${ANDROID_NDK_HOME}  FFMPEG_MAKER_DIR=${FFMPEG_MAKER_DIR}  UID=$(id -u):$(id -g)"

exec ./scripts/build.sh "$@"
