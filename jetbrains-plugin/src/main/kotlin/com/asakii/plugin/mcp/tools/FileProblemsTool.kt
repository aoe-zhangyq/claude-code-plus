package com.asakii.plugin.mcp.tools

import com.asakii.claude.agent.sdk.mcp.ToolResult
import com.asakii.claude.agent.sdk.utils.WslPathConverter
import com.asakii.server.mcp.schema.ToolSchemaLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 问题严重级别
 */
@Serializable
enum class ProblemSeverity {
    SYNTAX_ERROR, ERROR, WARNING, SUGGESTION
}

/**
 * 文件问题数据结构
 */
@Serializable
data class FileProblem(
    val filePath: String,
    val severity: ProblemSeverity,
    val message: String,
    val line: Int,
    val column: Int
)

/**
 * 项目问题分析工具
 *
 * 使用 PSI 分析收集项目中的语法错误。
 *
 * **性能优化**：
 * - 默认不刷新 VFS（refresh=false），避免耗时操作
 * - 快速扫描，仅收集 PSI 错误元素
 * - 超时时间缩短为 30 秒
 *
 * ## 工作流程
 *
 * 1. **VFS 刷新**（可选，默认关闭）
 * 2. **扫描所有源文件** 收集 PSI 语法错误
 * 3. **返回所有问题** 以 Markdown 表格格式
 *
 * ## 参数说明
 *
 * - `refresh`: 是否刷新 VFS（默认 false，避免耗时）
 * - `maxProblems`: 最大返回问题数（默认 50）
 *
 * ## 注意事项
 *
 * - PSI 分析只能捕获**严重的语法错误**（如括号完全不匹配、字符串未闭合等）
 * - 大多数语法错误（如缺少 extends、类型错误）需要使用 MavenCompile 工具
 * - 此工具非常快速，适合在开发过程中快速检查
 *
 * @param project IDEA 项目
 * @param wslModeEnabled 是否启用 WSL 模式
 */
class FileProblemsTool(
    private val project: Project,
    private val wslModeEnabled: Boolean = false
) {

    fun getInputSchema(): Map<String, Any> = ToolSchemaLoader.getSchema("FileProblems")

    suspend fun execute(arguments: Map<String, Any>): Any {
        val maxProblems = ((arguments["maxProblems"] as? Number)?.toInt() ?: 50).coerceAtLeast(1)
        val refresh = arguments["refresh"] as? Boolean ?: false  // 默认不刷新，避免耗时

        return try {
            val result = performAnalysis(refresh, maxProblems)
            if (wslModeEnabled) {
                WslPathConverter.convertPathsInResult(result)
            } else {
                result
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error { "❌ Analysis timeout" }
            ToolResult.error("Analysis timed out. Please try again.")
        } catch (e: Exception) {
            logger.error(e) { "❌ Analysis failed: ${e.message}" }
            ToolResult.error("Analysis failed: ${e.message}")
        }
    }

    private suspend fun performAnalysis(
        refresh: Boolean,
        maxProblems: Int
    ): String {
        val projectPath = project.basePath ?: return "## ❌ Error\n\nCannot get project path"

        // Step 1: VFS 刷新（可选，默认跳过）
        if (refresh) {
            logger.debug { "🔄 Refreshing VFS for project" }
            try {
                val future = CompletableFuture<Unit>()
                ApplicationManager.getApplication().invokeLater {
                    WriteAction.run<Nothing> {
                        VirtualFileManager.getInstance().syncRefresh()
                    }
                    future.complete(Unit)
                }
                future.get(3, TimeUnit.SECONDS)  // 缩短超时时间
            } catch (e: Exception) {
                logger.warn(e) { "⚠️ VFS refresh failed, continuing anyway" }
            }
        }

        // Step 2: 收集所有源文件的问题
        val allProblems = collectAllProjectProblems(maxProblems)

        // Step 3: 格式化结果
        return formatResult(allProblems)
    }

    /**
     * 收集项目中所有文件的问题
     */
    private suspend fun collectAllProjectProblems(
        maxProblems: Int
    ): List<FileProblem> {
        return try {
            withTimeout(30_000) {  // 缩短超时时间到 30 秒
                collectProblemsInBackground(maxProblems)
            }
        } catch (e: Exception) {
            logger.warn(e) { "⚠️ Failed to collect problems: ${e.message}" }
            emptyList()
        }
    }

    private suspend fun collectProblemsInBackground(
        maxProblems: Int
    ): List<FileProblem> {
        return suspendCancellableCoroutine { cont ->
            val task = object : Task.Backgroundable(project, "Analyzing Project Problems", true) {
                private var problems: List<FileProblem> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false

                    problems = try {
                        DumbService.getInstance(project).runReadActionInSmartMode<List<FileProblem>> {
                            indicator.text = "Collecting project problems..."
                            indicator.fraction = 0.1

                            val projectPath = project.basePath ?: return@runReadActionInSmartMode emptyList()
                            val problemList = mutableListOf<FileProblem>()

                            // 获取所有源文件
                            val sourceFiles = getSourceFiles(projectPath)
                            indicator.fraction = 0.3

                            logger.info { "📂 Found ${sourceFiles.size} source files to analyze" }

                            // 分析每个文件
                            for ((index, psiFile) in sourceFiles.withIndex()) {
                                if (problemList.size >= maxProblems) break

                                if (index > 0 && index % 10 == 0) {
                                    indicator.fraction = 0.3 + 0.7 * (index.toDouble() / sourceFiles.size)
                                    indicator.text2 = "Analyzing ${psiFile.name}..."
                                }

                                val fileProblems = collectFileProblems(psiFile)
                                if (fileProblems.isNotEmpty()) {
                                    logger.debug { "📄 ${psiFile.name}: ${fileProblems.size} errors" }
                                    problemList.addAll(fileProblems)
                                }
                            }

                            logger.info { "✅ Collected ${problemList.size} total problems" }

                            // 过滤并限制结果数量
                            filterProblems(problemList, maxProblems)
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "❌ Error collecting problems" }
                        emptyList()
                    }
                }

                override fun onSuccess() {
                    if (cont.isActive) {
                        cont.resume(problems)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    logger.error(error) { "❌ Problems collection task failed" }
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                }
            }
            ProgressManager.getInstance().run(task)
            cont.invokeOnCancellation {
                logger.info { "⚠️ Problems collection coroutine cancelled" }
            }
        }
    }

    /**
     * 获取项目的所有源文件
     */
    private fun getSourceFiles(projectPath: String): List<PsiFile> {
        val sourceFiles = mutableListOf<PsiFile>()
        val psiManager = PsiManager.getInstance(project)

        // 常见源码目录
        val sourceDirs = listOf(
            "src/main/java", "src/main/kotlin", "src",
            "app/src/main/java", "app/src/main/kotlin",
            "src/test/java", "src/test/kotlin"
        )

        for (dirName in sourceDirs) {
            val dir = File(projectPath, dirName)
            if (!dir.exists()) continue

            val virtualDir = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(dir)
            if (virtualDir != null && virtualDir.isDirectory) {
                collectSourceFiles(virtualDir, psiManager, sourceFiles)
            }
        }

        return sourceFiles
    }

    private fun collectSourceFiles(
        virtualDir: com.intellij.openapi.vfs.VirtualFile,
        psiManager: PsiManager,
        accumulator: MutableList<PsiFile>
    ) {
        val children = virtualDir.children
        for (child in children) {
            if (child.isDirectory) {
                if (child.name !in setOf("build", "out", "target", ".git", "node_modules")) {
                    collectSourceFiles(child, psiManager, accumulator)
                }
            } else if (child.extension in setOf("java", "kt", "kts")) {
                // 只获取 PSI 文件，不使用 PsiFileFactory 后备方案（性能优化）
                psiManager.findFile(child)?.let { accumulator.add(it) }
            }
        }
    }

    /**
     * 收集单个文件的问题
     *
     * 使用 PSI 查找语法错误元素 (PsiErrorElement)
     *
     * **重要限制**：PSI 解析器设计为容错优先，只会检测 SEVERE 语法错误：
     * - 字符串未闭合：`String s = "hello;`
     * - 括号完全不匹配：`System.out.println("test";`
     *
     * 以下错误 PSI 无法检测（会被"修复"而不报错）：
     * - 缺少 `extends` 关键字：`class War SpringBootServletInitializer {}`
     * - 类型错误、缺少分号（某些情况）
     *
     * 对于完整的编译错误检测，请使用 MavenCompile 工具。
     */
    private fun collectFileProblems(psiFile: PsiFile): List<FileProblem> {
        val problems = mutableListOf<FileProblem>()
        val projectPath = project.basePath ?: return emptyList()

        // 检查文件是否有效
        if (!psiFile.isValid) {
            logger.debug { "⚠️ PsiFile is not valid: ${psiFile.name}" }
            return emptyList()
        }

        // 使用 PsiTreeUtil 查找所有 PsiErrorElement（PSI 解析错误）
        val errorElements = PsiTreeUtil.findChildrenOfType(
            psiFile,
            PsiErrorElement::class.java
        )

        if (errorElements.isNotEmpty()) {
            logger.debug { "📄 ${psiFile.name}: found ${errorElements.size} PSI error element(s)" }
        }

        for (element in errorElements) {
            val document = element.containingFile.viewProvider.document
            val (line, column) = if (document != null) {
                try {
                    val offset = element.textRange.startOffset
                    val lineNumber = document.getLineNumber(offset) + 1
                    val columnNumber = offset - document.getLineStartOffset(lineNumber - 1) + 1
                    lineNumber to columnNumber
                } catch (e: Exception) {
                    logger.debug(e) { "⚠️ Failed to get line/column for error in ${psiFile.name}" }
                    1 to 1
                }
            } else {
                1 to 1
            }

            val filePath = psiFile.virtualFile?.path ?: psiFile.name
            val relativePath = if (filePath.startsWith(projectPath)) {
                filePath.substring(projectPath.length + 1)
            } else {
                filePath
            }

            val errorDesc = element.errorDescription
            logger.debug { "   ❌ PSI Error Line $line: $errorDesc" }

            problems.add(
                FileProblem(
                    filePath = relativePath,
                    severity = ProblemSeverity.SYNTAX_ERROR,
                    message = errorDesc ?: "Syntax error",
                    line = line,
                    column = column
                )
            )
        }

        return problems
    }

    private fun filterProblems(
        problems: List<FileProblem>,
        maxProblems: Int
    ): List<FileProblem> {
        return problems
            .filter { it.severity == ProblemSeverity.SYNTAX_ERROR }
            .take(maxProblems)
            .sortedWith(compareBy({ it.filePath }, { it.line }, { it.column }))
    }

    private fun formatResult(problems: List<FileProblem>): String {
        val sb = StringBuilder()
        sb.appendLine("## 🔨 Project Analysis")
        sb.appendLine()
        sb.appendLine("---")

        // 分析状态
        val syntaxErrors = problems.count { it.severity == ProblemSeverity.SYNTAX_ERROR }
        val errors = problems.count { it.severity == ProblemSeverity.ERROR }

        when {
            syntaxErrors == 0 && errors == 0 -> {
                sb.append("📊 Status: **SUCCESS**")
            }
            else -> {
                sb.append("📊 Status: **FAILED** - ${syntaxErrors + errors} error(s)")
            }
        }

        sb.appendLine()
        sb.appendLine()

        // 问题列表
        if (problems.isEmpty()) {
            sb.appendLine("✅ **No syntax errors found**")
            sb.appendLine()
            sb.appendLine("> **Note**: This only checks for syntax errors. Type errors and dependency issues")
            sb.appendLine("> are not detected by PSI analysis. Use MavenCompile for full validation.")
        } else {
            sb.appendLine("### Issues")

            // 按文件分组
            val problemsByFile = problems.groupBy { it.filePath }

            // 限制显示的文件数量
            val maxFilesToShow = 10
            val filesToShow = problemsByFile.entries.take(maxFilesToShow)

            for ((filePath, fileProblems) in filesToShow) {
                sb.appendLine()
                sb.appendLine("#### `$filePath`")
                sb.appendLine()

                sb.appendLine("| Severity | Line | Column | Message |")
                sb.appendLine("|----------|------|--------|---------|")

                for (problem in fileProblems) {
                    val icon = when (problem.severity) {
                        ProblemSeverity.SYNTAX_ERROR -> "🚫"
                        ProblemSeverity.ERROR -> "❌"
                        ProblemSeverity.WARNING -> "⚠️"
                        ProblemSeverity.SUGGESTION -> "💡"
                    }
                    val escapedMessage = problem.message.replace("|", "\\|").replace("\n", " ")
                    sb.appendLine("| $icon | ${problem.line} | ${problem.column} | $escapedMessage |")
                }
            }

            if (problemsByFile.size > maxFilesToShow) {
                sb.appendLine()
                sb.appendLine("*... and ${problemsByFile.size - maxFilesToShow} more file(s) with issues*")
            }

            sb.appendLine()
        }

        sb.appendLine("---")

        // 统计信息
        val warnings = problems.count { it.severity == ProblemSeverity.WARNING }
        val suggestions = problems.count { it.severity == ProblemSeverity.SUGGESTION }

        val parts = mutableListOf<String>()
        if (syntaxErrors > 0) parts.add("🚫 **$syntaxErrors** syntax errors")
        if (errors > 0) parts.add("❌ **$errors** errors")
        if (warnings > 0) parts.add("⚠️ **$warnings** warnings")
        if (suggestions > 0) parts.add("💡 **$suggestions** suggestions")

        if (parts.isNotEmpty()) {
            sb.appendLine()
            sb.append("📊 Summary: ${parts.joinToString(" | ")}")
        }

        return sb.toString()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
