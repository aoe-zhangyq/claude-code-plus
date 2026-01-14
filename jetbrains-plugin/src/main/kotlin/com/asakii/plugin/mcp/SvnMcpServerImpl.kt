package com.asakii.plugin.mcp

import com.asakii.claude.agent.sdk.mcp.McpServer
import com.asakii.claude.agent.sdk.mcp.McpServerBase
import com.asakii.claude.agent.sdk.mcp.annotations.McpServerConfig
import com.asakii.plugin.mcp.svn.*
import com.asakii.server.mcp.SvnMcpServerProvider
import com.asakii.settings.AgentSettingsService
import com.asakii.settings.McpDefaults
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * SVN MCP 服务器实现
 *
 * 提供 IDEA VCS/SVN 集成工具，如获取变更、提交历史、读写 commit message 等。
 */
@McpServerConfig(
    name = "jetbrains_svn",
    version = "1.0.0",
    description = "JetBrains IDE SVN/VCS integration tools for commit message generation and VCS operations"
)
class SvnMcpServerImpl(private val project: Project) : McpServerBase() {

    // 工具实例
    private lateinit var getVcsChangesTool: GetVcsChangesTool
    private lateinit var getSvnHistoryTool: GetSvnHistoryTool
    private lateinit var getCommitMessageTool: GetCommitMessageTool
    private lateinit var setCommitMessageTool: SetCommitMessageTool
    private lateinit var getSvnStatusTool: GetSvnStatusTool

    override fun getSystemPromptAppendix(): String {
        return AgentSettingsService.getInstance().effectiveSvnInstructions
    }

    /**
     * 获取需要自动允许的工具列表
     * SVN MCP 的所有工具都应该自动允许
     */
    override fun getAllowedTools(): List<String> = listOf(
        "GetVcsChanges",
        "GetSvnHistory",
        "GetCommitMessage",
        "SetCommitMessage",
        "GetSvnStatus"
    )

    companion object {
        /**
         * 预加载的工具 Schema
         */
        val TOOL_SCHEMAS: Map<String, Map<String, Any>> = loadAllSchemas()

        private fun loadAllSchemas(): Map<String, Map<String, Any>> {
            logger.info { "Loading SVN MCP tool schemas from McpDefaults" }

            return try {
                val json = Json { ignoreUnknownKeys = true }
                val toolsMap = json.decodeFromString<Map<String, JsonObject>>(McpDefaults.SVN_TOOLS_SCHEMA)
                val result = toolsMap.mapValues { (_, jsonObj) -> jsonObjectToMap(jsonObj) }
                logger.info { "Loaded ${result.size} SVN MCP tool schemas: ${result.keys}" }
                result
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse SVN MCP schemas: ${e.message}" }
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

        fun getToolSchema(toolName: String): Map<String, Any> {
            return TOOL_SCHEMAS[toolName] ?: run {
                logger.warn { "SVN MCP tool schema not found: $toolName" }
                emptyMap()
            }
        }
    }

    override suspend fun onInitialize() {
        logger.info { "Initializing SVN MCP Server for project: ${project.name}" }

        try {
            logger.info { "Using pre-loaded schemas: ${TOOL_SCHEMAS.size} tools (${TOOL_SCHEMAS.keys})" }

            if (TOOL_SCHEMAS.isEmpty()) {
                logger.error { "No SVN MCP schemas loaded! Tools will not work properly." }
            }

            // 初始化工具实例
            logger.info { "Creating SVN MCP tool instances..." }
            getVcsChangesTool = GetVcsChangesTool(project)
            getSvnHistoryTool = GetSvnHistoryTool(project)
            getCommitMessageTool = GetCommitMessageTool(project)
            setCommitMessageTool = SetCommitMessageTool(project)
            getSvnStatusTool = GetSvnStatusTool(project)
            logger.info { "All SVN MCP tool instances created" }

            // 注册工具
            registerToolFromSchema("GetVcsChanges", getToolSchema("GetVcsChanges")) { arguments ->
                getVcsChangesTool.execute(arguments)
            }

            registerToolFromSchema("GetSvnHistory", getToolSchema("GetSvnHistory")) { arguments ->
                getSvnHistoryTool.execute(arguments)
            }

            registerToolFromSchema("GetCommitMessage", getToolSchema("GetCommitMessage")) { arguments ->
                getCommitMessageTool.execute(arguments)
            }

            registerToolFromSchema("SetCommitMessage", getToolSchema("SetCommitMessage")) { arguments ->
                setCommitMessageTool.execute(arguments)
            }

            registerToolFromSchema("GetSvnStatus", getToolSchema("GetSvnStatus")) { arguments ->
                getSvnStatusTool.execute(arguments)
            }

            logger.info { "SVN MCP Server initialized, registered 5 tools" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize SVN MCP Server: ${e.message}" }
            throw e
        }
    }
}

/**
 * SVN MCP 服务器提供者实现
 */
class SvnMcpServerProviderImpl(private val project: Project) : SvnMcpServerProvider {

    private val _server: McpServer by lazy {
        logger.info { "Creating SVN MCP Server for project: ${project.name}" }
        SvnMcpServerImpl(project).also {
            logger.info { "SVN MCP Server instance created" }
        }
    }

    override fun getServer(): McpServer? {
        logger.info { "SvnMcpServerProvider.getServer() called" }
        return _server
    }
}
