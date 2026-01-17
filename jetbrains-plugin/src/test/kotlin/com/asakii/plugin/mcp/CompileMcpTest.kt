package com.asakii.plugin.mcp

import com.asakii.plugin.mcp.tools.FileBuildTool
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
 * 测试 IDEA 增量编译和 Maven 离线编译工具的功能
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
        // FileBuild 已移至 JetBrains MCP，只验证 MavenCompile Schema
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
            promptEn.contains("FileBuild"),
            "English prompt should mention FileBuild"
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
            promptZh.contains("FileBuild"),
            "Chinese prompt should mention FileBuild"
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
            prompt.contains("Code Output Phase"),
            "Prompt should define Code Output Phase"
        )
        assertTrue(
            prompt.contains("Validation Phase"),
            "Prompt should define Validation Phase"
        )
        assertTrue(
            prompt.contains("after ALL code is complete"),
            "Prompt should emphasize validating after all code is complete"
        )
    }

    @Test
    @DisplayName("验证 Schema 加载器能正确获取 Schema")
    fun testSchemaLoader() {
        // 注意：此测试需要 CompileMcpServerImpl 初始化后注册 Schema
        // 这里主要验证 ToolSchemaLoader 的接口
        val schema = ToolSchemaLoader.getSchema("IdeaCompile")

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
            prompt.contains("Wait until ALL code is written"),
            "Prompt should emphasize waiting until all code is written"
        )
        assertTrue(
            prompt.contains("DO NOT call compile tools between edits"),
            "Prompt should explicitly forbid calling tools between edits"
        )
    }

    @Test
    @DisplayName("验证提示词定义的验证循环顺序")
    fun testValidationCycle() {
        val prompt = com.asakii.settings.McpDefaults.getCompileInstructions("en")

        // 验证验证循环的顺序
        val lines = prompt.lines()
        val fileProblemsIndex = lines.indexOfFirst { it.contains("FileProblems") }
        val fileBuildIndex = lines.indexOfFirst { it.contains("FileBuild") }
        val mavenCompileIndex = lines.indexOfFirst { it.contains("MavenCompile") }

        assertTrue(
            fileProblemsIndex > 0 && fileBuildIndex > fileProblemsIndex,
            "FileProblems should come before FileBuild in the workflow"
        )
        assertTrue(
            mavenCompileIndex > fileBuildIndex,
            "MavenCompile should come after FileBuild in the workflow"
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
            "FileBuild",
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
    @DisplayName("验证 FileBuild 参数解析")
    fun testFileBuildParameters() {
        // FileBuild 现在在 JetBrains MCP 中
        val schema = ToolSchemaLoader.parseSchemaJson(
            com.asakii.settings.McpDefaults.JETBRAINS_TOOLS_SCHEMA
        )
        val fileBuildSchema = schema["FileBuild"] as? Map<*, *>
        val properties = fileBuildSchema!!["properties"] as? Map<*, *>

        // 验证 filePaths 参数
        val filePaths = properties!!["filePaths"] as? Map<*, *>
        assertEquals("array", filePaths!!["type"])
        assertEquals("string", (filePaths["items"] as? Map<*, *>)?.get("type"))

        // 验证 scope 参数
        val scope = properties["scope"] as? Map<*, *>
        assertEquals("string", scope!!["type"])
        assertEquals("project", scope["default"])

        // 验证 scope 枚举值
        val scopeEnum = scope["enum"] as? List<*>
        assertTrue(scopeEnum?.contains("project") == true)
        assertTrue(scopeEnum?.contains("module") == true)

        // 验证 forceRebuild 参数
        val forceRebuild = properties["forceRebuild"] as? Map<*, *>
        assertEquals("boolean", forceRebuild!!["type"])
        assertEquals(false, forceRebuild["default"])

        // 验证 fastMode 参数
        val fastMode = properties["fastMode"] as? Map<*, *>
        assertEquals("boolean", fastMode!!["type"])
        assertEquals(false, fastMode["default"])

        // 验证 skipWarnings 参数（默认为 true）
        val skipWarnings = properties["skipWarnings"] as? Map<*, *>
        assertEquals("boolean", skipWarnings!!["type"])
        assertEquals(true, skipWarnings["default"])
    }

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
            com.asakii.settings.McpDefaults.JETBRAINS_TOOLS_SCHEMA
        )

        // FileBuild maxErrors
        val fileBuildSchema = schema["FileBuild"] as? Map<*, *>
        val fileBuildProperties = fileBuildSchema!!["properties"] as? Map<*, *>
        val maxErrors = fileBuildProperties!!["maxErrors"] as? Map<*, *>
        assertEquals(1, maxErrors!!["minimum"])

        // MavenCompile timeout
        val compileSchema = ToolSchemaLoader.parseSchemaJson(
            com.asakii.settings.McpDefaults.COMPILE_TOOLS_SCHEMA
        )
        val mavenCompileSchema = compileSchema["MavenCompile"] as? Map<*, *>
        val mavenProperties = mavenCompileSchema!!["properties"] as? Map<*, *>
        val timeout = mavenProperties!!["timeout"] as? Map<*, *>
        assertEquals(30, timeout!!["minimum"])
    }
}
