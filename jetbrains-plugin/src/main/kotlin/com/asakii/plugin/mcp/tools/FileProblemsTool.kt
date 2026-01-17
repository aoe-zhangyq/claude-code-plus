package com.asakii.plugin.mcp.tools

import com.asakii.claude.agent.sdk.mcp.ToolResult
import com.asakii.claude.agent.sdk.utils.WslPathConverter
import com.asakii.claude.agent.sdk.utils.WslPathDirection
import com.asakii.server.mcp.schema.ToolSchemaLoader
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementVisitor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import mu.KotlinLogging
import java.io.File
import kotlin.coroutines.resume

/**
 * 问题严重级别
 *
 * 分类说明：
 * - SYNTAX_ERROR: 语法/解析错误（PSI 解析器产生的错误，如缺少括号、分号等）
 * - ERROR: 代码错误（编译错误、类型错误等）
 * - WARNING: 警告（过时 API、潜在问题、可能的 bug）
 * - SUGGESTION: 建议（代码风格、未使用的符号、可优化项）
 *
 * @see ProblemHighlightType 对应的 IDEA 原生类型
 */
@Serializable
enum class ProblemSeverity {
    SYNTAX_ERROR, ERROR, WARNING, SUGGESTION
}

/**
 * 分析结果：区分语法错误、编译器错误和代码检查问题
 */
private data class AnalysisResult(
    val psiFile: PsiFile?,
    val syntaxErrors: List<ProblemDescriptor>,
    val highlightInfos: List<HighlightInfo>,
    val inspectionProblems: List<ProblemDescriptor>
)

/**
 * 文件问题数据结构
 */
@Serializable
data class FileProblem(
    val severity: ProblemSeverity,
    val message: String,
    val line: Int,          // 1-based
    val column: Int,        // 1-based
    val endLine: Int,       // 1-based
    val endColumn: Int,     // 1-based
    val description: String? = null
)

/**
 * 文件分析结果
 */
@Serializable
data class FileProblemsResult(
    val filePath: String,
    val problems: List<FileProblem>,
    val syntaxErrorCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val suggestionCount: Int,
    val hasErrors: Boolean
)

/**
 * 文件静态错误工具
 *
 * 获取文件的编译错误、警告和建议，无需打开文件即可分析。
 *
 * ## 设计原理
 *
 * ### 线程模型（参考 [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)）
 *
 * 1. **VFS 刷新**：使用 `invokeAndWait` + `WriteAction.run` 在 EDT 上同步执行
 *    - 必须使用 `ApplicationManager.invokeAndWait()` 而非 `SwingUtilities.invokeLater()`
 *    - 2025.1+ 变更：后者不再持有 write-intent lock
 *
 * 2. **等待索引完成**：使用 `DumbService.runReadActionInSmartMode()`
 *    - 在 dumb mode（索引未就绪）时自动等待，而非直接返回空结果
 *    - 参考 [DumbService 文档](https://plugins.jetbrains.com/docs/intellij/dumb-aware.html)
 *
 * 3. **PSI 分析**：在后台线程执行 `Task.Backgroundable`
 *
 * ### 问题来源
 *
 * 1. **PSI 语法错误**：`PsiErrorElement` - 解析器级别错误
 * 2. **HighlightInfo**：`DaemonCodeAnalyzer` 的已有高亮信息（包含编译器错误）
 * 3. **Inspection 问题**：`InspectionManager` 运行的代码检查
 *
 * ## 参数说明
 *
 * - `filePath`: 文件相对路径
 * - `refresh`: 是否刷新 VFS（默认 true，编辑文件后调用）
 * - `includeWarnings`: 是否包含警告（默认 true）
 * - `includeSuggestions`: 是否包含建议（默认 false）
 * - `maxProblems`: 最大返回问题数（默认 50）
 *
 * @param project IDEA 项目
 * @param wslModeEnabled 是否启用 WSL 模式（自动转换路径格式）
 *
 * @see <a href="https://plugins.jetbrains.com/docs/intellij/code-inspections.html">Code Inspections</a>
 * @see <a href="https://plugins.jetbrains.com/docs/intellij/threading-model.html">Threading Model</a>
 */
class FileProblemsTool(
    private val project: Project,
    private val wslModeEnabled: Boolean = false
) {

    fun getInputSchema(): Map<String, Any> = ToolSchemaLoader.getSchema("FileProblems")

    /**
     * 执行文件分析
     *
     * @param arguments 工具参数
     * @return 分析结果（Markdown 格式）或错误信息
     */
    suspend fun execute(arguments: Map<String, Any>): Any {
        // 路径处理
        val rawFilePath = arguments["filePath"] as? String
            ?: return ToolResult.error("Missing required parameter: filePath")

        val filePath = if (wslModeEnabled && WslPathConverter.isWslMountPath(rawFilePath)) {
            WslPathConverter.convertPath(rawFilePath, WslPathDirection.WSL_TO_WINDOWS)
        } else {
            rawFilePath
        }

        val includeWarnings = arguments["includeWarnings"] as? Boolean ?: true
        val includeSuggestions = arguments["includeSuggestions"] as? Boolean ?: false
        val includeWeakWarnings = arguments["includeWeakWarnings"] as? Boolean ?: includeSuggestions
        val maxProblems = ((arguments["maxProblems"] as? Number)?.toInt() ?: 50).coerceAtLeast(1)
        val refresh = arguments["refresh"] as? Boolean ?: true

        val projectPath = project.basePath
            ?: return ToolResult.error("Cannot get project path")

        val absolutePath = File(projectPath, filePath).canonicalPath

        if (!absolutePath.startsWith(File(projectPath).canonicalPath)) {
            return ToolResult.error("File path must be within project directory")
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            ?: return ToolResult.error("File not found: $filePath")

        // Step 1: VFS 刷新（使用 invokeLater + CompletableFuture 避免 invokeAndWait 的 WriteIntentReadAction 限制）
        if (refresh) {
            logger.debug { "🔄 Refreshing VFS for file: $filePath" }
            try {
                val future = CompletableFuture<Unit>()
                ApplicationManager.getApplication().invokeLater {
                    WriteAction.run<Nothing> {
                        VirtualFileManager.getInstance().syncRefresh()
                        virtualFile.refresh(true, false)
                    }
                    future.complete(Unit)
                }
                future.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                logger.warn(e) { "⚠️ VFS refresh failed, continuing anyway" }
            }
        }

        // Step 2: 在后台线程运行分析（等待索引完成后执行）
        val analysisResult = try {
            withTimeout(30_000) {
                runAnalysisInBackground(virtualFile, includeWarnings, includeWeakWarnings)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.error(e) { "❌ Analysis timed out after 30s" }
            return ToolResult.error("Analysis timed out. Please try again.")
        } catch (e: Exception) {
            logger.error(e) { "❌ Analysis failed" }
            return ToolResult.error("Analysis failed: ${e.message}")
        }

        if (analysisResult == null) {
            return ToolResult.error("Analysis failed: no result (project may still be indexing)")
        }

        // Step 3: 收集并格式化结果
        val problems = collectProblems(
            analysisResult,
            includeWarnings,
            includeWeakWarnings,
            maxProblems
        )

        return formatResult(filePath, problems)
    }

    /**
     * 在后台线程运行分析
     *
     * 使用 DumbService.runReadActionInSmartMode 确保索引完成后才执行分析
     * 参考：https://plugins.jetbrains.com/docs/intellij/dumb-aware.html
     */
    private suspend fun runAnalysisInBackground(
        virtualFile: com.intellij.openapi.vfs.VirtualFile,
        includeWarnings: Boolean,
        includeWeakWarnings: Boolean
    ): AnalysisResult? {
        return suspendCancellableCoroutine { cont ->
            val task = object : Task.Backgroundable(project, "Analyzing File Problems", true) {
                private var result: AnalysisResult? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false

                    result = try {
                        // 使用 runReadActionInSmartMode 等待索引完成
                        DumbService.getInstance(project).runReadActionInSmartMode<AnalysisResult> {
                            indicator.text = "Waiting for index to complete..."
                            indicator.fraction = 0.1

                            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                            if (psiFile == null) {
                                logger.debug { "⚠️ Could not find PsiFile for ${virtualFile.path}" }
                                return@runReadActionInSmartMode AnalysisResult(null, emptyList(), emptyList(), emptyList())
                            }

                            indicator.text2 = "Analyzing ${psiFile.name}..."
                            indicator.fraction = 0.5

                            performAnalysis(psiFile, includeWarnings, includeWeakWarnings)
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "❌ Error during analysis" }
                        null
                    }
                }

                override fun onSuccess() {
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }

                override fun onThrowable(error: Throwable) {
                    logger.error(error) { "❌ Analysis task failed" }
                    if (cont.isActive) {
                        cont.resume(null)
                    }
                }
            }
            ProgressManager.getInstance().run(task)
            cont.invokeOnCancellation {
                logger.info { "⚠️ Analysis coroutine cancelled" }
            }
        }
    }

    /**
     * 执行实际的 PSI 分析
     *
     * @param psiFile 要分析的 PSI 文件
     * @param includeWarnings 是否包含警告
     * @param includeWeakWarnings 是否包含弱警告
     * @return 分析结果
     */
    private fun performAnalysis(
        psiFile: PsiFile,
        includeWarnings: Boolean,
        includeWeakWarnings: Boolean
    ): AnalysisResult {
        // 1. 收集 PSI 语法错误（解析器级别）
        val syntaxErrors = collectSyntaxErrors(psiFile)
        logger.debug { "📊 Found ${syntaxErrors.size} PSI syntax errors" }

        // 2. 获取已有的 HighlightInfo（编译器错误）
        // 注意：这依赖于文件已被 IDEA 分析过（在编辑器中打开过）
        val highlightInfos = getHighlightInfos(psiFile)
        logger.debug { "📊 Found ${highlightInfos.size} highlight infos" }

        // 3. 运行代码检查（可选）
        val inspectionProblems = runInspections(psiFile, includeWarnings, includeWeakWarnings)
        logger.debug { "📊 Found ${inspectionProblems.size} inspection problems" }

        return AnalysisResult(psiFile, syntaxErrors, highlightInfos, inspectionProblems)
    }

    /**
     * 收集 PSI 语法错误
     *
     * PSI 语法错误是解析器在解析代码时产生的错误，例如：
     * - 缺少分号、括号不匹配
     * - 意外的 token
     * - 不完整的语句
     *
     * @param psiFile PSI 文件
     * @return 语法错误描述符列表
     */
    private fun collectSyntaxErrors(psiFile: PsiFile): List<ProblemDescriptor> {
        val problems = mutableListOf<ProblemDescriptor>()
        val inspectionManager = InspectionManager.getInstance(project)

        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitErrorElement(element: PsiErrorElement) {
                super.visitErrorElement(element)

                val descriptor = inspectionManager.createProblemDescriptor(
                    element,
                    element.errorDescription,
                    false,
                    emptyArray(),
                    ProblemHighlightType.ERROR
                )
                problems.add(descriptor)
            }
        })

        return problems
    }

    /**
     * 获取 HighlightInfo（编译器错误和警告）
     *
     * 从 DaemonCodeAnalyzer 获取已有的高亮信息。
     * 注意：此功能依赖于文件已被 IDEA 分析过（在编辑器中打开过）。
     *
     * 由于 `getFileHighlightsMap` API 在不同版本中可能有变化，
     * 我们使用 `getErrorHighlightType` 作为后备方案。
     *
     * @param psiFile PSI 文件
     * @return HighlightInfo 列表
     */
    private fun getHighlightInfos(psiFile: PsiFile): List<HighlightInfo> {
        val problems = mutableListOf<HighlightInfo>()

        try {
            val daemonCodeAnalyzer = DaemonCodeAnalyzerImpl.getInstance(project)
                ?: return emptyList()

            // 尝试获取文件的高亮信息
            // 方法 1: 使用 getFileHighlightsMap（如果存在）
            try {
                val method = daemonCodeAnalyzer.javaClass.getDeclaredMethod(
                    "getFileHighlightsMap",
                    com.intellij.psi.PsiFile::class.java
                )
                method.isAccessible = true

                @Suppress("UNCHECKED_CAST")
                val highlightsMap = method.invoke(daemonCodeAnalyzer, psiFile) as? Map<HighlightInfo, List<HighlightInfo>>

                if (highlightsMap != null) {
                    highlightsMap.values.forEach { problems.addAll(it) }
                    problems.addAll(highlightsMap.keys)
                    return problems
                }
            } catch (e: NoSuchMethodException) {
                logger.debug { "⚠️ getFileHighlightsMap method not found, trying alternative" }
            }

            // 方法 2: 后备方案 - 尝试直接获取错误状态
            // 由于 API 变化较大，这里我们返回空列表
            // 实际的错误信息主要通过 InspectionEngine 获取
        } catch (e: Exception) {
            logger.debug { "⚠️ Failed to get highlight infos: ${e.message}" }
        }

        return problems
    }

    /**
     * 运行代码检查
     *
     * 运行项目中已启用的 LocalInspectionTool
     * 参考：https://plugins.jetbrains.com/docs/intellij/code-inspections.html
     *
     * @param psiFile PSI 文件
     * @param includeWarnings 是否包含警告
     * @param includeWeakWarnings 是否包含弱警告
     * @return 问题描述符列表
     */
    private fun runInspections(
        psiFile: PsiFile,
        includeWarnings: Boolean,
        includeWeakWarnings: Boolean
    ): List<ProblemDescriptor> {
        val problems = mutableListOf<ProblemDescriptor>()
        val inspectionManager = InspectionManager.getInstance(project)

        // 获取当前项目的检查配置
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile as? InspectionProfileImpl
            ?: return emptyList()

        // 获取所有启用的检查工具
        val toolsList = profile.getAllEnabledInspectionTools(project)

        for (tools in toolsList) {
            val toolWrapper = tools.tool
            if (toolWrapper !is com.intellij.codeInspection.ex.LocalInspectionToolWrapper) continue

            val configuredLevel = tools.defaultState.level
            val isWarning = configuredLevel == com.intellij.codeHighlighting.HighlightDisplayLevel.WARNING
            val isWeakWarning = configuredLevel == com.intellij.codeHighlighting.HighlightDisplayLevel.WEAK_WARNING ||
                               configuredLevel == com.intellij.codeHighlighting.HighlightDisplayLevel.DO_NOT_SHOW

            if (!includeWarnings && isWarning) continue
            if (!includeWeakWarnings && isWeakWarning) continue

            try {
                val context = inspectionManager.createNewGlobalContext()
                val descriptors = com.intellij.codeInspection.InspectionEngine.runInspectionOnFile(
                    psiFile,
                    toolWrapper,
                    context
                )
                problems.addAll(descriptors)
            } catch (e: Exception) {
                logger.debug { "⚠️ Inspection ${toolWrapper.shortName} failed: ${e.message}" }
            }
        }

        return problems
    }

    /**
     * 收集所有问题
     */
    private fun collectProblems(
        result: AnalysisResult,
        includeWarnings: Boolean,
        includeWeakWarnings: Boolean,
        maxProblems: Int
    ): List<FileProblem> {
        val problems = mutableListOf<FileProblem>()
        var syntaxErrorCount = 0
        var errorCount = 0
        var warningCount = 0
        var suggestionCount = 0

        // 1. 处理语法错误（始终包含）
        for (descriptor in result.syntaxErrors) {
            if (problems.size >= maxProblems) break
            syntaxErrorCount++
            addProblemFromDescriptor(descriptor, ProblemSeverity.SYNTAX_ERROR, problems, result.psiFile)
        }

        // 2. 处理 HighlightInfo（编译器错误）
        for (info in result.highlightInfos) {
            if (problems.size >= maxProblems) break

            val severity = classifyHighlightInfo(info.severity)
            when (severity) {
                ProblemSeverity.SYNTAX_ERROR -> continue
                ProblemSeverity.ERROR -> errorCount++
                ProblemSeverity.WARNING -> {
                    if (!includeWarnings) continue
                    warningCount++
                }
                ProblemSeverity.SUGGESTION -> {
                    if (!includeWeakWarnings) continue
                    suggestionCount++
                }
            }

            addProblemFromHighlightInfo(info, severity, problems, result.psiFile)
        }

        // 3. 处理代码检查问题
        for (descriptor in result.inspectionProblems) {
            if (problems.size >= maxProblems) break

            val severity = classifyProblemDescriptor(descriptor)
            when (severity) {
                ProblemSeverity.SYNTAX_ERROR -> continue
                ProblemSeverity.ERROR -> errorCount++
                ProblemSeverity.WARNING -> {
                    if (!includeWarnings) continue
                    warningCount++
                }
                ProblemSeverity.SUGGESTION -> {
                    if (!includeWeakWarnings) continue
                    suggestionCount++
                }
            }

            addProblemFromDescriptor(descriptor, severity, problems, result.psiFile)
        }

        return problems.sortedWith(compareBy({ it.severity.ordinal }, { it.line }, { it.column }))
    }

    /**
     * 根据 HighlightSeverity 分类问题
     */
    private fun classifyHighlightInfo(severity: HighlightSeverity): ProblemSeverity {
        return when {
            severity >= HighlightSeverity.ERROR -> ProblemSeverity.ERROR
            severity >= HighlightSeverity.WARNING -> ProblemSeverity.WARNING
            severity >= HighlightSeverity.WEAK_WARNING -> ProblemSeverity.SUGGESTION
            else -> ProblemSeverity.SUGGESTION
        }
    }

    /**
     * 根据 ProblemDescriptor 分类问题
     */
    private fun classifyProblemDescriptor(descriptor: ProblemDescriptor): ProblemSeverity {
        return when (descriptor.highlightType) {
            ProblemHighlightType.ERROR,
            ProblemHighlightType.GENERIC_ERROR -> ProblemSeverity.ERROR
            ProblemHighlightType.WARNING -> ProblemSeverity.WARNING
            ProblemHighlightType.WEAK_WARNING,
            ProblemHighlightType.INFORMATION,
            ProblemHighlightType.LIKE_UNUSED_SYMBOL -> ProblemSeverity.SUGGESTION
            else -> ProblemSeverity.SUGGESTION
        }
    }

    /**
     * 从 ProblemDescriptor 创建 FileProblem
     */
    private fun addProblemFromDescriptor(
        descriptor: ProblemDescriptor,
        severity: ProblemSeverity,
        problems: MutableList<FileProblem>,
        psiFile: PsiFile?
    ) {
        val psiElement = descriptor.psiElement
        val textRange = descriptor.textRangeInElement ?: psiElement?.textRange
        val document = psiElement?.containingFile?.viewProvider?.document

        val (line, column, endLine, endColumn) = if (document != null && textRange != null) {
            try {
                val startLine = document.getLineNumber(textRange.startOffset) + 1
                val startCol = textRange.startOffset - document.getLineStartOffset(startLine - 1) + 1
                val endL = document.getLineNumber(textRange.endOffset) + 1
                val endCol = textRange.endOffset - document.getLineStartOffset(endL - 1) + 1
                listOf(startLine, startCol, endL, endCol)
            } catch (e: Exception) {
                listOf(1, 1, 1, 1)
            }
        } else {
            listOf(1, 1, 1, 1)
        }

        problems.add(FileProblem(
            severity = severity,
            message = descriptor.descriptionTemplate ?: "Unknown issue",
            line = line,
            column = column,
            endLine = endLine,
            endColumn = endColumn,
            description = descriptor.toString()
        ))
    }

    /**
     * 从 HighlightInfo 创建 FileProblem
     */
    private fun addProblemFromHighlightInfo(
        info: HighlightInfo,
        severity: ProblemSeverity,
        problems: MutableList<FileProblem>,
        psiFile: PsiFile?
    ) {
        val document = psiFile?.viewProvider?.document

        val (line, column, endLine, endColumn) = if (document != null) {
            try {
                val startLine = document.getLineNumber(info.startOffset) + 1
                val startCol = info.startOffset - document.getLineStartOffset(startLine - 1) + 1
                val endL = document.getLineNumber(info.endOffset) + 1
                val endCol = info.endOffset - document.getLineStartOffset(endL - 1) + 1
                listOf(startLine, startCol, endL, endCol)
            } catch (e: Exception) {
                listOf(1, 1, 1, 1)
            }
        } else {
            listOf(1, 1, 1, 1)
        }

        val errorMessage = info.description
            ?: info.toolTip
            ?: buildString {
                append("Severity: ")
                append(info.severity)
                append(", Type: ")
                append(info.type?.toString() ?: "Unknown")
            }

        problems.add(FileProblem(
            severity = severity,
            message = errorMessage,
            line = line,
            column = column,
            endLine = endLine,
            endColumn = endColumn,
            description = info.toolTip
        ))
    }

    /**
     * 格式化结果为 Markdown
     */
    private fun formatResult(filePath: String, problems: List<FileProblem>): String {
        val sb = StringBuilder()
        sb.appendLine("## 📄 File: `$filePath`")
        sb.appendLine()

        val syntaxErrorCount = problems.count { it.severity == ProblemSeverity.SYNTAX_ERROR }
        val errorCount = problems.count { it.severity == ProblemSeverity.ERROR }
        val warningCount = problems.count { it.severity == ProblemSeverity.WARNING }
        val suggestionCount = problems.count { it.severity == ProblemSeverity.SUGGESTION }

        if (problems.isEmpty()) {
            sb.appendLine("✅ **No issues found**")
        } else {
            sb.appendLine("| Severity | Location | Message |")
            sb.appendLine("|----------|----------|---------|")
            problems.forEach { problem ->
                val icon = when (problem.severity) {
                    ProblemSeverity.SYNTAX_ERROR -> "🚫"
                    ProblemSeverity.ERROR -> "❌"
                    ProblemSeverity.WARNING -> "⚠️"
                    ProblemSeverity.SUGGESTION -> "💡"
                }
                val location = "${problem.line}:${problem.column}"
                val escapedMessage = problem.message.replace("|", "\\|").replace("\n", " ")
                sb.appendLine("| $icon | `$location` | $escapedMessage |")
            }
        }

        sb.appendLine()
        sb.appendLine("---")
        val parts = mutableListOf<String>()
        if (syntaxErrorCount > 0) parts.add("🚫 **$syntaxErrorCount** syntax errors")
        if (errorCount > 0) parts.add("❌ **$errorCount** errors")
        if (warningCount > 0) parts.add("⚠️ **$warningCount** warnings")
        if (suggestionCount > 0) parts.add("💡 **$suggestionCount** suggestions")
        if (parts.isEmpty()) {
            sb.append("📊 No problems")
        } else {
            sb.append("📊 Summary: ${parts.joinToString(" | ")}")
        }

        val result = sb.toString()

        return if (wslModeEnabled) {
            WslPathConverter.convertPathsInResult(result)
        } else {
            result
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
