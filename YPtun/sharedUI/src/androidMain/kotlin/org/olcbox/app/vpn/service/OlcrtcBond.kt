package org.olcbox.app.vpn.service

import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket

/**
 * Client-side wire primitives for the Stage-2 olcRTC stream BOND. This is the byte-for-byte counterpart
 * of the Go reference in `olcrtc/internal/bond/bond.go` (and the server `bond-server`): a single reliable
 * byte stream is split across N lanes (independent olcRTC room SOCKS connections) with per-frame sequence
 * numbers and reassembled strictly in order on the far side, so a SINGLE Chain→VLESS flow can aggregate
 * bandwidth across rooms ("many→single→vless") instead of being pinned to one.
 *
 * Wire shape (big-endian), MUST match bond.go:
 *   Hello (17 B): "OLB1"(4) | version(1)=1 | connID(8) | laneIndex(2) | laneCount(2)
 *   Frame (13 B hdr): type(1) [1=DATA,2=FIN] | seq(8) | len(4) | payload(len)
 */
internal object OlcrtcBond {
    const val MAGIC = "OLB1"
    const val VERSION = 1
    const val FRAME_DATA = 1
    const val FRAME_FIN = 2
    const val MAX_CHUNK = 16 * 1024
    const val HELLO_LEN = 17
    const val FRAME_HDR = 13

    /** One bonded DATA/FIN unit, ordered by [seq] within a connID. */
    data class Frame(val type: Int, val seq: Long, val data: ByteArray)

    fun writeHello(out: OutputStream, connId: Long, laneIndex: Int, laneCount: Int) {
        val b = ByteArray(HELLO_LEN)
        MAGIC.encodeToByteArray().copyInto(b, 0)
        b[4] = VERSION.toByte()
        putLong(b, 5, connId)
        putShort(b, 13, laneIndex)
        putShort(b, 15, laneCount)
        out.write(b)
        out.flush()
    }

    fun writeFrame(out: OutputStream, type: Int, seq: Long, data: ByteArray?, len: Int) {
        val hdr = ByteArray(FRAME_HDR)
        hdr[0] = type.toByte()
        putLong(hdr, 1, seq)
        putInt(hdr, 9, len)
        out.write(hdr)
        if (len > 0 && data != null) out.write(data, 0, len)
        out.flush()
    }

    /** Reads one frame; returns null on clean EOF. Throws on a malformed/oversized frame. */
    fun readFrame(input: DataInputStream): Frame? {
        val hdr = ByteArray(FRAME_HDR)
        try {
            input.readFully(hdr)
        } catch (_: EOFException) {
            return null
        }
        val type = hdr[0].toInt() and 0xFF
        val seq = getLong(hdr, 1)
        val size = getInt(hdr, 9)
        if (size < 0 || size > MAX_CHUNK) throw IllegalStateException("bond: bad frame size $size")
        val data = if (size > 0) ByteArray(size).also { input.readFully(it) } else ByteArray(0)
        return Frame(type, seq, data)
    }

    /**
     * Performs a SOCKS5 client handshake on [socket] (username/password auth, falling back to no-auth)
     * and a CONNECT to [host]:[port]. Used to open a lane THROUGH a room's SOCKS to the server bond
     * reassembler. Throws on any protocol failure.
     */
    fun socks5Connect(socket: Socket, user: String, pass: String, host: String, port: Int) {
        val out = socket.getOutputStream()
        val inp = DataInputStream(socket.getInputStream())

        // Greeting: offer username/password (0x02) and no-auth (0x00).
        out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        out.flush()
        val method = ByteArray(2).also { inp.readFully(it) }
        if (method[0].toInt() != 0x05) throw IllegalStateException("socks5: bad version ${method[0]}")
        when (method[1].toInt() and 0xFF) {
            0x00 -> { /* no auth */ }
            0x02 -> {
                val u = user.encodeToByteArray()
                val p = pass.encodeToByteArray()
                val auth = ByteArray(3 + u.size + p.size)
                auth[0] = 0x01
                auth[1] = u.size.toByte()
                u.copyInto(auth, 2)
                auth[2 + u.size] = p.size.toByte()
                p.copyInto(auth, 3 + u.size)
                out.write(auth); out.flush()
                val ar = ByteArray(2).also { inp.readFully(it) }
                if (ar[1].toInt() != 0x00) throw IllegalStateException("socks5: auth rejected")
            }
            else -> throw IllegalStateException("socks5: no acceptable auth method")
        }

        // CONNECT host:port (domain atyp — works for both IP literals and names).
        val h = host.encodeToByteArray()
        val req = ByteArray(4 + 1 + h.size + 2)
        req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
        req[4] = h.size.toByte()
        h.copyInto(req, 5)
        putShort(req, 5 + h.size, port)
        out.write(req); out.flush()

        // Reply: VER REP RSV ATYP BND.ADDR BND.PORT — consume fully.
        val head = ByteArray(4).also { inp.readFully(it) }
        if (head[1].toInt() != 0x00) throw IllegalStateException("socks5: connect failed (rep=${head[1]})")
        val addrLen = when (head[3].toInt() and 0xFF) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> (inp.readUnsignedByte())
            else -> throw IllegalStateException("socks5: bad atyp ${head[3]}")
        }
        inp.readFully(ByteArray(addrLen + 2)) // addr + port
    }

    private fun putShort(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 8) and 0xFF).toByte()
        b[off + 1] = (v and 0xFF).toByte()
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun putLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (56 - i * 8)) and 0xFF).toByte()
    }

    private fun getInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun getLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }
}
