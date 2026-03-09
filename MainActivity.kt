
package com.droidrun.agent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.*

// الالوان الخاصة بهوية Synapse AI
val CyanNeon = Color(0xFF00E5FF)
val DarkBg = Color(0xFF050505)
val PurpleAgent = Color(0xFFBD00FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check for Overlay Permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Please enable Overlay Permission for Synapse HUD", Toast.LENGTH_LONG).show()
        }

        setContent {
            SynapseAITheme {
                AgentScreen()
            }
        }
    }
}

@Composable
fun SynapseAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CyanNeon,
            background = DarkBg,
            surface = Color(0xFF101010)
        ),
        content = content
    )
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isError: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen() {
    var input by remember { mutableStateOf("") }
    val logs = remember { mutableStateListOf<LogEntry>() }
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logs.add(LogEntry("SYSTEM", "Synapse AI Agent Initialized."))
        logs.add(LogEntry("SYSTEM", "Kernel connection: STABLE"))
        logs.add(LogEntry("SYSTEM", "Accessibility HUD: READY"))
        logs.add(LogEntry("SYSTEM", "Autonomous Mode: STANDBY"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        // Header
        TopAppBar(
            title = {
                Column {
                    Text("SYNAPSE AI AGENT", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color.White)
                    Text("AUTONOMOUS MODE ACTIVE", fontSize = 10.sp, color = CyanNeon, letterSpacing = 2.sp)
                }
            },
            actions = {
                IconButton(onClick = { /* Open Settings */ }) {
                    Text("⚙️")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )

        // Stats Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0xFF0A0A0A), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatItem("LATENCY", "12ms")
            StatItem("TASKS", "READY")
            StatItem("LINK", "ONLINE")
        }

        // Terminal Output
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(logs) { log ->
                Column {
                    Text(
                        text = log.sender.uppercase(),
                        color = if (log.sender == "USER") CyanNeon else PurpleAgent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = log.text,
                        color = if (log.isError) Color.Red else Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (isProcessing) {
                item {
                    Text("ANALYZING A11Y_TREE...", color = CyanNeon, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp)
                }
            }
        }

        // Input Area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .background(Color(0xFF111111), RoundedCornerShape(20.dp))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Enter Command...", color = Color.Gray) },
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        textColor = Color.White
                    )
                )
                Button(
                    onClick = {
                        if (input.isNotBlank()) {
                            val currentInput = input
                            logs.add(LogEntry("USER", currentInput))
                            input = ""
                            isProcessing = true
                            // Simulate AI Call
                            scope.launch {
                                // Here you would call GeminiClient.generate(currentInput)
                                kotlinx.coroutines.delay(1500)
                                logs.add(LogEntry("AGENT", "> [Neural Perception] Screen Analyzed. Nodes: 142"))
                                logs.add(LogEntry("AGENT", "> [Accessibility] Executing human-like interaction..."))
                                isProcessing = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanNeon),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("SEND", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White.copy(alpha = 0.3f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
    }
}
