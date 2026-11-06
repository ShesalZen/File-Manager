package com.zenlabs.filemanager.screen.main.tab.files.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class FtpServerTest {
    @Test
    fun `commands before authentication are rejected`() {
        withSession(anonymous = true, allowWrite = false) { session ->
            session.command("LIST")
            assertTrue(session.response().startsWith("530"))
        }
    }

    @Test
    fun `anonymous session is read only`() {
        withSession(anonymous = true, allowWrite = true) { session ->
            session.command("USER anonymous")
            assertTrue(session.response().startsWith("331"))
            session.command("PASS test@example.test")
            assertTrue(session.response().startsWith("230"))
            session.command("STOR uploaded.txt")
            assertTrue(session.response().contains("550 File modification not allowed"))
        }
    }

    @Test
    fun `non anonymous password must match`() {
        withSession(anonymous = false, allowWrite = true) { session ->
            session.command("USER test")
            assertTrue(session.response().startsWith("331"))
            session.command("PASS wrong")
            assertTrue(session.response().startsWith("530"))
        }
    }

    private fun withSession(
        anonymous: Boolean,
        allowWrite: Boolean,
        block: (FtpTestSession) -> Unit
    ) {
        val root = File.createTempFile("zenlabs-ftp-root", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
        ServerSocket(0).use { server ->
            val accepted = Thread {
                val socket = server.accept()
                FtpSession(
                    socket,
                    root.absolutePath,
                    "test",
                    "secret",
                    anonymous,
                    allowWrite
                ).run()
            }
            accepted.start()
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5000
                val session = FtpTestSession(socket)
                assertTrue(session.response().startsWith("220"))
                block(session)
            }
            accepted.join(TimeUnit.SECONDS.toMillis(2))
        }
        root.deleteRecursively()
    }

    private class FtpTestSession(socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

        fun command(command: String) {
            writer.write("$command\r\n")
            writer.flush()
        }

        fun response(): String = reader.readLine() ?: ""
    }
}
