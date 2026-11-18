package com.leonkernan.adbutil

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Switches the local adbd from USB-only / port 5555 to a TCP port the car firewall lets through
 * (8080, see [CarFirewall]). adbd only honours `tcpip:<port>` requests from an authenticated ADB
 * host, so this talks the standard ADB wire protocol to 127.0.0.1:5555 as a normal host would
 * (CNXN, AUTH with an app-private RSA key, then OPEN "tcpip:<port>").
 *
 * No root, no firewall change: the port must already be whitelisted by the car.
 */
object NetworkAdb {
    const val DEFAULT_PORT = 8080
    const val LOCAL_SOURCE_PORT = 5555

    private val A_CNXN = cmd("CNXN")
    private val A_AUTH = cmd("AUTH")
    private val A_OPEN = cmd("OPEN")
    private val A_OKAY = cmd("OKAY")
    private val A_CLSE = cmd("CLSE")
    private val A_WRTE = cmd("WRTE")
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 4096
    // DER prefix of a SHA-1 DigestInfo; adbd hands us a pre-hashed 20-byte token.
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    data class Outcome(val port: Int, val address: String?)

    /**
     * Blocking; call from Dispatchers.IO. [sourceWaitMs] is how long to wait for adbd to come up on
     * 5555 (useful right after boot / after "Enable ADB"). Throws with a user-readable message on failure. */
    fun enable(context: Context, port: Int = DEFAULT_PORT, sourceWaitMs: Long = 1200): Outcome {
        require(port in 1..65535 && port != LOCAL_SOURCE_PORT)
        if (!waitForPort(LOCAL_SOURCE_PORT, sourceWaitMs)) {
            throw IllegalStateException(
                "adbd is not listening on 127.0.0.1:$LOCAL_SOURCE_PORT — tap \"Enable ADB\" first."
            )
        }
        val keys = loadOrCreateKeyPair(context)
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", LOCAL_SOURCE_PORT), 2500)
            socket.soTimeout = 90_000 // time for the user to approve the RSA prompt on the head unit
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream().buffered()
            try {
                send(output, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
                authenticate(input, output, keys)
                requestTcpip(input, output, port)
            } catch (e: SocketTimeoutException) {
                throw IllegalStateException("Timed out — approve the RSA authorization prompt and retry.")
            }
        }
        if (!waitForPort(port, 15_000)) {
            throw IllegalStateException("tcpip:$port was sent but port $port never opened.")
        }
        return Outcome(port, getWifiIPv4Address()?.hostAddress)
    }

    private fun authenticate(input: InputStream, output: OutputStream, keys: KeyPair) {
        var signed = false
        var keySent = false
        repeat(20) {
            val m = read(input)
            if (m.command == A_CNXN) return
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) return@repeat
            when {
                !signed -> { send(output, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data)); signed = true }
                !keySent -> { send(output, A_AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey(keys.public as RSAPublicKey)); keySent = true }
                else -> send(output, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data))
            }
        }
        throw IllegalStateException("ADB authentication did not complete.")
    }

    private fun requestTcpip(input: InputStream, output: OutputStream, port: Int) {
        val localId = 1
        var remoteId = 0
        send(output, A_OPEN, localId, 0, "tcpip:$port\u0000".toByteArray())
        try {
            repeat(30) {
                val m = read(input)
                if (m.arg1 != localId) return@repeat
                when (m.command) {
                    A_OKAY -> remoteId = m.arg0
                    A_WRTE -> {
                        if (remoteId == 0) remoteId = m.arg0
                        send(output, A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    A_CLSE -> {
                        send(output, A_CLSE, localId, if (remoteId == 0) m.arg0 else remoteId, ByteArray(0))
                        return
                    }
                }
            }
        } catch (_: EOFException) {
            // adbd restarts and drops the transport: expected
        } catch (_: SocketException) {
        }
    }

    private fun loadOrCreateKeyPair(context: Context): KeyPair {
        val priv = File(context.filesDir, "adb_key.pk8")
        val pub = File(context.filesDir, "adb_key.x509")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) {
            runCatching {
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))
                )
            }
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(pair.private.encoded)
        pub.writeBytes(pair.public.encoded)
        return pair
    }

    private fun sign(keys: KeyPair, token: ByteArray): ByteArray {
        require(token.size == 20) { "Unexpected ADB auth token size ${token.size}" }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.private)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /** Android's mincrypt RSAPublicKey blob (2048-bit), base64 + " user@host\0". */
    private fun adbPublicKey(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = two32.subtract(n.modInverse(two32)).and(two32.subtract(BigInteger.ONE))
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val blob = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        blob.putInt(64).putInt(n0inv.toLong().toInt())
        blob.put(littleEndian(n, 256)).put(littleEndian(rr, 256))
        blob.putInt(key.publicExponent.toInt())
        return (Base64.encodeToString(blob.array(), Base64.NO_WRAP) + " adbutil@car\u0000").toByteArray()
    }

    private fun littleEndian(v: BigInteger, size: Int): ByteArray {
        val big = v.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(size, big.size)) out[i] = big[big.size - 1 - i]
        return out
    }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command.inv())
        out.write(h.array())
        out.write(data)
        out.flush()
    }

    private fun read(input: InputStream): Message {
        val h = ByteBuffer.wrap(readFully(input, 24)).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int
        val arg0 = h.int
        val arg1 = h.int
        val length = h.int
        h.int // checksum (not verified)
        val magic = h.int
        check(command.inv() == magic) { "Invalid ADB header" }
        check(length in 0..(1024 * 1024)) { "Invalid ADB payload length $length" }
        return Message(command, arg0, arg1, readFully(input, length))
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(data, off, count - off)
            if (n < 0) throw EOFException("ADB transport closed")
            off += n
        }
        return data
    }

    private fun canConnect(port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    private fun waitForPort(port: Int, maxMs: Long): Boolean {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            if (canConnect(port, 600)) return true
            Thread.sleep(400)
        }
        return false
    }

    private fun cmd(s: String): Int = s[0].code or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)
}
