package com.radiomesh.test

import android.app.Activity
import android.net.wifi.WifiManager
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
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val startBtn = Button(this).apply { text = "Start / Discover" }
        peerView = TextView(this).apply { text = "Peers: -"; textSize = 14f }
        msgInput = EditText(this).apply { hint = "پیام تست" }
        val sendBtn = Button(this).apply { text = "Send" }
        logView = TextView(this).apply { textSize = 11f; text = "Log:\n" }

        val scroll = ScrollView(this)
        scroll.addView(logView)

        root.addView(startBtn)
        root.addView(peerView)
        root.addView(msgInput)
        root.addView(sendBtn)
        root.addView(scroll)
        setContentView(root)

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("radiomesh").apply {
            setReferenceCounted(true)
            acquire()
        }

        node = MeshNode(
            onLog = { append(it) },
            onMessage = { origin, text -> append("MSG [$origin] $text") },
            onPeer = { ip -> runOnUiThread { peerView.text = "Peers:\n$ip" } }
        )

        startBtn.setOnClickListener {
            Thread { node.start() }.start()
        }

        sendBtn.setOnClickListener {
            val text = msgInput.text.toString().trim()
            if (text.isNotEmpty()) {
                Thread { node.send(text) }.start()
                msgInput.setText("")
            }
        }
    }

    private fun append(s: String) {
        runOnUiThread { logView.append("$s\n") }
    }

    override fun onDestroy() {
        super.onDestroy()
        multicastLock?.release()
        node.stop()
    }
}
