# Native Sanitizer CI Builds

The native Android modules expose opt-in standalone CMake configurations for AddressSanitizer (ASan) and UndefinedBehaviorSanitizer (UBSan). Production Gradle builds do not enable either sanitizer and are unchanged by these presets.

GitHub Actions runs these checks in the `native-sanitizers` job defined in
`.github/workflows/ci.yml`. The Android build and JVM unit tests run separately in the
`android-validation` job.

## PRoot

PRoot is compiled with `-fsanitize=address,undefined` and `-fno-omit-frame-pointer` when `PROOT_ENABLE_SANITIZERS=ON`. The freestanding loader is intentionally excluded because it does not link a sanitizer runtime.

```bash
cmake --preset asan-ubsan -S proot
cmake --build --preset asan-ubsan -S proot
```

This is a build-level native assurance check. A PRoot guest-runtime integration test is not included because it requires a controlled guest rootfs and tracee environment; the existing Android release build remains the authoritative production validation.

## link2symlink

`link2symlink` has a host-only CTest smoke test. It verifies relative and absolute path resolution and actual `link`/`linkat` symlink creation while the implementation is built with ASan and UBSan.

```bash
cmake --preset asan-ubsan -S link2symlink
cmake --build --preset asan-ubsan -S link2symlink
ctest --preset asan-ubsan -S link2symlink
```

The test is enabled only on non-Android UNIX hosts. Android CI should continue to use Gradle's `assembleRelease` task for ABI-specific production builds.

## PRoot `openat2` semantics

PRoot translates guest paths into host paths rooted under the configured guest rootfs. It rewrites `openat2(2)` to `openat(2)` because an outer seccomp policy may trap the newer syscall, and because the translated pathname is an absolute host path.

The `how.resolve` flags, including `RESOLVE_BENEATH`, `RESOLVE_IN_ROOT`, `RESOLVE_NO_SYMLINKS`, and `RESOLVE_NO_MAGICLINKS`, are intentionally discarded. They describe constraints relative to the caller's original directory descriptor in the guest namespace, while PRoot's translated path is resolved in the host namespace. Passing those flags through would reject the valid translated host path or apply the constraint to the wrong namespace.

PRoot's existing guest-root path translation and binding checks provide the containment boundary. This is not equivalent to native kernel `openat2` `RESOLVE_*` enforcement, and callers must not rely on those flags as an additional sandbox layer inside PRoot.
