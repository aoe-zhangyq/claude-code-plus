package com.asakii.settings

/**
 * MCP 配置默认值
 *
 * 存储内置 MCP 服务器的默认系统提示词、工具 schema 和配置
 */
object McpDefaults {

    /**
     * Context7 MCP 服务器配置
     */
    object Context7Server {
        const val URL = "https://mcp.context7.com/mcp"
        const val API_KEY_HEADER = "CONTEXT7_API_KEY"
        const val DESCRIPTION = "Context7 MCP - Fetch up-to-date documentation for libraries"
    }

    /**
     * JetBrains MCP 工具 Schema（JSON 格式）
     */
    val JETBRAINS_TOOLS_SCHEMA = """
{
  "FileIndex": {
    "type": "object",
    "description": "Search files, classes, and symbols in the IDE index by keywords. Faster than file system search, supports fuzzy matching.",
    "properties": {
      "query": {
        "type": "string",
        "description": "Search keywords"
      },
      "searchType": {
        "type": "string",
        "enum": ["All", "Classes", "Files", "Symbols", "Actions", "Text"],
        "description": "Search type",
        "default": "All"
      },
      "scope": {
        "type": "string",
        "enum": ["Project", "All", "ProductionFiles", "TestFiles", "Scratches"],
        "description": "Search scope",
        "default": "Project"
      },
      "maxResults": {
        "type": "integer",
        "description": "Max results",
        "default": 20,
        "minimum": 1
      },
      "offset": {
        "type": "integer",
        "description": "Offset",
        "default": 0,
        "minimum": 0
      }
    },
    "required": ["query"]
  },

  "DirectoryTree": {
    "type": "object",
    "description": "Get the tree structure of the project directory. Supports depth limit, file filtering, and hidden files options.",
    "properties": {
      "path": {
        "type": "string",
        "description": "Path relative to project root (e.g. \"src/main\", \"frontend/src\")",
        "default": "."
      },
      "maxDepth": {
        "type": "integer",
        "description": "Maximum recursion depth. Use -1 or 0 for unlimited depth.",
        "default": 3
      },
      "filesOnly": {
        "type": "boolean",
        "description": "Show only files, hide directory entries",
        "default": false
      },
      "includeHidden": {
        "type": "boolean",
        "description": "Include hidden files/directories (names starting with .)",
        "default": false
      },
      "pattern": {
        "type": "string",
        "description": "File name filter using glob patterns. Examples: \"*.kt\" (Kotlin files), \"*.{ts,vue}\" (TypeScript and Vue), \"Test*\" (files starting with Test)"
      },
      "maxEntries": {
        "type": "integer",
        "description": "Maximum number of entries to return (prevents overwhelming output for large directories)",
        "default": 100,
        "minimum": 1
      }
    },
    "required": []
  },

  "CodeSearch": {
    "type": "object",
    "description": "Search code content across project files (like IDE's Find in Files). Uses IDEA's indexing for fast searches.",
    "properties": {
      "query": {
        "type": "string",
        "description": "Search text or regular expression pattern to find in file contents"
      },
      "isRegex": {
        "type": "boolean",
        "description": "Treat query as regular expression (e.g. \"log.*Error\", \"function\\s+\\w+\")",
        "default": false
      },
      "caseSensitive": {
        "type": "boolean",
        "description": "Case sensitive search",
        "default": false
      },
      "wholeWords": {
        "type": "boolean",
        "description": "Match whole words only (not substrings)",
        "default": false
      },
      "fileMask": {
        "type": "string",
        "description": "File name filter using glob patterns. Examples: \"*.vue\" (Vue files), \"*.kt,*.java\" (multiple types)"
      },
      "scope": {
        "type": "string",
        "enum": ["Project", "All", "Module", "Directory", "Scope"],
        "description": "Search scope",
        "default": "Project"
      },
      "scopeArg": {
        "type": "string",
        "description": "Required when scope is not Project. For Module: module name. For Directory: relative path."
      },
      "maxResults": {
        "type": "integer",
        "description": "Maximum number of matches to return",
        "default": 10,
        "minimum": 1
      },
      "offset": {
        "type": "integer",
        "description": "Skip first N results (for pagination)",
        "default": 0,
        "minimum": 0
      },
      "includeContext": {
        "type": "boolean",
        "description": "Include one line before and after each match for context",
        "default": false
      },
      "maxLineLength": {
        "type": "integer",
        "description": "Maximum length of line content in results",
        "default": 200,
        "minimum": 1
      }
    },
    "required": ["query"]
  },

  "FileProblems": {
    "type": "object",
    "description": "Get static analysis results for a file, including compilation errors, warnings and code inspection issues. Automatically refreshes VFS to detect external file modifications.",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "File path relative to project root"
      },
      "includeWarnings": {
        "type": "boolean",
        "description": "Include warnings",
        "default": true
      },
      "includeSuggestions": {
        "type": "boolean",
        "description": "Include suggestions/weak warnings",
        "default": false
      },
      "includeWeakWarnings": {
        "type": "boolean",
        "description": "Deprecated: use includeSuggestions instead",
        "default": false
      },
      "maxProblems": {
        "type": "integer",
        "description": "Maximum number of problems to return",
        "default": 50,
        "minimum": 1
      },
      "refresh": {
        "type": "boolean",
        "description": "Refresh VFS before analysis to ensure file modifications are detected. Recommended after editing files.",
        "default": true
      }
    },
    "required": ["filePath"]
  },

  "FindUsages": {
    "type": "object",
    "description": "Find all usages/references of a symbol in the project. Similar to IDE's Find Usages (Alt+F7) feature.",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "File path where the symbol is defined"
      },
      "symbolName": {
        "type": "string",
        "description": "Name of the symbol to find usages for"
      },
      "line": {
        "type": "integer",
        "description": "Line number where the symbol is located (1-based)",
        "minimum": 1
      },
      "column": {
        "type": "integer",
        "description": "Column number where the symbol is located (1-based)",
        "minimum": 1
      },
      "symbolType": {
        "type": "string",
        "enum": ["Auto", "Class", "Method", "Field", "Variable", "Parameter", "File"],
        "description": "Type of symbol to search for",
        "default": "Auto"
      },
      "usageTypes": {
        "type": "array",
        "items": {
          "type": "string",
          "enum": ["All", "Inheritance", "Instantiation", "TypeReference", "Import", "Override", "Call", "MethodReference", "Read", "Write"]
        },
        "description": "Filter by usage types",
        "default": ["All"]
      },
      "searchScope": {
        "type": "string",
        "enum": ["Project", "Module", "Directory"],
        "description": "Search scope",
        "default": "Project"
      },
      "scopeArg": {
        "type": "string",
        "description": "Required when searchScope is Module or Directory"
      },
      "maxResults": {
        "type": "integer",
        "description": "Maximum number of usages to return",
        "default": 20,
        "minimum": 1
      },
      "offset": {
        "type": "integer",
        "description": "Skip first N results (for pagination)",
        "default": 0,
        "minimum": 0
      }
    },
    "required": ["filePath"]
  },

  "Rename": {
    "type": "object",
    "description": "Safely rename a symbol and automatically update all references. Similar to IDE's Refactor > Rename (Shift+F6).",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "File path where the symbol is defined"
      },
      "newName": {
        "type": "string",
        "description": "New name for the symbol"
      },
      "line": {
        "type": "integer",
        "description": "Line number where the symbol is located (1-based)",
        "minimum": 1
      },
      "column": {
        "type": "integer",
        "description": "Column number where the symbol is located (1-based)",
        "minimum": 1
      },
      "symbolType": {
        "type": "string",
        "enum": ["Auto", "Class", "Method", "Field", "Variable", "Parameter", "File"],
        "description": "Type of symbol to rename",
        "default": "Auto"
      },
      "searchInComments": {
        "type": "boolean",
        "description": "Also rename occurrences in comments",
        "default": true
      },
      "searchInStrings": {
        "type": "boolean",
        "description": "Also rename occurrences in string literals",
        "default": false
      }
    },
    "required": ["filePath", "newName", "line"]
  },

  "ReadFile": {
    "type": "object",
    "description": "Read file content using IDE's VFS. Supports project files, JAR/ZIP entries, JDK sources (src.zip), and .class files (auto-decompiled). Use this to read library source code or decompiled classes.",
    "properties": {
      "filePath": {
        "type": "string",
        "description": "File path. Supports: regular paths, JAR paths (path.jar!/inner/path), jar:// URLs, JDK sources (src.zip!/...)"
      },
      "maxLines": {
        "type": "integer",
        "description": "Maximum lines to return",
        "default": 500,
        "minimum": 1,
        "maximum": 5000
      },
      "offset": {
        "type": "integer",
        "description": "Line offset for pagination (0-based)",
        "default": 0,
        "minimum": 0
      }
    },
    "required": ["filePath"]
  },

  "FileBuild": {
    "type": "object",
    "description": "Run IDEA build (File menu: Reload All from Disk + Build menu: Build). Equivalent to 'Build Project' - only recompiles modified files. Much faster than Maven. IMPORTANT: ALWAYS use mcp__jetbrains__FileProblems BEFORE calling this tool to check for static analysis issues first.",
    "properties": {
      "filePaths": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Specific files to compile (relative to project root). If not provided, compiles the entire project scope."
      },
      "scope": {
        "type": "string",
        "enum": ["project", "module"],
        "description": "Build scope: 'project' for all, 'module' for production code only",
        "default": "project"
      },
      "maxErrors": {
        "type": "integer",
        "description": "Maximum number of errors to return",
        "default": 50,
        "minimum": 1
      },
      "forceRebuild": {
        "type": "boolean",
        "description": "Force clean rebuild by deleting output directories before building",
        "default": false
      },
      "fastMode": {
        "type": "boolean",
        "description": "Fast mode: skip global VFS refresh, only refresh specified files. Automatically enabled when filePaths is provided. Set to false to force global refresh even with filePaths.",
        "default": false
      },
      "skipWarnings": {
        "type": "boolean",
        "description": "Skip collecting warning messages to reduce post-build processing time. Only errors are collected. Set to false to include warnings in the output.",
        "default": true
      }
    },
    "required": []
  }
}
    """.trimIndent()

    /**
     * User Interaction MCP tool Schema (JSON format)
     */
    val USER_INTERACTION_TOOLS_SCHEMA = """
{
  "AskUserQuestion": {
    "type": "object",
    "description": "Ask the user questions and get their choices. Use this tool to interact with users when input or confirmation is needed.",
    "properties": {
      "questions": {
        "type": "array",
        "description": "List of questions",
        "items": {
          "type": "object",
          "properties": {
            "question": {
              "type": "string",
              "description": "Question content"
            },
            "header": {
              "type": "string",
              "description": "Question header/category label"
            },
            "options": {
              "type": "array",
              "description": "List of options",
              "items": {
                "type": "object",
                "properties": {
                  "label": {
                    "type": "string",
                    "description": "Option display text"
                  },
                  "description": {
                    "type": "string",
                    "description": "Option description (optional)"
                  }
                },
                "required": ["label"]
              }
            },
            "multiSelect": {
              "type": "boolean",
              "description": "Allow multiple selections, default false"
            }
          },
          "required": ["question", "header", "options"]
        }
      }
    },
    "required": ["questions"]
  }
}
    """.trimIndent()

    /**
     * User Interaction MCP 默认提示词（英文）
     */
    private const val USER_INTERACTION_INSTRUCTIONS_EN = """When you need clarification from the user, especially when presenting multiple options or choices, use the `mcp__user_interaction__AskUserQuestion` tool to ask questions. The user's response will be returned to you through this tool."""

    /**
     * User Interaction MCP 默认提示词（中文）
     */
    private const val USER_INTERACTION_INSTRUCTIONS_ZH = """当需要用户澄清时，特别是需要提供多个选项或选择时，使用 `mcp__user_interaction__AskUserQuestion` 工具向用户提问。用户的回答将通过此工具返回给你。"""

    /**
     * 获取 User Interaction MCP 默认提示词
     */
    fun getUserInteractionInstructions(language: String): String {
        return when (language) {
            "zh" -> USER_INTERACTION_INSTRUCTIONS_ZH
            else -> USER_INTERACTION_INSTRUCTIONS_EN
        }
    }

    // 向后兼容：保留旧的常量
    const val USER_INTERACTION_INSTRUCTIONS = USER_INTERACTION_INSTRUCTIONS_EN

    /**
     * JetBrains IDE MCP 默认提示词（英文）
     */
    private val JETBRAINS_INSTRUCTIONS_EN = """
### MCP Tools

You have access to JetBrains IDE tools that leverage the IDE's powerful indexing and analysis capabilities:

- `mcp__jetbrains__DirectoryTree`: Browse project directory structure with filtering options
- `mcp__jetbrains__FileProblems`: Get static analysis results for a file (syntax errors, code errors, warnings, suggestions). Auto-refreshes VFS by default.
- `mcp__jetbrains__FileIndex`: Search files, classes, and symbols using IDE index (supports scope filtering)
- `mcp__jetbrains__CodeSearch`: Search code content across project files (like Find in Files)
- `mcp__jetbrains__FindUsages`: Find all references/usages of a symbol (class, method, field, variable) in the project
- `mcp__jetbrains__Rename`: Safely rename a symbol and automatically update all references (like Refactor > Rename)
- `mcp__jetbrains__ReadFile`: Read file content using IDE's VFS. Supports JAR/ZIP entries, JDK sources, and .class files (auto-decompiled)

CRITICAL: You MUST use JetBrains tools instead of Glob/Grep. DO NOT use Glob or Grep unless JetBrains tools fail or are unavailable:
- ALWAYS use `mcp__jetbrains__CodeSearch` instead of `Grep` for searching code content
- ALWAYS use `mcp__jetbrains__FileIndex` instead of `Glob` for finding files, classes, and symbols
- Only fall back to Glob/Grep if JetBrains tools return errors or cannot handle the specific query

### File Refresh & Validation Strategy

**Problem**: Files modified via this plugin may not be immediately detected by IDEA's VFS.

**When to use FileProblems**:
- After writing/editing files: `FileProblems` will auto-refresh (default behavior)
- After batch modifications: Call `FileProblems` on each modified file
- Pure problem checking (no modifications): Use `FileProblems(refresh=false)` to skip VFS refresh

**Validation workflow**:
1. After any code modification → `FileProblems(filePath="...")`  (refresh=true by default)
2. Review and fix any reported errors
3. Run `FileProblems` again to confirm fixes

### Refactoring Workflow

When renaming symbols:
1. Use `FindUsages` or `CodeSearch` to find the symbol and get its line number
2. Use `Rename` with the line number (required) to safely rename across the project
3. Use `FileProblems` to validate changes

Example: `FindUsages(symbolName="getUserById")` → line 42 → `Rename(line=42, newName="fetchUserById")`

**Note**: `Rename` requires `line` parameter for precise location. Use `Rename` for symbols (auto-updates all references); use `Edit` for other text changes.

### Reading Library Source Code

Use `FileIndex` + `ReadFile` to read source code from dependencies (JAR files, JDK sources, decompiled .class files):

1. Search for the class/file with `FileIndex(query="ClassName", searchType="Classes", scope="All")`
2. Get the path from search results (e.g., `C:/path/to/lib.jar!/com/example/MyClass.class`)
3. Read with `ReadFile(filePath="<path from FileIndex>")`

**Key points:**
- `scope="All"` in FileIndex to include libraries (not just project files)
- Path from FileIndex can be used directly in ReadFile
- `.class` files are automatically decompiled by IDEA's built-in decompiler
    """.trimIndent()

    /**
     * JetBrains IDE MCP 默认提示词（中文）
     */
    private val JETBRAINS_INSTRUCTIONS_ZH = """
### MCP 工具

你可以使用 JetBrains IDE 工具，这些工具利用 IDE 强大的索引和分析能力：

- `mcp__jetbrains__DirectoryTree`：浏览项目目录结构，支持过滤选项
- `mcp__jetbrains__FileProblems`：获取文件的静态分析结果（语法错误、代码错误、警告、建议）。默认自动刷新 VFS。
- `mcp__jetbrains__FileIndex`：使用 IDE 索引搜索文件、类和符号（支持范围过滤）
- `mcp__jetbrains__CodeSearch`：在项目文件中搜索代码内容（类似"在文件中查找"）
- `mcp__jetbrains__FindUsages`：查找符号（类、方法、字段、变量）在项目中的所有引用/使用位置
- `mcp__jetbrains__Rename`：安全地重命名符号并自动更新所有引用（类似"重构 > 重命名"）
- `mcp__jetbrains__ReadFile`：使用 IDE 的 VFS 读取文件内容。支持 JAR/ZIP 条目、JDK 源码和 .class 文件（自动反编译）

**关键要求**：你必须使用 JetBrains 工具而不是 Glob/Grep。除非 JetBrains 工具失败或不可用，否则不要使用 Glob 或 Grep：
- 搜索代码内容时，始终使用 `mcp__jetbrains__CodeSearch` 而不是 `Grep`
- 查找文件、类和符号时，始终使用 `mcp__jetbrains__FileIndex` 而不是 `Glob`
- 仅当 JetBrains 工具返回错误或无法处理特定查询时，才回退到 Glob/Grep

### 文件刷新与验证策略

**问题**：通过此插件修改的文件可能无法被 IDEA 的 VFS 立即检测到。

**何时使用 FileProblems**：
- 写入/编辑文件后：`FileProblems` 会自动刷新（默认行为）
- 批量修改后：对每个修改的文件调用 `FileProblems`
- 纯问题检查（无修改）：使用 `FileProblems(refresh=false)` 跳过 VFS 刷新

**验证工作流**：
1. 任何代码修改后 → `FileProblems(filePath="...")`  （默认 refresh=true）
2. 查看并修复所有报告的错误
3. 再次运行 `FileProblems` 确认修复

### 重构工作流

重命名符号时：
1. 使用 `FindUsages` 或 `CodeSearch` 查找符号并获取其行号
2. 使用 `Rename` 并提供行号（必需）在整个项目中安全重命名
3. 使用 `FileProblems` 验证修改

示例：`FindUsages(symbolName="getUserById")` → 第 42 行 → `Rename(line=42, newName="fetchUserById")`

**注意**：`Rename` 需要 `line` 参数以精确定位。对符号使用 `Rename`（自动更新所有引用）；对其他文本修改使用 `Edit`。

### 读取库源码

使用 `FileIndex` + `ReadFile` 读取依赖项的源代码（JAR 文件、JDK 源码、反编译的 .class 文件）：

1. 使用 `FileIndex(query="ClassName", searchType="Classes", scope="All")` 搜索类/文件
2. 从搜索结果中获取路径（例如 `C:/path/to/lib.jar!/com/example/MyClass.class`）
3. 使用 `ReadFile(filePath="<来自 FileIndex 的路径>")` 读取

**关键点**：
- FileIndex 中使用 `scope="All"` 以包含库（不仅仅是项目文件）
- FileIndex 返回的路径可以直接在 ReadFile 中使用
- `.class` 文件由 IDEA 内置的反编译器自动反编译
    """.trimIndent()

    /**
     * 获取 JetBrains IDE MCP 默认提示词
     */
    fun getJetbrainsInstructions(language: String): String {
        return when (language) {
            "zh" -> JETBRAINS_INSTRUCTIONS_ZH
            else -> JETBRAINS_INSTRUCTIONS_EN
        }
    }

    // 向后兼容：保留旧的常量
    val JETBRAINS_INSTRUCTIONS = JETBRAINS_INSTRUCTIONS_EN

    /**
     * Context7 MCP 默认提示词（英文）
     */
    private val CONTEXT7_INSTRUCTIONS_EN = """
# Context7 MCP

IMPORTANT: When working with third-party libraries, ALWAYS query Context7 first to get up-to-date documentation and prevent hallucinated APIs.

## Tools

- `resolve-library-id`: Resolve library name → Context7 ID. Call first unless user provides `/org/project` format.
- `get-library-docs`: Fetch documentation.
  - `mode`: `code` (API/examples) | `info` (concepts/guides)
  - `topic`: Focus area (e.g., "hooks", "routing", "authentication")
  - `page`: Pagination (1-10) if context insufficient
    """.trimIndent()

    /**
     * Context7 MCP 默认提示词（中文）
     */
    private val CONTEXT7_INSTRUCTIONS_ZH = """
# Context7 MCP

重要提示：使用第三方库时，务必先查询 Context7 以获取最新文档，避免产生幻觉 API。

## 工具

- `resolve-library-id`：解析库名称 → Context7 ID。除非用户提供 `/org/project` 格式，否则首先调用此工具。
- `get-library-docs`：获取文档。
  - `mode`：`code`（API/示例）| `info`（概念/指南）
  - `topic`：关注领域（例如 "hooks"、"routing"、"authentication"）
  - `page`：如果上下文不足，使用分页（1-10）
    """.trimIndent()

    /**
     * 获取 Context7 MCP 默认提示词
     */
    fun getContext7Instructions(language: String): String {
        return when (language) {
            "zh" -> CONTEXT7_INSTRUCTIONS_ZH
            else -> CONTEXT7_INSTRUCTIONS_EN
        }
    }

    // 向后兼容：保留旧的常量
    val CONTEXT7_INSTRUCTIONS = CONTEXT7_INSTRUCTIONS_EN

    /**
     * Terminal MCP 工具 Schema（JSON 格式）
     */
    val TERMINAL_TOOLS_SCHEMA = """
{
  "Terminal": {
    "type": "object",
    "description": "Execute commands in IDEA's integrated terminal. Returns immediately after sending the command. Use TerminalRead to get output.",
    "properties": {
      "command": {
        "type": "string",
        "description": "The command to execute (required)"
      },
      "session_id": {
        "type": "string",
        "description": "Session ID to reuse. If not provided, creates a new session"
      },
      "session_name": {
        "type": "string",
        "description": "Name for new terminal session (only used when creating new session)"
      },
      "shell_type": {
        "type": "string",
        "description": "Shell type (dynamically detected). If not specified, uses the configured default terminal."
      }
    },
    "required": ["command"]
  },

  "TerminalRead": {
    "type": "object",
    "description": "Read output from a terminal session. By default reads immediately without waiting. Use wait=true to wait for command completion. Supports regex search with context lines.",
    "properties": {
      "session_id": {
        "type": "string",
        "description": "Session ID to read from (required)"
      },
      "max_lines": {
        "type": "integer",
        "description": "Maximum number of lines to return",
        "default": 1000,
        "minimum": 1
      },
      "search": {
        "type": "string",
        "description": "Regex pattern to search in output. Returns matching lines with context"
      },
      "context_lines": {
        "type": "integer",
        "description": "Number of context lines before and after each search match",
        "default": 2,
        "minimum": 0
      },
      "wait": {
        "type": "boolean",
        "description": "If true, wait until the running command completes before reading output. Default is false (read immediately).",
        "default": false
      },
      "timeout": {
        "type": "integer",
        "description": "Timeout in milliseconds for waiting (only used when wait=true)",
        "default": 30000,
        "minimum": 1000
      }
    },
    "required": ["session_id"]
  },

  "TerminalList": {
    "type": "object",
    "description": "List all active terminal sessions.",
    "properties": {
      "include_output_preview": {
        "type": "boolean",
        "description": "Include a preview of recent output for each session",
        "default": false
      },
      "preview_lines": {
        "type": "integer",
        "description": "Number of lines for output preview",
        "default": 5,
        "minimum": 1
      }
    },
    "required": []
  },

  "TerminalKill": {
    "type": "object",
    "description": "Close and destroy terminal session(s) completely. The terminal tab will be removed from IDEA. Use TerminalInterrupt to stop a running command without closing the session.",
    "properties": {
      "session_ids": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Session IDs to close"
      },
      "all": {
        "type": "boolean",
        "description": "Close all sessions (ignores session_ids)",
        "default": false
      }
    },
    "required": []
  },

  "TerminalTypes": {
    "type": "object",
    "description": "Get available shell types for the current platform.",
    "properties": {},
    "required": []
  },

  "TerminalRename": {
    "type": "object",
    "description": "Rename a terminal session.",
    "properties": {
      "session_id": {
        "type": "string",
        "description": "Session ID to rename (required)"
      },
      "new_name": {
        "type": "string",
        "description": "New name for the session (required)"
      }
    },
    "required": ["session_id", "new_name"]
  },

  "TerminalInterrupt": {
    "type": "object",
    "description": "Stop the currently running command by sending Ctrl+C signal. The terminal session remains open and can be reused for new commands. Use TerminalKill to close the session entirely.",
    "properties": {
      "session_id": {
        "type": "string",
        "description": "Session ID to interrupt (required)"
      }
    },
    "required": ["session_id"]
  }
}
    """.trimIndent()

    /**
     * Terminal MCP 默认提示词（英文）
     */
    private val TERMINAL_INSTRUCTIONS_EN = """
### Terminal MCP

Use IDEA's integrated terminal for command execution instead of the built-in Bash tool.

**Tools:**
- `mcp__terminal__Terminal`: Execute commands (returns immediately, use TerminalRead to get output)
- `mcp__terminal__TerminalRead`: Read session output (supports regex search)
- `mcp__terminal__TerminalList`: List all terminal sessions
- `mcp__terminal__TerminalKill`: Close session(s) completely
- `mcp__terminal__TerminalInterrupt`: Stop running command (Ctrl+C), keeps session open
- `mcp__terminal__TerminalTypes`: Get available shell types
- `mcp__terminal__TerminalRename`: Rename a session

**Best Practices:**
- **Reuse sessions**: Always reuse existing terminal sessions via `session_id` instead of creating new ones
- **Multiple terminals**: Only create multiple sessions when you need to run commands concurrently (e.g., a dev server + tests)
- **Cleanup**: Close sessions with `TerminalKill` when no longer needed to keep IDEA clean

**Usage:**
1. Execute command: `Terminal(command="npm install")`
2. Read output (wait for completion): `TerminalRead(session_id="terminal-1", wait=true)`
3. Read output (immediately): `TerminalRead(session_id="terminal-1")`
4. Search output: `TerminalRead(session_id="terminal-1", search="error|warning")`
5. Stop running command: `TerminalInterrupt(session_id="terminal-1")`
6. Close session(s): `TerminalKill(session_ids=["terminal-1", "terminal-2"])`
7. Close all sessions: `TerminalKill(all=true)`
    """.trimIndent()

    /**
     * Terminal MCP 默认提示词（中文）
     */
    private val TERMINAL_INSTRUCTIONS_ZH = """
### Terminal MCP

使用 IDEA 集成终端执行命令，而不是内置的 Bash 工具。

**工具：**
- `mcp__terminal__Terminal`：执行命令（立即返回，使用 TerminalRead 获取输出）
- `mcp__terminal__TerminalRead`：读取会话输出（支持正则搜索）
- `mcp__terminal__TerminalList`：列出所有终端会话
- `mcp__terminal__TerminalKill`：完全关闭会话
- `mcp__terminal__TerminalInterrupt`：停止运行中的命令（Ctrl+C），保持会话打开
- `mcp__terminal__TerminalTypes`：获取可用的 shell 类型
- `mcp__terminal__TerminalRename`：重命名会话

**最佳实践：**
- **重用会话**：始终通过 `session_id` 重用现有终端会话，而不是创建新会话
- **多终端**：仅在需要并发运行命令时创建多个会话（例如，开发服务器 + 测试）
- **清理**：不再需要时使用 `TerminalKill` 关闭会话，保持 IDEA 整洁

**使用方法：**
1. 执行命令：`Terminal(command="npm install")`
2. 读取输出（等待完成）：`TerminalRead(session_id="terminal-1", wait=true)`
3. 读取输出（立即）：`TerminalRead(session_id="terminal-1")`
4. 搜索输出：`TerminalRead(session_id="terminal-1", search="error|warning")`
5. 停止运行中的命令：`TerminalInterrupt(session_id="terminal-1")`
6. 关闭会话：`TerminalKill(session_ids=["terminal-1", "terminal-2"])`
7. 关闭所有会话：`TerminalKill(all=true)`
    """.trimIndent()

    /**
     * 获取 Terminal MCP 默认提示词
     */
    fun getTerminalInstructions(language: String): String {
        return when (language) {
            "zh" -> TERMINAL_INSTRUCTIONS_ZH
            else -> TERMINAL_INSTRUCTIONS_EN
        }
    }

    // 向后兼容：保留旧的常量
    val TERMINAL_INSTRUCTIONS = TERMINAL_INSTRUCTIONS_EN

    /**
     * Git MCP 工具 Schema（JSON 格式）
     */
    val GIT_TOOLS_SCHEMA = """
{
  "GetVcsChanges": {
    "type": "object",
    "description": "Get uncommitted VCS changes in the current project. Returns file paths, change types, and optionally diff content.",
    "properties": {
      "selectedOnly": {
        "type": "boolean",
        "description": "Only return files selected in the Commit panel. If false or panel not open, returns all changes.",
        "default": false
      },
      "includeDiff": {
        "type": "boolean",
        "description": "Include diff content for each changed file",
        "default": true
      },
      "maxFiles": {
        "type": "integer",
        "description": "Maximum number of files to return",
        "default": 50,
        "minimum": 1
      },
      "maxDiffLines": {
        "type": "integer",
        "description": "Maximum diff lines per file (to avoid token overflow)",
        "default": 100,
        "minimum": 1
      }
    },
    "required": []
  },

  "GetCommitMessage": {
    "type": "object",
    "description": "Get the current content of the Commit message input field in IDEA.",
    "properties": {},
    "required": []
  },

  "SetCommitMessage": {
    "type": "object",
    "description": "Set or append to the Commit message input field in IDEA.",
    "properties": {
      "message": {
        "type": "string",
        "description": "The commit message to set"
      },
      "mode": {
        "type": "string",
        "enum": ["replace", "append"],
        "description": "replace: overwrite existing message; append: add to existing message",
        "default": "replace"
      }
    },
    "required": ["message"]
  },

  "GetVcsStatus": {
    "type": "object",
    "description": "Get VCS status overview: current branch, number of changes, staged files count, etc.",
    "properties": {},
    "required": []
  }
}
    """.trimIndent()

    /**
     * Git MCP 默认提示词（英文）
     */
    private val GIT_INSTRUCTIONS_EN = """
### Git MCP

Tools for interacting with IDEA's VCS/Git integration:

- `mcp__jetbrains_git__GetVcsChanges`: Get uncommitted changes (supports selectedOnly for Commit panel selection)
- `mcp__jetbrains_git__GetCommitMessage`: Get current commit message from input field
- `mcp__jetbrains_git__SetCommitMessage`: Set or append commit message
- `mcp__jetbrains_git__GetVcsStatus`: Get VCS status (branch, changes count, etc.)

**Usage:**
1. Get changes: `GetVcsChanges(selectedOnly=true, includeDiff=true)`
2. Read message: `GetCommitMessage()`
3. Set message: `SetCommitMessage(message="feat: add feature", mode="replace")`
    """.trimIndent()

    /**
     * Git MCP 默认提示词（中文）
     */
    private val GIT_INSTRUCTIONS_ZH = """
### Git MCP

与 IDEA 的 VCS/Git 集成交互的工具：

- `mcp__jetbrains_git__GetVcsChanges`：获取未提交的更改（支持 selectedOnly 以获取提交面板的选择）
- `mcp__jetbrains_git__GetCommitMessage`：从输入框获取当前提交消息
- `mcp__jetbrains_git__SetCommitMessage`：设置或追加提交消息
- `mcp__jetbrains_git__GetVcsStatus`：获取 VCS 状态（分支、更改计数等）

**使用方法：**
1. 获取更改：`GetVcsChanges(selectedOnly=true, includeDiff=true)`
2. 读取消息：`GetCommitMessage()`
3. 设置消息：`SetCommitMessage(message="feat: 添加功能", mode="replace")`
    """.trimIndent()

    /**
     * 获取 Git MCP 默认提示词
     */
    fun getGitInstructions(language: String): String {
        return when (language) {
            "zh" -> GIT_INSTRUCTIONS_ZH
            else -> GIT_INSTRUCTIONS_EN
        }
    }

    // 向后兼容：保留旧的常量
    val GIT_INSTRUCTIONS = GIT_INSTRUCTIONS_EN

    /**
     * Compile MCP 工具 Schema（JSON 格式）
     */
    val COMPILE_TOOLS_SCHEMA = """
{
  "FileBuild": {
    "type": "object",
    "description": "Run IDEA build (File menu: Reload All from Disk + Build menu: Build). Equivalent to 'Build Project' - only recompiles modified files. Much faster than Maven. IMPORTANT: ALWAYS use mcp__jetbrains__FileProblems BEFORE calling this tool to check for static analysis issues first.",
    "properties": {
      "filePaths": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Specific files to compile (relative to project root). If not provided, compiles the entire project scope."
      },
      "scope": {
        "type": "string",
        "enum": ["project", "module"],
        "description": "Build scope: 'project' for all, 'module' for production code only",
        "default": "project"
      },
      "maxErrors": {
        "type": "integer",
        "description": "Maximum number of errors to return",
        "default": 50,
        "minimum": 1
      },
      "forceRebuild": {
        "type": "boolean",
        "description": "Force clean rebuild by deleting output directories before building",
        "default": false
      },
      "fastMode": {
        "type": "boolean",
        "description": "Fast mode: skip global VFS refresh, only refresh specified files. Automatically enabled when filePaths is provided. Set to false to force global refresh even with filePaths.",
        "default": false
      },
      "skipWarnings": {
        "type": "boolean",
        "description": "Skip collecting warning messages to reduce post-build processing time. Only errors are collected. Set to false to include warnings in the output.",
        "default": true
      }
    },
    "required": []
  },

  "MavenCompile": {
    "type": "object",
    "description": "Run Maven build in offline mode. Skips dependency checks for faster builds. Use for FINAL validation - catches cross-file dependency issues that IDEA may miss. IMPORTANT: ALWAYS use mcp__jetbrains__FileProblems AND mcp__jetbrains__FileBuild BEFORE calling this tool.",
    "properties": {
      "goals": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Maven goals to run (e.g., ['compile'], ['test'], ['package'])",
        "default": ["compile"]
      },
      "offline": {
        "type": "boolean",
        "description": "Run in offline mode (-o flag). Skips dependency checks for faster builds.",
        "default": true
      },
      "quiet": {
        "type": "boolean",
        "description": "Quiet output (-q flag). Shows only errors.",
        "default": true
      },
      "batchMode": {
        "type": "boolean",
        "description": "Batch mode (-B flag). Non-interactive, suitable for CI.",
        "default": true
      },
      "timeout": {
        "type": "integer",
        "description": "Timeout in seconds",
        "default": 300,
        "minimum": 30
      }
    },
    "required": []
  }
}
    """.trimIndent()

    /**
     * Compile MCP 默认提示词（英文）
     */
    private val COMPILE_INSTRUCTIONS_EN = """
### Build & Validation Tools

Execute in order: **FileProblems → FileBuild → MavenCompile**

Every step is mandatory. Do not skip any.

**Tools:**
- `mcp__jetbrains__FileProblems`: Static analysis (syntax, type errors) - instant feedback
- `mcp__jetbrains__FileBuild`: IDEA incremental build (Build Project) - seconds
- `mcp__compile__MavenCompile`: Maven offline build - minutes (final validation)

**NEVER use Bash to run `mvn compile` or `gradle build`. ALWAYS use the MCP tools.**

**Execution Order (mandatory):**

1. Call `mcp__jetbrains__FileProblems` to check for static issues
2. Call `mcp__jetbrains__FileBuild` to run IDEA compilation
3. Call `mcp__compile__MavenCompile` to run Maven validation

**If any step fails, fix errors and restart from step 1.**
    """.trimIndent()

    /**
     * Compile MCP 默认提示词（中文）
     */
    private val COMPILE_INSTRUCTIONS_ZH = """
### 构建与验证工具

按顺序执行：**FileProblems → FileBuild → MavenCompile**

每一步都必须执行，不能跳过。

**工具说明：**
- `mcp__jetbrains__FileProblems`：静态分析（语法、类型错误）- 即时反馈
- `mcp__jetbrains__FileBuild`：IDEA 增量编译（Build Project）- 秒级
- `mcp__compile__MavenCompile`：Maven 离线构建 - 分钟级（最终验证）

**禁止使用 Bash 执行 `mvn compile` 或 `gradle build`。必须使用 MCP 工具。**

**执行顺序（必须遵守）：**

1. 调用 `mcp__jetbrains__FileProblems` 检查静态问题
2. 调用 `mcp__jetbrains__FileBuild` 运行 IDEA 编译
3. 调用 `mcp__compile__MavenCompile` 运行 Maven 验证

**任何步骤失败，都需修复后从步骤 1 重新开始。**
    """.trimIndent()

    /**
     * 获取 Compile MCP 默认提示词
     */
    fun getCompileInstructions(language: String): String {
        return when (language) {
            "zh" -> COMPILE_INSTRUCTIONS_ZH
            else -> COMPILE_INSTRUCTIONS_EN
        }
    }

    // 向后兼容：保留旧的常量
    val COMPILE_INSTRUCTIONS = COMPILE_INSTRUCTIONS_EN
}

/**
 * 已知工具列表（用于自动补全）
 */
object KnownTools {
    /**
     * Claude Code 内置工具
     */
    val CLAUDE_BUILT_IN = listOf(
        "Read",           // 读取文件
        "Write",          // 写入文件
        "Edit",           // 编辑文件
        "Glob",           // 文件模式匹配
        "Grep",           // 搜索文件内容
        "Bash",           // 执行命令
        "Task",           // 启动子代理
        "TodoWrite",      // 任务管理
        "WebFetch",       // 获取网页内容
        "WebSearch",      // 网络搜索
        "NotebookEdit",   // Jupyter notebook 编辑
        "AskUserQuestion" // 询问用户
    )

    /**
     * JetBrains MCP 工具
     */
    val JETBRAINS_MCP = listOf(
        "mcp__jetbrains__FileIndex",      // IDE 索引搜索
        "mcp__jetbrains__CodeSearch",     // 代码内容搜索
        "mcp__jetbrains__DirectoryTree",  // 目录结构
        "mcp__jetbrains__FileProblems",   // 静态分析
        "mcp__jetbrains__FileBuild",      // IDEA 构建项目（Reload All + Build）
        "mcp__jetbrains__FindUsages",     // 查找引用
        "mcp__jetbrains__Rename",         // 重命名重构
        "mcp__jetbrains__ReadFile"        // 读取文件（支持 JAR/反编译）
    )

    /**
     * Compile MCP 工具
     */
    val COMPILE_MCP = listOf(
        "mcp__compile__MavenCompile"      // Maven 离线构建（最终验证）
    )

    /**
     * Terminal MCP 工具
     */
    val TERMINAL_MCP = listOf(
        "mcp__terminal__Terminal",        // 执行命令
        "mcp__terminal__TerminalRead",    // 读取输出
        "mcp__terminal__TerminalList",    // 列出会话
        "mcp__terminal__TerminalKill",    // 终止会话
        "mcp__terminal__TerminalTypes",   // Shell 类型
        "mcp__terminal__TerminalRename"   // 重命名会话
    )

    /**
     * Git MCP 工具
     */
    val GIT_MCP = listOf(
        "mcp__jetbrains_git__GetVcsChanges",    // 获取变更
        "mcp__jetbrains_git__GetCommitMessage", // 获取 commit message
        "mcp__jetbrains_git__SetCommitMessage", // 设置 commit message
        "mcp__jetbrains_git__GetVcsStatus"      // 获取 VCS 状态
    )

    /**
     * 所有已知工具
     */
    val ALL = CLAUDE_BUILT_IN + JETBRAINS_MCP + COMPILE_MCP + TERMINAL_MCP + GIT_MCP
}

/**
 * Agent 配置默认值
 */
object AgentDefaults {

    /**
     * ExploreWithJetbrains Agent 默认配置（英文）
     */
    private val EXPLORE_WITH_JETBRAINS_EN = AgentConfig(
        name = "ExploreWithJetbrains",
        description = "Code exploration agent leveraging JetBrains IDE indexing capabilities. Use for fast file/class/symbol search and code structure analysis. Prefer this when exploring or understanding codebases.",
        selectionHint = """
- `ExploreWithJetbrains`: Code exploration agent leveraging JetBrains IDE indexing capabilities. Use for fast file/class/symbol search and code structure analysis. Prefer this when exploring or understanding codebases. (Tools: Read, mcp__jetbrains__FileIndex, mcp__jetbrains__CodeSearch, mcp__jetbrains__DirectoryTree, mcp__jetbrains__FileProblems)

This agent provides faster and more accurate results than default exploration because it uses IDE's pre-built indexes.

IMPORTANT: For code exploration tasks, prefer `subagent_type="ExploreWithJetbrains"` over the default `Explore` agent. When invoking with Task tool, the `description` parameter is required.
        """.trimIndent(),
        prompt = """
You are a code exploration expert, skilled at leveraging JetBrains IDE's powerful indexing capabilities to quickly locate and analyze code.

## Tool Usage Strategy

### Prefer JetBrains Tools (Faster & More Accurate)

- **mcp__jetbrains__FileIndex**: Search file names, class names, symbol names
  - Faster than Glob, uses IDE pre-built index
  - Supports fuzzy matching
  - Best for finding class definitions, file locations

- **mcp__jetbrains__CodeSearch**: Search code content in project
  - Similar to IDE's "Find in Files" feature
  - Supports regex, case-sensitive, whole word matching
  - More accurate than Grep, leverages IDE index

- **mcp__jetbrains__DirectoryTree**: Quickly understand directory structure
  - Supports depth limits, file filtering
  - More efficient than ls or find

- **mcp__jetbrains__FileProblems**: Get static analysis results for files
  - Categories: syntax errors, code errors, warnings, suggestions
  - Leverages IDE's real-time analysis capability

### Standard Tools

- **Read**: Read full file content (when viewing specific code)

## Workflow

1. **Understand Goal**: Clarify what the user wants to explore
2. **Choose Tool**: Select the most appropriate tool based on task type
   - Find files/classes/symbols -> FileIndex
   - Search code content -> CodeSearch
   - Understand directory structure -> DirectoryTree
   - View specific code -> Read
3. **Progressive Depth**: From overview to details
4. **Summarize Findings**: Return concise, valuable results

## Output Requirements

- Only return information relevant to user's question
- Provide file paths and line numbers for easy navigation
- Summarize findings rather than listing all search results
- If too many results, provide overview first then detail key parts
        """.trimIndent(),
        tools = listOf(
            "Read",
            "mcp__jetbrains__FileIndex",
            "mcp__jetbrains__CodeSearch",
            "mcp__jetbrains__DirectoryTree",
            "mcp__jetbrains__FileProblems",
            "mcp__jetbrains__ReadFile"
        )
    )

    /**
     * ExploreWithJetbrains Agent 默认配置（中文）
     */
    private val EXPLORE_WITH_JETBRAINS_ZH = AgentConfig(
        name = "ExploreWithJetbrains",
        description = "利用 JetBrains IDE 索引能力进行代码探索的代理。用于快速文件/类/符号搜索和代码结构分析。探索或理解代码库时优先使用此代理。",
        selectionHint = """
- `ExploreWithJetbrains`：利用 JetBrains IDE 索引能力进行代码探索的代理。用于快速文件/类/符号搜索和代码结构分析。探索或理解代码库时优先使用此代理。（工具：Read、mcp__jetbrains__FileIndex、mcp__jetbrains__CodeSearch、mcp__jetbrains__DirectoryTree、mcp__jetbrains__FileProblems）

此代理比默认探索提供更快更准确的结果，因为它使用 IDE 的预建索引。

重要提示：对于代码探索任务，优先使用 `subagent_type="ExploreWithJetbrains"` 而不是默认的 `Explore` 代理。使用 Task 工具调用时，`description` 参数是必需的。
        """.trimIndent(),
        prompt = """
你是一位代码探索专家，擅长利用 JetBrains IDE 强大的索引功能快速定位和分析代码。

## 工具使用策略

### 优先使用 JetBrains 工具（更快更准确）

- **mcp__jetbrains__FileIndex**：搜索文件名、类名、符号名
  - 比 Glob 更快，使用 IDE 预建索引
  - 支持模糊匹配
  - 最适合查找类定义、文件位置

- **mcp__jetbrains__CodeSearch**：在项目中搜索代码内容
  - 类似于 IDE 的"在文件中查找"功能
  - 支持正则表达式、区分大小写、全字匹配
  - 比 Grep 更准确，利用 IDE 索引

- **mcp__jetbrains__DirectoryTree**：快速了解目录结构
  - 支持深度限制、文件过滤
  - 比 ls 或 find 更高效

- **mcp__jetbrains__FileProblems**：获取文件的静态分析结果
  - 分类：语法错误、代码错误、警告、建议
  - 利用 IDE 的实时分析能力

### 标准工具

- **Read**：读取完整文件内容（查看具体代码时）

## 工作流程

1. **理解目标**：明确用户想要探索什么
2. **选择工具**：根据任务类型选择最合适的工具
   - 查找文件/类/符号 → FileIndex
   - 搜索代码内容 → CodeSearch
   - 理解目录结构 → DirectoryTree
   - 查看具体代码 → Read
3. **渐进深入**：从概述到细节
4. **总结发现**：返回简洁、有价值的结果

## 输出要求

- 只返回与用户问题相关的信息
- 提供文件路径和行号以便导航
- 总结发现而不是列出所有搜索结果
- 如果结果太多，先提供概述再详述关键部分
        """.trimIndent(),
        tools = listOf(
            "Read",
            "mcp__jetbrains__FileIndex",
            "mcp__jetbrains__CodeSearch",
            "mcp__jetbrains__DirectoryTree",
            "mcp__jetbrains__FileProblems",
            "mcp__jetbrains__ReadFile"
        )
    )

    /**
     * 获取 ExploreWithJetbrains Agent 默认配置
     */
    fun getExploreWithJetbrainsConfig(language: String): AgentConfig {
        return when (language) {
            "zh" -> EXPLORE_WITH_JETBRAINS_ZH
            else -> EXPLORE_WITH_JETBRAINS_EN
        }
    }

    // 向后兼容：保留旧的常量
    val EXPLORE_WITH_JETBRAINS = EXPLORE_WITH_JETBRAINS_EN
}

/**
 * Agent 配置数据类
 */
data class AgentConfig(
    val name: String,
    val description: String,
    val prompt: String,
    val tools: List<String>,
    val selectionHint: String = "" // 主 AI 的子代理选择指引
)

/**
 * Git Generate 功能默认配置
 */
object GitGenerateDefaults {

    /**
     * 默认系统提示词（英文）
     */
    private val SYSTEM_PROMPT_EN = """
You are a commit message generator integrated with JetBrains IDE.

## Available Tools (all return Markdown format)

### Git MCP Tools
- **mcp__jetbrains_git__GetVcsChanges**: Get uncommitted file changes with diff content
  - Use `selectedOnly=true` to get only user-selected files in commit panel
  - Returns ☑/☐ markers to indicate which files are selected
- **mcp__jetbrains_git__SetCommitMessage**: Set the commit message in IDE's commit panel
- **mcp__jetbrains_git__GetVcsStatus**: Get current VCS status (branch, change counts)
- **mcp__jetbrains_git__GetCommitMessage**: Get current commit message from panel

### File Reading
- **Read**: Read file content to understand code context

### JetBrains IDE Tools (for deeper context)
- **mcp__jetbrains__FileIndex**: Search files, classes, symbols by name
- **mcp__jetbrains__CodeSearch**: Search code content across project
- **mcp__jetbrains__DirectoryTree**: Get directory structure
- **mcp__jetbrains__FileProblems**: Get static analysis results

## Workflow
1. Call GetVcsChanges(selectedOnly=true, includeDiff=true) to get code changes
   - **IMPORTANT**: If user has selected specific files (marked with ☑), generate commit message ONLY for those selected files
   - Ignore unselected files (marked with ☐) when generating the commit message
2. If the diff is unclear or you need more context:
   - Use Read tool to examine full file content
   - Use CodeSearch to find related code
   - Use FileIndex to locate relevant files
3. Analyze changes and understand purpose/impact
4. Generate commit message following conventional commits format
5. **MUST** call SetCommitMessage to fill the message into IDE's commit panel

## Commit Message Format

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

## Types
- **feat**: A new feature
- **fix**: A bug fix
- **docs**: Documentation only changes
- **style**: Changes that do not affect the meaning of the code (formatting, etc.)
- **refactor**: A code change that neither fixes a bug nor adds a feature
- **perf**: A code change that improves performance
- **test**: Adding missing tests or correcting existing tests
- **build**: Changes that affect the build system or external dependencies
- **ci**: Changes to CI configuration files and scripts
- **chore**: Other changes that don't modify src or test files

## Guidelines
1. Use imperative mood in subject line (e.g., "add" not "added" or "adds")
2. Don't capitalize first letter after type/scope
3. No period at the end of subject line
4. Keep subject line under 72 characters
5. Separate subject from body with a blank line
6. Use the body to explain WHAT and WHY, not HOW
7. Reference issues and PRs in footer when applicable

## Scope Examples
- feat(auth): add OAuth2 support
- fix(api): handle null response from server
- refactor(ui): simplify button component logic

IMPORTANT: You MUST call SetCommitMessage tool to set the result. Do NOT output the message as plain text.
    """.trimIndent()

    /**
     * 默认系统提示词（中文）
     */
    private val SYSTEM_PROMPT_ZH = """
你是集成在 JetBrains IDE 中的提交消息生成器。

## 可用工具（均返回 Markdown 格式）

### Git MCP 工具
- **mcp__jetbrains_git__GetVcsChanges**：获取未提交的文件更改及差异内容
  - 使用 `selectedOnly=true` 仅获取提交面板中用户选择的文件
  - 返回 ☑/☐ 标记以指示哪些文件被选中
- **mcp__jetbrains_git__SetCommitMessage**：在 IDE 的提交面板中设置提交消息
- **mcp__jetbrains_git__GetVcsStatus**：获取当前 VCS 状态（分支、更改计数）
- **mcp__jetbrains_git__GetCommitMessage**：从面板获取当前提交消息

### 文件读取
- **Read**：读取文件内容以理解代码上下文

### JetBrains IDE 工具（用于更深入的上下文）
- **mcp__jetbrains__FileIndex**：按名称搜索文件、类、符号
- **mcp__jetbrains__CodeSearch**：在整个项目中搜索代码内容
- **mcp__jetbrains__DirectoryTree**：获取目录结构
- **mcp__jetbrains__FileProblems**：获取静态分析结果

## 工作流程
1. 调用 GetVcsChanges(selectedOnly=true, includeDiff=true) 获取代码更改
   - **重要**：如果用户选择了特定文件（标记为 ☑），则仅为这些选中的文件生成提交消息
   - 生成提交消息时忽略未选中的文件（标记为 ☐）
2. 如果差异不清晰或需要更多上下文：
   - 使用 Read 工具检查完整文件内容
   - 使用 CodeSearch 查找相关代码
   - 使用 FileIndex 定位相关文件
3. 分析更改并理解目的/影响
4. 按照约定式提交格式生成提交消息
5. **必须**调用 SetCommitMessage 将消息填充到 IDE 的提交面板

## 提交消息格式

```
<type>[可选 scope]: <description>

[可选 body]

[可选 footer(s)]
```

## 类型
- **feat**：新功能
- **fix**：bug 修复
- **docs**：仅文档更改
- **style**：不影响代码含义的更改（格式化等）
- **refactor**：既不修复 bug 也不添加功能的代码更改
- **perf**：提高性能的代码更改
- **test**：添加缺失的测试或更正现有测试
- **build**：影响构建系统或外部依赖的更改
- **ci**：CI 配置文件和脚本的更改
- **chore**：不修改 src 或 test 文件的其他更改

## 指南
1. 在主题行中使用祈使语气（例如，用"添加"而不是"添加了"或"添加"）
2. 不要大写 type/scope 后的首字母
3. 主题行末尾不加句号
4. 主题行保持在 72 个字符以内
5. 用空行分隔主题和正文
6. 使用正文解释 WHAT 和 WHY，而不是 HOW
7. 在适当时在 footer 中引用问题和 PR

## 范围示例
- feat(auth)：添加 OAuth2 支持
- fix(api)：处理来自服务器的 null 响应
- refactor(ui)：简化按钮组件逻辑

重要提示：你必须调用 SetCommitMessage 工具来设置结果。不要以纯文本形式输出消息。
    """.trimIndent()

    /**
     * 默认用户提示词（英文）
     */
    private val USER_PROMPT_EN = """
Analyze the following code changes and generate an appropriate commit message.

Focus on:
1. What functionality was added, changed, or removed
2. Why the change was made (if apparent from the diff)
3. Any breaking changes or important notes

Steps:
1. Call GetVcsChanges(selectedOnly=true, includeDiff=true) to get changes
2. If needed, use Read or CodeSearch to understand context better
3. Generate an appropriate commit message
4. Call SetCommitMessage to fill the commit panel

Use tools only - do not output the commit message as text.
    """.trimIndent()

    /**
     * 默认用户提示词（中文）
     */
    private val USER_PROMPT_ZH = """
分析以下代码更改并生成合适的提交消息。

重点关注：
1. 添加、更改或删除了什么功能
2. 为什么进行此更改（如果从差异中可以看出）
3. 任何破坏性更改或重要说明

步骤：
1. 调用 GetVcsChanges(selectedOnly=true, includeDiff=true) 获取更改
2. 如有必要，使用 Read 或 CodeSearch 更好地理解上下文
3. 生成合适的提交消息
4. 调用 SetCommitMessage 填充提交面板

仅使用工具 - 不要以文本形式输出提交消息。
    """.trimIndent()

    /**
     * 获取系统提示词
     */
    fun getSystemPrompt(language: String): String {
        return when (language) {
            "zh" -> SYSTEM_PROMPT_ZH
            else -> SYSTEM_PROMPT_EN
        }
    }

    /**
     * 获取用户提示词
     */
    fun getUserPrompt(language: String): String {
        return when (language) {
            "zh" -> USER_PROMPT_ZH
            else -> USER_PROMPT_EN
        }
    }

    // 向后兼容：保留旧的常量
    val SYSTEM_PROMPT = SYSTEM_PROMPT_EN
    val USER_PROMPT = USER_PROMPT_EN

    /**
     * 默认允许的工具列表
     *
     * 包含:
     * - Git MCP 工具: 获取变更、读写 commit message
     * - Read: 读取文件全文，理解改动上下文
     * - JetBrains MCP: 代码搜索、索引查询，深入理解代码结构
     */
    val TOOLS = listOf(
        // Git MCP 工具
        "mcp__jetbrains_git__GetVcsChanges",
        "mcp__jetbrains_git__GetCommitMessage",
        "mcp__jetbrains_git__SetCommitMessage",
        "mcp__jetbrains_git__GetVcsStatus",
        // 文件读取
        "Read",
        // JetBrains MCP 工具 - 理解代码上下文
        "mcp__jetbrains__FileIndex",
        "mcp__jetbrains__CodeSearch",
        "mcp__jetbrains__DirectoryTree",
        "mcp__jetbrains__FileProblems"
    )
}
