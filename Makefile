.PHONY: all build docker-build install deploy test

all: deploy

build:
	./scripts/build.sh

# The same pipeline as `build`, inside a container (nothing installed on the host).
docker-build:
	./scripts/docker-build.sh

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk

deploy: build install

test:
	@echo "=== [1/3] Running the Rust unit tests (host) ==="
	cd rust && cargo test -p protocols -p media-logic
	@echo "=== [2/3] Running the native C++ unit tests (host) ==="
	./scripts/test-native-host.sh
	@echo "=== [3/3] Running the Kotlin JVM unit tests ==="
	./gradlew testDebugUnitTest
