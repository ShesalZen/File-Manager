package com.zenlabs.filemanager.screen.main.tab.files.service

import android.content.Context
import android.net.Uri
import com.zenlabs.filemanager.common.PathContainment
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.regex.Pattern
import kotlin.concurrent.thread

object WebSharingServer {
    private const val PORT = 8080
    private const val MAX_HEADER_BYTES = 8 * 1024
    private const val MAX_UPLOAD_BYTES = 2L * 1024L * 1024L * 1024L
    private const val MAX_CONCURRENT_CLIENTS = 16
    private const val SOCKET_TIMEOUT_MS = 30_000
    private const val TOKEN_COOKIE = "session"

    private var serverSocket: ServerSocket? = null
    private var isLocalActive = false
    private var isInternetActive = false
    private var localIpAddress = "127.0.0.1"
    private var internetShareLink = ""
    private var internetErrorMessage = ""
    private var sshClient: SSHClient? = null
    private var rootDir = ""
    private var sessionToken = ""
    private val clientSlots = Semaphore(MAX_CONCURRENT_CLIENTS)

    fun start(context: Context, rootPath: String, onStatusChanged: () -> Unit) {
        if (isLocalActive) return
        this.rootDir = rootPath
        this.sessionToken = generateToken()
        localIpAddress = getLocalIpAddress()
        isLocalActive = true

        thread {
            try {
                serverSocket = ServerSocket(PORT)
                while (isLocalActive) {
                    val socket = serverSocket?.accept() ?: break
                    if (!clientSlots.tryAcquire()) {
                        try {
                            socket.getOutputStream().write("503 Too many clients.\r\n".toByteArray(Charsets.UTF_8))
                            socket.close()
                        } catch (_: Exception) {}
                        continue
                    }
                    thread {
                        try {
                            handleClient(context, socket)
                        } finally {
                            clientSlots.release()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stop(onStatusChanged)
            }
        }
        onStatusChanged()
    }

    fun stop(onStatusChanged: () -> Unit) {
        if (!isLocalActive) return
        isLocalActive = false
        stopInternetTunnel()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        sessionToken = ""
        onStatusChanged()
    }

    fun startInternetTunnel(context: Context, rootPath: String, onStatusChanged: () -> Unit) {
        if (isInternetActive) return
        if (!isLocalActive) {
            start(context, rootPath, onStatusChanged)
        }
        isInternetActive = true
        internetErrorMessage = ""
        internetShareLink = "Establishing secure proxy tunnel..."
        onStatusChanged()

        thread {
            val pinnedVerifier = WebShareTunnelKeys.createPinnedVerifier()
            try {
                com.zenlabs.filemanager.screen.main.tab.files.service.remote.SftpRemoteClient.ensureBouncyCastle()

                sshClient = SSHClient()
                sshClient!!.addHostKeyVerifier(pinnedVerifier)
                sshClient!!.connect("localhost.run", 22)
                val keys = WebShareTunnelKeys.loadKeys(sshClient!!, context)
                sshClient!!.auth(
                    "nokey",
                    net.schmizz.sshj.userauth.method.AuthNone(),
                    net.schmizz.sshj.userauth.method.AuthPublickey(keys)
                )

                // Bind port 80 of proxy server to local port 8080
                sshClient!!.remotePortForwarder.bind(
                    RemotePortForwarder.Forward(80),
                    SocketForwardingConnectListener(InetSocketAddress("127.0.0.1", PORT))
                )

                // Start shell session to read dynallocated domain
                val session = sshClient!!.startSession()
                WebShareTunnelKeys.requestShellNoReply(session)
                val reader = session.inputStream.bufferedReader(Charsets.UTF_8)
                var line: String? = null
                val pattern = Pattern.compile("([a-zA-Z0-9.-]+\\.(localhost\\.run|lhr\\.life))")
                while (isInternetActive && reader.readLine().also { line = it } != null) {
                    val matcher = pattern.matcher(line ?: "")
                    if (matcher.find()) {
                        internetShareLink = "https://" + matcher.group(1)
                        onStatusChanged()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                stopInternetTunnel()
                internetErrorMessage = if (pinnedVerifier.mismatchDetected) {
                    WebShareTunnelHostKeyMismatchException().message
                        ?: "The public tunnel service rotated its security keys. Please update the application to restore public tunnel sharing."
                } else {
                    findTunnelHostKeyMismatch(e)?.message
                        ?: "Unable to establish the secure public tunnel. Please try again."
                }
                onStatusChanged()
            }
        }
    }

    fun stopInternetTunnel() {
        if (!isInternetActive) return
        isInternetActive = false
        internetShareLink = ""
        try {
            sshClient?.close()
        } catch (_: Exception) {}
        sshClient = null
    }

    fun isLocalActive(): Boolean = isLocalActive
    fun isInternetActive(): Boolean = isInternetActive
    fun getLocalUrl(): String = withToken("http://$localIpAddress:$PORT/")
    fun getInternetUrl(): String = withToken(internetShareLink)
    fun getInternetErrorMessage(): String = internetErrorMessage

    private fun findTunnelHostKeyMismatch(error: Throwable): WebShareTunnelHostKeyMismatchException? {
        var current: Throwable? = error
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            if (current is WebShareTunnelHostKeyMismatchException) return current
            current = current.cause
        }
        return null
    }

    private fun withToken(url: String): String {
        if (!url.startsWith("http") || sessionToken.isEmpty()) return url
        val separator = if (url.contains('?')) '&' else '?'
        return "$url${separator}token=$sessionToken"
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    private fun handleClient(context: Context, socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            // Read the entire header block byte-by-byte until we hit \r\n\r\n
            // This avoids BufferedReader pre-consuming POST body bytes
            val headerBytes = readHttpHeaders(input) ?: return
            val headerText = headerBytes.toString(Charsets.UTF_8)
            val headerLines = headerText.split("\r\n")

            // Parse request line
            val requestLine = headerLines.firstOrNull()?.trim() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            if (method != "GET" && method != "POST") {
                sendErrorResponse(output, 405, "Method Not Allowed")
                return
            }
            val requestUri = Uri.parse(parts[1])
            val path = requestUri.path ?: "/"
            val queryToken = requestUri.getQueryParameter("token")

            // Parse headers
            val headers = mutableMapOf<String, String>()
            for (i in 1 until headerLines.size) {
                val hLine = headerLines[i]
                val index = hLine.indexOf(':')
                if (index != -1) {
                    headers[hLine.substring(0, index).trim().lowercase()] = hLine.substring(index + 1).trim()
                }
            }

            val authorized = isAuthorized(headers, queryToken)
            if (!authorized) {
                sendErrorResponse(output, 401, "Unauthorized")
                return
            }
            if (queryToken != null && constantTimeEquals(queryToken, sessionToken)) {
                sendRedirectWithTokenCookie(output, path)
                return
            }

            val targetFile = try {
                PathContainment.resolveUnderRoot(File(rootDir), path.removePrefix("/"))
            } catch (_: SecurityException) {
                sendErrorResponse(output, 403, "Forbidden")
                return
            }

            if (method == "POST") {
                val fileNameHeader = headers["x-file-name"]
                if (fileNameHeader != null) {
                    val decodedFileName = Uri.decode(fileNameHeader)
                    val safeFileName = decodedFileName.substringAfterLast('/').substringAfterLast('\\')
                    if (safeFileName.isBlank() || safeFileName == "." || safeFileName == "..") {
                        sendErrorResponse(output, 400, "Bad Request")
                        return
                    }
                    val targetDirectory = if (targetFile.isDirectory) targetFile else targetFile.parentFile
                        ?: return sendErrorResponse(output, 400, "Bad Request")
                    val uploadDestination = try {
                        PathContainment.resolveUnderRoot(targetDirectory, safeFileName)
                    } catch (_: SecurityException) {
                        sendErrorResponse(output, 403, "Forbidden")
                        return
                    }

                    val contentLength = headers["content-length"]?.toLongOrNull()
                    if (contentLength == null || contentLength < 0L || contentLength > MAX_UPLOAD_BYTES) {
                        sendErrorResponse(output, 413, "Payload Too Large")
                        return
                    }
                    val availableBytes = uploadDestination.parentFile?.usableSpace ?: 0L
                    if (contentLength > 0L && availableBytes < contentLength) {
                        sendErrorResponse(output, 507, "Insufficient Storage")
                        return
                    }

                    val partFile = File(uploadDestination.parentFile, ".${uploadDestination.name}.${UUID.randomUUID()}.part")
                    var completed = false
                    try {
                        partFile.outputStream().use { fos ->
                            val buffer = ByteArray(64 * 1024)
                            var totalRead = 0L
                            while (totalRead < contentLength) {
                                val toRead = minOf(buffer.size.toLong(), contentLength - totalRead).toInt()
                                val read = input.read(buffer, 0, toRead)
                                if (read == -1) break
                                fos.write(buffer, 0, read)
                                totalRead += read
                            }
                            if (totalRead != contentLength) {
                                throw IOException("Incomplete upload body")
                            }
                        }
                        if (!moveAtomically(partFile, uploadDestination)) {
                            throw IOException("Unable to finalize upload")
                        }
                        completed = true
                        sendStringResponse(output, 200, "OK", "Upload successful", "text/plain")
                    } finally {
                        if (!completed) partFile.delete()
                    }
                } else {
                    sendErrorResponse(output, 400, "Bad Request")
                }
                return
            }

            // GET Request Handling
            if (!targetFile.exists()) {
                sendErrorResponse(output, 404, "Not Found")
                return
            }

            if (targetFile.isDirectory) {
                serveDirectoryListing(context, output, path, targetFile)
            } else {
                serveFileStreaming(output, headers, targetFile)
            }
        } catch (e: HeaderTooLargeException) {
            try { sendErrorResponse(output, 431, "Request Header Fields Too Large") } catch (_: Exception) {}
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                sendErrorResponse(output, 500, "Internal Server Error")
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Reads raw HTTP headers byte-by-byte until the \r\n\r\n terminal sequence is found.
     * Returns the header bytes (without the terminal sequence) or null if the stream ends
     * before a complete header block is received.
     *
     * This is critical for correct POST upload handling — using BufferedReader here would
     * cause it to pre-buffer POST body bytes, making them unavailable for later file writing.
     */
    private fun readHttpHeaders(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        var prev3 = 0; var prev2 = 0; var prev1 = 0
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() > 0) buffer.toByteArray() else null
            if (buffer.size() >= MAX_HEADER_BYTES) throw HeaderTooLargeException()
            buffer.write(b)
            // Detect \r\n\r\n
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                // Strip the trailing \r\n\r\n from the buffer
                val full = buffer.toByteArray()
                return full.copyOf(full.size - 4)
            }
            prev3 = prev2; prev2 = prev1; prev1 = b
        }
    }

    internal fun isAuthorized(
        headers: Map<String, String>,
        queryToken: String?,
        expectedToken: String = sessionToken
    ): Boolean {
        if (expectedToken.isEmpty()) return false
        val authorization = headers["authorization"]
        if (authorization != null && authorization.startsWith("Bearer ", ignoreCase = true)) {
            if (constantTimeEquals(authorization.substring(7).trim(), expectedToken)) return true
        }
        if (queryToken != null && constantTimeEquals(queryToken, expectedToken)) return true
        val cookie = headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$TOKEN_COOKIE=") }
        val cookieToken = cookie?.substringAfter("$TOKEN_COOKIE=")
        return cookieToken != null && constantTimeEquals(cookieToken, expectedToken)
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean {
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun sendRedirectWithTokenCookie(output: OutputStream, path: String) {
        val location = encodePath(if (path.startsWith("/")) path else "/$path")
        val pw = PrintWriter(output)
        pw.print("HTTP/1.1 302 Found\r\n")
        pw.print("Location: $location\r\n")
        pw.print("Set-Cookie: $TOKEN_COOKIE=$sessionToken; Path=/; HttpOnly; SameSite=Strict\r\n")
        pw.print("Referrer-Policy: no-referrer\r\n")
        pw.print("Content-Length: 0\r\n")
        pw.print("Cache-Control: no-store\r\n")
        pw.print("\r\n")
        pw.flush()
    }

    private fun moveAtomically(source: File, destination: File): Boolean {
        return try {
            java.nio.file.Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            try {
                java.nio.file.Files.move(
                    source.toPath(),
                    destination.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private class HeaderTooLargeException : Exception()

    private fun serveDirectoryListing(context: Context, output: OutputStream, currentPath: String, directory: File) {
        val files = directory.listFiles() ?: emptyArray()
        // Sort: folders first, then alphabetical
        Arrays.sort(files) { a, b ->
            if (a.isDirectory && !b.isDirectory) -1
            else if (!a.isDirectory && b.isDirectory) 1
            else a.name.lowercase().compareTo(b.name.lowercase())
        }

        // SVG Icons
        val folderSvg = """<svg class="svg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>"""
        val videoSvg = """<svg class="svg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>"""
        val audioSvg = """<svg class="svg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>"""
        val imageSvg = """<svg class="svg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect><circle cx="8.5" cy="8.5" r="1.5"></circle><polyline points="21 15 16 10 5 21"></polyline></svg>"""
        val pdfSvg = """<svg class="svg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line></svg>"""
        val fileSvg = """<svg class="svg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path><polyline points="13 2 13 9 20 9"></polyline></svg>"""
        val backSvg = """<svg class="svg-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 14 4 9 9 4"></polyline><path d="M20 20v-7a4 4 0 0 0-4-4H4"></path></svg>"""
        val downloadSvg = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="7 10 12 15 17 10"></polyline><line x1="12" y1="15" x2="12" y2="3"></line></svg>"""

        val title = if (currentPath == "/") "Root" else currentPath.substringAfterLast("/")

        // Breadcrumbs
        val parts = currentPath.split("/").filter { it.isNotEmpty() }
        var breadcrumbsHtml = """<a href="/">Root</a>"""
        var pathAccumulator = ""
        for (part in parts) {
            pathAccumulator += "/${Uri.encode(part)}"
            breadcrumbsHtml += """ <span class="arrow">></span> <a href="${escapeHtml(pathAccumulator)}">${escapeHtml(part)}</a>"""
        }

        var listHtml = ""
        if (currentPath != "/") {
            val parentPath = if (currentPath.substringBeforeLast("/").isEmpty()) "/" else currentPath.substringBeforeLast("/")
            listHtml += """
                <div class="explorer-item parent-dir" data-type="directory" data-url="${escapeHtml(encodePath(parentPath))}" onclick="handleItemClick(this)">
                  <div class="item-icon-wrapper dir-icon">$backSvg</div>
                  <div class="item-details">
                    <div class="item-name">.. (Parent Directory)</div>
                    <div class="item-meta">Go up one level</div>
                  </div>
                </div>
            """.trimIndent()
        }

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        for (file in files) {
            val name = file.name
            if (name.startsWith(".")) continue

            val isDir = file.isDirectory
            val relativeUrl = if (currentPath == "/") "/${Uri.encode(name)}" else "${encodePath(currentPath)}/${Uri.encode(name)}"
            val escapedName = escapeHtml(name)
            val escapedUrl = escapeHtml(relativeUrl)

            var sizeStr = "-"
            var dateStr = "-"
            var iconClass = "file-icon"
            var mimeType = "application/octet-stream"
            var svgIcon = fileSvg

            if (isDir) {
                iconClass = "dir-icon"
                svgIcon = folderSvg
            } else {
                val sizeBytes = file.length()
                dateStr = df.format(Date(file.lastModified()))

                sizeStr = when {
                    sizeBytes > 1024 * 1024 * 1024 -> "%.2f GB".format(sizeBytes / (1024.0 * 1024 * 1024))
                    sizeBytes > 1024 * 1024 -> "%.1f MB".format(sizeBytes / (1024.0 * 1024))
                    else -> "${sizeBytes / 1024} KB"
                }

                val ext = file.extension.lowercase()
                when (ext) {
                    "mp4", "mkv", "avi", "mov" -> {
                        iconClass = "video-icon"
                        mimeType = "video/mp4"
                        svgIcon = videoSvg
                    }
                    "mp3", "wav", "flac", "m4a" -> {
                        iconClass = "audio-icon"
                        mimeType = "audio/mpeg"
                        svgIcon = audioSvg
                    }
                    "jpg", "jpeg", "png", "gif", "webp" -> {
                        iconClass = "image-icon"
                        mimeType = "image/png"
                        svgIcon = imageSvg
                    }
                    "pdf" -> {
                        iconClass = "pdf-icon"
                        mimeType = "application/pdf"
                        svgIcon = pdfSvg
                    }
                    else -> {
                        iconClass = "file-icon"
                        svgIcon = fileSvg
                    }
                }
            }

            if (isDir) {
                listHtml += """
                    <div class="explorer-item folder-item" data-name="$escapedName" data-type="directory" data-url="$escapedUrl" onclick="handleItemClick(this)">
                      <div class="item-icon-wrapper dir-icon">$svgIcon</div>
                      <div class="item-details">
                        <div class="item-name" title="$escapedName">$escapedName</div>
                      </div>
                    </div>
                """.trimIndent()
            } else {
                listHtml += """
                    <div class="explorer-item file-item" data-name="$escapedName" data-type="file" data-url="$escapedUrl" data-size="$sizeStr" data-modified="$dateStr" data-mime="$mimeType" onclick="handleItemClick(this)">
                      <div class="item-icon-wrapper $iconClass">$svgIcon</div>
                      <div class="item-details">
                        <div class="item-name" title="$escapedName">$escapedName</div>
                        <div class="item-meta">
                          <span class="item-size">$sizeStr</span>
                          <span class="item-sep">•</span>
                          <span class="item-date">$dateStr</span>
                        </div>
                      </div>
                      <div class="item-actions">
                          <button class="action-btn download-btn" data-url="$escapedUrl" data-name="$escapedName" onclick="downloadFileFromElement(this, event)" title="Download File">
                          $downloadSvg
                        </button>
                      </div>
                    </div>
                """.trimIndent()
            }
        }

        val badgeHtml = """
            <div class="header-actions">
              <span class="status-indicator ${if (isInternetActive) "cloud" else "local"}" title="${if (isInternetActive) "Secure Internet Share" else "Local High-Speed Wi-Fi Share"}"></span>
              <button class="header-upload-btn" onclick="triggerFileInput()" title="Upload Files to this Folder">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                <span>Upload</span>
              </button>
            </div>
        """.trimIndent()

        // Read template from assets
        val template = context.assets.open("web_share_portal.html").bufferedReader().use { it.readText() }
        val outputHtml = template
            .replace("${'$'}title", escapeHtml(title))
            .replace("${'$'}badgeHtml", badgeHtml)
            .replace("${'$'}breadcrumbsHtml", breadcrumbsHtml)
            .replace("${'$'}listHtml", listHtml)

        sendStringResponse(output, 200, "OK", outputHtml, "text/html; charset=utf-8")
    }

    private fun serveFileStreaming(output: OutputStream, headers: Map<String, String>, file: File) {
        val ext = file.extension.lowercase()
        val contentType = when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mp3", "m4a", "wav" -> "audio/mpeg"
            "jpg", "jpeg" -> "image/jpeg"
            "png", "gif", "webp" -> "image/png"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }

        val fileSize = file.length()
        val rangeHeader = headers["range"]
        val encodedFilename = Uri.encode(file.name)

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substring(6).split("-")
            var start = 0L
            var end = fileSize - 1

            try {
                if (range[0].isNotEmpty()) start = range[0].toLong()
                if (range.size > 1 && range[1].isNotEmpty()) end = range[1].toLong()
            } catch (_: Exception) {}

            if (start >= fileSize || end >= fileSize || start > end) {
                val pw = PrintWriter(output)
                pw.print("HTTP/1.1 416 Range Not Satisfiable\r\n")
                pw.print("Content-Range: bytes */$fileSize\r\n\r\n")
                pw.flush()
                return
            }

            val contentLength = end - start + 1
            val pw = PrintWriter(output)
            pw.print("HTTP/1.1 206 Partial Content\r\n")
            pw.print("Accept-Ranges: bytes\r\n")
            pw.print("Content-Type: $contentType\r\n")
            pw.print("Content-Length: $contentLength\r\n")
            pw.print("Content-Range: bytes $start-$end/$fileSize\r\n")
            pw.print("Content-Disposition: attachment; filename=\"$encodedFilename\"; filename*=UTF-8''$encodedFilename\r\n")
            pw.print("Cache-Control: no-store\r\n")
            pw.print("X-Content-Type-Options: nosniff\r\n\r\n")
            pw.flush()

            file.inputStream().use { input ->
                input.skip(start)
                var remaining = contentLength
                val buffer = ByteArray(8192)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.flush()
        } else {
            val pw = PrintWriter(output)
            pw.print("HTTP/1.1 200 OK\r\n")
            pw.print("Accept-Ranges: bytes\r\n")
            pw.print("Content-Type: $contentType\r\n")
            pw.print("Content-Length: $fileSize\r\n")
            pw.print("Content-Disposition: attachment; filename=\"$encodedFilename\"; filename*=UTF-8''$encodedFilename\r\n")
            pw.print("Cache-Control: no-store\r\n")
            pw.print("X-Content-Type-Options: nosniff\r\n\r\n")
            pw.flush()

            file.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()
        }
    }

    internal fun escapeHtml(value: String): String = value
        .replace("&", "\u0026amp;")
        .replace("<", "\u0026lt;")
        .replace(">", "\u0026gt;")
        .replace("\"", "\u0026quot;")
        .replace("'", "\u0026#x27;")

    private fun encodePath(path: String): String {
        return "/" + path.split("/").filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) }
    }

    private fun sendStringResponse(output: OutputStream, code: Int, status: String, data: String, contentType: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val pw = PrintWriter(output)
        pw.print("HTTP/1.1 $code $status\r\n")
        pw.print("Content-Type: $contentType\r\n")
        pw.print("Content-Length: ${bytes.size}\r\n")
        pw.print("Cache-Control: no-store\r\n")
        pw.print("X-Content-Type-Options: nosniff\r\n")
        pw.print("Referrer-Policy: no-referrer\r\n")
        pw.print("Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\n")
        pw.print("\r\n")
        pw.flush()
        output.write(bytes)
        output.flush()
    }

    private fun sendErrorResponse(output: OutputStream, code: Int, message: String) {
        sendStringResponse(output, code, message, "$code $message", "text/plain")
    }
}
