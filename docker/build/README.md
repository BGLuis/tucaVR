# tucaVR — Docker Build

Build do APK totalmente dentro de um container Docker, sem instalar
Android SDK, NDK, Rust ou FFmpeg no host.

## Pré-requisitos

1. **Docker** instalado e funcionando:
   ```bash
   sudo apt-get update && sudo apt-get install -y docker.io
   sudo systemctl enable --now docker
   sudo usermod -aG docker $USER
   # logout + login para o grupo ter efeito
   ```

2. **Meta OpenXR Mobile SDK** em `sdk/meta-openxr-sdk/`:
   - Baixe em https://developers.meta.com/horizon/downloads/package/oculus-openxr-mobile-sdk/
   - Extraia de forma que exista `sdk/meta-openxr-sdk/Samples/SampleXrFramework/`

## Uso Rápido

Da raiz do projeto:

```bash
# Build completo (imagem + APK)
./docker-build.sh

# Forçar rebuild da imagem Docker
./docker-build.sh --rebuild

# Shell interativo para debug
./docker-build.sh --shell
```

O APK gerado aparece em: `app/build/outputs/apk/debug/app-debug.apk`

## Uso Manual (docker CLI)

```bash
# 1. Construir a imagem (uma vez)
docker build -t tucavr-builder docker/build/

# 2. Compilar o APK
docker run --rm \
  -v "$(pwd)":/project \
  -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) \
  -v tucavr-gradle-cache:/root/.gradle \
  -v tucavr-cargo-cache:/root/.cargo/registry \
  tucavr-builder

# 3. (Opcional) Shell interativo
docker run --rm -it \
  -v "$(pwd)":/project \
  --entrypoint /bin/bash \
  tucavr-builder
```

## O que está dentro da imagem

| Componente | Versão |
|------------|--------|
| Ubuntu | 22.04 |
| JDK | 17 (OpenJDK Headless) |
| Android SDK | Platform 34 |
| Android NDK | 26.3.11579264 |
| CMake | 3.22.1 (via SDK) |
| Rust | stable + `aarch64-linux-android` |
| cargo-ndk | latest |
| FFmpeg | Pré-compilado para `arm64-v8a` (API 26) |

**Tamanho estimado da imagem:** ~12-15 GB (SDK + NDK + FFmpeg são pesados).

## Caches

Volumes Docker persistem caches entre builds:

| Volume | Conteúdo |
|--------|----------|
| `tucavr-gradle-cache` | Downloads de dependências Gradle e build cache |
| `tucavr-cargo-cache` | Registry de crates Rust |

Para limpar caches:
```bash
docker volume rm tucavr-gradle-cache tucavr-cargo-cache
```

## Variáveis de Ambiente Opcionais

| Variável | Descrição |
|----------|-----------|
| `APP_VERSION_NAME` | Override da versionName (ex: `1.0.0`) |
| `APP_VERSION_CODE` | Override do versionCode (ex: `100`) |
| `HOST_UID` | UID do host para corrigir ownership dos artefatos |
| `HOST_GID` | GID do host para corrigir ownership dos artefatos |

Exemplo:
```bash
docker run --rm \
  -v "$(pwd)":/project \
  -e APP_VERSION_NAME=1.0.0 \
  -e APP_VERSION_CODE=100 \
  -e HOST_UID=$(id -u) -e HOST_GID=$(id -g) \
  tucavr-builder
```
