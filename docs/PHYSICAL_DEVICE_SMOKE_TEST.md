# Physical-device validation checklist

Status: **prepared, not yet executed on a physical device**. No Android device or emulator AVD was
connected to the validation workstation. Automated launch/restart checks are in
`tools/device_smoke_test.ps1`; APK and unit-test checks run without a device.

## Test setup

Record device model, manufacturer, Android/API version/build, ABI (`arm64-v8a`, `armeabi-v7a`, or
`x86_64`), free storage, APK path/SHA-256, app build/signing identity, and network fixtures.

```powershell
adb install -r -t app\build\outputs\apk\release\app-arm64-v8a-release.apk
adb logcat -c
```

The x86 32-bit APK is intentionally not produced because no compatible `lib7za.so` or
`libtectonic.so` exists for that ABI. Test x86_64 emulators when one is available.

## Automated preflight

- [ ] Run `.\gradlew.bat :app:testDebugUnitTest :app:assembleRelease --no-daemon --console=plain`.
- [ ] Run `.\tools\analyze_apks.ps1 -ApkDirectory app\build\outputs\apk\release -ReportPath docs\APK_ANALYSIS.md`.
- [ ] Run `.\tools\device_smoke_test.ps1 -ApkPath app\build\outputs\apk\release\app-arm64-v8a-release.apk`.
- [ ] Confirm install, launch, restart, process liveness, native directory, and no fatal crash.
- [ ] After the interactive session, run `adb logcat -d` and retain the log with the report.

## 1. First launch and onboarding

- [ ] Clean install (`adb shell pm clear com.zenlabs.filemanager`) shows onboarding before files.
- [ ] Swipe every page, use Next/Get Started, and verify the browser appears.
- [ ] Relaunch and verify onboarding does not appear again.
- [ ] Clear data, choose Skip, and verify the browser appears and the choice persists.

## 2. Storage permissions and SAF

- [ ] Android 11+: storage screen links to All files access; grant, return, and browse.
- [ ] Revoke access, relaunch, and verify the app asks again rather than crashing.
- [ ] Android 10 and below: deny then grant legacy read/write permissions.
- [ ] Another app's document picker shows ZenLabs Documents roots and opens a file via valid URI.
- [ ] Denied-provider access produces a recoverable error, not a crash.

## 3. Internal and external storage

- [ ] Browse primary shared, app-specific external, and SD/removable storage.
- [ ] Navigate deep/empty directories; verify names, sizes, dates, and icons refresh.
- [ ] Switch storage devices and verify sensible navigation and current directory.
- [ ] Create/rename directories in writable volumes; read-only volumes have no invalid writes.

## 4. Local file operations

- [ ] Create text, binary, and zero-byte files; copy/move within and across volumes.
- [ ] Rename with Unicode, spaces, and long names.
- [ ] Delete to Recycle Bin, restore, empty it, and permanently delete.
- [ ] Search and verify no duplicate/stale results; share and open a content URI.
- [ ] Interrupt a large copy and verify recovery/error reporting does not corrupt the source.

## 5. Archive extraction and creation

Use ZIP, 7z, TAR, TAR.GZ, and another supported format.

- [ ] List without extraction; extract and compare count, names, sizes, and content.
- [ ] Extract nested directories/Unicode names and encrypted archives.
- [ ] Correct password succeeds; incorrect password fails cleanly.
- [ ] Create ZIP and 7z, then list/re-extract; test progress, cancellation, conflicts, low space.
- [ ] `lib7za.so` executes without `Exec format error`.

## 6. PDF and Office viewers

- [ ] Open a text PDF; scroll, zoom, search/select text, and navigate pages.
- [ ] Open a scanned PDF and exercise OCR if available.
- [ ] Open DOC, DOCX, XLS, XLSX, PPT, PPTX, ODT, and ODS fixtures.
- [ ] Verify pagination, tables, formulas as displayed values, and images.
- [ ] Wrong document password fails gracefully; close/reopen causes no renderer crash.

## 7. Image, audio, and video playback

- [ ] Open JPEG, PNG, GIF, WebP, and a large image; verify zoom and rotation.
- [ ] EXIF-orientation image is not sideways.
- [ ] Play MP3/AAC/FLAC; test seek, pause, background/foreground, and headset route.
- [ ] Play MP4/H.264 video with subtitles if supported; test seek, rotation, pause, and audio.
- [ ] Unsupported codec gives a useful fallback/error; screen lock/rotation causes no crash.

## 8. Remote storage connections

Test FTP, FTPS, SFTP, WebDAV, and SMB/LAN with controlled servers.

- [ ] Create/save/edit/delete a connection with valid and invalid credentials.
- [ ] Connect, list, download, upload, rename, and delete.
- [ ] Slow/disconnected server has timeout, cancel, and retry behavior.
- [ ] Credentials are absent from logs and not exported as plain text.
- [ ] Remote roots appear in ZenLabs Documents; cross-backend copy/move preserves content.

## 9. Terminal and PRoot

- [ ] First-run setup completes to a shell prompt.
- [ ] Run `uname -a`, `id`, `pwd`, `ls`, pipes, and a failing command.
- [ ] Create/edit a file and see it in the file manager.
- [ ] Run 32-bit and 64-bit guest executables under PRoot.
- [ ] `PROOT_LOADER` and `PROOT_LOADER_32` match their guest architectures.
- [ ] Rotate/background/return leaves the session usable; setup failure is recoverable.

## 10. Restart and data persistence

- [ ] Change theme, sort, hidden-file setting, startup tab, bookmark, recents, and connection.
- [ ] Force-stop/relaunch and reboot/relaunch; verify settings and user files persist.
- [ ] Prepared legacy fixture: `.prism` copies to `.zenlabs` without deletion.
- [ ] Interrupted staging resumes; partial unmarked target is never reported complete.
- [ ] Corrupt same-sized file is recopied; source mutation prevents publication.
- [ ] `.prism` remains fallback after a deliberately failed migration.

## 11. Dark mode and configuration persistence

- [ ] Light, Dark, and System themes are legible on every major screen/dialog.
- [ ] Toggle system dark mode while foreground and background.
- [ ] Rotate portrait/landscape and retain settings/state.
- [ ] Change font/display, terminal colors, and viewer settings; force-stop/relaunch.
- [ ] All choices persist.

## Result recording

Record **Pass**, **Fail**, or **Blocked** with device/OS/ABI, steps, expected versus actual, logcat or
screenshot, and issue reference. A crash, data loss, migration false-positive, wrong-ABI native
execution, or unrecoverable denied-permission state is a release blocker.
