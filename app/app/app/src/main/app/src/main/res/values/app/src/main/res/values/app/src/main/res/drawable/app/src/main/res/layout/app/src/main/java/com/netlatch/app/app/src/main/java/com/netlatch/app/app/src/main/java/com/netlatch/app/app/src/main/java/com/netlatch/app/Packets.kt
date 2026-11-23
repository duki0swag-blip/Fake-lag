package com.netlatch.app

import java.util.concurrent.atomic.AtomicInteger

/**
 * Construcción de paquetes IP con checksums correctos.
 * Necesario para reinyectar respuestas UDP y segmentos TCP en el TUN.
 */
object Packets {

    private val ipId = AtomicInteger(1)

    fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v shr 24) and 0xFF).toByte()
        b[o + 1] = ((v shr 16) and 0xFF).toByte()
        b[o + 2] = ((v shr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }

    private fun finish(sum0: Int): Int {
        var s = sum0
        while ((s shr 16) != 0) s = (s and 0xFFFF) + (s shr 16)
        return s.inv() and 0xFFFF
    }

    private fun sumWords(b: ByteArray, off: Int, len: Int): Int {
        var s = 0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    private fun pseudo(srcIp: ByteArray, dstIp: ByteArray, len: Int, proto: Int, v6: Boolean): Int {
        var s = sumWords(srcIp, 0, srcIp.size) + sumWords(dstIp, 0, dstIp.size)
        s += len
        s += proto
        if (v6) s += 6
        return s
    }

    private fun ipHeader(
        p: ByteArray, srcIp: ByteArray, dstIp: ByteArray,
        payloadLen: Int, proto: Int, v6: Boolean
    ): Int {
        if (v6) {
            p[0] = 0x60
            put16(p, 4, payloadLen)
            p[6] = proto.toByte()
            p[7] = 64
            System.arraycopy(srcIp, 0, p, 8, 16)
            System.arraycopy(dstIp, 0, p, 24, 16)
            return 40
        }
        p[0] = 0x45
        put16(p, 2, 20 + payloadLen)
        put16(p, 4, ipId.incrementAndGet() and 0xFFFF)
        put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = proto.toByte()
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        put16(p, 10, finish(sumWords(p, 0, 20)))
        return 20
    }

    fun udp(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        payload: ByteArray, v6: Boolean
    ): ByteArray {
        val udpLen = 8 + payload.size
        val ipLen = if (v6) 40 else 20
        val p = ByteArray(ipLen + udpLen)
        val o = ipHeader(p, srcIp, dstIp, udpLen, 17, v6)
        put16(p, o, srcPort)
        put16(p, o + 2, dstPort)
        put16(p, o + 4, udpLen)
        put16(p, o + 6, 0)
        System.arraycopy(payload, 0, p, o + 8, payload.size)
        var cs = finish(pseudo(srcIp, dstIp, udpLen, 17, v6) + sumWords(p, o, udpLen))
        if (cs == 0) cs = 0xFFFF
        put16(p, o + 6, cs)
        return p
    }

    fun tcp(
        srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray, v6: Boolean, mss: Boolean
    ): ByteArray {
        val hdr = if (mss) 24 else 20
        val ipLen = if (v6) 40 else 20
        val p = ByteArray(ipLen + hdr + payload.size)
        val o = ipHeader(p, srcIp, dstIp, hdr + payload.size, 6, v6)
        put16(p, o, srcPort)
        put16(p, o + 2, dstPort)
        put32(p, o + 4, seq and 0xFFFFFFFFL)
        put32(p, o + 8, ack and 0xFFFFFFFFL)
        p[o + 12] = ((hdr / 4) shl 4).toByte()
        p[o + 13] = flags.toByte()
        put16(p, o + 14, 65535)
        put16(p, o + 16, 0)
        put16(p, o + 18, 0)
        if (mss) {
            p[o + 20] = 2
            p[o + 21] = 4
            put16(p, o + 22, 1460)
        }
        System.arraycopy(payload, 0, p, o + hdr, payload.size)
        put16(
            p, o + 16,
            finish(pseudo(srcIp, dstIp, hdr + payload.size, 6, v6) + sumWords(p, o, hdr + payload.size))
        )
        return p
    }

    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}
