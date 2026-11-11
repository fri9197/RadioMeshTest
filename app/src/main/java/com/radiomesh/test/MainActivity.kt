package com.radiomesh.test

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var peerView: TextView
    private lateinit var msgInput: EditText
    private lateinit var node: MeshNode
    private lateinit var wifiDirect: WifiDirectHelper

    private var isGroupOwner = false
    private var groupOwnerIp: String? = null
    private var meshStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val discoverBtn = Button(this).apply { text = "Discover Peers" }
        val createGroupBtn = Button(this).apply { text = "Create Group (من میزبان)" }
        val disconnectBtn = Button(this).apply { text = "Disconnect / Remove Group" }
        peerView = TextView(this).apply { text = "Peers: -"; textSize = 13f }
        msgInput = EditText(this).apply { hint = "پیام تست" }
        val sendBtn = Button(this).apply { text = "Send" }
        logView = TextView(this).apply { textSize = 11f; text = "Log:\n" }

        val scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(discoverBtn)
        root.addView(createGroupBtn)
        root.addView(disconnectBtn)
        root.addView(peerView)
        root.addView(msgInput)
        root.addView(sendBtn)
        root.addView(scroll)
        setContentView(root)

        requestPermissionsIfNeeded()

        node = MeshNode(
            onLog = { append(it) },
            onMessage = { origin, text -> append("MSG [$origin] $text") },
            onPeer = { ip -> runOnUiThread { peerView.text = "Peers:\n$ip" } }
        )

        wifiDirect = WifiDirectHelper(
            context = this,
            onLog = { append("[P2P] $it") },
            onPeersChanged = { peers -> updatePeers(peers) },
            onConnectionInfo = { info -> onP2pConnected(info) }
        )
        wifiDirect.init()

        discoverBtn.setOnClickListener {
            wifiDirect.register()
            wifiDirect.discoverPeers()
        }

        createGroupBtn.setOnClickListener {
            wifiDirect.register()
            wifiDirect.createGroup()
        }

        disconnectBtn.setOnClickListener {
            wifiDirect.removeGroup()
            append("--- disconnected ---")
        }

        sendBtn.setOnClickListener {
            val text = msgInput.text.toString().trim()
            if (text.isNotEmpty()) {
                if (!meshStarted) append("Mesh هنوز شروع نشده — اول Wi-Fi Direct وصل شود")
                Thread { node.send(text) }.start()
                msgInput.setText("")
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (perms.isNotEmpty()) {
            requestPermissions(perms.toTypedArray(), 1001)
        }
    }

    private fun updatePeers(peers: List<WifiP2pDevice>) {
        runOnUiThread {
            if (peers.isEmpty()) {
                peerView.text = "Peers: -"
            } else {
                peerView.text = "Peers (برای اتصال کلیک کن):\n" +
                        peers.joinToString("\n") { "${it.deviceName} (${it.deviceAddress})" }
            }
        }
        peerView.setOnClickListener {
            if (peers.isNotEmpty()) {
                wifiDirect.connect(peers.first())
                append("Connecting to ${peers.first().deviceName}...")
            }
        }
    }

    private fun onP2pConnected(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            append("P2P: group not formed yet")
            return
        }
        isGroupOwner = info.isGroupOwner
        val ownerIp = info.groupOwnerAddress?.hostAddress
        groupOwnerIp = ownerIp
        append("P2P connected: isOwner=$isGroupOwner ownerIp=$ownerIp")

        if (!meshStarted) {
            node.start()
            meshStarted = true
        }

        if (!isGroupOwner && ownerIp != null) {
            node.connectTo(ownerIp)
            append("Mesh peer added (Group Owner): $ownerIp")
        }
    }

    private fun append(s: String) {
        runOnUiThread { logView.append("$s\n") }
    }

    override fun onResume() {
        super.onResume()
        wifiDirect.register()
    }

    override fun onPause() {
        super.onPause()
        wifiDirect.unregister()
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiDirect.unregister()
        node.stop()
    }
}
