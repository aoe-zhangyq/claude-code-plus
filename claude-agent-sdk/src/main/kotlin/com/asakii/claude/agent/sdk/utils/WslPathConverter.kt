package com.asakii.claude.agent.sdk.utils

import mu.KotlinLogging

/**
 * WSL 路径转换工具类
 *
 * 用于在 Windows 模式和 WSL 模式之间转换路径和 URL。
 */
private val logger = KotlinLogging.logger {}

object WslPathConverter {

    /**
     * 将 Windows 路径转换为 WSL 路径
     *
     * 示例：
     * - `D:\Develop\Code\project` → `/mnt/d/Develop/Code/project`
     * - `C:\Users\username\file.txt` → `/mnt/c/Users/username/file.txt`
     *
     * @param windowsPath Windows 格式的绝对路径（如 `D:\path\to\file`）
     * @return WSL 格式的路径（如 `/mnt/d/path/to/file`），如果转换失败返回原路径
     */
    fun windowsToWslPath(windowsPath: String): String {
        if (windowsPath.isEmpty()) return windowsPath

        // 匹配 Windows 盘符路径：如 D:\path 或 D:/path
        val driveLetterPattern = Regex("^([A-Za-z]):[/\\\\](.*)$")
        val matchResult = driveLetterPattern.matchEntire(windowsPath)

        return if (matchResult != null) {
            val drive = matchResult.groupValues[1].lowercase()
            val restPath = matchResult.groupValues[2]
            // 将反斜杠转换为正斜杠
            val wslPath = restPath.replace("\\", "/")
            "/mnt/$drive/$wslPath"
        } else {
            // 不是标准的 Windows 绝对路径，保持原样
            logger.debug { "⚠️ Not a Windows absolute path: $windowsPath" }
            windowsPath
        }
    }

    /**
     * 将 WSL 路径转换为 Windows 路径
     *
     * 示例：
     * - `/mnt/d/Develop/Code/project` → `D:\Develop\Code\project`
     * - `/mnt/c/Users/username/file.txt` → `C:\Users\username\file.txt`
     *
     * @param wslPath WSL 格式的路径（如 `/mnt/d/path/to/file`）
     * @return Windows 格式的路径（如 `D:\path\to\file`），如果转换失败返回原路径
     */
    fun wslToWindowsPath(wslPath: String): String {
        if (wslPath.isEmpty()) return wslPath

        // 匹配 WSL /mnt/ 路径
        val mntPattern = Regex("^/mnt/([a-z])/(.*)$")
        val matchResult = mntPattern.matchEntire(wslPath)

        return if (matchResult != null) {
            val drive = matchResult.groupValues[1].uppercase()
            val restPath = matchResult.groupValues[2]
            // 将正斜杠转换为反斜杠
            val windowsPath = restPath.replace("/", "\\")
            "$drive:\\$windowsPath"
        } else {
            // 不是标准的 WSL /mnt/ 路径，保持原样
            logger.debug { "⚠️ Not a WSL /mnt/ path: $wslPath" }
            wslPath
        }
    }

    /**
     * 转换 MCP HTTP URL 中的 localhost/127.0.0.1 为 WSL 主机 IP
     *
     * 示例：
     * - `http://127.0.0.1:8765/mcp` → `http://172.20.160.1:8765/mcp`
     * - `http://localhost:8765/mcp` → `http://172.20.160.1:8765/mcp`
     *
     * @param url 原始 URL
     * @param wslHostIp WSL 主机 IP（如 172.20.160.1）
     * @return 转换后的 URL，如果不需要转换或转换失败返回原 URL
     */
    fun convertMcpUrl(url: String, wslHostIp: String?): String {
        if (url.isEmpty() || wslHostIp.isNullOrEmpty()) return url

        return try {
            val uri = java.net.URI(url)

            // 只转换 localhost 或 127.0.0.1
            val host = uri.host
            val shouldConvert = host == "localhost" || host == "127.0.0.1"

            if (shouldConvert) {
                val newUri = java.net.URI(
                    uri.scheme,
                    uri.userInfo,
                    wslHostIp,
                    uri.port,
                    uri.path,
                    uri.query,
                    uri.fragment
                )
                newUri.toString()
            } else {
                url
            }
        } catch (e: Exception) {
            logger.warn(e) { "⚠️ Failed to convert MCP URL: $url" }
            url
        }
    }

    /**
     * 转换 MCP 服务器配置列表中的所有 URL
     *
     * @param mcpServers MCP 服务器配置 Map
     * @param wslHostIp WSL 主机 IP
     * @return 转换后的 MCP 服务器配置 Map
     */
    fun convertMcpServersConfig(
        mcpServers: Map<String, Any>,
        wslHostIp: String?
    ): Map<String, Any> {
        if (wslHostIp.isNullOrEmpty()) return mcpServers

        return mcpServers.mapValues { (name, config) ->
            @Suppress("UNCHECKED_CAST")
            val configMap = config as? Map<String, Any> ?: return@mapValues config

            when (configMap["type"]) {
                "http" -> {
                    @Suppress("UNCHECKED_CAST")
                    val url = configMap["url"] as? String
                    if (url != null) {
                        val convertedUrl = convertMcpUrl(url, wslHostIp)
                        configMap + ("url" to convertedUrl)
                    } else {
                        configMap
                    }
                }
                else -> config
            }
        }
    }
}
