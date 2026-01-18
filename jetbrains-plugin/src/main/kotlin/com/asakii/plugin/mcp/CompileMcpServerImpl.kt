package com.asakii.plugin.mcp

import com.asakii.claude.agent.sdk.mcp.McpServer
import com.asakii.claude.agent.sdk.mcp.McpServerBase
import com.asakii.claude.agent.sdk.mcp.annotations.McpServerConfig
import com.asakii.plugin.mcp.tools.MavenCompileTool
import com.asakii.server.mcp.CompileMcpServerProvider
import com.asakii.settings.AgentSettingsService
import com.asakii.settings.McpDefaults
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Compile MCP 服务器实现
 *
 * 提供 Maven 最终验证功能：
 * - Maven 离线编译：最终验证，适合提交前检查
 *
 * 注意：IDEA 构建功能已合并到 FileProblems (JetBrains MCP) 中
 *
 * WSL 模式支持：
 * 当启用 WSL 模式时，工具会自动转换路径格式。
 */
@McpServerConfig(
    name = "compile",
    version = "1.0.0",
    description = "Maven offline build for final validation - catches cross-file dependency issues that IDEA may miss."
)
class CompileMcpServerImpl(private val project: Project) : McpServerBase() {

    // WSL 模式配置
    private val wslModeEnabled: Boolean
        get() = AgentSettingsService.getInstance().wslModeEnabled

    // 工具实例
    private lateinit var mavenCompileTool: MavenCompileTool

    override fun getSystemPromptAppendix(): String {
        val baseInstructions = getCompileInstructions()
        return if (wslModeEnabled) {
            """
            $baseInstructions

            **WSL Mode Enabled:**
            - All file paths returned are in WSL format (e.g., /mnt/d/Develop/Code/project)
            - Input paths in WSL format are automatically converted to Windows format
            """.trimIndent()
        } else {
            baseInstructions
        }
    }

    override fun getAllowedTools(): List<String> = listOf(
        "MavenCompile"
    )

    companion object {
        val TOOL_SCHEMAS: Map<String, Map<String, Any>> = loadCompileSchemas()

        private fun loadCompileSchemas(): Map<String, Map<String, Any>> {
            logger.info { "📂 [CompileMcpServer] Loading compile tool schemas" }

            return try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val jsonElement = json.decodeFromString<kotlinx.serialization.json.JsonElement>(McpDefaults.COMPILE_TOOLS_SCHEMA)
                val toolsMap = jsonElement.jsonObject
                val result = toolsMap.mapValues { (_, jsonObj) -> jsonObjectToMap(jsonObj.jsonObject) }
                logger.info { "✅ [CompileMcpServer] Loaded ${result.size} tool schemas: ${result.keys}" }
                result
            } catch (e: Exception) {
                logger.error(e) { "❌ [CompileMcpServer] Failed to parse schemas: ${e.message}" }
                emptyMap()
            }
        }

        private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
            return jsonObject.mapValues { (_, value) -> jsonElementToAny(value) }
        }

        private fun jsonElementToAny(element: JsonElement): Any {
            return when (element) {
                is JsonPrimitive -> when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content.toIntOrNull() != null -> element.content.toInt()
                    element.content.toLongOrNull() != null -> element.content.toLong()
                    element.content.toDoubleOrNull() != null -> element.content.toDouble()
                    else -> element.content
                }
                is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToAny(it) }
                is JsonObject -> jsonObjectToMap(element)
                is kotlinx.serialization.json.JsonNull -> ""
            }
        }

        fun getToolSchema(toolName: String): Map<String, Any> {
            return TOOL_SCHEMAS[toolName] ?: run {
                logger.warn { "⚠️ [CompileMcpServer] Tool schema not found: $toolName" }
                emptyMap()
            }
        }

        private fun getCompileInstructions(): String {
            val language = AgentSettingsService.getInstance().promptLanguage
            return McpDefaults.getCompileInstructions(language)
        }
    }

    override suspend fun onInitialize() {
        logger.info { "🔧 Initializing Compile MCP Server for project: ${project.name}" }
        logger.info { "🔧 WSL Mode: ${if (wslModeEnabled) "ENABLED" else "DISABLED"}" }

        try {
            if (TOOL_SCHEMAS.isEmpty()) {
                logger.error { "❌ No schemas loaded! Tools will not work properly." }
            }

            // 初始化工具实例
            logger.info { "🔧 Creating compile tool instances..." }
            mavenCompileTool = MavenCompileTool(project, wslModeEnabled)
            logger.info { "✅ All compile tool instances created" }

            // 注册 Maven 编译工具
            val mavenCompileSchema = getToolSchema("MavenCompile")
            registerToolFromSchema("MavenCompile", mavenCompileSchema) { arguments ->
                mavenCompileTool.execute(arguments)
            }

            logger.info { "✅ Compile MCP Server initialized, registered MavenCompile tool" }
        } catch (e: Exception) {
            logger.error(e) { "❌ Failed to initialize Compile MCP Server: ${e.message}" }
            throw e
        }
    }
}

/**
 * Compile MCP 服务器提供者实现
 */
class CompileMcpServerProviderImpl(private val project: Project) : CompileMcpServerProvider {

    private val _server: McpServer by lazy {
        logger.info { "🔧 Creating Compile MCP Server for project: ${project.name}" }
        CompileMcpServerImpl(project).also {
            logger.info { "✅ Compile MCP Server instance created" }
        }
    }

    override fun getServer(): McpServer {
        logger.info { "📤 CompileMcpServerProvider.getServer() called" }
        return _server
    }
}
