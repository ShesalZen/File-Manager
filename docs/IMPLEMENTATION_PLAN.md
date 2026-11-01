# File Manager by ZenLabs — Implementation Plan

## Scope

Transform the existing Prism File Explorer fork into `File Manager by ZenLabs`
(`com.zenlabs.filemanager`) without discarding working local, privileged, remote,
archive, media, or document-viewing functionality.

## Existing architecture

- Kotlin and Jetpack Compose with Material 3, multi-tab navigation, drawer, grid/list views.
- `ContentHolder` is the common file/folder handle. Implementations cover local files,
  root, Shizuku, archives, virtual collections, and remote files.
- `StorageProvider` discovers local, external, root, and saved remote locations.
  `RemoteClient` supplies blocking operations for FTP/FTPS, SFTP, WebDAV, and SMB/LAN.
- `RemoteFileHolder` downloads to a cache and delegates opening to `LocalFileHolder`.
- `OpenWithDispatchActivity` resolves external files and routes them to dedicated viewers.
- Dedicated viewers exist for PDF, Office/OOXML, spreadsheets, presentations, EPUB/e-books,
  DjVu, LaTeX, Markdown, HTML, text/code, images, audio/video, comics, and archives.
- Preferences use DataStore; recycle bin and open-location history are persisted.
- Google Drive has a credential-free provider seam and explicit setup-required state; live OAuth,
  token storage, and Drive API operations are not implemented yet.

## Implementation checkpoint (2026-11-01)

Completed foundation work:

- Rebranding is implemented and compiles under `com.zenlabs.filemanager`.
- Legacy shared app data is migrated off the main thread through a resumable staging directory.
  Files are SHA-256 checked during copy, the complete tree is verified again, and the target is
  published by rename without deleting the `.prism` source. The untouched legacy directory stays
  active as a fallback if the target is not verified.
- The existing `DocumentViewerActivity` and `UniversalDocumentConverter` stack is the shared
  document-viewing path for local and remote content; it was preserved rather than duplicated.
- Stable provider metadata, capability rules, holder classification, and a `ContentHolder`
  façade have been added under `screen/main/tab/files/provider`.
- Google Drive is represented by an `AUTH_REQUIRED` descriptor and a credential-gated
  authorization-request seam. The drawer exposes the setup-required state; live OAuth, token
  storage, and Drive API operations are not implemented.
- Unit coverage includes provider kinds/capabilities, Drive configuration behavior, legacy
  migration integrity/fallback behavior, and existing correctness/performance suites. The TextMate
  warm-index guard rail is configurable through the `zenlabs.textmate.indexBudgetMs` system
  property because hosted JVM performance is not a reliable on-device proxy.
- Native builds are path-safe on Windows: PRoot quotes path-bearing compiler options and generates
  loader metadata without external `awk`; terminal-emulator uses CMake/Ninja instead of the legacy
  `ndk-build` wrapper that cannot resolve a build script under a path containing spaces.
- Final host verification passes: 48 unit tests with zero failures, Kotlin/Java compilation, and
  debug APK assembly for universal, ARM64, ARMv7, and x86_64 outputs. x86 is intentionally excluded
  because upstream has no x86 `lib7za.so` or `libtectonic.so`. The APK/native-library audit passes
  with no warnings. Physical-device behavior remains prepared but unverified.

Remaining work depends on an OAuth client ID, redirect URI, and a consent-flow ownership
decision. Until those are supplied, no OAuth library, token store, Drive network client, or
credentialed APK configuration should be added.

## Existing features to preserve

Local and external storage; SAF provider; root and Shizuku; FTP/FTPS, SFTP, WebDAV,
SMB/LAN; local FTP and web-sharing servers; recycle bin; bookmarks; search; recents;
archive browsing/extraction; APK bundle handling; terminal/PRoot; all current viewers.

## Required improvements and additions

1. Complete branding, icons, metadata, package references, and build validation.
2. Make legacy `.prism` migration non-destructive, resumable, and off the main thread.
3. Add explicit storage capabilities and a source registry over existing backends.
4. Centralize document classification so local, SAF, remote, and cloud files share routing.
5. Add Google Drive API v3 as a `RemoteClient` backend using the existing holder/cache path.
6. Add Google Identity authorization, encrypted access-token storage, reauthorization
   state, and explicit not-configured/disconnected UI.
7. Add tests for migration, routing, capabilities, Drive path/export logic, and query handling.
8. Update settings/about/cloud entry points and document OAuth configuration.
9. Build debug APK, run unit tests, and report verified versus unverified behavior.

## Files expected to change

- `App.kt`, `BrandCompatibility.kt`, `ContentHolder` implementations.
- `StorageProvider`, new storage source/capability classes, drawer and cloud UI.
- `OpenWithDispatchActivity` and new document-routing classes.
- `RemoteClient`, `RemoteConnectionPool`, `RemoteFileHolder`, `RemoteFileItem`.
- New `drive` package for configuration, token storage, authorization, and API client.
- Gradle version catalog/build files, manifest, strings, README, fastlane metadata.
- New tests under `app/src/test/java/com/zenlabs/filemanager`.

## Dependencies

- Google Play services Auth 22.0.0 is a candidate for `AuthorizationClient`, but it has not been
  added because the consent-flow ownership decision is still open.
- Reuse existing OkHttp, Gson, and DataStore; no duplicate HTTP/JSON stack.
- Do not add Google Drive Java client libraries (large and unnecessary for a scoped v3 REST client).

## Compatibility and security decisions

- Keep legacy `.prism` data readable. Copy into a verified temporary `.zenlabs` directory,
  then publish it atomically; do not delete `.prism`.
- Keep legacy preference import extension `prismprefs` and datastore key compatibility.
- Drive scope is only `https://www.googleapis.com/auth/drive.file`.
- Google-native Docs/Sheets/Slides use export, never binary download.
- Tokens are encrypted with an Android Keystore AES/GCM key.
- No credentials or tokens are logged or committed.
- Drive stays explicitly unconfigured until OAuth setup is supplied.

## Phase order

1. Rebranding, build stabilization, documentation.
2. Safe migration, foundational capabilities, and routing.
3. Viewer routing consolidation and regression checks.
4. Storage source façade and existing-provider adaptation.
5. Google Drive authentication and API provider.
6. Cloud UI, polish, error/loading/accessibility states.
7. Build, tests, completion report, remaining OAuth steps.
