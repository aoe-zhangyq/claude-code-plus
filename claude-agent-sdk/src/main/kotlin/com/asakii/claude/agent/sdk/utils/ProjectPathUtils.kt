package com.asakii.claude.agent.sdk.utils

import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/**
 * 项目路径工具类
 * 提供项目路径和项目目录名之间的转换
 */
object ProjectPathUtils {

    /**
     * 将项目路径转换为 Claude CLI 使用的目录名
     *
     * Claude CLI 的命名规则：
     * 1. Windows 盘符冒号移除，盘符后加 -
     * 2. 将路径分隔符替换为 -
     * 3. 将点号 (.) 替换为 -
     * 4. 将下划线 (_) 替换为 -
     * 5. 保留开头的 - (Unix 路径)
     *
     * 例如：
     * - /home/username/codes/claude-code-plus → -home-username-codes-claude-code-plus
     * - /Users/username/.claude-code-router → -Users-username--claude-code-router
     * - C:\Users\user\project → C--Users-user-project
     *
     * @param projectPath 项目的绝对路径
     * @return Claude CLI 使用的目录名
     */
    fun projectPathToDirectoryName(projectPath: String): String {
        // 规范化路径（处理不同操作系统的路径分隔符）
        val normalizedPath = Paths.get(projectPath).normalize().toString()

        // Claude CLI 的实际编码规则：
        // 1. 先移除 Windows 盘符冒号
        // 2. 将所有路径分隔符替换为 -
        // 3. 将点号替换为 -
        // 4. 将下划线替换为 -
        // 5. Windows 路径会在盘符后产生双横线（例如 C:\Users → C--Users）
        var dirName = normalizedPath

        // 处理 Windows 盘符（例如 "C:" → "C-"）
        if (dirName.length >= 2 && dirName[1] == ':') {
            dirName = dirName[0] + "-" + dirName.substring(2)
        }

        // 替换路径分隔符、点号和下划线
        dirName = dirName
            .replace('\\', '-')  // Windows 路径分隔符
            .replace('/', '-')   // Unix 路径分隔符
            .replace('.', '-')   // 点号
            .replace('_', '-')   // 下划线

        // Claude CLI 保留开头的 -，只移除结尾的 -
        return dirName.trimEnd('-')
    }

    /**
     * 获取项目的简短名称（用于显示）
     *
     * @param projectPath 项目路径
     * @return 项目名称（最后一级目录名）
     */
    fun getProjectName(projectPath: String): String {
        return Paths.get(projectPath).fileName?.toString() ?: "Unknown"
    }

    /**
     * 生成项目的唯一标识符
     * 用于需要更短的标识符的场景
     *
     * @param projectPath 项目路径
     * @return 8字符的唯一标识
     */
    fun generateProjectId(projectPath: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(projectPath.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * 验证项目路径是否有效
     */
    fun isValidProjectPath(projectPath: String): Boolean {
        return try {
            val path = Paths.get(projectPath)
            path.isAbsolute
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检测是否为 WSL 路径（如 /mnt/c/..., /mnt/d/...）
     */
    fun isWslPath(projectPath: String): Boolean {
        return projectPath.startsWith("/mnt/") && projectPath.length > 6
    }

    /**
     * 将 WSL 路径转换为 Windows 路径
     *
     * 例如：
     * - /mnt/c/Users/username/project → C:\Users\username\project
     * - /mnt/d/Develop/project → D:\Develop\project
     *
     * @param wslPath WSL 格式的绝对路径
     * @return Windows 格式的绝对路径，如果不是 WSL 路径则返回原路径
     */
    fun wslPathToWindowsPath(wslPath: String): String {
        if (!isWslPath(wslPath)) {
            return wslPath
        }

        // 提取盘符（/mnt/c → C:, /mnt/d → D:）
        val driveLetter = wslPath[5].uppercaseChar()  // 获取 /mnt/X 的 X
        val remainingPath = wslPath.substring(6)       // 获取盘符后的路径

        // 将 Unix 风格路径分隔符转换为 Windows 风格
        val windowsPath = remainingPath.replace('/', '\\')

        return "$driveLetter:$windowsPath"
    }

    /**
     * 获取项目的可能目录名列表（用于历史文件查找）
     *
     * 在 WSL 环境下，可能存在两种历史目录：
     * 1. Windows 路径转换的目录（使用 Windows 版 Claude CLI 创建）
     * 2. WSL 路径转换的目录（使用 WSL 版 Claude CLI 创建）
     *
     * 返回按优先级排序的目录名列表（优先检查原路径，然后检查转换后的路径）
     *
     * @param projectPath 项目的绝对路径
     * @return 可能的目录名列表
     */
    fun getPossibleDirectoryNames(projectPath: String): List<String> {
        val primaryName = projectPathToDirectoryName(projectPath)
        val result = mutableListOf(primaryName)

        // 如果是 WSL 路径，添加对应的 Windows 路径目录名
        if (isWslPath(projectPath)) {
            val windowsPath = wslPathToWindowsPath(projectPath)
            val windowsDirName = projectPathToDirectoryName(windowsPath)
            if (windowsDirName != primaryName) {
                result.add(windowsDirName)
            }
        }

        return result
    }
}