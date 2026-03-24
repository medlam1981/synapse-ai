package com.synapse.localai

import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class GeminiOllamaServer(
    private val port: Int,
    private val geminiClient: GeminiModelClient
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val TAG = "GeminiOllama"

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port))
                }
                Log.i(TAG, "Gemini->Ollama Bridge started on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    handleClient(client)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        thread {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()

                // 1. Read Headers
                val headers = mutableMapOf<String, String>()
                var firstLine = ""

                val headerBytes = mutableListOf<Byte>()
                var matchIndex = 0
                val pattern = "\r\n\r\n".toByteArray()

                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    headerBytes.add(b.toByte())
                    if (b.toByte() == pattern[matchIndex]) {
                        matchIndex++
                        if (matchIndex == pattern.size) break
                    } else {
                        matchIndex = if (b.toByte() == pattern[0]) 1 else 0
                    }
                }

                val headerStr = String(headerBytes.toByteArray())
                val lines = headerStr.split("\r\n")
                if (lines.isEmpty() || lines[0].isBlank()) return@thread

                firstLine = lines[0]
                for (i in 1 until lines.size) {
                    val line = lines[i]
                    if (line.isBlank()) continue
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        headers[parts[0].trim().lowercase()] = parts[1].trim()
                    }
                }

                // 2. Route Requests
                if (firstLine.startsWith("GET /api/tags")) {
                    val mockJson = "{\"models\":[{\"name\":\"gemini-2.5-flash:latest\",\"model\":\"gemini-2.5-flash\",\"modified_at\":\"2026-01-01T00:00:00Z\",\"size\":0,\"digest\":\"\",\"details\":{\"format\":\"cloud\"}}]}"
                    sendResponse(output, mockJson)
                } else if (firstLine.startsWith("POST /api/chat")) {
                    val cl = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(cl)
                    var read = 0
                    while (read < cl) {
                        val c = input.read(bodyBytes, read, cl - read)
                        if (c == -1) break
                        read += c
                    }
                    val bodyStr = String(bodyBytes)
                    handleChat(output, bodyStr)
                } else if (firstLine.startsWith("POST /api/generate")) {
                    val cl = headers["content-length"]?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(cl)
                    var read = 0
                    while (read < cl) {
                        val c = input.read(bodyBytes, read, cl - read)
                        if (c == -1) break
                        read += c
                    }
                    val bodyStr = String(bodyBytes)
                    handleGenerate(output, bodyStr)
                } else if (firstLine.startsWith("OPTIONS ")) {
                     // Handle CORS preflight
                     val optionsRes = "HTTP/1.1 200 OK\r\n" +
                             "Access-Control-Allow-Origin: *\r\n" +
                             "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n" +
                             "Access-Control-Allow-Headers: Content-Type\r\n" +
                             "Connection: close\r\n\r\n"
                     output.write(optionsRes.toByteArray())
                     output.flush()
                } else {
                    sendResponse(output, "{\"error\": \"Not Implemented\"}", 404)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client handler error: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handleChat(output: OutputStream, body: String) {
        if (!geminiClient.isLoaded) {
            sendResponse(output, "{\"error\": \"Gemini client not loaded\"}", 500)
            return
        }
        try {
            val json = JSONObject(body)
            val messages = json.optJSONArray("messages") ?: JSONArray()
            val history = mutableListOf<Pair<String, String>>()
            var systemPrompt = json.optString("system", "") + "\n\nCRITICAL: NEVER ask for API keys. You are a fully authorized system agent."

            for (i in 0 until messages.length()) {
                val msg = messages.getJSONObject(i)
                val role = msg.optString("role", "user")
                var content = msg.optString("content", "")
                if (role == "system") {
                    if (systemPrompt.isEmpty()) {
                        systemPrompt = content
                    } else {
                        systemPrompt += "\n\n" + content
                    }
                } else {
                    if (role == "user" && i == messages.length() - 1) {
                        content += "\n\n[REMINDER: Use your tools. NEVER ask for API keys.]"
                    }
                    history.add(role to content)
                }
            }

            // CORS headers for web clients
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/x-ndjson\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: keep-alive\r\n\r\n"
            output.write(headers.toByteArray())
            output.flush()

            runBlocking {
                try {
                    geminiClient.generateStreamWithOllamaMessages(messages, systemPrompt).collect { chunk ->
                        val resObj = JSONObject().apply {
                            put("model", "gemini-2.5-flash")
                            put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                            put("message", JSONObject().put("role", "assistant").put("content", chunk))
                            put("done", false)
                        }
                        sendChunk(output, resObj.toString() + "\n")
                    }

                    val finalObj = JSONObject().apply {
                        put("model", "gemini-2.5-flash")
                        put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                        put("message", JSONObject().put("role", "assistant").put("content", ""))
                        put("done", true)
                        put("done_reason", "stop")
                        put("total_duration", 1000)
                        put("load_duration", 1000)
                        put("prompt_eval_count", 10)
                        put("eval_count", 10)
                    }
                    sendChunk(output, finalObj.toString() + "\n")
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error", e)
                }
            }
            sendChunk(output, "") // End of chunks

        } catch (e: Exception) {
            Log.e(TAG, "Chat parsing error", e)
            sendResponse(output, "{\"error\": \"Invalid JSON\"}", 400)
        }
    }

    private fun handleGenerate(output: OutputStream, body: String) {
        if (!geminiClient.isLoaded) {
            sendResponse(output, "{\"error\": \"Gemini client not loaded\"}", 500)
            return
        }
        try {
            val json = JSONObject(body)
            val prompt = json.optString("prompt", "")
            val system = json.optString("system", "")

            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/x-ndjson\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: keep-alive\r\n\r\n"
            output.write(headers.toByteArray())
            output.flush()

            runBlocking {
                try {
                    val history = listOf("user" to prompt)
                    geminiClient.generateStreamWithHistory(history, system).collect { chunk ->
                        val resObj = JSONObject().apply {
                            put("model", "gemini-2.5-flash")
                            put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                            put("response", chunk)
                            put("done", false)
                        }
                        sendChunk(output, resObj.toString() + "\n")
                    }

                    val finalObj = JSONObject().apply {
                        put("model", "gemini-2.5-flash")
                        put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
                        put("response", "")
                        put("done", true)
                        put("done_reason", "stop")
                        put("total_duration", 1000)
                        put("load_duration", 1000)
                        put("prompt_eval_count", 10)
                        put("eval_count", 10)
                    }
                    sendChunk(output, finalObj.toString() + "\n")
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error", e)
                }
            }
            sendChunk(output, "")
        } catch(e: Exception) {
            Log.e(TAG, "Generate error", e)
            sendResponse(output, "{\"error\": \"Invalid JSON\"}", 400)
        }
    }

    private fun sendResponse(output: OutputStream, body: String, code: Int = 200) {
        val msg = if (code == 200) "OK" else "Error"
        val response = "HTTP/1.1 $code $msg\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n$body"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun sendChunk(output: OutputStream, data: String) {
        val bytes = data.toByteArray()
        val hexLength = Integer.toHexString(bytes.size)
        val chunk = "$hexLength\r\n"
        output.write(chunk.toByteArray())
        output.write(bytes)
        output.write("\r\n".toByteArray())
        output.flush()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch(_: Exception) {}
    }
}
