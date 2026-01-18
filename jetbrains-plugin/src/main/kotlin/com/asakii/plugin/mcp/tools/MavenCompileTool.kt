package com.asakii.plugin.mcp.tools

import com.asakii.claude.agent.sdk.mcp.ToolResult
import com.asakii.claude.agent.sdk.utils.WslPathConverter
import com.asakii.claude.agent.sdk.utils.WslPathDirection
import com.asakii.server.mcp.schema.ToolSchemaLoader
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.text.trim

private val logger = KotlinLogging.logger {}

/**
 * Maven 离线编译工具
 *
 * 使用 Maven 离线模式编译，跳过依赖检查。Maven 默认使用增量编译，
 * 只编译修改过的文件。
 *
 * @param project IDEA 项目
 * @param wslModeEnabled 是否启用 WSL 模式
 */
class MavenCompileTool(
    private val project: Project,
    private val wslModeEnabled: Boolean = false
) {

    fun getInputSchema(): Map<String, Any> = ToolSchemaLoader.getSchema("MavenCompile")

    suspend fun execute(arguments: Map<String, Any>): Any {
        val goals = arguments["goals"] as? List<*> ?: listOf("compile")
        val offline = arguments["offline"] as? Boolean ?: true
        val quiet = arguments["quiet"] as? Boolean ?: true
        val batchMode = arguments["batchMode"] as? Boolean ?: true
        val timeoutSec = (arguments["timeout"] as? Number)?.toInt() ?: 300

        return try {
            val result = runMavenBuild(
                goals = goals.map { it.toString() },
                offline = offline,
                quiet = quiet,
                batchMode = batchMode,
                timeoutSec = timeoutSec
            )
            if (wslModeEnabled) {
                WslPathConverter.convertPathsInResult(result)
            } else {
                result
            }
        } catch (e: TimeoutCancellationException) {
            logger.error { "❌ Maven compile timeout after ${timeoutSec}s" }
            ToolResult.error("Maven compile timeout after ${timeoutSec}s")
        } catch (e: Exception) {
            logger.error(e) { "❌ Maven compile failed: ${e.message}" }
            ToolResult.error("Maven compile failed: ${e.message}")
        }
    }

    /**
     * 运行 Maven 构建
     *
     * Maven 默认使用增量编译，只编译修改过的文件
     */
    private suspend fun runMavenBuild(
        goals: List<String>,
        offline: Boolean,
        quiet: Boolean,
        batchMode: Boolean,
        timeoutSec: Int
    ): String {
        val projectPath = project.basePath
            ?: return "## ❌ Error\n\nCannot get project path"

        // 查找 Maven 可执行文件
        val mavenExecutable = findMavenExecutable()
            ?: return "## ❌ Error\n\nMaven not found. Please ensure Maven is installed and in PATH."

        logger.info { "🔨 Running Maven: ${mavenExecutable.name} ${goals.joinToString(" ")}" }

        // 构建命令行
        val commandLine = GeneralCommandLine(mavenExecutable.absolutePath)
        commandLine.setWorkDirectory(File(projectPath))
        commandLine.charset = Charset.forName("UTF-8")

        // 添加参数
        if (offline) commandLine.addParameter("-o")
        if (quiet) commandLine.addParameter("-q")
        if (batchMode) commandLine.addParameter("-B")

        // 注意：Maven 默认就是增量编译，无需额外参数
        // 以下参数无效或已废弃，不要使用：
        // - -Dmaven.compiler.useIncrementalCompilation=true (3.x有效但默认已是true，4.x已废弃)
        // - -Dmaven.incrementalCompilation=true (从未存在，完全无效)

        commandLine.addParameters(goals)

        logger.debug { "🔨 Command: ${commandLine.commandLineString}" }

        // 运行进程并收集输出
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exitCode: Int = withTimeout(timeoutSec * 1000L) {
            try {
                val processHandler = OSProcessHandler(commandLine)

                // 收集输出
                processHandler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        val text = event.text
                        when (outputType) {
                            com.intellij.execution.process.ProcessOutputTypes.STDOUT -> stdout.append(text)
                            com.intellij.execution.process.ProcessOutputTypes.STDOUT -> stdout.append(text)
                            com.intellij.execution.process.ProcessOutputTypes.STDERR -> stderr.append(text)
                            com.intellij.execution.process.ProcessOutputTypes.SYSTEM -> {} // 忽略系统输出
                        }
                    }
                })

                processHandler.startNotify()
                processHandler.waitFor(TimeUnit.SECONDS.toMillis(timeoutSec.toLong()))
                // 获取进程退出码
                processHandler.exitCode ?: -1
            } catch (e: ExecutionException) {
                logger.error(e) { "❌ Failed to execute Maven" }
                return@withTimeout -1
            }
        }

        val output = stdout.toString() + stderr.toString()
        logger.debug { "📊 Maven exit code: $exitCode, output length: ${output.length}" }

        return parseMavenOutput(exitCode, output)
    }

    /**
     * 查找 Maven 可执行文件
     */
    private fun findMavenExecutable(): File? {
        // 1. 检查环境变量 MAVEN_HOME
        val mavenHome = System.getenv("MAVEN_HOME")
        if (mavenHome != null) {
            val mvn = if (System.getProperty("os.name").lowercase().contains("windows")) {
                File(mavenHome, "bin/mvn.cmd")
            } else {
                File(mavenHome, "bin/mvn")
            }
            if (mvn.exists()) return mvn
        }

        // 2. 检查 PATH 中的 mvn
        val pathEnv = System.getenv("PATH") ?: ""
        val pathSeparator = if (System.getProperty("os.name").lowercase().contains("windows")) ";" else ":"
        val pathDirs = pathEnv.split(pathSeparator)

        for (dir in pathDirs) {
            val mvn = if (System.getProperty("os.name").lowercase().contains("windows")) {
                File(dir, "mvn.cmd")
            } else {
                File(dir, "mvn")
            }
            if (mvn.exists()) return mvn
        }

        // 3. 检查 IDEA Bundled Maven
        val ideaMavenHome = System.getProperty("idea.maven.home")
        if (ideaMavenHome != null) {
            val mvn = if (System.getProperty("os.name").lowercase().contains("windows")) {
                File(ideaMavenHome, "bin/mvn.cmd")
            } else {
                File(ideaMavenHome, "bin/mvn")
            }
            if (mvn.exists()) return mvn
        }

        return null
    }

    /**
     * 解析 Maven 输出
     */
    private fun parseMavenOutput(exitCode: Int, rawOutput: String): String {
        val sb = StringBuilder()
        sb.appendLine("## 🔨 Maven Build")
        sb.appendLine()

        // 解析输出中的错误和警告
        val lines = rawOutput.lines()
        val errors = mutableListOf<MavenError>()
        val warnings = mutableListOf<MavenWarning>()

        var currentBuildError: StringBuilder? = null

        for (line in lines) {
            // 解析编译错误: [ERROR] /path/to/File.java:[line,column] error message
            if (line.contains("[ERROR]") && line.contains(".java:[")) {
                val parsed = parseJavaError(line)
                if (parsed != null) {
                    errors.add(parsed)
                }
            }
            // 解析警告
            else if (line.contains("[WARNING]") && line.contains(".java:[")) {
                val parsed = parseJavaWarning(line)
                if (parsed != null) {
                    warnings.add(parsed)
                }
            }
            // 收集构建错误摘要
            else if (line.trim().startsWith("[ERROR] BUILD FAILURE")) {
                currentBuildError = StringBuilder()
            } else if (currentBuildError != null) {
                if (line.trim().startsWith("---") || line.trim().startsWith("Re-run Maven")) {
                    // 错误摘要结束
                    currentBuildError = null
                } else if (line.trim().isNotEmpty()) {
                    currentBuildError?.appendLine(line.trim())
                }
            }
        }

        if (exitCode == 0 && errors.isEmpty()) {
            sb.appendLine("✅ **Build successful**")
        } else {
            if (errors.isNotEmpty()) {
                sb.appendLine("### ❌ Compilation Errors (${errors.size})")
                sb.appendLine()
                sb.appendLine("| File | Line | Message |")
                sb.appendLine("|------|------|---------|")
                errors.take(50).forEach { error ->
                    val relPath = error.filePath.let { path ->
                        val projectPath = project.basePath
                        if (projectPath != null && path.startsWith(projectPath)) {
                            path.removePrefix(projectPath).removePrefix("/").removePrefix("\\")
                        } else {
                            File(path).name
                        }
                    }
                    val escapedMsg = error.message.replace("|", "\\|")
                    sb.appendLine("| `${relPath}` | ${error.line} | ${escapedMsg} |")
                }
                if (errors.size > 50) {
                    sb.appendLine("| ... | ... | ... and ${errors.size - 50} more errors |")
                }
                sb.appendLine()
            }

            if (warnings.isNotEmpty()) {
                sb.appendLine("### ⚠️ Warnings (${warnings.size})")
                sb.appendLine()
                sb.appendLine("| File | Line | Message |")
                sb.appendLine("|------|------|---------|")
                warnings.take(20).forEach { warning ->
                    val relPath = warning.filePath.let { path ->
                        val projectPath = project.basePath
                        if (projectPath != null && path.startsWith(projectPath)) {
                            path.removePrefix(projectPath).removePrefix("/").removePrefix("\\")
                        } else {
                            File(path).name
                        }
                    }
                    val escapedMsg = warning.message.replace("|", "\\|")
                    sb.appendLine("| `${relPath}` | ${warning.line} | ${escapedMsg} |")
                }
                if (warnings.size > 20) {
                    sb.appendLine("| ... | ... | ... and ${warnings.size - 20} more warnings |")
                }
                sb.appendLine()
            }
        }

        sb.appendLine("---")
        if (exitCode == 0 && errors.isEmpty()) {
            sb.append("📊 Status: **SUCCESS**")
        } else if (errors.isNotEmpty()) {
            sb.append("📊 Status: **FAILED** - ${errors.size} error(s)")
        } else {
            sb.append("📊 Status: **FAILED** - exit code $exitCode")
        }

        return sb.toString()
    }

    /**
     * 解析 Java 编译错误
     * 格式: [ERROR] /path/to/File.java:[line,column] error message
     */
    private fun parseJavaError(line: String): MavenError? {
        // 提取文件路径和位置
        val fileMatch = Regex("""\[ERROR\]\s+(.+\.java):\[(\d+),(\d+)\]\s+(.+)""").find(line)
        if (fileMatch != null) {
            val (filePath, lineStr, columnStr, message) = fileMatch.destructured
            return MavenError(
                filePath = filePath.trim(),
                line = lineStr.toIntOrNull() ?: 0,
                column = columnStr.toIntOrNull() ?: 0,
                message = message.trim()
            )
        }
        return null
    }

    /**
     * 解析 Java 编译警告
     */
    private fun parseJavaWarning(line: String): MavenWarning? {
        val fileMatch = Regex("""\[WARNING\]\s+(.+\.java):\[(\d+),(\d+)\]\s+(.+)""").find(line)
        if (fileMatch != null) {
            val (filePath, lineStr, columnStr, message) = fileMatch.destructured
            return MavenWarning(
                filePath = filePath.trim(),
                line = lineStr.toIntOrNull() ?: 0,
                column = columnStr.toIntOrNull() ?: 0,
                message = message.trim()
            )
        }
        return null
    }

    private data class MavenError(
        val filePath: String,
        val line: Int,
        val column: Int,
        val message: String
    )

    private data class MavenWarning(
        val filePath: String,
        val line: Int,
        val column: Int,
        val message: String
    )
}
