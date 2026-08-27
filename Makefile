.PHONY: all build install deploy test

all: deploy

build:
	./scripts/build.sh

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk

deploy: build install

test:
	@echo "=== [1/3] Executando testes unitarios Rust (host) ==="
	cd rust && cargo test -p protocols -p media-logic
	@echo "=== [2/3] Executando testes unitarios nativos C++ (host) ==="
	./scripts/test-native-host.sh
	@echo "=== [3/3] Executando testes unitarios Kotlin JVM ==="
	./gradlew testDebugUnitTest
