# Task runner at strict parity with Makefile (transition period: Makefile stays untouched).
# Follow-up: consolidate README/scripts onto `just` and evaluate passing `RUST_BUILD_MODE=skip`
# to `./gradlew testDebugUnitTest` in `test` once the transition is complete.

set positional-arguments

fast := env("FAST", "0")
launch := env("LAUNCH", "1")
keep_awake := env("KEEP_AWAKE", "0")
device_serial := env("DEVICE_SERIAL", "")

deploy_args := trim((if fast == "0" { "--no-fast" } else { "" }) + " " + (if launch == "0" { "--no-launch" } else { "" }) + " " + (if keep_awake == "1" { "--keep-awake" } else { "" }) + " " + (if device_serial != "" { "-s " + device_serial } else { "" }))

# Default target: build, install, and launch on Quest 3
[default]
all: deploy

# Run full build pipeline via scripts/build.sh
build:
    ./scripts/build.sh

# Run the same build pipeline inside a container (no host toolchain required)
docker-build:
    ./scripts/docker-build.sh

# Install the APK onto a connected Quest 3 via scripts/deploy.sh
install:
    ./scripts/deploy.sh {{ deploy_args }}

# Force-stop and launch com.tucavr on the connected Quest 3
launch:
    adb shell am force-stop com.tucavr
    adb shell am start -n com.tucavr/.VRActivity

# Build, install, and launch the app
deploy: build install launch

# The brand suite's Gradle-backed tests repeat the JVM tests or run lint, which `test`
# leaves out, hence --skip-gradle.

# Run all host unit test suites (Rust + C++ host + Kotlin JVM + brand assets)
test:
    @echo "=== [1/4] Running the Rust unit tests (host) ==="
    cd rust && cargo test -p protocols -p media-logic
    @echo "=== [2/4] Running the native C++ unit tests (host) ==="
    ./scripts/test-native-host.sh
    @echo "=== [3/4] Running the Kotlin JVM unit tests ==="
    ./gradlew testDebugUnitTest
    @echo "=== [4/4] Running the brand asset tests (host) ==="
    ./scripts/test-brand-transition.sh --skip-gradle
