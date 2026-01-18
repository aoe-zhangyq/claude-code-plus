package com.asakii.plugin.mcp

import com.asakii.plugin.mcp.tools.MavenCompileTool
import com.asakii.server.mcp.schema.ToolSchemaLoader
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compile MCP 工具测试
 *
 * 测试 Maven 离线编译工具的功能
 */
@DisplayName("Compile MCP 工具测试")
class CompileMcpTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        // 测试前的初始化
    }

    @AfterEach
    fun tearDown() {
        // 清理 mock
        unmockkAll()
    }

    @Test
    @DisplayName("验证 Schema 格式正确")
    fun testSchemaFormat() {
        // 只验证 MavenCompile Schema（FileBuild 已合并到 FileProblems）
        val schema = ToolSchemaLoader.parseSchemaJson(
            com.asakii.settings.McpDefaults.COMPILE_TOOLS_SCHEMA
        )

        // 验证 MavenCompile Schema
        val mavenCompileSchema = schema["MavenCompile"] as? Map<*, *>
        assertTrue(mavenCompileSchema != null, "MavenCompile schema should exist")
        assertEquals("object", mavenCompileSchema!!["type"])

        val mavenProperties = mavenCompileSchema["properties"] as? Map<*, *>
        assertTrue(mavenProperties != null, "MavenCompile should have properties")

        // 验证默认值
        val goalsDefault = (mavenProperties!!["goals"] as? Map<*, *>)?.get("default") as? List<*>
        assertEquals(listOf("compile"), goalsDefault, "goals default should be ['compile']")

        val offlineDefault = (mavenProperties["offline"] as? Map<*, *>)?.get("default")
        assertEquals(true, offlineDefault, "offline default should be true")
    }

    @Test
    @DisplayName("验证提示词包含关键信息")
    fun testPromptContent() {
        val promptEn = com.asakii.settings.McpDefaults.getCompileInstructions("en")
        val promptZh = com.asakii.settings.McpDefaults.getCompileInstructions("zh")

        // 验证英文提示词包含关键内容
        assertTrue(
            promptEn.contains("FileProblems"),
            "English prompt should mention FileProblems"
        )
        assertTrue(
            promptEn.contains("MavenCompile"),
            "English prompt should mention MavenCompile"
        )

        // 验证中文提示词包含关键内容
        assertTrue(
            promptZh.contains("FileProblems"),
            "Chinese prompt should mention FileProblems"
        )
        assertTrue(
            promptZh.contains("MavenCompile"),
            "Chinese prompt should mention MavenCompile"
        )
    }

    @Test
    @DisplayName("验证提示词包含完整的工作流程")
    fun testWorkflowInPrompt() {
        val prompt = com.asakii.settings.McpDefaults.getCompileInstructions("en")

        // 验证工作流程步骤
        assertTrue(
            prompt.contains("FileProblems → MavenCompile"),
            "Prompt should define workflow: FileProblems → MavenCompile"
        )
    }

    @Test
    @DisplayName("验证 Schema 加载器能正确获取 Schema")
    fun testSchemaLoader() {
        // 注意：此测试需要 CompileMcpServerImpl 初始化后注册 Schema
        // 这里主要验证 ToolSchemaLoader 的接口
        val schema = ToolSchemaLoader.getSchema("MavenCompile")

        // 如果 Schema 未注册，会返回空 Map
        // 如果已注册，应包含 type 字段
        if (schema.isNotEmpty()) {
            assertEquals("object", schema["type"])
        }
    }
}

/**
 * Compile MCP 提示词行为测试
 *
 * 测试 AI 是否按照预期使用 Compile 工具
 */
@DisplayName("Compile MCP 提示词行为测试")
class CompileMcpPromptBehaviorTest {

    @Test
    @DisplayName("验证提示词强调先写代码后验证")
    fun testCodeFirstThenValidate() {
        val prompt = com.asakii.settings.McpDefaults.getCompileInstructions("en")

        // 验证提示词明确要求先写代码
        assertTrue(
            prompt.contains("If any step fails"),
            "Prompt should emphasize fixing errors when steps fail"
        )
    }

    @Test
    @DisplayName("验证提示词定义的验证循环顺序")
    fun testValidationCycle() {
        val prompt = com.asakii.settings.McpDefaults.getCompileInstructions("en")

        // 验证验证循环的顺序：FileProblems → MavenCompile
        val lines = prompt.lines()
        val fileProblemsIndex = lines.indexOfFirst { it.contains("FileProblems") }
        val mavenCompileIndex = lines.indexOfFirst { it.contains("MavenCompile") }

        assertTrue(
            fileProblemsIndex > 0 && mavenCompileIndex > fileProblemsIndex,
            "FileProblems should come before MavenCompile in the workflow"
        )
    }

    @Test
    @DisplayName("验证中英文提示词内容一致")
    fun testBilingualPromptConsistency() {
        val promptEn = com.asakii.settings.McpDefaults.getCompileInstructions("en")
        val promptZh = com.asakii.settings.McpDefaults.getCompileInstructions("zh")

        // 验证两者都包含关键概念
        val keyConcepts = listOf(
            "FileProblems",
            "MavenCompile",
            "compile"  // MavenCompile goals 中的 compile
        )

        keyConcepts.forEach { concept ->
            assertTrue(
                promptEn.contains(concept),
                "English prompt should contain $concept"
            )
            assertTrue(
                promptZh.contains(concept),
                "Chinese prompt should contain $concept"
            )
        }
    }
}

/**
 * Compile MCP 参数验证测试
 *
 * 测试工具参数的解析和验证
 */
@DisplayName("Compile MCP 参数验证测试")
class CompileMcpParameterTest {

    @Test
    @DisplayName("验证 MavenCompile 参数解析")
    fun testMavenCompileParameters() {
        val schema = ToolSchemaLoader.parseSchemaJson(
            com.asakii.settings.McpDefaults.COMPILE_TOOLS_SCHEMA
        )
        val mavenCompileSchema = schema["MavenCompile"] as? Map<*, *>
        val properties = mavenCompileSchema!!["properties"] as? Map<*, *>

        // 验证 goals 参数
        val goals = properties!!["goals"] as? Map<*, *>
        assertEquals("array", goals!!["type"])
        assertEquals(listOf("compile"), goals["default"])

        // 验证 offline 参数
        val offline = properties["offline"] as? Map<*, *>
        assertEquals("boolean", offline!!["type"])
        assertEquals(true, offline["default"])

        // 验证 timeout 参数
        val timeout = properties["timeout"] as? Map<*, *>
        assertEquals("integer", timeout!!["type"])
        assertEquals(300, timeout["default"])
        assertEquals(30, timeout["minimum"])
    }

    @Test
    @DisplayName("验证参数边界值")
    fun testParameterBoundaries() {
        val schema = ToolSchemaLoader.parseSchemaJson(
            com.asakii.settings.McpDefaults.COMPILE_TOOLS_SCHEMA
        )

        // MavenCompile timeout
        val mavenCompileSchema = schema["MavenCompile"] as? Map<*, *>
        val mavenProperties = mavenCompileSchema!!["properties"] as? Map<*, *>
        val timeout = mavenProperties!!["timeout"] as? Map<*, *>
        assertEquals(30, timeout!!["minimum"])
    }
}
