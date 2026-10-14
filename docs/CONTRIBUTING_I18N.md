# Contributing Translations to tucaVR

Thank you for your interest in translating tucaVR! This guide provides step-by-step instructions and requirements for contributing new languages or improving existing translations.

For the full architectural and technical context of the localization system, see [docs/i18n.md](i18n.md) (especially Section 5, "How to Add a New Language").

---

## Quick Start: Adding a New Language

1. **Identify the Android locale qualifier**:
   - Use standard BCP-47 qualifiers: `values-<language>` (e.g., `values-fr` for French, `values-de` for German, `values-ja` for Japanese).
   - Use regional variants when necessary: `values-<language>-r<REGION>` (e.g., `values-pt-rBR`, `values-zh-rCN`).

2. **Create the string resource directory**:
   - Create `app/src/main/res/values-<qualifier>/strings.xml`.
   - Copy the structure and comments directly from the canonical default file: [`app/src/main/res/values/strings.xml`](../app/src/main/res/values/strings.xml).
   - **Do not** copy from another translation (such as `values-pt-rBR` or `values-es`), to avoid compounding translation drift.

3. **Translate the values**:
   - Translate each string while strictly preserving key names, declaration order, and placeholder formatting.
   - UI symbols and icons (e.g., `📁`, `🌐`, `⚠`) at the start of strings are visual markers; preserve them intact.

---

## Translation Rules & Requirements

All translations are checked automatically on every pull request by `com.tucavr.I18nParityTest`. A build will fail if any rule is violated.

### 1. Strict Key Parity

- Every key present in `values/strings.xml` must exist in your translation file.
- No extra keys may be introduced in a translation file without adding them to all supported locales and the default English file.

### 2. Identical Key Declaration Order

- The declaration order of keys in `values-<qualifier>/strings.xml` must mirror `values/strings.xml` line by line.
- This ensures clean side-by-side diffs during code reviews and maintenance.

### 3. Positional Placeholders (`%1$s`, `%2$d`)

- Always use positional placeholders (`%1$s`, `%1$d`, `%2$s`).
- Unindexed placeholders like `%s` or `%d` are strictly prohibited because they break when word order changes across languages.
- You may change the order of placeholders to match your language's grammar (e.g., `"%2$s of %1$s"` instead of `"%1$s of %2$s"`), but all placeholders from the default string must be accounted for with identical format specifiers.

### 4. Plurals (`<plurals>`)

- Use `<plurals>` resources for counts and quantities.
- Include all quantity items required by your language according to the [Unicode CLDR Plural Rules](https://cldr.unicode.org/index/cldr-spec/plural-rules) and [Android Plurals Documentation](https://developer.android.com/guide/topics/resources/string-resource#Plurals).
- English, Spanish, and Brazilian Portuguese require `one` and `other`. Other languages (such as Russian, Arabic, Polish) require additional categories (`zero`, `two`, `few`, `many`).

---

## Testing Your Translation Locally

Before opening a pull request, run the automated translation parity tests on your machine (no headset required):

```bash
./gradlew testDebugUnitTest --tests "com.tucavr.I18nParityTest"
```

To run the full suite of Kotlin and resource lint checks:

```bash
./gradlew ktlintCheck
./gradlew :app:lintDebug
```

---

## Pull Request Checklist for Translators

When submitting a translation PR, please ensure:

- [ ] New locale directory follows the correct qualifier naming (`values-<lang>` or `values-<lang>-r<REGION>`).
- [ ] Every key from `app/src/main/res/values/strings.xml` is translated.
- [ ] The order of string and plural tags exactly matches `app/src/main/res/values/strings.xml`.
- [ ] All positional placeholders (`%1$s`, `%1$d`, etc.) are preserved with correct types.
- [ ] No non-positional placeholders (`%s`, `%d`) are used.
- [ ] Plurals contain all required CLDR categories for the target language.
- [ ] `./gradlew testDebugUnitTest --tests "com.tucavr.I18nParityTest"` passes with 0 failures.
