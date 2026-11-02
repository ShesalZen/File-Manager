# Development Status

Last updated: 2026-11-01

This document distinguishes implementation and host-side verification from behavior that
still requires a physical Android device or emulator. The release gate is the checklist in
`docs/PHYSICAL_DEVICE_SMOKE_TEST.md`; it has been prepared but has not been executed.

## Validation snapshot

- Package/namespace: `com.zenlabs.filemanager`.
- Clean host build: `clean :app:testDebugUnitTest :app:assembleDebug` succeeded.
- Unit tests: **71 passed, 0 failed, 0 errors, 0 skipped** across 16 suites.
- Debug APKs: universal, ARM64, ARMv7, and x86_64 outputs were produced.
- Supported packaged ABIs: `arm64-v8a`, `armeabi-v7a`, and `x86_64`. There is deliberately
  **no x86 APK** because upstream has no x86 builds of `lib7za.so` or `libtectonic.so`.
- APK native-library audit: no missing libraries, unexpected libraries, unsupported ABI
  directories, or wrong ELF machine types. See `docs/APK_ANALYSIS.md`.
- PowerShell validation: `tools/analyze_apks.ps1` and `tools/device_smoke_test.ps1` both pass
  the PowerShell parser; the APK analyzer completed with no warnings.
- Runtime environment: `adb` is installed, but no device is connected and no emulator AVD is
  available. Device installation, launch, crash scanning, and the physical-device checklist
  therefore remain unexecuted.

## Implemented and tested

### Build and packaging

- Kotlin/Java debug compilation, unit tests, dexing, packaging, and APK assembly pass from a
  clean build.
- The application uses the `arm64-v8a`, `armeabi-v7a`, and `x86_64` ABI filters consistently in
  both the application build and ABI splits.
- The generated artifacts are:
  - `app-arm64-v8a-debug.apk`: 96,756,203 bytes.
  - `app-armeabi-v7a-debug.apk`: 94,862,243 bytes.
  - `app-x86_64-debug.apk`: 99,161,271 bytes.
  - `app-universal-debug.apk`: 141,340,369 bytes (134.79 MiB).
- `tools/analyze_apks.ps1` checks each APK's ABI directories, expected native-library inventory,
  and ELF architecture. The current report is committed under `docs/APK_ANALYSIS.md`.

### File sizes, home tiles, scrollbar, Recent list, and naming

- File sizes are decimal (base-1000) everywhere: `common/FileSizeFormat.kt` renders `B`, `KB`, `MB`,
  `GB`, and `TB` with at most one decimal, and `Long.toFormattedSize()` in `common/Utils.kt`
  delegates to it so every screen shows the same string. Unit selection uses repeated division
  instead of `log10`/`pow`, and promotes a unit before it can render as `1,000 KB`.
- Home category tiles use short, static labels that fit the tile width (`Recent`, `APK`); the
  animated marquee was removed so tile text never scrolls, with ellipsis only as a safety net for
  very large font scales.
- The list scrollbar draws only its draggable thumb (`common/ui/FastScrollbar.kt`): the full-height
  track/bar is no longer painted, while hold-to-drag seeking, fast scrolling, and section jumps are
  unchanged.
- The Recent list is bounded (`RecentFilePolicy.kt` + `StorageProvider`): it merges files mutated
  inside the app with a MediaStore query restricted to a recency window, newest first, capped at
  `MAX_RECENT_CANDIDATES`. The previous whole-device walk (which could list ~18,000+ entries) and
  its legacy MediaStore cache were removed. Recency is based on last-modified time, which is what
  the app can obtain for every provider; it is never presented as a "last opened" time.
- Recent change detection was re-pointed at the same bounded MediaStore query that builds the
  list (`recentNewestModifiedSeconds`), because the old signal came from the all-device cache that
  Recent no longer populates. The list and the check now share one query builder, so they cannot
  drift apart in their filters.
- `RecentFile.lastModified` is normalized to milliseconds everywhere. The all-device collector
  previously stored seconds while the new bounded collector stored milliseconds, which would have
  silently scrambled the newest-first ordering if the two were ever merged. MediaStore timestamps
  now go through `RecentFilePolicy.mediaStoreSecondsToMillis`.
- The app is named **File Manager** (`app_name`, `main_activity_title`, share/open-with labels,
  onboarding copy, and the eight translated `app_name` variants). The "by ZenLabs" suffix was
  dropped from the display name; a subtle `Made by ZenLabs` attribution is shown under the version
  badge on the About screen instead.
- New JVM-only tests cover this work: `common/FileSizeFormatTest` (8 tests, locale pinned to
  `en-US`) and `...files.provider.RecentFilePolicyTest` (15 tests) for sorting stability, the
  exclusion rules (hidden paths, temp/database sidecars, `Android/data`, `Android/obb`, recycle
  bin, and user-configured excluded paths), and the MediaStore seconds-to-milliseconds conversion.

### Legacy data migration

- `BrandMigration` copies legacy shared data into a sibling staging directory without deleting
  the `.prism` source.
- Every file is SHA-256 checked during copy and the complete tree is checked again before an
  atomic publish. A completion marker distinguishes a verified target from an unmarked or
  partial target.
- Interrupted staging is resumable; a corrupt same-sized file is recopied, and unexpected
  staging files are removed before publication.
- `preferredDirectory` uses a verified new target when available and otherwise keeps the
  untouched legacy directory active as a fallback.
- Eight focused migration tests cover successful copy, idempotence, partial-target rejection,
  exact-match verification, resumable/corrupt-file replacement, failure preservation, source
  mutation detection, and the no-legacy-data case.

### Validation tooling

- `tools/device_smoke_test.ps1` prepares ADB install, timed launch, process checks,
  force-stop/restart, installed native-library inspection, and fatal-exception log scanning.
- `docs/PHYSICAL_DEVICE_SMOKE_TEST.md` defines the manual release checklist and records
  Pass/Fail/Blocked outcomes and release-blocker criteria.

## Implemented but not runtime-verified

The following code paths are present and compile, but they have not been exercised on Android
hardware in this validation phase. They must not be described as device-verified.

- ZenLabs rebranding: package/namespace, labels, compatibility layer, README, and adaptive and
  legacy launcher icons are implemented and compile.
- Onboarding and core file-manager UI: Compose screens, navigation, dark mode, and the SAF
  document provider compile, but launch, interaction, and persistence were not device-tested.
- Local and privileged storage: internal/external storage, root, and Shizuku implementations
  exist in the preserved upstream codebase; their runtime behavior was not retested here.
- File operations, archives, search, recycle bin, favorites, recents, and APK tools are present
  in source and compile, but no physical-device operations were executed.
- Remote providers FTP/FTPS, SFTP, WebDAV, and SMB/LAN, plus local FTP and web-sharing servers,
  compile but require network/device verification.
- Terminal/PRoot and the PDF, Office, spreadsheet, presentation, e-book/comic, text/code, image,
  audio, and video viewers remain implemented in source; their native/runtime paths were not
  exercised.
- The device smoke-test script and manual checklist are **prepared, not executed** because the
  host has no connected device and no emulator AVD.

## Partially implemented

- Provider architecture is a foundation rather than a completed rewrite. Stable provider
  metadata, capability rules, holder classification, a provider catalog, and a `ContentHolder`
  façade are implemented and unit-tested; broad UI and operation adoption can continue
  incrementally.
- Google Drive has a credential-free provider seam, explicit `AUTH_REQUIRED`/setup-required UI,
  and unit-tested configuration behavior. This seam does **not** implement Google Drive.
- The existing `DocumentViewerActivity` and `UniversalDocumentConverter` stack remains the
  shared in-app routing layer. It is preserved rather than replaced by a new viewer, and cloud
  export behavior is not implemented.
- Release validation is prepared but incomplete until the physical-device checklist is executed
  and recorded.
- Drawer cloud state and provider-aware messaging exist, while broader accessibility, loading,
  error, and cloud-provider UI polish remains.

## Not implemented

### Google Drive

Google Drive is **not implemented**. The repository currently contains only a credential-free
provider seam and setup-required state. None of the following exist yet:

- OAuth consent flow or authorization activity.
- OAuth client/redirect configuration and signing-SHA registration.
- Encrypted access/refresh-token storage, token refresh, or reauthorization handling.
- Drive API v3 client and provider operations.
- Drive browsing, upload, download, rename, delete, or file creation.
- Google-native Docs/Sheets/Slides export through the document-viewer stack.
- Drive-specific error, loading, offline, or account-selection UI.

The OAuth client ID, redirect URI, and consent-flow ownership decision are still required before
implementation. The intended scope is `https://www.googleapis.com/auth/drive.file`; this scope
cannot provide unrestricted whole-Drive browsing, and existing files need user selection (for
example, the system picker) to grant access.

### Other gaps and risks

- Physical-device validation has not been executed, so the release checklist remains open.
- The app remains a sideload-oriented power-user product due to storage, package-query,
  install-package, Shizuku, root, and terminal capabilities.
- Gallery/private-vault obfuscation must not be represented as strong encryption.
- Legacy migration cannot import the upstream app's private DataStore after an app-ID change;
  only exported preference files and shared external app data are portable.
