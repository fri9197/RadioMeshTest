package com.radiomesh.test

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MeshNode(
    private val tcpPort: Int = 50505,
    private val udpPort: Int = 50506,
    private val onLog: (String) -> Unit,
    private val onMessage: (String, String) -> Unit,
    private val onPeer: (String) -> Unit
) {
    private val peers = ConcurrentHashMap.newKeySet<String>()
    private val seen = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun myIp(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces()
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    fun start() {
        if (running) return
        running = true
        startTcp()
        startUdp()
        broadcastLoop()
        onLog("STARTED  IP=${myIp() ?: "?"}  TCP=$tcpPort  UDP=$udpPort")
    }

    // ⬇️ متدی که فراموش شده بود — این بار هست
    fun connectTo(host: String) {
        if (peers.add(host)) {
            onLog("PEER added manually: $host")
            onPeer(host)
        } else {
            onLog("PEER already known: $host")
        }
    }

    private fun startTcp() {
        pool.execute {
            try {
                server = ServerSocket(tcpPort)
                onLog("TCP listening on $tcpPort")
                while (running) {
                    val s = server!!.accept()
                    pool.execute { handle(s) }
                }
            } catch (e: Exception) {
                onLog("TCP stop: ${e.message}")
            }
        }
    }

    private fun startUdp() {
        pool.execute {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                sock.broadcast = true
                sock.bind(InetSocketAddress(udpPort))
                udp = sock
                val buf = ByteArray(256)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val msg = String(pkt.data, 0, pkt.length)
                    if (msg.startsWith("RADIO|HELLO|")) {
                        val ip = msg.removePrefix("RADIO|HELLO|").trim()
                        if (ip.isNotEmpty() && ip != myIp() && peers.add(ip)) {
                            onLog("DISCOVERED: $ip")
                            onPeer(ip)
                        }
                    }
                }
            } catch (e: Exception) {
                onLog("UDP stop: ${e.message}")
            }
        }
    }

    private fun broadcastLoop() {
        pool.execute {
            while (running) {
                try {
                    val ip = myIp() ?: continue
                    val msg = "RADIO|HELLO|$ip".toByteArray()
                    val addr = InetAddress.getByName("255.255.255.255")
                    udp?.send(DatagramPacket(msg, msg.size, addr, udpPort))
                } catch (_: Exception) {}
                Thread.sleep(2000)
            }
        }
    }

    fun pingPeer(host: String, onResult: (Boolean) -> Unit) {
        pool.execute {
            val ok = trySend(host, "PING")
            onResult(ok)
        }
    }

    fun send(text: String) {
        val id = UUID.randomUUID().toString()
        seen.add(id)
        onMessage("me", text)
        val line = "MSG|$id|3|${myIp() ?: "me"}|$text"
        for (host in peers) {
            pool.execute {
                if (trySend(host, line)) onLog("TX OK $host")
            }
        }
        if (peers.isEmpty()) onLog("NO PEERS — هنوز کسی کشف نشده")
    }

    private fun handle(socket: Socket) {
        socket.use {
            val line = it.getInputStream().bufferedReader().readLine() ?: return
            val from = it.inetAddress.hostAddress
            if (line == "PING") {
                onLog("RX PING from $from")
                return
            }
            onLog("RX $from")
            process(line, from)
        }
    }

    private fun process(line: String, from: String?) {
        val p = line.split("|", limit = 5)
        if (p.size < 5 || p[0] != "MSG") return
        val id = p[1]
        val ttl = p[2].toIntOrNull() ?: 0
        val origin = p[3]
        val text = p[4]
        if (!seen.add(id)) return
        onMessage(origin, text)
        if (ttl > 0) {
            val line2 = "MSG|$id|${ttl - 1}|$origin|$text"
            for (host in peers) {
                if (host == from) continue
                pool.execute { trySend(host, line2) }
            }
        }
    }

    private fun trySend(host: String, line: String): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, tcpPort), 2000)
            s.use {
                val w = it.getOutputStream().bufferedWriter()
                w.write(line); w.newLine(); w.flush()
            }
            true
        } catch (e: Exception) {
            onLog("TX FAIL $host: ${e.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }
}
