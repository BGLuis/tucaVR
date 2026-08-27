## Description

<!-- Describe clearly and concisely what this Pull Request changes or adds. -->
<!-- If this PR resolves an existing issue, use the closing keywords (e.g., "Closes #123") -->

## Type of change

<!-- Check the relevant options (put an 'x' inside the brackets) -->

- [ ] 🐛 Bug fix (non-breaking change which fixes an issue)
- [ ] ✨ New feature (non-breaking change which adds functionality)
- [ ] 🛠️ Technical refactor / Code improvement
- [ ] 📝 Documentation update
- [ ] 🚀 Performance optimization
- [ ] 💥 Breaking change (fix or feature that would cause existing functionality to not work as expected)

## Affected layers

<!-- Which parts of the Kotlin <-JNI-> C++ <-C ABI-> Rust stack does this touch? -->

- [ ] 🟣 Kotlin (`app/`) — app shell, UI panels, credentials, history, i18n
- [ ] 🔵 C++ (`native/`) — OpenXR session, Vulkan/GLES render loop, controller input
- [ ] 🦀 Rust (`rust/`) — demux, decode, audio, network protocols
- [ ] 🔧 Build / CI / scripts
- [ ] 📚 Docs only

## How has this been tested?

<!-- Describe the tests that you ran to verify your changes. -->
<!-- Remember to mention if you tested only on the emulator/host or directly on a VR headset (e.g., Meta Quest 3). -->

- [ ] Unit tests (JVM / `cargo test`)
- [ ] Native/Vulkan build compiled without errors
- [ ] Physically tested on a device (Quest 2 / Quest 3 / Quest 3s)

## Checklist:

- [ ] My code follows the project's style guidelines (ktlint, rustfmt, `clippy -D warnings`).
- [ ] I have performed a self-review of my own code.
- [ ] I have commented my code, particularly in hard-to-understand areas.
- [ ] I have added new tests that prove my fix is effective or that my feature works.
- [ ] The CI/CD Actions (build-and-lint) are passing for this PR.

### If applicable

- [ ] Kotlin never calls Rust directly — the Kotlin → JNI → C++ → C ABI → Rust path is preserved (ADR-002).
- [ ] I changed the screen/stereo mode enum and kept it in sync across **all three** places: `SCREEN_MODE` in `rust/bridge/src/lib.rs`, `enum class ScreenMode` in `native/src/vr_player_app.cpp`, and `modeLabelResIds` in `VRControlsPresentation.kt`.
- [ ] I added or changed user-facing strings and updated **both** `values/strings.xml` and `values-pt-rBR/strings.xml`, keeping the key order mirrored and using positional placeholders (`%1$s`).
- [ ] Pure logic I added lives in `media-logic` (host-testable) rather than in `core`, where it could not be tested.
- [ ] No credential is stored or logged in plain text.

## Screenshots / Videos (if applicable)

<!-- For visual changes, please add screenshots or short clips showing the before and after. -->
