package com.radiomesh.test

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper

class WifiDirectHelper(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit,
    private val onConnectionInfo: (WifiP2pInfo?) -> Unit
) {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var registered = false

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    fun init() {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager?
        if (manager == null) {
            onLog("Wi-Fi Direct پشتیبانی نمی‌شود")
            return
        }
        channel = manager!!.initialize(context, Looper.getMainLooper()) {
            onLog("Wi-Fi Direct channel disconnected")
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        onLog("P2P state: ${if (enabled == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "ON" else "OFF"}")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        manager?.requestConnectionInfo(channel, WifiP2pManager.ConnectionInfoListener { info ->
                            onLog("Connection info: formed=${info.groupFormed} owner=${info.isGroupOwner} " +
                                    "ownerIp=${info.groupOwnerAddress?.hostAddress}")
                            onConnectionInfo(info)
                        })
                    }
                }
            }
        }
    }

    fun register() {
        if (!registered && receiver != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, intentFilter)
            }
            registered = true
        }
    }

    fun unregister() {
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            registered = false
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("Discover started")
            override fun onFailure(reason: Int) = onLog("Discover failed: $reason")
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        manager?.requestPeers(channel) { peers ->
            val list = peers.deviceList.toList()
            onLog("Peers: ${list.size}")
            onPeersChanged(list)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        val config = android.net.wifi.p2p.WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = android.net.wifi.wps.WpsInfo.PBC
        }
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("Connect request sent")
            override fun onFailure(reason: Int) = onLog("Connect failed: $reason")
        })
    }

    @SuppressLint("MissingPermission")
    fun createGroup() {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("Group created (this device is owner)")
            override fun onFailure(reason: Int) = onLog("Create group failed: $reason")
        })
    }

    @SuppressLint("MissingPermission")
    fun removeGroup() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onLog("Group removed")
            override fun onFailure(reason: Int) = onLog("Remove group failed: $reason")
        })
    }
}
