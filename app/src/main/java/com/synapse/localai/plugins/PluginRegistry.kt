package com.synapse.localai.plugins

import android.content.Context
import com.synapse.localai.AtlasLog as Log
import com.synapse.localai.RootShellManager
import com.synapse.localai.ToolHandler
import com.synapse.localai.tools.PythonExecutionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic Modular Plugin Engine & Tool Registry.
 *
 * Enables dynamic discovery, hot-loading, parameter validation, and execution of custom developer tools
 * from `/sdcard/atlas-ai/plugins/` without recompiling the application.
 */
object PluginRegistry {

    private const val TAG = "PluginRegistry"
    private const val EXTERNAL_PLUGIN_DIR = "/sdcard/atlas-ai/plugins"
    private const val LEGACY_PLUGIN_DIR = "/sdcard/Medlam-Ai/plugins"
    private const val FALLBACK_PLUGIN_DIR = "/data/local/tmp/plugins"

    private val plugins = ConcurrentHashMap<String, PluginManifest>()
    @Volatile private var isInitialized = false

    /**
     * Initializes the plugin system and loads all available plugins.
     */
    fun init(context: Context? = null) {
        if (!isInitialized) {
            reloadPlugins(context)
            isInitialized = true
        }
    }

    /**
     * Rescans and reloads all plugin manifests from disk.
     */
    fun reloadPlugins(context: Context? = null) {
        val loaded = mutableListOf<PluginManifest>()
        val primaryDir = File(EXTERNAL_PLUGIN_DIR)
        val legacyDir = File(LEGACY_PLUGIN_DIR)
        val appInternalDir = context?.let { File(it.filesDir, "plugins") }
        val fallbackDir = File(FALLBACK_PLUGIN_DIR)

        listOfNotNull(legacyDir, primaryDir, appInternalDir, fallbackDir).forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { file ->
                    try {
                        val content = file.readText(StandardCharsets.UTF_8).trim()
                        if (content.isNotEmpty()) {
                            val manifest = PluginManifest.fromJson(JSONObject(content))
                            if (manifest.isEnabled && manifest.script.isNotBlank()) {
                                loaded.add(manifest)
                                Log.i(TAG, "Loaded dynamic plugin: ${manifest.name} (v${manifest.version})")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load plugin manifest ${file.name}: ${e.message}")
                    }
                }
            }
        }

        plugins.clear()
        loaded.forEach { plugins[it.name.lowercase()] = it }
        Log.i(TAG, "Total active dynamic plugins: ${plugins.size}")
    }

    /**
     * Returns all registered plugins.
     */
    fun getAllPlugins(): List<PluginManifest> = plugins.values.toList()

    /**
     * Returns a specific plugin by name.
     */
    fun getPlugin(name: String): PluginManifest? = plugins[name.trim().lowercase()]

    /**
     * Checks if a plugin with given name exists.
     */
    fun hasPlugin(name: String): Boolean = plugins.containsKey(name.trim().lowercase())

    /**
     * Executes a dynamic plugin with the provided parameters.
     */
    suspend fun executePlugin(pluginName: String, params: Map<String, String>): ToolHandler.ToolExecutionResult = withContext(Dispatchers.IO) {
        val plugin = getPlugin(pluginName)
            ?: return@withContext ToolHandler.ToolExecutionResult(
                success = false,
                summary = "✖ Plugin [$pluginName] not found in dynamic registry."
            )

        try {
            // Validate required parameters and apply defaults
            val resolvedParams = mutableMapOf<String, String>()
            for (p in plugin.parameters) {
                val value = params[p.name] ?: params[p.name.lowercase()] ?: p.defaultValue
                if (p.required && (value == null || value.isBlank())) {
                    return@withContext ToolHandler.ToolExecutionResult(
                        success = false,
                        summary = "✖ Plugin [${plugin.name}] missing required parameter: ${p.name} (${p.description})"
                    )
                }
                if (value != null) {
                    resolvedParams[p.name] = value
                }
            }
            // Include extra params passed
            params.forEach { (k, v) -> if (!resolvedParams.containsKey(k)) resolvedParams[k] = v }

            // Parameter interpolation in script
            var interpolatedScript = plugin.script
            resolvedParams.forEach { (k, v) ->
                // Sanitize quotes in value for safety
                val safeVal = v.replace("\"", "\\\"").replace("'", "\\'")
                interpolatedScript = interpolatedScript.replace("\${$k}", safeVal)
                    .replace("{$k}", safeVal)
                    .replace("\$$k", safeVal)
            }

            Log.i(TAG, "Executing plugin [${plugin.name}] via ${plugin.executionType}")

            when (plugin.executionType) {
                PluginExecutionType.SHELL -> {
                    val outLines = RootShellManager.execAsync(interpolatedScript)
                    val out = outLines.joinToString("\n").trim()
                    val summary = "✔ Plugin(${plugin.name}) ─── [SHELL EXECUTED]\n$out"
                    ToolHandler.ToolExecutionResult(true, summary)
                }
                PluginExecutionType.PYTHON -> {
                    val pyResult = PythonExecutionEngine.execute(interpolatedScript, autoImportSci = true)
                    val statusTag = if (pyResult.success) "PYTHON EXECUTED" else "PYTHON ERROR"
                    val summary = "✔ Plugin(${plugin.name}) ─── [$statusTag]\n${pyResult.output}"
                    ToolHandler.ToolExecutionResult(pyResult.success, summary)
                }
                PluginExecutionType.INTERNAL -> {
                    ToolHandler.ToolExecutionResult(true, "✔ Plugin(${plugin.name}) [INTERNAL SUCCESS]")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing plugin ${plugin.name}: ${e.message}", e)
            ToolHandler.ToolExecutionResult(false, "✖ Plugin [${plugin.name}] execution failed: ${e.message}")
        }
    }

    /**
     * Generates documentation of all active plugins to inject into the AI agent prompt.
     */
    fun getPluginToolDeclarations(): String {
        if (plugins.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("\n*** DYNAMIC CUSTOM PLUGINS (EXTENDED TOOLSET) ***")
        sb.appendLine("The system dynamically loaded the following custom extensions from $EXTERNAL_PLUGIN_DIR:")
        plugins.values.forEach { plugin ->
            val paramsStr = if (plugin.parameters.isEmpty()) "" else {
                plugin.parameters.joinToString(", ") { p ->
                    "${p.name}: ${p.type}${if (!p.required) " (optional, default=${p.defaultValue ?: "null"})" else ""}"
                }
            }
            sb.appendLine("- run_plugin(name=\"${plugin.name}\", ...): ${plugin.description}. Parameters: [$paramsStr]")
            sb.appendLine("  Alias: ${plugin.name}($paramsStr)")
        }
        return sb.toString()
    }

}
