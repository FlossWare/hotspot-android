package org.flossware.hotspot.security

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Wraps a plain [Socket] with a [SecureChannel] to provide transparent
 * encryption/decryption of all data flowing through it.
 *
 * Usage:
 * ```
 * val channel = SecureChannel.handshakeAsInitiator(...)
 * val secure = SecureSocket(rawSocket, channel)
 * secure.getOutputStream().write(data)  // encrypted on the wire
 * val plain = secure.getInputStream().read(buf) // decrypted from the wire
 * ```
 *
 * The encrypted stream is frame-based: each write produces one frame
 * (header + nonce + ciphertext + tag) and each read returns the plaintext
 * of exactly one frame.
 */
class SecureSocket(
    private val socket: Socket,
    private val channel: SecureChannel,
) : Closeable {

    private val encryptedInput by lazy {
        SecureInputStream(socket.getInputStream(), channel)
    }

    private val encryptedOutput by lazy {
        SecureOutputStream(socket.getOutputStream(), channel)
    }

    /** Returns an [InputStream] that decrypts frames read from the socket. */
    fun getInputStream(): InputStream = encryptedInput

    /** Returns an [OutputStream] that encrypts data written to the socket. */
    fun getOutputStream(): OutputStream = encryptedOutput

    /** Whether the underlying socket is connected. */
    val isConnected: Boolean get() = socket.isConnected && !socket.isClosed

    override fun close() {
        socket.close()
    }
}

/**
 * An [InputStream] that reads encrypted frames from the underlying stream
 * and returns decrypted plaintext to the caller.
 *
 * Internally buffers the plaintext of one frame at a time; successive
 * [read] calls consume the buffer before reading the next frame.
 */
internal class SecureInputStream(
    private val raw: InputStream,
    private val channel: SecureChannel,
) : InputStream() {

    private var buffer: ByteArray = ByteArray(0)
    private var pos: Int = 0
    private companion object { const val BYTE_MASK = 0xFF }

    @Synchronized
    override fun read(): Int {
        if (!refill()) return -1
        return buffer[pos++].toInt() and BYTE_MASK
    }

    @Synchronized
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!refill()) return -1
        val available = buffer.size - pos
        val count = minOf(len, available)
        System.arraycopy(buffer, pos, b, off, count)
        pos += count
        return count
    }

    private fun refill(): Boolean {
        if (pos < buffer.size) return true
        return try {
            buffer = channel.readDecrypted(raw)
            pos = 0
            buffer.isNotEmpty()
        } catch (_: java.io.IOException) {
            false
        }
    }

    override fun close() = raw.close()
}

/**
 * An [OutputStream] that encrypts each [write] call as a single frame
 * on the underlying stream.
 */
internal class SecureOutputStream(
    private val raw: OutputStream,
    private val channel: SecureChannel,
) : OutputStream() {

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
        channel.writeEncrypted(raw, data)
    }

    override fun flush() = raw.flush()

    override fun close() = raw.close()
}
