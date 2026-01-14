package com.asakii.plugin.ui

import com.asakii.rpc.api.JetBrainsQuestion
import com.asakii.rpc.api.JetBrainsQuestionOption
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.uiDesigner.core.GridLayoutManager
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * 用户问题确认对话框
 * 支持单选和多选模式
 */
class UserQuestionDialog(
    private val project: Project,
    private val questions: List<JetBrainsQuestion>,
    private val timeoutMs: Long = 300000
) : DialogWrapper(true) {

    private val questionPanels = mutableListOf<QuestionPanel>()
    private var result: Map<String, String>? = null

    init {
        title = "Claude 需要您的回答"
        setOKButtonText("Submit")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
        }

        questions.forEach { question ->
            val questionPanel = QuestionPanel(question).also { questionPanels.add(it) }
            panel.add(questionPanel)
            panel.add(Box.createVerticalStrut(16))
        }

        return JBScrollPane(panel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    override fun getOKAction(): Action {
        return object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val answers = mutableMapOf<String, String>()
                var allAnswered = true

                questionPanels.forEach { panel ->
                    val answer = panel.getAnswer()
                    if (answer == null) {
                        allAnswered = false
                    } else {
                        answers[panel.question.question] = answer
                    }
                }

                if (allAnswered && answers.isNotEmpty()) {
                    result = answers
                    close(OK_EXIT_CODE)
                } else {
                    JOptionPane.showMessageDialog(
                        contentPanel,
                        "Please answer all questions.",
                        "Incomplete",
                        JOptionPane.WARNING_MESSAGE
                    )
                }
            }
        }.apply {
            putValue(Action.NAME, "Submit")
        }
    }

    override fun getCancelAction(): Action {
        return object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                result = null
                close(CANCEL_EXIT_CODE)
            }
        }.apply {
            putValue(Action.NAME, "Cancel")
        }
    }

    fun getResult(): Map<String, String>? = result

    /**
     * 单个问题面板
     */
    private inner class QuestionPanel(val question: JetBrainsQuestion) : JPanel() {
        private var otherInput: JTextField? = null
        private var selectedOption: String? = null
        private val selectedMultiOptions = mutableSetOf<String>()

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 0, 16, 0)
            buildUI()
        }

        private fun buildUI() {
            // Header tag + Question text
            val headerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(8, 0)
            }

            question.header?.let { header ->
                headerPanel.add(JLabel(header).apply {
                    foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                    font = font.deriveFont(java.awt.Font.BOLD, 11f)
                    border = JBUI.Borders.empty(4, 8, 4, 8)
                    isOpaque = true
                    background = JBUI.CurrentTheme.Banner.INFO_BACKGROUND
                })
            }

            headerPanel.add(Box.createHorizontalStrut(8))
            headerPanel.add(JLabel(question.question).apply {
                font = font.deriveFont(java.awt.Font.BOLD, 13f)
            })

            add(headerPanel)
            add(Box.createVerticalStrut(8))

            if (question.multiSelect) {
                buildMultiSelectUI()
            } else {
                buildSingleSelectUI()
            }
        }

        private fun buildSingleSelectUI() {
            val optionsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 16)
            }

            question.options.forEach { option ->
                val optionButton = JRadioButton(createOptionLabel(option)).apply {
                    border = JBUI.Borders.empty(8)
                    isBorderPainted = true
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            selectedOption = option.label
                            // 取消其他选项的选中状态
                            optionsPanel.components.forEach { comp ->
                                if (comp is JRadioButton) {
                                    comp.isSelected = false
                                }
                            }
                            isSelected = true
                            // 如果没有多选问题，自动提交
                            if (!questions.any { it.multiSelect }) {
                                getOKAction().actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "submit"))
                            }
                        }
                    })
                }
                optionsPanel.add(optionButton)
            }

            // Other 输入框
            val otherPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 16)
            }

            val otherRadio = JRadioButton("Other: ").apply {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        selectedOption = null
                        optionsPanel.components.forEach { comp ->
                            if (comp is JRadioButton) {
                                comp.isSelected = false
                            }
                        }
                        isSelected = true
                        otherInput?.requestFocus()
                    }
                })
            }
            otherPanel.add(otherRadio)

            otherInput = JTextField(20).apply {
                maximumSize = preferredSize
                addActionListener {
                    if (otherRadio.isSelected && text.isNotBlank()) {
                        selectedOption = text
                        if (!questions.any { it.multiSelect }) {
                            getOKAction().actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, "submit"))
                        }
                    }
                }
            }
            otherPanel.add(otherInput)

            optionsPanel.add(otherPanel)
            add(optionsPanel)
        }

        private fun buildMultiSelectUI() {
            val optionsPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(0, 16)
            }

            question.options.forEach { option ->
                val optionCheckbox = JCheckBox(createOptionLabel(option)).apply {
                    border = JBUI.Borders.empty(8)
                    isBorderPainted = true
                    addActionListener {
                        if (isSelected) {
                            selectedMultiOptions.add(option.label)
                        } else {
                            selectedMultiOptions.remove(option.label)
                        }
                    }
                }
                optionsPanel.add(optionCheckbox)
            }

            // Other 输入框
            val otherPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(4, 16)
            }

            otherPanel.add(JCheckBox("Other: ").apply {
                border = JBUI.Borders.empty(8)
                isBorderPainted = true
            })

            otherInput = JTextField(20).apply {
                maximumSize = preferredSize
                document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateOtherAnswer()
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateOtherAnswer()
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateOtherAnswer()
                })
            }
            otherPanel.add(otherInput)

            optionsPanel.add(otherPanel)
            add(optionsPanel)
        }

        private fun updateOtherAnswer() {
            // 多选模式下 "Other" 的更新逻辑
        }

        private fun createOptionLabel(option: JetBrainsQuestionOption): String {
            return if (option.description != null) {
                "${option.label} - ${option.description}"
            } else {
                option.label
            }
        }

        fun getAnswer(): String? {
            return if (question.multiSelect) {
                // 多选：返回逗号分隔的选项
                val multiAnswer = selectedMultiOptions.toList()
                val otherText = otherInput?.text?.takeIf { it.isNotBlank() }
                if (multiAnswer.isNotEmpty() || otherText != null) {
                    listOf(multiAnswer, otherText?.let { listOf(it) } ?: emptyList())
                        .flatten()
                        .joinToString(", ")
                } else null
            } else {
                // 单选
                selectedOption ?: otherInput?.text?.takeIf { it.isNotBlank() }
            }
        }
    }
}
