package com.asakii.server.mcp

import com.asakii.claude.agent.sdk.mcp.McpServer

/**
 * Compile MCP 服务器提供者接口
 *
 * 由于 Compile MCP 需要访问 IDEA Platform API，
 * 实现类必须在 jetbrains-plugin 模块中创建。
 * 此接口用于解耦 ai-agent-server 和 jetbrains-plugin 的依赖。
 */
interface CompileMcpServerProvider {
    /**
     * 获取 Compile MCP 服务器实例
     * @return McpServer 实例，如果不可用则返回 null
     */
    fun getServer(): McpServer?
}

/**
 * 默认实现（不支持编译集成时使用）
 */
object DefaultCompileMcpServerProvider : CompileMcpServerProvider {
    override fun getServer(): McpServer? = null
}
