<template>
  <div
    v-if="pendingPermission"
    ref="containerRef"
    class="permission-request"
    :class="{ 'from-other-tab': permissionTabInfo && !permissionTabInfo.isCurrentTab }"
    :style="{ left: position.x + 'px', top: position.y + 'px' }"
    tabindex="0"
    @keydown.esc="handleDeny"
    @click="switchToPermissionTab"
  >
    <div class="permission-card">
      <!-- 拖动手柄 -->
      <div
        class="drag-handle"
        :class="{ dragging: isDragging }"
        @mousedown="startDrag"
        @click.stop
      >
        <span class="drag-dots">⋮⋮</span>
      </div>

      <!-- 工具信息头部 -->
      <div class="permission-header">
        <span class="tool-icon">{{ getToolIcon(pendingPermission.toolName) }}</span>
        <span class="tool-name">{{ getToolDisplayName(pendingPermission.toolName) || pendingPermission.toolName || 'Unknown Tool' }}</span>
        <span class="permission-label">{{ t('permission.needsAuth') }}</span>
        <!-- 来自其他 tab 的提示 -->
        <span
          v-if="permissionTabInfo && !permissionTabInfo.isCurrentTab"
          class="tab-indicator"
          title="点击切换到此会话"
        >
          {{ permissionTabInfo.tabName }}
        </span>
      </div>

      <!-- 工具参数预览 -->
      <div class="permission-content">
        <template v-if="pendingPermission.toolName === 'Bash'">
          <pre class="command-preview">{{ pendingPermission.input.command }}</pre>
        </template>
        <template v-else-if="pendingPermission.toolName === 'Write'">
          <div class="file-info">
            <span class="file-icon">📄</span>
            <span class="file-path">{{ pendingPermission.input.file_path }}</span>
          </div>
          <div v-if="pendingPermission.input.content" class="content-preview">
            <pre class="content-text">{{ truncateContent(pendingPermission.input.content) }}</pre>
          </div>
        </template>
        <template v-else-if="pendingPermission.toolName === 'Edit'">
          <div class="file-info">
            <span class="file-icon">✏️</span>
            <span class="file-path">{{ pendingPermission.input.file_path }}</span>
            <button v-if="isIdeEnvironment()" class="btn-preview" @click="showEditPreview">
              {{ t('permission.viewInIdea') }}
            </button>
          </div>
          <div v-if="pendingPermission.input.old_string" class="edit-preview">
            <div class="edit-section">
              <span class="edit-label">{{ t('permission.replace') }}:</span>
              <pre class="edit-text old">{{ truncateContent(pendingPermission.input.old_string) }}</pre>
            </div>
            <div class="edit-section">
              <span class="edit-label">{{ t('permission.with') }}:</span>
              <pre class="edit-text new">{{ truncateContent(pendingPermission.input.new_string || '') }}</pre>
            </div>
          </div>
        </template>
        <template v-else-if="pendingPermission.toolName === 'MultiEdit'">
          <div class="file-info">
            <span class="file-icon">📋</span>
            <span class="file-path">{{ pendingPermission.input.file_path }}</span>
            <button v-if="isIdeEnvironment()" class="btn-preview" @click="showMultiEditPreview">
              {{ t('permission.viewInIdea') }}
            </button>
          </div>
          <div class="multi-edit-info">
            <span class="edit-count">{{ pendingPermission.input.edits?.length || 0 }} {{ t('permission.edits') }}</span>
          </div>
        </template>
        <template v-else-if="isExitPlanMode">
          <div class="plan-info">
            <span class="plan-icon">📋</span>
            <span class="plan-label">{{ t('permission.planReady') }}</span>
            <button class="btn-preview" @click="togglePlanExpand">
              {{ planExpanded ? t('permission.collapse') : t('permission.expand') }}
            </button>
            <button v-if="isIdeEnvironment() && planContent" class="btn-preview" @click="openPlanInIdea">
              {{ t('permission.viewInIdea') }}
            </button>
          </div>
          <!-- 展开显示 plan 内容 -->
          <div v-if="planExpanded && planContent" class="plan-expanded-content">
            <MarkdownRenderer :content="planContent" />
          </div>
          <div v-else-if="planExpanded && !planContent" class="plan-error">
            {{ t('permission.noPlanContent') }}
          </div>
        </template>
        <template v-else>
          <pre v-if="hasInputParams(pendingPermission.input)" class="params-preview">{{ formatParams(pendingPermission.input) }}</pre>
          <div v-else class="no-params-hint">{{ t('permission.noParams') }}</div>
        </template>
      </div>

      <!-- 操作选项 -->
      <div class="permission-options" @click.stop>
        <!-- 允许（仅本次） -->
        <button class="btn-option btn-allow" @click="isExitPlanMode ? handleApproveWithMode('default') : handleApprove()">
          {{ t('permission.allow') }}
        </button>

        <!-- ExitPlanMode 专用选项 -->
        <template v-if="isExitPlanMode">
          <button class="btn-option btn-allow-rule" @click="handleApproveWithMode('acceptEdits')">
            Allow, with Accept Edits
          </button>
          <button class="btn-option btn-allow-rule" @click="handleApproveWithMode('bypassPermissions')">
            Allow, with Bypass
          </button>
        </template>

        <!-- 动态渲染 permissionSuggestions -->
        <button
          v-for="(suggestion, index) in pendingPermission.permissionSuggestions"
          :key="index"
          class="btn-option btn-allow-rule"
          @click="handleAllowWithUpdate(suggestion)"
        >
          {{ t('permission.allow') }}，{{ formatSuggestion(suggestion) }}
        </button>

        <!-- 不允许（带输入框） -->
        <div class="deny-inline">
          <input
            v-model="denyReason"
            class="deny-input"
            :placeholder="t('permission.denyReasonPlaceholder')"
            @keydown.enter="handleDeny"
          />
          <button class="btn-option btn-deny" @click="handleDeny">
            {{ t('permission.deny') }}
          </button>
        </div>
      </div>

      <!-- 快捷键提示 -->
      <div class="shortcut-hint">{{ t('permission.escToDeny') }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useSessionStore } from '@/stores/sessionStore'
import { useI18n } from '@/composables/useI18n'
import { jetbrainsBridge, isIdeEnvironment } from '@/services/jetbrainsApi'
import MarkdownRenderer from '@/components/markdown/MarkdownRenderer.vue'
import type { PermissionUpdate, PendingPermissionRequest } from '@/types/permission'

const { t } = useI18n()
const sessionStore = useSessionStore()

const containerRef = ref<HTMLElement | null>(null)
const denyReason = ref('')

// Plan 展开状态
const planExpanded = ref(false)

// 拖动状态
const isDragging = ref(false)
const dragOffset = ref({ x: 0, y: 0 })
const position = ref({ x: 16, y: 80 }) // 默认位置：右上角区域
const INITIAL_POSITION_KEY = 'permission-dialog-position'

// 从 localStorage 读取上次位置
function loadSavedPosition() {
  try {
    const saved = localStorage.getItem(INITIAL_POSITION_KEY)
    if (saved) {
      const pos = JSON.parse(saved)
      // 确保位置在可视区域内
      const maxX = window.innerWidth - 300
      const maxY = window.innerHeight - 200
      position.value = {
        x: Math.max(16, Math.min(pos.x, maxX)),
        y: Math.max(80, Math.min(pos.y, maxY))
      }
    }
  } catch {
    // 使用默认位置
  }
}

// 保存位置到 localStorage
function savePosition() {
  try {
    localStorage.setItem(INITIAL_POSITION_KEY, JSON.stringify(position.value))
  } catch {
    // 忽略保存失败
  }
}

// 开始拖动
function startDrag(event: MouseEvent) {
  event.preventDefault()
  isDragging.value = true
  dragOffset.value = {
    x: event.clientX - position.value.x,
    y: event.clientY - position.value.y
  }
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', stopDrag)
}

// 拖动中
function onDrag(event: MouseEvent) {
  if (!isDragging.value) return

  const newX = event.clientX - dragOffset.value.x
  const newY = event.clientY - dragOffset.value.y

  // 限制在可视区域内
  const maxX = window.innerWidth - (containerRef.value?.offsetWidth || 300)
  const maxY = window.innerHeight - (containerRef.value?.offsetHeight || 200)

  position.value = {
    x: Math.max(0, Math.min(newX, maxX)),
    y: Math.max(0, Math.min(newY, maxY))
  }
}

// 停止拖动
function stopDrag() {
  if (isDragging.value) {
    isDragging.value = false
    savePosition()
    document.removeEventListener('mousemove', onDrag)
    document.removeEventListener('mouseup', stopDrag)
  }
}

// 组件挂载时加载位置
onMounted(() => {
  loadSavedPosition()
  // 窗口大小改变时确保窗口在可视区域内
  window.addEventListener('resize', () => {
    const rect = containerRef.value?.getBoundingClientRect()
    if (rect) {
      const maxX = window.innerWidth - rect.width
      const maxY = window.innerHeight - rect.height
      if (position.value.x > maxX || position.value.y > maxY) {
        position.value = {
          x: Math.max(16, Math.min(position.value.x, maxX)),
          y: Math.max(80, Math.min(position.value.y, maxY))
        }
      }
    }
  })
})

// 组件卸载时清理事件监听
onBeforeUnmount(() => {
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', stopDrag)
})

// Plan 内容（计算属性）
const planContent = computed(() => {
  if (!pendingPermission.value) return ''
  return (pendingPermission.value.input.plan as string) || ''
})

// 切换 plan 展开/收起
function togglePlanExpand() {
  planExpanded.value = !planExpanded.value
}

// 在 IDEA 中打开 plan
async function openPlanInIdea() {
  if (!planContent.value) return

  const success = await jetbrainsBridge.showMarkdown({
    content: planContent.value,
    title: t('permission.planPreviewTitle')
  })

  if (!success) {
    console.warn('[ToolPermission] Failed to open plan in IDEA')
  }
}

// 获取所有 tab 的待处理授权请求（按创建时间排序，最早的在前面）
const allPendingPermissions = computed(() => {
  const allPermissions: Array<{
    permission: PendingPermissionRequest
    tabId: string
    tabName: string
    isCurrentTab: boolean
  }> = []

  for (const tab of sessionStore.tabs) {
    const permissions = tab.permissions.pendingPermissionList.value
    for (const p of permissions) {
      allPermissions.push({
        permission: p,
        tabId: tab.tabId,
        tabName: tab.name.value,
        isCurrentTab: tab.tabId === sessionStore.currentTabId
      })
    }
  }

  // 按创建时间排序，最早的在前面
  allPermissions.sort((a, b) => a.permission.createdAt - b.permission.createdAt)
  return allPermissions
})

// 获取第一个待处理的权限请求（来自最早创建的请求）
const pendingPermission = computed(() => {
  return allPendingPermissions.value[0]?.permission || null
})

// 获取第一个请求所属的 tab 信息
const permissionTabInfo = computed(() => {
  const first = allPendingPermissions.value[0]
  return first ? {
    tabId: first.tabId,
    tabName: first.tabName,
    isCurrentTab: first.isCurrentTab
  } : null
})

// 检查是否是 ExitPlanMode 权限请求
const isExitPlanMode = computed(() => {
  return pendingPermission.value?.toolName === 'ExitPlanMode'
})

// 点击弹窗切换到对应的 tab
async function switchToPermissionTab() {
  if (!permissionTabInfo.value || permissionTabInfo.value.isCurrentTab) return

  try {
    await sessionStore.switchTab(permissionTabInfo.value.tabId)
  } catch (error) {
    console.warn('[ToolPermission] Failed to switch tab:', error)
  }
}

// 当有新的权限请求时，自动聚焦并清空拒绝原因
watch(pendingPermission, (newVal) => {
  if (newVal) {
    denyReason.value = ''
    nextTick(() => {
      containerRef.value?.focus()
    })
  }
})

function handleApprove() {
  if (pendingPermission.value) {
    sessionStore.respondPermission(pendingPermission.value.id, { approved: true })
  }
}

// ExitPlanMode 专用：允许并切换到指定模式
async function handleApproveWithMode(mode: 'default' | 'acceptEdits' | 'bypassPermissions') {
  if (pendingPermission.value) {
    // 先返回权限结果为 true
    sessionStore.respondPermission(pendingPermission.value.id, { approved: true })

    // 然后调用 API 设置权限模式
    const tab = sessionStore.currentTab
    if (tab) {
      await tab.setPermissionMode(mode)
      // 如果是 bypassPermissions 模式，同时更新 UI 上的 Bypass 开关
      if (mode === 'bypassPermissions') {
        tab.skipPermissions.value = true
      }
    }
  }
}

function handleAllowWithUpdate(update: PermissionUpdate) {
  if (pendingPermission.value) {
    // 如果是 setMode 类型，只更新本地 UI 状态
    // 不需要调用 setPermissionMode RPC，SDK 收到响应后会自行切换
    if (update.type === 'setMode' && update.mode) {
      sessionStore.setLocalPermissionMode(update.mode)
      // 如果是 bypassPermissions 模式，同时更新 UI 上的 Bypass 开关
      if (update.mode === 'bypassPermissions') {
        const tab = sessionStore.currentTab
        if (tab) {
          tab.skipPermissions.value = true
        }
      }
    }

    sessionStore.respondPermission(pendingPermission.value.id, {
      approved: true,
      permissionUpdates: [update]
    })
  }
}

function handleDeny() {
  if (pendingPermission.value) {
    sessionStore.respondPermission(pendingPermission.value.id, {
      approved: false,
      denyReason: denyReason.value || undefined
    })
  }
}

// ========== IDE 预览方法 ==========

async function showEditPreview() {
  if (!pendingPermission.value) return
  const input = pendingPermission.value.input

  const success = await jetbrainsBridge.showEditPreviewDiff({
    filePath: input.file_path || '',
    edits: [{
      oldString: input.old_string || '',
      newString: input.new_string || '',
      replaceAll: input.replace_all || false
    }],
    title: `${t('permission.editPreviewTitle')}: ${input.file_path}`
  })

  if (!success) {
    console.warn('[ToolPermission] Failed to show edit preview')
  }
}

async function showMultiEditPreview() {
  if (!pendingPermission.value) return
  const input = pendingPermission.value.input

  if (!input.file_path || !input.edits) return

  const success = await jetbrainsBridge.showEditPreviewDiff({
    filePath: input.file_path,
    edits: input.edits.map((e: any) => ({
      oldString: e.old_string || '',
      newString: e.new_string || '',
      replaceAll: e.replace_all || false
    })),
    title: `${t('permission.multiEditPreviewTitle')}: ${input.file_path}`
  })

  if (!success) {
    console.warn('[ToolPermission] Failed to show multi-edit preview')
  }
}

function formatSuggestion(suggestion: PermissionUpdate): string {
  const dest = t(`permission.destination.${suggestion.destination || 'session'}`)

  switch (suggestion.type) {
    case 'addRules':
      if (suggestion.rules?.length) {
        const rule = suggestion.rules[0]
        if (rule.ruleContent) {
          return t('permission.suggestion.rememberWithRuleTo', {
            tool: rule.toolName,
            rule: rule.ruleContent,
            dest
          })
        }
        return t('permission.suggestion.rememberTo', { tool: rule.toolName, dest })
      }
      break

    case 'replaceRules':
      return t('permission.suggestion.replaceTo', { dest })

    case 'removeRules':
      if (suggestion.rules?.length) {
        return t('permission.suggestion.removeFrom', { tool: suggestion.rules[0].toolName, dest })
      }
      return t('permission.suggestion.removeRulesFrom', { dest })

    case 'setMode': {
      const mode = t(`permission.mode.${suggestion.mode || 'default'}`)
      return t('permission.suggestion.switchTo', { mode })
    }

    case 'addDirectories':
      if (suggestion.directories?.length) {
        return t('permission.suggestion.addDirTo', { dir: suggestion.directories[0], dest })
      }
      break

    case 'removeDirectories':
      if (suggestion.directories?.length) {
        return t('permission.suggestion.removeDirFrom', { dir: suggestion.directories[0], dest })
      }
      break
  }

  return t('permission.suggestion.applyTo', { dest })
}

function getToolDisplayName(name: string): string {
  const names: Record<string, string> = {
    'Bash': 'Terminal',
    'Write': 'Write File',
    'Edit': 'Edit File',
    'Read': 'Read File',
    'MultiEdit': 'Multi Edit',
    'Glob': 'Find Files',
    'Grep': 'Search Content'
  }
  return names[name] || name
}

function getToolIcon(name: string): string {
  const icons: Record<string, string> = {
    'Bash': '🖥',
    'Write': '📝',
    'Edit': '✏️',
    'Read': '📖',
    'MultiEdit': '📋',
    'Glob': '🔍',
    'Grep': '🔎'
  }
  return icons[name] || '🔧'
}

function truncateContent(content: string, maxLength: number = 200): string {
  if (!content) return ''
  if (content.length <= maxLength) return content
  return content.substring(0, maxLength) + '...'
}

function formatParams(params: Record<string, unknown>): string {
  try {
    return JSON.stringify(params, null, 2)
  } catch {
    return String(params)
  }
}

function hasInputParams(input: Record<string, unknown>): boolean {
  if (!input) return false
  return Object.keys(input).length > 0
}
</script>

<style scoped>
.permission-request {
  position: fixed;
  outline: none;
  max-height: 120px; /* 与输入框高度相近，避免遮挡聊天记录 */
  max-width: 400px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transition: max-height 0.2s ease;
  z-index: 1000;
}

/* 当内容过多时，允许扩展到更大高度（用户交互后可扩展） */
.permission-request.expanded {
  max-height: 40vh;
}

.permission-request:focus .permission-card {
  box-shadow: 0 0 0 2px var(--theme-accent, #0366d6), 0 8px 32px rgba(0, 0, 0, 0.15);
}

/* 拖动手柄 */
.drag-handle {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 20px;
  cursor: move;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
  border-radius: 12px 12px 0 0;
}

.drag-handle:hover {
  background: var(--theme-hover-background, rgba(0, 0, 0, 0.05));
}

.drag-handle.dragging {
  cursor: grabbing;
  background: var(--theme-hover-background, rgba(0, 0, 0, 0.08));
}

.drag-dots {
  font-size: 12px;
  color: var(--theme-muted, #6a737d);
  opacity: 0.5;
  letter-spacing: 2px;
  user-select: none;
}

.permission-card {
  background: var(--theme-background, #ffffff);
  border: 1px solid var(--theme-border, #e1e4e8);
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: 100%;
}

.permission-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 24px 16px 12px; /* 顶部增加空间给拖动手柄 */
  background: var(--theme-panel-background, #f6f8fa);
  color: var(--theme-foreground, #24292e);
}

.tool-icon {
  font-size: 18px;
}

.tool-name {
  font-size: 14px;
  font-weight: 600;
}

.permission-label {
  font-size: 12px;
  background: var(--theme-accent-subtle, #e8f1fb);
  color: var(--theme-accent, #0366d6);
  padding: 2px 8px;
  border-radius: 999px;
  margin-left: auto;
  border: 1px solid var(--theme-accent, #0366d6);
}

/* 来自其他 tab 的指示器 */
.tab-indicator {
  font-size: 11px;
  background: var(--theme-warning, #ffc107);
  color: #000;
  padding: 2px 8px;
  border-radius: 999px;
  margin-left: 6px;
  border: 1px solid var(--theme-warning, #ffc107);
  font-weight: 600;
  animation: pulse-tab 2s ease-in-out infinite;
}

@keyframes pulse-tab {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0.7;
  }
}

/* 来自其他 tab 的弹窗样式 */
.permission-request.from-other-tab {
  cursor: pointer;
}

.permission-request.from-other-tab .permission-card {
  border-color: var(--theme-warning, #ffc107);
  box-shadow: 0 0 0 2px rgba(255, 193, 7, 0.3), 0 8px 24px rgba(0, 0, 0, 0.12);
}

.permission-content {
  padding: 16px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  background: var(--theme-background, #fff);
}

.command-preview {
  background: #2d2d2d;
  color: #e6e6e6;
  padding: 12px;
  border-radius: 6px;
  font-family: var(--theme-editor-font-family);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--theme-panel-background, #f6f8fa);
  border-radius: 6px;
  margin-bottom: 8px;
}

.btn-preview {
  font-size: 12px;
  padding: 4px 8px;
  background: var(--theme-accent-subtle, #e8f1fb);
  color: var(--theme-accent, #0366d6);
  border: 1px solid var(--theme-accent, #0366d6);
  border-radius: 4px;
  cursor: pointer;
  margin-left: auto;
  transition: all 0.15s ease;
}

.btn-preview:hover {
  background: var(--theme-accent, #0366d6);
  color: #fff;
}

.multi-edit-info {
  padding: 8px 12px;
  background: var(--theme-panel-background, #f6f8fa);
  border-radius: 6px;
}

.edit-count {
  font-size: 13px;
  color: var(--theme-secondary-foreground, #586069);
}

.plan-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--theme-panel-background, #f6f8fa);
  border-radius: 6px;
}

.plan-icon {
  font-size: 16px;
}

.plan-label {
  font-size: 13px;
  color: var(--theme-foreground, #24292e);
}

.file-icon {
  font-size: 16px;
}

.file-path {
  font-family: var(--theme-editor-font-family);
  font-size: 13px;
  color: var(--theme-foreground, #24292e);
  word-break: break-all;
}

.content-preview,
.edit-preview {
  margin-top: 8px;
}

.content-text {
  background: var(--theme-code-background, #f6f8fa);
  color: var(--theme-foreground, #24292e);
  padding: 8px;
  border-radius: 4px;
  font-family: var(--theme-editor-font-family);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 80px;
  overflow-y: auto;
}

.edit-section {
  margin-bottom: 8px;
}

.edit-label {
  font-size: 12px;
  color: var(--theme-secondary-foreground, #586069);
  display: block;
  margin-bottom: 4px;
}

.edit-text {
  background: var(--theme-code-background, #f6f8fa);
  color: var(--theme-foreground, #24292e);
  padding: 8px;
  border-radius: 4px;
  font-family: var(--theme-editor-font-family);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 50px;
  overflow-y: auto;
}

.edit-text.old {
  background: var(--theme-diff-removed-bg, rgba(248, 81, 73, 0.15));
  border-left: 3px solid var(--theme-diff-removed-border, #f85149);
  color: var(--theme-diff-removed-text, var(--theme-foreground, #24292e));
}

.edit-text.new {
  background: var(--theme-diff-added-bg, rgba(63, 185, 80, 0.15));
  border-left: 3px solid var(--theme-diff-added-border, #3fb950);
  color: var(--theme-diff-added-text, var(--theme-foreground, #24292e));
}

.params-preview {
  background: var(--theme-code-background, #f6f8fa);
  color: var(--theme-foreground, #24292e);
  padding: 12px;
  border-radius: 6px;
  font-family: var(--theme-editor-font-family);
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 100px;
  overflow-y: auto;
}

.no-params-hint {
  color: var(--theme-secondary-foreground, #586069);
  font-size: 13px;
  font-style: italic;
  padding: 8px 0;
}

.permission-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--theme-border, #e1e4e8);
  background: var(--theme-background, #fff);
}

.btn-option {
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  text-align: left;
  border: 1px solid var(--theme-border, #e1e4e8);
  background: var(--theme-panel-background, #f6f8fa);
  color: var(--theme-foreground, #24292e);
}

.btn-option:hover {
  border-color: var(--theme-accent, #0366d6);
  color: var(--theme-accent, #0366d6);
  background: var(--theme-accent-subtle, #e8f1fb);
}

.btn-allow {
  background: var(--theme-accent-subtle, #e8f1fb);
  border-color: var(--theme-accent, #0366d6);
  color: var(--theme-accent, #0366d6);
}

.btn-allow:hover {
  background: var(--theme-accent, #0366d6);
  color: #fff;
}

.btn-allow-rule {
  background: var(--theme-panel-background, #f6f8fa);
  border-color: var(--theme-accent, #0366d6);
  color: var(--theme-accent, #0366d6);
}

.btn-allow-rule:hover {
  background: var(--theme-accent, #0366d6);
  color: #fff;
}

.deny-inline {
  display: flex;
  align-items: center;
  gap: 8px;
}

.deny-input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid var(--theme-border, #e1e4e8);
  border-radius: 6px;
  font-size: 13px;
  background: var(--theme-background, #fff);
  color: var(--theme-foreground, #24292e);
}

.deny-input:focus {
  outline: none;
  border-color: var(--theme-error, #dc3545);
}

.btn-deny {
  background: var(--theme-background, #fff);
  border: 1px solid var(--theme-error, #dc3545);
  color: var(--theme-error, #dc3545);
  flex-shrink: 0;
}

.btn-deny:hover {
  background: var(--theme-error, #dc3545);
  color: white;
}

.shortcut-hint {
  font-size: 11px;
  color: var(--theme-muted, #6a737d);
  text-align: right;
  padding: 8px 16px;
  background: var(--theme-panel-background, #f6f8fa);
  border-top: 1px solid var(--theme-border, #e1e4e8);
}

/* Plan 展开内容样式 */
.plan-expanded-content {
  margin-top: 12px;
  padding: 12px;
  background: var(--theme-code-background, #f6f8fa);
  border: 1px solid var(--theme-border, #e1e4e8);
  border-radius: 6px;
  max-height: 300px;
  overflow-y: auto;
}

.plan-expanded-content :deep(pre) {
  margin: 0;
  padding: 8px;
  background: var(--theme-background, #fff);
  border-radius: 4px;
}

.plan-expanded-content :deep(code) {
  font-size: 12px;
}

.plan-error {
  margin-top: 12px;
  padding: 12px;
  background: var(--theme-error-subtle, rgba(220, 53, 69, 0.1));
  border: 1px solid var(--theme-error, #dc3545);
  border-radius: 6px;
  color: var(--theme-error, #dc3545);
  font-size: 13px;
}
</style>
