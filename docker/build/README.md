# tucaVR — Docker Build

Build do APK debug dentro de um container, sem instalar Android SDK, NDK, Rust
ou FFmpeg no host. O container delega para `scripts/build.sh`, o mesmo pipeline
do build local e do CI.

## Pré-requisitos

1. **Docker** (ou Podman) funcionando: <https://docs.docker.com/engine/install/>.
2. **Meta OpenXR Mobile SDK** em `sdk/meta-openxr-sdk/` (download manual, exige
   aceite de licença):
   - <https://developers.meta.com/horizon/downloads/package/oculus-openxr-mobile-sdk/>
   - deve existir `sdk/meta-openxr-sdk/Samples/SampleXrFramework/`.
3. Um **checkout normal ou clone** do repositório. `git worktree` não funciona:
   o `.git` de um worktree aponta para um caminho que não existe no container
   (o entrypoint detecta e avisa).

## Uso

Da raiz do projeto:

```bash
./scripts/docker-build.sh            # constrói a imagem (cache) e compila o APK
./scripts/docker-build.sh --rebuild  # refaz a imagem do zero (--no-cache --pull)
./scripts/docker-build.sh --shell    # shell interativo no container
make docker-build                    # atalho para o primeiro
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

`APP_VERSION_NAME` e `APP_VERSION_CODE`, se definidos no host, são repassados ao
Gradle. `DOCKER=podman ./scripts/docker-build.sh` usa outro runtime (o caminho
com Podman rootless não foi testado).

## Como funciona

- **Multi-stage**: `sdk`, `ffmpeg` e `rust` são independentes (o BuildKit os
  constrói em paralelo). A imagem final leva só o necessário para compilar: sem
  `nasm`/`yasm`/`curl`/`wget`, sem `.git` do `ffmpeg-android-maker` e sem fontes
  do FFmpeg.
- **Não-root**: o container roda com o UID/GID do host (`--user`), então tudo o
  que ele gera já nasce seu, sem `chown` e sem arquivos de root no projeto.
  Também usa `--cap-drop ALL` e `no-new-privileges`. Sem `--user`, a imagem usa
  `10001:10001` (que não escreve no projeto montado).
- **Nada é escrito fora dos caches e do projeto**: o FFmpeg pré-compilado fica em
  `/opt/ffmpeg-android-maker` e o `build.sh` o encontra por `FFMPEG_MAKER_DIR`; não há
  symlink dentro do seu projeto.
- **Reprodutível**: base Ubuntu por digest, Rust, `cargo-ndk`, `rustup-init` e
  `commandlinetools` fixados (os dois últimos com SHA-256), FFmpeg no mesmo commit
  de `scripts/setup-deps.sh`. Atualize os `ARG` do Dockerfile de propósito.

## Conteúdo da imagem

| Componente | Versão |
|------------|--------|
| Ubuntu | 22.04 (por digest) |
| JDK | 17 (OpenJDK headless) |
| Android SDK | Platform 34, build-tools 34.0.0 |
| Android NDK | 26.3.11579264 |
| CMake | 3.22.1 (via SDK) |
| Rust | 1.97.1 + `aarch64-linux-android` |
| cargo-ndk | 4.1.2 |
| FFmpeg | `arm64-v8a`, API 26 |

## Caches

Volumes nomeados persistem entre builds:

| Volume | Conteúdo |
|--------|----------|
| `tucavr-gradle-cache` | Dependências e cache do Gradle (`/cache/gradle`) |
| `tucavr-cargo-cache` | Registry e git de crates (`/cache/cargo`) |

Para limpar: `docker volume rm tucavr-gradle-cache tucavr-cargo-cache`.

## Uso manual (sem o script)

```bash
docker build -t tucavr-builder docker/build/
docker run --rm --init --user "$(id -u):$(id -g)" \
  --cap-drop ALL --security-opt no-new-privileges:true \
  -v "$(pwd)":/project \
  -v tucavr-gradle-cache:/cache/gradle \
  -v tucavr-cargo-cache:/cache/cargo \
  tucavr-builder
```

## Solução de problemas

- `/project é um git worktree`: use um clone completo (veja Pré-requisitos).
- `Meta OpenXR SDK não encontrado`: veja Pré-requisitos, item 2.
- Cache corrompido ou lento: apague os volumes acima e rode com `--rebuild`.
