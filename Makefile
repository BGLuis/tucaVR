.PHONY: all build docker-build install launch deploy test

all: deploy

build:
	./scripts/build.sh

# The same pipeline as `build`, inside a container (nothing installed on the host).
docker-build:
	./scripts/docker-build.sh

FAST       ?= 0
LAUNCH     ?= 1
KEEP_AWAKE ?= 0

DEPLOY_ARGS := $(if $(filter 0,$(FAST)),--no-fast,) \
               $(if $(filter 0,$(LAUNCH)),--no-launch,) \
               $(if $(filter 1,$(KEEP_AWAKE)),--keep-awake,) \
               $(if $(DEVICE_SERIAL),-s $(DEVICE_SERIAL),)

install:
	./scripts/deploy.sh $(DEPLOY_ARGS)

launch:
	adb shell am force-stop com.tucavr
	adb shell am start -n com.tucavr/.VRActivity

deploy: build install launch

# The brand suite's Gradle-backed tests repeat the JVM tests or run lint, which `test`
# leaves out, hence --skip-gradle.
test:
	@echo "=== [1/4] Running the Rust unit tests (host) ==="
	cd rust && cargo test -p protocols -p media-logic
	@echo "=== [2/4] Running the native C++ unit tests (host) ==="
	./scripts/test-native-host.sh
	@echo "=== [3/4] Running the Kotlin JVM unit tests ==="
	./gradlew testDebugUnitTest
	@echo "=== [4/4] Running the brand asset tests (host) ==="
	./scripts/test-brand-transition.sh --skip-gradle
