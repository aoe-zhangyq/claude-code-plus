package com.asakii.plugin.ui

import com.asakii.server.HttpServerProjectService
import com.asakii.settings.AgentSettingsService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.JComponent

/**
 * ToolWindow 工厂：支持两种浏览器模式
 * - external（默认）：使用系统浏览器，不占用 IDEA 内存
 * - embedded：使用 IDEA 内置 JBCefBrowser
 */
class NativeToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private val logger = Logger.getInstance(NativeToolWindowFactory::class.java)
        private var browserOpened = false  // 防止重复打开浏览器
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        logger.info("🚀 Creating Claude ToolWindow")
        val toolWindowEx = toolWindow as? ToolWindowEx
        val contentFactory = ContentFactory.getInstance()
        val httpService = HttpServerProjectService.getInstance(project)
        val settings = AgentSettingsService.getInstance()
        val serverUrl = httpService.serverUrl

        // 根据设置选择浏览器模式
        val browserMode = settings.browserMode.takeIf { it.isNotBlank() } ?: "external"
        logger.info("🌐 Browser mode: $browserMode")

        // 服务器指示器（两种模式都需要）
        val serverIndicatorLabel = createServerPortIndicator(project)
        val serverIndicatorAction = ComponentAction(serverIndicatorLabel)

        // 标题栏动作
        val titleActions = mutableListOf<AnAction>()

        if (serverUrl.isNullOrBlank()) {
            logger.warn("⚠️ HTTP Server is not ready, showing placeholder panel")
            val placeholder = createPlaceholderComponent("Claude HTTP 服务启动中，请稍候...")
            val content = contentFactory.createContent(placeholder, "", false)
            toolWindow.contentManager.addContent(content)
            toolWindowEx?.setTabActions(serverIndicatorAction)
            toolWindowEx?.setTitleActions(titleActions)
            return
        }

        // 构建 URL 参数：ide=true + 初始主题
        val targetUrl = buildTargetUrl(serverUrl, httpService, project)
        logger.info("🔗 Target URL: ${targetUrl.take(100)}...")

        when (browserMode) {
            "external" -> {
                // 系统浏览器模式
                setupExternalBrowserMode(project, toolWindow, toolWindowEx, contentFactory, serverUrl, targetUrl, serverIndicatorAction, titleActions)
            }
            "embedded" -> {
                // IDEA 内置浏览器模式
                setupEmbeddedBrowserMode(project, toolWindow, toolWindowEx, contentFactory, serverUrl, targetUrl, serverIndicatorAction, titleActions)
            }
            else -> {
                logger.warn("⚠️ Unknown browser mode: $browserMode, falling back to embedded")
                setupEmbeddedBrowserMode(project, toolWindow, toolWindowEx, contentFactory, serverUrl, targetUrl, serverIndicatorAction, titleActions)
            }
        }
    }

    /**
     * 设置系统浏览器模式
     */
    private fun setupExternalBrowserMode(
        project: Project,
        toolWindow: ToolWindow,
        toolWindowEx: ToolWindowEx?,
        contentFactory: ContentFactory,
        serverUrl: String,
        targetUrl: String,
        serverIndicatorAction: ComponentAction,
        titleActions: MutableList<AnAction>
    ) {
        logger.info("🌐 Using external browser mode")

        // 显示一个简单的面板，说明正在使用系统浏览器
        val infoPanel = createExternalBrowserInfoPanel(project, serverUrl)
        val content = contentFactory.createContent(infoPanel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        // 左侧 Tab Actions：服务器指示器
        toolWindowEx?.setTabActions(serverIndicatorAction)

        // 添加操作按钮
        titleActions.add(object : AnAction(
            "Open in Browser",
            "在系统浏览器中打开 Claude Code Plus",
            AllIcons.Xml.Browsers.Chrome
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                openInBrowser(project, serverUrl)
            }
        })

        // 添加切换到内置浏览器模式按钮
        titleActions.add(object : AnAction(
            "Switch to Embedded Browser",
            "切换到 IDEA 内置浏览器模式",
            AllIcons.General.User
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val settings = AgentSettingsService.getInstance()
                settings.browserMode = "embedded"
                settings.notifyChange()
                // 重启后生效
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "已切换到内置浏览器模式。\n请关闭并重新打开 Claude Code Plus 工具窗口以应用更改。",
                    "切换浏览器模式"
                )
            }
        })

        // 添加设置按钮
        titleActions.add(object : AnAction(
            "Settings",
            "Open Claude Code Settings",
            AllIcons.General.Settings
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "com.asakii.settings.claudecode")
            }
        })

        toolWindowEx?.setTitleActions(titleActions)

        // 自动打开系统浏览器（只打开一次）
        if (!browserOpened) {
            browserOpened = true
            // 延迟一点，确保服务器完全启动
            javax.swing.Timer(500) { _ ->
                openInBrowser(project, targetUrl)
                logger.info("✅ Opened external browser: $targetUrl")
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    /**
     * 设置 IDEA 内置浏览器模式
     */
    private fun setupEmbeddedBrowserMode(
        project: Project,
        toolWindow: ToolWindow,
        toolWindowEx: ToolWindowEx?,
        contentFactory: ContentFactory,
        serverUrl: String,
        targetUrl: String,
        serverIndicatorAction: ComponentAction,
        titleActions: MutableList<AnAction>
    ) {
        logger.info("🔧 Using embedded browser mode")

        // 检查 JCEF 是否可用
        if (!JBCefApp.isSupported()) {
            logger.warn("⚠️ JCEF is not supported, falling back to external browser mode")
            val settings = AgentSettingsService.getInstance()
            settings.browserMode = "external"
            setupExternalBrowserMode(project, toolWindow, toolWindowEx, contentFactory, serverUrl, targetUrl, serverIndicatorAction, titleActions)
            return
        }

        // 使用 Builder 模式显式禁用 OSR，避免 IDEA 2025.x 中上下文菜单和 DevTools 被禁用
        val browser = JBCefBrowser.createBuilder()
            .setOffScreenRendering(false)
            .setEnableOpenDevToolsMenuItem(true)
            .build()

        browser.loadURL(targetUrl)

        // 将浏览器组件包装在 JBPanel 中
        val browserPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(browser.component, BorderLayout.CENTER)
        }

        val content = contentFactory.createContent(browserPanel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)
        Disposer.register(content, browser)

        // 左侧 Tab Actions：服务器指示器
        toolWindowEx?.setTabActions(serverIndicatorAction)

        // 刷新按钮
        val httpService = HttpServerProjectService.getInstance(project)
        val refreshAction = object : AnAction(
            "Refresh",
            "重启后端服务器并重新加载前端",
            AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                logger.info("🔄 Restarting server and refreshing frontend...")
                val newUrl = httpService.restart()
                if (newUrl != null) {
                    val newTargetUrl = buildTargetUrl(newUrl, httpService, project)
                    logger.info("🔗 Loading new URL: ${newTargetUrl.take(100)}...")
                    browser.loadURL(newTargetUrl)
                } else {
                    logger.warn("⚠️ Server restart failed, just reloading page")
                    browser.cefBrowser.reloadIgnoreCache()
                }
            }
        }
        titleActions.add(refreshAction)

        // 添加切换到系统浏览器模式按钮
        titleActions.add(object : AnAction(
            "Switch to External Browser",
            "切换到系统浏览器模式（更省内存）",
            AllIcons.Xml.Browsers.Chrome
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val settings = AgentSettingsService.getInstance()
                settings.browserMode = "external"
                settings.notifyChange()
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "已切换到系统浏览器模式。\n请关闭并重新打开 Claude Code Plus 工具窗口以应用更改。",
                    "切换浏览器模式"
                )
            }
        })

        // 设置按钮
        titleActions.add(object : AnAction(
            "Settings",
            "Open Claude Code Settings",
            AllIcons.General.Settings
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, "com.asakii.settings.claudecode")
            }
        })

        toolWindowEx?.setTitleActions(titleActions)

        // DevTools 选项
        val gearActions = com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
            add(object : AnAction(
                "Open DevTools",
                "打开浏览器开发者工具 (调试 JCEF)",
                com.intellij.icons.AllIcons.Toolwindows.ToolWindowDebugger
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    openDevToolsInDialog(project, browser)
                }
            })
        }
        toolWindowEx?.setAdditionalGearActions(gearActions)
    }

    /**
     * 构建目标 URL（包含 ide 参数、主题参数、项目信息）
     */
    private fun buildTargetUrl(serverUrl: String, httpService: HttpServerProjectService, project: Project): String {
        val jetbrainsApi = httpService.jetbrainsApi
        val themeParam = try {
            val theme = jetbrainsApi?.theme?.get()
            if (theme != null) {
                val themeJson = Json.encodeToString(theme)
                val encoded = URLEncoder.encode(themeJson, StandardCharsets.UTF_8.toString())
                "&initialTheme=$encoded"
            } else ""
        } catch (e: Exception) {
            logger.warn("⚠️ Failed to encode initial theme: ${e.message}")
            ""
        }

        // 添加项目信息用于设置网页标题
        val projectPath = project.basePath ?: ""
        val projectName = project.name
        val projectInfo = "&projectPath=${URLEncoder.encode(projectPath, StandardCharsets.UTF_8.toString())}" +
                          "&projectName=${URLEncoder.encode(projectName, StandardCharsets.UTF_8.toString())}"

        return if (serverUrl.contains("?")) {
            "$serverUrl&ide=true&scrollMultiplier=2.5$themeParam$projectInfo"
        } else {
            "$serverUrl?ide=true&scrollMultiplier=2.5$themeParam$projectInfo"
        }
    }

    /**
     * 创建系统浏览器模式的信息面板（紧凑版）
     */
    private fun createExternalBrowserInfoPanel(project: Project, serverUrl: String): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(16)
        panel.isOpaque = false

        val content = """
            <html>
            <div style='text-align: center; padding: 4px;'>
                <div style='font-size: 16px; color: #FFFFFF; font-weight: 500;'>Claude Code Plus</div>
                <div style='font-size: 11px; color: #B0B0B0; margin-top: 13px;'>系统浏览器模式</div>
                <div style='font-size: 10px; color: #4A90E2; margin-top: 10px;'>$serverUrl</div>
            </div>
            </html>
        """.trimIndent()

        val label = JBLabel(content).apply {
            font = JBUI.Fonts.label(11f)
            horizontalAlignment = javax.swing.SwingConstants.CENTER
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        // 点击打开浏览器
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                openInBrowser(project, serverUrl)
            }
        })

        panel.add(label, BorderLayout.CENTER)

        return panel
    }

    private fun createPlaceholderComponent(message: String): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())
        panel.border = JBUI.Borders.empty(32)
        val label = JBLabel(message).apply {
            foreground = JBColor(0x6B7280, 0x9CA3AF)
        }
        panel.add(label, BorderLayout.CENTER)
        return panel
    }

    /**
     * 将 Swing 组件包装为 ToolWindow 标题栏可用的 Action。
     */
    private class ComponentAction(
        private val component: JComponent
    ) : AnAction(), CustomComponentAction {
        override fun actionPerformed(e: AnActionEvent) = Unit

        override fun createCustomComponent(
            presentation: com.intellij.openapi.actionSystem.Presentation,
            place: String
        ): JComponent = component
    }

    /**
     * 创建服务器端口指示器
     */
    private fun createServerPortIndicator(project: Project): JBLabel {
        val httpService = HttpServerProjectService.getInstance(project)
        val initialUrl = httpService.serverUrl ?: "未启动"

        val linkColor = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val linkHoverColor = JBUI.CurrentTheme.Link.Foreground.HOVERED

        val label = JBLabel("🌐 $initialUrl")
        label.font = JBUI.Fonts.smallFont()
        label.foreground = linkColor
        label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.toolTipText = "<html>HTTP 服务地址<br>单击：复制地址<br>双击：在浏览器中打开</html>"

        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val currentUrl = httpService.serverUrl ?: "未启动"
                if (e.clickCount == 1) {
                    CopyPasteManager.getInstance().setContents(StringSelection(currentUrl))
                    JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder("已复制：$currentUrl", MessageType.INFO, null)
                        .setFadeoutTime(2000)
                        .createBalloon()
                        .show(RelativePoint.getCenterOf(label), Balloon.Position.below)
                } else if (e.clickCount == 2) {
                    openInBrowser(project, currentUrl)
                }
            }

            override fun mouseEntered(e: MouseEvent) {
                label.foreground = linkHoverColor
            }

            override fun mouseExited(e: MouseEvent) {
                label.foreground = linkColor
            }
        })

        return label
    }

    /**
     * 在浏览器中打开 URL
     */
    private fun openInBrowser(project: Project, url: String) {
        try {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                desktop.browse(java.net.URI(url))
            } else {
                logger.warn("Browser not supported to open: $url")
            }
        } catch (e: IOException) {
            logger.warn("Failed to open browser: ${e.message}", e)
        }
    }

    /**
     * 打开 DevTools 窗口
     */
    private fun openDevToolsInDialog(project: Project, browser: JBCefBrowser) {
        try {
            browser.openDevtools()
            logger.info("✅ DevTools window opened via JBCefBrowser.openDevtools()")
        } catch (e: Exception) {
            logger.warn("⚠️ JBCefBrowser.openDevtools() failed: ${e.message}")
            val serverUrl = HttpServerProjectService.getInstance(project).serverUrl
            if (serverUrl != null) {
                com.intellij.openapi.ui.Messages.showInfoMessage(
                    project,
                    "DevTools 无法在 IDE 内打开 (Windows JCEF 兼容性问题)。\n\n" +
                    "请在外部浏览器中打开以下地址，使用浏览器的 DevTools (F12)：\n$serverUrl",
                    "DevTools"
                )
            }
        }
    }

    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "Claude Code Plus"
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
