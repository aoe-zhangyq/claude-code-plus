package com.asakii.plugin.mcp.svn

import com.asakii.plugin.services.SvnService
import com.intellij.openapi.project.Project
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 获取 SVN 历史记录工具
 *
 * 返回 SVN 提交历史，包括作者、日期、消息和变更路径
 * 返回格式：Markdown
 */
class GetSvnHistoryTool(private val project: Project) {

    suspend fun execute(arguments: Map<String, Any?>): String {
        val maxCount = (arguments["maxCount"] as? Number)?.toInt() ?: 20
        val revision = arguments["revision"] as? String
        val filePath = arguments["filePath"] as? String

        val svnService = SvnService.getInstance(project)

        if (!svnService.isSvnAvailable()) {
            return buildString {
                appendLine("# SVN History")
                appendLine()
                appendLine("**Error**: SVN is not available for this project.")
                appendLine()
                appendLine("Please ensure the project is under SVN version control.")
            }
        }

        val commits = when {
            revision != null -> {
                // 获取特定修订版信息
                svnService.getCommitInfo(revision)?.let { listOf(it) } ?: emptyList()
            }
            filePath != null -> {
                // 获取特定文件的历史
                svnService.getFileHistory(filePath, maxCount)
            }
            else -> {
                // 获取最近的提交历史
                svnService.getRecentCommits(maxCount)
            }
        }

        return buildString {
            appendLine("# SVN History")
            appendLine()

            val title = when {
                revision != null -> "Revision r$revision"
                filePath != null -> "File: $filePath"
                else -> "Recent Commits"
            }
            appendLine("## $title")
            appendLine("- **Total**: ${commits.size} commit(s)")
            appendLine()

            if (commits.isEmpty()) {
                appendLine("*No commit history found.*")
                return@buildString
            }

            for (commit in commits) {
                appendLine("### r${commit.revision} - ${commit.author}")
                appendLine()
                appendLine("**Date**: ${commit.date}")
                appendLine()
                appendLine("**Message**:")
                appendLine("```")
                appendLine(commit.message.trim())
                appendLine("```")
                appendLine()

                if (commit.changedPaths.isNotEmpty()) {
                    val pathsToShow = commit.changedPaths.take(10)
                    appendLine("**Changed Paths** (${commit.changedPaths.size} total):")
                    pathsToShow.forEach { path ->
                        appendLine("- `$path`")
                    }
                    if (commit.changedPaths.size > 10) {
                        appendLine("- ... and ${commit.changedPaths.size - 10} more")
                    }
                    appendLine()
                }
            }
        }
    }
}
