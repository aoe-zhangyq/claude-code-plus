package com.asakii.plugin.mcp.svn

import com.asakii.plugin.compat.VcsCompat
import com.asakii.plugin.services.SvnService
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 获取 SVN 状态工具
 *
 * 返回 VCS 状态概览：当前分支/URL、修订版本、变更数量等
 * 返回格式：Markdown
 */
class GetSvnStatusTool(private val project: Project) {

    suspend fun execute(arguments: Map<String, Any?>): String {
        return ReadAction.compute<String, Throwable> {
            try {
                val changeListManager = ChangeListManager.getInstance(project)
                val changes = changeListManager.allChanges

                // 获取活跃的 VCS
                val activeVcss = VcsCompat.getAllActiveVcss(project)
                val hasVcs = activeVcss.isNotEmpty()
                val vcsType = activeVcss.firstOrNull()?.name

                // 使用 SvnService 获取 SVN 特定信息
                val svnService = SvnService.getInstance(project)
                val currentUrl = svnService.getCurrentUrl()
                val branchInfo = svnService.getBranchInfo()
                val currentRevision = svnService.getCurrentRevision()

                // 按变更类型统计
                val newCount = changes.count { it.type == Change.Type.NEW }
                val modifiedCount = changes.count { it.type == Change.Type.MODIFICATION }
                val deletedCount = changes.count { it.type == Change.Type.DELETED }
                val movedCount = changes.count { it.type == Change.Type.MOVED }
                val unversionedCount = changeListManager.unversionedFilesPaths.size

                buildString {
                    appendLine("# SVN Status")
                    appendLine()

                    // 基本信息
                    appendLine("## Overview")
                    appendLine("- **VCS Enabled**: ${if (hasVcs) "Yes" else "No"}")
                    if (vcsType != null) {
                        appendLine("- **VCS Type**: $vcsType")
                    }
                    if (currentUrl != null) {
                        appendLine("- **Repository URL**: `$currentUrl`")
                    }
                    if (branchInfo != null) {
                        appendLine("- **Branch/Tag**: `$branchInfo`")
                    }
                    if (currentRevision != null) {
                        appendLine("- **Current Revision**: r$currentRevision")
                    }
                    appendLine()

                    // 变更统计
                    appendLine("## Changes Summary")
                    appendLine("| Type | Count |")
                    appendLine("|------|-------|")
                    appendLine("| New | $newCount |")
                    appendLine("| Modified | $modifiedCount |")
                    appendLine("| Deleted | $deletedCount |")
                    appendLine("| Moved | $movedCount |")
                    appendLine("| **Total** | **${changes.size}** |")
                    if (unversionedCount > 0) {
                        appendLine("| Unversioned | $unversionedCount |")
                    }
                    appendLine()

                    // 变更列表信息
                    val changeLists = changeListManager.changeLists
                    if (changeLists.isNotEmpty()) {
                        appendLine("## Change Lists")
                        for (changeList in changeLists) {
                            val defaultMarker = if (changeList.isDefault) " (default)" else ""
                            appendLine("- **${changeList.name}**$defaultMarker: ${changeList.changes.size} changes")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to get SVN status" }
                buildString {
                    appendLine("# SVN Status")
                    appendLine()
                    appendLine("**Error**: ${e.message ?: "Unknown error"}")
                    appendLine()
                    appendLine("- **VCS Enabled**: No")
                }
            }
        }
    }
}
