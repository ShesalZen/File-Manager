# Security Hardening Device Smoke Test

Use this checklist with a physical device or emulator connected through ADB. It validates behavior that local JVM unit tests cannot cover: Android Keystore, browser cookies, Wi-Fi FTP, and the public SSH tunnel.

## Prerequisites

- Install the release APK under test.
- Put the device and test computer on the same isolated Wi-Fi network.
- Replace the address and ports with the active test values:

```powershell
$DEVICE_IP = "192.168.1.X"
$FTP_PORT = 2121
$WEB_PORT = 8080
```

- Record the FTP username/password configured in the app and the generated web-share token.

## 1. FTP authentication, containment, and read-only mode

```powershell
# Pre-authentication must fail.
curl.exe -v "ftp://$DEVICE_IP:$FTP_PORT/"

# Authenticated traversal attempts must not read outside the shared root.
curl.exe -v -u "user:password" "ftp://$DEVICE_IP:$FTP_PORT/../../../../etc/hosts"

# Read-only mode must reject writes.
curl.exe -v -T test.txt -u "user:password" "ftp://$DEVICE_IP:$FTP_PORT/test.txt"
```

Expected:

- Pre-auth requests return `530 Please login with USER and PASS.`
- Traversal requests return `550` and do not expose host files.
- Uploads return `550 File modification not allowed` while the upload toggle is off.
- With **Allow uploads and file modifications** enabled, upload succeeds only inside the configured shared root.
- An incorrect password fails authentication and never reaches file commands.

## 2. Web-share token, cookie fallback, and XSS

```powershell
# Unauthenticated requests must fail.
curl.exe -I "http://$DEVICE_IP:$WEB_PORT/"

# A valid query token bootstraps the browser session cookie.
curl.exe -v "http://$DEVICE_IP:$WEB_PORT/?token=<GENERATED_TOKEN>"

# Bearer and query-token clients work without cookie support.
curl.exe -v -H "Authorization: Bearer <GENERATED_TOKEN>" "http://$DEVICE_IP:$WEB_PORT/"
curl.exe -v "http://$DEVICE_IP:$WEB_PORT/?token=<GENERATED_TOKEN>"
```

Expected:

- Unauthenticated requests return `HTTP/1.1 401 Unauthorized`.
- Bootstrap returns `302 Found` and a `Set-Cookie` header containing `HttpOnly` and `SameSite=Strict`.
- Redirected token-free URLs continue to work using the cookie.
- Bearer-only and query-token-only requests work when cookies are blocked.
- An invalid or empty token never grants access.

Create a file named `<script>alert(1)</script>.txt` in the shared directory and inspect the listing:

```powershell
curl.exe -b "session=<SESSION_COOKIE>" "http://$DEVICE_IP:$WEB_PORT/" | Select-String -SimpleMatch "<script>"
```

Expected: the filename is HTML-escaped and no raw `<script>` element is returned.

## 3. Public tunnel and localhost.run pin failure

1. Start **Secure Internet Share (localhost.run)** in the app.
2. Open the generated HTTPS URL from another device.
3. Confirm the tunnel uses the token-required web-share endpoint.

Expected: the tunnel starts only when the server key matches an application pin. If localhost.run rotates its key, the tunnel fails closed and the app displays:

> The public tunnel service rotated its security keys. Please update the application to restore public tunnel sharing.

## 4. SFTP host-key trust and change detection

1. Add an SFTP connection to a test server.
2. Connect for the first time.
3. Compare the displayed SHA-256 fingerprint with `ssh-keygen -lf <server-public-key>` on the server.
4. Trust the key only after it matches.
5. Change the server host key and connect again.

Expected:

- An unknown key requires explicit trust.
- A changed key fails closed and does not overwrite the trusted fingerprint.
- The SSH client is closed after a verification failure.

## 5. Vault legacy migration

1. Place a known legacy v1 `.nfv` file into the app's Vault directory.
2. Unlock the Vault and open the vaulted file.
3. Confirm the contents are intact.
4. Inspect the migrated header with ADB:

```powershell
adb shell "run-as com.zenlabs.filemanager xxd -l 8 /path/to/file.nfv"
```

Expected: the header is `5a56 4155 4c54 3032` (`ZVAULT02`), proving atomic migration to authenticated AES-GCM.

## 6. Android Keystore invalidation and restore

1. Save at least two remote connections.
2. Force-stop and restart the app; confirm the connections remain usable.
3. On a disposable test device, restore the app backup to a device without the original Keystore key, or induce OEM key invalidation.
4. Open each affected saved connection.

Expected:

- The app does not crash and does not discard host, port, username, path, type, or friendly name.
- A password prompt appears before connecting.
- Recovering one connection does not overwrite another connection that still needs recovery.
- Backups never contain the plaintext or encrypted credential preferences; only non-sensitive connection metadata is restored.

## 7. Large image and PDF memory guards

1. Open a very large JPEG/PNG image (at least 4096 px on the long edge).
2. Open a high-page-count, high-resolution PDF.
3. Exercise image edits and several undo/redo operations.

Expected:

- Image decode is sampled rather than fully allocating the source bitmap.
- PDF rasterization remains capped at 300 DPI, 4096 px, and the app heap budget.
- Undo/redo history does not grow beyond three entries and releases old bitmaps.

## Result recording

Record pass/fail, device model, Android version, app version/commit, and any logcat excerpt for each failed case. Do not attach production passwords or tokens to the test report.
