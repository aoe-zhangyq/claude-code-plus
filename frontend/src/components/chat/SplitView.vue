<template>
  <div class="split-view" :class="splitClass">
    <!-- 渲染所有分屏面板 -->
    <div
      v-for="(panel, index) in panels"
      :key="panel.tabId"
      class="split-panel"
      :class="{ focused: panel.focus }"
      @click="handlePanelClick(index)"
    >
      <!-- 面板头部：Tab 信息 -->
      <div class="panel-header">
        <span class="panel-title">{{ getTabName(panel.tabId) }}</span>
        <div class="panel-status">
          <span
            v-if="isTabGenerating(panel.tabId)"
            class="status-dot generating"
            title="Generating"
          />
          <span
            v-else-if="isTabConnected(panel.tabId)"
            class="status-dot connected"
            title="Connected"
          />
          <span
            v-else
            class="status-dot disconnected"
            title="Disconnected"
          />
        </div>
      </div>

      <!-- 面板内容：使用 MessageList 和 ChatInput -->
      <div class="panel-content">
        <MessageList
          :display-items="getTabDisplayItems(panel.tabId)"
          :is-loading="getTabIsLoadingHistory(panel.tabId)"
          :has-more-history="getTabHasMoreHistory(panel.tabId)"
          :is-streaming="getTabIsStreaming(panel.tabId)"
          :streaming-start-time="getTabStreamingStartTime(panel.tabId)"
          :input-tokens="getTabStreamingInputTokens(panel.tabId)"
          :output-tokens="getTabStreamingOutputTokens(panel.tabId)"
          :content-version="getTabContentVersion(panel.tabId)"
          :connection-status="getTabConnectionStatus(panel.tabId)"
          class="panel-message-list"
          @load-more-history="() => handleLoadMoreHistory(panel.tabId)"
        />
      </div>

      <!-- 面板输入区 -->
      <div class="panel-input" @click.stop>
        <ChatInput
          :model-value="getTabInputText(panel.tabId)"
          :pending-tasks="getTabPendingTasks(panel.tabId)"
          :contexts="getTabContexts(panel.tabId)"
          :is-generating="getTabIsStreaming(panel.tabId)"
          :enabled="panel.focus"
          :show-toast="showToast"
          :actual-model-id="getTabModelId(panel.tabId)"
          :selected-permission="getTabPermissionMode(panel.tabId)"
          :skip-permissions="getTabSkipPermissions(panel.tabId)"
          :selected-model="getTabSelectedModel(panel.tabId)"
          :auto-cleanup-contexts="uiState.autoCleanupContexts"
          :message-history="[]"
          :session-token-usage="getTabTokenUsage(panel.tabId)"
          :streaming-start-time="getTabStreamingStartTime(panel.tabId)"
          :streaming-input-tokens="getTabStreamingInputTokens(panel.tabId)"
          :streaming-output-tokens="getTabStreamingOutputTokens(panel.tabId)"
          :show-context-controls="true"
          :show-model-selector="false"
          :show-permission-controls="false"
          :show-send-button="true"
          class="panel-chat-input"
          @update:model-value="(v: string) => handleInputChange(panel.tabId, v)"
          @send="(c, o) => handleSendMessage(panel.tabId, c, o)"
          @stop="() => handleStopGeneration(panel.tabId)"
        />
      </div>
    </div>

    <!-- 交换按钮（仅在双面板时显示） -->
    <button
      v-if="panels.length === 2"
      class="swap-btn"
      :class="swapButtonClass"
      :title="swapButtonTitle"
      @click="handleSwap"
    >
      <svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5">
        <path d="M1 6h10M8 3l3 3-3 3"/>
      </svg>
    </button>

    <!-- 添加第二个面板的提示 -->
    <div
      v-if="panels.length === 1 && availableTabs.length > 0"
      class="add-panel-hint"
      @click="handleShowTabSelector"
    >
      <span class="hint-icon">+</span>
      <span class="hint-text">{{ t('split.addPanel') || 'Add panel' }}</span>
    </div>
  </div>

  <!-- Tab 选择器弹窗 -->
  <div
    v-if="showTabSelector"
    class="tab-selector-overlay"
    @click="showTabSelector = false"
  >
    <div class="tab-selector" @click.stop>
      <div class="tab-selector-header">
        <span>{{ t('split.selectTab') || 'Select a session' }}</span>
        <button class="close-btn" @click="showTabSelector = false">×</button>
      </div>
      <div class="tab-selector-list">
        <div
          v-for="tab in availableTabs"
          :key="tab.tabId"
          class="tab-selector-item"
          @click="handleSelectTab(tab.tabId)"
        >
          <span class="tab-item-name">{{ tab.name.value }}</span>
          <span
            v-if="tab.isGenerating.value"
            class="tab-item-status generating"
          >●</span>
          <span
            v-else-if="tab.connectionState.status === 'CONNECTED'"
            class="tab-item-status connected"
          >●</span>
          <span
            v-else
            class="tab-item-status disconnected"
          >○</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useSessionStore } from '@/stores/sessionStore'
import type { SplitPanel } from '@/stores/sessionStore'
import { ConnectionStatus } from '@/types/display'
import { useI18n } from '@/composables/useI18n'
import MessageList from './MessageList.vue'
import ChatInput, { type ActiveFileInfo } from './ChatInput.vue'

const props = defineProps<{
  mode: 'horizontal' | 'vertical'
  panels: SplitPanel[]
  showToast: (message: string, duration?: number) => void
}>()

const emit = defineEmits<{
  (e: 'focus-panel', index: number): void
  (e: 'swap-panels'): void
  (e: 'add-panel', tabId: string): void
}>()

const { t } = useI18n()
const sessionStore = useSessionStore()

// UI 状态
const showTabSelector = ref(false)

// 所有可用的 Tab（排除已在分屏中的）
const availableTabs = computed(() => {
  const panelTabIds = new Set(props.panels.map(p => p.tabId))
  return sessionStore.tabs.filter(tab => !panelTabIds.has(tab.tabId))
})

// 分屏容器样式类
const splitClass = computed(() => ({
  'split-horizontal': props.mode === 'horizontal',
  'split-vertical': props.mode === 'vertical',
  [`panel-count-${props.panels.length}`]: true
}))

// 交换按钮的样式类
const swapButtonClass = computed(() => {
  return props.mode === 'horizontal' ? 'swap-horizontal' : 'swap-vertical'
})

// 交换按钮的标题
const swapButtonTitle = computed(() => {
  return props.mode === 'horizontal'
    ? (t('split.swapVertical') || 'Swap panels')
    : (t('split.swapHorizontal') || 'Swap panels')
})

// 暂时用于获取 uiState 的本地状态
const uiState = ref({
  autoCleanupContexts: false
})

// ========== Tab 数据获取方法 ==========

function getTab(tabId: string) {
  return sessionStore.tabs.find(t => t.tabId === tabId)
}

function getTabName(tabId: string): string {
  const tab = getTab(tabId)
  return tab?.name.value || t('session.unnamed') || 'Unnamed'
}

function getTabDisplayItems(tabId: string) {
  const tab = getTab(tabId)
  return tab?.displayItems ?? []
}

function getTabIsLoadingHistory(tabId: string): boolean {
  const tab = getTab(tabId)
  return tab?.historyState.loading ?? false
}

function getTabHasMoreHistory(tabId: string): boolean {
  const tab = getTab(tabId)
  return tab?.historyState.hasMore ?? false
}

function getTabIsStreaming(tabId: string): boolean {
  const tab = getTab(tabId)
  return tab?.isGenerating.value ?? false
}

function getTabIsConnected(tabId: string): boolean {
  const tab = getTab(tabId)
  return tab?.connectionState.status === ConnectionStatus.CONNECTED
}

function isTabConnected(tabId: string): boolean {
  return getTabIsConnected(tabId)
}

function isTabGenerating(tabId: string): boolean {
  return getTabIsStreaming(tabId)
}

function getTabConnectionStatus(tabId: string): string {
  const tab = getTab(tabId)
  const status = tab?.connectionState.status
  if (status === 'CONNECTED') return 'CONNECTED'
  if (status === 'CONNECTING') return 'CONNECTING'
  return 'DISCONNECTED'
}

function getTabStreamingStartTime(tabId: string): number {
  const tab = getTab(tabId)
  return tab?.stats.getCurrentTracker()?.requestStartTime ?? Date.now()
}

function getTabStreamingInputTokens(tabId: string): number {
  const tab = getTab(tabId)
  return tab?.stats.getCurrentTracker()?.inputTokens ?? 0
}

function getTabStreamingOutputTokens(tabId: string): number {
  const tab = getTab(tabId)
  return tab?.stats.getCurrentTracker()?.outputTokens ?? 0
}

function getTabContentVersion(tabId: string): number {
  const tab = getTab(tabId)
  return tab?.stats.streamingContentVersion.value ?? 0
}

function getTabInputText(tabId: string): string {
  const tab = getTab(tabId)
  return tab?.uiState.inputText ?? ''
}

function getTabContexts(tabId: string) {
  const tab = getTab(tabId)
  return tab?.uiState.contexts ?? []
}

function getTabPendingTasks(tabId: string) {
  return []
}

function getTabModelId(tabId: string): string | undefined {
  const tab = getTab(tabId)
  return tab?.modelId.value
}

function getTabPermissionMode(tabId: string): 'default' | 'all' | 'none' {
  const tab = getTab(tabId)
  return tab?.permissionMode.value ?? 'default'
}

function getTabSkipPermissions(tabId: string): boolean {
  const tab = getTab(tabId)
  return tab?.skipPermissions.value ?? false
}

function getTabSelectedModel(tabId: string): any {
  // 简化处理
  return 'DEFAULT'
}

function getTabTokenUsage(tabId: string): any {
  // 简化处理
  return null
}

// ========== 事件处理 ==========

function handlePanelClick(index: number) {
  emit('focus-panel', index)
}

function handleSwap() {
  emit('swap-panels')
}

function handleShowTabSelector() {
  showTabSelector.value = true
}

function handleSelectTab(tabId: string) {
  emit('add-panel', tabId)
  showTabSelector.value = false
}

function handleInputChange(tabId: string, value: string) {
  const tab = getTab(tabId)
  if (tab) {
    tab.uiState.inputText = value
  }
}

function handleSendMessage(tabId: string, contents: any, options: any) {
  const tab = getTab(tabId)
  if (tab) {
    // 保存 contexts
    const currentContexts = tab.uiState.contexts ?? []
    tab.uiState.contexts = []

    tab.sendMessage({
      contexts: currentContexts,
      contents,
      ideContext: options?.ideContext
    })
  }
}

function handleStopGeneration(tabId: string) {
  const tab = getTab(tabId)
  if (tab) {
    tab.clearQueue()
    tab.interrupt()
  }
}

function handleLoadMoreHistory(tabId: string) {
  const tab = getTab(tabId)
  if (tab) {
    tab.loadMoreHistory()
  }
}
</script>

<style scoped>
.split-view {
  display: flex;
  width: 100%;
  height: 100%;
  position: relative;
  gap: 4px;
  padding: 4px;
  box-sizing: border-box;
}

.split-view.split-horizontal {
  flex-direction: column;
}

.split-view.split-vertical {
  flex-direction: row;
}

.split-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--theme-border, #e1e4e8);
  border-radius: 8px;
  background: var(--theme-card-background, #ffffff);
  overflow: hidden;
  transition: border-color 0.2s, box-shadow 0.2s;
  min-width: 0;
  min-height: 0;
  position: relative;
}

.split-panel.focused {
  border-color: var(--theme-accent, #0366d6);
  box-shadow: 0 0 0 2px rgba(3, 102, 214, 0.1);
}

.split-panel:hover {
  border-color: var(--theme-accent, #0366d6);
}

/* 单个面板时占满整个空间 */
.panel-count-1 .split-panel {
  flex: 1;
}

/* 两个面板时平分空间 */
.panel-count-2 .split-panel {
  flex: 1;
}

/* 面板头部 */
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  border-bottom: 1px solid var(--theme-border, #e1e4e8);
  background: var(--theme-panel-background, #f6f8fa);
  flex-shrink: 0;
}

.panel-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--theme-foreground, #24292e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.panel-status {
  flex-shrink: 0;
  margin-left: 8px;
}

.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.status-dot.connected {
  background: var(--theme-success, #28a745);
}

.status-dot.disconnected {
  background: var(--theme-error, #dc3545);
}

.status-dot.generating {
  background: var(--theme-success, #28a745);
  animation: pulse 1s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

/* 面板内容区 */
.panel-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.panel-message-list {
  height: 100%;
  border: none;
  border-radius: 0;
  background: transparent;
}

/* 面板输入区 */
.panel-input {
  flex-shrink: 0;
  border-top: 1px solid var(--theme-border, #e1e4e8);
  padding: 8px;
  background: var(--theme-background, #ffffff);
}

.panel-chat-input {
  background: transparent;
  padding: 0;
}

/* 交换按钮 */
.swap-btn {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 1px solid var(--theme-border, #d0d7de);
  background: var(--theme-background, #ffffff);
  color: var(--theme-secondary-foreground, #6a737d);
  cursor: pointer;
  z-index: 10;
  transition: all 0.15s ease;
}

.swap-btn:hover {
  background: var(--theme-accent, #0366d6);
  color: #ffffff;
  border-color: var(--theme-accent, #0366d6);
  transform: scale(1.1);
}

.swap-horizontal {
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
}

.swap-horizontal:hover {
  transform: translateY(-50%) scale(1.1);
}

.split-view.swap-horizontal .swap-btn {
  top: 50%;
  right: 8px;
}

.swap-vertical {
  left: 50%;
  bottom: 8px;
  transform: translateX(-50%);
}

.swap-vertical:hover {
  transform: translateX(-50%) scale(1.1);
}

.split-view.swap-vertical .swap-btn {
  left: 50%;
  bottom: 8px;
}

/* 添加面板提示 */
.add-panel-hint {
  position: absolute;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 16px;
  border-radius: 8px;
  border: 2px dashed var(--theme-border, #d0d7de);
  background: var(--theme-panel-background, #f6f8fa);
  color: var(--theme-secondary-foreground, #6a737d);
  cursor: pointer;
  transition: all 0.2s ease;
  min-width: 100px;
  min-height: 60px;
}

.split-horizontal .add-panel-hint {
  bottom: 8px;
  right: 8px;
}

.split-vertical .add-panel-hint {
  top: 50%;
  right: 8px;
  transform: translateY(-50%);
}

.add-panel-hint:hover {
  border-color: var(--theme-accent, #0366d6);
  color: var(--theme-accent, #0366d6);
  background: rgba(3, 102, 214, 0.05);
}

.hint-icon {
  font-size: 20px;
  line-height: 1;
}

.hint-text {
  font-size: 11px;
  margin-top: 4px;
}

/* Tab 选择器弹窗 */
.tab-selector-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.tab-selector {
  background: var(--theme-card-background, #ffffff);
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  min-width: 280px;
  max-width: 400px;
  max-height: 400px;
  overflow: hidden;
}

.tab-selector-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--theme-border, #e1e4e8);
  font-weight: 600;
  font-size: 14px;
}

.close-btn {
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  color: var(--theme-secondary-foreground, #6a737d);
  font-size: 18px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}

.close-btn:hover {
  background: var(--theme-hover-background, rgba(0, 0, 0, 0.06));
}

.tab-selector-list {
  max-height: 300px;
  overflow-y: auto;
  padding: 4px;
}

.tab-selector-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.tab-selector-item:hover {
  background: var(--theme-hover-background, rgba(0, 0, 0, 0.04));
}

.tab-item-name {
  font-size: 13px;
  color: var(--theme-foreground, #24292e);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.tab-item-status {
  flex-shrink: 0;
  margin-left: 8px;
  font-size: 10px;
}

.tab-item-status.connected {
  color: var(--theme-success, #28a745);
}

.tab-item-status.disconnected {
  color: var(--theme-error, #dc3545);
}

.tab-item-status.generating {
  color: var(--theme-success, #28a745);
  animation: pulse 1s infinite;
}
</style>
