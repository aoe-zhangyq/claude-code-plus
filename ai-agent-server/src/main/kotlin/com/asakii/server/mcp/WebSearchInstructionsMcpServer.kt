package com.asakii.server.mcp

import com.asakii.claude.agent.sdk.mcp.McpServerBase
import com.asakii.claude.agent.sdk.mcp.annotations.McpServerConfig
import mu.KotlinLogging

/**
 * 联网检索提示 MCP Server
 *
 * 提供系统提示词追加内容，提醒模型在不确定 API 时可以使用联网检索工具。
 * 此服务器不提供任何工具，仅用于追加提示词。
 *
 * @property instructions 联网检索提示词内容
 */
private val logger = KotlinLogging.logger {}

@McpServerConfig(
    name = "web_search_instructions",
    version = "1.0.0",
    description = "联网检索提示服务器，提醒模型可以使用 WebSearch 工具查找官方文档"
)
class WebSearchInstructionsMcpServer(
    private val instructions: String
) : McpServerBase() {

    /**
     * 获取系统提示词追加内容
     *
     * 返回联网检索相关的提示词，提醒模型在不确定 API 时可以使用 WebSearch 工具。
     */
    override fun getSystemPromptAppendix(): String {
        return instructions
    }

    override suspend fun onInitialize() {
        logger.info { "✅ [WebSearchInstructionsMcpServer] 初始化完成" }
    }

    /**
     * 提供者类
     *
     * 用于创建和配置 WebSearchInstructionsMcpServer 实例
     */
    class Provider {
        /**
         * 创建服务器实例
         *
         * @param instructions 联网检索提示词内容
         * @return WebSearchInstructionsMcpServer 实例，如果提示词为空则返回 null
         */
        fun createServer(instructions: String): WebSearchInstructionsMcpServer? {
            if (instructions.isBlank()) {
                logger.info { "⏭️ [WebSearchInstructionsMcpServer] 提示词为空，跳过创建" }
                return null
            }
            return WebSearchInstructionsMcpServer(instructions)
        }
    }
}
