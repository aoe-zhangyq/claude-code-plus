package com.asakii.plugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.ApiStatus

/**
 * SVN 服务接口
 *
 * 使用 JetBrains 官方推荐的可选依赖模式：
 * - 默认实现 (NoopSvnService): 当 SVN 插件未安装时使用
 * - SVN 实现 (SvnServiceImpl): 当 SVN 插件已安装时使用，在 plugin-withSvn.xml 中注册
 *
 * 这样可以避免使用反射，使用编译时类型安全的 API
 */
interface SvnService {

    /**
     * 获取当前工作副本的 URL
     * @return SVN URL，如果不可用则返回 null
     */
    fun getCurrentUrl(): String?

    /**
     * 获取当前分支/标签信息
     * SVN 中分支和标签是通过目录路径实现的，例如 /branches/feature-xxx 或 /tags/v1.0
     * @return 分支/标签信息，如果不可用则返回 null
     */
    fun getBranchInfo(): String?

    /**
     * 获取当前修订版本号
     * @return 修订版本号，如果不可用则返回 null
     */
    fun getCurrentRevision(): String?

    /**
     * 检查 SVN 是否可用
     * @return true 如果项目有 SVN 工作副本
     */
    fun isSvnAvailable(): Boolean

    /**
     * 获取最近的提交历史
     * @param maxCount 最大返回数量
     * @return 提交历史列表
     */
    fun getRecentCommits(maxCount: Int): List<SvnCommitInfo>

    /**
     * 获取指定文件的提交历史
     * @param filePath 文件路径
     * @param maxCount 最大返回数量
     * @return 提交历史列表
     */
    fun getFileHistory(filePath: String, maxCount: Int): List<SvnCommitInfo>

    /**
     * 获取指定修订版的详细信息
     * @param revision 修订版本号
     * @return 提交信息，如果不可用则返回 null
     */
    fun getCommitInfo(revision: String): SvnCommitInfo?

    companion object {
        @JvmStatic
        fun getInstance(project: Project): SvnService {
            return project.getService(SvnService::class.java)
        }
    }
}

/**
 * SVN 提交信息
 */
data class SvnCommitInfo(
    val revision: String,
    val author: String,
    val date: String,
    val message: String,
    val changedPaths: List<String> = emptyList()
)

/**
 * 默认实现 - 当 SVN 插件未安装时使用
 * 所有方法返回空/null 值
 */
@Service(Service.Level.PROJECT)
class NoopSvnService : SvnService {

    override fun getCurrentUrl(): String? = null

    override fun getBranchInfo(): String? = null

    override fun getCurrentRevision(): String? = null

    override fun isSvnAvailable(): Boolean = false

    override fun getRecentCommits(maxCount: Int): List<SvnCommitInfo> = emptyList()

    override fun getFileHistory(filePath: String, maxCount: Int): List<SvnCommitInfo> = emptyList()

    override fun getCommitInfo(revision: String): SvnCommitInfo? = null
}
