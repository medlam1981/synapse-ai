package com.synapse.localai

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.*

class PortalServer(private val viewModel: AgentViewModel) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val PORT = 8081
    private val TAG = "PortalServer"

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(PORT))
                }
                Log.i(TAG, "Portal Server started on port $PORT")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Portal Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                val line = reader.readLine() ?: return@thread
                
                Log.d(TAG, "Portal Received: $line")
                val response = runBlocking { processCommand(line) }
                writer.println(response)
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Portal Client error: ${e.message}")
            }
        }
    }

    private suspend fun processCommand(input: String): String {
        return try {
            val json = JSONObject(input)
            val action = json.optString("action", "")
            
            when (action) {
                "get_state" -> {
                    val service = SynapseAccessibilityService.instance
                    val tree = service?.getDroidrunScreenTree() ?: "[No Service]"
                    val state = service?.getDroidrunPhoneState() ?: "[No State]"
                    JSONObject().apply {
                        put("status", "success")
                        put("ui_tree", tree as Any)
                        put("phone_state", state as Any)
                    }.toString()
                }
                "execute" -> {
                    val cmd = json.getString("command")
                    val result = viewModel.runShellInternal(cmd)
                    JSONObject().put("status", "success").put("output", result).toString()
                }
                "ping" -> JSONObject().put("status", "success").put("message", "pong").toString()
                else -> JSONObject().put("status", "error").put("message", "Unknown action").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("message", e.message).toString()
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch(_:Exception) {}
        serverSocket = null
    }
}
