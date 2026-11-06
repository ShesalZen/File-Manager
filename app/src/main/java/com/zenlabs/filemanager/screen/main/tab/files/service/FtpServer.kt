package com.zenlabs.filemanager.screen.main.tab.files.service

import android.os.Environment
import com.zenlabs.filemanager.common.PathContainment
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

private const val MAX_CONCURRENT_SESSIONS = 8
private const val SOCKET_TIMEOUT_MS = 30_000

object FtpServer {
    private var controlSocket: ServerSocket? = null
    private var port = 9999
    private var homeDir = Environment.getExternalStorageDirectory().absolutePath
    private var username = "Anonymous"
    private var password = ""
    private var anonymous = true
    private var allowWrite = false
    private var isActive = false
    private val activeSessions = Collections.synchronizedList(mutableListOf<FtpSession>())
    private val sessionSlots = Semaphore(MAX_CONCURRENT_SESSIONS)

    fun configure(
        port: Int,
        homeDir: String,
        username: String,
        password: String = "",
        anonymous: Boolean = true,
        allowWrite: Boolean = false
    ) {
        this.port = port
        this.homeDir = homeDir
        this.username = username
        this.password = password
        this.anonymous = anonymous
        this.allowWrite = allowWrite
    }

    fun start(onStatusChanged: () -> Unit) {
        if (isActive) return
        if (!anonymous && password.isEmpty()) {
            throw IllegalArgumentException("FTP password is required when anonymous access is disabled")
        }
        isActive = true
        thread {
            try {
                controlSocket = ServerSocket(port)
                while (isActive) {
                    val client = controlSocket?.accept() ?: break
                    if (!sessionSlots.tryAcquire()) {
                        try {
                            client.getOutputStream().write("421 Too many connections.\r\n".toByteArray(Charsets.UTF_8))
                            client.close()
                        } catch (_: Exception) {}
                        continue
                    }
                    val session = FtpSession(client, homeDir, username, password, anonymous, allowWrite)
                    activeSessions.add(session)
                    thread {
                        try {
                            session.run()
                        } finally {
                            activeSessions.remove(session)
                            sessionSlots.release()
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
        if (!isActive) return
        isActive = false
        try {
            controlSocket?.close()
        } catch (_: Exception) {}
        controlSocket = null
        val sessions = synchronized(activeSessions) { activeSessions.toList() }
        for (session in sessions) {
            session.close()
        }
        activeSessions.clear()
        onStatusChanged()
    }

    fun isRunning(): Boolean = isActive
    fun getPort(): Int = port
    fun getHomeDir(): String = homeDir
    fun getUsername(): String = username
    fun isAnonymous(): Boolean = anonymous
    fun allowsWrite(): Boolean = allowWrite

    fun getLocalIpAddress(): String {
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
}

class FtpSession(
    private val controlSocket: Socket,
    private val homeDir: String,
    private val expectedUser: String,
    private val expectedPassword: String,
    private val anonymous: Boolean,
    private val allowWrite: Boolean
) {
    private val reader = BufferedReader(InputStreamReader(controlSocket.getInputStream(), "UTF-8"))
    private val writer = BufferedWriter(OutputStreamWriter(controlSocket.getOutputStream(), "UTF-8"))
    private val root = File(homeDir).canonicalFile
    private var currentDir = "/"
    private var authenticated = false
    private var pendingUser: String? = null
    private var sessionReadOnly = false
    private var passiveServer: ServerSocket? = null
    private var activeHost: String? = null
    private var activePort: Int? = null
    private var renameFromPath: String? = null

    fun run() {
        controlSocket.soTimeout = SOCKET_TIMEOUT_MS
        sendResponse("220 ZenLabs FTP Server ready.")
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                processCommand(line ?: break)
            }
        } catch (e: Exception) {
            // connection reset or closed
        } finally {
            close()
        }
    }

    fun close() {
        try { controlSocket.close() } catch (_: Exception) {}
        try { passiveServer?.close() } catch (_: Exception) {}
    }

    @Synchronized
    private fun sendResponse(response: String) {
        try {
            writer.write(response + "\r\n")
            writer.flush()
        } catch (_: Exception) {}
    }

    private fun virtualPathFor(relPath: String): String {
        return if (relPath.startsWith("/")) {
            relPath
        } else {
            if (currentDir == "/") "/$relPath" else "$currentDir/$relPath"
        }
    }

    private fun resolvePath(relPath: String): File {
        return PathContainment.resolveUnderRoot(root, virtualPathFor(relPath))
    }

    private fun virtualPathUnderRoot(target: File): String {
        val relative = root.toPath().relativize(target.canonicalFile.toPath()).toString()
        return "/" + relative.replace(File.separatorChar, '/').trim('/')
    }

    private fun processCommand(line: String) {
        val parts = line.trim().split(" ")
        val cmd = parts[0].uppercase()
        val arg = if (parts.size > 1) parts.subList(1, parts.size).joinToString(" ") else ""

        if (!authenticated && cmd !in setOf("USER", "PASS", "QUIT", "NOOP", "AUTH")) {
            sendResponse("530 Please login with USER and PASS.")
            return
        }

        try {
            when (cmd) {
                "USER" -> handleUser(arg)
                "PASS" -> handlePassword(arg)
                "AUTH" -> sendResponse("504 AUTH not supported.")
                "SYST" -> sendResponse("215 UNIX Type: L8")
                "FEAT" -> sendResponse("211-Features:\r\n UTF8\r\n211 End")
                "OPTS" -> {
                    if (arg.uppercase() == "UTF8 ON") {
                        sendResponse("200 UTF8 Option Enabled")
                    } else {
                        sendResponse("501 Option not understood")
                    }
                }
                "PWD" -> sendResponse("257 \"$currentDir\" is current directory.")
                "TYPE" -> sendResponse("200 Type set to $arg")
                "PASV" -> enterPassiveMode()
                "PORT" -> enterActiveMode(arg)
                "LIST" -> thread { handleList(arg, detailed = true) }
                "NLST" -> thread { handleList(arg, detailed = false) }
                "CWD" -> handleCwd(arg)
                "CDUP" -> handleCwd("..")
                "SIZE" -> handleSize(arg)
                "RETR" -> thread { handleRetrieve(arg) }
                "STOR" -> {
                    if (!canModify()) return
                    thread { handleStore(arg) }
                }
                "DELE" -> {
                    if (!canModify()) return
                    handleDelete(arg)
                }
                "MKD" -> {
                    if (!canModify()) return
                    handleMakeDir(arg)
                }
                "RMD" -> {
                    if (!canModify()) return
                    handleRemoveDir(arg)
                }
                "RNFR" -> {
                    if (!canModify()) return
                    val from = resolvePath(arg)
                    if (!from.exists()) {
                        sendResponse("550 File not found.")
                    } else {
                        renameFromPath = from.canonicalPath
                        sendResponse("350 File exists, ready for destination name.")
                    }
                }
                "RNTO" -> {
                    if (!canModify()) return
                    handleRenameTo(arg)
                }
                "NOOP" -> sendResponse("200 OK")
                "QUIT" -> {
                    sendResponse("221 Goodbye.")
                    close()
                }
                else -> sendResponse("502 Command not implemented.")
            }
        } catch (_: SecurityException) {
            sendResponse("550 Permission denied.")
        } catch (_: Exception) {
            sendResponse("451 Requested action aborted.")
        }
    }

    private fun handleUser(arg: String) {
        if (authenticated) {
            sendResponse("503 Already logged in.")
            return
        }
        val validUser = if (anonymous) arg.equals("anonymous", ignoreCase = true) else arg == expectedUser
        if (!validUser) {
            pendingUser = null
            sendResponse("530 Login incorrect.")
            return
        }
        pendingUser = arg
        sendResponse("331 User name okay, need password.")
    }

    private fun handlePassword(arg: String) {
        val user = pendingUser
        if (user == null) {
            sendResponse("503 Send USER first.")
            return
        }
        val validPassword = if (anonymous) true else constantTimeEquals(arg, expectedPassword)
        if (!validPassword) {
            pendingUser = null
            sendResponse("530 Login incorrect.")
            return
        }
        pendingUser = null
        authenticated = true
        sessionReadOnly = anonymous || !allowWrite
        sendResponse("230 User logged in, proceed.")
    }

    private fun constantTimeEquals(actual: String, expected: String): Boolean {
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun canModify(): Boolean {
        if (sessionReadOnly) {
            sendResponse("550 File modification not allowed.")
            return false
        }
        return true
    }

    private fun enterPassiveMode() {
        try {
            passiveServer?.close()
            passiveServer = ServerSocket(0)
            val port = passiveServer!!.localPort
            val ipParts = FtpServer.getLocalIpAddress().split(".")
            if (ipParts.size != 4) {
                sendResponse("500 Internal error")
                return
            }
            val p1 = port shr 8
            val p2 = port and 0xFF
            sendResponse("227 Entering Passive Mode (${ipParts.joinToString(",")},$p1,$p2)")
        } catch (e: Exception) {
            sendResponse("451 Local error in processing.")
        }
    }

    private fun enterActiveMode(arg: String) {
        val parts = arg.split(",")
        if (parts.size != 6) {
            sendResponse("501 Syntax error")
            return
        }
        activeHost = parts.subList(0, 4).joinToString(".")
        activePort = (parts[4].toInt() shl 8) + parts[5].toInt()
        sendResponse("200 PORT command successful.")
    }

    private fun getDataSocket(): Socket? {
        if (passiveServer != null) {
            return try {
                passiveServer!!.soTimeout = 10000
                val socket = passiveServer!!.accept()
                passiveServer?.close()
                passiveServer = null
                socket
            } catch (e: Exception) {
                null
            }
        } else if (activeHost != null && activePort != null) {
            return try {
                val socket = Socket(activeHost, activePort!!)
                activeHost = null
                activePort = null
                socket
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun handleList(arg: String, detailed: Boolean) {
        val options = arg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val includeHidden = options.any { it.startsWith("-") && it.contains('a') }
        val requestedPath = options.lastOrNull { !it.startsWith("-") }.orEmpty()
        val target = try {
            resolvePath(requestedPath)
        } catch (_: SecurityException) {
            sendResponse("550 Permission denied.")
            return
        }
        val socket = getDataSocket()
        if (socket == null) {
            sendResponse("425 Can't open data connection.")
            return
        }
        sendResponse("150 Opening ASCII mode data connection for file list.")
        try {
            val files = if (target.isDirectory) target.listFiles() ?: emptyArray() else emptyArray()
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), "UTF-8"))
            val df = SimpleDateFormat("MMM dd HH:mm", Locale.US)
            for (file in files) {
                if (file.name.startsWith(".") && !includeHidden) continue
                if (!detailed) {
                    writer.write("${file.name}\r\n")
                    continue
                }
                val typeChar = if (file.isDirectory) 'd' else '-'
                val permissions = if (file.isDirectory) "rwxr-xr-x" else "rw-r--r--"
                val size = if (file.isDirectory) 4096 else file.length()
                val dateStr = df.format(Date(file.lastModified()))
                writer.write("$typeChar$permissions 1 owner group $size $dateStr ${file.name}\r\n")
            }
            writer.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
            sendResponse("226 Transfer complete.")
        }
    }

    private fun handleCwd(arg: String) {
        val requested = if (arg == ".." && currentDir == "/") "/" else arg
        val dir = resolvePath(requested)
        if (!dir.exists() || !dir.isDirectory) {
            sendResponse("550 Directory not found.")
        } else {
            currentDir = virtualPathUnderRoot(dir)
            sendResponse("250 Directory successfully changed.")
        }
    }

    private fun handleSize(arg: String) {
        val file = resolvePath(arg)
        if (file.exists() && file.isFile) {
            sendResponse("213 ${file.length()}")
        } else {
            sendResponse("550 File not found.")
        }
    }

    private fun handleRetrieve(arg: String) {
        val file = try {
            resolvePath(arg)
        } catch (_: SecurityException) {
            sendResponse("550 Permission denied.")
            return
        }
        if (!file.exists() || !file.isFile) {
            sendResponse("550 File not found.")
            return
        }
        val socket = getDataSocket()
        if (socket == null) {
            sendResponse("425 Can't open data connection.")
            return
        }
        sendResponse("150 Opening BINARY mode data connection.")
        try {
            file.inputStream().use { input ->
                socket.getOutputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
            sendResponse("226 Transfer complete.")
        }
    }

    private fun handleStore(arg: String) {
        val file = try {
            resolvePath(arg)
        } catch (_: SecurityException) {
            sendResponse("550 Permission denied.")
            return
        }
        val socket = getDataSocket()
        if (socket == null) {
            sendResponse("425 Can't open data connection.")
            return
        }
        sendResponse("150 Opening BINARY mode data connection.")
        try {
            socket.getInputStream().use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            socket.close()
            sendResponse("226 Transfer complete.")
        }
    }

    private fun handleDelete(arg: String) {
        val file = resolvePath(arg)
        if (file.exists() && file.isFile) {
            file.delete()
            sendResponse("250 File deleted successfully.")
        } else {
            sendResponse("550 File not found.")
        }
    }

    private fun handleMakeDir(arg: String) {
        val file = resolvePath(arg)
        if (file.mkdirs()) {
            sendResponse("257 \"$arg\" directory created.")
        } else {
            sendResponse("550 Can't create directory.")
        }
    }

    private fun handleRemoveDir(arg: String) {
        val file = resolvePath(arg)
        if (file.exists() && file.isDirectory) {
            file.deleteRecursively()
            sendResponse("250 Directory deleted successfully.")
        } else {
            sendResponse("550 Directory not found.")
        }
    }

    private fun handleRenameTo(arg: String) {
        if (renameFromPath == null) {
            sendResponse("503 Bad sequence of commands.")
            return
        }
        val from = File(renameFromPath!!)
        val to = resolvePath(arg)
        if (from.exists() && from.renameTo(to)) {
            sendResponse("250 File renamed successfully.")
        } else {
            sendResponse("550 File rename failed.")
        }
        renameFromPath = null
    }
}
