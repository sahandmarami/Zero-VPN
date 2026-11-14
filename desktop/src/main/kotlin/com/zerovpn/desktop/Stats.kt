package com.zerovpn.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

// ---------------------------------------------------------------------------
// Live traffic stats — a hand-rolled, dependency-free gRPC (h2c) client for
// Xray's local StatsService.
//
// The xray config gains a small loopback API inbound (dokodemo-door tagged
// "api") plus the `stats` service and system policies. Every second while the
// tunnel is up, we query `StatsService/QueryStats` on 127.0.0.1:<apiPort> and
// read the per-inbound uplink/downlink counters — the same source v2rayN uses.
// Speed is the 1 s delta; session totals accumulate over the connection.
//
// The HTTP/2 subset implemented here is exactly what a loopback gRPC call
// needs: connection preface, one SETTINGS (+ACK), HPACK literal headers
// (no indexing / no Huffman — fully legal per RFC 7541), one DATA frame,
// then collect DATA frames until END_STREAM. Any failure is swallowed and the
// UI just keeps the previous values; stats never crash the app.
// ---------------------------------------------------------------------------

/** Live traffic state observed by the home screen (written on Main only). */
object TrafficTracker {
    var upSpeed by mutableStateOf(0L)          // bytes / second
    var downSpeed by mutableStateOf(0L)        // bytes / second
    var upTotal by mutableStateOf(0L)          // session bytes
    var downTotal by mutableStateOf(0L)        // session bytes
    var lastSampleUp by mutableStateOf(0L)     // raw counters from the core
    var lastSampleDown by mutableStateOf(0L)

    fun reset() {
        upSpeed = 0; downSpeed = 0
        upTotal = 0; downTotal = 0
        lastSampleUp = 0; lastSampleDown = 0
    }

    /** Fold a new counter sample into speeds + session totals (Main thread). */
    fun sample(up: Long, down: Long) {
        if (up >= lastSampleUp && lastSampleUp > 0) {
            upSpeed = up - lastSampleUp
            upTotal += upSpeed
        }
        if (down >= lastSampleDown && lastSampleDown > 0) {
            downSpeed = down - lastSampleDown
            downTotal += downSpeed
        }
        lastSampleUp = up
        lastSampleDown = down
    }
}

object StatsClient {

    /** Sum of socks+http inbound counters; null = stats unavailable. */
    fun queryInboundTraffic(port: Int): Pair<Long, Long>? = try {
        Socket().use { sock ->
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress("127.0.0.1", port), 1200)
            sock.soTimeout = 2500
            val input = DataInputStream(sock.getInputStream().buffered())
            val output = DataOutputStream(sock.getOutputStream().buffered())

            // --- HTTP/2 connection preface ----------------------------------
            output.writeBytes("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")
            writeFrame(output, F_SETTINGS, 0, 0, ByteArray(0))
            output.flush()

            // --- gRPC request -----------------------------------------------
            val path = "/xray.app.stats.command.StatsService/QueryStats"
            val headers = hpackHeaders(mapOf(
                ":method" to "POST",
                ":scheme" to "http",
                ":path" to path,
                ":authority" to "127.0.0.1:$port",
                "content-type" to "application/grpc",
                "te" to "trailers",
                "user-agent" to "ZeroVPN-grpc/$APP_VERSION",
            ))
            writeFrame(output, F_HEADERS, FLAG_END_HEADERS, 1, headers)
            // gRPC message frame: 1 byte compressed flag + 4 byte length + body.
            // QueryStatsRequest is an EMPTY protobuf message → zero-length body.
            val grpcMsg = byteArrayOf(0, 0, 0, 0, 0)
            writeFrame(output, F_DATA, FLAG_END_STREAM, 1, grpcMsg)
            output.flush()

            // --- Response loop ------------------------------------------------
            val data = ByteArrayOutputStream()
            var grpcBody: ByteArray? = null
            var sawEnd = false
            var guard = 0
            while (!sawEnd && guard++ < 64) {
                val hdr = readFrameHeader(input) ?: break
                val payload = ByteArray(hdr.length).also { input.readFully(it) }
                when (hdr.type) {
                    F_SETTINGS -> if (hdr.flags and FLAG_ACK == 0) {
                        writeFrame(output, F_SETTINGS, FLAG_ACK, 0, ByteArray(0)); output.flush()
                    }
                    F_PING -> {
                        writeFrame(output, F_PING, FLAG_ACK, 0, payload); output.flush()
                    }
                    F_DATA -> {
                        data.write(payload)
                        if (hdr.flags and FLAG_END_STREAM != 0) sawEnd = true
                    }
                    F_HEADERS -> if (hdr.flags and FLAG_END_STREAM != 0) sawEnd = true
                    F_RST_STREAM, F_GOAWAY -> sawEnd = true
                }
            }
            val all = data.toByteArray()
            if (all.size >= 5) grpcBody = all.copyOfRange(5, all.size)
            grpcBody?.let { parseStats(it) }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Aggregate uplink/downlink of the tunnel inbounds (socks + http) from a
     * QueryStatsResponse protobuf:
     *   message Stat { string name = 1; uint64 value = 2; }
     *   message QueryStatsResponse { repeated Stat stat = 1; }
     */
    fun parseStats(body: ByteArray): Pair<Long, Long>? {
        if (body.isEmpty()) return null
        var up = 0L; var down = 0L; var seen = false
        var i = 0
        while (i < body.size) {
            val tag = readVarint(body, i); if (tag < 0) break; i += varintLen(body, i)
            if (tag shr 3 == 1L && (tag and 7) == 2L) {          // field 1, length-delimited
                val flen = readVarint(body, i); if (flen < 0) break; i += varintLen(body, i)
                val end = (i + flen.toInt()).coerceAtMost(body.size)
                var name: String? = null; var value = 0L
                var j = i
                while (j < end) {
                    val t2 = readVarint(body, j); if (t2 < 0) break; j += varintLen(body, j)
                    when {
                        t2 shr 3 == 1L && (t2 and 7) == 2L -> {  // name (string)
                            val l2 = readVarint(body, j); if (l2 < 0) return null
                            j += varintLen(body, j)
                            val l = l2.toInt().coerceAtMost(body.size - j)
                            name = String(body, j, l, Charsets.UTF_8)
                            j += l
                        }
                        t2 shr 3 == 2L && (t2 and 7) == 0L -> {  // value (uint64 varint)
                            var v = 0L; var shift = 0
                            while (j < end) {
                                val b = body[j].toInt(); j++
                                v = v or ((b and 0x7F).toLong() shl shift)
                                if (b and 0x80 == 0) break
                                shift += 7; if (shift > 63) break
                            }
                            value = v
                        }
                        else -> return null
                    }
                }
                if (name != null && name.endsWith("uplink")) {
                    if (name.startsWith("inbound>>>socks-in") || name.startsWith("inbound>>>http-in")) {
                        up += value; seen = true
                    }
                } else if (name != null && name.endsWith("downlink")) {
                    if (name.startsWith("inbound>>>socks-in") || name.startsWith("inbound>>>http-in")) {
                        down += value; seen = true
                    }
                }
                i = end
            } else {
                // skip unknown field
                when (tag and 7) {
                    0L -> { val v = readVarint(body, i); if (v < 0) break; i += varintLen(body, i) }
                    1L -> i += 8
                    2L -> { val l = readVarint(body, i); if (l < 0) break; i += varintLen(body, i) + l.toInt() }
                    5L -> i += 4
                    else -> break
                }
            }
        }
        return if (seen) up to down else null
    }

    // --- protobuf helpers ----------------------------------------------------
    private fun readVarint(b: ByteArray, off: Int): Long {
        var v = 0L; var shift = 0
        var i = off
        while (i < b.size) {
            val x = b[i].toInt(); i++
            v = v or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return v
            shift += 7
            if (shift > 63) return -1
        }
        return -1
    }

    private fun varintLen(b: ByteArray, off: Int): Int {
        var n = 0; var i = off
        while (i < b.size) { n++; if (b[i].toInt() and 0x80 == 0) break; i++ }
        return n.coerceAtLeast(1)
    }

    // --- HTTP/2 frames ---------------------------------------------------------
    private const val F_DATA = 0x0
    private const val F_HEADERS = 0x1
    private const val F_RST_STREAM = 0x3
    private const val F_SETTINGS = 0x4
    private const val F_PING = 0x6
    private const val F_GOAWAY = 0x7
    private const val FLAG_ACK = 0x1
    private const val FLAG_END_HEADERS = 0x4
    private const val FLAG_END_STREAM = 0x1

    private fun writeFrame(out: DataOutputStream, type: Int, flags: Int, stream: Int, payload: ByteArray) {
        out.writeByte((payload.size ushr 16) and 0xFF)
        out.writeByte((payload.size ushr 8) and 0xFF)
        out.writeByte(payload.size and 0xFF)
        out.writeByte(type and 0xFF)
        out.writeByte(flags and 0xFF)
        out.writeInt(stream)
        out.write(payload)
    }

    private class FrameHeader(val length: Int, val type: Int, val flags: Int)

    private fun readFrameHeader(input: DataInputStream): FrameHeader? {
        val b0 = input.read(); val b1 = input.read(); val b2 = input.read()
        if (b0 < 0 || b1 < 0 || b2 < 0) return null
        val len = (b0 shl 16) or (b1 shl 8) or b2
        val type = input.read(); if (type < 0) return null
        val flags = input.read(); if (flags < 0) return null
        input.readInt()   // stream id — single-stream client, value unused
        return FrameHeader(len, type, flags)
    }

    /**
     * HPACK: literal header field WITHOUT indexing, new name, raw strings —
     * 0x00 | len(name) | name | len(value) | value. Legal per RFC 7541 §6.2.2
     * and accepted by Go's hpack (xray's HTTP/2 stack).
     */
    private fun hpackHeaders(h: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        h.forEach { (name, value) ->
            out.write(0x00)
            writeHpackString(out, name)
            writeHpackString(out, value)
        }
        return out.toByteArray()
    }

    private fun writeHpackString(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        // length < 127 → single byte, no Huffman (high bit clear)
        require(b.size < 127) { "hpack string too long" }
        out.write(b.size)
        out.write(b)
    }
}
