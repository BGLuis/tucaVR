# Phase 1.0 — Release

> **Objective**: Production stabilization and final polish. User-imported custom 3D environments, community translation contribution framework, unified UX onboarding and settings, Dokka/rustdoc contributor and architecture documentation, security and quality audits, and Meta Quest Store submission readiness.  
> **Prerequisite**: Phase 0.5 (Premium) and Phase 0.6 (Stabilization) complete and stable.  
> **Expected Outcome**: The player is a polished, production-ready product for public distribution — any user can install the APK, watch VR/2D content from local or network sources safely, and developers can clone, build, and contribute without friction.

---

## 📋 Table of Contents

1. [Customizable Environments](#1-customizable-environments)
2. [Community Translations (i18n)](#2-community-translations-i18n)
3. [Final UX Polish](#3-final-ux-polish)
4. [Complete Documentation](#4-complete-documentation)
5. [Meta Quest Store Submission](#5-meta-quest-store-submission)
6. [Security and Quality Audit](#6-security-and-quality-audit)
7. [Cross-Cutting Concerns of Phase 1.0](#7-cross-cutting-concerns-of-phase-10)
8. [Definition of Done — v1.0](#8-definition-of-done--v10)

---

## 1. Customizable Environments

### Concept

Fulfills **RF-ENV-005** (User-customizable environments — 3D model importing). Allows users to import external `.glb`/`.gltf` 3D models into their environment catalog.

### Tasks

- [ ] **T1.1** — Implement **environment validator** in C++:
  - Hardware budget validation before importing (triangles ≤ 100K, texture dimensions ≤ 4096², file size ≤ 100MB)
- [ ] **T1.2** — Implement **environment installer** in Kotlin:
  - Import file handling, destination folder layout in app internal storage (`environments/custom_<uuid>/`)
- [ ] **T1.3** — Interactive virtual screen configurator in VR (C++):
  - Visual repositioning and anchoring of the virtual screen inside the newly imported mesh
- [ ] **T1.4** — Texture conversion to ASTC during import
- [ ] **T1.5** — Custom environment management UI (delete, rename, re-anchor)
- [ ] **T1.6** — Automated thumbnail generation for imported environments

---

## 2. Community Translations (i18n)

### Architecture

Enable community localization contributions with CI verification and translator acknowledgments.

### Tasks

- [ ] **T2.1** — Translation validation script for CI (`scripts/validate_i18n.sh`)
- [ ] **T2.2** — Automated CI step for translation key parity enforcement
- [ ] **T2.3** — Contribution guide (`docs/CONTRIBUTING_I18N.md`)
- [ ] **T2.4** — "About" screen with translator credits in Kotlin
- [ ] **T2.5** — Complete plural and positional parameter audit across all locales

---

## 3. Final UX Polish

### Tasks

- [ ] **T3.1** — First-run onboarding tutorial in VR (controller and gesture guidance)
- [ ] **T3.2** — Consolidated unified settings panel
- [ ] **T3.3** — Visual loading states and spinner overlays in C++
- [ ] **T3.4** — Friendly user-facing error handling and recovery recommendations
- [ ] **T3.5** — Accessibility and comfort options (IPD adjustment hints, UI scale toggles)

---

## 4. Complete Documentation

### Tasks

- [ ] **T4.1** — Generate Rust API documentation via `cargo doc`
- [ ] **T4.2** — Generate Kotlin documentation via Dokka
- [ ] **T4.3** — Consolidate comprehensive `BUILD.md`
- [ ] **T4.4** — Write end-user manual (`docs/USER_GUIDE.md`)
- [ ] **T4.5** — Finalize root `README.md` with shields and showcase screenshots
- [ ] **T4.6** — Maintain canonical `docs/ARCHITECTURE.md`

---

## 5. Meta Quest Store Submission

### Tasks

- [ ] **T5.1** — Run Meta VRC Validator Tool locally and resolve warnings
- [ ] **T5.2** — Configure release `AndroidManifest.xml` (VR-only flags, permission cleanup)
- [ ] **T5.3** — APK size and binary optimization (strip symbols, enable ProGuard/R8)
- [ ] **T5.4** — Store visual assets (app icon, banner, panoramic screenshots)
- [ ] **T5.5** — Privacy policy documentation
- [ ] **T5.6** — Complete IARC age rating questionnaire

---

## 6. Security and Quality Audit

### Tasks

- [ ] **T6.1** — Credential storage security audit (`EncryptedSharedPreferences`, no plaintext passwords)
- [ ] **T6.2** — Network TLS/SSL audit (valid certificate enforcement)
- [ ] **T6.3** — Verify complete absence of telemetry or analytics tracking
- [ ] **T6.4** — Reach target unit test coverage (RNF-QUAL-001/002)
- [ ] **T6.5** — Fully green CI/CD build matrix
- [ ] **T6.6** — Comprehensive structured logging audit

---

## 7. Cross-Cutting Concerns of Phase 1.0

> [!IMPORTANT]
> **Strict Semantic Versioning**: Phase 1.0 initiates SemVer guarantees. Any breaking configuration or database schema changes require incremented major versions and explicit migration paths.

---

## 8. Definition of Done — v1.0

- [ ] Custom glTF/GLB environments can be imported and validated
- [ ] Community translation workflow validated by CI
- [ ] Onboarding tutorial guides first-time headset users
- [ ] Documentation complete (Dokka, rustdoc, USER_GUIDE.md, ARCHITECTURE.md)
- [ ] Meta VRC validation tests pass without errors
- [ ] Security audit passes with zero plaintext credentials or telemetry
- [ ] Automated CI pipeline passes cleanly on all branches

---

*Phase 1.0 — Estimate: 8-12 weeks for an experienced solo developer*
