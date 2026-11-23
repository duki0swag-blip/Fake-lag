package com.netlatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

class LagVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private val tunLock = Any()

    @Volatile private var running = false
    @Volatile private var lag = false
    @Volatile private var burstBusy = false
    @Volatile private var lastMs = 0

    private lateinit var tracker: EndpointTracker
    private lateinit var bubble: Bubble
    private val udpFlows = ConcurrentHashMap<String, UdpFlow>()
    private val tcpFlows = ConcurrentHashMap<String, TcpFlow>()

    private inner class UdpFlow(
        val socket: DatagramSocket,
        val clientIp: ByteArray, val clientPort: Int,
        val remoteIp: ByteArray, val remotePort: Int,
        val v6: Boolean
    )

    private inner class TcpFlow(
        val clientIp: ByteArray, val clientPort: Int,
        val remoteIp: ByteArray, val remotePort: Int,
        val v6: Boolean,
        val clientIsn: Long
    ) {
        @Volatile var socket: Socket? = null
        val ourIsn: Long = (Random.nextInt() and 0x7FFFFFFF).toLong()
        @Volatile var sent = 0L
        @Volatile var recv = 0L
        @Volatile var closed = false
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        tracker = EndpointTracker(this)
        tracker.load()
        bubble = Bubble(
            this,
            onTap = { toggleLag() },
            onHide = {
                Prefs.get(this).edit().putBoolean(Prefs.KEY_BUBBLE, false).apply()
                bubble.hide()
                LogBus.add("burbuja oculta")
            }
        )
        running = true
        State.running = true
        LogBus.add("servicio creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LAG -> toggleLag()
            ACTION_TOGGLE_BUBBLE -> {
                val on = !Prefs.get(this).getBoolean(Prefs.KEY_BUBBLE, false)
                Prefs.get(this).edit().putBoolean(Prefs.KEY_BUBBLE, on).apply()
                if (on) bubble.show() else bubble.hide()
                syncNotification()
            }
            ACTION_SETTLE -> {
                syncNotification()
                return START_STICKY
            }
        }

        if (tun == null) {
            if (!establish()) {
                stopSelf()
                return START_NOT_STICKY
            }
            thread(isDaemon = true) { pump() }
            thread(isDaemon = true) { refreshLoop() }
            thread(isDaemon = true) { autoLoop() }
            if (Prefs.get(this).getBoolean(Prefs.KEY_BUBBLE, false) && Settings.canDrawOverlays(this)) {
                bubble.show()
            }
            if (Prefs.get(this).getBoolean(Prefs.KEY_AUTO, false)) toggleLag()
        }
        return START_STICKY
    }

    private fun establish(): Boolean {
        try {
            val b = Builder()
                .setSession("NetLatch")
                .addAddress("10.8.0.2", 32)
                .addRoute("0.0.0.0/0", 0)
                .addAddress("fd00:8::2", 128)
                .addRoute("::/0", 0)
                .setMtu(1500)
            var any = false
            for (pkg in EndpointTracker.PACKAGES) {
                try {
                    b.addAllowedApplication(pkg)
                    any = true
                } catch (_: Exception) {
                }
            }
            if (!any) LogBus.add("sin juego: se tuneliza todo")
            val p = b.establish() ?: run {
                LogBus.add("establish() denegado")
                return false
            }
            tun = p
            tunIn = FileInputStream(p.fileDescriptor)
            tunOut = FileOutputStream(p.fileDescriptor)
            LogBus.add("túnel establecido")
            return true
        } catch (e: Exception) {
            LogBus.add("fallo al establecer: ${e.message}")
            return false
        }
    }

    private fun refreshLoop() {
        while (running) {
            tracker.refresh()
            State.endpoints = tracker.endpoints.size
            try {
                Thread.sleep(2000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun autoLoop() {
        while (running) {
            if (!Prefs.get(this).getBoolean(Prefs.KEY_AUTO, false)) {
                sleep(Random.nextLong(1500, 2500))
                continue
            }
            if (!lag) burst()
            sleep(Random.nextLong(4000, 15000))
        }
    }

    private fun setLag(v: Boolean) {
        lag = v
        State.lag = v
        bubble.setActive(v)
        syncNotification()
    }

    private fun burst() {
        if (burstBusy) return
        burstBusy = true
        val r = Prefs.range(this)
        lastMs = Random.nextInt(r.first, r.last + 1).coerceAtMost(800)
        setLag(true)
        try {
            Thread.sleep(lastMs.toLong())
        } catch (_: InterruptedException) {
        }
        setLag(false)
        burstBusy = false
    }

    fun toggleLag() {
        if (lag) {
            setLag(false)
            burstBusy = false
            return
        }
        if (burstBusy) return
        burstBusy = true
        thread(isDaemon = true) {
            val r = Prefs.range(this)
            lastMs = Random.nextInt(r.first, r.last + 1).coerceAtMost(800)
            setLag(true)
            Thread.sleep(lastMs.toLong())
            setLag(false)
            burstBusy = false
        }
    }

    private fun pump() {
        val buf = ByteArray(65536)
        while (running) {
            val n = try {
                tunIn?.read(buf) ?: -1
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) {
                if (!running) break
                continue
            }
            val pkt = buf.copyOf(n)
            try {
                when ((pkt[0].toInt() shr 4) and 0xF) {
                    4 -> ipv4(pkt)
                    6 -> ipv6(pkt)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun ipv4(p: ByteArray) {
        if (p.size < 28) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < 20 || p.size < ihl + 8) return
        when (p[9].toInt() and 0xFF) {
            17 -> udpIn(p, ihl, p.copyOfRange(12, 16), p.copyOfRange(16, 20), false)
            6 -> tcpIn(p, ihl, p.copyOfRange(12, 16), p.copyOfRange(16, 20), false)
        }
    }

    private fun ipv6(p: ByteArray) {
        if (p.size < 48) return
        when (p[6].toInt() and 0xFF) {
            17 -> udpIn(p, 40, p.copyOfRange(8, 24), p.copyOfRange(24, 40), true)
            6 -> tcpIn(p, 40, p.copyOfRange(8, 24), p.copyOfRange(24, 40), true)
        }
    }

    private fun ipStr(a: ByteArray): String = try {
        InetAddress.getByAddress(a).hostAddress ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun isGame(ip: String, port: Int): Boolean {
        if (tracker.endpoints.isEmpty()) return port >= 1024 && port != 53
        return tracker.endpoints.contains("$ip:$port")
    }

    // ---------------- UDP ----------------

    private fun udpIn(p: ByteArray, off: Int, srcIp: ByteArray, dstIp: ByteArray, v6: Boolean) {
        val sp = Packets.u16(p, off)
        val dp = Packets.u16(p, off + 2)
        val dst = ipStr(dstIp)
        if (lag && isGame(dst, dp)) return

        val payload = p.copyOfRange(off + 8, p.size)
        val key = "${ipStr(srcIp)}:$sp>$dst:$dp"
        var flow = udpFlows[key]
        if (flow == null) {
            val s = DatagramSocket()
            protect(s)
            flow = UdpFlow(s, srcIp, sp, dstIp, dp, v6)
            udpFlows[key] = flow
            val f = flow
            thread(isDaemon = true) { udpOut(f) }
        }
        try {
            flow.socket.send(
                DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), dp)
            )
        } catch (e: Exception) {
            udpFlows.remove(key)
            flow.socket.close()
        }
    }

    private fun udpOut(f: UdpFlow) {
        val buf = ByteArray(65536)
        while (running && !f.socket.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                f.socket.receive(pkt)
            } catch (e: Exception) {
                break
            }
            val remote = ipStr(f.remoteIp)
            if (Prefs.get(this).getBoolean(Prefs.KEY_BIDI, false) && lag &&
                isGame(remote, f.remotePort)
            ) continue
            val out = Packets.udp(
                f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                buf.copyOfRange(pkt.offset, pkt.offset + pkt.length), f.v6
            )
            writeTun(out)
        }
    }

    // ---------------- TCP ----------------

    private fun tcpIn(p: ByteArray, off: Int, srcIp: ByteArray, dstIp: ByteArray, v6: Boolean) {
        val sp = Packets.u16(p, off)
        val dp = Packets.u16(p, off + 2)
        val flags = p[off + 13].toInt() and 0xFF
        val seq = read32(p, off + 4)
        val key = "${ipStr(srcIp)}:$sp>${ipStr(dstIp)}:$dp"

        if (flags and Packets.SYN != 0 && flags and Packets.ACK == 0) {
            val f = TcpFlow(srcIp, sp, dstIp, dp, v6, seq)
            tcpFlows[key] = f
            thread(isDaemon = true) { tcpConnect(f, key) }
            return
        }

        val f = tcpFlows[key] ?: return
        if (flags and Packets.RST != 0) {
            tcpFlows.remove(key)
            closeQuiet(f)
            return
        }

        val dataOff = ((p[off + 12].toInt() shr 4) and 0xF) * 4
        if (p.size > off + dataOff) {
            val data = p.copyOfRange(off + dataOff, p.size)
            if (data.isNotEmpty()) {
                try {
                    f.socket?.outputStream?.write(data)
                    f.recv += data.size
                    writeTun(
                        Packets.tcp(
                            f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                            f.ourIsn + 1 + f.sent, f.clientIsn + 1 + f.recv,
                            Packets.ACK, EMPTY, v6, false
                        )
                    )
                } catch (e: Exception) {
                    tcpFlows.remove(key)
                    closeQuiet(f)
                }
            }
        }

        if (flags and Packets.FIN != 0) {
            tcpFlows.remove(key)
            try {
                writeTun(
                    Packets.tcp(
                        f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                        f.ourIsn + 1 + f.sent, f.clientIsn + 1 + f.recv,
                        Packets.ACK or Packets.FIN, EMPTY, v6, false
                    )
                )
            } catch (_: Exception) {
            }
            closeQuiet(f)
        }
    }

    private fun tcpConnect(f: TcpFlow, key: String) {
        val s = Socket()
        try {
            protect(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(InetAddress.getByAddress(f.remoteIp), f.remotePort), 8000)
            f.socket = s
            writeTun(
                Packets.tcp(
                    f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                    f.ourIsn, f.clientIsn + 1, Packets.SYN or Packets.ACK, EMPTY, f.v6, true
                )
            )
        } catch (e: Exception) {
            writeTun(
                Packets.tcp(
                    f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                    0, f.clientIsn + 1, Packets.RST or Packets.ACK, EMPTY, f.v6, false
                )
            )
            tcpFlows.remove(key)
            closeQuiet(f)
            return
        }

        val buf = ByteArray(16384)
        while (running && !f.closed) {
            val n = try {
                s.inputStream.read(buf)
            } catch (e: Exception) {
                -1
            }
            if (n <= 0) break
            val seg = Packets.tcp(
                f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                f.ourIsn + 1 + f.sent, f.clientIsn + 1 + f.recv, Packets.ACK or Packets.PSH,
                buf.copyOf(n), f.v6, false
            )
            f.sent += n
            writeTun(seg)
        }
        if (!f.closed) {
            try {
                writeTun(
                    Packets.tcp(
                        f.remoteIp, f.clientIp, f.remotePort, f.clientPort,
                        f.ourIsn + 1 + f.sent, f.clientIsn + 1 + f.recv,
                        Packets.ACK or Packets.FIN, EMPTY, f.v6, false
                    )
                )
            } catch (_: Exception) {
            }
        }
        tcpFlows.remove(key)
        closeQuiet(f)
    }

    // ---------------- utilidades ----------------

    private fun read32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun writeTun(pkt: ByteArray) {
        try {
            synchronized(tunLock) { tunOut?.write(pkt) }
        } catch (_: Exception) {
        }
    }

    private fun closeQuiet(f: TcpFlow) {
        f.closed = true
        try {
            f.socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    // ---------------- notificación ----------------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "NetLatch", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, LagVpnService::class.java).setAction(ACTION_TOGGLE_LAG),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, LagVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val txt = when {
            lag -> "Cortando ${lastMs}ms"
            State.endpoints > 0 -> "Listo · ${State.endpoints} endpoints"
            else -> "Esperando tráfico del juego"
        }

        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("NetLatch")
            .setContentText(txt)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Lag", toggle)
            .addAction(0, "Parar", stop)
            .build()
    }

    private fun syncNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        State.running = false
        State.lag = false
        lag = false
        bubble.hide()
        for ((_, f) in udpFlows) {
            try {
                f.socket.close()
            } catch (_: Exception) {
            }
        }
        for ((_, f) in tcpFlows) closeQuiet(f)
        udpFlows.clear()
        tcpFlows.clear()
        try {
            tunIn?.close()
            tunOut?.close()
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        LogBus.add("servicio detenido")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.netlatch.app.STOP"
        const val ACTION_TOGGLE_LAG = "com.netlatch.app.TOGGLE"
        const val ACTION_TOGGLE_BUBBLE = "com.netlatch.app.BUBBLE"
        const val ACTION_SETTLE = "com.netlatch.app.SETTLE"
        private const val CHANNEL = "netlatch"
        private const val NOTIF_ID = 41
        private val EMPTY = ByteArray(0)
    }
}w
