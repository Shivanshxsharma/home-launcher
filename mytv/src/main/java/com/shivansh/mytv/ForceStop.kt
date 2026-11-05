package com.shivansh.mytv

import android.os.Process
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import javax.crypto.Cipher

/**
 * Really stops apps, as Settings' Force stop does. Android reserves that for system apps, and the
 * kill an ordinary app may ask for spares anything keeping a service alive, so this goes through
 * the box's own debugging link: a small client for adb's protocol, talking to the adb daemon on
 * this same box. The TV then asks the user to allow MY TV's key. On the Mi Box that prompt closes
 * itself after five seconds, allowing the key once without saving it, and the answer can go to
 * another connection, so nothing works until the user ticks Always allow in those five seconds.
 *
 * It can run exactly one thing, `am force-stop`, for well-formed package names. If the link is off
 * or refused, nothing happens here, and the ordinary kill already done has to be enough.
 */
class ForceStop(private val dir: File) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "force-stop").apply { isDaemon = true }
    }

    /**
     * The key has been offered, so the prompt isn't raised on every card. Later tries only sign,
     * which starts working the moment the user has chosen Always allow.
     */
    @Volatile
    private var offered = false

    /** The switcher opened: one more offer is fine. */
    fun allowOffer() {
        offered = false
    }

    fun stop(packages: List<String>) {
        val names = packages.filter { NAME.matches(it) }
        if (names.isEmpty()) return
        worker.execute {
            try {
                try {
                    run(names)
                } catch (_: SocketTimeoutException) {
                    // The prompt has gone. If Always allow was ticked, the key now simply works.
                    run(names)
                }
                Log.i(TAG, "Force stopped $names")
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't force stop: $e")
            }
        }
    }

    private fun run(names: List<String>) {
        val keys = keys()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, PORT), CONNECT_MS)
            socket.tcpNoDelay = true
            // Long enough for someone to answer the prompt on the TV.
            socket.soTimeout = PROMPT_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val out = BufferedOutputStream(socket.getOutputStream())
            send(out, CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            authenticate(input, out, keys)
            socket.soTimeout = COMMAND_MS
            val command = "shell:" + names.joinToString("; ") { "am force-stop $it" } + "\u0000"
            send(out, OPEN, LOCAL_ID, 0, command.toByteArray())
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
    }

    /** Signs adbd's challenge; a key it doesn't know is then offered, which raises the TV's prompt. */
    private fun authenticate(input: DataInputStream, out: OutputStream, keys: KeyPair) {
        var signed = false
        while (true) {
            val message = read(input)
            when (message.command) {
                CNXN -> return
                AUTH -> if (message.arg0 == AUTH_TOKEN) {
                    if (signed) {
                        if (offered) throw IOException("Waiting for Always allow")
                        offered = true
                        send(out, AUTH, AUTH_PUBLIC_KEY, 0, publicKey(keys.public as RSAPublicKey))
                    } else {
                        signed = true
                        send(out, AUTH, AUTH_SIGNATURE, 0, sign(message.data, keys))
                    }
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

    /** The key in adb's own format: base64 of its Montgomery form, then a name for the prompt. */
    private fun publicKey(key: RSAPublicKey): ByteArray {
        val word = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(KEY_WORDS * 32)
        var n = key.modulus
        var rr = r.modPow(BigInteger.valueOf(2), n)
        val n0inv = n.remainder(word).modInverse(word).negate().toInt()
        val modulus = IntArray(KEY_WORDS)
        val squared = IntArray(KEY_WORDS)
        for (i in 0 until KEY_WORDS) {
            val a = rr.divideAndRemainder(word)
            rr = a[0]
            squared[i] = a[1].toInt()
            val b = n.divideAndRemainder(word)
            n = b[0]
            modulus[i] = b[1].toInt()
        }
        val bytes = ByteBuffer.allocate(8 + KEY_WORDS * 8 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(KEY_WORDS).putInt(n0inv)
        modulus.forEach { bytes.putInt(it) }
        squared.forEach { bytes.putInt(it) }
        bytes.putInt(key.publicExponent.toInt())
        return (Base64.encodeToString(bytes.array(), Base64.NO_WRAP) + " MY TV launcher\u0000").toByteArray()
    }

    private companion object {
        const val TAG = "ForceStop"
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        const val CONNECT_MS = 1_500
        const val PROMPT_MS = 9_000
        const val COMMAND_MS = 15_000
        const val HEADER = 24
        const val MAX_READ = 1024 * 1024
        const val VERSION = 0x01000000
        const val MAX_DATA = 4096
        const val LOCAL_ID = 1
        const val KEY_WORDS = 64
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLIC_KEY = 3
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
