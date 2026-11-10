package com.shivansh.mytv

import android.os.Process
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import javax.crypto.Cipher

/**
 * Really stops apps, as Settings' Force stop does, when the TV lets it. Android reserves that for
 * system apps, so this goes through the box's own debugging link: a small client for adb's
 * protocol, talking to the adb daemon on this same box.
 *
 * It only ever signs with MY TV's key and never offers it, so the TV never shows its debugging
 * prompt. If the user hasn't allowed the key (and the Mi Box forgets allowed keys when it
 * restarts), nothing happens here and the ordinary close already done has to be enough.
 *
 * It can run exactly one thing, `am force-stop`, for well-formed package names.
 */
class ForceStop(private val dir: File) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "force-stop").apply { isDaemon = true }
    }

    fun stop(packages: List<String>) {
        val names = packages.filter { NAME.matches(it) }
        if (names.isEmpty()) return
        worker.execute {
            try {
                attempt(names)
                Log.i(TAG, "Force stopped $names")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't force stop: $e")
            }
        }
    }

    private fun attempt(names: List<String>) {
        var tries = 0
        while (true) {
            try {
                return run(names)
            } catch (e: ConnectException) {
                // Just after the TV starts, its debugging link takes a little while to come up.
                if (++tries >= TRIES) throw e
                Thread.sleep(BOOT_WAIT_MS)
            }
        }
    }

    private fun run(names: List<String>) {
        val keys = keys()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, PORT), CONNECT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = COMMAND_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = BufferedOutputStream(socket.getOutputStream())
            send(out, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            authenticate(input, out, keys)
            val command = "shell:" + names.joinToString("; ") { "am force-stop $it" } + "\u0000"
            send(out, OPEN, LOCAL_ID, 0, command.toByteArray())
            finish(input, out)
        }
    }

    /** Signs adbd's challenge. A second challenge means the key isn't allowed, and that's the end of it. */
    private fun authenticate(input: DataInputStream, out: OutputStream, keys: KeyPair) {
        var signed = false
        while (true) {
            val message = read(input)
            when (message.command) {
                CNXN -> return
                AUTH -> if (message.arg0 == AUTH_TOKEN) {
                    if (signed) throw IOException("MY TV's key isn't allowed")
                    signed = true
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(message.data, keys))
                }
            }
        }
    }

    /** Answers the shell's output until it closes. */
    private fun finish(input: DataInputStream, out: OutputStream) {
        while (true) {
            val message = read(input)
            when (message.command) {
                WRTE -> send(out, OKAY, LOCAL_ID, message.arg0, EMPTY)
                CLSE -> {
                    send(out, CLSE, LOCAL_ID, message.arg0, EMPTY)
                    return
                }
            }
        }
    }

    private class Message(val command: Int, val arg0: Int, val data: ByteArray)

    private fun read(input: DataInputStream): Message {
        val head = ByteArray(HEADER)
        input.readFully(head)
        val fields = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val command = fields.getInt(0)
        val arg0 = fields.getInt(4)
        val length = fields.getInt(12)
        if (length !in 0..MAX_READ) throw IOException("Bad length")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, data)
    }

    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val head = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        head.putInt(data.sumOf { it.toInt() and 0xFF }).putInt(command xor -1)
        out.write(head.array())
        out.write(data)
        out.flush()
    }

    /** MY TV's own key, made once and kept in its private storage. */
    private fun keys(): KeyPair {
        val private = File(dir, "key")
        val public = File(dir, "key.pub")
        val factory = KeyFactory.getInstance("RSA")
        if (private.exists() && public.exists()) {
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(public.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(private.readBytes())),
            )
        }
        dir.mkdirs()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_WORDS * 32) }.generateKeyPair()
        private.writeBytes(pair.private.encoded)
        public.writeBytes(pair.public.encoded)
        return pair
    }

    /** adb signs its 20-byte token as if it were a SHA-1 digest, so the padding is built by hand. */
    private fun sign(token: ByteArray, keys: KeyPair): ByteArray {
        val padded = ByteArray(KEY_WORDS * 4 - token.size)
        padded[1] = 1
        val prefix = padded.size - SHA1_PREFIX.size
        padded.fill(0xFF.toByte(), 2, prefix)
        SHA1_PREFIX.copyInto(padded, prefix)
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys.private)
            update(padded)
            doFinal(token)
        }
    }

    private companion object {
        const val TAG = "ForceStop"
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        const val CONNECT_MS = 1_500
        const val COMMAND_MS = 15_000
        const val TRIES = 3
        const val BOOT_WAIT_MS = 4_000L
        const val HEADER = 24
        const val MAX_READ = 1024 * 1024
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
        const val LOCAL_ID = 1
        const val KEY_WORDS = 64
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        val EMPTY = ByteArray(0)
        val NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        /** The DER header for a SHA-1 digest, as PKCS#1 puts it before the hash. */
        val SHA1_PREFIX = intArrayOf(0x00, 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
            .map { it.toByte() }.toByteArray()
    }
}
