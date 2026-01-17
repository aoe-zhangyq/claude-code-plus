package com.asakii.plugin.mcp

import com.asakii.claude.agent.sdk.mcp.McpServer
import com.asakii.claude.agent.sdk.mcp.McpServerBase
import com.asakii.claude.agent.sdk.mcp.annotations.McpServerConfig
import com.asakii.plugin.mcp.tools.*
import com.asakii.server.mcp.JetBrainsMcpServerProvider
import com.asakii.settings.AgentSettingsService
import com.asakii.settings.McpDefaults
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * JetBrains MCP 服务器实现
 *
 * 提供 IDEA 平台相关的工具，如目录树、文件问题检测、文件索引搜索、代码搜索等。
 * 这些工具利用 IDEA 的强大索引和分析能力，提供比纯文件系统操作更丰富的功能。
 *
 * WSL 模式支持：
 * 当启用 WSL 模式时，工具会自动转换路径格式，确保 CC（运行在 WSL 中）能正确处理 Windows 路径。
 */
@McpServerConfig(
    name = "jetbrains",
    version = "1.0.0",
    description = "JetBrains IDE integration tool server, providing directory browsing, file problem detection, index search, code search and other features"
)
class JetBrainsMcpServerImpl(private val project: Project) : McpServerBase() {

    // WSL 模式配置
    private val wslModeEnabled: Boolean
        get() = AgentSettingsService.getInstance().wslModeEnabled

    // 工具实例
    private lateinit var directoryTreeTool: DirectoryTreeTool
    private lateinit var fileProblemsTool: FileProblemsTool
    private lateinit var fileIndexTool: FileIndexTool
    private lateinit var codeSearchTool: CodeSearchTool
    private lateinit var findUsagesTool: FindUsagesTool
    private lateinit var renameTool: RenameTool
    private lateinit var readFileTool: ReadFileTool

    override fun getSystemPromptAppendix(): String {
        val settings = AgentSettingsService.getInstance()
        val baseInstructions = settings.effectiveJetbrainsInstructions
        val defaultShell = settings.getEffectiveDefaultShell()
        val isWindows = settings.isWindows()

        return buildString {
            appendLine(baseInstructions)
            appendLine()

            if (wslModeEnabled) {
                appendLine("**WSL Mode Enabled:**")
                appendLine("- All file paths returned are in WSL format (e.g., /mnt/d/Develop/Code/project)")
                appendLine("- Input paths in WSL format are automatically converted to Windows format")
            } else if (isWindows) {
                // ============================================================================
                // Windows 环境下的 Shell 类型提示
                // ============================================================================
                //
                // 修改日期: 2025-01-17
                // 修改原因: 告诉 CC 当前使用的 shell 类型，避免生成错误的命令
                // 示例: Git Bash 环境下应使用 rm 而不是 del，路径应为 /d/... 而不是 D:\...
                //
                // 回退方式: 删除或注释下方代码块
                // ============================================================================

                appendLine("**Current Environment:**")
                appendLine("- Platform: Windows")
                appendLine("- Default Shell: $defaultShell")

                when {
                    defaultShell.contains("git-bash", ignoreCase = true) ||
                    defaultShell.contains("bash", ignoreCase = true) -> {
                        appendLine()
                        appendLine("**⚠️ Git Bash Environment:**")
                        appendLine("- Use Unix commands: rm, cp, mv, cat, ls, etc. (NOT Windows: del, copy, move, type)")
                        appendLine("- File paths returned are in Windows format (D:\\path\\to\\file)")
                        appendLine("- When using file paths in commands, convert to Git Bash format:")
                        appendLine("  - D:\\path\\to\\file → /d/path/to/file")
                        appendLine("  - C:\\Users\\... → /c/Users/...")
                        appendLine("- Example: rm -f \"D:\\\\Develop\\\\Code\\\\file.java\" → rm -f \"/d/Develop/Code/file.java\"")
                    }
                    defaultShell.contains("wsl", ignoreCase = true) -> {
                        appendLine()
                        appendLine("**⚠️ WSL Environment:**")
                        appendLine("- Use Unix commands: rm, cp, mv, cat, ls, etc.")
                        appendLine("- File paths returned are in Windows format (D:\\path\\to\\file)")
                        appendLine("- When using file paths in commands, convert to WSL format:")
                        appendLine("  - D:\\path\\to\\file → /mnt/d/path/to/file")
                        appendLine("- Example: rm -f \"D:\\\\Develop\\\\Code\\\\file.java\" → rm -f \"/mnt/d/Develop/Code/file.java\"")
                    }
                    defaultShell.contains("powershell", ignoreCase = true) ||
                    defaultShell.contains("pwsh", ignoreCase = true) -> {
                        appendLine()
                        appendLine("**⚠️ PowerShell Environment:**")
                        appendLine("- Use PowerShell commands: Remove-Item, Copy-Item, Move-Item, Get-Content, etc.")
                        appendLine("- File paths in Windows format are acceptable: D:\\path\\to\\file")
                        appendLine("- Or use PowerShell path format: D:/path/to/file")
                    }
                    defaultShell.contains("cmd", ignoreCase = true) -> {
                        appendLine()
                        appendLine("**⚠️ Command Prompt (CMD) Environment:**")
                        appendLine("- Use Windows commands: del, copy, move, type, etc.")
                        appendLine("- File paths in Windows format: D:\\path\\to\\file")
                    }
                    else -> {
                        appendLine()
                        appendLine("**⚠️ Unknown Shell ($defaultShell):**")
                        appendLine("- Use Unix-style commands as default")
                        appendLine("- File paths may need conversion depending on actual shell")
                    }
                }
            } else {
                appendLine("**Current Environment:**")
                appendLine("- Platform: Unix/Linux/macOS")
                appendLine("- Default Shell: $defaultShell")
                appendLine("- Use Unix commands: rm, cp, mv, cat, ls, etc.")
            }
        }.trimIndent()
    }

    /**
     * 获取需要自动允许的工具列表
     * JetBrains MCP 的所有工具都应该自动允许，因为它们只是读取 IDE 信息
     */
    override fun getAllowedTools(): List<String> = listOf(
        "DirectoryTree",
        "FileProblems",
        "FileIndex",
        "CodeSearch",
        "FindUsages",
        "Rename",
        "ReadFile"
    )

    companion object {
        /**
         * 预加载的工具 Schema（使用 McpDefaults 中的静态定义）
         */
        val TOOL_SCHEMAS: Map<String, Map<String, Any>> = loadAllSchemas()

        /**
         * 从 McpDefaults 加载所有工具 Schema
         */
        private fun loadAllSchemas(): Map<String, Map<String, Any>> {
            logger.info { "📂 [JetBrainsMcpServer] Loading schemas from McpDefaults" }

            return try {
                val json = Json { ignoreUnknownKeys = true }
                val toolsMap = json.decodeFromString<Map<String, JsonObject>>(McpDefaults.JETBRAINS_TOOLS_SCHEMA)
                val result = toolsMap.mapValues { (_, jsonObj) -> jsonObjectToMap(jsonObj) }
                logger.info { "✅ [JetBrainsMcpServer] Loaded ${result.size} tool schemas: ${result.keys}" }
                result
            } catch (e: Exception) {
                logger.error(e) { "❌ [JetBrainsMcpServer] Failed to parse schemas: ${e.message}" }
                emptyMap()
            }
        }

        /**
         * 将 JsonObject 递归转换为 Map<String, Any>
         */
        private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any> {
            return jsonObject.mapValues { (_, value) -> jsonElementToAny(value) }
        }

        /**
         * 将 JsonElement 递归转换为 Any
         */
        private fun jsonElementToAny(element: JsonElement): Any {
            return when (element) {
                is JsonPrimitive -> when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.boolean
                    element.intOrNull != null -> element.int
                    element.longOrNull != null -> element.long
                    element.doubleOrNull != null -> element.double
                    else -> element.content
                }
                is JsonArray -> element.map { jsonElementToAny(it) }
                is JsonObject -> jsonObjectToMap(element)
                is JsonNull -> ""
            }
        }

        /**
         * 获取指定工具的 Schema
         */
        fun getToolSchema(toolName: String): Map<String, Any> {
            return TOOL_SCHEMAS[toolName] ?: run {
                logger.warn { "⚠️ [JetBrainsMcpServer] Tool schema not found: $toolName" }
                emptyMap()
            }
        }
    }

    override suspend fun onInitialize() {
        logger.info { "🔧 Initializing JetBrains MCP Server for project: ${project.name}" }
        logger.info { "🔧 WSL Mode: ${if (wslModeEnabled) "ENABLED" else "DISABLED"}" }

        try {
            // 验证预加载的 Schema
            logger.info { "📋 Using pre-loaded schemas: ${TOOL_SCHEMAS.size} tools (${TOOL_SCHEMAS.keys})" }

            if (TOOL_SCHEMAS.isEmpty()) {
                logger.error { "❌ No schemas loaded! Tools will not work properly." }
            }

            // 初始化工具实例（传递 WSL 模式配置）
            logger.info { "🔧 Creating tool instances..." }
            directoryTreeTool = DirectoryTreeTool(project, wslModeEnabled)
            fileProblemsTool = FileProblemsTool(project, wslModeEnabled)
            fileIndexTool = FileIndexTool(project, wslModeEnabled)
            codeSearchTool = CodeSearchTool(project, wslModeEnabled)
            findUsagesTool = FindUsagesTool(project, wslModeEnabled)
            renameTool = RenameTool(project, wslModeEnabled)
            readFileTool = ReadFileTool(project, wslModeEnabled)
            logger.info { "✅ All tool instances created" }

            // 注册目录树工具（使用预加载的 Schema）
            val directoryTreeSchema = getToolSchema("DirectoryTree")
            logger.info { "📝 DirectoryTree schema: ${directoryTreeSchema.keys}" }
            registerToolFromSchema("DirectoryTree", directoryTreeSchema) { arguments ->
                directoryTreeTool.execute(arguments)
            }

            // 注册文件问题检测工具
            val fileProblemsSchema = getToolSchema("FileProblems")
            logger.info { "📝 FileProblems schema: ${fileProblemsSchema.keys}" }
            registerToolFromSchema("FileProblems", fileProblemsSchema) { arguments ->
                fileProblemsTool.execute(arguments)
            }

            // 注册文件索引搜索工具
            val fileIndexSchema = getToolSchema("FileIndex")
            logger.info { "📝 FileIndex schema: ${fileIndexSchema.keys}" }
            registerToolFromSchema("FileIndex", fileIndexSchema) { arguments ->
                fileIndexTool.execute(arguments)
            }

            // 注册代码搜索工具
            val codeSearchSchema = getToolSchema("CodeSearch")
            logger.info { "📝 CodeSearch schema: ${codeSearchSchema.keys}" }
            registerToolFromSchema("CodeSearch", codeSearchSchema) { arguments ->
                codeSearchTool.execute(arguments)
            }

            // 注册查找引用工具
            val findUsagesSchema = getToolSchema("FindUsages")
            logger.info { "📝 FindUsages schema: ${findUsagesSchema.keys}" }
            registerToolFromSchema("FindUsages", findUsagesSchema) { arguments ->
                findUsagesTool.execute(arguments)
            }

            // 注册重命名工具
            val renameSchema = getToolSchema("Rename")
            logger.info { "📝 Rename schema: ${renameSchema.keys}" }
            registerToolFromSchema("Rename", renameSchema) { arguments ->
                renameTool.execute(arguments)
            }

            // 注册文件读取工具
            val readFileSchema = getToolSchema("ReadFile")
            logger.info { "📝 ReadFile schema: ${readFileSchema.keys}" }
            registerToolFromSchema("ReadFile", readFileSchema) { arguments ->
                readFileTool.execute(arguments)
            }

            logger.info { "✅ JetBrains MCP Server initialized, registered 7 tools" }
        } catch (e: Exception) {
            logger.error(e) { "❌ Failed to initialize JetBrains MCP Server: ${e.message}" }
            throw e
        }
    }
}

/**
 * JetBrains MCP 服务器提供者实现
 *
 * 在 jetbrains-plugin 模块中实现，提供对 IDEA Platform API 的访问。
 */
class JetBrainsMcpServerProviderImpl(private val project: Project) : JetBrainsMcpServerProvider {

    private val _server: McpServer by lazy {
        logger.info { "🔧 Creating JetBrains MCP Server for project: ${project.name}" }
        JetBrainsMcpServerImpl(project).also {
            logger.info { "✅ JetBrains MCP Server instance created" }
        }
    }

    override fun getServer(): McpServer {
        logger.info { "📤 JetBrainsMcpServerProvider.getServer() called" }
        return _server
    }

    /**
     * 获取需要禁用的内置工具列表
     *
     * 当 JetBrains MCP 启用时，禁用内置的 Glob 和 Grep 工具，
     * 因为 JetBrains MCP 的 CodeSearch 和 FileIndex 工具提供更强大的功能。
     */
    override fun getDisallowedBuiltinTools(): List<String> {
        val settings = AgentSettingsService.getInstance()
        // 只有当 JetBrains MCP 启用时才禁用 Glob 和 Grep
        return if (settings.enableJetBrainsMcp) {
            listOf("Glob", "Grep")
        } else {
            emptyList()
        }
    }
}

