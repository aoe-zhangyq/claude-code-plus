package com.asakii.plugin.mcp.tools

import com.asakii.claude.agent.sdk.mcp.ToolResult
import com.asakii.claude.agent.sdk.utils.WslPathConverter
import com.asakii.claude.agent.sdk.utils.WslPathDirection
import com.asakii.server.mcp.schema.ToolSchemaLoader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompileStatusNotification
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 编译错误详情
 */
@Serializable
data class CompilerErrorDetail(
    val filePath: String,
    val line: Int,
    val column: Int,
    val message: String
)

/**
 * 带错误详情的构建结果
 */
private data class BuildResultWithDetails(
    val aborted: Boolean,
    val errors: Int,
    val warnings: Int,
    val errorDetails: List<CompilerErrorDetail>
)

/**
 * IDEA 文件构建工具
 *
 * 触发 IDEA 的增量编译（Make Project）。
 *
 * ## 工具定位
 *
 * ```
 * FileProblems (轻量) → FileBuild (增量编译) → MavenCompile (命令行兜底)
 * ```
 *
 * ## 设计说明
 *
 * 使用 `CompilerManager.make(Module, CompileStatusNotification)` 触发增量编译。
 *
 * 根据 [CompilerManager API 文档](https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/compiler/CompilerManager.html)：
 * > `make(Module module, CompileStatusNotification callback)` - Compile all modified files and all files that depend on them throughout the entire project
 *
 * 这意味着传入单个 Module 也会编译整个项目的相关文件，这正是增量编译的行为。
 *
 * ## 错误详情收集
 *
 * 由于 `CompileStatusNotification` 回调只提供错误/警告计数，不包含详细错误信息，
 * 编译完成后会通过 PSI 分析收集项目的语法错误。
 *
 * 限制：
 * - 只能收集 PSI 级别的语法错误（如缺少分号、括号不匹配等）
 * - 编译器产生的类型错误等需要使用 FileProblems 工具针对具体文件进行分析
 *
 * @param project IDEA 项目
 * @param wslModeEnabled 是否启用 WSL 模式
 *
 * @see <a href="https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/compiler/CompilerManager.html">CompilerManager API</a>
 * @see <a href="https://github.com/JetBrains/intellij-community/blob/master/java/compiler/openapi/src/com/intellij/openapi/compiler/CompilerManager.java">CompilerManager 源码</a>
 */
class FileBuildTool(
    private val project: Project,
    private val wslModeEnabled: Boolean = false
) {

    fun getInputSchema(): Map<String, Any> = ToolSchemaLoader.getSchema("FileBuild")

    suspend fun execute(arguments: Map<String, Any>): Any {
        val forceRebuild = arguments["forceRebuild"] as? Boolean ?: false
        val refresh = arguments["refresh"] as? Boolean ?: true
        val timeoutSec = (arguments["timeout"] as? Number)?.toInt() ?: 120

        return try {
            val result = performBuild(forceRebuild, refresh, timeoutSec)
            if (wslModeEnabled) {
                WslPathConverter.convertPathsInResult(result)
            } else {
                result
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error { "❌ Build timeout after ${timeoutSec}s" }
            ToolResult.error("Build timeout after ${timeoutSec}s")
        } catch (e: Exception) {
            logger.error(e) { "❌ Build failed: ${e.message}" }
            ToolResult.error("Build failed: ${e.message}")
        }
    }

    private suspend fun performBuild(
        forceRebuild: Boolean,
        refresh: Boolean,
        timeoutSec: Int
    ): String {
        val projectPath = project.basePath
            ?: return "## ❌ Error\n\nCannot get project path"

        // VFS 刷新（必须在 EDT 上执行）
        // 使用 invokeLater + CompletableFuture 避免 invokeAndWait 的 WriteIntentReadAction 限制
        if (refresh) {
            try {
                val future = CompletableFuture<Unit>()
                ApplicationManager.getApplication().invokeLater {
                    WriteAction.run<Nothing> {
                        VirtualFileManager.getInstance().syncRefresh()
                    }
                    future.complete(Unit)
                }
                future.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                logger.warn(e) { "⚠️ VFS refresh failed, continuing anyway" }
            }
        }

        // 强制重新构建
        if (forceRebuild) {
            cleanOutputDirectories(projectPath)
        }

        // 执行增量编译并收集错误详情
        val buildResult = runIncrementalBuildWithDetails(timeoutSec)

        // 格式化结果
        return formatBuildResultWithDetails(
            buildResult.aborted,
            buildResult.errors,
            buildResult.warnings,
            buildResult.errorDetails
        )
    }

    /**
     * 运行 IDEA 增量编译并收集错误详情
     *
     * 使用 `CompilerManager.make(Module, CompileStatusNotification)` 触发编译。
     * 编译完成后，通过 PSI 分析收集项目的语法错误详情。
     *
     * **注意**：`CompilerManager.make()` 必须在 EDT 上执行。
     * 使用 `invokeLater` + `CompletableFuture` 避免 `invokeAndWait` 的 WriteIntentReadAction 限制。
     */
    private suspend fun runIncrementalBuildWithDetails(timeoutSec: Int): BuildResultWithDetails {
        // 获取项目模块（提前检查，避免在 withTimeout 内部 return）
        val modules = com.intellij.openapi.module.ModuleManager.getInstance(project).modules
        if (modules.isEmpty()) {
            return BuildResultWithDetails(false, 0, 0, emptyList())
        }

        // 使用第一个模块触发编译
        val module = modules[0]

        return withTimeout(timeoutSec * 1000L) {
            // 存储编译结果
            val resultHolder = arrayOf<BuildResult?>(null)
            val future = CompletableFuture<Unit>()

            // 在 EDT 上执行编译（CompilerManager.make() 要求 EDT）
            ApplicationManager.getApplication().invokeLater {
                try {
                    val compilerManager = CompilerManager.getInstance(project)

                    // 创建回调
                    val notification = CompileStatusNotification { aborted, errors, warnings, _ ->
                        resultHolder[0] = BuildResult(aborted, errors, warnings)
                        logger.info { "✅ Build finished: aborted=$aborted, errors=$errors, warnings=$warnings" }
                        future.complete(Unit)
                    }

                    // 启动编译
                    compilerManager.make(module, notification)
                } catch (e: Exception) {
                    logger.error(e) { "❌ Build execution failed" }
                    resultHolder[0] = BuildResult(false, -1, 0)
                    future.completeExceptionally(e)
                }
            }

            // 等待编译完成
            future.get(timeoutSec.toLong(), TimeUnit.SECONDS)

            // 等待一小段时间确保回调完成
            var attempts = 0
            while (resultHolder[0] == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }

            val result = resultHolder[0] ?: BuildResult(false, 0, 0)

            // 如果有错误，收集错误详情
            val errorDetails = if (result.errors > 0) {
                collectErrorDetails()
            } else {
                emptyList()
            }

            BuildResultWithDetails(result.aborted, result.errors, result.warnings, errorDetails)
        }
    }

    /**
     * 收集项目的错误详情
     *
     * 通过 PSI 分析收集语法错误。
     * 注意：这只收集 PSI 级别的语法错误，不包含编译器的类型错误。
     */
    private suspend fun collectErrorDetails(): List<CompilerErrorDetail> {
        return try {
            withTimeout(30_000) {
                collectSyntaxErrorsInBackground()
            }
        } catch (e: Exception) {
            logger.warn(e) { "⚠️ Failed to collect error details: ${e.message}" }
            emptyList()
        }
    }

    /**
     * 在后台线程收集语法错误
     */
    private suspend fun collectSyntaxErrorsInBackground(): List<CompilerErrorDetail> {
        return suspendCancellableCoroutine { cont ->
            val task = object : Task.Backgroundable(project, "Collecting Build Errors", true) {
                private var errors: List<CompilerErrorDetail> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false

                    errors = try {
                        // 使用 runReadActionInSmartMode 等待索引完成
                        DumbService.getInstance(project).runReadActionInSmartMode<List<CompilerErrorDetail>> {
                            indicator.text = "Collecting syntax errors..."
                            indicator.fraction = 0.1

                            val errorList = mutableListOf<CompilerErrorDetail>()
                            val projectPath = project.basePath ?: return@runReadActionInSmartMode emptyList()

                            // 获取所有源文件
                            val sourceFiles = getSourceFiles(projectPath)
                            indicator.fraction = 0.5

                            // 分析每个源文件
                            for ((index, sourceFile) in sourceFiles.withIndex()) {
                                if (index > 0 && index % 10 == 0) {
                                    indicator.fraction = 0.5 + 0.5 * (index.toDouble() / sourceFiles.size)
                                    indicator.text2 = "Analyzing ${sourceFile.name}..."
                                }

                                val fileErrors = collectFileSyntaxErrors(sourceFile)
                                errorList.addAll(fileErrors)
                            }

                            errorList
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "❌ Error collecting syntax errors" }
                        emptyList()
                    }
                }

                override fun onSuccess() {
                    if (cont.isActive) {
                        cont.resume(errors)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    logger.error(error) { "❌ Syntax errors collection task failed" }
                    if (cont.isActive) {
                        cont.resume(emptyList())
                    }
                }
            }
            ProgressManager.getInstance().run(task)
            cont.invokeOnCancellation {
                logger.info { "⚠️ Syntax errors collection coroutine cancelled" }
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
        val sourceDirs = listOf("src/main/java", "src/main/kotlin", "src", "app/src/main/java", "app/src/main/kotlin")

        for (dirName in sourceDirs) {
            val dir = File(projectPath, dirName)
            if (!dir.exists()) continue

            val virtualDir = LocalFileSystem.getInstance().findFileByIoFile(dir)
            if (virtualDir != null && virtualDir.isDirectory) {
                collectSourceFiles(virtualDir, psiManager, sourceFiles)
            }
        }

        return sourceFiles
    }

    /**
     * 递归收集目录中的源文件
     */
    private fun collectSourceFiles(
        virtualDir: com.intellij.openapi.vfs.VirtualFile,
        psiManager: PsiManager,
        accumulator: MutableList<PsiFile>
    ) {
        val children = virtualDir.children
        for (child in children) {
            if (child.isDirectory) {
                // 跳过常见的非源码目录
                if (child.name !in setOf("build", "out", "target", ".git", "node_modules")) {
                    collectSourceFiles(child, psiManager, accumulator)
                }
            } else if (child.extension in setOf("java", "kt", "kts")) {
                val psiFile = psiManager.findFile(child)
                if (psiFile != null) {
                    accumulator.add(psiFile)
                }
            }
        }
    }

    /**
     * 收集单个文件的语法错误
     */
    private fun collectFileSyntaxErrors(psiFile: PsiFile): List<CompilerErrorDetail> {
        val errors = mutableListOf<CompilerErrorDetail>()
        val projectPath = project.basePath ?: return emptyList()

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitErrorElement(element: PsiErrorElement) {
                super.visitErrorElement(element)

                val document = element.containingFile.viewProvider.document
                val (line, column) = if (document != null) {
                    try {
                        val offset = element.textRange.startOffset
                        val lineNumber = document.getLineNumber(offset) + 1
                        val columnNumber = offset - document.getLineStartOffset(lineNumber - 1) + 1
                        lineNumber to columnNumber
                    } catch (e: Exception) {
                        1 to 1
                    }
                } else {
                    1 to 1
                }

                // 获取相对路径
                val filePath = psiFile.virtualFile.path
                val relativePath = if (filePath.startsWith(projectPath)) {
                    filePath.substring(projectPath.length + 1)
                } else {
                    filePath
                }

                errors.add(
                    CompilerErrorDetail(
                        filePath = relativePath,
                        line = line,
                        column = column,
                        message = element.errorDescription ?: "Syntax error"
                    )
                )
            }
        })

        return errors
    }

    /**
     * 格式化构建结果（包含错误详情）
     */
    private fun formatBuildResultWithDetails(
        aborted: Boolean,
        errors: Int,
        warnings: Int,
        errorDetails: List<CompilerErrorDetail>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("## 🔨 IDEA Build")
        sb.appendLine()
        sb.appendLine("---")

        when {
            errors == 0 && !aborted -> {
                sb.append("📊 Status: **SUCCESS**")
                if (warnings > 0) sb.append(" with $warnings warning(s)")
            }
            errors > 0 -> sb.append("📊 Status: **FAILED** - $errors error(s)")
            aborted -> sb.append("📊 Status: **ABORTED**")
            else -> sb.append("📊 Status: **UNKNOWN**")
        }

        sb.appendLine()
        sb.appendLine()

        // 如果有错误详情，显示出来
        if (errorDetails.isNotEmpty()) {
            sb.appendLine("### ❌ Errors")

            // 限制显示的错误数量
            val maxErrorsToShow = 20
            val displayErrors = errorDetails.take(maxErrorsToShow)

            sb.appendLine()
            sb.appendLine("| File | Line | Column | Message |")
            sb.appendLine("|------|------|--------|---------|")

            for (error in displayErrors) {
                val escapedPath = error.filePath.replace("|", "\\|")
                val escapedMessage = error.message.replace("|", "\\|").replace("\n", " ")
                sb.appendLine("| `${escapedPath}` | ${error.line} | ${error.column} | ${escapedMessage} |")
            }

            if (errorDetails.size > maxErrorsToShow) {
                sb.appendLine()
                sb.appendLine("*... and ${errorDetails.size - maxErrorsToShow} more error(s)*")
            }

            sb.appendLine()
        }

        sb.appendLine("---")

        return sb.toString()
    }

    private fun cleanOutputDirectories(projectPath: String) {
        // 使用 invokeLater + CompletableFuture 避免 WriteAction 从后台线程调用的问题
        val future = CompletableFuture<Unit>()
        ApplicationManager.getApplication().invokeLater {
            WriteAction.run<Nothing> {
                val outDir = File(projectPath, "out")
                if (outDir.exists()) {
                    logger.info { "🧹 Deleting: ${outDir.path}" }
                    deleteRecursively(outDir)
                }
            }
            future.complete(Unit)
        }
        future.get(10, TimeUnit.SECONDS)
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }

    private data class BuildResult(val aborted: Boolean, val errors: Int, val warnings: Int)

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
