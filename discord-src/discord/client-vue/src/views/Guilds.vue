<template>
  <div class="guilds-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">服务器列表</h2>
        <p class="page-desc">管理 Discord 服务器配置，抓取服务器成员数据</p>
      </div>
      <div class="header-actions">
        <el-select
          v-model="filters.discordAccountId"
          placeholder="选择 Discord 账号"
          clearable
          style="width: 240px"
          @change="loadServers"
        >
          <el-option
            v-for="a in accountOptions"
            :key="a.id"
            :label="a.name || a.discordName || a.discordId || ('账号' + a.id)"
            :value="a.id"
          />
        </el-select>
        <el-button type="primary" @click="openEditDialog()">
          <el-icon><Plus /></el-icon> 新增服务器
        </el-button>
      </div>
    </div>

    <div class="page-body">
      <el-table
        :data="guildServers.servers"
        v-loading="guildServers.loading"
        stripe
        style="width: 100%"
        :header-cell-style="{ background: 'var(--color-bg-2)', color: 'var(--color-text)' }"
      >
        <el-table-column label="服务器" min-width="220">
          <template #default="{ row }">
            <div class="server-cell">
              <img v-if="row.iconUrl" :src="row.iconUrl" class="server-icon" />
              <div v-else class="server-icon placeholder">
                {{ (row.name || '?').charAt(0).toUpperCase() }}
              </div>
              <div class="server-info">
                <div class="server-name">{{ row.name || '未命名服务器' }}</div>
                <div class="server-sub" v-if="row.guildId">Guild ID: {{ row.guildId }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="所属账号" width="180">
          <template #default="{ row }">
            <el-tag size="small" type="info" effect="plain">{{ row.accountName || row.accountDiscordName || '-' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column prop="channelId" label="Channel ID" width="180">
          <template #default="{ row }">
            <span v-if="row.channelId" class="mono">{{ row.channelId }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>

        <el-table-column label="成员数" width="100" align="center">
          <template #default="{ row }">
            <span class="member-count">{{ row.memberCount || 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="最后采集" width="160">
          <template #default="{ row }">
            <span v-if="row.lastFetchAt" class="text-muted">{{ formatTime(row.lastFetchAt) }}</span>
            <span v-else class="text-muted">从未</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button size="small" link type="primary" @click="openMemberDialog(row)">
                <el-icon><User /></el-icon> 成员明细
              </el-button>
              <el-button size="small" link type="primary" @click="openEditDialog(row)">
                <el-icon><Edit /></el-icon> 编辑
              </el-button>
              <el-button 
                size="small" 
                link 
                :type="isServerSyncing(row.id) ? 'primary' : 'primary'" 
                :loading="isServerSyncing(row.id)"
                @click="isServerSyncing(row.id) ? openProgressDialog(row, getServerTaskId(row.id)) : openSyncDialog(row)"
              >
                <el-icon v-if="!isServerSyncing(row.id)"><Download /></el-icon>
                {{ isServerSyncing(row.id) ? '采集中' : '采集' }}
              </el-button>
              <el-button size="small" link type="primary" @click="openProgressDialog(row)">
                <el-icon><DataLine /></el-icon> 进度
              </el-button>
              <el-button size="small" link type="primary" @click="confirmDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty v-if="!loading" description="暂无服务器，点击右上角新增" />
        </template>
      </el-table>
    </div>

    <!-- 编辑/新增服务器 Dialog -->
    <el-dialog v-model="editDialog.visible" :title="editDialog.isEdit ? '编辑服务器' : '新增服务器'" width="560px" :close-on-click-modal="false">
      <el-form :model="editDialog.form" label-width="120px" label-position="left">
        <el-form-item label="所属账号" required>
          <el-select v-model="editDialog.form.discordAccountId" placeholder="选择 Discord 账号" style="width: 100%">
            <el-option
              v-for="a in accountOptions"
              :key="a.id"
              :label="a.name || a.discordName || a.discordId || ('账号' + a.id)"
              :value="a.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="服务器 URL">
          <el-input 
            v-model="editDialog.form.guildUrl" 
            type="textarea" 
            :rows="2"
            placeholder="https://discord.com/channels/guildId/channelId" 
          />
          <el-button type="primary" plain @click="parseUrl" style="width: 100%; margin-top: 8px;">
            <el-icon><MagicStick /></el-icon> 解析
          </el-button>
        </el-form-item>
        <el-form-item label="Guild ID">
          <el-input v-model="editDialog.form.guildId" placeholder="服务器 ID" />
        </el-form-item>
        <el-form-item label="Channel ID">
          <el-input v-model="editDialog.form.channelId" placeholder="频道 ID" />
        </el-form-item>
        <el-form-item label="服务器名称">
          <el-input v-model="editDialog.form.name" placeholder="服务器名称（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="saveServer">保存</el-button>
      </template>
    </el-dialog>

    <!-- 采集 Dialog -->
    <el-dialog 
      v-model="syncDialog.visible" 
      title="采集服务器成员" 
      width="560px" 
      :close-on-click-modal="false" 
      class="sync-dialog"
      align-center
    >
      <div v-if="syncDialog.server" class="sync-info">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="所属账号">
            {{ syncDialog.server.accountName || syncDialog.server.accountDiscordName }}
          </el-descriptions-item>
          <el-descriptions-item label="Guild ID">
            <span class="mono">{{ syncDialog.server.guildId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="Channel ID">
            <span v-if="syncDialog.server.channelId" class="mono">{{ syncDialog.server.channelId }}</span>
            <span v-else class="text-muted">-</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">抓取配置</el-divider>

        <el-form :model="syncDialog.config" label-width="120px">
          <el-form-item label="获取数量">
            <el-input-number v-model="syncDialog.config.fetchLimit" :min="100" :max="2000000" :step="1000" style="width: 100%" />
          </el-form-item>
          <el-form-item label="请求间隔(秒)">
            <el-input-number v-model="syncDialog.config.requestInterval" :min="1" :max="60" :step="1" style="width: 100%" />
          </el-form-item>
          <el-form-item label="每次请求数">
            <el-input-number v-model="syncDialog.config.requestCount" :min="10" :max="1000" :step="50" style="width: 100%" />
          </el-form-item>
          <el-form-item label="下钻深度">
            <el-input-number v-model="syncDialog.config.maxDepth" :min="1" :max="20" style="width: 100%" />
          </el-form-item>
          <el-form-item label="请求次数">
            <el-input-number v-model="syncDialog.config.maxRequests" :min="1" :max="10000" :step="100" style="width: 100%" />
          </el-form-item>
          <el-form-item label="断点续采">
            <div style="display: flex; flex-direction: column; align-items: flex-start; width: 100%;">
              <el-switch 
                v-model="syncDialog.resumeSync" 
                active-text="开启（从上次断点继续）" 
                inactive-text="关闭（全量重新采集）"
                style="--el-switch-on-color: #409eff;"
              />
              <div class="switch-hint">开启后将从上次采集进度继续；关闭则从最新状态重新采集</div>
            </div>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="syncDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="syncDialog.fetching" @click="startFetch">
          {{ syncDialog.fetching ? '启动中...' : '开始采集' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 进度 Dialog -->
    <el-dialog 
      v-model="progressDialog.visible" 
      title="数据采集进度" 
      width="900px" 
      @close="stopProgressPolling" 
      class="progress-dialog"
      align-center
      :close-on-click-modal="false"
    >
      <div v-if="progressDialog.server" class="progress-content">
        <!-- 状态头部 -->
        <div class="progress-header">
          <el-icon class="progress-icon" :class="progressStatusClass"><component :is="progressStatusIcon" /></el-icon>
          <div class="progress-header-info">
            <div class="progress-title">{{ progressStatusText }}</div>
            <div class="progress-desc">
              <el-icon><Monitor /></el-icon> 
              服务器: <strong>{{ progressDialog.server.name || '未命名' }}</strong>
              <span class="mono-text">(Guild ID: {{ progressDialog.server.guildId }})</span>
            </div>
          </div>
        </div>

        <!-- 进度条 -->
        <div class="progress-bar-section" v-if="currentProgressTask">
          <div class="progress-bar-label">采集进度</div>
          <el-progress 
            :percentage="progressPercentage" 
            :stroke-width="12"
            :color="progressStatusClass === 'failed' ? '#f56c6c' : progressStatusClass === 'completed' ? '#67c23a' : '#409eff'"
            :status="progressStatusClass === 'failed' ? 'exception' : (progressStatusClass === 'completed' ? 'success' : '')"
          />
        </div>

        <!-- 核心数据统计 -->
        <div class="progress-stats-enhanced" v-if="currentProgressTask">
          <div class="stat-card">
            <div class="stat-icon request-icon">
              <el-icon><Connection /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-values">
                <span class="stat-current">{{ currentProgressTask.requestsSent || 0 }}</span>
                <span class="stat-sep">/</span>
                <span class="stat-total">{{ currentProgressTask.maxRequests || '-' }}</span>
              </div>
              <div class="stat-label">已请求 / 总请求数</div>
            </div>
          </div>
          
          <div class="stat-card">
            <div class="stat-icon fetch-icon">
              <el-icon><User /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-values">
                <span class="stat-current">{{ currentProgressTask.membersUnique || 0 }}</span>
                <span class="stat-sep">/</span>
                <span class="stat-total">{{ currentProgressTask.maxMembers || 0 }}</span>
              </div>
              <div class="stat-label">已采集 / 总采集数</div>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon dedup-icon">
              <el-icon><DataLine /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-values">
                <span class="stat-current">{{ currentProgressTask.membersUnique || 0 }}</span>
                <span class="stat-sep">/</span>
                <span class="stat-total">{{ currentProgressTask.totalRespondedMembers || 0 }}</span>
              </div>
              <div class="stat-label">去重数 / 总响应数</div>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon time-icon">
              <el-icon><Timer /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-values">
                <span class="stat-current">{{ formatMs(currentProgressTask.elapsedMs || 0) }}</span>
              </div>
              <div class="stat-label">总耗时</div>
            </div>
          </div>

          <div class="stat-card">
            <div class="stat-icon reconnect-icon">
              <el-icon><Refresh /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-values">
                <span class="stat-current">{{ currentProgressTask.reconnects || 0 }}</span>
                <template v-if="currentProgressTask.isPaused">
                  <span class="stat-sep">/</span>
                  <span class="stat-total">{{ currentProgressTask.maxInitialReconnects || 5 }}</span>
                </template>
              </div>
              <div class="stat-label">
                <template v-if="currentProgressTask.isPaused">
                  暂停中，{{ formatCountdown(currentProgressTask.nextRetryAtMs) }}
                </template>
                <template v-else-if="currentProgressTask.finalReconnectAttempts > 0">
                  最终重试 {{ currentProgressTask.finalReconnectAttempts }}/{{ currentProgressTask.maxFinalReconnects || 3 }}
                </template>
                <template v-else>
                  重连数 {{ currentProgressTask.reconnects || 0 }}/{{ currentProgressTask.maxInitialReconnects || 5 }}
                </template>
              </div>
            </div>
          </div>
        </div>
        <div class="progress-stats-enhanced" v-else>
          <div class="stat-card">
            <div class="stat-info" style="text-align:center;width:100%">
              <div class="stat-label" style="font-size:14px;padding:20px 0">暂无进度数据，请点击"开始同步"启动采集</div>
            </div>
          </div>
        </div>

        <!-- 详细信息 -->
        <div class="progress-detail-grid" v-if="currentProgressTask && !isTerminalStatus">
          <div class="detail-item">
            <span class="detail-label">采集状态</span>
            <el-tag type="warning" size="small">采集中</el-tag>
          </div>
          <div class="detail-item">
            <span class="detail-label">当前前缀</span>
            <span class="detail-value mono-text">{{ currentProgressTask.currentPrefix || '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">前缀进度</span>
            <span class="detail-value">{{ currentProgressTask.prefixesDone || 0 }} / {{ currentProgressTask.prefixesTotal || '-' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">重连次数</span>
            <span class="detail-value">{{ currentProgressTask.reconnects || 0 }}</span>
          </div>
        </div>

        <!-- 采集结果详情（仅在完成/失败时显示） -->
        <div class="progress-result-section" v-if="currentProgressTask && isTerminalStatus">
          <div class="result-header">
            <el-icon class="result-icon" :class="progressStatusClass">
              <component :is="progressStatusIcon" />
            </el-icon>
            <div class="result-title">
              <span :class="'result-status-text ' + progressStatusClass">{{ progressStatusText }}</span>
            </div>
          </div>

          <div class="result-grid">
            <div class="result-item">
              <div class="result-label">请求数 / 总请求数</div>
              <div class="result-value">{{ currentProgressTask.requestsSent || 0 }} / {{ currentProgressTask.maxRequests || '-' }}</div>
              <div class="result-sub">完成率: {{ getRequestRate(currentProgressTask) }}%</div>
            </div>
            <div class="result-item">
              <div class="result-label">去重数 / 总响应数</div>
              <div class="result-value">{{ currentProgressTask.membersUnique || 0 }} / {{ currentProgressTask.totalRespondedMembers || 0 }}</div>
              <div class="result-sub">去重率: {{ getDedupRate(currentProgressTask) }}%</div>
            </div>
            <div class="result-item">
              <div class="result-label">本次采集量 / 采集上限</div>
              <div class="result-value">{{ currentProgressTask.membersUnique || 0 }} / {{ currentProgressTask.maxMembers || '-' }}</div>
              <div class="result-sub">完成率: {{ getCollectRate(currentProgressTask) }}%</div>
            </div>
            <div class="result-item">
              <div class="result-label">本次耗时 / 总耗时</div>
              <div class="result-value">{{ formatMs(currentProgressTask.lastRequestTimeMs || 0) }} / {{ formatMs(currentProgressTask.elapsedMs || 0) }}</div>
              <div class="result-sub">总耗时: {{ formatElapsedTime(currentProgressTask) }}</div>
            </div>
            <div class="result-item">
              <div class="result-label">最后请求前缀</div>
              <div class="result-value mono-text">{{ currentProgressTask.lastPrefix || currentProgressTask.currentPrefix || '-' }}</div>
              <div class="result-sub">前缀深度: {{ (currentProgressTask.lastPrefix || currentProgressTask.currentPrefix || '').length || 0 }}</div>
            </div>
            <div class="result-item">
              <div class="result-label">重连次数</div>
              <div class="result-value">{{ currentProgressTask.reconnects || 0 }}</div>
              <div class="result-sub">累计响应: {{ formatResponseTime(currentProgressTask) }}</div>
            </div>
          </div>

          <!-- 失败/中断原因 -->
          <div class="result-failure" v-if="currentProgressTask.status === 'FAILED' || currentProgressTask.status === 'ERROR'">
            <el-alert
              type="error"
              show-icon
              :closable="false"
            >
              <template #title>
                <span>{{ currentProgressTask.failureReason || currentProgressTask.error || currentProgressTask.progressMessage || '未知错误' }}</span>
              </template>
            </el-alert>
          </div>
          <div class="result-failure" v-else-if="currentProgressTask.status === 'COMPLETED' || currentProgressTask.status === 'DONE'">
            <el-alert
              type="success"
              show-icon
              :closable="false"
              title="数据采集任务已完成"
            />
          </div>

          
        </div>

        <!-- 进度消息（仅采集中显示） -->
        <div class="progress-message" v-if="currentProgressTask && !isTerminalStatus">
          <!-- fetching 状态：显示精简进度信息 -->
          <div v-if="currentProgressTask.progressMessage && currentProgressTask.progressMessage.includes('[fetching]')" class="fetching-message">
            <div class="fetching-message-header">
              <el-icon class="is-loading" style="color: var(--color-primary)"><Loading /></el-icon>
              <span class="fetching-stage">[{{ currentProgressTask.status === 'RUNNING' ? 'fetching' : currentProgressTask.status.toLowerCase() }}]</span>
            </div>
            <div class="fetching-message-body">
              <span class="fetching-item"><strong>{{ currentProgressTask.requestsSent || 0 }}</strong></span>
              <span class="fetching-sep">·</span>
              <span class="fetching-item mono-text">{{ currentProgressTask.currentPrefix || '-' }}</span>
              <span class="fetching-sep">·</span>
              <span class="fetching-item">本次响应 <strong>{{ currentProgressTask.lastResponded || 0 }}</strong></span>
              <span class="fetching-sep">·</span>
              <span class="fetching-item">本次去重 <strong>{{ currentProgressTask.lastDeduped || 0 }}</strong></span>
              <span v-if="currentProgressTask.lastRequestTimeMs > 0" class="fetching-sep">·</span>
              <span v-if="currentProgressTask.lastRequestTimeMs > 0" class="fetching-item">本次耗时 <strong>{{ formatMsShort(currentProgressTask.lastRequestTimeMs) }}</strong></span>
            </div>
          </div>
          <!-- 暂停状态：显示重连倒计时 -->
          <div v-else-if="currentProgressTask.isPaused" class="fetching-message paused-message">
            <div class="fetching-message-header">
              <el-icon style="color: var(--color-warning)"><Timer /></el-icon>
              <span class="fetching-stage paused-stage">[paused]</span>
            </div>
            <div class="fetching-message-body">
              <span class="fetching-item">连接不稳定，已暂停</span>
              <span class="fetching-sep">·</span>
              <span class="fetching-item">初始重试 {{ currentProgressTask.reconnects || 0 }}/{{ currentProgressTask.maxInitialReconnects || 5 }}</span>
              <span class="fetching-sep">·</span>
              <span class="fetching-item"><strong>{{ formatCountdown(currentProgressTask.nextRetryAtMs) }}</strong></span>
            </div>
          </div>
          <!-- 其他状态：显示原格式 -->
          <el-alert
            v-else-if="currentProgressTask.progressMessage || currentProgressTask.progress"
            :title="currentProgressTask.progressMessage || currentProgressTask.progress"
            :type="currentProgressTask.status === 'FAILED' ? 'error' : 'info'"
            show-icon
            :closable="false"
          />
        </div>

        <!-- 实时更新提示 -->
        <div v-if="progressDialog.taskId && currentProgressTask && !isTerminalStatus" class="progress-tip">
          <el-icon class="is-loading"><Loading /></el-icon> 数据采集中，进度每 2 秒自动刷新...
        </div>
      </div>

      <template #footer>
        <div v-if="currentProgressTask && !isTerminalStatus && isRunningTask" style="display:flex;justify-content:flex-end;gap:8px;">
          <el-button type="warning" :disabled="progressDialog.stopping" @click="handlePauseSync">
            <el-icon><VideoPause /></el-icon> {{ progressDialog.stopping ? '正在停止...' : '暂停采集' }}
          </el-button>
          <el-button @click="progressDialog.visible = false">关闭</el-button>
        </div>
        <div v-else style="display:flex;justify-content:flex-end;gap:8px;">
          <el-button @click="progressDialog.visible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 成员明细 Dialog -->
    <el-dialog v-model="memberDialog.visible" :title="memberDialogTitle" width="920px" :close-on-click-modal="false" @close="stopMemberAutoRefresh" class="member-dialog">
      <div class="member-dialog-toolbar">
        <el-input
          v-model="memberDialog.search"
          size="small"
          placeholder="搜索成员昵称 / ID"
          :prefix-icon="Search"
          clearable
          style="width: 260px"
          @keyup.enter="searchMembers"
          @clear="searchMembers"
        />
        <el-select
          v-model="memberDialog.discordStatus"
          size="small"
          placeholder="Discord状态"
          clearable
          style="width: 140px; margin-left: 10px"
          @change="searchMembers"
        >
          <el-option label="全部" value="" />
          <el-option label="在线" value="online" />
          <el-option label="空闲" value="idle" />
          <el-option label="请勿打扰" value="dnd" />
          <el-option label="离线" value="offline" />
        </el-select>
        <el-tag size="small" type="info" effect="plain" style="margin-left: 10px">共 {{ memberDialog.total }} 名成员</el-tag>
      </div>
      <el-table
        :data="memberDialog.members"
        v-loading="memberDialog.loading"
        stripe
        size="small"
        height="380"
        style="width: 100%"
      >
        <el-table-column label="成员" min-width="160">
          <template #default="{ row }">
            <div class="member-cell-simple">
              <span class="member-name-text">{{ row.displayName || row.globalName || row.username || '未知成员' }}</span>
              <el-tag v-if="row.isBot" size="small" type="warning" effect="plain" style="margin-left:6px">BOT</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="用户名" width="130">
          <template #default="{ row }">
            <span class="mono">{{ row.username || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="昵称" width="130">
          <template #default="{ row }">
            <span>{{ row.nick || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="ID" width="160">
          <template #default="{ row }">
            <div class="id-cell">
              <span class="mono">{{ row.userId || '-' }}</span>
              <el-button
                size="small"
                link
                type="primary"
                :icon="CopyDocument"
                @click="copyText(row.userId)"
                title="复制 ID"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="加入时间" width="110">
          <template #default="{ row }">
            <span class="text-muted">{{ formatDate(row.joinedAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Discord状态" width="100">
          <template #default="{ row }">
            <el-tag
              v-if="row.discordStatus === 'online'"
              size="small"
              type="success"
            >在线</el-tag>
            <el-tag
              v-else-if="row.discordStatus === 'idle'"
              size="small"
              type="warning"
            >空闲</el-tag>
            <el-tag
              v-else-if="row.discordStatus === 'dnd'"
              size="small"
              type="danger"
            >请勿打扰</el-tag>
            <el-tag
              v-else-if="row.discordStatus === 'offline'"
              size="small"
              type="info"
            >离线</el-tag>
            <el-tag
              v-else
              size="small"
            >未知</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="添加状态" width="100">
          <template #default="{ row }">
            <el-tag 
              v-if="row.friendStatus === 0 || row.friendStatus === null" 
              size="small" 
              type="info"
            >待添加</el-tag>
            <el-tag 
              v-else-if="row.friendStatus === 1" 
              size="small" 
              type="warning"
            >已分配</el-tag>
            <el-tag 
              v-else-if="row.friendStatus === 2" 
              size="small" 
              type="success"
            >添加成功</el-tag>
            <el-tag 
              v-else-if="row.friendStatus === 3" 
              size="small" 
              type="danger"
            >添加失败</el-tag>
            <el-tag 
              v-else 
              size="small"
            >未知</el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无成员数据，请先同步" :image-size="80" />
        </template>
      </el-table>
      <div class="member-dialog-pagination">
        <el-pagination
          v-model:current-page="memberDialog.page"
          v-model:page-size="memberDialog.size"
          :total="memberDialog.total"
          :page-sizes="[20, 50, 100, 200]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="onMemberPageSizeChange"
          @current-change="onMemberPageChange"
        />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Plus, Edit, Delete, Download, DataLine, MagicStick,
  Loading, CircleCheck, Warning, Close, Monitor, Connection, User, Search,
  CopyDocument, InfoFilled, Timer, Refresh, VideoPause
} from '@element-plus/icons-vue'
import { useAccountsStore } from '@/stores/accounts'
import { useGuildServersStore } from '@/stores/guildServers'

const accounts = useAccountsStore()
const guildServers = useGuildServersStore()

const filters = reactive({
  discordAccountId: null
})

// 服务器列表和加载状态直接从 store 获取，不再使用本地 ref

const accountOptions = computed(() => accounts.accounts || [])

// 全局变量（用于onMounted中恢复状态）
const fetchingServerId = ref(null)
const fetchingTaskId = ref(null)

// 编辑 Dialog
const editDialog = reactive({
  visible: false,
  isEdit: false,
  form: {
    id: null,
    discordAccountId: null,
    guildUrl: '',
    guildId: '',
    channelId: '',
    name: ''
  }
})

// 同步 Dialog
const syncDialog = reactive({
  visible: false,
  fetching: false,
  server: null,
  token: '', 
  resumeSync: true,
  config: {
    fetchLimit: 100000,
    requestInterval: 3,
    requestCount: 100,
    maxDepth: 5,
    maxRequests: 1000
  }
})

// 进度 Dialog
const progressDialog = reactive({
  visible: false,
  server: null,
  taskId: null,
  stopping: false
})

// 成员明细 Dialog
const memberDialog = reactive({
  visible: false,
  server: null,
  members: [],
  total: 0,
  loading: false,
  search: '',
  discordStatus: '',  // Discord 原生状态筛选
  page: 0,
  size: 50,
  totalPages: 0
})

const memberDialogTitle = computed(() => {
  const name = memberDialog.server?.name || memberDialog.server?.guildId || '服务器'
  return `成员明细 - ${name}`
})

// 多任务同步状态管理
// serverId -> taskId 的映射
const syncTasksMap = reactive(new Map())
// taskId -> timer 的映射
const syncTimersMap = reactive(new Map())
// taskId -> progressTask 的映射（用于对话框显示）
const progressTasksMap = reactive(new Map())

// 计算某个服务器是否正在同步
const isServerSyncing = (serverId) => syncTasksMap.has(serverId)

// 获取某个服务器的任务ID
const getServerTaskId = (serverId) => syncTasksMap.get(serverId)

// 计算进度百分比 - 基于请求数/总请求数
const progressPercentage = computed(() => {
  if (!progressDialog.taskId) return 0
  const task = progressTasksMap.get(progressDialog.taskId)
  if (!task) return 0
  const maxRequests = task.maxRequests || 1
  const requestsSent = task.requestsSent || 0
  return Math.min(100, Math.round((requestsSent / maxRequests) * 100))
})

const statusMap = {
  PENDING: { icon: Loading, text: '等待开始', class: 'pending' },
  RUNNING: { icon: Loading, text: '抓取进行中', class: 'running' },
  COMPLETED: { icon: CircleCheck, text: '采集完成', class: 'completed' },
  FAILED: { icon: Warning, text: '采集中断', class: 'failed' },
  DONE: { icon: CircleCheck, text: '采集完成', class: 'completed' },
  ERROR: { icon: Warning, text: '采集失败', class: 'failed' },
  NO_DATA: { icon: InfoFilled, text: '暂无记录', class: 'pending' },
  UNKNOWN: { icon: Warning, text: '状态未知', class: 'failed' }
}

const progressStatus = computed(() => {
  if (!progressDialog.taskId) return statusMap.PENDING
  const task = progressTasksMap.get(progressDialog.taskId)
  const status = task?.status || 'PENDING'
  return statusMap[status] || statusMap.PENDING
})
const progressStatusIcon = computed(() => progressStatus.value.icon)
const progressStatusText = computed(() => progressStatus.value.text)
const progressStatusClass = computed(() => progressStatus.value.class)

// 当前进度对话框显示的任务
const currentProgressTask = computed(() => {
  if (!progressDialog.taskId) return null
  return progressTasksMap.get(progressDialog.taskId)
})

// 是否为终态（完成/失败/错误）
const isTerminalStatus = computed(() => {
  if (!currentProgressTask.value) return false
  const status = currentProgressTask.value.status
  return status === 'COMPLETED' || status === 'DONE' || status === 'FAILED' || status === 'ERROR'
})

// 是否有正在运行的任务
const isRunningTask = computed(() => {
  if (!currentProgressTask.value) return false
  const status = currentProgressTask.value.status
  return status === 'RUNNING' || status === 'PENDING'
})

// 结果详情辅助函数
function getRequestRate(task) {
  if (!task) return 0
  const total = task.maxRequests || 0
  if (total <= 0) return 0
  return Math.min(100, Math.round((task.requestsSent || 0) / total * 100))
}

function getDedupRate(task) {
  if (!task) return 0
  const total = task.totalRespondedMembers || 0
  if (total <= 0) return 0
  return Math.min(100, Math.round((task.membersUnique || 0) / total * 100))
}

function getCollectRate(task) {
  if (!task) return 0
  const total = task.maxMembers || 0
  if (total <= 0) return 0
  return Math.min(100, Math.round((task.membersUnique || 0) / total * 100))
}

function formatElapsedTime(task) {
  if (!task) return '-'
  if (task.elapsedMs && task.elapsedMs > 0) {
    return formatMs(task.elapsedMs)
  }
  if (task.startedAt && task.completedAt) {
    const elapsed = task.completedAt - task.startedAt
    return formatMs(elapsed)
  }
  if (task.startedAt) {
    return formatMs(Date.now() - task.startedAt)
  }
  return '-'
}

function formatStartTime(task) {
  if (!task || !task.startedAt) return '-'
  const d = new Date(task.startedAt)
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}

function formatResponseTime(task) {
  if (!task) return '-'
  const ms = task.totalResponseTimeMs || 0
  return formatMs(ms)
}

function formatMs(ms) {
  if (ms <= 0) return '0s'
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return seconds + 's'
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60
  if (minutes < 60) return minutes + 'm ' + remainSeconds + 's'
  const hours = Math.floor(minutes / 60)
  const remainMinutes = minutes % 60
  return hours + 'h ' + remainMinutes + 'm ' + remainSeconds + 's'
}

/** 格式化倒计时显示（传入目标时间戳） */
function formatCountdown(targetMs) {
  if (!targetMs || targetMs <= 0) return '计算中...'
  const now = Date.now()
  const remaining = targetMs - now
  if (remaining <= 0) return '即将重试...'
  const seconds = Math.floor(remaining / 1000)
  if (seconds < 60) return seconds + '秒后重试'
  const minutes = Math.floor(seconds / 60)
  const remainSeconds = seconds % 60
  if (minutes < 60) return minutes + '分 ' + remainSeconds + '秒后重试'
  const hours = Math.floor(minutes / 60)
  const remainMinutes = minutes % 60
  return hours + '时 ' + remainMinutes + '分后重试'
}

/** 格式化短耗时显示（低于1s显示毫秒） */
function formatMsShort(ms) {
  if (!ms || ms <= 0) return '-'
  if (ms < 1000) return ms + 'ms'
  return formatMs(ms)
}

// 方法
async function loadServers() {
  try {
    await guildServers.fetchServers(filters.discordAccountId)
  } catch (e) {
    ElMessage.error('加载服务器列表失败')
  }
}

function openEditDialog(server = null) {
  editDialog.isEdit = !!server
  if (server) {
    editDialog.form = {
      id: server.id,
      discordAccountId: server.discordAccountId,
      guildUrl: server.guildUrl || '',
      guildId: server.guildId || '',
      channelId: server.channelId || '',
      name: server.name || ''
    }
  } else {
    editDialog.form = {
      id: null,
      discordAccountId: filters.discordAccountId || (accountOptions.value[0]?.id) || null,
      guildUrl: '',
      guildId: '',
      channelId: '',
      name: ''
    }
  }
  editDialog.visible = true
}

async function parseUrl() {
  const url = editDialog.form.guildUrl
  if (!url) {
    ElMessage.warning('请输入服务器 URL')
    return
  }
  try {
    const result = await guildServers.resolveLink(url, editDialog.form.discordAccountId)
    if (result.success) {
      editDialog.form.guildId = result.guildId || editDialog.form.guildId
      editDialog.form.channelId = result.channelId || editDialog.form.channelId
      if (result.serverName) {
        editDialog.form.name = result.serverName
        ElMessage.success(`解析成功: ${result.serverName}`)
      } else {
        ElMessage.success('URL 解析成功')
      }
    } else {
      ElMessage.error(result.message || '解析失败')
    }
  } catch (e) {
    ElMessage.error('解析失败')
  }
}

async function saveServer() {
  if (!editDialog.form.discordAccountId) {
    ElMessage.warning('请选择所属账号')
    return
  }
  try {
    const savedServer = await guildServers.saveServer(editDialog.form)
    ElMessage.success('保存成功')
    editDialog.visible = false
    // 如果当前过滤条件与保存的服务器账号不同，清除过滤以显示所有服务器
    if (filters.discordAccountId && savedServer && savedServer.discordAccountId !== filters.discordAccountId) {
      filters.discordAccountId = null
    }
    await loadServers()
  } catch (e) {
    ElMessage.error('保存失败')
  }
}

async function confirmDelete(server) {
  try {
    await ElMessageBox.confirm(`确定删除服务器「${server.name || server.guildId}」及其所有成员数据？`, '删除确认', {
      type: 'warning'
    })
    await guildServers.deleteServer(server.id)
    ElMessage.success('删除成功')
    await loadServers()
  } catch (e) {}
}

function openSyncDialog(server) {
  syncDialog.server = server
  syncDialog.resumeSync = true
  // 回填完整 token（非脱敏），供展示与编辑
  syncDialog.token = server.accountToken || server.token || ""
  // 加载商户配置作为默认值
  guildServers.loadMerchantConfig().then(config => {
    if (config) {
      syncDialog.config = {
        fetchLimit: config.fetchLimit || 100000,
        requestInterval: config.requestInterval || 3,
        requestCount: config.requestCount || 100,
        maxDepth: config.maxDepth || 5,
        maxRequests: config.maxRequests || 1000
      }
    }
  }).catch(() => {})
  syncDialog.visible = true
}

function maskToken(token) {
  if (!token) return '-'
  if (token.length <= 10) return token.substring(0, 4) + '****'
  return token.substring(0, 6) + '****' + token.substring(token.length - 4)
}

async function startFetch() {
  if (!syncDialog.server) return
  syncDialog.fetching = true
  try {
    const tokenValue = (syncDialog.token || '').trim()
    if (!tokenValue) {
      ElMessage.error('请输入完整的 Token，无法同步')
      syncDialog.fetching = false
      return
    }

    const result = await guildServers.startFetch({
      token: tokenValue,
      link: syncDialog.server.guildId,
      guildServerId: syncDialog.server.id,
      discordAccountId: syncDialog.server.discordAccountId,
      channelId: syncDialog.server.channelId,
      maxMembers: syncDialog.config.fetchLimit,
      pageDelay: syncDialog.config.requestInterval,
      maxDepth: syncDialog.config.maxDepth,
      maxRequests: syncDialog.config.maxRequests,
      resumeSync: syncDialog.resumeSync
    })

    if (result.success) {
      // 关闭同步弹窗
      syncDialog.visible = false
      await nextTick()
      // 设置该服务器为同步中状态 - 保存到 Map
      syncTasksMap.set(syncDialog.server.id, result.taskId)
      // 初始化任务状态为 PENDING，显示进度对话框而不是空数据
      progressTasksMap.set(result.taskId, {
        status: 'PENDING',
        progressMessage: '正在初始化采集任务...',
        currentPrefix: '',
        requestsSent: 0,
        membersUnique: 0,
        maxRequests: syncDialog.config.maxRequests,
        maxMembers: syncDialog.config.fetchLimit,
        prefixesDone: 0,
        prefixesTotal: 0,
        reconnects: 0,
        completedPrefixCount: 0,
        totalRespondedMembers: 0,
        totalResponseTimeMs: 0,
        lastResponded: 0,
        lastDeduped: 0,
        lastRequestTimeMs: 0,
        elapsedMs: 0
      })
      // 自动打开进度对话框
      progressDialog.server = syncDialog.server
      progressDialog.taskId = result.taskId
      progressDialog.visible = true
      // 启动该任务的进度轮询
      startTaskPolling(result.taskId, syncDialog.server.id)
    } else {
      ElMessage.error(result.message || '启动同步失败')
    }
  } catch (e) {
    console.error('启动同步失败:', e)
    ElMessage.error('启动同步失败')
  } finally {
    syncDialog.fetching = false
  }
}

async function openProgressDialog(server, taskId = null) {
  progressDialog.server = server
  progressDialog.visible = true
  progressDialog.stopping = false

  // 如果没传 taskId，尝试从 Map 或后端获取
  if (!taskId) {
    // 检查是否有该服务器正在进行的同步
    if (syncTasksMap.has(server.id)) {
      taskId = syncTasksMap.get(server.id)
    }
  }

  progressDialog.taskId = taskId

  if (taskId) {
    // 如果该任务的轮询未启动，启动它
    if (!syncTimersMap.has(taskId)) {
      startTaskPolling(taskId, server.id)
    }
  } else {
    // 没有正在进行的任务，从后端获取最近的采集结果
    try {
      const latestTask = await guildServers.getLatestTask(server.id)
      if (latestTask && latestTask.status) {
        // 生成一个临时 taskId 用于显示
        const tempTaskId = 'latest_' + server.id
        progressDialog.taskId = tempTaskId
        progressTasksMap.set(tempTaskId, {
          ...latestTask,
          taskId: tempTaskId,
          // 计算 maxRequests 和 maxMembers（从已请求数推算）
          maxRequests: latestTask.requestsSent || 1000,
          maxMembers: latestTask.membersUnique || 0
        })
      } else {
        // 没有历史记录，显示空状态
        progressTasksMap.set('empty_' + server.id, {
          status: 'NO_DATA',
          progressMessage: '暂无采集记录',
          requestsSent: 0,
          membersUnique: 0,
          prefixesDone: 0,
          prefixesTotal: 0
        })
        progressDialog.taskId = 'empty_' + server.id
      }
    } catch (e) {
      console.error('获取最近采集记录失败:', e)
    }
  }
}

// 为单个任务启动进度轮询
function startTaskPolling(taskId, serverId) {
  // 清除已有的定时器
  if (syncTimersMap.has(taskId)) {
    clearInterval(syncTimersMap.get(taskId))
  }

  let consecutiveErrors = 0

  const timer = setInterval(async () => {
    try {
      let task = await guildServers.pollTask(taskId)
      // 检查后端返回是否为错误响应（如任务不存在）
      if (task && task.success === false) {
        consecutiveErrors++
        // 如果连续3次获取不到任务，停止轮询并提示
        if (consecutiveErrors >= 3) {
          console.warn('连续获取任务失败，可能任务已丢失')
          stopTaskPolling(taskId, serverId)
          const existingTask = progressTasksMap.get(taskId)
          if (existingTask && existingTask.status !== 'COMPLETED' && existingTask.status !== 'FAILED' && existingTask.status !== 'DONE') {
            progressTasksMap.set(taskId, {
              ...existingTask,
              status: 'FAILED',
              progressMessage: '任务获取失败：' + (task.message || '未知错误')
            })
          }
          ElMessage.error('获取采集任务状态失败：' + (task.message || '任务不存在'))
          return
        }
        // 等待下一次轮询
        return
      }
      
      if (!task) {
        // 尝试从数据库获取最近的任务
        const dbTask = await guildServers.getLatestTask(serverId)
        if (dbTask && dbTask.status) {
          task = { ...dbTask, taskId }
        }
      }
      
      if (task) {
        consecutiveErrors = 0
        progressTasksMap.set(taskId, task)
        if (task.status === 'COMPLETED' || task.status === 'FAILED' || task.status === 'DONE' || task.status === 'ERROR') {
          stopTaskPolling(taskId, serverId)
          progressDialog.stopping = false
          // 清理 syncTasksMap，使后续点击"进度"按钮走已完成的历史数据分支
          if (serverId) {
            syncTasksMap.delete(serverId)
          }
          await loadServers()
          // 如果成员明细对话框正在显示该服务器，刷新成员列表
          if (memberDialog.visible && memberDialog.server?.id === serverId) {
            stopMemberAutoRefresh()
            await loadMemberPage()
          }
          // 采集完成/失败时不自动关闭对话框，保留结果供查看
          if (task.status === 'COMPLETED' || task.status === 'DONE') {
            ElMessage.success('数据采集已完成')
          } else if (task.status === 'FAILED' || task.status === 'ERROR') {
            ElMessage.error('数据采集失败：' + (task.progressMessage || task.error || '未知错误'))
          }
        }
      }
    } catch (e) {
      console.warn('获取进度失败', e)
      consecutiveErrors++
      if (consecutiveErrors >= 5) {
        console.warn('连续获取进度失败过多，停止轮询')
        stopTaskPolling(taskId, serverId)
      }
    }
  }, 2000)

  syncTimersMap.set(taskId, timer)
}

// 停止单个任务的进度轮询
function stopTaskPolling(taskId, serverId) {
  const timer = syncTimersMap.get(taskId)
  if (timer) {
    clearInterval(timer)
    syncTimersMap.delete(taskId)
  }
  syncTasksMap.delete(serverId)
  // 保留 progressTasksMap 中的任务数据，供完成后展示
  // 仅在任务不存在时清理
}

async function loadLatestTaskStatus(serverId) {
  try {
    const tasks = await guildServers.getActiveTasks()
    let runningTask = null
    let completedTask = null
    for (const [taskId, task] of Object.entries(tasks || {})) {
      if (task.guildServerId === serverId) {
        const isTerminal = task.status === 'COMPLETED' || task.status === 'DONE' || task.status === 'FAILED' || task.status === 'ERROR'
        if (!isTerminal && !runningTask) {
          runningTask = { ...task, taskId }
        }
        if (!completedTask) {
          completedTask = { ...task, taskId }
        }
      }
    }
    let taskToShow = runningTask || completedTask

    if (!taskToShow) {
      const dbTask = await guildServers.getLatestTask(serverId)
      if (dbTask && dbTask.status) {
        taskToShow = { ...dbTask, taskId: 'db_' + serverId }
      }
    }

    if (taskToShow) {
      progressTasksMap.set(taskToShow.taskId, taskToShow)
      const isTerminal = taskToShow.status === 'COMPLETED' || taskToShow.status === 'DONE' || taskToShow.status === 'FAILED' || taskToShow.status === 'ERROR'
      if (!isTerminal) {
        syncTasksMap.set(serverId, taskToShow.taskId)
        startTaskPolling(taskToShow.taskId, serverId)
      }
    }
  } catch (e) {
    console.warn('获取最近任务失败', e)
  }
}

async function openMemberDialog(server) {
  memberDialog.server = server
  memberDialog.visible = true
  memberDialog.search = ''
  memberDialog.discordStatus = ''
  memberDialog.members = []
  memberDialog.total = 0
  memberDialog.page = 0
  memberDialog.size = 50
  memberDialog.totalPages = 0
  memberDialog.loading = true
  try {
    await loadMemberPage()
  } catch (e) {
    ElMessage.error('加载成员列表失败')
  } finally {
    memberDialog.loading = false
  }
  
  // 如果该服务器正在同步，定时刷新成员列表
  if (server && isServerSyncing(server.id)) {
    startMemberAutoRefresh(server.id)
  }
}

// 成员列表自动刷新（同步进行中时每5秒刷新一次）
let memberRefreshTimer = null
function startMemberAutoRefresh(serverId) {
  stopMemberAutoRefresh()
  memberRefreshTimer = setInterval(async () => {
    if (!isServerSyncing(serverId) || !memberDialog.visible || memberDialog.server?.id !== serverId) {
      stopMemberAutoRefresh()
      return
    }
    try {
      await loadMemberPage()
    } catch (e) {
      // 静默失败
    }
  }, 5000)
}
function stopMemberAutoRefresh() {
  if (memberRefreshTimer) {
    clearInterval(memberRefreshTimer)
    memberRefreshTimer = null
  }
}

async function loadMemberPage() {
  if (!memberDialog.server) return
  memberDialog.loading = true
  try {
    const params = {
      page: memberDialog.page,
      size: memberDialog.size,
      keyword: memberDialog.search || undefined
    }
    if (memberDialog.discordStatus && memberDialog.discordStatus.trim()) {
      params.discordStatus = memberDialog.discordStatus.trim()
    }
    const result = await guildServers.fetchMembersList(memberDialog.server.id, params)
    memberDialog.members = result.list || []
    memberDialog.total = result.total || 0
    memberDialog.totalPages = result.totalPages || 0
  } catch (e) {
    console.warn('加载成员列表失败', e)
  } finally {
    memberDialog.loading = false
  }
}

function onMemberPageChange(page) {
  memberDialog.page = page - 1
  loadMemberPage()
}

function onMemberPageSizeChange(size) {
  memberDialog.size = size
  memberDialog.page = 0
  loadMemberPage()
}

function searchMembers() {
  memberDialog.page = 0
  loadMemberPage()
}

function stopProgressPolling() {
  // 进度对话框关闭时，任务继续在后台轮询
  // 因为我们使用了每个任务独立的定时器，所以这里不需要停止轮询
  // startTaskPolling 会继续在后台运行
}

async function handlePauseSync() {
  if (!progressDialog.taskId || progressDialog.stopping) return
  progressDialog.stopping = true
  try {
    const result = await guildServers.stopTask(progressDialog.taskId)
    if (result && result.success) {
      ElMessage.success(result.message || '已请求停止，当前请求完成后将自动保存并停止')
      // 轮询会继续检测任务状态变化，完成后自动切换到完成视图
    } else {
      ElMessage.error(result?.message || '停止失败')
      progressDialog.stopping = false
    }
  } catch (e) {
    ElMessage.error('停止请求失败: ' + (e.message || '未知错误'))
    progressDialog.stopping = false
  }
}

function formatTime(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return t
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function formatDate(t) {
  if (!t) return '-'
  const d = new Date(t)
  if (isNaN(d.getTime())) return String(t)
  const pad = n => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`
}

async function copyText(text) {
  if (!text) {
    ElMessage.warning('没有可复制的内容')
    return
  }
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    ElMessage.success('已复制到剪贴板')
  }
}

onMounted(async () => {
  if (accounts.accounts.length === 0) {
    try { await accounts.fetchAccounts() } catch (e) {}
  }
  await loadServers()
  
  // 恢复所有正在进行的采集任务
  try {
    const tasks = await guildServers.getActiveTasks()
    if (tasks && typeof tasks === 'object') {
      for (const [taskId, taskState] of Object.entries(tasks)) {
        if (taskState && taskState.guildServerId) {
          const isTerminal = taskState.status === 'COMPLETED' || taskState.status === 'DONE' || taskState.status === 'FAILED' || taskState.status === 'ERROR'
          if (!isTerminal) {
            const serverId = Number(taskState.guildServerId)
            syncTasksMap.set(serverId, taskId)
            progressTasksMap.set(taskId, taskState)
            startTaskPolling(taskId, serverId)
          }
        }
      }
    }
  } catch (e) {
    console.warn('获取活跃任务失败', e)
  }
})

onUnmounted(() => {
  stopProgressPolling()
})
</script>

<style scoped>
.guilds-page {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--color-bg);
  overflow: hidden;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px 16px;
  background: var(--color-bg-2);
  border-bottom: 1px solid var(--color-border);
}

.page-title { margin: 0; font-size: 18px; font-weight: 700; color: var(--color-text); }
.page-desc { margin: 4px 0 0; font-size: 12px; color: var(--color-text-2); }
.header-actions { display: flex; align-items: center; gap: 12px; }

.page-body {
  flex: 1;
  min-height: 0;
  padding: 20px 24px;
  overflow: auto;
}

.server-cell { display: flex; align-items: center; gap: 10px; }
.server-icon {
  width: 40px; height: 40px; border-radius: 10px; object-fit: cover;
  background: var(--color-bg-3);
}
.server-icon.placeholder {
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 700;
  background: linear-gradient(135deg, var(--color-primary), var(--color-pink));
}
.server-info { flex: 1; min-width: 0; }
.server-name { font-size: 14px; font-weight: 600; color: var(--color-text); }
.server-sub { font-size: 11px; color: var(--color-text-3); font-family: monospace; }

.member-count { font-weight: 600; color: var(--color-primary); }

.mono { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.text-muted { color: var(--color-text-3); font-size: 12px; }

.sync-info { padding: 8px 0; }
.form-tip { font-size: 11px; color: var(--color-text-3); margin-top: 2px; }

.progress-content { padding: 8px 0; }
.progress-header { display: flex; align-items: center; gap: 14px; margin-bottom: 20px; }
.progress-icon {
  font-size: 48px;
  padding: 12px;
  border-radius: 50%;
}
.progress-icon.pending { color: var(--color-text-3); }
.progress-icon.running { color: var(--color-primary); animation: spin 2s linear infinite; }
.progress-icon.completed { color: #67c23a; }
.progress-icon.failed { color: #f56c6c; }

.progress-title { font-size: 16px; font-weight: 600; color: var(--color-text); }
.progress-desc { font-size: 12px; color: var(--color-text-3); margin-top: 4px; }
.progress-task { font-size: 11px; color: var(--color-text-4); margin-top: 2px; font-family: monospace; }

.progress-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.stat-box {
  background: var(--color-bg-2);
  border-radius: 10px;
  padding: 14px;
  text-align: center;
}
.stat-box.highlight {
  background: linear-gradient(135deg, rgba(88,101,242,0.15), rgba(255,100,150,0.1));
}
.stat-value { font-size: 18px; font-weight: 700; color: var(--color-text); word-break: break-all; }
.stat-label { font-size: 11px; color: var(--color-text-3); margin-top: 4px; }

.progress-message { margin-bottom: 12px; }

.fetching-message {
  padding: 12px 16px;
  background: linear-gradient(135deg, rgba(64,158,255,0.08), rgba(64,158,255,0.02));
  border: 1px solid rgba(64,158,255,0.2);
  border-radius: 10px;
}
.fetching-message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.fetching-stage {
  font-weight: 600;
  color: var(--color-primary);
  font-size: 13px;
}
.fetching-message-body {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--color-text);
}
.fetching-item strong {
  color: var(--color-primary);
  font-size: 15px;
}
.fetching-sep {
  color: var(--color-text-3);
}
.progress-tip {
  margin-top: 12px;
  padding: 10px;
  background: var(--color-bg-2);
  border-radius: 8px;
  font-size: 12px;
  color: var(--color-text-2);
  display: flex;
  align-items: center;
  gap: 6px;
}

/* ========== Enhanced Progress Dialog Styles ========== */
.progress-header-info { flex: 1; }
.progress-desc strong { color: var(--color-text); font-weight: 600; }
.progress-desc .mono-text { color: var(--color-text-3); margin-left: 8px; }

.progress-bar-section {
  margin-bottom: 20px;
}
.progress-bar-label {
  font-size: 12px;
  color: var(--color-text-2);
  margin-bottom: 8px;
  font-weight: 500;
}

.progress-stats-enhanced {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 20px;
}
.stat-card {
  background: var(--color-bg-2);
  border-radius: 12px;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  border: 1px solid var(--color-border);
}
.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}
.stat-icon.request-icon {
  background: linear-gradient(135deg, rgba(64,158,255,0.15), rgba(64,158,255,0.05));
  color: #409eff;
}
.stat-icon.fetch-icon {
  background: linear-gradient(135deg, rgba(103,194,58,0.15), rgba(103,194,58,0.05));
  color: #67c23a;
}
.stat-icon.dedup-icon {
  background: linear-gradient(135deg, rgba(230,162,60,0.15), rgba(230,162,60,0.05));
  color: #e6a23c;
}
.stat-icon.time-icon {
  background: linear-gradient(135deg, rgba(144,147,153,0.15), rgba(144,147,153,0.05));
  color: #909399;
}
.stat-icon.reconnect-icon {
  background: linear-gradient(135deg, rgba(245,108,108,0.15), rgba(245,108,108,0.05));
  color: #f56c6c;
}
.stat-info { flex: 1; min-width: 0; }
.stat-values {
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.stat-current {
  font-size: 24px;
  font-weight: 700;
  color: var(--color-text);
}
.stat-sep {
  font-size: 18px;
  color: var(--color-text-3);
}
.stat-total {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-3);
}
.stat-label {
  font-size: 12px;
  color: var(--color-text-2);
  margin-top: 4px;
}

.progress-detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 20px;
  margin-bottom: 16px;
  padding: 14px;
  background: var(--color-bg-2);
  border-radius: 10px;
}
.detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  border-bottom: 1px dashed var(--color-border);
}
.detail-item:nth-last-child(-n+2) {
  border-bottom: none;
}
.detail-label {
  font-size: 12px;
  color: var(--color-text-2);
}
.detail-value {
  font-size: 13px;
  color: var(--color-text);
  font-weight: 500;
}
.mono-text {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
}

.progress-tip.completed {
  background: linear-gradient(135deg, rgba(103,194,58,0.15), rgba(103,194,58,0.05));
  color: #67c23a;
  border: 1px solid rgba(103,194,58,0.2);
}

.progress-tip.failed {
  background: linear-gradient(135deg, rgba(245,108,108,0.15), rgba(245,108,108,0.05));
  color: #f56c6c;
  border: 1px solid rgba(245,108,108,0.2);
}

/* ========== 采集结果详情 ========== */
.progress-result-section {
  margin-top: 8px;
}

.result-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: var(--color-bg-2);
  border-radius: 10px;
  margin-bottom: 14px;
  border: 1px solid var(--color-border);
}

.result-icon {
  font-size: 32px;
}

.result-icon.completed { color: #67c23a; }
.result-icon.failed { color: #f56c6c; }
.result-icon.running { color: #409eff; }
.result-icon.pending { color: #e6a23c; }

.result-title {
  display: flex;
  flex-direction: column;
}

.result-status-text {
  font-size: 18px;
  font-weight: 600;
}
.result-status-text.completed { color: #67c23a; }
.result-status-text.failed { color: #f56c6c; }
.result-status-text.running { color: #409eff; }
.result-status-text.pending { color: #e6a23c; }

.result-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 10px;
  margin-bottom: 14px;
}

.result-item {
  padding: 10px 12px;
  background: var(--color-bg-2);
  border-radius: 8px;
  border: 1px solid var(--color-border);
}

.result-label {
  font-size: 11px;
  color: var(--color-text-2);
  margin-bottom: 4px;
}

.result-value {
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
}

.result-sub {
  font-size: 11px;
  color: var(--color-text-3);
  margin-top: 3px;
}

.result-failure {
  margin-bottom: 14px;
}

.result-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 6px;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ========== 成员明细 Dialog ========== */
.member-dialog-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.member-dialog-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px solid var(--color-border);
}
.member-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.member-avatar {
  flex-shrink: 0;
  background: linear-gradient(135deg, var(--color-primary), var(--color-pink));
  color: #fff;
  font-weight: 600;
}
.member-cell-simple {
  display: flex;
  align-items: center;
}
.member-name-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
}
.id-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}
.action-cell { display: flex; flex-wrap: nowrap; white-space: nowrap; align-items: center; gap: 0; }

/* Progress dialog - centered, full-height, scrollable body */
.progress-dialog {
  max-height: 85vh;
}

.progress-dialog :deep(.el-dialog) {
  max-height: 85vh;
  max-width: 95vw;
  display: flex;
  flex-direction: column;
}

.progress-dialog :deep(.el-dialog__header) {
  flex-shrink: 0;
  padding: 16px 20px;
}

.progress-dialog :deep(.el-dialog__body) {
  flex: 1;
  overflow-y: auto;
  padding: 12px 20px;
}

.progress-dialog :deep(.el-dialog__footer) {
  flex-shrink: 0;
  padding: 12px 20px;
  border-top: 1px solid var(--el-border-color-lighter);
}

/* Responsive: on smaller screens */
@media screen and (max-width: 1200px) {
  .progress-dialog :deep(.el-dialog) {
    width: 95vw !important;
    max-height: 90vh;
  }
  .progress-stats-enhanced {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media screen and (max-width: 768px) {
  .progress-dialog :deep(.el-dialog) {
    width: 98vw !important;
    max-height: 95vh;
  }
  .progress-stats-enhanced {
    grid-template-columns: 1fr;
  }
}

/* Sync dialog centering */
.sync-dialog {
  max-height: 90vh;
}

.sync-dialog :deep(.el-dialog) {
  max-height: 90vh;
  display: flex;
  flex-direction: column;
}

.sync-dialog :deep(.el-dialog__header) {
  flex-shrink: 0;
}

.sync-dialog :deep(.el-dialog__body) {
  flex: 1;
  overflow-y: auto;
}

.sync-dialog :deep(.el-dialog__footer) {
  flex-shrink: 0;
}

/* Switch hint text */
.switch-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 6px;
  line-height: 1.4;
}

/* Member dialog centering */
.member-dialog :deep(.el-dialog) {
  margin-top: 6vh !important;
}
</style>
