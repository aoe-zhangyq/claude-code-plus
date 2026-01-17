package com.asakii.claude.agent.sdk.utils

import mu.KotlinLogging

/**
 * Shell 路径类型
 *
 * 用于区分不同 shell 环境下的路径格式
 */
enum class ShellPathType {
    /** Windows 原生路径 (CMD, PowerShell): `D:\Develop\Code` */
    WINDOWS,
    /** WSL 路径: `/mnt/d/Develop/Code` */
    WSL,
    /** Git Bash / MSYS2 路径: `/d/Develop/Code` */
    GIT_BASH
}

/**
 * WSL 路径转换方向
 */
enum class WslPathDirection {
    /** Windows → WSL (MCP 响应 → CC) */
    WINDOWS_TO_WSL,
    /** WSL → Windows (CC → MCP 请求) */
    WSL_TO_WINDOWS
}

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

    /**
     * 根据方向转换路径
     *
     * @param path 原始路径
     * @param direction 转换方向
     * @return 转换后的路径
     */
    fun convertPath(path: String, direction: WslPathDirection): String {
        if (path.isEmpty()) return path
        return when (direction) {
            WslPathDirection.WINDOWS_TO_WSL -> windowsToWslPath(path)
            WslPathDirection.WSL_TO_WINDOWS -> wslToWindowsPath(path)
        }
    }

    /**
     * 转换参数 Map 中的指定字段为 WSL 路径
     *
     * 用于 MCP 工具请求参数的路径转换（WSL → Windows）
     *
     * @param arguments 原始参数 Map
     * @param pathFields 需要转换的字段名列表
     * @return 转换后的参数 Map
     */
    fun convertArgumentsPaths(
        arguments: Map<String, Any>,
        pathFields: List<String> = listOf("path", "filePath", "file", "directory")
    ): Map<String, Any> {
        val result = arguments.toMutableMap()
        for (field in pathFields) {
            val value = result[field]
            if (value is String) {
                result[field] = convertPath(value, WslPathDirection.WSL_TO_WINDOWS)
            }
        }
        return result
    }

    /**
     * 转换结果字符串中的 Windows 路径为 WSL 路径
     *
     * 用于 MCP 工具响应结果的路径转换（Windows → WSL）
     * 查找字符串中所有符合 Windows 路径格式的子串并转换
     *
     * @param result 原始结果字符串
     * @return 转换后的结果字符串
     */
    fun convertPathsInResult(result: String): String {
        if (result.isEmpty()) return result

        // 匹配 Windows 路径模式：盘符:\路径
        // 如 D:\Develop\Code 或 D:/Develop/Code
        val windowsPathPattern = Regex("""([A-Za-z]):[\\/][^`\s"']*[^\s`"']""")

        return windowsPathPattern.replace(result) { match ->
            val windowsPath = match.value
            val wslPath = windowsToWslPath(windowsPath)
            if (wslPath != windowsPath) {
                logger.debug { "🔄 [WSL] Converted path in result: $windowsPath → $wslPath" }
                wslPath
            } else {
                match.value
            }
        }
    }

    /**
     * 检测路径是否为 Windows 绝对路径
     *
     * @param path 待检测路径
     * @return true 如果是 Windows 绝对路径
     */
    fun isWindowsAbsolutePath(path: String): Boolean {
        if (path.isEmpty()) return false
        val driveLetterPattern = Regex("^([A-Za-z]):[/\\\\]")
        return driveLetterPattern.containsMatchIn(path)
    }

    /**
     * 检测路径是否为 WSL /mnt/ 路径
     *
     * @param path 待检测路径
     * @return true 如果是 WSL /mnt/ 路径
     */
    fun isWslMountPath(path: String): Boolean {
        if (path.isEmpty()) return false
        return path.startsWith("/mnt/") || path.startsWith("/mnt\\")
    }

    /**
     * 批量转换字符串列表中的路径
     *
     * @param paths 路径列表
     * @param direction 转换方向
     * @return 转换后的路径列表
     */
    fun convertPathList(paths: List<String>, direction: WslPathDirection): List<String> {
        return paths.map { convertPath(it, direction) }
    }

    /**
     * 将 Windows 路径转换为 Git Bash (MSYS2) 路径
     *
     * Git Bash 使用 MinGW/MSYS2 的路径格式：
     * - `C:\` → `/c/`
     * - `D:\` → `/d/`
     *
     * 示例：
     * - `D:\Develop\Code\project` → `/d/Develop/Code/project`
     * - `C:\Users\username\file.txt` → `/c/Users/username/file.txt`
     *
     * @param windowsPath Windows 格式的绝对路径（如 `D:\path\to\file`）
     * @return Git Bash 格式的路径（如 `/d/path/to/file`），如果转换失败返回原路径
     */
    fun windowsToGitBashPath(windowsPath: String): String {
        if (windowsPath.isEmpty()) return windowsPath

        // 匹配 Windows 盘符路径：如 D:\path 或 D:/path
        val driveLetterPattern = Regex("^([A-Za-z]):[/\\\\](.*)$")
        val matchResult = driveLetterPattern.matchEntire(windowsPath)

        return if (matchResult != null) {
            val drive = matchResult.groupValues[1].lowercase()
            val restPath = matchResult.groupValues[2]
            // 将反斜杠转换为正斜杠
            val gitBashPath = restPath.replace("\\", "/")
            "/$drive/$gitBashPath"
        } else {
            // 不是标准的 Windows 绝对路径，保持原样
            logger.debug { "⚠️ Not a Windows absolute path: $windowsPath" }
            windowsPath
        }
    }

    /**
     * 将 Git Bash (MSYS2) 路径转换为 Windows 路径
     *
     * 示例：
     * - `/d/Develop/Code/project` → `D:\Develop\Code\project`
     * - `/c/Users/username/file.txt` → `C:\Users\username\file.txt`
     *
     * @param gitBashPath Git Bash 格式的路径（如 `/d/path/to/file`）
     * @return Windows 格式的路径（如 `D:\path\to\file`），如果转换失败返回原路径
     */
    fun gitBashToWindowsPath(gitBashPath: String): String {
        if (gitBashPath.isEmpty()) return gitBashPath

        // 匹配 Git Bash /驱动器/ 路径（如 /c/ 或 /d/）
        val gitBashPattern = Regex("^/([a-z])/(.*)$")
        val matchResult = gitBashPattern.matchEntire(gitBashPath)

        return if (matchResult != null) {
            val drive = matchResult.groupValues[1].uppercase()
            val restPath = matchResult.groupValues[2]
            // 将正斜杠转换为反斜杠
            val windowsPath = restPath.replace("/", "\\")
            "$drive:\\$windowsPath"
        } else {
            // 不是标准的 Git Bash 路径，保持原样
            logger.debug { "⚠️ Not a Git Bash path: $gitBashPath" }
            gitBashPath
        }
    }

    /**
     * 检测路径是否为 Git Bash /驱动器/ 路径
     *
     * @param path 待检测路径
     * @return true 如果是 Git Bash 路径
     */
    fun isGitBashPath(path: String): Boolean {
        if (path.isEmpty()) return false
        val gitBashPattern = Regex("^/([a-z])/.+$")
        return gitBashPattern.containsMatchIn(path)
    }

    /**
     * 根据目标 shell 类型转换 Windows 路径
     *
     * @param windowsPath Windows 格式的路径
     * @param shellType 目标 shell 类型
     * @return 转换后的路径
     */
    fun convertPathForShell(windowsPath: String, shellType: ShellPathType): String {
        return when (shellType) {
            ShellPathType.WINDOWS -> windowsPath
            ShellPathType.WSL -> windowsToWslPath(windowsPath)
            ShellPathType.GIT_BASH -> windowsToGitBashPath(windowsPath)
        }
    }

    /**
     * 根据 shell 名称推断路径类型
     *
     * @param shellName shell 名称（如 "git-bash", "powershell", "wsl"）
     * @return 对应的路径类型
     */
    fun inferPathTypeFromShell(shellName: String): ShellPathType {
        val lowerName = shellName.lowercase()
        return when {
            lowerName.contains("wsl") || lowerName.contains("ubuntu") ||
            lowerName.contains("debian") || lowerName.contains("opensuse") -> ShellPathType.WSL
            lowerName.contains("git bash") || lowerName.contains("git-bash") ||
            lowerName.contains("mingw") || lowerName.contains("msys") -> ShellPathType.GIT_BASH
            lowerName.contains("powershell") || lowerName.contains("pwsh") ||
            lowerName.contains("cmd") || lowerName.contains("command prompt") -> ShellPathType.WINDOWS
            lowerName.contains("bash") || lowerName.contains("zsh") ||
            lowerName.contains("fish") -> ShellPathType.GIT_BASH  // Unix-like shells on Windows typically use Git Bash format
            else -> ShellPathType.WINDOWS  // 默认使用 Windows 格式
        }
    }

    // ============================================================================
    // 命令路径转换功能
    // ============================================================================
    //
    // 如果需要回退此功能，有两种方式：
    // 1. 设置 FEATURE_FLAG_COMMAND_PATH_CONVERSION = false
    // 2. 在 TerminalSessionManager.executeCommandAsync() 中注释掉转换调用
    //
    // 修改日期: 2025-01-17
    // 修改原因: 修复 Git Bash/WSL 终端中命令参数使用 Windows 路径格式的问题
    // 示例: Bash type "D:\path\file.txt" 在 Git Bash 中应转换为 type "/d/path/file.txt"
    // ============================================================================

    /**
     * 命令路径转换功能开关
     *
     * 设置为 false 可禁用命令中的路径自动转换功能
     */
    const val FEATURE_FLAG_COMMAND_PATH_CONVERSION = true

    /**
     * 转换命令字符串中的 Windows 路径为适合目标 shell 的格式
     *
     * 此功能会扫描命令字符串，查找其中的 Windows 路径（如 D:\path\file.txt），
     * 并根据目标 shell 类型转换为相应格式。
     *
     * 支持的路径格式:
     * - 带引号的路径: "D:\path\file.txt" 或 'D:\path\file.txt'
     * - 不带引号的路径: D:\path\file.txt
     *
     * @param command 原始命令字符串
     * @param shellType 目标 shell 类型
     * @return 转换后的命令字符串
     *
     * @since 2025-01-17
     */
    fun convertPathsInCommand(command: String, shellType: ShellPathType): String {
        if (!FEATURE_FLAG_COMMAND_PATH_CONVERSION || shellType == ShellPathType.WINDOWS) {
            return command
        }

        // Windows 路径正则：匹配盘符:\路径
        // 支持以下格式:
        // - D:\path\to\file
        // - D:/path/to/file
        // - 带引号: "D:\path\to\file" 或 'D:\path\to\file'
        val windowsPathPattern = Regex(
            """([\"']?)(([A-Za-z]):[\\/][^\"'\s]+)\1""",
            RegexOption.COMMENTS
        )

        return windowsPathPattern.replace(command) { match ->
            val quote = match.groupValues[1]  // 引号字符（可能为空）
            val path = match.groupValues[2]   // 路径部分
            val convertedPath = convertPathForShell(path, shellType)

            if (convertedPath != path) {
                logger.debug { "🔄 [Command] Converted path: $path → $convertedPath (shellType=$shellType)" }
                // 保留原引号包裹转换后的路径
                "$quote$convertedPath$quote"
            } else {
                match.value
            }
        }
    }

    /**
     * 转换命令字符串中的 Windows 路径（根据 shell 名称推断类型）
     *
     * @param command 原始命令字符串
     * @param shellName shell 名称
     * @return 转换后的命令字符串
     */
    fun convertPathsInCommand(command: String, shellName: String): String {
        val shellType = inferPathTypeFromShell(shellName)
        return convertPathsInCommand(command, shellType)
    }
}
