package com.asakii.plugin.mcp.tools

import com.asakii.claude.agent.sdk.mcp.ToolResult
import com.asakii.claude.agent.sdk.utils.WslPathConverter
import com.asakii.claude.agent.sdk.utils.WslPathDirection
import com.asakii.server.mcp.schema.ToolSchemaLoader
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

private val logger = KotlinLogging.logger {}

/**
 * 目录树条目
 */
@Serializable
data class DirectoryEntry(
    val name: String,
    val path: String,           // 相对路径
    val isDirectory: Boolean,
    val size: Long? = null,     // 文件大小（仅文件）
    val children: List<DirectoryEntry>? = null  // 子目录/文件
)

/**
 * 目录树工具返回结果
 */
@Serializable
data class DirectoryTreeResult(
    val root: String,
    val entries: List<DirectoryEntry>,
    val totalFiles: Int,
    val totalDirectories: Int,
    val truncated: Boolean = false,  // 是否因达到限制而截断
    val maxDepthReached: Boolean = false
)

/**
 * 目录树工具
 *
 * 获取项目目录中指定文件夹的目录树结构
 *
 * @param project IDEA 项目
 * @param wslModeEnabled 是否启用 WSL 模式（自动转换路径格式）
 */
class DirectoryTreeTool(
    private val project: Project,
    private val wslModeEnabled: Boolean = false
) {

    fun getInputSchema(): Map<String, Any> = ToolSchemaLoader.getSchema("DirectoryTree")

    suspend fun execute(arguments: Map<String, Any>): Any {
        // 处理 path 参数：WSL 模式下可能接收到 WSL 格式路径
        val rawPath = (arguments["path"] as? String)?.takeIf { it.isNotBlank() } ?: "."
        val path = if (wslModeEnabled && WslPathConverter.isWslMountPath(rawPath)) {
            // 将 WSL 路径转换为 Windows 路径，然后转为相对路径
            val windowsPath = WslPathConverter.convertPath(rawPath, WslPathDirection.WSL_TO_WINDOWS)
            // 转换为相对于项目根目录的路径
            val projectPath = project.basePath ?: return ToolResult.error("Cannot get project path")
            val windowsFile = File(windowsPath)
            val projectFile = File(projectPath)
            try {
                windowsFile.relativeTo(projectFile).path.replace("\\", "/")
            } catch (e: Exception) {
                rawPath
            }
        } else {
            rawPath
        }
        val maxDepthArg = (arguments["maxDepth"] as? Number)?.toInt() ?: 3
        val maxDepth = if (maxDepthArg <= 0) Int.MAX_VALUE else maxDepthArg  // -1 or 0 means unlimited
        val filesOnly = arguments["filesOnly"] as? Boolean ?: false
        val includeHidden = arguments["includeHidden"] as? Boolean ?: false
        val pattern = arguments["pattern"] as? String
        val maxEntries = ((arguments["maxEntries"] as? Number)?.toInt() ?: 100).coerceAtLeast(1)

        val projectPath = project.basePath
            ?: return ToolResult.error("Cannot get project path")

        val targetPath = File(projectPath, path).canonicalPath
        if (!targetPath.startsWith(File(projectPath).canonicalPath)) {
            return ToolResult.error("Path must be within project directory")
        }

        val targetDir = File(targetPath)
        if (!targetDir.exists()) {
            return ToolResult.error("Directory not found: $path")
        }
        if (!targetDir.isDirectory) {
            return ToolResult.error("Path is not a directory: $path")
        }

        // 编译 glob 模式
        val matcher: PathMatcher? = pattern?.let {
            FileSystems.getDefault().getPathMatcher("glob:$it")
        }

        var totalFiles = 0
        var totalDirectories = 0
        var entriesCount = 0
        var truncated = false
        var maxDepthReached = false

        fun buildTree(dir: File, currentDepth: Int, relativePath: String): List<DirectoryEntry> {
            if (entriesCount >= maxEntries) {
                truncated = true
                return emptyList()
            }
            
            if (currentDepth > maxDepth) {
                maxDepthReached = true
                return emptyList()
            }

            val entries = mutableListOf<DirectoryEntry>()
            val files = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) 
                ?: return entries

            for (file in files) {
                if (entriesCount >= maxEntries) {
                    truncated = true
                    break
                }

                // 过滤隐藏文件
                if (!includeHidden && file.name.startsWith(".")) continue

                val fileRelativePath = if (relativePath.isEmpty()) file.name else "$relativePath/${file.name}"

                if (file.isDirectory) {
                    if (!filesOnly) {
                        totalDirectories++
                        entriesCount++
                        
                        val children = if (currentDepth < maxDepth) {
                            buildTree(file, currentDepth + 1, fileRelativePath)
                        } else {
                            maxDepthReached = true
                            null
                        }

                        entries.add(DirectoryEntry(
                            name = file.name,
                            path = fileRelativePath,
                            isDirectory = true,
                            children = children
                        ))
                    } else {
                        // filesOnly 模式下仍需递归目录
                        entries.addAll(buildTree(file, currentDepth + 1, fileRelativePath))
                    }
                } else {
                    // 应用文件名模式过滤
                    if (matcher != null && !matcher.matches(java.nio.file.Paths.get(file.name))) {
                        continue
                    }

                    totalFiles++
                    entriesCount++
                    entries.add(DirectoryEntry(
                        name = file.name,
                        path = fileRelativePath,
                        isDirectory = false,
                        size = file.length()
                    ))
                }
            }

            return entries
        }

        val entries = buildTree(targetDir, 1, "")

        // 生成 Markdown 格式的树形结构
        val sb = StringBuilder()
        sb.appendLine("## Directory Tree: `$path`")
        sb.appendLine()
        sb.appendLine("```")

        fun renderTree(items: List<DirectoryEntry>, prefix: String = "") {
            items.forEachIndexed { index, entry ->
                val isLast = index == items.lastIndex
                val connector = if (isLast) "└── " else "├── "
                val sizeInfo = entry.size?.let { " (${formatSize(it)})" } ?: ""
                val dirMarker = if (entry.isDirectory) "/" else ""

                sb.appendLine("$prefix$connector${entry.name}$dirMarker$sizeInfo")

                entry.children?.let { children ->
                    val newPrefix = prefix + if (isLast) "    " else "│   "
                    renderTree(children, newPrefix)
                }
            }
        }

        renderTree(entries)
        sb.appendLine("```")

        sb.appendLine()
        sb.appendLine("---")
        sb.append("**Statistics:** $totalFiles files, $totalDirectories directories")
        if (truncated) sb.append(" *(truncated, max entries reached)*")
        if (maxDepthReached) sb.append(" *(max depth reached)*")

        val result = sb.toString()

        // WSL 模式：转换结果中的 Windows 路径为 WSL 路径
        return if (wslModeEnabled) {
            WslPathConverter.convertPathsInResult(result)
        } else {
            result
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }
}
