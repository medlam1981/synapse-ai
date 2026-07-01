package com.synapse.localai

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class OllamaProxy(private val listenPort: Int, private val targetPort: Int, private val host: String) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val TAG = "OllamaProxy"

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    val bindHost = if (host == "0.0.0.0") null else java.net.InetAddress.getByName(host)
                    bind(java.net.InetSocketAddress(bindHost, listenPort))
                }
                Log.i(TAG, "OllamaProxy started on port $listenPort forwarding to $targetPort")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "OllamaProxy error: ${e.message}")
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        thread {
            var targetSocket: Socket? = null
            try {
                val clientIn = clientSocket.getInputStream()
                val clientOut = clientSocket.getOutputStream()
                
                // Read HTTP request headers
                val headerBytes = mutableListOf<Byte>()
                var matchIndex = 0
                val pattern = "\r\n\r\n".toByteArray()
                
                while (true) {
                    val b = clientIn.read()
                    if (b == -1) break
                    headerBytes.add(b.toByte())
                    if (b.toByte() == pattern[matchIndex]) {
                        matchIndex++
                        if (matchIndex == pattern.size) break
                    } else {
                        matchIndex = if (b.toByte() == pattern[0]) 1 else 0
                    }
                }
                
                val requestStr = String(headerBytes.toByteArray())
                val firstLine = requestStr.substringBefore("\r\n")
                
                // INTERCEPT /api/tags TO FIX OLLAMA CLI PARSING ERROR
                if (firstLine.startsWith("GET /api/tags ")) {
                    Log.d(TAG, "Intercepted /api/tags request to inject valid modified_at date.")
                    val mockJson = "{\"models\":[{\"name\":\"local\",\"model\":\"local\",\"modified_at\":\"2026-01-01T00:00:00Z\",\"size\":1000000000,\"digest\":\"\",\"type\":\"model\",\"description\":\"\",\"tags\":[\"\"],\"capabilities\":[\"completion\"],\"parameters\":\"\",\"details\":{\"parent_model\":\"\",\"format\":\"gguf\",\"family\":\"\",\"families\":[\"\"],\"parameter_size\":\"\",\"quantization_level\":\"\"}}],\"object\":\"list\"}"
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${mockJson.length}\r\nConnection: close\r\n\r\n$mockJson"
                    clientOut.write(response.toByteArray())
                    clientOut.flush()
                    clientSocket.close()
                    return@thread
                }
                
                // FOR ALL OTHER REQUESTS: Transparently forward to llama-server
                targetSocket = Socket("127.0.0.1", targetPort)
                val targetIn = targetSocket.getInputStream()
                val targetOut = targetSocket.getOutputStream()
                
                targetOut.write(headerBytes.toByteArray())
                targetOut.flush()
                
                thread { pump(clientIn, targetOut) }
                pump(targetIn, clientOut)
                
            } catch (e: Exception) {
                // Ignore disconnects
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
                try { targetSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        try {
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (e: Exception) {
            // End of stream
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
