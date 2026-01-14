package com.asakii.plugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.svn.SvnVcs
import org.jetbrains.idea.svn.api.RepositoryLocation
import org.jetbrains.idea.svn.info.Info
import mu.KotlinLogging
import java.text.SimpleDateFormat
import java.util.*

private val logger = KotlinLogging.logger {}

/**
 * SVN 实现 - 当 SVN 插件安装时使用
 *
 * 直接使用 SVN4Idea 的公开 API，无需反射
 * 此类在 plugin-withSvn.xml 中注册，覆盖默认的 NoopSvnService
 */
@Service(Service.Level.PROJECT)
class SvnServiceImpl(private val project: Project) : SvnService {

    private val svnVcs: SvnVcs?
        get() = SvnVcs.getInstance(project).takeIf { it.isActive }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun getCurrentUrl(): String? {
        return try {
            val vcs = svnVcs ?: return null
            val location = getFirstRepositoryLocation() ?: return null
            location.url
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get current SVN URL" }
            null
        }
    }

    override fun getBranchInfo(): String? {
        return try {
            val url = getCurrentUrl() ?: return null
            // 解析 SVN URL 中的分支信息
            // SVN 中分支和标签是通过目录路径实现的
            // 例如: https://svn.example.com/repo/trunk -> trunk
            //       https://svn.example.com/repo/branches/feature-xxx -> branches/feature-xxx
            //       https://svn.example.com/repo/tags/v1.0 -> tags/v1.0
            val pathParts = url.split("/").filter { it.isNotEmpty() }
            val repoRootIndex = pathParts.indexOfFirst { it == "trunk" || it == "branches" || it == "tags" }

            when {
                repoRootIndex >= 0 && repoRootIndex < pathParts.size - 1 -> {
                    // 有分支/标签信息
                    val type = pathParts[repoRootIndex]
                    val name = pathParts[repoRootIndex + 1]
                    "$type/$name"
                }
                repoRootIndex >= 0 -> {
                    // 只有 trunk
                    "trunk"
                }
                else -> {
                    // 无法判断，返回相对路径
                    pathParts.takeLast(2).joinToString("/")
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get SVN branch info" }
            null
        }
    }

    override fun getCurrentRevision(): String? {
        return try {
            val vcs = svnVcs ?: return null
            val info = getSvnInfo() ?: return null
            info.revision?.toString()
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get current SVN revision" }
            null
        }
    }

    override fun isSvnAvailable(): Boolean {
        return try {
            val vcs = svnVcs ?: return false
            val location = getFirstRepositoryLocation()
            location != null
        } catch (e: Exception) {
            false
        }
    }

    override fun getRecentCommits(maxCount: Int): List<SvnCommitInfo> {
        return try {
            val vcs = svnVcs ?: return emptyList()
            val location = getFirstRepositoryLocation() ?: return emptyList()

            // 使用 VcsHistoryProvider 获取历史记录
            val provider = vcs.vcsHistoryProvider ?: return emptyList()

            // 获取项目根目录
            val baseDir = project.baseDir
            val file = baseDir?.findChild(".svn")?.parent ?: return emptyList()

            // 构建会话
            val session = vcs.createSession(location)
                ?: return emptyList()

            try {
                // 获取历史记录
                val history = provider.createAppropriateSessioningState().
                    let { provider.createCommitWorker(session).createCommits(iterable = emptyList(), "") }

                // 尝试另一种方式获取历史
                getHistoryFromLocation(vcs, location, maxCount)
            } finally {
                session.dispose()
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get SVN commits" }
            emptyList()
        }
    }

    override fun getFileHistory(filePath: String, maxCount: Int): List<SvnCommitInfo> {
        return try {
            val vcs = svnVcs ?: return emptyList()
            val file = project.baseDir?.findFileByRelativePath(filePath)
                ?: return emptyList()

            val location = vcs.getLocation(file)
                ?: return emptyList()

            getHistoryFromLocation(vcs, location, maxCount)
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get SVN file history for $filePath" }
            emptyList()
        }
    }

    override fun getCommitInfo(revision: String): SvnCommitInfo? {
        return try {
            val vcs = svnVcs ?: return null
            val location = getFirstRepositoryLocation() ?: return null

            val revisionNumber = object : VcsRevisionNumber {
                override fun asString(): String = revision
                override fun compareTo(other: VcsRevisionNumber): Int =
                    asString().compareTo(other.asString())
            }

            // 获取特定修订的信息
            val log = vcs.createLog(location, revisionNumber)
                ?: return null

            val entries = log.run() ?: return null
            val entry = entries.firstOrNull()

            entry?.let {
                SvnCommitInfo(
                    revision = it.revision.toString(),
                    author = it.author ?: "Unknown",
                    date = formatDate(it.date),
                    message = it.message ?: "",
                    changedPaths = it.changedPaths?.map { path -> path.path } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get SVN commit info for revision $revision" }
            null
        }
    }

    // ========== 私有辅助方法 ==========

    private fun getFirstRepositoryLocation(): RepositoryLocation? {
        val vcs = svnVcs ?: return null
        val roots = vcs.workingCopiesRoots
        if (roots.isEmpty()) return null

        val firstRoot = roots.firstOrNull() ?: return null
        return vcs.getLocation(firstRoot)
    }

    private fun getSvnInfo(): Info? {
        val vcs = svnVcs ?: return null
        val location = getFirstRepositoryLocation() ?: return null
        return try {
            vcs.getInfo(location)
        } catch (e: VcsException) {
            null
        }
    }

    private fun getHistoryFromLocation(
        vcs: SvnVcs,
        location: RepositoryLocation,
        maxCount: Int
    ): List<SvnCommitInfo> {
        return try {
            val log = vcs.createLog(location) ?: return emptyList()

            // 设置限制
            // 注意: SVN API 的具体实现可能因版本而异
            // 这里使用通用的方式获取日志

            val entries = log.run()?.take(maxCount) ?: emptyList()

            entries.map { entry ->
                SvnCommitInfo(
                    revision = entry.revision.toString(),
                    author = entry.author ?: "Unknown",
                    date = formatDate(entry.date),
                    message = entry.message ?: "",
                    changedPaths = entry.changedPaths?.map { path -> path.path } ?: emptyList()
                )
            }
        } catch (e: Exception) {
            logger.debug(e) { "Failed to get SVN history from location" }
            emptyList()
        }
    }

    private fun formatDate(date: Date?): String {
        if (date == null) return "Unknown"
        return try {
            dateFormat.format(date)
        } catch (e: Exception) {
            date.toString()
        }
    }
}
