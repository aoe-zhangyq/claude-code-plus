package standalone

import com.asakii.ai.agent.sdk.AiAgentProvider
import com.asakii.ai.agent.sdk.client.AgentMessageInput
import com.asakii.ai.agent.sdk.client.ClaudeAgentClientImpl
import com.asakii.ai.agent.sdk.connect.AiAgentConnectOptions
import com.asakii.ai.agent.sdk.connect.ClaudeOverrides
import com.asakii.ai.agent.sdk.model.*
import com.asakii.claude.agent.sdk.types.ClaudeAgentOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Compile MCP 工作流测试
 *
 * 测试 AI 是否按照预期使用 Compile 工具：
 * 1. 代码输出阶段：不调用编译工具
 * 2. 验证阶段：按照 FileProblems → FileBuild → MavenCompile 的顺序
 *
 * 使用方法：
 * 1. 确保 CLAUDE_API_KEY 环境变量已设置
 * 2. 运行 main 函数
 * 3. 观察工具调用顺序是否符合预期
 */
fun main() = runBlocking {
    println("=".repeat(60))
    println("🧪 Compile MCP 工作流测试")
    println("=".repeat(60))

    // 检查环境变量
    val apiKey = System.getenv("CLAUDE_API_KEY")
    println("📋 环境变量检查:")
    println("   CLAUDE_API_KEY = ${if (apiKey.isNullOrEmpty()) "❌ 未设置" else "✅ 已设置(${apiKey.take(8)}...)"}")
    println()

    if (apiKey.isNullOrEmpty()) {
        println("❌ 请设置 CLAUDE_API_KEY 环境变量")
        return@runBlocking
    }

    // 创建 Claude Agent 客户端
    val client = ClaudeAgentClientImpl()

    // 配置 Claude 选项
    val claudeOptions = ClaudeAgentOptions(
        model = "claude-sonnet-4-20250514",
        maxTurns = 10,
        print = true,
        verbose = true,
        includePartialMessages = true,
        dangerouslySkipPermissions = true,
        allowDangerouslySkipPermissions = true
    )

    // 连接选项
    val connectOptions = AiAgentConnectOptions(
        provider = AiAgentProvider.CLAUDE,
        sessionId = "test-compile-mcp-${System.currentTimeMillis()}",
        claude = ClaudeOverrides(options = claudeOptions)
    )

    try {
        println("[步骤 1] 连接到 Claude...")
        client.connect(connectOptions)
        println("✅ 连接成功\n")

        // 启动事件收集协程
        println("[步骤 2] 启动流式事件监听...")

        // 工具调用记录
        val toolCallSequence = mutableListOf<String>()
        val codeEditBlocks = mutableListOf<String>()
        var receivedComplete = false

        val collectJob = launch {
            client.streamEvents()
                .onEach { event ->
                    handleStreamEvent(event, toolCallSequence, codeEditBlocks)
                    if (event is UiMessageComplete || event is UiError) {
                        receivedComplete = true
                    }
                }
                .catch { e ->
                    println("❌ 流式事件错误: ${e.message}")
                }
                .collect()
        }

        // 发送测试消息 - 添加一个简单的编译错误场景
        println("[步骤 3] 发送测试消息...")
        println("   任务: 创建一个包含编译错误的类，然后修复它\n")

        client.sendMessage(AgentMessageInput(
            text = """
                请完成以下任务：

                1. 在 src/main/kotlin/test 目录创建一个名为 BadMath.kt 的文件
                2. 文件内容故意包含一个编译错误（例如：类型不匹配）
                3. 然后修复这个错误

                注意：请先完成所有代码修改，再进行验证。
            """.trimIndent()
        ))

        // 等待响应完成
        println("\n[步骤 4] 等待响应完成...")
        withTimeout(120000) {  // 2分钟超时
            while (!receivedComplete) {
                delay(100)
            }
        }

        // 取消收集任务
        collectJob.cancelAndJoin()

        // 分析结果
        println("\n" + "=".repeat(60))
        println("📊 测试结果分析")
        println("=".repeat(60))

        analyzeResults(toolCallSequence, codeEditBlocks)

    } catch (e: Exception) {
        println("\n❌ 测试失败: ${e.message}")
        e.printStackTrace()
    } finally {
        println("\n[清理] 断开连接...")
        client.disconnect()
        println("🔌 已断开连接")
    }
}

/**
 * 处理流式事件
 */
private fun handleStreamEvent(
    event: UiStreamEvent,
    toolCallSequence: MutableList<String>,
    codeEditBlocks: MutableList<String>
) {
    when (event) {
        is UiMessageStart -> {
            println("   📨 MessageStart")
        }
        is UiTextDelta -> {
            // 检测代码编辑块
            val text = event.text
            if (text.contains("```") && (text.contains("Write(") || text.contains("Edit("))) {
                // 可能是代码编辑
            }
        }
        is UiToolStart -> {
            val toolName = event.toolName
            toolCallSequence.add(toolName)
            println("   🔧 ToolStart: $toolName")
        }
        is UiToolComplete -> {
            println("   ✅ ToolComplete: ${event.toolId}")
        }
        is UiMessageComplete -> {
            println("\n   🎉 MessageComplete")
        }
        is UiError -> {
            println("   ❌ Error: ${event.message}")
        }
        else -> {
            // 忽略其他事件
        }
    }
}

/**
 * 分析测试结果
 */
private fun analyzeResults(toolCallSequence: List<String>, codeEditBlocks: List<String>) {
    println()
    println("🔍 工具调用顺序:")
    toolCallSequence.forEachIndexed { index, tool ->
        println("   $index. $tool")
    }
    println()

    // 验证规则
    var passed = 0
    var failed = 0

    val checks = mutableListOf<Pair<String, Boolean>>()

    // 检查 1: 编译工具是否在代码编辑之后调用
    val firstCodeEditIndex = toolCallSequence.indexOfFirst {
        it == "Write" || it == "Edit"
    }
    val firstCompileIndex = toolCallSequence.indexOfFirst {
        it.contains("Compile") || it.contains("FileProblems")
    }

    checks.add("编译工具在代码编辑后调用" to (firstCompileIndex > firstCodeEditIndex || firstCompileIndex == -1))

    // 检查 2: FileProblems 是否在 FileBuild 之前
    val fileProblemsIndex = toolCallSequence.indexOf("FileProblems")
    val fileBuildIndex = toolCallSequence.indexOfFirst { it.contains("FileBuild") }

    if (fileProblemsIndex >= 0 && fileBuildIndex >= 0) {
        checks.add("FileProblems 在 FileBuild 之前" to (fileProblemsIndex < fileBuildIndex))
    }

    // 检查 3: FileBuild 是否在 MavenCompile 之前
    val mavenCompileIndex = toolCallSequence.indexOfFirst { it.contains("MavenCompile") }

    if (fileBuildIndex >= 0 && mavenCompileIndex >= 0) {
        checks.add("FileBuild 在 MavenCompile 之前" to (fileBuildIndex < mavenCompileIndex))
    }

    // 检查 4: 是否使用了 Compile 工具
    val usedCompileTools = toolCallSequence.any { it.contains("Compile") }
    checks.add("使用了至少一个编译工具" to usedCompileTools)

    // 打印结果
    println("✅ 验证结果:")
    checks.forEach { (description, result) ->
        val status = if (result) "✅" else "❌"
        println("   $status $description")
        if (result) passed++ else failed++
    }
    println()

    println("📈 统计: $passed 通过, $failed 失败")

    if (failed == 0) {
        println("\n🎉 所有检查通过！AI 按照预期使用了 Compile 工具。")
    } else {
        println("\n⚠️ 部分检查失败，可能需要调整提示词。")
    }

    println("=".repeat(60))
}

/**
 * 测试用例 2: 验证 AI 不会在代码编辑中途调用编译工具
 */
fun testNoMidStreamCompile() = runBlocking {
    println("=".repeat(60))
    println("🧪 测试: 验证 AI 不会在代码编辑中途调用编译工具")
    println("=".repeat(60))

    // TODO: 实现具体的测试逻辑
    println("   此测试用例需要在实际 IDEA 环境中运行")
}

/**
 * 测试用例 3: 验证验证循环的正确顺序
 */
fun testValidationCycle() = runBlocking {
    println("=".repeat(60))
    println("🧪 测试: 验证验证循环的正确顺序")
    println("=".repeat(60))

    // TODO: 实现具体的测试逻辑
    println("   此测试用例需要在实际 IDEA 环境中运行")
}
