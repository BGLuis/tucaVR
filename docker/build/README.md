# tucaVR — Docker Build

Build do APK debug dentro de um container, sem instalar Android SDK, NDK, Rust
ou FFmpeg no host. O container delega para `scripts/build.sh`, o mesmo pipeline
do build local e do CI.

## Pré-requisitos

1. **Docker** (ou Podman) funcionando: <https://docs.docker.com/engine/install/>.
2. **Meta OpenXR SDK** em `sdk/meta-openxr-sdk/`. Rode
   `./scripts/setup-deps.sh` para baixar o último release público do GitHub:
   <https://github.com/meta-quest/Meta-OpenXR-SDK/releases>
3. Um **checkout normal ou clone** do repositório. `git worktree` não funciona:
   o `.git` de um worktree aponta para um caminho que não existe no container
   (o entrypoint detecta e avisa).

## Uso

Da raiz do projeto:

```bash
./scripts/docker-build.sh            # constrói a imagem (cache) e compila o APK
./scripts/docker-build.sh --clean    # descarta caches de compilação e recompila tudo
./scripts/docker-build.sh --rebuild  # refaz a imagem do zero (--no-cache --pull)
./scripts/docker-build.sh --shell    # shell interativo no container
make docker-build                    # atalho para o primeiro
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

`APP_VERSION_NAME` e `APP_VERSION_CODE`, se definidos no host, são repassados ao
Gradle. `DOCKER=podman ./scripts/docker-build.sh` usa outro runtime (o caminho
com Podman rootless não foi testado).

Para confirmar uma alteração com uma compilação sem artefatos anteriores, use
`./scripts/docker-build.sh --clean`. Isso limpa o target do Cargo e o estado de
build Android/CMake antes de compilar novamente. No host, o equivalente é
`./scripts/build.sh --clean`. A compilação normal já rastreia alterações e
recompila os arquivos afetados; a opção limpa é para diagnóstico ou conferência,
não é necessária após cada edição.

## Build pela Android Studio

Use **Build > Make Project** (ou execute `assembleDebug` na janela Gradle). A
task `buildRust` é executada automaticamente durante a montagem do APK: compila
Rust com Cargo NDK e atualiza `app/src/main/jniLibs/arm64-v8a` antes de empacotar
o APK. São necessários no host o JDK/Android SDK/NDK configurados no projeto,
Rust com `cargo-ndk` instalado (`cargo install cargo-ndk`) e o FFmpeg compilado
conforme o passo de instalação no `README.md` (o `setup-deps.sh` baixa as fontes,
mas não compila o FFmpeg). C++ continua sendo compilado pelo CMake/Android
Gradle Plugin.
Para uma compilação sem artefatos anteriores, execute a task Gradle `clean` e
depois `assembleDebug` no Android Studio; `clean` também limpa os artefatos Cargo.

## Como funciona

- **Multi-stage**: `sdk`, `ffmpeg` e `rust` são independentes (o BuildKit os
  constrói em paralelo). A imagem final leva só o necessário para compilar: sem
  `nasm`/`yasm`/`curl`/`wget`, sem `.git` do `ffmpeg-android-maker` e sem fontes
  do FFmpeg.
- **Não-root**: o container roda com o UID/GID do host (`--user`), então tudo o
  que ele gera já nasce seu, sem `chown` e sem arquivos de root no projeto.
  Também usa `--cap-drop ALL` e `no-new-privileges`. Sem `--user`, a imagem usa
  `10001:10001` (que não escreve no projeto montado).
- **Alternância com Android Studio**: antes do build, o script guarda o estado
  nativo do host (`app/.cxx` e `app/build/intermediates/cxx`) e restaura-o ao
  sair. Se for detectado um cache com o caminho do Ninja indisponível no host,
  ele será removido e recriado no container. Assim, caminhos absolutos do SDK do
  container não quebram o `clean` nem os builds do Android Studio.
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
- `Meta OpenXR SDK não encontrado`: rode `./scripts/setup-deps.sh`.
- Cache corrompido ou lento: apague os volumes acima e rode com `--rebuild`.
