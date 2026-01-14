package com.asakii.plugin.ui

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import java.awt.Component
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * 工具权限确认对话框（JetBrains 原生对话框）
 * 脱离插件窗口，独立显示
 */
class PermissionRequestDialog(
    private val project: Project,
    private val toolName: String,
    private val inputJson: String,
    private val suggestions: List<PermissionSuggestion>
    // 不设置超时，对话框会一直等待用户操作
) : DialogWrapper(true) {

    private var result: PermissionResult? = null
    private var denyReason = ""
    private var denyInput: JTextField? = null

    init {
        title = "Claude Permission Request"
        isModal = true
        init()
    }

    override fun createActions(): Array<Action> {
        // 不显示默认的 OK/Cancel 按钮
        return emptyArray()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }

        // 工具信息头部
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(8, 0)
        }

        headerPanel.add(JLabel(getToolIcon(toolName)).apply {
            font = font.deriveFont(18f)
        })
        headerPanel.add(Box.createHorizontalStrut(8))
        headerPanel.add(JLabel(toolName).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
        })
        headerPanel.add(Box.createHorizontalStrut(8))
        headerPanel.add(JLabel("需要授权").apply {
            foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
            font = font.deriveFont(java.awt.Font.BOLD, 12f)
            border = JBUI.Borders.empty(4, 8, 4, 8)
            isOpaque = true
            background = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
        })

        panel.add(headerPanel)
        panel.add(Box.createVerticalStrut(12))

        // 工具参数预览
        val input = parseInputJson()
        val previewPanel = createPreviewPanel(input)
        panel.add(previewPanel)
        panel.add(Box.createVerticalStrut(16))

        // 操作选项
        val actionsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(0, 16)
        }

        // Allow 按钮
        actionsPanel.add(createActionButton("Allow", true) {
            result = PermissionResult(approved = true, permissionUpdates = emptyList(), denyReason = null)
            close(OK_EXIT_CODE)
        })

        // 建议选项
        suggestions.forEach { suggestion ->
            actionsPanel.add(createActionButton(suggestion.label, true) {
                result = PermissionResult(
                    approved = true,
                    permissionUpdates = listOf(suggestion.toJson()),
                    denyReason = null
                )
                close(OK_EXIT_CODE)
            })
        }

        // Deny 输入和按钮
        val denyPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 16)
        }

        denyInput = JTextField(30).apply {
            maximumSize = preferredSize
            document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateDenyReason()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateDenyReason()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateDenyReason()
            })
        }

        val denyButton = JButton("Deny").apply {
            addActionListener {
                result = PermissionResult(
                    approved = false,
                    permissionUpdates = emptyList(),
                    denyReason = denyReason.ifBlank { "User denied" }
                )
                close(OK_EXIT_CODE)
            }
        }

        denyPanel.add(JLabel("Reason:"))
        denyPanel.add(Box.createHorizontalStrut(8))
        denyPanel.add(denyInput)
        denyPanel.add(Box.createHorizontalStrut(8))
        denyPanel.add(denyButton)

        actionsPanel.add(denyPanel)

        panel.add(actionsPanel)

        // 快捷键提示
        panel.add(Box.createVerticalStrut(8))
        panel.add(JLabel("按 Esc 键拒绝").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
        })

        return JBScrollPane(panel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    override fun getPreferredFocusedComponent(): JComponent? {
        return null
    }

    private fun updateDenyReason() {
        denyReason = denyInput?.text ?: ""
    }

    private fun parseInputJson(): JsonObject {
        return try {
            Gson().fromJson(inputJson, JsonObject::class.java)
        } catch (e: Exception) {
            JsonObject()
        }
    }

    private fun createPreviewPanel(input: JsonObject): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(12)
            background = JBUI.CurrentTheme.Popup.BACKGROUND
        }

        when (toolName) {
            "Bash" -> {
                val command = input.get("command")?.asString ?: ""
                panel.add(JLabel("Command:").apply {
                    font = font.deriveFont(java.awt.Font.BOLD, 12f)
                })
                panel.add(Box.createVerticalStrut(4))
                panel.add(createFixedWidthTextArea(command, 6, 10))
            }
            "Write" -> {
                val filePath = input.get("file_path")?.asString ?: ""
                val content = input.get("content")?.asString ?: ""
                panel.add(JLabel("文件: $filePath").apply {
                    font = font.deriveFont(java.awt.Font.BOLD, 12f)
                })
                if (content.length <= 500) {
                    panel.add(Box.createVerticalStrut(8))
                    panel.add(JLabel("内容预览:"))
                    panel.add(createFixedWidthTextArea(content, 8, 12))
                } else {
                    panel.add(JLabel("内容大小: ${content.length} 字符"))
                }
            }
            "Edit", "MultiEdit" -> {
                val filePath = input.get("file_path")?.asString ?: ""
                panel.add(JLabel("文件: $filePath").apply {
                    font = font.deriveFont(java.awt.Font.BOLD, 12f)
                })
                val oldString = input.get("old_string")?.asString
                val newString = input.get("new_string")?.asString
                if (oldString != null && newString != null) {
                    panel.add(Box.createVerticalStrut(8))
                    panel.add(JLabel("修改预览:"))
                    val previewText = "替换: ${oldString.take(100)}...\n为: ${newString.take(100)}..."
                    panel.add(createFixedWidthTextArea(previewText, 4, 6))
                }
            }
            else -> {
                panel.add(JLabel("工具: $toolName"))
                panel.add(createFixedWidthTextArea(inputJson, 5, 10))
            }
        }

        return panel
    }

    private fun createActionButton(text: String, default: Boolean, action: () -> Unit): JButton {
        return JButton(text).apply {
            putClientProperty("JButton.defaultButton", default)
            addActionListener { action() }
        }
    }

    /**
     * 创建固定宽度的文本区域，自动换行
     */
    private fun createFixedWidthTextArea(text: String, minRows: Int, maxRows: Int): JTextArea {
        return JTextArea(text).apply {
            isEditable = false
            background = JBColor.WHITE
            foreground = JBColor.BLACK
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true
            rows = kotlin.math.min(minRows, text.lines().size.coerceAtMost(maxRows))
            // 限制最大宽度为 600 像素
            setMaximumSize(java.awt.Dimension(600, Int.MAX_VALUE))
            preferredSize = java.awt.Dimension(550, preferredSize.height)
        }
    }

    fun getResult(): PermissionResult? = result

    companion object {
        fun getToolIcon(toolName: String): String {
            return when (toolName) {
                "Bash" -> "🖥"
                "Write" -> "📝"
                "Edit" -> "✏️"
                "Read" -> "📖"
                "MultiEdit" -> "📋"
                "Glob" -> "🔍"
                "Grep" -> "🔎"
                else -> "🔧"
            }
        }
    }
}

/**
 * 权限建议
 */
data class PermissionSuggestion(
    val label: String,
    val mode: String,
    val updateType: String,
    val rules: List<String>
) {
    fun toJson(): String {
        return """{"type":"$updateType","mode":"$mode","rules":${Gson().toJson(rules)}}"""
    }
}

/**
 * 权限结果
 */
data class PermissionResult(
    val approved: Boolean,
    val permissionUpdates: List<String>,
    val denyReason: String?
)
