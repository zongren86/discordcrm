<template>
  <div class="chat-page">
    <!-- 左侧会话列表面板 -->
    <aside class="conv-panel">
      <div class="panel-header">
        <div class="panel-title-row">
          <h3>消息中心</h3>
          <el-tag size="small" effect="dark" class="count-tag">{{ filteredConversations.length }}</el-tag>
        </div>

        <el-input v-model="convSearch" size="default" placeholder="搜索用户名、昵称、备注、标签、消息"
          :prefix-icon="Search" class="search-input" clearable />

        <div class="filter-bar">
          <el-select v-model="selectedAccountId" size="small" placeholder="账号" clearable class="filter-select">
            <el-option :value="null" label="全部账号" />
            <el-option v-for="acc in accounts.accounts" :key="acc.id" :value="acc.id"
              :label="acc.name || acc.nickname || acc.discordName || ('账号#' + acc.id)" />
          </el-select>

          <el-select v-model="selectedStage" size="small" placeholder="漏斗" clearable class="filter-select">
            <el-option :value="null" label="全部阶段" />
            <el-option v-for="s in stageOptions" :key="s.value" :value="s.value" :label="s.label" />
          </el-select>

          <el-popover placement="bottom-start" :width="440" trigger="click" v-model:visible="datePopoverVisible" popper-class="date-popover-popper" :close-on-click-modal="false" :teleported="false">
            <template #reference>
              <el-button size="small" class="date-filter-trigger" :type="dateRange || dateQuick ? 'primary' : 'default'">
                <el-icon style="margin-right:3px;"><Calendar /></el-icon>日期
                <span v-if="dateQuick" class="date-filter-label">{{ dateQuickLabel }}</span>
                <span v-else-if="dateRange && dateRange.length === 2" class="date-filter-label">
                  {{ dateRange[0].slice(5) }} ~ {{ dateRange[1].slice(5) }}
                </span>
              </el-button>
            </template>
            <div class="date-popover">
              <div class="date-section-title">快捷选择</div>
              <div class="date-quick-row">
                <el-button v-for="opt in dateQuickOptions" :key="opt.value" size="small"
                  :type="dateQuick === opt.value ? 'primary' : 'default'"
                  :plain="dateQuick !== opt.value"
                  @click="setDateRange(opt.value)">{{ opt.label }}</el-button>
              </div>
              <div class="date-section-title">自定义范围</div>
              <el-date-picker
                v-model="tempDateRange"
                type="daterange"
                range-separator="-"
                start-placeholder="开始日期"
                end-placeholder="结束日期"
                value-format="YYYY-MM-DD"
                size="small"
                class="date-range-picker"
                :teleported="false"
              />
              <div class="date-popover-footer">
                <el-button v-if="dateRange || dateQuick" size="small" type="danger" link @click="clearDateFilter">清除筛选</el-button>
                <span v-else class="date-filter-hint">选择日期范围筛选会话</span>
                <div class="date-popover-actions">
                  <el-button size="small" @click="cancelDateRange">取消</el-button>
                  <el-button size="small" type="primary" :disabled="!tempDateRange || tempDateRange.length !== 2" @click="confirmDateRange">确定</el-button>
                </div>
              </div>
            </div>
          </el-popover>
        </div>
      </div>

      <div class="conv-list-wrap">
        <el-scrollbar class="conv-scroll">
          <!-- 动态无刷新技术：只在首次加载时显示loading，后续静默刷新 -->
          <div v-if="conversations.loadingConversations && !conversations.initialLoadDone" class="loading-tip">
            <el-icon class="is-loading"><Loading /></el-icon> 加载中...
          </div>
          <el-empty v-else-if="getFilteredSortedConversations().length === 0" description="暂无会话" :image-size="80" />
          <div v-else class="conv-list">
            <template v-if="getPinnedConversations().length > 0">
              <div class="conv-section-header">
                <el-icon><Top /></el-icon>
                <span>置顶会话</span>
                <span class="section-count">{{ getPinnedConversations().length }}</span>
              </div>
              <div v-for="c in getPinnedConversations()" :key="'pinned-' + c.id"
                :class="['conv-item', 'pinned-item', { active: c.id === conversations.currentConversationId }]"
                @click="selectConversation(c)">
                <div v-if="c.agentName" class="conv-agent-name" :title="c.agentName">
                  <span v-for="(line, i) in splitAgentName(c.agentName)" :key="i">{{ line }}</span>
                </div>
                <div class="conv-avatar-wrap">
                  <el-avatar :size="44" :src="getAvatar(c)" class="conv-avatar">
                    {{ initialOf(c) }}
                  </el-avatar>
                  <!-- 红点显示规则 -->
                  <!-- PROSPECT阶段 + 从未发过消息(无lastMessageAt) => 红点 -->
                  <span v-if="c.stage === 'PROSPECT' && !c.lastMessageAt" class="unread-dot"></span>
                  <!-- 有未读消息 => 数字 -->
                  <span v-else-if="c.unreadCount > 0" class="unread-badge">
                    {{ c.unreadCount > 99 ? '99+' : c.unreadCount }}
                  </span>
                  <el-avatar v-if="c.agentName" :size="18" class="agent-badge" :title="c.agentName">
                    {{ (c.agentName || '?').charAt(0).toUpperCase() }}
                  </el-avatar>
                  <!-- 账号指示点（与当前会话绑定的Discord账号颜色） -->
                  <span v-if="c.discordAccountId"
                        class="account-dot"
                        :style="{ background: accountColor(c.discordAccountId) }"
                        :title="c.discordAccountName ? `账号: ${c.discordAccountName}` : '账号'"></span>
                  <!-- 好友原生在线状态圆点：绿=在线，灰=离线 -->
                  <span class="conv-presence-dot"
                        :class="presenceClass(c)"
                        :title="presenceTitle(c)"></span>
                </div>
                <div class="conv-main">
                  <div class="conv-line-1">
                    <el-icon class="pin-icon" :size="12"><Top /></el-icon>
                    <span class="conv-name">{{ truncateText(c.remark || c.globalName || c.username || ('用户' + (c.friendDiscordUserId || c.discordUserId)), 8) }}</span>
                    <el-tag v-if="c.stage" :type="stageTagType(c.stage)" size="small" effect="light" class="stage-tag-mini">
                      {{ stageLabel(c.stage) }}
                    </el-tag>
                  </div>
                  <div class="conv-line-2 conv-line-grid">
                    <span class="conv-nickname" :title="'@' + (c.username || c.globalName || '')">
                      @{{ c.username || c.globalName || ('用户' + (c.friendDiscordUserId || c.discordUserId)) }}
                    </span>
                  </div>
                </div>
                <div class="conv-actions">
                  <el-tooltip content="取消置顶">
                    <el-button type="warning" size="small" circle @click.stop="togglePin(c)">
                      <el-icon><Top /></el-icon>
                    </el-button>
                  </el-tooltip>
                </div>
              </div>
            </template>

            <template v-if="getNormalConversations().length > 0">
              <div v-if="getPinnedConversations().length > 0" class="conv-section-header subtle">
                <span>其他会话</span>
              </div>
              <div v-for="c in getNormalConversations()" :key="'normal-' + c.id"
                :class="['conv-item', { active: c.id === conversations.currentConversationId }]"
                @click="selectConversation(c)">
                <div v-if="c.agentName" class="conv-agent-name" :title="c.agentName">
                  <span v-for="(line, i) in splitAgentName(c.agentName)" :key="i">{{ line }}</span>
                </div>
                <div class="conv-avatar-wrap">
                  <el-avatar :size="44" :src="getAvatar(c)" class="conv-avatar">
                    {{ initialOf(c) }}
                  </el-avatar>
                  <!-- 红点显示规则 -->
                  <!-- PROSPECT阶段 + 从未发过消息(无lastMessageAt) => 红点 -->
                  <span v-if="c.stage === 'PROSPECT' && !c.lastMessageAt" class="unread-dot"></span>
                  <!-- 有未读消息 => 数字 -->
                  <span v-else-if="c.unreadCount > 0" class="unread-badge">
                    {{ c.unreadCount > 99 ? '99+' : c.unreadCount }}
                  </span>
                  <el-avatar v-if="c.agentName" :size="18" class="agent-badge" :title="c.agentName">
                    {{ (c.agentName || '?').charAt(0).toUpperCase() }}
                  </el-avatar>
                  <span v-if="c.discordAccountId"
                        class="account-dot"
                        :style="{ background: accountColor(c.discordAccountId) }"
                        :title="c.discordAccountName ? `账号: ${c.discordAccountName}` : '账号'"></span>
                  <span class="conv-presence-dot"
                        :class="presenceClass(c)"
                        :title="presenceTitle(c)"></span>
                </div>
                <div class="conv-main">
                  <div class="conv-line-1">
                    <span class="conv-name">{{ truncateText(c.remark || c.globalName || c.username || ('用户' + (c.friendDiscordUserId || c.discordUserId)), 8) }}</span>
                    <el-tag v-if="c.stage" :type="stageTagType(c.stage)" size="small" effect="light" class="stage-tag-mini">
                      {{ stageLabel(c.stage) }}
                    </el-tag>
                  </div>
                  <div class="conv-line-2 conv-line-grid">
                    <span class="conv-nickname" :title="'@' + (c.username || c.globalName || '')">
                      @{{ c.username || c.globalName || ('用户' + (c.friendDiscordUserId || c.discordUserId)) }}
                    </span>
                  </div>
                </div>
                <div class="conv-actions">
                  <el-tooltip content="置顶">
                    <el-button :type="c.pinned ? 'warning' : 'default'" size="small" circle
                      @click.stop="togglePin(c)">
                      <el-icon><Top /></el-icon>
                    </el-button>
                  </el-tooltip>
                </div>
              </div>
            </template>
          </div>
        </el-scrollbar>
      </div>
    </aside>

    <!-- 中间聊天面板 -->
    <section class="chat-panel">
      <div v-if="!conversations.currentConversation" class="chat-empty">
        <div class="chat-empty-inner">
          <div class="empty-icon"><el-icon :size="56"><ChatDotRound /></el-icon></div>
          <div class="empty-title">选择一个会话开始聊天</div>
          <div class="empty-desc">或者在「好友管理」中发起新对话</div>
        </div>
      </div>

      <template v-else>
        <!-- 分配对话框 -->
        <el-dialog v-model="assignDialogVisible" title="分配给客服" width="420px" :close-on-click-modal="false">
          <div class="dialog-form">
            <el-form :model="assignForm" label-width="90px" size="default">
              <el-form-item label="选择客服">
                <el-select v-model="assignForm.agentId" placeholder="请选择客服" style="width:100%" :loading="agentsLoading">
                  <el-option v-for="a in agents" :key="a.id" :value="a.id"
                    :label="`${a.displayName || a.username} (${a.role})`" />
                </el-select>
              </el-form-item>
            </el-form>
          </div>
          <template #footer>
            <el-button @click="assignDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="assignSubmitting" @click="submitAssign">确定</el-button>
          </template>
        </el-dialog>

        <!-- 转移对话框 -->
        <el-dialog v-model="transferDialogVisible" title="转移会话" width="460px" :close-on-click-modal="false">
          <div class="dialog-form">
            <el-form :model="transferForm" label-width="90px" size="default">
              <el-form-item label="目标客服">
                <el-select v-model="transferForm.agentId" placeholder="选择要转移给的客服" style="width:100%" :loading="agentsLoading">
                  <el-option v-for="a in agents" :key="a.id" :value="a.id"
                    :label="`${a.displayName || a.username} (${a.role})`" />
                </el-select>
              </el-form-item>
              <el-form-item label="转移原因">
                <el-input v-model="transferForm.reason" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }"
                  placeholder="请输入转移原因（可选）" />
              </el-form-item>
            </el-form>
          </div>
          <template #footer>
            <el-button @click="transferDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="transferSubmitting" @click="submitTransfer">确认转移</el-button>
          </template>
        </el-dialog>

        <!-- 标签对话框 -->
        <el-dialog v-model="tagDialogVisible" title="会话标签" width="500px" :close-on-click-modal="false">
          <div class="tag-dialog">
            <div class="tag-section">
              <div class="tag-section-title">当前标签</div>
              <div v-if="currentTags.length === 0" class="tag-empty">暂无标签</div>
              <div v-else class="tag-list">
                <el-tag v-for="t in currentTags" :key="t.id" closable @close="removeCurrentTag(t)"
                  :color="t.color" class="conv-tag">{{ t.name }}</el-tag>
              </div>
            </div>
            <div class="tag-section">
              <div class="tag-section-title">添加标签</div>
              <div class="tag-add-row">
                <el-input v-model="newTagName" size="default" placeholder="输入新标签名" style="flex:1"
                  @keyup.enter="addNewTag" />
                <el-color-picker v-model="newTagColor" size="small" />
                <el-button type="primary" @click="addNewTag">添加</el-button>
              </div>
            </div>
            <div v-if="tagNamesFromServer.length > 0" class="tag-section">
              <div class="tag-section-title">已有标签（点击添加）</div>
              <div class="tag-list">
                <el-tag v-for="name in tagNamesFromServer" :key="name" class="conv-tag clickable"
                  @click="addExistingTag(name)">{{ name }}</el-tag>
              </div>
            </div>
          </div>
        </el-dialog>

        <!-- 消息模板对话框 -->
        <el-dialog v-model="templateDialogVisible" title="消息模板" width="560px" :close-on-click-modal="false">
          <div class="template-dialog">
            <div class="template-toolbar">
              <el-select v-model="templateCategory" size="small" placeholder="全部分类" clearable style="width:160px"
                @change="loadTemplates">
                <el-option v-for="c in templateCategories" :key="c" :value="c" :label="c" />
              </el-select>
              <el-button size="small" type="primary" plain @click="openTemplateCreate">+ 新建模板</el-button>
            </div>
            <el-scrollbar style="max-height:380px">
              <div v-if="templateList.length === 0" class="template-empty">暂无模板</div>
              <div v-else class="template-list">
                <div v-for="tpl in templateList" :key="tpl.id" class="template-item" @click="useTemplate(tpl)">
                  <div class="template-title">{{ tpl.title || '（无标题）' }}</div>
                  <div class="template-content">{{ tpl.content }}</div>
                  <div class="template-meta">
                    <el-tag v-if="tpl.category" size="small" effect="plain">{{ tpl.category }}</el-tag>
                    <el-button size="small" link @click.stop="editTemplateItem(tpl)">编辑</el-button>
                    <el-button size="small" link type="danger" @click.stop="deleteTemplateItem(tpl)">删除</el-button>
                  </div>
                </div>
              </div>
            </el-scrollbar>
          </div>
        </el-dialog>

        <!-- 模板编辑对话框 -->
        <el-dialog v-model="templateEditVisible" :title="templateEditId ? '编辑模板' : '新建模板'" width="460px" :close-on-click-modal="false">
          <el-form :model="templateEditForm" label-width="80px" size="default">
            <el-form-item label="标题"><el-input v-model="templateEditForm.title" placeholder="模板标题" /></el-form-item>
            <el-form-item label="分类"><el-input v-model="templateEditForm.category" placeholder="如：问候/产品介绍" /></el-form-item>
            <el-form-item label="内容"><el-input v-model="templateEditForm.content" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" placeholder="模板内容" /></el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="templateEditVisible = false">取消</el-button>
            <el-button type="primary" @click="saveTemplateEdit">保存</el-button>
          </template>
        </el-dialog>

        <!-- 聊天头部 -->
        <div class="chat-header">
          <div class="chat-header-main">
            <el-avatar :size="40" :src="getAvatar(conversations.currentConversation)" class="header-avatar">
              {{ initialOf(conversations.currentConversation) }}
            </el-avatar>
            <div class="chat-header-meta">
              <div class="chat-header-name">
                <el-tag v-if="conversations.currentConversation.stage"
                  :type="stageTagType(conversations.currentConversation.stage)" size="small" effect="light" class="header-stage-tag">
                  {{ stageLabel(conversations.currentConversation.stage) }}
                </el-tag>
                <span class="customer-name">{{ conversations.currentConversation.remark || conversations.currentConversation.globalName || conversations.currentConversation.username || '客户' }}</span>
              </div>
              <div class="chat-header-sub">
                @{{ conversations.currentConversation.username || '-' }}
                <span class="divider">·</span>
                ID: {{ conversations.currentConversation.friendDiscordUserId || conversations.currentConversation.discordUserId }}
              </div>
            </div>
          </div>
          <div class="chat-header-actions">
            <el-select v-model="currentStage" size="small" placeholder="阶段" class="stage-select" @change="onStageChange">
              <el-option v-for="s in stageOptions" :key="s.value" :value="s.value" :label="s.label" />
            </el-select>
            <el-button size="small" circle :type="conversations.currentConversation.pinned ? 'warning' : 'default'" @click="togglePin">
              <el-icon><Top /></el-icon>
            </el-button>
            <el-button size="small" circle @click="showProfile = !showProfile">
              <el-icon><User /></el-icon>
            </el-button>
          </div>
        </div>

        <!-- 消息列表 -->
        <el-scrollbar ref="msgScrollRef" class="msg-scroll" @scroll="onMsgScroll">
          <div v-if="hasMore" class="load-more-row">
            <el-button v-if="!loadingMore" size="small" link type="primary" @click="loadMore">
              <el-icon><ArrowUp /></el-icon> 加载更多历史消息
            </el-button>
            <span v-else class="loading-tip-sm">
              <el-icon class="is-loading"><Loading /></el-icon> 加载中...
            </span>
          </div>

          <div v-if="currentLoading" class="messages-loading">
            <el-icon class="is-loading"><Loading /></el-icon> 加载消息...
          </div>

          <div v-else class="messages-list" @click="conversations.markCurrentAsRead()">
            <el-empty v-if="conversations.currentMessages.length === 0" description="暂无消息，开始对话吧" :image-size="80" />
            <div v-for="(msg, idx) in conversations.currentMessages" :key="msg.id || ('msg-' + idx)"
              :class="['msg-row', msg.direction === 'OUTBOUND' ? 'out' : 'in', { deleted: msg.isDeleted }]">
              <el-avatar v-if="msg.direction !== 'OUTBOUND'" :size="36" :src="getAvatarByMsg(msg, 'in')" class="msg-avatar">
                {{ initialOfByMsg(msg, 'in') }}
              </el-avatar>

              <div class="msg-bubble-wrap" @mouseenter="isHoverMsg = msg.id" @mouseleave="isHoverMsg = null; isActionHover = false">
                <div class="msg-meta-line">
                  <span class="msg-sender">{{ senderNameOf(msg) }}</span>
                  <span v-if="msg.direction === 'OUTBOUND'" class="mine-badge">我</span>
                  <span class="msg-time">{{ formatTime(msg.createdAt) }}</span>
                  <span v-if="msg.editedAt" class="msg-edited">(已编辑)</span>
                </div>
                <div :class="['msg-bubble', msg.direction === 'OUTBOUND' ? 'bubble-out' : 'bubble-in', { 'bubble-deleted': msg.isDeleted }]">
                  <div v-if="parseAttachments(msg).length" class="msg-attachments">
                    <div v-for="att in parseAttachments(msg)" :key="att.url" class="attachment-item">
                      <img v-if="isImage(att.filename)" :src="att.url" class="attachment-image" />
                      <a v-else :href="att.url" target="_blank" class="attachment-file">
                        <el-icon><Document /></el-icon>
                        <span>{{ att.filename }}</span>
                      </a>
                    </div>
                  </div>

                  <div v-if="msg.referencedMessageId" class="msg-quote" @click="jumpToMessage(msg.referencedMessageId)">
                    <el-icon><ChatLineSquare /></el-icon>
                    <span>引用消息</span>
                  </div>

                  <div v-if="!msg.isDeleted && isVoiceMsg(msg)" class="msg-voice-wrap">
                    <el-icon><Microphone /></el-icon>
                    <audio v-if="voiceSrc(msg)" :src="voiceSrc(msg)" :type="msg.audioMimeType || 'audio/ogg'" controls class="msg-voice-audio"
                           onerror="this.style.display='none';this.nextElementSibling && (this.nextElementSibling.style.display='flex')">
                    </audio>
                    <div v-else class="msg-voice-placeholder">
                      语音消息
                      <span v-if="msg.audioDuration" class="msg-voice-duration">{{ msg.audioDuration }}s</span>
                      <span v-else-if="voiceSrc(msg)" class="msg-voice-duration">点击播放</span>
                    </div>
                  </div>

                  <!-- 语音转文字区（INBOUND 默认展示，已去掉"语音转文字/翻译/转文字"按钮）
                       - 转写/翻译进行中：显示骨架
                       - 失败：显示错误+重试
                       - 成功：默认直接显示中文译文（asrTranslated 或兜底 asrText/content）
                       - 仅当译文存在且与原文不同时，才在右下角提供"查看原文 / 查看译文"切换链接 -->
                  <div v-if="!msg.isDeleted && isVoiceMsg(msg) && shouldShowAsrCard(msg)" class="msg-asr-card asr-card-simple">
                    <div class="asr-card-body">
                      <!-- 加载状态 -->
                      <el-skeleton v-if="isAsrPending(msg) || !hasAsrResult(msg) && !asrFailed(msg)" :rows="2" animated />
                      <!-- 错误状态 -->
                      <div v-else-if="asrFailed(msg)" class="asr-error">
                        <el-icon><Warning /></el-icon>
                        <span>语音转文字失败：{{ msg.asrError || '请检查 AI 配置中的百炼 key' }}</span>
                        <el-button size="small" type="primary" link @click="triggerAsr(msg, true)">重试</el-button>
                      </div>
                      <!-- 文本内容：默认显示译文，下方展开原文 -->
                      <div v-else>
                        <!-- 译文（优先显示，没有译文时显示原文） -->
                        <div class="asr-text">{{ asrDisplayText(msg) }}</div>
                        <!-- 有译文+原文时，下方展开显示 ASR 原文（非切换，是展开） -->
                        <div v-if="canShowOriginalBelow(msg)" class="asr-original-wrap">
                          <div class="asr-toggle-link" @click="toggleOriginalBelow(msg)">
                            {{ originalBelowExpanded[msg.id] ? '收起原文' : '查看原文' }}
                          </div>
                          <div v-show="originalBelowExpanded[msg.id]" class="asr-original-text">{{ msg.asrText }}</div>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div v-if="!msg.isDeleted && !isVoiceMsg(msg)" class="msg-content">{{ displayContentOf(msg) }}</div>
                  <div v-else-if="msg.isDeleted" class="msg-deleted-tip">[消息已删除]</div>

                  <div v-if="parseReactions(msg).length" class="msg-reactions">
                    <el-tag v-for="r in parseReactions(msg)" :key="r.emoji" size="small" class="reaction-tag"
                      @click="addReactionEmoji(msg, r.emoji, true)">
                      {{ r.emoji }} {{ r.count }}
                    </el-tag>
                  </div>

                  <!-- 普通文本消息的"外层原文折叠"：对语音消息隐藏，避免和卡片内「查看原文/译文」切换重复 -->
                  <div v-if="hasOriginal(msg) && !isVoiceMsg(msg)" class="msg-original-wrap">
                    <el-button size="small" link type="info" @click="toggleOriginal(msg.id)">
                      {{ originalExpandedSet[msg.id] ? '收起原文' : `查看原文 (${(originalContentOf(msg) || '').length})` }}
                      <el-icon class="arrow-icon" :class="{ flip: originalExpandedSet[msg.id] }"><ArrowDown /></el-icon>
                    </el-button>
                    <div v-show="originalExpandedSet[msg.id]" class="original-text">原文：{{ originalContentOf(msg) }}</div>
                  </div>

                  <div v-if="canTranslateInbound(msg)" class="msg-translate-wrap">
                    <el-tag v-if="msg.language && msg.language !== 'zh-cn'" size="small" type="info" effect="plain" class="msg-lang-tag">
                      检测语言: {{ getLanguageName(msg.language) }}
                    </el-tag>
                    <el-button size="small" link type="primary" :loading="translatingSet[msg.id]" @click="translateMsg(msg)">
                      <el-icon><Location /></el-icon> 翻译成{{ targetLang === 'zh' ? '中文' : targetLang === 'en' ? 'English' : targetLang === 'ja' ? '日本語' : '한국어' }}
                    </el-button>
                    <div v-if="msg.userTranslated" class="translated-text">翻译：{{ msg.userTranslated }}</div>
                  </div>

                  <div class="msg-actions" v-if="(isHoverMsg === msg.id || isActionHover === msg.id) && !msg.isDeleted">
                    <el-tooltip content="回复">
                      <el-button size="small" link @mouseenter="isActionHover = msg.id" @mouseleave="isActionHover = null" @click="replyTo(msg)"><el-icon><ChatLineSquare /></el-icon></el-button>
                    </el-tooltip>
                    <el-tooltip content="编辑" v-if="msg.direction === 'OUTBOUND'">
                      <el-button size="small" link @mouseenter="isActionHover = msg.id" @mouseleave="isActionHover = null" @click="enterEditMode(msg)"><el-icon><Edit /></el-icon></el-button>
                    </el-tooltip>
                    <el-popover placement="top" :width="180" trigger="click">
                      <template #reference>
                        <el-tooltip content="添加表情反应">
                          <el-button size="small" link @mouseenter="isActionHover = msg.id" @mouseleave="isActionHover = null">😀</el-button>
                        </el-tooltip>
                      </template>
                      <div class="reaction-picker">
                        <span v-for="e in quickEmojis" :key="e" class="emoji-item" @click="addReactionEmoji(msg, e)">{{ e }}</span>
                      </div>
                    </el-popover>
                    <el-tooltip content="复制">
                      <el-button size="small" link @mouseenter="isActionHover = msg.id" @mouseleave="isActionHover = null" @click="copyMsg(msg)"><el-icon><CopyDocument /></el-icon></el-button>
                    </el-tooltip>
                    <el-tooltip content="删除" v-if="msg.direction === 'OUTBOUND'">
                      <el-button size="small" link type="danger" @mouseenter="isActionHover = msg.id" @mouseleave="isActionHover = null" @click="deleteMsg(msg)"><el-icon><Delete /></el-icon></el-button>
                    </el-tooltip>
                  </div>
                </div>
              </div>

              <el-avatar v-if="msg.direction === 'OUTBOUND'" :size="36" :src="getAvatarByMsg(msg, 'out')" class="msg-avatar">
                {{ initialOfByMsg(msg, 'out') }}
              </el-avatar>
            </div>
          </div>

          <div ref="scrollAnchor" style="height:1px;"></div>
        </el-scrollbar>

        <!-- 工具栏 -->
        <div class="toolbar-row">
          <div class="toolbar-left">
            <el-popover placement="top" :width="260" trigger="click">
              <template #reference>
                <el-button size="small" circle class="toolbar-btn"><el-icon><Stamp /></el-icon></el-button>
              </template>
              <div class="emoji-picker">
                <span v-for="e in emojiList" :key="e" class="emoji-item" @click="insertEmoji(e)">{{ e }}</span>
              </div>
            </el-popover>

            <el-popover v-model:visible="showGifPanel" placement="top-start" width="320" trigger="click">
              <template #reference>
                <el-button size="small" circle class="toolbar-btn">GIF</el-button>
              </template>
              <div class="gif-panel">
                <el-tabs v-model="gifTab" size="small">
                  <el-tab-pane label="收藏" name="favorites">
                    <div class="gif-grid">
                      <div v-for="(gif, i) in gifFavorites" :key="'fav-'+i" class="gif-item" @click="insertText(gif)">
                        <img :src="gif" class="gif-thumb" />
                      </div>
                      <div v-if="gifFavorites.length === 0" class="gif-empty">暂无收藏</div>
                    </div>
                  </el-tab-pane>
                  <el-tab-pane label="热门" name="hot">
                    <div class="gif-grid">
                      <div v-for="(gif, i) in gifHot" :key="'hot-'+i" class="gif-item" @click="insertText(gif)">
                        <img :src="gif" class="gif-thumb" />
                      </div>
                    </div>
                  </el-tab-pane>
                </el-tabs>
              </div>
            </el-popover>

            <el-popover v-model:visible="showStickerPanel" placement="top-start" width="280" trigger="click">
              <template #reference>
                <el-button size="small" circle class="toolbar-btn"><el-icon><Stamp /></el-icon></el-button>
              </template>
              <div class="sticker-panel">
                <el-tabs v-model="stickerTab" size="small">
                  <el-tab-pane label="系统" name="system">
                    <div class="sticker-grid">
                      <div v-for="(s, i) in systemStickers" :key="'sys-'+i" class="sticker-item" @click="insertText(s.url)">
                        <img :src="s.url" class="sticker-thumb" />
                        <span class="sticker-count" v-if="s.count">{{ s.count }}</span>
                      </div>
                    </div>
                  </el-tab-pane>
                  <el-tab-pane label="收藏" name="favorites">
                    <div class="sticker-grid">
                      <div v-for="(s, i) in favoriteStickers" :key="'fav-'+i" class="sticker-item" @click="insertText(s.url)">
                        <img :src="s.url" class="sticker-thumb" />
                        <span class="sticker-count" v-if="s.count">{{ s.count }}</span>
                      </div>
                      <div v-if="favoriteStickers.length === 0" class="sticker-empty">暂无收藏</div>
                    </div>
                  </el-tab-pane>
                </el-tabs>
              </div>
            </el-popover>

            <el-popover v-model:visible="showMentionPanel" placement="top-start" width="240" trigger="click">
              <template #reference>
                <el-button size="small" circle class="toolbar-btn">@</el-button>
              </template>
              <div class="mention-panel">
                <div class="mention-list">
                  <div v-for="m in memberList" :key="m.id" class="mention-item" @click="insertMention(m)">
                    <el-avatar :size="24" :src="m.avatarUrl">{{ (m.name || '?').charAt(0) }}</el-avatar>
                    <span>{{ m.name }}</span>
                  </div>
                  <div v-if="memberList.length === 0" class="mention-empty">暂无成员</div>
                </div>
              </div>
            </el-popover>

            <el-button size="small" circle class="toolbar-btn" @click="toggleAttachment">
              <el-icon><UploadFilled /></el-icon>
            </el-button>
            <input ref="fileInputRef" type="file" accept="image/*,.pdf,.doc,.docx,.xls,.xlsx,.txt,.zip" multiple style="display:none" @change="onFileSelect" />
          </div>

          <div class="toolbar-right">
            <span class="detected-lang">好友语言: {{ detectedLang }}</span>
            <span class="target-lang-label">目标语言:</span>
            <el-select v-model="targetLang" size="small" class="lang-select" @change="onTargetLangChange">
              <el-option value="zh" label="中文" />
              <el-option value="en" label="English" />
              <el-option value="ja" label="日本語" />
              <el-option value="ko" label="한국어" />
            </el-select>
            <el-button size="small" type="primary" plain @click="translateCurrentMsg">
              <el-icon><Location /></el-icon> 翻译
            </el-button>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="input-area">
          <div class="input-hint" v-if="inputHint">
            <el-icon><InfoFilled /></el-icon>
            <span>{{ inputHint }}</span>
            <span class="shortcut-hint">（Alt+W 翻译 · Alt+E 漏斗 · Alt+R AI建议）</span>
          </div>

          <div v-if="showAiPanel" class="ai-panel">
            <div class="ai-panel-header">
              <span>💡 AI 推荐回复</span>
              <el-select v-model="aiTone" size="small" style="width:100px">
                <el-option value="friendly" label="友好" />
                <el-option value="professional" label="专业" />
                <el-option value="casual" label="轻松" />
              </el-select>
              <el-button size="small" @click="loadAiSuggestions">刷新</el-button>
              <el-button size="small" link @click="showAiPanel = false">关闭</el-button>
            </div>
            <div v-if="aiLoading" class="ai-loading">加载中...</div>
            <div v-else class="ai-suggestions">
              <div v-for="(s, i) in aiSuggestions" :key="i" class="ai-suggestion" @click="applyAiSuggestion(s)">
                <div class="ai-suggestion-text">{{ s.text }}</div>
                <div v-if="s.translated" class="ai-suggestion-translated">{{ s.translated }}</div>
              </div>
            </div>
          </div>

          <div v-if="replyToMsg" class="reply-preview">
            <el-icon><ChatLineSquare /></el-icon>
            <span>回复 {{ replyToMsg.senderName }}: {{ (replyToMsg.content || '').slice(0, 60) }}</span>
            <el-icon class="close-icon" @click="cancelReply"><Close /></el-icon>
          </div>

          <div v-if="pendingAttachments.length" class="attachment-preview-list">
            <div v-for="(att, i) in pendingAttachments" :key="i" class="attachment-preview-item">
              <img v-if="isImage(att.name)" :src="att.url" class="attachment-thumb" />
              <el-icon v-else><Document /></el-icon>
              <span>{{ att.name }}</span>
              <el-icon class="close-icon" @click="pendingAttachments.splice(i, 1)"><Close /></el-icon>
            </div>
          </div>

          <div v-if="isEditing" class="editing-hint">
            <el-icon><Edit /></el-icon>
            <span>正在编辑消息 #{{ editingMsgId }}</span>
            <el-icon class="close-icon" @click="cancelEditMode"><Close /></el-icon>
          </div>

          <div class="input-box-wrap">
            <!-- 空闲：开始录音按钮（只有idle态才显示，录音中整块区域替换） -->
            <el-button
              v-if="!isEditing && !isRecording"
              class="voice-btn voice-btn-start"
              :type="recordedAudioData ? 'success' : 'default'"
              circle
              size="large"
              :disabled="sending"
              @click="startRecording"
              title="开始录音（发送语音）">
              <el-icon><Microphone /></el-icon>
            </el-button>

            <!-- 录音中：左(波形+时间+提示) + 右(取消/停止都放右边) -->
            <div v-else-if="!isEditing && isRecording" class="recorder-live">
              <div class="recorder-live-main">
                <div class="live-wave">
                  <!-- 基线声波 SVG：作为一条连续的浅红正弦波打底，初始就是"声波线"不是虚线 -->
                  <svg class="live-wave-baseline" viewBox="0 0 600 40" preserveAspectRatio="none" aria-hidden="true">
                    <defs>
                      <linearGradient id="waveBaselineGrad" x1="0" x2="1" y1="0" y2="0">
                        <stop offset="0%" stop-color="#fca5a5" stop-opacity="0.9"/>
                        <stop offset="50%" stop-color="#f87171" stop-opacity="0.95"/>
                        <stop offset="100%" stop-color="#fca5a5" stop-opacity="0.9"/>
                      </linearGradient>
                    </defs>
                    <path d="M0 20
                             C 30 18, 45 24, 60 20
                             C 75 16, 90 26, 105 20
                             C 120 14, 135 28, 150 20
                             C 165 12, 180 30, 195 20
                             C 210 10, 225 32, 240 20
                             C 255 8, 270 34, 285 20
                             C 300 6, 315 36, 330 20
                             C 345 6, 360 36, 375 20
                             C 390 8, 405 34, 420 20
                             C 435 10, 450 32, 465 20
                             C 480 12, 495 30, 510 20
                             C 525 14, 540 26, 555 20
                             C 570 16, 585 24, 600 20"
                          fill="none"
                          stroke="url(#waveBaselineGrad)"
                          stroke-width="2.2"
                          stroke-linecap="round"
                          stroke-linejoin="round"/>
                  </svg>
                  <div class="live-wave-bars" :aria-label="'录音中，音量波形条，共'+recordingBars.length+'格'">
                    <div v-for="(v, i) in recordingBars" :key="i" class="live-wave-bar" :style="{ '--lv': v }"></div>
                  </div>
                </div>
                <div class="live-meta">
                  <div class="live-duration">
                    <span class="live-led" aria-hidden="true"></span>
                    <span class="live-clock">{{ String(Math.floor(recordingDuration/60)).padStart(2,'0') }}:{{ String(recordingDuration%60).padStart(2,'0') }}</span>
                  </div>
                  <div class="live-hint" aria-live="polite">
                    点击 <b>停止录音</b> 预览并发送 · <span class="live-hint-act" @click.stop="cancelRecording">取消</span>
                  </div>
                </div>
              </div>

              <div class="recorder-actions">
                <button type="button" class="btn-cancel-rec" @click="cancelRecording" aria-label="取消本次录音，不保存">
                  取消
                </button>
                <button type="button"
                        class="btn-stop-rec"
                        @click="stopRecording"
                        aria-label="停止录音并进入预览">
                  <span class="bsr-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"></rect></svg>
                  </span>
                  <span class="bsr-label">停止</span>
                </button>
              </div>
            </div>

            <div v-if="recordedAudioData && !isEditing" class="recorded-preview">
              <audio :src="localVoiceSrc()" controls class="recorded-audio" />
              <span v-if="recordedAudioDuration" class="recorded-duration">{{ recordedAudioDuration }}s</span>
              <el-button size="small" circle @click="cancelRecording" :disabled="sending" title="删除录音">
                <el-icon><Delete /></el-icon>
              </el-button>
              <el-button size="small" type="primary" circle :loading="sending" :disabled="sending" @click="sendVoice" title="发送语音">
                <el-icon><Promotion /></el-icon>
              </el-button>
            </div>

            <el-input v-if="!recordedAudioData && !isRecording" v-model="inputText" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }"
              :placeholder="isEditing ? '编辑消息内容...' : inputPlaceholder"
              resize="none" @keydown="onInputKeydown" class="msg-input" />
            <el-button v-if="!isEditing && !recordedAudioData && !isRecording" type="primary" class="send-btn"
              :disabled="!inputText.trim() && !replyToMsg && pendingAttachments.length === 0" :loading="sending"
              @click="send">
              {{ sending ? '发送中' : '发送' }}
              <el-icon style="margin-left:4px;"><Promotion /></el-icon>
            </el-button>
            <el-button v-else-if="isEditing" type="primary" class="send-btn"
              :disabled="!inputText.trim()" :loading="sending"
              @click="saveEditMsg">
              保存
              <el-icon style="margin-left:4px;"><Promotion /></el-icon>
            </el-button>
          </div>
        </div>
      </template>
    </section>

    <!-- 右侧客户资料侧栏 -->
    <aside v-if="showProfile" class="profile-panel">
      <div class="profile-header">
        <h4>客户资料</h4>
        <el-button size="small" link @click="showProfile = false"><el-icon><Close /></el-icon></el-button>
      </div>
      <div v-if="profileLoading" class="profile-loading">
        <el-icon class="is-loading"><Loading /></el-icon> 加载中...
      </div>
      <template v-else-if="userProfile">
        <div class="profile-section">
          <div class="profile-avatar-wrap">
            <el-avatar :size="72" :src="userProfile.avatarUrl" class="profile-avatar">
              {{ (userProfile.globalName || userProfile.username || '?').charAt(0).toUpperCase() }}
            </el-avatar>
            <div class="profile-online-dot" :class="{ online: isOnline, blocked: userProfile.status === 'BLOCKED' }"></div>
          </div>
          <h3 class="profile-name">{{ userProfile.globalName || userProfile.username }}</h3>
          <div class="profile-sub">
            <el-tag v-if="userProfile.status === 'BLOCKED'" type="danger" size="small" effect="plain">已拉黑</el-tag>
            <el-tag v-else type="success" size="small" effect="plain">{{ isOnline ? '在线' : '离线' }}</el-tag>
          </div>
          <div class="profile-info-row">
            <div class="profile-info-item inline-item">
              <span class="info-label">昵称</span>
              <span class="info-value">{{ userProfile.globalName || '-' }}</span>
            </div>
            <div class="profile-info-item inline-item">
              <span class="info-label">加入时间</span>
              <span class="info-value">{{ formatDate(userProfile.firstSeenAt) }}</span>
            </div>
          </div>
        </div>

        <div class="profile-section">
          <div class="profile-section-title">备注</div>
          <el-input v-model="remarkEdit" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }"
            placeholder="添加客户备注" @change="saveRemark" />
        </div>

        <div class="profile-section">
          <div class="profile-info-row">
            <span>来源游戏</span>
            <span>
              {{ conversations.currentConversation?.gameName || '-' }}
              <el-tag v-if="userProfile?.serverName" type="primary" effect="plain" size="small" style="margin-left: 4px;">
                {{ userProfile.serverName }}
              </el-tag>
            </span>
          </div>
        </div>

        <div class="profile-section">
          <div class="profile-section-title">快速状态</div>
          <div class="status-grid">
            <el-button v-for="s in quickStageOptions" :key="s.value"
              size="small" :type="currentStage === s.value ? s.type : 'default'"
              :effect="currentStage === s.value ? 'dark' : 'plain'"
              class="status-btn"
              @click="onStageChange(s.value)">
              {{ s.label }}
            </el-button>
          </div>
        </div>

        <div class="profile-section">
          <el-button size="default" style="width:100%" @click="onHeaderCommand('transfer')">
            <el-icon><Switch /></el-icon> 转移客户
          </el-button>
        </div>

        <div class="profile-section ai-section">
          <div class="profile-section-title-row clickable" @click="toggleAiPanel">
            <span class="profile-section-title">AI推荐回复</span>
            <el-icon><component :is="showAiPanel ? 'ArrowDown' : 'ArrowUp'" /></el-icon>
          </div>
          <div v-if="showAiPanel" class="ai-inline-panel">
            <div class="ai-tone-row">
              <span class="ai-tone-label">语气</span>
              <el-select v-model="aiTone" size="small" style="flex:1">
                <el-option value="friendly" label="友好" />
                <el-option value="professional" label="专业" />
                <el-option value="casual" label="轻松" />
              </el-select>
            </div>
          </div>
        </div>
      </template>
    </aside>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, Loading, ChatDotRound, Refresh, ArrowUp, ArrowDown, InfoFilled, Promotion,
  Location, User, Top, Stamp, Plus, Close, Document, CopyDocument, Delete,
  ChatLineSquare, Edit, MoreFilled, Switch, PriceTag, UploadFilled, Calendar, Microphone,
  Warning, RefreshRight
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useAccountsStore } from '@/stores/accounts'
import { useConversationsStore } from '@/stores/conversations'
import {
  listMessages, translateMessage, getUserProfile, updateUserProfile,
  editMessage as editMessageApi, deleteMessage as deleteMessageApi,
  addReaction as addReactionApi, replyMessage as replyMessageApi,
  uploadAttachment, getAiSuggestions,
  assignToAgent, transferConversation, listAvailableAgents,
  getConversationTags, addConversationTags, removeConversationTag, listTagNames as listTagNamesApi,
  listMessageTemplates, getTemplateCategories, createMessageTemplate,
  updateMessageTemplate, deleteMessageTemplate,
  transcribeVoiceAsr, translateAsrText
} from '@/api'

const auth = useAuthStore()
const accounts = useAccountsStore()
const conversations = useConversationsStore()

const msgScrollRef = ref(null)
const scrollAnchor = ref(null)
const fileInputRef = ref(null)

const convSearch = ref('')
const selectedAccountId = ref(null)
const selectedStage = ref(null)
const pinnedOnly = ref(false)
const dateRange = ref(null)
const tempDateRange = ref(null)
const dateQuick = ref('')
const datePopoverVisible = ref(false)

// 监听弹窗打开/关闭，初始化tempDateRange
watch(datePopoverVisible, (visible) => {
  if (visible) {
    tempDateRange.value = dateRange.value ? [...dateRange.value] : null
  }
})

const dateQuickOptions = [
  { value: 'today',  label: '当天' },
  { value: '3d',     label: '近3天' },
  { value: '7d',     label: '近7天' },
  { value: '10d',    label: '近10天' },
  { value: '15d',    label: '近15天' },
  { value: '30d',    label: '近30天' }
]

const dateQuickLabel = computed(() => {
  const found = dateQuickOptions.find(o => o.value === dateQuick.value)
  return found ? found.label : ''
})
const inputText = ref('')
const sending = ref(false)
const loadingMore = ref(false)
const showProfile = ref(true)
const profileLoading = ref(false)
const userProfile = ref(null)
const remarkEdit = ref('')
const newTag = ref('')
const replyToMsg = ref(null)
const isHoverMsg = ref(null)
const isActionHover = ref(false)
const attachmentPreview = ref(null)
const editTargetMsg = ref(null)
const editText = ref('')
const pendingAttachments = ref([])
const showAiPanel = ref(false)
const aiTone = ref('friendly')
const aiSuggestions = ref([])
const aiLoading = ref(false)

// 使用 ref 对象代替 Set 以确保模板中的响应性
const originalExpandedSet = ref({})
const translatingSet = ref({})
const asrLoadingSet = ref({})
// 语音消息"查看原文"展开状态（在译文下方展开，不是切换）
const originalBelowExpanded = ref({})

const targetLang = ref('zh')
const detectedLang = ref('未知')
let userChangedTargetLang = false  // 标记用户是否手动修改过目标语言
const isEditing = ref(false)
const editingMsgId = ref(null)
const showGifPanel = ref(false)
const showStickerPanel = ref(false)
const showMentionPanel = ref(false)
const gifTab = ref('favorites')
const stickerTab = ref('system')

// === 语音录音 ===
const isRecording = ref(false)
const recordingDuration = ref(0)
const recordedAudioData = ref(null)  // base64 dataURL
const recordedAudioMime = ref('audio/webm')
const recordedAudioDuration = ref(0)
const mediaRecorderRef = ref(null)
const recordedChunks = []
let recordTimer = null
let recordStartTime = 0
let audioContextRef = null
let analyserRef = null
let audioStreamRef = null
let animationFrameId = null
// 可视化波形条（40 格，每格 0~1 的音量）
const recordingBars = ref(new Array(40).fill(0))
// 最近 40 次 RMS 采样，按时间顺序滚动，模拟 Discord 语音消息的波形从左到右延伸
const recordingRmsHistory = []

// === 分配/转移 ===
const assignDialogVisible = ref(false)
const transferDialogVisible = ref(false)
const assignForm = reactive({ agentId: null })
const transferForm = reactive({ agentId: null, reason: '' })
const assignSubmitting = ref(false)
const transferSubmitting = ref(false)
const agents = ref([])
const agentsLoading = ref(false)

// === 标签 ===
const tagDialogVisible = ref(false)
const currentTags = ref([])
const tagNamesFromServer = ref([])
const newTagName = ref('')
const newTagColor = ref('#5865f2')

// === 消息模板 ===
const templateDialogVisible = ref(false)
const templateDialogLoading = ref(false)
const templateCategory = ref('')
const templateCategories = ref([])
const templateList = ref([])
const templateEditVisible = ref(false)
const templateEditId = ref(null)
const templateEditForm = reactive({ title: '', category: '', content: '' })

const stageOptions = [
  { value: 'PROSPECT',   label: '通过客户',   type: 'info' },
  { value: 'NEW',        label: '回复客户',   type: 'primary' },
  { value: 'CONVERTED',  label: '注册客户',   type: 'success' },
  { value: 'CHURNED',    label: '流失客户',   type: 'danger' },
  { value: 'ARCHIVED',   label: '归档客户',   type: 'info' }
]

// 客户资料快速状态只保留注册客户和归档客户
const quickStageOptions = [
  { value: 'CONVERTED',  label: '注册客户',   type: 'success' },
  { value: 'ARCHIVED',   label: '归档客户',   type: 'info' }
]

const emojiList = ['😀','😂','🤣','😊','😍','😘','🥰','😎','🤔','😴','🤗','😇','😉','😋','🤩','😏','😒','😞','😔','😟','😕','🙁','☹️','😣','😖','😫','😩','🥺','😢','😭','😤','😠','😡','🤬','🤯','😳','🥵','🥶','😱','😨','😰','😥','😓','🤤','😪','🤤','😴','😷','🤒','🤕','🤢','🤮','🥴','😵','🤯','🤠','🥳','🥸','😎','🤓','🧐']
const quickEmojis = ['👍','❤️','😂','🎉','🔥','💯','👍','🙏','💪','🚀','✨','😅']

const gifFavorites = ref([])
const gifHot = ref([])
const systemStickers = ref([])
const favoriteStickers = ref([])
const memberList = ref([])

const currentStage = computed({
  get: () => conversations.currentConversation?.stage || null,
  set: (v) => { if (conversations.currentConversation) { updateStageSilently(v) } }
})

const tagList = computed(() => {
  if (!userProfile.value?.tags) return []
  return userProfile.value.tags.split(',').map(t => t.trim()).filter(Boolean)
})

const filteredConversations = computed(() => {
  let list = conversations.conversations
  // 默认过滤掉流失客户，除非用户明确选择了流失阶段
  if (selectedStage.value !== 'CHURNED') {
    list = list.filter(c => c.stage !== 'CHURNED')
  }
  if (selectedAccountId.value) {
    list = list.filter(c => (c.discordAccountId || c.accountId) === selectedAccountId.value)
  }
  if (selectedStage.value) {
    list = list.filter(c => c.stage === selectedStage.value)
  }
  if (pinnedOnly.value) {
    list = list.filter(c => c.pinned)
  }
  if (dateRange.value && dateRange.value.length === 2) {
    const [start, end] = dateRange.value
    const startTs = new Date(start + 'T00:00:00').getTime()
    const endTs = new Date(end + 'T23:59:59').getTime()
    list = list.filter(c => {
      const ts = c.lastMessageAt ? new Date(c.lastMessageAt).getTime() : (c.createdAt ? new Date(c.createdAt).getTime() : 0)
      return ts >= startTs && ts <= endTs
    })
  }
  const kw = convSearch.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter(c => {
    const name = (c.globalName || c.username || '').toLowerCase()
    const id = c.friendDiscordUserId || c.discordUserId || ''
    const snippet = (c.lastMessagePreview || '').toLowerCase()
    const remark = (c.remark || '').toLowerCase()
    return name.includes(kw) || id.includes(kw) || snippet.includes(kw) || remark.includes(kw)
  })
})

function setDateRange(type) {
  dateQuick.value = type
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = new Date(today)
  let start = new Date(today)

  switch (type) {
    case 'today':
      start = new Date(today)
      break
    case '3d':
      start = new Date(today.getTime() - 2 * 86400000)
      break
    case '7d':
      start = new Date(today.getTime() - 6 * 86400000)
      break
    case '10d':
      start = new Date(today.getTime() - 9 * 86400000)
      break
    case '15d':
      start = new Date(today.getTime() - 14 * 86400000)
      break
    case '30d':
      start = new Date(today.getTime() - 29 * 86400000)
      break
  }

  const fmt = d => {
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${y}-${m}-${day}`
  }
  dateRange.value = [fmt(start), fmt(end)]
  tempDateRange.value = [fmt(start), fmt(end)]
  datePopoverVisible.value = false
}

function confirmDateRange() {
  if (tempDateRange.value && tempDateRange.value.length === 2) {
    dateQuick.value = ''
    dateRange.value = [...tempDateRange.value]
    datePopoverVisible.value = false
  }
}

function cancelDateRange() {
  tempDateRange.value = dateRange.value ? [...dateRange.value] : null
  datePopoverVisible.value = false
}

function clearDateFilter() {
  dateRange.value = null
  tempDateRange.value = null
  dateQuick.value = ''
  datePopoverVisible.value = false
}

const currentLoading = computed(() =>
  conversations.currentConversationId
    ? !!conversations.loadingMessagesMap[conversations.currentConversationId]
    : false
)

const hasMore = computed(() =>
  conversations.currentConversationId
    ? !!conversations.hasMoreMap[conversations.currentConversationId]
    : false
)

const inputHint = computed(() => {
  if (!inputText.value.trim()) return ''
  if (containsChinese(inputText.value)) {
    const targetName = LANGUAGE_NAMES[targetLang.value] || targetLang.value
    return `检测到中文，发送时将自动翻译为${targetName}`
  }
  return ''
})

const inputPlaceholder = computed(() => {
  const targetName = LANGUAGE_NAMES[targetLang.value] || targetLang.value
  return `输入消息，Enter 发送，Shift+Enter 换行。中文会自动翻译成${targetName}发送。`
})

const isOnline = computed(() => {
  const lastActive = userProfile.value?.lastActiveAt
  if (!lastActive) return false
  const diff = Date.now() - new Date(lastActive).getTime()
  return diff < 5 * 60 * 1000
})

function containsChinese(text) {
  return /[\u4e00-\u9fa5]/.test(text || '')
}

function stageLabel(v) {
  return stageOptions.find(s => s.value === v)?.label || v
}
function stageTagType(v) {
  return stageOptions.find(s => s.value === v)?.type || 'info'
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const pad = n => n.toString().padStart(2, '0')
  const today = new Date()
  const sameDay = d.toDateString() === today.toDateString()
  if (sameDay) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const sameYear = d.getFullYear() === today.getFullYear()
  if (sameYear) return `${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function formatTimeShort(t) {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const today = new Date()
  const pad = n => n.toString().padStart(2, '0')
  const sameDay = d.toDateString() === today.toDateString()
  if (sameDay) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const diff = Math.floor((today - d) / 86400000)
  if (diff === 1) return '昨天'
  if (diff < 7) return `${diff}天前`
  return `${d.getMonth()+1}/${d.getDate()}`
}

function formatDate(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return t
  return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
}

function initialOf(obj) {
  const n = obj?.globalName || obj?.username || obj?.friendName || obj?.friendNickname || obj?.friendUsername || obj?.friendGlobalName || '?'
  return String(n).charAt(0).toUpperCase()
}

function getAvatar(obj) {
  return obj?.avatarUrl || obj?.friendAvatarUrl || ''
}

function initialOfByMsg(msg, direction) {
  if (direction === 'out') {
    const accId = conversations.currentConversation?.discordAccountId || conversations.currentConversation?.accountId
    const acc = accounts.getAccountById(accId)
    const name = acc?.name || acc?.nickname || acc?.discordName || acc?.globalName || acc?.username || auth.agent?.username || 'A'
    return name.charAt(0).toUpperCase()
  }
  const convName = conversations.currentConversation?.globalName || conversations.currentConversation?.username || conversations.currentConversation?.friendName
  const n = msg?.senderName || convName || '?'
  return String(n).charAt(0).toUpperCase()
}

function getAvatarByMsg(msg, direction) {
  if (direction === 'out') {
    const accId = conversations.currentConversation?.discordAccountId || conversations.currentConversation?.accountId
    const acc = accounts.getAccountById(accId)
    if (acc?.avatarUrl) return acc.avatarUrl
    return auth.agent?.avatarUrl || ''
  }
  return msg?.senderAvatarUrl || conversations.currentConversation?.avatarUrl || conversations.currentConversation?.friendAvatarUrl || ''
}

function senderNameOf(msg) {
  if (msg.direction === 'OUTBOUND') {
    const accId = conversations.currentConversation?.discordAccountId || conversations.currentConversation?.accountId
    const acc = accounts.getAccountById(accId)
    return acc?.name || acc?.discordName || acc?.nickname || acc?.globalName || acc?.username || msg.senderName || '客服'
  }
  const convName = conversations.currentConversation?.globalName || conversations.currentConversation?.username || conversations.currentConversation?.friendName
  return msg.senderName || convName || '客户'
}

function displayContentOf(msg) {
  if (msg?.messageType === 'voice') return ''
  if (msg.direction === 'OUTBOUND') return msg.translatedContent || msg.content || ''
  return msg.translatedContent || msg.content || ''
}

function isVoiceMsg(msg) {
  return msg?.messageType === 'voice' || msg?.audioUrl || msg?.audioData
}

function voiceSrc(msg) {
  // 优先用后端下载并转存的 base64（同源 data URL，不会被 CDN CORS/Referer 拦截）
  if (msg?.audioData) {
    const mime = msg.audioMimeType || 'audio/webm'
    return `data:${mime};base64,${msg.audioData}`
  }
  // 兜底：直接走 CDN URL（浏览器如果 CORS 失败会走 audio 的 onerror 回落到 placeholder）
  if (msg?.audioUrl) return msg.audioUrl
  return ''
}

function hasOriginal(msg) {
  return !!(msg.translatedContent && msg.content && msg.translatedContent !== msg.content)
}

function originalContentOf(msg) { return msg.content || '' }

function toggleOriginal(msgId) {
  if (originalExpandedSet.value[msgId]) delete originalExpandedSet.value[msgId]
  else originalExpandedSet.value[msgId] = true
}

function canTranslateInbound(msg) {
  if (msg.direction !== 'INBOUND') return false
  // 语音消息: 占位文本「[语音消息]」不提供翻译操作（要翻译文字的话走语音转文字后再翻）
  if (isVoiceMsg(msg)) return false
  if (msg.translatedContent && msg.translatedContent !== msg.content) return false
  if (msg.userTranslated) return false
  return !containsChinese(msg.content || '')
}

// 语言代码映射
const LANGUAGE_NAMES = {
  'zh': '中文', 'zh-cn': '中文', 'en': '英文', 'ja': '日文', 'ko': '韩文',
  'fr': '法文', 'de': '德文', 'es': '西班牙文', 'pt': '葡萄牙文', 'ru': '俄文',
  'it': '意大利文', 'ar': '阿拉伯文', 'th': '泰文', 'vi': '越南文'
}

function getLanguageName(langCode) {
  if (!langCode) return '未知'
  return LANGUAGE_NAMES[langCode.toLowerCase()] || langCode
}

async function translateMsg(msg) {
  if (translatingSet.value[msg.id]) return
  translatingSet.value[msg.id] = true
  try {
    const res = await translateMessage(conversations.currentConversationId, msg.id, targetLang.value)
    if (res?.translatedContent) {
      msg.translatedContent = res.translatedContent
      msg.userTranslated = res.translatedContent
      if (res.language) {
        msg.language = res.language
      }
    } else {
      msg.userTranslated = '翻译结果为空'
    }
  } catch (e) {
    ElMessage.error('翻译失败')
  } finally {
    delete translatingSet.value[msg.id]
  }
}

/* ====== 语音转文字(ASR) 相关 ====== */

function isAsrPending(msg) {
  return msg?.asrStatus === 'pending' || asrLoadingSet.value[msg.id]
}
function asrFailed(msg) {
  return msg?.asrStatus === 'failed' && !msg?.asrText
}
function hasAsrText(msg) {
  return !!(msg?.asrText && msg.asrText.trim())
}
function hasAsrTranslated(msg) {
  return !!(msg?.asrTranslated && msg.asrTranslated.trim())
}
function hasAsrTranslatedDifferent(msg) {
  return !!(msg?.asrTranslated && msg.asrTranslated.trim() && msg.asrTranslated.trim() !== msg.asrText?.trim())
}
function hasAsrResult(msg) {
  return hasAsrText(msg) || hasAsrTranslated(msg)
}
function hasAsrTextOnly(msg) {
  return hasAsrText(msg) && !hasAsrTranslated(msg)
}
function canShowOriginalBelow(msg) {
  // 有译文又有原文且两者不同时，才允许在下方展开显示原文
  return hasAsrText(msg) && hasAsrTranslatedDifferent(msg)
}

function toggleOriginalBelow(msg) {
  if (originalBelowExpanded.value[msg.id]) {
    delete originalBelowExpanded.value[msg.id]
  } else {
    originalBelowExpanded.value[msg.id] = true
  }
}

/**
 * 好友发来的语音消息（INBOUND）默认下面直接显示 ASR 卡片（转文字+译文或原文）；
 * 自己发出去的语音（OUTBOUND）只有当：
 *   - 已有转写结果 / pending / failed
 *   - 或用户手动点击"转文字"后 set asrLoadingSet 让卡片先显示出来
 * 才会出来，满足"自己发出去的保持原样"的要求。
 */
function shouldShowAsrCard(msg) {
  if (!isVoiceMsg(msg)) return false
  if (msg.direction === 'INBOUND') return true
  // OUTBOUND：只有当存在结果或明确进行中时才展示
  if (isAsrPending(msg)) return true
  if (hasAsrResult(msg)) return true
  if (asrFailed(msg)) return true
  return false
}

function asrCardTitle(msg) {
  if (isAsrPending(msg)) return '语音转文字中…'
  if (asrFailed(msg)) return '转文字失败'
  if (hasAsrResult(msg)) return '语音转文字'
  return '语音转文字'
}

function asrStatusIconClass(msg) {
  if (isAsrPending(msg)) return 'asr-ic asr-ic-pending'
  if (asrFailed(msg)) return 'asr-ic asr-ic-fail'
  return 'asr-ic asr-ic-done'
}

function asrDisplayText(msg) {
  // 优先显示译文，没有译文时显示原文
  if (hasAsrTranslated(msg)) return msg.asrTranslated
  if (hasAsrText(msg)) return msg.asrText
  return ''
}

/** 手动触发一次 ASR（对 INBOUND 自动同时翻译为中文） */
async function triggerAsr(msg, autoTranslate) {
  const id = msg.id
  if (asrLoadingSet.value[id]) return
  asrLoadingSet.value[id] = true
  try {
    const res = await transcribeVoiceAsr(conversations.currentConversationId, id, !!autoTranslate)
    // 把接口返回值（最新的 asr 字段）合并回 msg 原对象
    if (res) applyAsrFields(msg, res)
  } catch (e) {
    if (asrFailed(msg)) { /* 保留后端返回的错误 */ }
    ElMessage.warning(e?.response?.data?.message || e?.message || '转文字失败')
  } finally {
    delete asrLoadingSet.value[id]
  }
}

/** 对已转写出来的 asrText 触发单独翻译（INBOUND 默认译中文，目标语言用全局翻译框） */
async function triggerTranslateAsr(msg) {
  const key = msg.id + '|tr'
  if (asrLoadingSet.value[key]) return
  asrLoadingSet.value[key] = true
  try {
    const targetLangCode = targetLang.value === 'zh' ? 'zh-CN' : targetLang.value
    const res = await translateAsrText(conversations.currentConversationId, msg.id, targetLangCode)
    if (res) applyAsrFields(msg, res)
  } catch (e) {
    ElMessage.error('ASR翻译失败')
  } finally {
    delete asrLoadingSet.value[key]
  }
}

/** 把接口返回的 MessageDto 中关于 asr 的字段写回到当前消息对象 */
function applyAsrFields(msg, dto) {
  if (dto.asrText != null) msg.asrText = dto.asrText
  if (dto.asrTranslated != null) msg.asrTranslated = dto.asrTranslated
  if (dto.asrLanguage != null) msg.asrLanguage = dto.asrLanguage
  if (dto.asrStatus != null) msg.asrStatus = dto.asrStatus
  if (dto.asrError != null) msg.asrError = dto.asrError
  // 兼容：WebSocket 推过来的可能没带上面字段但直接有 translated / content 更新，也接收
  if (dto.translatedContent != null) msg.translatedContent = dto.translatedContent
  if (dto.content != null) msg.content = dto.content
}

function insertEmoji(e) {
  inputText.value += e
}

function parseAttachments(msg) {
  if (!msg.attachments) return []
  if (Array.isArray(msg.attachments)) return msg.attachments
  if (typeof msg.attachments === 'string') {
    try { return JSON.parse(msg.attachments) } catch (e) { return [] }
  }
  return []
}

function parseReactions(msg) {
  if (!msg.reactions) return []
  if (Array.isArray(msg.reactions)) return msg.reactions
  if (typeof msg.reactions === 'string') {
    try {
      const obj = JSON.parse(msg.reactions)
      return Object.entries(obj).map(([emoji, count]) => ({ emoji, count }))
    } catch (e) { return [] }
  }
  return []
}

function isImage(filename) {
  if (!filename) return false
  return /\.(jpg|jpeg|png|gif|webp|bmp|svg)$/i.test(filename)
}

function toggleAttachment() {
  fileInputRef.value?.click()
}

async function onFileSelect(e) {
  const files = Array.from(e.target.files || [])
  for (const file of files) {
    try {
      const res = await uploadAttachment(file)
      if (res?.success) {
        pendingAttachments.value.push({
          name: res.filename || file.name,
          url: res.url,
          size: res.size,
          contentType: res.contentType
        })
      }
    } catch (err) {
      pendingAttachments.value.push({
        name: file.name,
        url: URL.createObjectURL(file),
        size: file.size,
        contentType: file.type,
        local: true
      })
    }
  }
  e.target.value = ''
}

async function addReactionEmoji(msg, emoji, remove = false) {
  try {
    const res = await addReactionApi(conversations.currentConversationId, msg.id, emoji, remove)
    if (res) {
      msg.reactions = res.reactions
    }
  } catch (e) {
    ElMessage.error('Reaction操作失败')
  }
}

function replyTo(msg) {
  replyToMsg.value = msg
  ElMessage.success('已设置回复引用')
}

function cancelReply() {
  replyToMsg.value = null
}

function jumpToMessage(msgId) {
  ElMessage.info('跳转到消息 #' + msgId)
  const target = conversations.currentMessages.find(m => m.id === msgId)
  if (target) {
    ElMessage.success('已定位到引用消息')
  }
}

function editMsg(msg) {
  editTargetMsg.value = msg
  editText.value = msg.content || ''
  ElMessageBox.prompt('编辑消息内容', '编辑消息', {
    inputValue: msg.content || '',
    inputType: 'textarea'
  }).then(async ({ value }) => {
    if (!value?.trim()) return
    try {
      const res = await editMessageApi(conversations.currentConversationId, msg.id, value)
      if (res) {
        msg.content = res.content
        msg.translatedContent = res.translatedContent
        msg.editedAt = res.editedAt
        ElMessage.success('消息已更新')
      }
    } catch (e) {
      ElMessage.error('编辑失败')
    }
    editTargetMsg.value = null
  }).catch(() => { editTargetMsg.value = null })
}

function copyMsg(msg) {
  navigator.clipboard?.writeText(msg.content || '').then(() => {
    ElMessage.success('已复制')
  }).catch(() => ElMessage.info('复制失败'))
}

async function deleteMsg(msg) {
  try {
    await ElMessageBox.confirm('确定删除这条消息？', '提示', { type: 'warning' })
    const res = await deleteMessageApi(conversations.currentConversationId, msg.id)
    if (res) {
      msg.isDeleted = true
      ElMessage.success('消息已删除')
    }
  } catch (e) { /* cancelled */ }
}

function toggleAiPanel() {
  showAiPanel.value = !showAiPanel.value
  if (showAiPanel.value) loadAiSuggestions()
}

async function loadAiSuggestions() {
  if (!conversations.currentConversationId) return
  aiLoading.value = true
  try {
    const res = await getAiSuggestions(conversations.currentConversationId, aiTone.value, 3)
    aiSuggestions.value = Array.isArray(res) ? res : []
  } catch (e) {
    ElMessage.error('加载AI建议失败')
  } finally {
    aiLoading.value = false
  }
}

function applyAiSuggestion(s) {
  inputText.value = s.text || s.translated || ''
  showAiPanel.value = false
  ElMessage.success('已填入建议回复')
}

async function selectConversation(c) {
  conversations.selectConversation(c.id)
  conversations.markAsRead(c.id)
  replyToMsg.value = null
  // 每次打开会话都强制只加载当天 20 条（避免缓存了历史消息时出现"打开看到非今天"、页面卡顿）
  // 只有用户主动向上滚动，才会触发 loadMore() 拉取更早一页
  await conversations.fetchMessages(c.id)
  if (showProfile.value) {
    await loadUserProfile(c)
  }
  // 重置 lastCount：避免"旧会话 lastCount>0 新会话 currentMessages.length<旧值"导致 watcher 不再触发自动滚动
  lastCount = (conversations.currentMessages || []).length
  // 连续 3 帧重试：兼容消息条目渲染/异步资源高度变化后仍能滚到真正的底部
  const forceBottom = () => scrollToBottom({ force: true, retries: 0 })
  await nextTick()
  forceBottom()
  setTimeout(forceBottom, 80)
  setTimeout(forceBottom, 220)
}

async function loadUserProfile(c) {
  const userId = c?.friendDiscordUserId || c?.discordUserId
  if (!userId) return
  profileLoading.value = true
  try {
    userProfile.value = await getUserProfile(userId)
    remarkEdit.value = conversations.currentConversation?.remark || ''
  } catch (e) {
    userProfile.value = null
  } finally {
    profileLoading.value = false
  }
}

async function saveRemark() {
  if (!conversations.currentConversation) return
  try {
    await conversations.updateRemark(conversations.currentConversation.id, remarkEdit.value)
    ElMessage.success('备注已更新')
  } catch (e) {
    ElMessage.error('更新失败')
  }
}

async function onStageChange(newStage) {
  if (!conversations.currentConversation) return
  try {
    await conversations.updateStage(conversations.currentConversation.id, newStage)
    ElMessage.success('阶段已更新')
  } catch (e) {
    ElMessage.error('更新失败')
  }
}

async function updateStageSilently(stage) {
  if (!conversations.currentConversation) return
  try {
    await conversations.updateStage(conversations.currentConversation.id, stage)
  } catch (e) {}
}

async function togglePin(conv) {
  const target = conv || conversations.currentConversation
  if (!target) return
  const newPinned = !target.pinned
  try {
    await conversations.updatePin(target.id, newPinned)
    target.pinned = newPinned
    ElMessage.success(newPinned ? '已置顶' : '已取消置顶')
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

function addTag() {
  if (!newTag.value.trim()) return
  const current = userProfile.value?.tags || ''
  const tags = current ? current.split(',').map(t => t.trim()).filter(Boolean) : []
  if (!tags.includes(newTag.value.trim())) {
    tags.push(newTag.value.trim())
    updateUserProfile(userProfile.value.id, { tags: tags.join(',') }).then(() => {
      ElMessage.success('标签已添加')
    }).catch(() => ElMessage.error('添加失败'))
  }
  newTag.value = ''
}

function removeTag(t) {
  const current = userProfile.value?.tags || ''
  const tags = current ? current.split(',').map(x => x.trim()).filter(Boolean) : []
  const filtered = tags.filter(x => x !== t)
  updateUserProfile(userProfile.value.id, { tags: filtered.join(',') }).then(() => {
    ElMessage.success('标签已移除')
  }).catch(() => ElMessage.error('移除失败'))
}

async function refreshMessages() {
  if (!conversations.currentConversationId) return
  await conversations.fetchMessages(conversations.currentConversationId)
  await nextTick()
  scrollToBottom({ force: true })
}

async function loadMore() {
  if (loadingMore.value) return
  loadingMore.value = true
  try {
    const wrapEl = getScrollWrapEl()
    const prevTop = wrapEl ? wrapEl.scrollTop : (msgScrollRef.value?.scrollTop ?? 0)
    const prevHeight = wrapEl ? wrapEl.scrollHeight : 0
    const added = await conversations.loadMore(conversations.currentConversationId)
    if (added && added.length > 0) {
      // 上滑加载历史后保持滚动位置，不让用户跳到刚才看的位置
      await nextTick()
      const wrapNow = getScrollWrapEl()
      const newHeight = wrapNow ? wrapNow.scrollHeight : 0
      const delta = newHeight - prevHeight
      if (delta > 0 && wrapNow) {
        // 直接操作 DOM，避免 element-plus setScrollTop 在某些情况下不生效
        try {
          wrapNow.scrollTop = prevTop + delta
        } catch (e) {}
        try {
          if (msgScrollRef.value && typeof msgScrollRef.value.setScrollTop === 'function') {
            msgScrollRef.value.setScrollTop(prevTop + delta)
          }
        } catch (e) {}
      }
    }
  } finally {
    // 冷却 1 帧，避免 setScrollTop 后仍触发 onMsgScroll(top<120) 再次 loadMore
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        loadingMore.value = false
      })
    })
  }
}

let scrollTickPending = false
function onMsgScroll() {
  if (scrollTickPending) return
  scrollTickPending = true
  requestAnimationFrame(() => {
    scrollTickPending = false
    const wrapEl = getScrollWrapEl()
    if (!wrapEl) return
    const top = wrapEl.scrollTop
    if (top < 120 && hasMore.value && !loadingMore.value && !currentLoading.value) {
      loadMore()
    }
  })
}

/** 返回 el-scrollbar 的滚动容器 DOM（兼容写法） */
function getScrollWrapEl() {
  if (!msgScrollRef.value) return null
  return msgScrollRef.value.wrapRef?.$el
    || msgScrollRef.value.$el?.querySelector?.('.el-scrollbar__wrap')
    || null
}

/** 当前滚动位置是否处于底部附近（threshold px） */
function isNearBottom(threshold = 120) {
  const wrapEl = getScrollWrapEl()
  if (!wrapEl) return false
  const { scrollTop, scrollHeight, clientHeight } = wrapEl
  return scrollHeight - scrollTop - clientHeight <= threshold
}

async function scrollToBottom({ force = false, retries = 0 } = {}) {
  await nextTick()
  if (!msgScrollRef.value) {
    if (retries < 3) {
      setTimeout(() => scrollToBottom({ force, retries: retries + 1 }), 60)
    }
    return
  }
  // force=false 时：只在用户本来就已经靠近底部时才自动滚到底，避免打断用户阅读历史 / 听语音
  if (!force && !isNearBottom()) return
  // 三重保险：1) anchor scrollIntoView；2) 直接操作 wrap DOM；3) setScrollTop API
  try {
    const anchor = scrollAnchor?.value
    if (anchor && typeof anchor.scrollIntoView === 'function') {
      anchor.scrollIntoView({ block: 'end', behavior: 'auto' })
    }
  } catch (e) {}
  try {
    const wrapEl = getScrollWrapEl()
    if (wrapEl) {
      wrapEl.scrollTop = wrapEl.scrollHeight
    }
  } catch (e) {}
  try { msgScrollRef.value.setScrollTop(99999999) } catch (e) {}
  // force=true：再补 2 次重试（渲染后期图片/音频条可能改变高度，避免第一次只滚到半截）
  if (force && retries < 2) {
    setTimeout(() => scrollToBottom({ force: true, retries: retries + 1 }), 120)
  }
}

function onInputKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  } else if (e.altKey && e.key.toLowerCase() === 'w') {
    e.preventDefault()
    ElMessage.info('正在批量翻译...')
    const inboundMsgs = conversations.currentMessages.filter(m => m.direction === 'INBOUND' && canTranslateInbound(m))
    for (const m of inboundMsgs) {
      translateMsg(m)
    }
  } else if (e.altKey && e.key.toLowerCase() === 'e') {
    e.preventDefault()
    ElMessageBox.prompt('输入漏斗阶段 (PROSPECT/NEW/CONVERTED/CHURNED/ARCHIVED)',
      '快速修改漏斗', { inputValue: currentStage.value || '' }).then(({ value }) => {
      if (value) updateStageSilently(value.toUpperCase())
    }).catch(() => {})
  } else if (e.altKey && e.key.toLowerCase() === 'r') {
    e.preventDefault()
    toggleAiPanel()
  }
}

async function send() {
  const text = inputText.value.trim()
  if (!text && !replyToMsg.value && pendingAttachments.value.length === 0) return
  if (!conversations.currentConversationId) {
    ElMessage.warning('请选择会话')
    return
  }
  sending.value = true
  try {
    let content = text
    if (replyToMsg.value && !text) {
      content = `[回复] ${replyToMsg.value.content || ''}`
    } else if (replyToMsg.value) {
      content = text
    }
    if (pendingAttachments.value.length > 0) {
      const attachmentDesc = pendingAttachments.value.map(a => `[附件:${a.name}]`).join(' ')
      content = (content ? content + ' ' : '') + attachmentDesc
    }
    if (replyToMsg.value && replyToMsg.value.id) {
      await replyMessageApi(conversations.currentConversationId, replyToMsg.value.id, content, targetLang.value)
      replyToMsg.value = null
    } else {
      await conversations.send(conversations.currentConversationId, content, targetLang.value)
    }
    inputText.value = ''
    pendingAttachments.value = []
    replyToMsg.value = null
    conversations.markCurrentAsRead()
    await nextTick()
    // 自己发送完成：强制滚到底部，看到刚发出去的消息
    scrollToBottom({ force: true })
  } catch (e) {
    const errorMsg = e?.response?.data?.message || e?.message || '发送失败'
    ElMessage.error(errorMsg)
  } finally {
    sending.value = false
  }
}

let lastCount = 0
watch(() => conversations.currentMessages.length, (cnt) => {
  if (cnt > lastCount) scrollToBottom()
  lastCount = cnt
  // 更新检测语言
  updateDetectedLang()
})

watch(() => conversations.currentConversationId, (newId, oldId) => {
  replyToMsg.value = null
  userChangedTargetLang = false
  if (showProfile.value && conversations.currentConversation) {
    loadUserProfile(conversations.currentConversation)
  }
  updateDetectedLang()
  if (newId && (conversations.currentMessages || []).length > 0) {
    nextTick().then(() => scrollToBottom({ force: true }))
  }
  lastCount = (conversations.currentMessages || []).length
  nextTick().then(() => autoTriggerPendingAsr())
})

// 轮询或 WebSocket 更新消息后，自动触发未处理的语音消息 ASR
watch(() => conversations.currentMessages?.length, (newLen, oldLen) => {
  if (newLen && newLen !== oldLen) {
    nextTick().then(() => autoTriggerPendingAsr())
  }
})

/** 扫描已加载的 INBOUND 语音消息，对没有 ASR 数据且非 pending 的自动触发转写 */
function autoTriggerPendingAsr() {
  const msgs = conversations.currentMessages
  if (!msgs || msgs.length === 0) return
  for (const msg of msgs) {
    if (!isVoiceMsg(msg)) continue
    if (msg.direction !== 'INBOUND') continue
    if (isAsrPending(msg)) continue
    if (hasAsrResult(msg)) continue
    if (asrFailed(msg)) continue
    triggerAsr(msg, true)
  }
}

function updateDetectedLang() {
  const msgs = conversations.currentMessages
  if (!msgs || msgs.length === 0) {
    detectedLang.value = '未知'
    return
  }
  // 优先使用最近一条入站消息的检测语言
  let detectedCode = null
  for (let i = msgs.length - 1; i >= 0; i--) {
    const msg = msgs[i]
    if (msg.direction === 'INBOUND' && msg.language && msg.language !== 'unknown') {
      detectedCode = msg.language
      break
    }
  }
  // 如果没有检测到，使用启发式检测
  if (!detectedCode) {
    const lastMsg = msgs[msgs.length - 1]
    if (lastMsg?.content) {
      detectedCode = heuristicDetectLang(lastMsg.content)
    }
  }
  if (detectedCode) {
    detectedLang.value = getLanguageName(detectedCode)
    // 如果用户没有手动修改目标语言，则自动跟随检测到的语言
    if (!userChangedTargetLang) {
      targetLang.value = detectedCode
    }
  } else {
    detectedLang.value = '未知'
  }
}

function onTargetLangChange() {
  userChangedTargetLang = true
}

function heuristicDetectLang(text) {
  if (!text) return null
  const zhRegex = /[\u4e00-\u9fa5]/
  const jaRegex = /[\u3040-\u309F]/
  const koRegex = /[\uAC00-\uD7AF]/
  if (koRegex.test(text)) return 'ko'
  if (jaRegex.test(text) && zhRegex.test(text)) return 'ja'
  if (zhRegex.test(text)) return 'zh'
  if (/[\u0600-\u06FF]/.test(text)) return 'ar'
  if (/[\u0E00-\u0E7F]/.test(text)) return 'th'
  return 'en'
}

let pollTimer = null
let lastPollCount = 0

/** 把两组消息按 msg.id / discordMessageId 合并去重，返回升序合并结果 */
function mergeMessageLists(olderOrCurrent, newerOrRemote) {
  const map = new Map()
  for (const m of olderOrCurrent || []) {
    const key = m.id != null ? `id:${m.id}` : `dmid:${m.discordMessageId || ''}`
    map.set(key, m)
  }
  for (const m of newerOrRemote || []) {
    const key = m.id != null ? `id:${m.id}` : `dmid:${m.discordMessageId || ''}`
    // 远端同 key：覆盖（因为 ASR/翻译字段可能更新了）
    map.set(key, m)
  }
  const merged = Array.from(map.values())
  merged.sort((a, b) => {
    const ta = new Date(a.discordCreatedAt || a.createdAt || 0).getTime()
    const tb = new Date(b.discordCreatedAt || b.createdAt || 0).getTime()
    return ta - tb || (a.id || 0) - (b.id || 0)
  })
  return merged
}

async function pollCurrentMessages() {
  const convId = conversations.currentConversationId
  if (!convId) return
  // 正在加载更多历史时不要 poll 覆盖 messagesMap，避免游标回滚 / 闪烁
  if (loadingMore.value) return
  try {
    // 轮询也使用只返回当天 20 条的分页接口，避免页面卡顿
    const res = await listMessages(convId, { daysBack: 1, pageSize: 20 })
    const page = !Array.isArray(res) && res && (Array.isArray(res?.messages) || Array.isArray(res?.data)) ? res : null
    const msgs = page ? (res.messages || res.data || []) : (Array.isArray(res) ? res : (res?.data || []))
    msgs.sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0))
    const current = conversations.messagesMap[convId] || []
    const todayStart = new Date()
    todayStart.setHours(0, 0, 0, 0)

    const newestRemote = msgs.length > 0 ? msgs[msgs.length - 1] : null
    const newestLocal = current.length > 0 ? current[current.length - 1] : null
    const remoteNewerTs = newestRemote ? new Date(newestRemote.createdAt || 0).getTime() : 0
    const localNewerTs = newestLocal ? new Date(newestLocal.createdAt || 0).getTime() : 0
    const hasOldInLocal = current.length > 0 && current.some(m => new Date(m.discordCreatedAt || m.createdAt || 0) < todayStart)

    // 计算远端页面内最早和最晚时间，用于判断游标是否需要更新
    const remoteOldestTs = msgs.length > 0 ? new Date(msgs[0].discordCreatedAt || msgs[0].createdAt || 0).getTime() : Infinity
    const existingCursor = conversations.oldestCursorMap?.[convId]
    const existingCursorTs = existingCursor?.createdAt
      ? new Date(existingCursor.createdAt).getTime()
      : Infinity

    const hasNewRemoteMessage = newestRemote && (!newestLocal || remoteNewerTs > localNewerTs)
    const fieldUpdated = (() => {
      // ASR/翻译字段变化（比如 pending→done、翻译文本补填）也需要更新
      if (current.length === 0 || msgs.length === 0) return false
      const localMap = new Map(current.map(m => [m.id != null ? `id:${m.id}` : `dmid:${m.discordMessageId || ''}`, m]))
      return msgs.some(r => {
        const key = r.id != null ? `id:${r.id}` : `dmid:${r.discordMessageId || ''}`
        const l = localMap.get(key)
        if (!l) return false
        return (l.asrStatus || '') !== (r.asrStatus || '')
          || (l.asrText || '') !== (r.asrText || '')
          || (l.translatedContent || '') !== (r.translatedContent || '')
      })
    })()

    // 情况 A：本地已经加载过历史（含非今天）→ 不允许用轮询返回的"当天页"覆盖整个 messagesMap，只做增量合并
    if (!hasOldInLocal && current.length === 0 && msgs.length === 0) {
      // 完全空，无需处理
      return
    }

    if (!hasOldInLocal && !hasNewRemoteMessage && !fieldUpdated && msgs.length <= current.length) {
      // 没有新消息，也没有字段更新，直接跳过
      return
    }

    const conv = conversations.conversations.find(c => c.id === convId)
    if (newestRemote) {
      if (conv) {
        conv.lastMessagePreview = (newestRemote.translatedContent || newestRemote.content || '').slice(0, 60)
        conv.lastMessageSnippet = conv.lastMessagePreview
        conv.lastMessageAt = newestRemote.createdAt
        conv.lastMessageTime = newestRemote.createdAt
      }
    }

    if (hasOldInLocal || hasNewRemoteMessage || fieldUpdated) {
      // 合并：保留本地已加载的历史（包括 loadMore 加载的更早页），远端当天页更新对应 ID 的字段
      const merged = mergeMessageLists(current, msgs)

      // 过滤：如果 merged 里 50% 以上是非今天，说明用户一直在看历史，不应把它们删掉
      conversations.messagesMap[convId] = merged

      // 游标规则：轮询绝不允许把游标推得更晚（只能向前=更早推进，或保持不动）
      const cursorFromPage = (page?.oldestId != null && page?.oldestCreatedAt)
        ? { createdAt: page.oldestCreatedAt, id: page.oldestId }
        : (msgs.length > 0
          ? { createdAt: msgs[0].discordCreatedAt || msgs[0].createdAt, id: msgs[0].id }
          : null)
      if (cursorFromPage) {
        const pageCursorTs = new Date(cursorFromPage.createdAt).getTime()
        if (!existingCursor || pageCursorTs < existingCursorTs) {
          // 只有新游标更早（向前推进）才更新
          conversations.oldestCursorMap = conversations.oldestCursorMap || {}
          conversations.oldestCursorMap[convId] = cursorFromPage
        }
        // 否则：保留 existingCursor（更老），避免被回滚到当天 → 触发反复 loadMore
      }
      if (merged.length > 0) {
        // earliestIdMap 保持为 merged[0]（整页最早），用于兼容老接口
        conversations.earliestIdMap[convId] = merged[0].id
      }
      // hasMore：如果本地已有更早消息（merged[0].createdAt < todayStart），那肯定有更多
      const mergedOldestTs = merged.length > 0
        ? new Date(merged[0].discordCreatedAt || merged[0].createdAt || 0).getTime()
        : Infinity
      const backendHasMore = page ? !!res.hasMore : msgs.length >= 20
      conversations.hasMoreMap[convId] = backendHasMore
        || mergedOldestTs < todayStart.getTime()
        || backendHasMore

      await nextTick()
      // 轮询更新消息时，只在用户本来就已经在底部附近时才自动滚到底，避免打断听语音/看历史
      scrollToBottom({ force: false })
    }
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn('[pollCurrentMessages] error', e)
  }
}

let convPollTimer = null
/**
 * 动态无刷新技术 - 静默刷新会话列表
 * 不显示loading，在后台静默更新数据
 */
async function pollConversations() {
  try {
    // 使用 silent=true 静默刷新，不显示loading
    await conversations.fetchConversations({}, true)
  } catch (e) {
    // 静默刷新失败不提示用户
  }
}

// ========= 头部菜单命令 =========
async function onHeaderCommand(cmd) {
  if (!conversations.currentConversationId) {
    ElMessage.warning('请先选择一个会话')
    return
  }
  if (cmd === 'assign') {
    assignForm.agentId = null
    assignDialogVisible.value = true
    await loadAgents()
  } else if (cmd === 'transfer') {
    transferForm.agentId = null
    transferForm.reason = ''
    transferDialogVisible.value = true
    await loadAgents()
  } else if (cmd === 'tags') {
    tagDialogVisible.value = true
    await loadTagsData()
  } else if (cmd === 'pin') {
    await togglePin()
  } else if (cmd === 'template') {
    templateDialogVisible.value = true
    templateCategory.value = ''
    await loadTemplateCategories()
    await loadTemplates()
  }
}

async function loadAgents() {
  agentsLoading.value = true
  try {
    const res = await listAvailableAgents()
    agents.value = Array.isArray(res) ? res : []
  } catch (e) {
    ElMessage.error('加载客服列表失败')
  } finally {
    agentsLoading.value = false
  }
}

async function submitAssign() {
  if (!assignForm.agentId) {
    ElMessage.warning('请选择客服')
    return
  }
  assignSubmitting.value = true
  try {
    await assignToAgent(conversations.currentConversationId, assignForm.agentId)
    ElMessage.success('已分配给客服')
    assignDialogVisible.value = false
    // 动态无刷新技术：静默刷新，不显示loading
    await conversations.fetchConversations({}, true)
  } catch (e) {
    ElMessage.error('分配失败')
  } finally {
    assignSubmitting.value = false
  }
}

async function submitTransfer() {
  if (!transferForm.agentId) {
    ElMessage.warning('请选择目标客服')
    return
  }
  transferSubmitting.value = true
  try {
    await transferConversation(conversations.currentConversationId, transferForm.agentId, transferForm.reason)
    ElMessage.success('会话已转移')
    transferDialogVisible.value = false
    // 动态无刷新技术：静默刷新，不显示loading
    await conversations.fetchConversations({}, true)
  } catch (e) {
    ElMessage.error('转移失败')
  } finally {
    transferSubmitting.value = false
  }
}

async function loadTagsData() {
  try {
    const tags = await getConversationTags(conversations.currentConversationId)
    currentTags.value = Array.isArray(tags) ? tags : []
  } catch (e) {
    currentTags.value = []
  }
  try {
    const names = await listTagNamesApi()
    tagNamesFromServer.value = Array.isArray(names) ? names : []
  } catch (e) {
    tagNamesFromServer.value = []
  }
}

async function addNewTag() {
  const name = newTagName.value.trim()
  if (!name) return
  if (currentTags.value.some(t => t.name === name)) {
    ElMessage.warning('标签已存在')
    return
  }
  try {
    const res = await addConversationTags(conversations.currentConversationId, [name], newTagColor.value)
    currentTags.value = Array.isArray(res) ? res : []
    newTagName.value = ''
    ElMessage.success('标签已添加')
  } catch (e) {
    ElMessage.error('添加失败')
  }
}

async function addExistingTag(name) {
  if (currentTags.value.some(t => t.name === name)) {
    ElMessage.info('标签已存在')
    return
  }
  try {
    const res = await addConversationTags(conversations.currentConversationId, [name], newTagColor.value)
    currentTags.value = Array.isArray(res) ? res : []
    ElMessage.success('标签已添加')
  } catch (e) {
    ElMessage.error('添加失败')
  }
}

async function removeCurrentTag(tag) {
  try {
    const res = await removeConversationTag(conversations.currentConversationId, tag.id)
    currentTags.value = Array.isArray(res) ? res : []
    ElMessage.success('标签已移除')
  } catch (e) {
    ElMessage.error('移除失败')
  }
}

async function loadTemplateCategories() {
  try {
    const res = await getTemplateCategories()
    templateCategories.value = Array.isArray(res) ? res : []
  } catch (e) {}
}

async function loadTemplates() {
  templateDialogLoading.value = true
  try {
    const res = await listMessageTemplates(templateCategory.value || undefined)
    templateList.value = Array.isArray(res) ? res : []
  } catch (e) {
    ElMessage.error('加载模板失败')
  } finally {
    templateDialogLoading.value = false
  }
}

function useTemplate(tpl) {
  if (tpl?.content) {
    inputText.value = tpl.content
    templateDialogVisible.value = false
    ElMessage.success('已应用模板')
  }
}

function openTemplateCreate() {
  templateEditId.value = null
  templateEditForm.title = ''
  templateEditForm.category = ''
  templateEditForm.content = ''
  templateEditVisible.value = true
}

function editTemplateItem(tpl) {
  templateEditId.value = tpl.id
  templateEditForm.title = tpl.title || ''
  templateEditForm.category = tpl.category || ''
  templateEditForm.content = tpl.content || ''
  templateEditVisible.value = true
}

async function saveTemplateEdit() {
  if (!templateEditForm.content?.trim()) {
    ElMessage.warning('模板内容不能为空')
    return
  }
  try {
    if (templateEditId.value) {
      await updateMessageTemplate(templateEditId.value, { ...templateEditForm })
      ElMessage.success('模板已更新')
    } else {
      await createMessageTemplate({ ...templateEditForm })
      ElMessage.success('模板已创建')
    }
    templateEditVisible.value = false
    await loadTemplates()
    await loadTemplateCategories()
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

async function deleteTemplateItem(tpl) {
  try {
    await ElMessageBox.confirm(`确定删除模板"${tpl.title || tpl.content?.slice(0, 20)}"？`, '提示', { type: 'warning' })
    await deleteMessageTemplate(tpl.id)
    ElMessage.success('已删除')
    await loadTemplates()
  } catch (e) { /* cancelled */ }
}

function getFilteredSortedConversations() {
  let list = filteredConversations.value
  return [...list].sort((a, b) => {
    // 归档客户始终排最下面
    if (a.stage === 'ARCHIVED' && b.stage !== 'ARCHIVED') return 1
    if (a.stage !== 'ARCHIVED' && b.stage === 'ARCHIVED') return -1
    // 置顶优先
    if (a.pinned && !b.pinned) return -1
    if (!a.pinned && b.pinned) return 1
    // PROSPECT（通过客户）排在前面
    const aProspect = a.stage === 'PROSPECT'
    const bProspect = b.stage === 'PROSPECT'
    if (aProspect && !bProspect) return -1
    if (!aProspect && bProspect) return 1
    // 有最新消息的排前面
    const aHasMsg = !!a.lastMessageAt
    const bHasMsg = !!b.lastMessageAt
    if (aHasMsg && !bHasMsg) return -1
    if (!aHasMsg && bHasMsg) return 1
    // 有未读消息的排前面
    const unreadA = a.unreadCount || 0
    const unreadB = b.unreadCount || 0
    if (unreadA !== unreadB) return unreadB - unreadA
    // 按最后消息时间排序
    const timeA = a.lastMessageTime || a.lastMessageAt || a.updatedAt || a.createdAt || 0
    const timeB = b.lastMessageTime || b.lastMessageAt || b.updatedAt || b.createdAt || 0
    return new Date(timeB).getTime() - new Date(timeA).getTime()
  })
}

function getPinnedConversations() {
  return getFilteredSortedConversations().filter(c => c.pinned)
}

function getNormalConversations() {
  return getFilteredSortedConversations().filter(c => !c.pinned)
}

function detectLanguage(text) {
  if (!text) return '未知'
  if (/[\u4e00-\u9fa5]/.test(text)) return '中文'
  if (/[\u3040-\u30ff]/.test(text)) return '日语'
  if (/[\uac00-\ud7af]/.test(text)) return '韩语'
  return 'English'
}

function truncateText(text, maxLen = 8) {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}

function splitAgentName(name) {
  if (!name) return []
  const chars = Array.from(name)
  const maxLines = 3
  const lineChars = 2
  const maxChars = maxLines * lineChars
  if (chars.length > maxChars) {
    const keep = chars.slice(0, (maxLines - 1) * lineChars).join('')
    const lines = []
    for (let i = 0; i < keep.length; i += lineChars) {
      lines.push(keep.slice(i, i + lineChars))
    }
    lines.push('...')
    return lines
  }
  const lines = []
  for (let i = 0; i < chars.length; i += lineChars) {
    lines.push(chars.slice(i, i + lineChars).join(''))
  }
  return lines
}

// ===== 账号颜色（稳定 hash），用于区分账号指示点 =====
const ACCOUNT_PALETTE = [
  '#5865F2', '#EB459E', '#57F287', '#FEE75C', '#ED4245',
  '#9B59B6', '#1ABC9C', '#E67E22', '#2980B9', '#D35400'
]
const accountColorCache = new Map()
function accountColor(accountId) {
  if (accountId == null) return 'var(--color-primary)'
  const key = String(accountId)
  if (accountColorCache.has(key)) return accountColorCache.get(key)
  let hash = 0
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) >>> 0
  const color = ACCOUNT_PALETTE[hash % ACCOUNT_PALETTE.length]
  accountColorCache.set(key, color)
  return color
}

// ===== 好友在线状态（原生 Discord Presence + 活跃时间兜底） =====
function presenceClass(c) {
  const p = c?.friendPresence
  // 1. 有明确在线状态（online/idle/dnd）直接返回在线
  if (p === 'online' || p === 'idle' || p === 'dnd') return 'online'
  // 2. 即使 presence 显示 offline，如果最近 5 分钟内活跃过，也视为在线（兜底）
  const t = c?.friendLastActiveAt
  if (t) {
    const diff = Date.now() - new Date(t).getTime()
    if (diff < 5 * 60 * 1000) return 'online'
  }
  // 3. 明确 offline 且没有最近活跃
  if (p === 'offline') return 'offline'
  // 4. 都没有数据时兜底
  if (!t) return 'offline'
  const diff2 = Date.now() - new Date(t).getTime()
  return diff2 < 5 * 60 * 1000 ? 'online' : 'offline'
}

function presenceTitle(c) {
  const p = c?.friendPresence
  const map = { online: '在线', idle: '空闲', dnd: '请勿打扰', offline: '离线' }
  // 如果最近活跃，优先显示在线
  const t = c?.friendLastActiveAt
  if (t) {
    const diff = Date.now() - new Date(t).getTime()
    if (diff < 5 * 60 * 1000 && (p === 'offline' || !map[p])) return '在线'
  }
  if (p && map[p]) return map[p]
  if (!t) return '离线'
  const diff = Date.now() - new Date(t).getTime()
  if (diff < 5 * 60 * 1000) return '最近活跃'
  return '离线'
}

// ===== 语音录音辅助 =====
const MAX_RECORD_SECONDS = 60
const VIS_BAR_COUNT = 40

function localVoiceSrc() {
  if (!recordedAudioData.value) return ''
  const mime = recordedAudioMime.value || 'audio/webm'
  return `data:${mime};base64,${recordedAudioData.value}`
}

function _cleanupVisualizer() {
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
    animationFrameId = null
  }
  if (analyserRef) {
    try { analyserRef.disconnect() } catch (_) { /* noop */ }
    analyserRef = null
  }
  if (audioContextRef && audioContextRef.state !== 'closed') {
    audioContextRef.close().catch(() => {})
  }
  audioContextRef = null
  recordingBars.value = new Array(VIS_BAR_COUNT).fill(0)
  recordingRmsHistory.length = 0
}

function _startVisualizerFromStream(stream) {
  try {
    const AC = window.AudioContext || window.webkitAudioContext
    if (!AC) return
    audioContextRef = new AC()
    const source = audioContextRef.createMediaStreamSource(stream)
    const analyser = audioContextRef.createAnalyser()
    analyser.fftSize = 1024
    analyser.smoothingTimeConstant = 0.6
    source.connect(analyser)
    analyserRef = analyser

    const buf = new Uint8Array(analyser.fftSize)
    const tick = () => {
      if (!analyserRef) return
      analyserRef.getByteTimeDomainData(buf)
      let sum = 0
      for (let i = 0; i < buf.length; i++) {
        const v = (buf[i] - 128) / 128
        sum += v * v
      }
      // RMS 归一化到 0~1，人类说话语音峰值一般 RMS 0.05~0.3，压缩一下显示更明显
      const rms = Math.sqrt(sum / buf.length)
      let level = Math.min(1, Math.max(0, (rms - 0.01)) * 3.5)
      if (level < 0.03) level = 0  // 背景静默抑制
      recordingRmsHistory.push(level)
      if (recordingRmsHistory.length > VIS_BAR_COUNT) recordingRmsHistory.shift()
      // 输出 bars：按「历史按时间从左到右」铺 40 格，不足的前面补 0
      const out = new Array(VIS_BAR_COUNT).fill(0)
      for (let i = 0; i < recordingRmsHistory.length; i++) {
        out[VIS_BAR_COUNT - recordingRmsHistory.length + i] = recordingRmsHistory[i]
      }
      recordingBars.value = out
      animationFrameId = requestAnimationFrame(tick)
    }
    animationFrameId = requestAnimationFrame(tick)
  } catch (e) {
    // 可视化失败不影响录音
    console.warn('录音可视化启动失败', e)
  }
}

async function startRecording() {
  if (isRecording.value) return
  try {
    recordedChunks.length = 0
    recordedAudioData.value = null
    recordedAudioDuration.value = 0
    recordingDuration.value = 0
    recordingBars.value = new Array(VIS_BAR_COUNT).fill(0)
    recordingRmsHistory.length = 0

    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    audioStreamRef = stream
    let mimeType = 'audio/webm'
    const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4']
    for (const c of candidates) {
      if (window.MediaRecorder && MediaRecorder.isTypeSupported(c)) { mimeType = c; break }
    }
    const mr = new MediaRecorder(stream, { mimeType })
    recordedAudioMime.value = mimeType

    mr.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) recordedChunks.push(e.data)
    }
    mr.onstop = () => {
      const blob = new Blob(recordedChunks, { type: mimeType })
      const reader = new FileReader()
      reader.onloadend = () => {
        const dataUrl = reader.result
        // 去掉 data:audio/xxx;base64, 前缀
        const base64 = dataUrl?.split(',')[1] || ''
        recordedAudioData.value = base64
        recordedAudioDuration.value = Math.round((Date.now() - recordStartTime) / 1000)
      }
      reader.readAsDataURL(blob)
      stream.getTracks().forEach(t => t.stop())
      if (audioStreamRef) {
        audioStreamRef.getTracks().forEach(t => t.stop())
        audioStreamRef = null
      }
      _cleanupVisualizer()
    }

    mr.start()
    mediaRecorderRef.value = mr
    isRecording.value = true
    recordStartTime = Date.now()
    _startVisualizerFromStream(stream)
    recordTimer = setInterval(() => {
      recordingDuration.value = Math.floor((Date.now() - recordStartTime) / 1000)
      if (recordingDuration.value >= MAX_RECORD_SECONDS) stopRecording()
    }, 200)
  } catch (e) {
    ElMessage.error('无法访问麦克风: ' + (e?.message || '请检查浏览器权限'))
  }
}

function stopRecording() {
  if (!isRecording.value) return
  isRecording.value = false
  if (recordTimer) { clearInterval(recordTimer); recordTimer = null }
  try {
    mediaRecorderRef.value?.stop()
  } catch (_) { /* ignore */ }
}

function cancelRecording() {
  stopRecording()
  recordedChunks.length = 0
  recordedAudioData.value = null
  recordingDuration.value = 0
  _cleanupVisualizer()
}

async function sendVoice() {
  if (!recordedAudioData.value) return
  if (!conversations.currentConversationId) {
    ElMessage.warning('请选择会话')
    return
  }
  sending.value = true
  try {
    const mime = recordedAudioMime.value || 'audio/webm'
    const ext = mime.includes('mp4') ? 'm4a' : (mime.includes('ogg') ? 'ogg' : 'webm')
    await conversations.send(conversations.currentConversationId, '[语音]', targetLang.value, {
      messageType: 'voice',
      audioData: recordedAudioData.value,
      audioMimeType: mime,
      audioDuration: recordedAudioDuration.value,
      audioFileName: `voice_message.${ext}`
    })
    recordedAudioData.value = null
    recordingDuration.value = 0
    conversations.markCurrentAsRead()
  } catch (e) {
    const errorMsg = e?.response?.data?.message || e?.message || '语音发送失败'
    ElMessage.error(errorMsg)
  } finally {
    sending.value = false
  }
}


function enterEditMode(msg) {
  isEditing.value = true
  editingMsgId.value = msg.id
  inputText.value = msg.content || ''
}

function cancelEditMode() {
  isEditing.value = false
  editingMsgId.value = null
  inputText.value = ''
}

async function saveEditMsg() {
  if (!editingMsgId.value) return
  const text = inputText.value.trim()
  if (!text) return
  sending.value = true
  try {
    const res = await editMessageApi(conversations.currentConversationId, editingMsgId.value, text)
    if (res) {
      const msg = conversations.currentMessages.find(m => m.id === editingMsgId.value)
      if (msg) {
        msg.content = res.content
        msg.translatedContent = res.translatedContent
        msg.editedAt = res.editedAt
      }
      ElMessage.success('消息已更新')
    }
    isEditing.value = false
    editingMsgId.value = null
    inputText.value = ''
  } catch (e) {
    ElMessage.error('编辑失败')
  } finally {
    sending.value = false
  }
}

function insertText(text) {
  inputText.value += text
}

function insertMention(m) {
  inputText.value += `@${m.name} `
}

async function translateCurrentMsg() {
  if (!conversations.currentConversationId) {
    ElMessage.warning('请选择会话')
    return
  }
  const inboundMsgs = conversations.currentMessages.filter(m => m.direction === 'INBOUND' && canTranslateInbound(m))
  if (inboundMsgs.length === 0) {
    ElMessage.info('没有可翻译的消息')
    return
  }
  for (const m of inboundMsgs) {
    translateMsg(m)
  }
  ElMessage.success(`正在翻译 ${inboundMsgs.length} 条消息...`)
}

onMounted(async () => {
  if (accounts.accounts.length === 0) {
    try { await accounts.fetchAccounts() } catch (e) {}
  }
  if (conversations.conversations.length === 0) {
    try { await conversations.fetchConversations() } catch (e) {}
  }
  pollTimer = setInterval(pollCurrentMessages, 1000)
  convPollTimer = setInterval(pollConversations, 2000)
})

onUnmounted(() => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (convPollTimer) { clearInterval(convPollTimer); convPollTimer = null }
})
</script>

<style scoped>
.chat-page {
  width: 100%;
  height: 100%;
  display: flex;
  background: var(--color-bg);
  overflow: hidden;
  color: var(--color-text);
  min-width: 0;
}

/* ==== 左侧会话列表面板 ==== */
.conv-panel {
  width: 300px;
  min-width: 240px;
  flex-shrink: 1;
  background: var(--color-bg-2);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  padding: 14px 12px 10px;
  border-bottom: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex-shrink: 0;
}

.panel-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.panel-title-row h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text);
}

.count-tag {
  background: #5865F2;
  border: none;
  color: #fff;
  font-weight: 600;
}

.search-input :deep(.el-input__wrapper) {
  padding: 4px 10px;
  background: var(--color-bg-3);
  border-radius: 20px;
  box-shadow: none !important;
}
.search-input :deep(.el-input__inner) {
  color: var(--color-text);
}
.search-input :deep(.el-input__inner::placeholder) {
  color: var(--color-text-3);
}
.search-input :deep(.el-input__prefix .el-icon) {
  color: var(--color-text-3);
}

.filter-bar {
  display: flex;
  gap: 5px;
  align-items: center;
}

.filter-select {
  flex: 1;
  min-width: 0;
}
.filter-select :deep(.el-select__wrapper) {
  padding: 3px 8px;
  background: var(--color-bg-3);
  box-shadow: none !important;
  border-radius: 6px;
}
.filter-select :deep(.el-select__placeholder) {
  color: var(--color-text-3);
}
.filter-select :deep(.el-select__selected-item) {
  color: var(--color-text);
}
.filter-select :deep(.el-select__caret) {
  color: var(--color-text-3);
}

.date-filter-trigger {
  flex-shrink: 0;
  padding: 6px 10px;
  font-size: 12px;
  background: var(--color-bg-3);
  border-color: var(--color-border);
  color: var(--color-text-2);
}
.date-filter-trigger:hover {
  background: var(--color-bg-hover);
  color: var(--color-text);
}
.date-filter-label {
  margin-left: 3px;
  font-size: 11px;
  opacity: 0.9;
}

.date-popover {
  padding: 10px 12px;
  position: relative;
  z-index: 9999;
}
.date-popover :deep(.el-date-editor-tdate__popper) {
  position: absolute !important;
  top: 100% !important;
  left: 0 !important;
  margin-top: 4px;
  z-index: 10000;
}
.date-section-title {
  font-size: 11px;
  font-weight: 600;
  color: var(--color-text-3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 6px;
}
.date-section-title:not(:first-child) {
  margin-top: 12px;
}
.date-quick-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}
.date-quick-row .el-button {
  font-size: 12px;
  padding: 5px 0;
  background: var(--color-bg-3);
  border-color: var(--color-border);
  color: var(--color-text-2);
  border-radius: 6px;
}
.date-quick-row .el-button:hover {
  background: var(--color-bg-hover);
  color: var(--color-text);
}
.date-quick-row .el-button.is-active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
}
.date-quick-row .el-button.is-plain {
  background: transparent;
}
.date-range-picker {
  width: 100%;
  display: block;
}
.date-range-picker :deep(.el-input__wrapper) {
  padding: 4px 10px;
  background: var(--color-bg-3);
  box-shadow: none !important;
  border-radius: 8px;
  border: 1px solid var(--color-border);
}
.date-range-picker :deep(.el-input__inner) {
  color: var(--color-text);
  font-size: 12px;
}
.date-range-picker :deep(.el-range-separator),
.date-range-picker :deep(.el-input__prefix),
.date-range-picker :deep(.el-input__suffix) {
  color: var(--color-text-3);
  font-size: 12px;
}
.date-range-picker :deep(.el-range-input) {
  font-size: 12px;
  color: var(--color-text);
}
.date-range-picker :deep(.el-range-separator) {
  padding: 0 6px;
}
.date-popover-footer {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 32px;
}
.date-filter-hint {
  font-size: 11px;
  color: var(--color-text-3);
}
.date-popover-actions {
  display: flex;
  gap: 6px;
}

.conv-section-header {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 10px 10px 6px;
  font-size: 11px;
  font-weight: 600;
  color: var(--color-primary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.conv-section-header .el-icon {
  font-size: 12px;
}
.conv-section-header .section-count {
  background: var(--color-primary);
  color: #fff;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  font-weight: 600;
}
.conv-section-header.subtle {
  color: var(--color-text-3);
  border-top: 1px solid var(--color-border);
  margin-top: 4px;
  padding-top: 8px;
}

.pin-icon {
  color: #e6a23c;
  flex-shrink: 0;
}

.pinned-item {
  background: linear-gradient(90deg, rgba(230, 162, 60, 0.08) 0%, transparent 100%);
  border-left: 2px solid #e6a23c;
  padding-left: 8px !important;
}
.pinned-item.active {
  background: linear-gradient(90deg, rgba(230, 162, 60, 0.12) 0%, rgba(88, 101, 242, 0.08) 100%);
  border-left: 3px solid var(--color-primary) !important;
}

.conv-list-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.conv-scroll {
  flex: 1;
  min-height: 0;
}

.loading-tip {
  padding: 40px 10px;
  text-align: center;
  color: var(--color-text-2);
  font-size: 13px;
}

.conv-list {
  padding: 6px 6px 16px;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 10px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.12s ease;
  position: relative;
  margin-bottom: 2px;
}

.conv-item:hover {
  background: var(--color-bg-hover);
}

.conv-item.active {
  background: var(--color-bg-hover);
  border-left: 3px solid var(--color-primary);
  padding-left: 7px;
}

.conv-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.conv-agent-name {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  max-width: 20px;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--color-text-2);
  text-align: center;
  gap: 1px;
  flex-shrink: 0;
  word-break: break-all;
}

.conv-agent-name span:last-child {
  letter-spacing: -1px;
}

.conv-avatar {
  background: var(--color-primary);
  color: #fff;
  font-weight: 600;
  border: 2px solid var(--color-bg-2);
}

.unread-badge {
  position: absolute;
  top: -6px;
  right: -6px;
  min-width: 18px;
  height: 18px;
  background: #ed4245;
  color: #fff;
  border-radius: 9px;
  font-size: 10px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 5px;
  line-height: 1;
  border: 2px solid var(--color-bg-2);
}

.unread-dot {
  position: absolute;
  top: -4px;
  right: -4px;
  width: 12px;
  height: 12px;
  background: #ed4245;
  border-radius: 50%;
  border: 2px solid var(--color-bg);
  z-index: 10;
  box-shadow: 0 0 0 2px rgba(237, 66, 69, 0.25);
}

.account-dot {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--color-bg-2);
  background: var(--color-primary);
  z-index: 3;
}

.conv-presence-dot {
  position: absolute;
  top: -4px;
  left: -4px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid var(--color-bg-2);
  background: #555;
  z-index: 3;
  transition: background 0.2s ease;
}
.conv-presence-dot.online {
  background: #23a55a;
}
.conv-presence-dot.offline {
  background: #8a919f;
}

.conv-line-grid {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 8px;
  align-items: center;
}

.conv-account-name {
  font-size: 11px;
  color: var(--color-text-3);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 80px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  flex-shrink: 1;
}

.conv-account-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.agent-badge {
  position: absolute;
  bottom: -2px;
  left: -4px;
  background: linear-gradient(135deg, var(--color-primary), var(--color-pink));
  color: #fff;
  font-size: 9px;
  font-weight: 600;
  border: 2px solid var(--color-bg-2);
  z-index: 2;
}

.conv-main {
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.conv-line-1 {
  display: flex;
  align-items: center;
  gap: 6px;
}

.conv-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stage-tag-mini {
  font-size: 10px;
  flex-shrink: 0;
}

.conv-line-2 {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
}

.conv-nickname {
  font-size: 12px;
  color: var(--color-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-actions {
  flex-shrink: 0;
  opacity: 1;
}

/* ==== 中间聊天面板 ==== */
.chat-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-bg);
  height: 100%;
  overflow: hidden;
}

.chat-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-empty-inner {
  text-align: center;
  color: var(--color-text-2);
}

.empty-icon {
  width: 96px;
  height: 96px;
  margin: 0 auto 20px;
  background: var(--color-bg-3);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-primary);
  opacity: 0.6;
}

.empty-title {
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 6px;
}

.empty-desc {
  font-size: 13px;
  color: var(--color-text-3);
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: var(--color-bg-3);
  border-bottom: 1px solid var(--color-border);
  flex-shrink: 0;
}

.chat-header-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-avatar {
  background: var(--color-primary);
  color: #fff;
  font-weight: 600;
}

.chat-header-meta {
  min-width: 0;
}

.chat-header-name {
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text);
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-stage-tag {
  font-size: 11px;
}

.customer-name {
  color: var(--color-text);
}

.chat-header-sub {
  font-size: 11px;
  color: var(--color-text-3);
  margin-top: 3px;
  font-family: "JetBrains Mono", monospace;
}

.divider {
  margin: 0 6px;
  color: var(--color-text-3);
}

.chat-header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stage-select {
  width: 130px;
}
.stage-select :deep(.el-select__wrapper) {
  background: var(--color-bg-3);
  box-shadow: none !important;
  border-radius: 6px;
}
.stage-select :deep(.el-select__selected-item) {
  color: var(--color-text);
}

/* ==== 消息列表 ==== */
.msg-scroll {
  flex: 1;
  min-height: 0;
  background: var(--color-bg);
}

.load-more-row {
  text-align: center;
  padding: 10px 0 4px;
}
.load-more-row .el-button {
  color: var(--color-text-2);
}

.loading-tip-sm {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-2);
  font-size: 12px;
}

.messages-loading {
  padding: 40px;
  text-align: center;
  color: var(--color-text-2);
  font-size: 13px;
}

.messages-list {
  padding: 12px 24px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  max-width: 100%;
}

.msg-row.out {
  flex-direction: row-reverse;
}

.msg-row.deleted .msg-bubble {
  opacity: 0.5;
}
.msg-row.deleted .msg-content {
  display: none;
}

.msg-edited {
  font-size: 10px;
  color: var(--color-text-3);
  font-style: italic;
}

.bubble-deleted {
  background: transparent !important;
  border: 1px dashed var(--color-border);
}

.msg-deleted-tip {
  font-size: 12px;
  color: var(--color-text-3);
  font-style: italic;
}

.msg-avatar {
  flex-shrink: 0;
  width: 36px !important;
  height: 36px !important;
  font-size: 13px;
  margin-top: 20px;
}

.msg-row.out .msg-avatar {
  background: var(--color-primary);
  color: #fff;
  font-weight: 600;
}
.msg-row.in .msg-avatar {
  background: var(--color-bg-3);
  color: var(--color-text-2);
}

.msg-bubble-wrap {
  max-width: min(60%, 640px);
  display: flex;
  flex-direction: column;
  gap: 4px;
  position: relative;
}

.msg-row.out .msg-bubble-wrap {
  align-items: flex-end;
}
.msg-row.in .msg-bubble-wrap {
  align-items: flex-start;
}

.msg-meta-line {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: var(--color-text-3);
  padding: 0 6px;
}

.msg-sender {
  font-weight: 600;
  color: var(--color-text-2);
}

.mine-badge {
  background: var(--color-primary);
  color: #fff;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  font-weight: 600;
}

.msg-time {
  font-family: "JetBrains Mono", monospace;
}

.msg-bubble {
  border-radius: 14px;
  padding: 10px 14px;
  font-size: 14px;
  line-height: 1.55;
  word-wrap: break-word;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}

.bubble-in {
  background: var(--color-bubble-in);
  color: var(--color-text);
  border-top-left-radius: 4px;
}

.bubble-out {
  background: var(--color-primary);
  color: #fff;
  border-top-right-radius: 4px;
}

.msg-content {
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-attachments {
  margin-top: 8px;
}

.attachment-item {
  margin-bottom: 6px;
}

.attachment-image {
  max-width: 240px;
  max-height: 240px;
  border-radius: 8px;
  display: block;
}

.attachment-file {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--color-bg-hover);
  border-radius: 6px;
  color: inherit;
  text-decoration: none;
}

.msg-quote {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--color-text-3);
  padding: 4px 8px;
  background: var(--color-bg-hover);
  border-radius: 4px;
  margin-bottom: 6px;
  cursor: pointer;
}
.bubble-out .msg-quote {
  background: rgba(255, 255, 255, 0.15);
  color: rgba(255, 255, 255, 0.8);
}

.msg-reactions {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.reaction-tag {
  cursor: pointer;
}

.reaction-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 6px;
  max-width: 180px;
}

.msg-original-wrap,
.msg-translate-wrap {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.msg-lang-tag {
  margin-right: 8px;
  font-size: 11px;
}

.msg-translate-wrap .el-button,
.msg-translate-wrap .el-tag {
  align-self: flex-start;
}

.original-text,
.translated-text {
  font-size: 12px;
  color: var(--color-text-3);
  line-height: 1.5;
}
.bubble-out .original-text,
.bubble-out .translated-text {
  color: rgba(255, 255, 255, 0.8);
}

.arrow-icon {
  display: inline-block;
  transition: transform 0.2s ease;
}
.arrow-icon.flip {
  transform: rotate(180deg);
}

.msg-actions {
  position: absolute;
  top: -28px;
  display: flex;
  gap: 2px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 2px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
}

/* ==== 工具栏 ==== */
.toolbar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 20px;
  background: var(--color-bg-3);
  border-top: 1px solid var(--color-border);
  flex-shrink: 0;
  gap: 12px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-btn {
  background: var(--color-bg-3);
  border: none;
  color: var(--color-text-3);
  transition: background 0.15s;
}
.toolbar-btn:hover {
  background: var(--color-bg-hover);
  color: var(--color-text);
}

.detected-lang {
  font-size: 12px;
  color: var(--color-text-3);
  background: var(--color-bg-3);
  padding: 4px 8px;
  border-radius: 6px;
}

.target-lang-label {
  font-size: 12px;
  color: var(--color-text-3);
}

.lang-select {
  width: 100px;
}
.lang-select :deep(.el-select__wrapper) {
  background: var(--color-bg-3);
  box-shadow: none !important;
  border-radius: 6px;
}
.lang-select :deep(.el-select__selected-item) {
  color: var(--color-text);
}

.emoji-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 4px;
  max-width: 240px;
}

.emoji-item {
  font-size: 18px;
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
}
.emoji-item:hover {
  background: var(--color-bg-hover);
}

.gif-panel,
.sticker-panel,
.mention-panel {
  padding: 4px;
}

.gif-grid,
.sticker-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
  padding: 8px;
}

.gif-item,
.sticker-item {
  cursor: pointer;
  border-radius: 6px;
  overflow: hidden;
  position: relative;
  transition: background 0.15s;
}
.gif-item:hover,
.sticker-item:hover {
  background: var(--color-bg-hover);
}

.gif-thumb,
.sticker-thumb {
  width: 100%;
  height: 60px;
  object-fit: cover;
  border-radius: 6px;
  display: block;
}

.sticker-count {
  position: absolute;
  bottom: 2px;
  right: 2px;
  background: #ed4245;
  color: #fff;
  font-size: 10px;
  padding: 0 4px;
  border-radius: 8px;
}

.gif-empty,
.sticker-empty,
.mention-empty {
  text-align: center;
  color: var(--color-text-3);
  font-size: 12px;
  padding: 20px;
}

.mention-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 6px;
  max-height: 240px;
  overflow-y: auto;
}

.mention-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
  font-size: 13px;
  color: var(--color-text);
}
.mention-item:hover {
  background: var(--color-bg-hover);
}

/* ==== 输入区 ==== */
.input-area {
  background: var(--color-bg-3);
  border-top: 1px solid var(--color-border);
  padding: 10px 20px 16px;
  flex-shrink: 0;
}

.input-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #faa61a;
  margin-bottom: 6px;
  padding: 0 2px;
}

.shortcut-hint {
  color: var(--color-text-3);
  margin-left: auto;
}

.reply-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--color-bg-3);
  border-radius: 8px;
  font-size: 12px;
  color: var(--color-text-2);
  margin-bottom: 8px;
  border-left: 3px solid var(--color-primary);
}
.reply-preview .close-icon {
  margin-left: auto;
  cursor: pointer;
}

.editing-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--color-bg-3);
  border-radius: 8px;
  font-size: 12px;
  color: var(--color-primary);
  margin-bottom: 8px;
  border-left: 3px solid var(--color-primary);
}
.editing-hint .close-icon {
  margin-left: auto;
  cursor: pointer;
}

.attachment-preview-list {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.attachment-preview-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: var(--color-bg-3);
  border-radius: 8px;
  font-size: 12px;
  color: var(--color-text-2);
}
.attachment-preview-item .close-icon {
  cursor: pointer;
}

.attachment-thumb {
  width: 36px;
  height: 36px;
  object-fit: cover;
  border-radius: 4px;
}

.input-box-wrap {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  background: var(--color-bg-3);
  border-radius: 12px;
  padding: 8px 10px;
}

.msg-input {
  flex: 1;
}
.msg-input :deep(.el-textarea__inner) {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  padding: 4px 6px;
  line-height: 1.5;
  color: var(--color-text);
}
.msg-input :deep(.el-textarea__inner::placeholder) {
  color: var(--color-text-3);
}
.msg-input :deep(.el-textarea__inner:focus) {
  box-shadow: none !important;
}

.send-btn {
  flex-shrink: 0;
  height: 40px;
  padding: 0 22px;
  font-weight: 600;
  border-radius: 10px;
  background: var(--color-primary);
  border-color: var(--color-primary);
}
.send-btn:hover {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}

/* === 语音录音 === */
.voice-btn {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  background: var(--color-bg-4);
  border-color: var(--color-border);
  color: var(--color-text-2);
  transition: all 180ms cubic-bezier(.2,.7,.2,1);
}
.voice-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
  transform: translateY(-1px);
  box-shadow: 0 6px 18px -6px rgba(88, 101, 242, 0.45);
}
.voice-btn-start:active { transform: scale(.94); }
.voice-btn-start:disabled { opacity: .55; transform: none; box-shadow: none; }

/* ============= 录音中：整个 inline 录音器（新UI） ============= */
.recorder-live {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: stretch;
  gap: 8px;
  min-width: 0;
  padding: 5px;
  border-radius: 16px;
  background:
    radial-gradient(120% 140% at 0% 0%, rgba(255, 180, 180, 0.20), transparent 55%),
    linear-gradient(180deg, #fffaf9 0%, #fff6f0 100%);
  border: 1px solid rgba(252, 165, 165, 0.55);
  box-shadow:
    0 1px 0 rgba(255,255,255,.7) inset,
    0 8px 20px -12px rgba(248, 113, 113, 0.25);
}

/* 右侧：取消+停止 按钮组（紧凑同一行，不占中间空间） */
.recorder-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 1px;
}

/* 停止按钮：浅红配色，紧凑 38px 高度，标签缩成「停止」两个字 */
.btn-stop-rec {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 38px;
  padding: 0 11px 0 9px;
  border: 1px solid rgba(248, 113, 113, 0.55);
  border-radius: 12px;
  background: linear-gradient(180deg, #fff1f1 0%, #ffe4e4 100%);
  color: #c2410c;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.2px;
  cursor: pointer;
  box-shadow:
    0 1px 0 rgba(255,255,255,.85) inset,
    0 -1px 0 rgba(255,255,255,.3) inset,
    0 6px 14px -10px rgba(248, 113, 113, 0.55);
  transition: transform 120ms ease, box-shadow 180ms ease, filter 120ms ease, background 120ms ease;
  user-select: none;
}
.btn-stop-rec:hover {
  background: linear-gradient(180deg, #ffe7e7 0%, #ffd6d6 100%);
  transform: translateY(-1px);
  box-shadow:
    0 1px 0 rgba(255,255,255,.85) inset,
    0 -1px 0 rgba(255,255,255,.3) inset,
    0 10px 20px -10px rgba(248, 113, 113, 0.65);
}
.btn-stop-rec:active { transform: translateY(0) scale(.98); }
.btn-stop-rec .bsr-ic {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 5px;
  background: rgba(251, 113, 133, 0.18);
  box-shadow: inset 0 0 0 1px rgba(248, 113, 113, 0.22);
  color: #b91c1c;
}
.btn-stop-rec .bsr-label { padding-right: 1px; }

/* 中间区域：波形条 + 时间 + 提示文字 */
.recorder-live-main {
  display: grid;
  grid-template-rows: 1fr auto;
  gap: 2px;
  min-width: 0;
  padding: 1px 2px 2px 2px;
}
.live-wave {
  position: relative;
  display: flex;
  align-items: center;
  min-width: 0;
  padding: 2px 6px;
  border-radius: 10px;
  background: linear-gradient(180deg, rgba(255, 241, 241, 0.35), rgba(255, 241, 241, 0.05));
}
/* 连续声波基线（SVG，默认展示就有一条平滑红色正弦声波，不是 40 个虚线段） */
.live-wave-baseline {
  position: absolute;
  inset: 4px 6px;
  width: calc(100% - 12px);
  height: calc(100% - 8px);
  opacity: 0.9;
  pointer-events: none;
}
.live-wave-bars {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 0;
  height: 26px;
}
.live-wave-bar {
  flex: 1 1 0;
  min-width: 2px;
  max-width: 6px;
  height: calc(2px + var(--lv, 0) * 22px);
  min-height: 2px;
  border-radius: 999px;
  background: linear-gradient(180deg, #fca5a5 0%, #f87171 55%, #fb7185 100%);
  transform-origin: center;
  transition: height 70ms cubic-bezier(.2,.7,.2,1);
  mix-blend-mode: multiply;
}
.live-wave-bar:nth-child(even) {
  background: linear-gradient(180deg, #fecaca 0%, #fca5a5 55%, #f87171 100%);
}
/* RMS = 0（静默）时，bar 完全透明，让 SVG 基线露出来，不会出现 40 个小虚线段 */
.live-wave-bar {
  opacity: calc(0.12 + var(--lv, 0) * 1.05);
}

/* 元信息：时间 + 操作提示 */
.live-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 0 6px 1px;
  min-width: 0;
}
.live-duration {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 2px 9px 2px 7px;
  border-radius: 999px;
  background: rgba(254, 202, 202, 0.45);
  border: 1px solid rgba(252, 165, 165, 0.55);
}
.live-led {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f87171;
  box-shadow: 0 0 0 2.5px rgba(248, 113, 113, 0.20);
  animation: led-blink 1.1s ease-in-out infinite;
}
@keyframes led-blink {
  0%, 100% { transform: scale(1); opacity: 1; box-shadow: 0 0 0 2.5px rgba(248, 113, 113, 0.20); }
  50% { transform: scale(1.22); opacity: .78; box-shadow: 0 0 0 4px rgba(248, 113, 113, 0.05); }
}
.live-clock {
  font-size: 13px;
  font-weight: 700;
  color: #c2410c;
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, "JetBrains Mono", monospace;
  letter-spacing: 0.5px;
}

/* 操作提示文字 */
.live-hint {
  flex: 1;
  text-align: right;
  font-size: 11.5px;
  color: #9ca3af;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.live-hint b {
  color: #b91c1c;
  font-weight: 700;
  padding: 1px 5px;
  margin: 0 2px;
  border-radius: 5px;
  background: rgba(254, 202, 202, 0.45);
  border: 1px solid rgba(252, 165, 165, 0.45);
}
.live-hint-act {
  cursor: pointer;
  color: #6b7280;
  text-decoration: underline;
  text-decoration-color: rgba(107,114,128,.4);
  text-underline-offset: 2px;
  padding: 0 2px;
  border-radius: 4px;
  transition: color 120ms ease, background 120ms ease;
}
.live-hint-act:hover {
  color: #374151;
  background: rgba(0,0,0,.04);
}

/* 取消录音：副按钮（放在右边，跟停止同组） */
.btn-cancel-rec {
  align-self: center;
  height: 38px;
  padding: 0 11px;
  border-radius: 12px;
  background: #ffffff;
  border: 1px solid rgba(17, 24, 39, 0.10);
  color: #4b5563;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow:
    0 1px 0 rgba(255,255,255,.9) inset,
    0 5px 12px -10px rgba(0,0,0,.20);
  transition: transform 120ms ease, color 120ms ease, border-color 120ms ease, background 120ms ease;
}
.btn-cancel-rec:hover {
  color: #111827;
  border-color: rgba(17, 24, 39, 0.20);
  background: #fafafa;
  transform: translateY(-1px);
}
.btn-cancel-rec:active { transform: translateY(0) scale(.98); }

@media (prefers-reduced-motion: reduce) {
  .live-led, .live-wave-bar { animation: none !important; transition: none !important; }
}

/* === 录音预览态（已有录音） === */
.recorded-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  background: var(--color-bg-4);
  border: 1px solid var(--color-primary);
  border-radius: 20px;
  padding: 4px 10px;
  min-width: 0;
}
.recorded-audio {
  flex: 1;
  height: 32px;
  min-width: 0;
}
.recorded-duration {
  font-size: 12px;
  color: var(--color-text-2);
  font-family: "JetBrains Mono", monospace;
  min-width: 32px;
  text-align: right;
}

.msg-voice-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--color-bg-3);
  border-radius: 10px;
  margin-bottom: 6px;
}
.msg-voice-wrap .el-icon {
  font-size: 18px;
  color: var(--color-primary);
}
.msg-voice-audio {
  flex: 1;
  height: 28px;
  min-width: 180px;
}
.msg-voice-placeholder {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--color-text-2);
  font-size: 13px;
}
.msg-voice-duration {
  color: var(--color-text-3);
  font-size: 12px;
}

/* ====== ASR 语音转文字卡片 ====== */
.msg-asr-card {
  margin-top: 2px;
  margin-bottom: 6px;
  padding: 9px 11px 10px 11px;
  border-radius: 11px;
  border: 1px solid var(--color-border-soft, #e5e7eb);
  background:
    radial-gradient(100% 140% at 0% 0%, rgba(139,92,246,0.05), transparent 60%),
    linear-gradient(180deg, rgba(248, 250, 252, 0.95), rgba(255,255,255,1) 100%);
  box-shadow:
    0 1px 0 rgba(255,255,255,.7) inset,
    0 4px 12px -10px rgba(15,23,42,.12);
  min-width: 180px;
  max-width: 100%;
}
.asr-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.asr-title {
  display: inline-flex;
  align-items: center;
  font-size: 12.5px;
  font-weight: 600;
  color: #475569;
  min-width: 0;
}
.asr-lang {
  margin-left: 8px;
  font-weight: 500;
  color: #64748b;
}
.asr-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.asr-card-body {
  font-size: 13px;
  line-height: 1.55;
  color: #0f172a;
  min-height: 26px;
  white-space: pre-wrap;
  word-break: break-word;
}
.asr-text {
  padding: 2px 2px 1px;
}
/* 简化版 ASR 卡片：移除标题/分段控件后，卡片更紧凑；右下角提供单链接切换原文/译文 */
.msg-asr-card.asr-card-simple {
  padding: 7px 10px 8px 10px;
}
.asr-toggle-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  padding: 1px 2px 0;
  font-size: 12px;
  line-height: 1.35;
  color: #64748b;   /* 去掉蓝色：改为灰色小字，和普通文本一致不抢视觉 */
  cursor: pointer;
  user-select: none;
  opacity: .9;
}
.asr-toggle-wrap {
  text-align: right;
}
.asr-original-wrap {
  margin-top: 6px;
  padding-top: 5px;
  border-top: 1px dashed #e2e8f0;
}
.asr-original-text {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
  white-space: pre-wrap;
  word-break: break-word;
  background: rgba(148, 163, 184, 0.08);
  padding: 6px 8px;
  border-radius: 4px;
}
.asr-toggle-link:hover {
  color: #0f172a;
  text-decoration: underline;
  opacity: 1;
}
.asr-error {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: #b91c1c;
  background: rgba(254, 202, 202, 0.35);
  border: 1px solid rgba(252, 165, 165, 0.55);
  padding: 5px 8px;
  border-radius: 8px;
  line-height: 1.5;
}
.asr-error span { flex: 1; }
.asr-ic { font-size: 13px; line-height: 1; }
.asr-ic-done    { color: #2563eb; }
.asr-ic-pending { color: #ca8a04; animation: asrPulse 1s ease-in-out infinite; }
.asr-ic-fail    { color: #dc2626; }
@keyframes asrPulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50%      { transform: scale(1.2); opacity: .7; }
}
/* ASR 卡片在"气泡"里做适配：INBOUND 默认左对齐气泡里的卡片样式一致 */
.msg-item-left  .msg-asr-card { border-color: #e2e8f0; background: linear-gradient(180deg, #ffffff, #f8fafc); }
.msg-item-right .msg-asr-card { border-color: #e0e7ff; background: linear-gradient(180deg, #ffffff, #f5f3ff); }

/* AI Panel */
.ai-panel {
  background: var(--color-bg-3);
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 8px;
  border: 1px solid var(--color-border);
}

.ai-panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
}

.ai-loading {
  text-align: center;
  color: var(--color-text-2);
  font-size: 12px;
  padding: 8px;
}

.ai-suggestions {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-suggestion {
  padding: 8px 10px;
  background: var(--color-bg);
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
  border-left: 3px solid var(--color-primary);
}
.ai-suggestion:hover {
  background: var(--color-bg-hover);
}

.ai-suggestion-text {
  font-size: 13px;
  color: var(--color-text);
  line-height: 1.5;
}

.ai-suggestion-translated {
  font-size: 11px;
  color: var(--color-text-3);
  margin-top: 3px;
  font-style: italic;
}

/* ==== 客户资料侧栏 ==== */
.profile-panel {
  width: 320px;
  min-width: 260px;
  flex-shrink: 1;
  background: var(--color-bg-2);
  border-left: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  height: 100%;
}

.profile-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border);
}

.profile-header h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
}

.profile-loading {
  padding: 40px;
  text-align: center;
  color: var(--color-text-2);
}

.profile-section {
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border);
}

.profile-section-title {
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.profile-section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.profile-section-title-row .profile-section-title {
  margin-bottom: 0;
}
.profile-section-title-row.clickable {
  cursor: pointer;
}

.profile-avatar-wrap {
  position: relative;
  width: fit-content;
  margin: 0 auto 10px;
}

.profile-avatar {
  background: var(--color-primary);
  color: #fff;
  font-weight: 600;
}

.profile-online-dot {
  position: absolute;
  bottom: 2px;
  right: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #555;
  border: 2px solid var(--color-bg-2);
}
.profile-online-dot.online {
  background: #23a55a;
}

.profile-name {
  text-align: center;
  font-size: 16px;
  font-weight: 700;
  color: var(--color-text);
  margin: 0 0 4px;
}

.profile-sub {
  text-align: center;
  font-size: 12px;
  color: var(--color-text-2);
  margin-bottom: 12px;
}

.profile-info-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.profile-info-row {
  display: flex;
  gap: 16px;
  margin-top: 8px;
}

.profile-info-item.inline-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 0;
  border-bottom: 1px solid var(--color-border);
}

.profile-info-item.inline-item .info-label {
  font-size: 12px;
  color: var(--color-text-3);
}

.profile-info-item.inline-item .info-value {
  font-size: 13px;
  color: var(--color-text);
  font-weight: 500;
}

.profile-info-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
  border-bottom: 1px solid var(--color-border);
}

.profile-info-item:last-child {
  border-bottom: none;
}

.info-label {
  font-size: 12px;
  color: var(--color-text-3);
}

.info-value {
  font-size: 13px;
  color: var(--color-text);
  font-weight: 500;
  max-width: 60%;
  text-align: right;
  word-break: break-all;
}

.info-value.mono {
  font-family: "JetBrains Mono", monospace;
  font-size: 11px;
}

.profile-id {
  text-align: center;
  font-size: 11px;
  color: var(--color-text-3);
  font-family: "JetBrains Mono", monospace;
  margin-top: 4px;
}

.profile-online-dot.blocked {
  background: var(--el-color-danger, #f56c6c);
}

.profile-online-dot.online {
  background: var(--el-color-success, #67c23a);
}

.scan-btn {
  width: 100%;
  margin-top: 10px;
  background: var(--color-bg-3);
  border-color: transparent;
  color: var(--color-text-2);
}
.scan-btn:hover {
  background: var(--color-bg-hover);
  color: var(--color-text);
}

.source-account-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.source-avatar {
  background: var(--color-primary);
  color: #fff;
  font-weight: 600;
}

.source-account-info {
  flex: 1;
}

.source-account-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
}

.source-account-status {
  font-size: 11px;
  color: var(--color-text-3);
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #555;
}
.status-dot.online {
  background: #23a55a;
}

.profile-info-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  padding: 4px 0;
  color: var(--color-text-2);
}
.profile-info-row span:first-child {
  color: var(--color-text-3);
}

.status-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}

.status-btn {
  font-size: 12px;
}

.followup-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.followup-item {
  padding: 8px 10px;
  background: var(--color-bg-3);
  border-radius: 8px;
  border-left: 3px solid var(--color-primary);
}

.followup-time {
  font-size: 11px;
  color: var(--color-text-3);
  margin-bottom: 4px;
}

.followup-content {
  font-size: 13px;
  color: var(--color-text);
  line-height: 1.5;
}

.empty-followup {
  font-size: 12px;
  color: var(--color-text-3);
  text-align: center;
  padding: 10px;
}

.full-detail-btn {
  width: 100%;
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
  font-weight: 600;
}
.full-detail-btn:hover {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}

.ai-section {
  border-bottom: none;
}

.ai-inline-panel {
  margin-top: 8px;
}

.ai-tone-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-tone-label {
  font-size: 12px;
  color: var(--color-text-3);
}

.profile-tag {
  margin: 0;
}

.tags-edit {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

/* ==== 对话框样式 ==== */
.dialog-form {
  padding-top: 4px;
}
.dialog-form .el-form-item {
  margin-bottom: 14px;
}

.tag-dialog {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.tag-section {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--color-bg-3);
}

.tag-section-title {
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 8px;
  font-weight: 600;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-empty {
  font-size: 12px;
  color: var(--color-text-3);
}

.tag-add-row {
  display: flex;
  gap: 6px;
  align-items: center;
}

.conv-tag {
  cursor: default;
}
.conv-tag.clickable {
  cursor: pointer;
}

.template-dialog {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.template-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border);
}

.template-empty {
  padding: 40px;
  text-align: center;
  color: var(--color-text-3);
}

.template-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.template-item {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--color-bg-3);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.template-item:hover {
  border-color: var(--color-primary);
  background: var(--color-bg-hover);
}

.template-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  margin-bottom: 4px;
}

.template-content {
  font-size: 12px;
  color: var(--color-text-2);
  line-height: 1.5;
  margin-bottom: 6px;
  white-space: pre-wrap;
  word-break: break-word;
}

.template-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>

<style>
.date-popover-popper {
  border-radius: 12px !important;
  border: 1px solid var(--color-border, #e4e7ed) !important;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12) !important;
  padding: 0 !important;
}
.date-popover-popper .el-popover__content {
  padding: 0 !important;
}
</style>