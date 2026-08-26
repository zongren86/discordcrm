<template>
  <div class="guild-members-page">
    <div v-if="loading" class="loading-wrap">
      <el-icon class="is-loading" :size="48"><Loading /></el-icon>
      <p>正在加载好友管理界面...</p>
    </div>

    <template v-else>
      <div class="page-header">
        <div>
          <h2 class="page-title">好友管理</h2>
          <p class="page-desc">管理模拟器实例，配置自动加好友，监控添加状态</p>
        </div>
        <div class="header-actions">
          <el-tag v-if="physicalStatus.agentOnline" type="success" effect="light" style="cursor: pointer" @click="getAgentDetails">
            {{ physicalStatus.message || 'Agent 已连接' }}
            <el-icon style="margin-left: 4px"><InfoFilled /></el-icon>
          </el-tag>
          <el-tag v-else-if="physicalStatus.localReachable" type="success" effect="light" style="cursor: pointer" @click="showLocalInfo">
            {{ physicalStatus.message || '本地环境已就绪' }}
            <el-icon style="margin-left: 4px"><InfoFilled /></el-icon>
          </el-tag>
          <el-tag v-else type="warning" effect="light">
            {{ physicalStatus.message || '未检测到在线 Agent' }}
          </el-tag>
          <el-button
            v-if="!physicalStatus.available"
            link
            type="primary"
            size="small"
            @click="checkPhysicalStatus"
          >重新检测</el-button>
        </div>
      </div>

      <div class="page-body">








        <!-- Agent 详情弹窗 -->
        <el-dialog
          v-model="showAgentDetails"
          title="连接的 Agent 设备详情"
          width="700px"
        >
          <div v-if="agentDetails.agents.length === 0" style="text-align: center; padding: 40px; color: #909399">
            <el-icon :size="48" color="#c0c4cc"><Monitor /></el-icon>
            <p style="margin-top: 12px">暂无在线 Agent 设备</p>
          </div>
          <div v-else>
            <el-table :data="agentDetails.agents" stripe style="width: 100%">
              <el-table-column prop="userId" label="账号" width="120">
                <template #default="{ row }">
                  <el-tag type="primary" size="small">{{ row.userId || '-' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="deviceId" label="设备 ID" min-width="320" show-overflow-tooltip>
                <template #default="{ row }">
                  <span style="font-family: monospace; font-size: 12px; word-break: break-all">{{ row.deviceId }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="os" label="操作系统" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.os === 'darwin'" type="info" size="small">macOS</el-tag>
                  <el-tag v-else-if="row.os === 'win32'" type="warning" size="small">Windows</el-tag>
                  <el-tag v-else-if="row.os === 'linux'" type="success" size="small">Linux</el-tag>
                  <span v-else>{{ row.os || '-' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="heartbeatStatus" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag v-if="row.heartbeatStatus === '超时'" type="danger" size="small">心跳超时</el-tag>
                  <el-tag v-else type="success" size="small">正常</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="secondsSinceHeartbeat" label="最后心跳" width="100">
                <template #default="{ row }">
                  {{ formatDuration(row.secondsSinceHeartbeat) }}
                </template>
              </el-table-column>
              <el-table-column prop="lastHeartbeatAt" label="最后心跳时间" width="180">
                <template #default="{ row }">
                  {{ row.lastHeartbeatAt ? new Date(row.lastHeartbeatAt).toLocaleString('zh-CN') : '-' }}
                </template>
              </el-table-column>
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button type="warning" size="small" link @click="disconnectAgentDevice(row)">断开</el-button>
                  <el-popconfirm title="确定删除该Agent？" @confirm="deleteAgentDevice(row)">
                    <template #reference>
                      <el-button type="danger" size="small" link>删除</el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-dialog>

        <!-- 差异检测弹窗 -->
        <el-dialog
          v-model="showDiffDialog"
          title="检测到物理模拟器与数据库存在差异"
          width="600px"
          :close-on-click-modal="false"
        >
          <div v-if="diffEmulators.length > 0">
            <el-alert
              type="warning"
              :closable="false"
              style="margin-bottom: 16px"
            >
              <template #title>
                检测到 {{ diffEmulators.length }} 个物理模拟器在数据库中不存在记录，请选择要创建的模拟器
              </template>
            </el-alert>
            
            <div style="margin-bottom: 12px;">
              <el-checkbox
                :model-value="selectedDiffIndices.length === diffEmulators.length"
                @change="toggleSelectAllDiff"
              >全选</el-checkbox>
              <span style="margin-left: 12px; color: #909399;">
                已选择 {{ selectedDiffIndices.length }} / {{ diffEmulators.length }}
              </span>
            </div>
            
            <el-table :data="diffEmulators" stripe size="small" max-height="300">
              <el-table-column width="50">
                <template #default="{ row }">
                  <el-checkbox
                    :model-value="selectedDiffIndices.includes(row.index)"
                    @change="toggleDiffSelection(row.index)"
                  />
                </template>
              </el-table-column>
              <el-table-column prop="name" label="名称" width="120" />
              <el-table-column prop="index" label="序号" width="80">
                <template #default="{ row }">V{{ String(row.index).padStart(3, '0') }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.status === 'RUNNING' ? 'success' : 'info'" size="small">
                    {{ row.status === 'RUNNING' ? '运行中' : '已停止' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="cpuCores" label="CPU" width="60" />
              <el-table-column prop="memoryGb" label="内存(G)" width="70" />
            </el-table>
          </div>
          
          <template #footer>
            <el-button @click="showDiffDialog = false">稍后处理</el-button>
            <el-button
              type="primary"
              :disabled="selectedDiffIndices.length === 0"
              @click="confirmDiffEmulators"
            >
              创建选中的 {{ selectedDiffIndices.length }} 条记录
            </el-button>
          </template>
        </el-dialog>

        <!-- Agent 引导弹窗 -->
        <el-dialog
          v-model="showAgentGuide"
          title="安装 mumu-agent 客户端"
          width="600px"
          :close-on-click-modal="false"
        >
          <div v-if="agentGuideData">
            <el-steps :active="guideStep" finish-status="success" align-center>
              <el-step
                v-for="(step, index) in agentGuideData.steps"
                :key="index"
                :title="step.title"
              />
            </el-steps>
            
            <div class="agent-guide-content" style="margin-top: 24px;">
              <div class="guide-section">
                <h4>📋 当前商户信息</h4>
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item label="商户账号">
                    {{ agentGuideData.userId }}
                  </el-descriptions-item>
                  <el-descriptions-item label="商户ID">
                    {{ agentGuideData.merchantId }}
                  </el-descriptions-item>
                </el-descriptions>
              </div>

              <div class="guide-section" style="margin-top: 16px;">
                <h4>🚀 快速安装步骤</h4>
                <ol class="install-steps">
                  <li>
                    <strong>安装 MuMu 模拟器</strong>
                    <p>确保本地服务器已安装 MuMuPlayer 模拟器</p>
                  </li>
                  <li>
                    <strong>下载 mumu-agent 客户端</strong>
                    <p>点击下方按钮获取安装脚本，在本地服务器上执行</p>
                  </li>
                  <li>
                    <strong>启动并连接</strong>
                    <p>执行安装脚本后，mumu-agent 将自动连接云端</p>
                  </li>
                  <li>
                    <strong>验证连接</strong>
                    <p>点击页面上的"重新检测"按钮，确认 Agent 已连接</p>
                  </li>
                </ol>
              </div>

              <div class="guide-section" style="margin-top: 16px;">
                <h4>⚙️ 生成配置</h4>
                <div class="config-preview">
                  <pre style="background: #f5f5f5; padding: 12px; border-radius: 4px; overflow: auto;">{{ JSON.stringify(agentConfig?.config, null, 2) }}</pre>
                  <el-button size="small" @click="copyToClipboard(JSON.stringify(agentConfig?.config, null, 2))">
                    <el-icon><CopyDocument /></el-icon> 复制配置
                  </el-button>
                </div>
              </div>
            </div>
          </div>

          <template #footer>
            <el-button @click="showAgentGuide = false">关闭</el-button>
            <el-button type="primary" :loading="agentScriptLoading" @click="generateAgentScript">
              <el-icon><Download /></el-icon> 生成启动脚本
            </el-button>
          </template>
        </el-dialog>

        <!-- 脚本预览弹窗 -->
        <el-dialog
          v-model="showScriptModal"
          title="启动脚本内容"
          width="700px"
        >
          <div class="script-preview">
            <pre style="background: #1e1e1e; color: #d4d4d4; padding: 16px; border-radius: 8px; max-height: 400px; overflow: auto; font-size: 13px; line-height: 1.6;">{{ agentScriptContent }}</pre>
          </div>
          <div style="margin-top: 16px; display: flex; gap: 8px;">
            <el-button @click="copyToClipboard(agentScriptContent)">
              <el-icon><CopyDocument /></el-icon> 复制脚本
            </el-button>
            <el-button type="primary" @click="downloadAsFile(agentScriptContent, 'install-mumu-agent.sh')">
              <el-icon><Download /></el-icon> 下载脚本
            </el-button>
          </div>
          <div style="margin-top: 12px; padding: 12px; background: #f0f9ff; border-radius: 4px; font-size: 13px;">
            <strong>💡 使用说明:</strong>
            <ol style="margin: 8px 0 0 20px;">
              <li>将下载的脚本文件保存到本地服务器</li>
              <li>执行: <code>chmod +x install-mumu-agent.sh</code></li>
              <li>执行: <code>./install-mumu-agent.sh</code></li>
              <li>等待安装完成，mumu-agent 将自动连接</li>
            </ol>
          </div>
        </el-dialog>

        <!-- TAB 结构 -->
        <el-tabs v-model="activeTab" type="card">
        <!-- Tab 1: 模拟器列表 -->
        <el-tab-pane 
          v-if="hasTabPermission('emulator_tab_list') || hasTabPermission('emulator')" 
          label="模拟器列表" 
          name="list"
        >
          <el-card class="panel" shadow="hover">
                <template #header>
                  <div class="panel-header">
                    <el-icon><User /></el-icon>
                    <span>服务器管理 & 好友号池（{{ addedServers.length }} 个服务器）</span>
                    <el-button type="primary" size="small" @click="showServerDialog = true">
                      添加服务器
                    </el-button>
                  </div>
                </template>
                <div class="panel-body">
                  <div v-if="addedServers.length === 0" class="empty-hint">
                    暂未添加服务器，点击上方"添加服务器"按钮开始
                  </div>
                  <div v-else class="server-card-list">
                    <div 
                      v-for="srv in addedServers" 
                      :key="srv.id" 
                      class="server-card"
                      :class="{ 'server-card--selected': selectedServerId === srv.serverId }"
                      @click="selectServer(srv.serverId)"
                    >
                      <div class="server-card-stats">
                        <div class="stat-item stat-server-name">
                          <div class="server-name-text" :title="srv.serverName || srv.name || '-'">
                            {{ srv.serverName || srv.name || '-' }}
                          </div>
                          <el-tag v-if="selectedServerId === srv.serverId" type="primary" size="small" style="margin-top: 2px">当前</el-tag>
                        </div>
                        <div class="stat-item">
                          <div class="stat-value">{{ getFriendPoolStatsForServer(srv.serverId)?.total || 0 }}</div>
                          <div class="stat-label">总数</div>
                        </div>
                        <div class="stat-item stat-pending">
                          <div class="stat-value-row">
                            <span class="stat-value">{{ getFriendPoolStatsForServer(srv.serverId)?.pending || 0 }}</span>
                            <span class="stat-pct">{{ getRatioForServer(srv.serverId, 'pending') }}%</span>
                          </div>
                          <el-progress 
                            :percentage="getRatioNumForServer(srv.serverId, 'pending')" 
                            :stroke-width="4" 
                            :show-text="false" 
                            color="#e6a23c"
                            style="width: 100%"
                          />
                          <div class="stat-label">待添加</div>
                        </div>
                        <div class="stat-item stat-assigned">
                          <div class="stat-value-row">
                            <span class="stat-value">{{ getFriendPoolStatsForServer(srv.serverId)?.assigned || 0 }}</span>
                            <span class="stat-pct">{{ getRatioForServer(srv.serverId, 'assigned') }}%</span>
                          </div>
                          <el-progress 
                            :percentage="getRatioNumForServer(srv.serverId, 'assigned')" 
                            :stroke-width="4" 
                            :show-text="false" 
                            color="#409eff"
                            style="width: 100%"
                          />
                          <div class="stat-label">已分配</div>
                        </div>
                        <div class="stat-item stat-success">
                          <div class="stat-value-row">
                            <span class="stat-value">{{ getFriendPoolStatsForServer(srv.serverId)?.success || 0 }}</span>
                            <span class="stat-pct">{{ getRatioForServer(srv.serverId, 'success') }}%</span>
                          </div>
                          <el-progress 
                            :percentage="getRatioNumForServer(srv.serverId, 'success')" 
                            :stroke-width="4" 
                            :show-text="false" 
                            color="#67c23a"
                            style="width: 100%"
                          />
                          <div class="stat-label">成功</div>
                        </div>
                        <div class="stat-item stat-failed">
                          <div class="stat-value-row">
                            <span class="stat-value">{{ getFriendPoolStatsForServer(srv.serverId)?.failed || 0 }}</span>
                            <span class="stat-pct">{{ getRatioForServer(srv.serverId, 'failed') }}%</span>
                          </div>
                          <el-progress 
                            :percentage="getRatioNumForServer(srv.serverId, 'failed')" 
                            :stroke-width="4" 
                            :show-text="false" 
                            color="#f56c6c"
                            style="width: 100%"
                          />
                          <div class="stat-label">失败</div>
                        </div>
                        <div class="stat-item stat-delete">
                          <el-button 
                            type="danger" 
                            size="small" 
                            link 
                            @click.stop="removeServer(srv.id)"
                          >删除</el-button>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </el-card>

          <!-- 批量操作工具栏 -->
          <div class="batch-toolbar">
            <div class="batch-actions">
              <!-- 新增按钮（放在第一个位置） -->
              <el-button 
                v-if="hasTabPermission('emulator_tab_list') || hasTabPermission('emulator')" 
                type="primary" 
                size="small" 
                @click="showAddDialog = true"
              >
                + 新增
              </el-button>
              <!-- 移到工具栏的控制按钮 -->
              <el-button type="primary" size="small" @click="syncPhysical" :disabled="emuLoading">
                同步
              </el-button>
              <el-button type="primary" size="small" @click="startAll" :disabled="emuLoading || !physicalStatus.available">
                全部启动
              </el-button>
              <el-button type="primary" size="small" @click="stopAll" :disabled="emuLoading || !physicalStatus.available">
                全部停止
              </el-button>
              <el-button type="primary" size="small" @click="restartAll" :disabled="emuLoading || !physicalStatus.available">
                全部重启
              </el-button>
              <el-divider direction="vertical" />
              <el-button type="primary" size="small" @click="batchAction('start')" :disabled="!canBatchStart || !physicalStatus.available">
                批量启动
              </el-button>
              <el-button type="primary" size="small" @click="batchAction('stop')" :disabled="!canBatchStop || !physicalStatus.available">
                批量停止
              </el-button>
              <el-button type="primary" size="small" @click="batchAction('restart')" :disabled="!canBatchRestart || !physicalStatus.available">
                批量重启
              </el-button>
              <el-button type="primary" size="small" @click="batchAction('installDiscord')" :disabled="!canBatchInstall || !physicalStatus.available">
                批量安装DS
              </el-button>
              <el-divider direction="vertical" />
              <el-button v-if="!autoAddTaskRunning" type="primary" size="small" @click="startAutoAll">
                全部开始加好友
              </el-button>
              <el-button v-else type="danger" size="small" @click="stopAutoAll">
                全部停止加好友
              </el-button>
              <el-button type="primary" size="small" @click="batchAction('startAuto')" :disabled="!canBatchStartAuto">
                选中启动加好友
              </el-button>
              <el-button type="primary" size="small" @click="batchAction('stopAuto')" :disabled="!canBatchStopAuto">
                选中停止添加
              </el-button>
              <el-button type="primary" size="small" @click="batchAction('delete')" :disabled="selectedEmulators.length === 0 || !physicalStatus.available">
                批量删除
              </el-button>
            </div>
          </div>

          <!-- 模拟器列表 -->
      <div v-if="emulators.length > 0" class="table-container">
        <el-table 
          :data="paginatedEmulators" 
          style="margin-top: 8px; width: 100%"
          height="calc(100vh - 520px)"
          @selection-change="handleSelectionChange"
          :row-class-name="rowClassName"
          size="small"
        >
        <el-table-column type="selection" width="40" class-name="checkbox-column" />
        <el-table-column label="ID" width="45">
          <template #default="{ row }">
            <span>{{ row.index }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="350">
          <template #default="{ row }">
            <template v-if="row.status !== 'RUNNING'">
              <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="startEmulator(row.index)">
                {{ isOperating(row.index) ? '启动中...' : '启动' }}
              </el-button>
            </template>
            <template v-else>
              <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="stopEmulator(row.index)">
                {{ isOperating(row.index) ? '停止中...' : '停止' }}
              </el-button>
              <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="restartEmulator(row.index)">
                {{ isOperating(row.index) ? '重启中...' : '重启' }}
              </el-button>
            </template>
            <el-button 
              v-if="row.damaged || row.status === 'DAMAGED'"
              size="small" 
              link 
              type="warning" 
              :disabled="isOperating(row.index)" 
              @click="repairEmulator(row.index)"
            >一键修复</el-button>
            <el-button
              v-if="row.status === 'RUNNING' && !row.autoRunning && row.discordInstalled && !isAutoStarting(row.index)"
              size="small" link type="primary"
              @click="startAuto(row.index)"
            >自动加好友</el-button>
            <el-button
              v-else-if="row.status === 'RUNNING' && !row.autoRunning && row.discordInstalled && isAutoStarting(row.index)"
              size="small" link type="primary" disabled
            >加好友启动中</el-button>
            <el-button
              v-else-if="row.status === 'RUNNING' && row.autoRunning"
              size="small" link type="primary"
              @click="stopAuto(row.index)"
            >停止添加</el-button>
            <el-button size="small" link type="primary" :disabled="isOperating(row.index)" @click="deleteEmulator(row.index)">删除</el-button>
          </template>
        </el-table-column>
        <el-table-column label="名称" width="110">
          <template #default="{ row }">
            <el-button link type="primary" @click="showEmuDetail(row)" style="font-size: 13px; padding: 0; height: auto; text-align: left">
              {{ row.name || `模拟器${row.index}` }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column v-if="agentDetails.agents.length > 1" label="Agent" width="100">
          <template #default="{ row }">
            <el-tag size="small" v-if="row.agentLabel">{{ row.agentLabel }}</el-tag>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="CPU/内存" width="90">
          <template #default="{ row }">
            <span v-if="row.cpuCores || row.memoryGb">{{ row.cpuCores || '-' }}核/{{ row.memoryGb || 0 }}G</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" style="white-space: nowrap; display: inline-flex; align-items: center; max-width: 100%;">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Discord账号编号" width="130">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 4px">
              <el-input-number
                v-if="editingAccountNumberIdx === row.index"
                v-model="editingAccountNumberValue"
                :min="1"
                :max="999999"
                size="small"
                style="width: 80px"
              />
              <span v-else style="font-weight: 600; color: #303133">
                {{ (row.discordAccountNumber ? 'V' + String(row.discordAccountNumber).padStart(3, '0') : '—') }}
              </span>
              <el-tooltip v-if="row.discordAccountNumberExplicit" content="已显式绑定（非默认）" placement="top">
                <el-icon :size="12" style="color: #409eff"><Flag /></el-icon>
              </el-tooltip>
              <el-button
                v-if="editingAccountNumberIdx !== row.index"
                size="small" link type="primary" :icon="Edit"
                @click="startEditAccountNumber(row)"
              ></el-button>
              <template v-else>
                <el-button size="small" link type="success" :icon="Check" @click="saveEditAccountNumber(row.index)"></el-button>
                <el-button size="small" link @click="cancelEditAccountNumber"><el-icon><Close /></el-icon></el-button>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="Discord账号" width="140">
          <template #default="{ row }">
            <span v-if="row.discordLoggedIn && row.discordAccount">{{ row.discordAccount }}</span>
            <span v-else-if="row.discordLoggedIn" style="color: #e6a23c">未获取</span>
            <span v-else style="color: #f56c6c">未登录</span>
          </template>
        </el-table-column>
        <el-table-column label="Discord状态" width="100">
          <template #default="{ row }">
            <div v-if="row.discordInstalled">
              <el-tag type="success" size="small">已安装</el-tag>
              <el-tag v-if="row.discordOnHome" type="success" size="small" style="margin-left: 2px">首页</el-tag>
            </div>
            <el-tag v-else-if="row.status === 'RUNNING'" type="warning" size="small">未安装</el-tag>
            <span v-else style="color: #909399">-</span>
          </template>
        </el-table-column>
        <el-table-column label="下次添加时间" width="110">
          <template #default="{ row }">
            <span v-if="row.nextAddAt && row.status === 'RUNNING' && row.autoRunning" style="color: #409eff; font-size: 12px; font-family: monospace">{{ formatCountdown(row.nextAddAt) }}</span>
            <span v-else style="color: #909399; font-size: 12px">-</span>
          </template>
        </el-table-column>
        <el-table-column label="加好友状态" width="200">
          <template #default="{ row }">
            <div class="friend-status-cell">
              <div v-if="row.status === 'RUNNING' && row.autoRunning" style="color: #67c23a">
                运行中
              </div>
              <div v-else style="color: #909399; font-size: 12px">
                {{ (row.status === 'RUNNING' && row.autoRunning) ? '已启动(等待)' : '未启动' }}
              </div>
              <div class="fs-stats" v-if="(row.assignedCount || 0) > 0 || (row.successCount || 0) > 0 || (row.failedCount || 0) > 0">
                <span class="fs-tag fs-assigned">已分配: {{ row.assignedCount || 0 }}</span>
                <span class="fs-tag fs-success">成功: {{ row.successCount || 0 }}</span>
                <span class="fs-tag fs-failed">失败: {{ row.failedCount || 0 }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最后添加结果" width="160">
          <template #default="{ row }">
            <span v-if="row.autoLastResult" style="color: #409eff; font-size: 12px">{{ row.autoLastResult }}</span>
            <span v-else-if="row.lastError" style="color: #f56c6c; font-size: 12px">{{ row.lastError }}</span>
            <span v-else style="color: #909399; font-size: 12px">-</span>
          </template>
        </el-table-column>
      </el-table>
          <!-- 分页 -->
          <div class="pagination-container">
            <el-pagination
              v-model:current-page="currentPage"
              v-model:page-size="pageSize"
              :page-sizes="[100]"
              :total="sortedEmulators.length"
              layout="total, prev, pager, next"
              background
              small
            />
          </div>
      </div>

      <el-empty v-else-if="!loading" description="暂无模拟器，点击上方「新增」按钮创建" />
        </el-tab-pane>

        <!-- Tab 2: 配置 -->
        <el-tab-pane 
          v-if="hasTabPermission('emulator_tab_config') || hasTabPermission('emulator')" 
          label="配置" 
          name="config"
        >
          <el-card class="panel" shadow="hover" style="max-width: 600px">
            <template #header>
              <div class="panel-header">
                <el-icon><Promotion /></el-icon>
                <span>自动加好友配置</span>
              </div>
            </template>
            <div class="panel-body">
              <!-- 加好友时段 -->
              <div class="form-row">
                <label>加好友时段</label>
                <el-time-picker
                  v-model="autoConfig.addStartTime"
                  format="HH:mm"
                  value-format="HH:mm"
                  style="width: 120px"
                  size="small"
                  placeholder="开始"
                />
                <span>~</span>
                <el-time-picker
                  v-model="autoConfig.addEndTime"
                  format="HH:mm"
                  value-format="HH:mm"
                  style="width: 120px"
                  size="small"
                  placeholder="结束"
                />
              </div>
              <!-- 每天可加人数 -->
              <div class="form-row">
                <label>每天可加人数</label>
                <el-input-number v-model="autoConfig.dailyLimit" :min="1" :max="10000" size="small" style="width: 120px" />
                <span class="unit">人</span>
              </div>
              <!-- 自动计算的间隔时间（只读） -->
              <div class="form-row">
                <label>间隔时间</label>
                <el-input-number 
                  v-model="autoConfig.calculatedIntervalMinutes" 
                  :min="1" 
                  :max="999999" 
                  size="small" 
                  style="width: 120px"
                  :disabled="true"
                />
                <span class="unit">分钟(自动计算)</span>
              </div>
              <!-- 并发 -->
              <div class="form-row">
                <label>同时启动</label>
                <el-input-number v-model="autoConfig.maxConcurrentEmulators" :min="1" :max="200" size="small" style="width: 120px" />
                <span class="unit">台</span>
              </div>
              <!-- 启动间隔 -->
              <div class="form-row">
                <label>启动间隔</label>
                <el-input-number v-model="autoConfig.emulatorStartIntervalSec" :min="1" :max="3600" size="small" style="width: 120px" />
                <span class="unit">秒</span>
              </div>
              <!-- 预估单机完成时长 -->
              <div class="form-row">
                <label>预估单机时长</label>
                <el-input-number v-model="autoConfig.estimatedSingleDurationMin" :min="1" :max="1440" size="small" style="width: 120px" />
                <span class="unit">分钟</span>
              </div>
              <!-- 延迟 -->
              <div class="form-row">
                <label>随机延迟</label>
                <el-input-number v-model="autoConfig.delayMinMinutes" :min="0" :max="999999" size="small" style="width: 120px" />
                <span>~</span>
                <el-input-number v-model="autoConfig.delayMaxMinutes" :min="0" :max="999999" size="small" style="width: 120px" />
                <span class="unit">分钟</span>
              </div>
              <!-- 测试模式 -->
              <div class="form-row inline-row">
                <label>测试模式</label>
                <el-switch v-model="autoConfig.testModeEnabled" size="small" />
                <span class="hint-sm" style="margin-left: 6px; color: #e6a23c">默认开启，只测试不添加好友</span>
              </div>
              <!-- 保存配置 -->
              <div class="form-row">
                <el-button type="primary" size="small" @click="saveAutoConfig">保存配置</el-button>
              </div>
            </div>
          </el-card>
        </el-tab-pane>
        <!-- Tab 3: 下载 -->
        <el-tab-pane 
          label="下载" 
          name="download"
        >
          <el-card class="panel" shadow="hover" style="max-width: 700px">
            <template #header>
              <div class="panel-header">
                <el-icon><Download /></el-icon>
                <span>mumu-agent 客户端下载</span>
              </div>
            </template>
            
            <div style="padding: 20px;">
              <h3 style="margin-top: 0;">MuMu Agent 客户端</h3>
              <p style="color: #666;">
                mumu-agent 是运行在商户服务器上的客户端程序，用于连接云端管理后台并控制本地 MuMu 模拟器。
              </p>
              
              <el-divider />
              
              <h4>安装步骤</h4>
              <ol style="padding-left: 20px; line-height: 2;">
                <li>在商户服务器上安装 Node.js (>= 18)
                  <br/>
                  <el-link type="primary" href="https://nodejs.org/" target="_blank">https://nodejs.org/</el-link>
                </li>
                <li>点击下方按钮下载 mumu-agent 完整包</li>
                <li>解压下载的压缩包</li>
                <li>进入 mumu-agent 目录，运行 <code>npm install</code> 安装依赖</li>
                <li>运行 <code>node agent.js</code> 启动客户端</li>
                <li>刷新本页面，检查连接状态</li>
              </ol>
              
              <el-divider />
              
              <h4>快捷下载</h4>
              <div style="display: flex; gap: 12px; flex-wrap: wrap;">
                <el-button type="primary" size="large" @click="handleDownloadAgentPackage" :loading="downloadLoading">
                  <el-icon><Download /></el-icon> 下载 mumu-agent.zip
                </el-button>
                <el-button size="large" @click="openAgentGuide">
                  <el-icon><Guide /></el-icon> 查看完整引导
                </el-button>
              </div>
              
              <el-divider />
              
              <el-alert 
                type="warning" 
                :closable="false"
                show-icon
                title="注意事项"
                style="margin-top: 16px;"
              >
                <ul style="margin: 0; padding-left: 20px;">
                  <li>同一商户账号只能在一台服务器上运行 mumu-agent</li>
                  <li>如需更换服务器，请先停止旧服务器上的 Agent</li>
                  <li>请确保服务器已安装 MuMu 模拟器</li>
                </ul>
              </el-alert>
            </div>
          </el-card>
        </el-tab-pane>
      </el-tabs>
      </div>
    </template>

    <!-- 新增模拟器弹窗 -->
    <el-dialog v-model="showAddDialog" title="新增模拟器" width="450px" :close-on-click-modal="false">
      <div class="add-emu-form">
        <div class="form-row" v-if="agentDetails.agents.length > 1">
          <label>目标 Agent</label>
          <el-select v-model="addEmuDeviceId" size="small" style="width: 200px">
            <el-option 
              v-for="agent in agentDetails.agents" 
              :key="agent.deviceId" 
              :label="agent.userId + ' (' + (agent.os === 'darwin' ? 'macOS' : agent.os === 'win32' ? 'Windows' : agent.os) + ')'" 
              :value="agent.deviceId" 
            />
          </el-select>
        </div>
        <div class="form-row">
          <label>新增数量</label>
          <el-input-number v-model="addEmuCount" :min="1" :max="50" size="small" style="width: 120px" />
          <span class="unit">台</span>
        </div>
        <div class="form-row">
          <label>CPU</label>
          <el-select v-model="addEmuConfig.cpuCores" size="small" style="width: 120px">
            <el-option v-for="n in 8" :key="n" :label="String(n)" :value="n" />
          </el-select>
          <span class="unit">核</span>
        </div>
        <div class="form-row">
          <label>内存</label>
          <el-select v-model="addEmuConfig.memoryGb" size="small" style="width: 120px">
            <el-option v-for="n in 8" :key="n" :label="String(n)" :value="n" />
          </el-select>
          <span class="unit">G</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmAddEmulators" :loading="emuLoading">确定</el-button>
      </template>
    </el-dialog>

    <!-- 添加服务器弹窗 -->
    <el-dialog v-model="showServerDialog" title="添加服务器" width="800px" :close-on-click-modal="false">
      <div class="dialog-section">
        <h4>已添加的服务器</h4>
        <div v-if="addedServers.length === 0" class="empty-hint">暂无已添加服务器</div>
        <el-table v-else :data="addedServers" size="small" style="width: 100%">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="serverName" label="服务器名称" width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="60">
            <template #default="{ row }">
              <el-button type="primary" size="small" link @click="removeServer(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-divider />
      <div class="dialog-section">
        <div class="dialog-header">
          <h4>可添加的服务器</h4>
          <div class="filter-row">
            <el-select v-model="selectedAccountId" size="small" placeholder="账号筛选（可选）" clearable style="width: 150px">
              <el-option 
                v-for="acc in addedAccounts" 
                :key="acc.discordAccountId" 
                :label="acc.accountName || acc.discordName" 
                :value="acc.discordAccountId" 
              />
            </el-select>
            <el-input v-model="serverSearch" size="small" placeholder="搜索服务器名称" clearable style="width: 180px" />
          </div>
        </div>
        <div v-if="availableServersLoading" class="loading-hint">加载中...</div>
        <div v-else-if="filteredAvailableServers.length === 0" class="empty-hint">
          没有可添加的服务器
        </div>
        <el-table v-else :data="filteredAvailableServers" size="small" style="width: 100%">
          <el-table-column prop="id" label="ID" width="60" />
          <el-table-column prop="accountName" label="Discord账号" width="120" />
          <el-table-column prop="name" label="服务器名称" width="200" show-overflow-tooltip />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button type="primary" size="small" @click="addServer(row)">添加</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- 模拟器详情 Dialog -->
    <el-dialog v-model="emuDetailVisible" :title="emuDetailData?.name || '模拟器详情'" width="500px">
      <div v-if="emuDetailData" class="emu-detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="索引">{{ emuDetailData.index }}</el-descriptions-item>
          <el-descriptions-item label="名称">{{ emuDetailData.name }}</el-descriptions-item>
          <el-descriptions-item label="CPU">{{ emuDetailData.cpuCores || '-' }} 核</el-descriptions-item>
          <el-descriptions-item label="内存">{{ emuDetailData.memoryGb || 0 }} G</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(emuDetailData.status)" size="small">{{ statusText(emuDetailData.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="ADB端口">{{ emuDetailData.adbPort || '-' }}</el-descriptions-item>
          <el-descriptions-item label="分辨率">{{ emuDetailData.resolution || '-' }}</el-descriptions-item>
          <el-descriptions-item label="Discord状态">
            <el-tag v-if="emuDetailData.discordInstalled" type="success" size="small">已安装</el-tag>
            <span v-else style="color: #f56c6c">未安装</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">Discord 账号信息</el-divider>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="账号">
            <span v-if="emuDetailData.discordAccount">{{ emuDetailData.discordAccount }}</span>
            <span v-else-if="emuDetailData.discordLoggedIn" style="color: #e6a23c">未获取</span>
            <span v-else style="color: #f56c6c">未登录</span>
          </el-descriptions-item>
          <el-descriptions-item label="登录状态">
            <el-tag v-if="emuDetailData.discordLoggedIn" type="success" size="small">已登录</el-tag>
            <el-tag v-else type="warning" size="small">未登录</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="首页状态" :span="2">
            <el-tag v-if="emuDetailData.discordOnHome" type="success" size="small">在首页</el-tag>
            <span v-else style="color: #909399">-</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">加好友状态</el-divider>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="自动加好友">
            <el-tag v-if="emuDetailData.autoRunning" type="success" size="small">运行中</el-tag>
            <el-tag v-else type="info" size="small">已停止</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="已添加">{{ emuDetailData.addedCount || 0 }} 个</el-descriptions-item>
          <el-descriptions-item label="下次添加时间" :span="2">
            {{ emuDetailData.nextAddAt ? formatCountdown(emuDetailData.nextAddAt) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="最后结果" :span="2">
            <span v-if="emuDetailData.autoLastResult" style="color: #409eff">{{ emuDetailData.autoLastResult }}</span>
            <span v-else style="color: #909399">-</span>
          </el-descriptions-item>
          <el-descriptions-item label="错误信息" :span="2">
            <span v-if="emuDetailData.lastError" style="color: #f56c6c">{{ emuDetailData.lastError }}</span>
            <span v-else style="color: #909399">无</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-dialog>

    <!-- 全屏加载遮罩 -->
    <div v-if="globalLoading.show" class="global-loading-mask">
      <div class="global-loading-content">
        <el-icon class="is-loading" :size="48" color="#409eff"><Loading /></el-icon>
        <p class="global-loading-text">{{ globalLoading.text }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
// vue imports - already imported above
import {
  Loading, WarningFilled, VideoPlay, VideoPause, Refresh,
  ChatDotRound, Setting, Key, Promotion, CircleCheck, User, Avatar,
  Edit, Check, Close, Flag, CopyDocument, Download, Guide, InfoFilled, Monitor
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, ElDialog, ElSteps, ElStep } from 'element-plus'
import axios from 'axios'
import { config } from '@/config'
import { getAgentConfig, downloadAgentScript, getAgentGuide, downloadAgentPackage } from '@/api'
import { useAuthStore } from '@/stores/auth'
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'

const loading = ref(true)
const backendAvailable = ref(false)
const emulators = ref([])
const targetCount = ref(3)
const emuLoading = ref(false)

// 实时倒计时更新
const now = ref(Date.now())
let countdownTimer = null

// 全屏加载遮罩状态
const globalLoading = ref({ show: false, text: '' })
function showLoading(text) {
  globalLoading.value = { show: true, text: text || '处理中...' }
}
function hideLoading() {
  globalLoading.value = { show: false, text: '' }
}

// 物理模拟器连接状态
const physicalStatus = ref({ available: false, message: '检测中...' })

// Agent 详情弹窗状态
const showAgentDetails = ref(false)
const agentDetails = ref({ agents: [], agentCount: 0 })

// Agent 引导弹窗状态
const showAgentGuide = ref(false)
const downloadLoading = ref(false)
const agentConfig = ref(null)
const agentGuideData = ref(null)
const agentScriptLoading = ref(false)
const agentScriptContent = ref('')
const showScriptModal = ref(false)
const guideStep = ref(0)

const apkDownloaded = ref(false)
const apkLoading = ref(false)
const apkInput = ref(null)

const emuConfig = ref({ cpuCores: 1, memoryGb: 1 })
// 自动加好友配置：单位改为分钟
const autoConfig = ref({
  intervalMinutes: 15,
  delayMinMinutes: 1,
  delayMaxMinutes: 10,
  autoLoginDiscord: false,
  maxConcurrentEmulators: 5,
  emulatorStartIntervalSec: 5,
  testModeEnabled: true,
  // 新字段
  addStartTime: '09:00',
  addEndTime: '18:00',
  dailyLimit: 6,
  estimatedSingleDurationMin: 5,
  // 计算字段
  calculatedIntervalMinutes: 15
})

// 自动加好友任务状态
const autoAddTaskRunning = ref(false)
const autoAddTaskStatus = ref({})
let autoAddStatusPollTimer = null

// Discord账号编号列编辑状态
const editingAccountNumberIdx = ref(null)
const editingAccountNumberValue = ref(null)

// 操作中的模拟器索引列表（用于按钮禁用状态）
const operatingEmulators = ref(new Set())
const autoStartingEmulators = ref(new Set())

const selectedEmulators = ref([])

// 账号管理
const addedAccounts = ref([])
const availableAccounts = ref([])
const availableAccountsLoading = ref(false)
const accountSearch = ref('')
const showAccountDialog = ref(false)

// 服务器管理
const addedServers = ref([])
const availableServers = ref([])
const availableServersLoading = ref(false)
const serverSearch = ref('')
const showServerDialog = ref(false)
const selectedAccountId = ref(null)  // 选中的账号ID，用于筛选服务器
const selectedServerId = ref(null)  // 当前选中的服务器ID

// 好友号池
const friendPool = ref([])
const friendPoolStats = ref({ total: 0, pending: 0, assigned: 0, success: 0, failed: 0 })
const friendPoolLoading = ref(false)
const friendPoolFilter = ref('PENDING')
let friendPoolPollTimer = null

// 每个服务器的好友号池统计
const serverFriendPoolStats = ref({})

// TAB 相关
const activeTab = ref('list')
const showAddDialog = ref(false)
const addEmuCount = ref(1)
const addEmuConfig = ref({ cpuCores: 1, memoryGb: 1 })
const addEmuDeviceId = ref('')

// 差异检测相关
const diffEmulators = ref([])
const showDiffDialog = ref(false)
const selectedDiffIndices = ref([])
const isMerchantAdmin = computed(() => {
  const agent = authStore.agent
  return agent && agent.accountType === 0 && agent.merchantId != null
})

// 权限检查
const authStore = useAuthStore()
// 如果用户没有任何 emulator 相关权限，默认显示所有 TAB（向下兼容）
const hasAnyEmulatorTabPermission = computed(() => {
  const perms = ['emulator_tab_list', 'emulator_tab_config', 'emulator']
  return perms.some(p => authStore.hasPermission(p))
})
function hasTabPermission(perm) {
  // 如果没有配置任何 TAB 权限，默认允许访问（兼容旧系统）
  if (!hasAnyEmulatorTabPermission.value) return true
  return authStore.hasPermission(perm) || authStore.hasPermission('emulator')
}

// 模拟器详情
const emuDetailVisible = ref(false)
const emuDetailData = ref(null)

// 当前选中的服务器名称
const currentServerName = computed(() => {
  const server = addedServers.value.find(s => s.serverId === selectedServerId.value)
  return server ? (server.serverName || server.name || '') : ''
})

// API 基础 URL
const API_BASE = '/api/emu'

const canBatchStart = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status !== 'RUNNING'
  })
)

const canBatchStop = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING'
  })
)

const canBatchRestart = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING'
  })
)

const canBatchInstall = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING' && !emu.discordInstalled
  })
)

const canBatchStartAuto = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING' && emu.discordInstalled && !emu.autoRunning
  })
)

const canBatchStopAuto = computed(() => 
  selectedEmulators.value.some(idx => {
    const emu = emulators.value.find(e => e.index === idx)
    return emu && emu.status === 'RUNNING' && emu.autoRunning
  })
)

// 过滤可用账号
const filteredAvailableAccounts = computed(() => {
  if (!accountSearch.value) return availableAccounts.value
  const keyword = accountSearch.value.toLowerCase()
  return availableAccounts.value.filter(acc => 
    (String(acc.id).includes(keyword)) ||
    (acc.accountName && acc.accountName.toLowerCase().includes(keyword)) ||
    (acc.email && acc.email.toLowerCase().includes(keyword)) ||
    (acc.discordName && acc.discordName.toLowerCase().includes(keyword))
  )
})

// 过滤可用服务器
const filteredAvailableServers = computed(() => {
  let servers = availableServers.value
  if (selectedAccountId.value) {
    servers = servers.filter(s => s.discordAccountId === selectedAccountId.value)
  }
  if (!serverSearch.value) return servers
  const keyword = serverSearch.value.toLowerCase()
  return servers.filter(srv => 
    (srv.name && srv.name.toLowerCase().includes(keyword)) ||
    (srv.guildId && srv.guildId.toLowerCase().includes(keyword))
  )
})

// 计算占比（2位小数，字符串）
function getRatio(count) {
  const total = friendPoolStats.value.total || 0
  if (total === 0) return '0.00'
  return ((count / total) * 100).toFixed(2)
}

// 计算占比（数值型，用于进度条）
function getRatioNum(count) {
  const total = friendPoolStats.value.total || 0
  if (total === 0) return 0
  return Math.round((count / total) * 100)
}

// 获取指定服务器的好友号池统计
function getFriendPoolStatsForServer(serverId) {
  if (!serverId) return { total: 0, pending: 0, assigned: 0, success: 0, failed: 0 }
  // 使用字符串键名匹配，避免类型不匹配
  const key = String(serverId)
  return serverFriendPoolStats.value[key] || { total: 0, pending: 0, assigned: 0, success: 0, failed: 0 }
}

// 计算指定服务器的某字段占比（低于0.01%显示4位小数，否则显示2位小数）
function getRatioForServer(serverId, field) {
  const stats = getFriendPoolStatsForServer(serverId)
  const total = stats.total || 0
  if (total === 0) return '0.00'
  const ratio = (stats[field] || 0) / total * 100
  if (ratio > 0 && ratio < 0.01) {
    return ratio.toFixed(4)
  }
  return ratio.toFixed(2)
}

// 计算指定服务器的某字段占比（数值型，用于进度条）
function getRatioNumForServer(serverId, field) {
  const stats = getFriendPoolStatsForServer(serverId)
  const total = stats.total || 0
  if (total === 0) return 0
  return Math.round(((stats[field] || 0) / total) * 100)
}

let healthCheckTimer = null

const emuApi = axios.create({ baseURL: '/emu-api', timeout: 60000 })
emuApi.interceptors.request.use(config => {
  const token = localStorage.getItem('crm_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const friendApi = axios.create({ baseURL: '/api', timeout: 60000 })
friendApi.interceptors.request.use(config => {
  const token = localStorage.getItem('crm_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

const sortedEmulators = computed(() => {
  // 按序号(index)从小到大排序，保证最早创建/编号最小的排在最上面
  return [...emulators.value].sort((a, b) => {
    const idxA = a.index ?? Number.MAX_SAFE_INTEGER
    const idxB = b.index ?? Number.MAX_SAFE_INTEGER
    if (idxA !== idxB) return idxA - idxB
    // index 相同时按名称再排一次
    const nameA = a.name || ''
    const nameB = b.name || ''
    return nameA.localeCompare(nameB, 'zh', { numeric: true, sensitivity: 'base' })
  })
})

// 分页相关
const currentPage = ref(1)
const pageSize = ref(100)
const paginatedEmulators = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  const end = start + pageSize.value
  return sortedEmulators.value.slice(start, end)
})

function handleSelectionChange(selection) {
  selectedEmulators.value = selection.map(item => item.index)
}

function rowClassName({ row }) {
  return selectedEmulators.value.includes(row.index) ? 'selected-row' : ''
}

async function batchAction(action) {
  if (selectedEmulators.value.length === 0) return
  
  const actionMap = {
    start: { confirm: '确定要批量启动选中的模拟器吗？', method: 'start', loadingText: '批量启动中...' },
    stop: { confirm: '确定要批量停止选中的模拟器吗？', method: 'stop', loadingText: '批量停止中...' },
    restart: { confirm: '确定要批量重启选中的模拟器吗？', method: 'restart', loadingText: '批量重启中...' },
    installDiscord: { confirm: '确定要批量安装 Discord 到选中的模拟器吗？', method: 'install', loadingText: '批量安装中...' },
    startAuto: { confirm: '确定要批量启动自动加好友吗？', method: 'startAuto', loadingText: '批量启动加好友中...' },
    stopAuto: { confirm: '确定要批量停止自动加好友吗？', method: 'stopAuto', loadingText: '批量停止加好友中...' },
    delete: { confirm: '确定要批量删除选中的模拟器吗？此操作不可恢复！', method: 'delete', loadingText: '批量删除中...' }
  }
  
  const actionConfig = actionMap[action]
  if (!actionConfig) return
  
  try {
    await ElMessageBox.confirm(actionConfig.confirm, '确认', { type: 'warning' })
    
    showLoading(actionConfig.loadingText)
    
    const results = []
    for (const index of selectedEmulators.value) {
      try {
        let resp
        switch (actionConfig.method) {
          case 'start':
            resp = await emuApi.post(`/emulators/${index}/start`)
            break
          case 'stop':
            resp = await emuApi.post(`/emulators/${index}/stop`)
            break
          case 'restart':
            resp = await emuApi.post(`/emulators/${index}/restart`)
            break
          case 'install':
            resp = await friendApi.post(`/discord/${index}/install`)
            break
          case 'startAuto':
            resp = await friendApi.post(`/autoadd/${index}/start`)
            break
          case 'stopAuto':
            resp = await friendApi.post(`/autoadd/${index}/stop`)
            break
          case 'delete':
            resp = await emuApi.delete(`/emulators/${index}`)
            break
        }
        results.push({ index, success: true })
      } catch (e) {
        results.push({ index, success: false, error: e.response?.data?.message || e.message })
      }
    }
    
    const successCount = results.filter(r => r.success).length
    const failCount = results.filter(r => !r.success).length
    
    if (failCount === 0) {
      ElMessage.success(`批量操作完成，${successCount}个模拟器操作成功`)
    } else {
      ElMessage.warning(`批量操作完成，${successCount}个成功，${failCount}个失败`)
      const failed = results.filter(r => !r.success).map(r => `#${r.index}`).join(', ')
      ElMessage.error(`失败的模拟器: ${failed}`)
    }
    
    if (action === 'delete') {
      emulators.value = emulators.value.filter(e => !selectedEmulators.value.includes(e.index))
      selectedEmulators.value = []
    }
    
    await fetchEmulators()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批量操作失败')
    }
  } finally {
    hideLoading()
  }
}

// 带超时的Promise包装
function withTimeout(promise, ms, fallback = null) {
  return Promise.race([
    promise.catch(() => fallback),
    new Promise(resolve => setTimeout(() => resolve(fallback), ms))
  ])
}

async function fetchAgentDetailsForInit() {
  try {
    const resp = await emuApi.get('/agent/details')
    agentDetails.value = resp.data
    // 如果只有一个 Agent，自动设置为默认
    if (agentDetails.value.agents && agentDetails.value.agents.length === 1) {
      addEmuDeviceId.value = agentDetails.value.agents[0].deviceId
    }
  } catch {}
}

onMounted(async () => {
  // 并行执行所有加载，不等待服务检查，每个任务最多8秒超时
  const LOAD_TIMEOUT = 8000
  const loadTasks = [
    withTimeout(fetchAgentDetailsForInit(), LOAD_TIMEOUT),
    withTimeout(fetchEmulators(), LOAD_TIMEOUT),
    withTimeout(loadAddedServers(), LOAD_TIMEOUT),
    withTimeout(loadAvailableServers(), LOAD_TIMEOUT),
    withTimeout(loadAutoConfig(), LOAD_TIMEOUT),
    withTimeout(checkApkStatus(), LOAD_TIMEOUT),
    withTimeout(loadFriendPoolStats(), LOAD_TIMEOUT),
    withTimeout(loadAllServerFriendPoolStats(), LOAD_TIMEOUT),
    withTimeout(loadFriendPool(), LOAD_TIMEOUT),
    withTimeout(checkPhysicalStatus(), LOAD_TIMEOUT),
    withTimeout(fetchAutoAddStatus(), LOAD_TIMEOUT)
  ]
  
  // 服务检查与数据加载并行
  const serviceCheckPromise = checkBackend().then(ok => {
    backendAvailable.value = ok
  })
  
  // 并行执行所有任务，最多等待10秒
  await Promise.all([
    serviceCheckPromise,
    ...loadTasks
  ])
  
  loading.value = false
  
  // 启动轮询
  if (backendAvailable.value) {
    startFriendPoolPolling()
    // 如果任务正在运行，启动状态轮询
    if (autoAddTaskStatus.value.isRunning) {
      startAutoAddStatusPolling()
    }
  }
  
  startHealthCheck()
  // 启动每秒更新的倒计时定时器
  countdownTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (healthCheckTimer) { clearInterval(healthCheckTimer); healthCheckTimer = null }
  if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null }
  stopFriendPoolPolling()
  stopAutoAddStatusPolling()
})

// 监听弹窗打开时加载数据
watch(showServerDialog, async (val) => {
  if (val) {
    await Promise.all([loadAddedServers(), loadAvailableServers()])
  }
})

// 监听账号选择变化，重新加载服务器列表（账号筛选变为前端过滤）
watch(selectedAccountId, async () => {
  // 筛选由前端 computed 处理，无需重新加载
})

// 监听服务器选择变化，重新加载好友池
watch(selectedServerId, async () => {
  await Promise.all([loadFriendPoolStats(), loadFriendPool()])
})

async function checkService() {
  // 首次检查不显示loading（页面已有loading状态保护）
  const ok = await checkBackend()
  backendAvailable.value = ok
  loading.value = false
}

async function checkBackend() {
  try {
    // 缩短超时到5秒，避免长时间等待
    const resp = await emuApi.get('/emulators', { timeout: 5000 })
    return Array.isArray(resp.data) || resp.status < 500
  } catch { return false }
}

// 健康检查防抖：连续失败 ≥ N 次才弹一次 warning，成功后再弹一次 success
const healthFailStreak = ref(0)
const LAST_WARN_KEY = 'emu_view_last_warn_ts'
function startHealthCheck() {
  healthCheckTimer = setInterval(async () => {
    const ok = await checkBackend()
    if (!ok && backendAvailable.value) {
      healthFailStreak.value++
      // 连续失败 ≥ 3 次才认为真的断开（每 10s 检查一次，3次=30s）
      if (healthFailStreak.value >= 3) {
        backendAvailable.value = false
        ElMessage.warning('后端服务已断开，正在自动重连...')
      }
    } else if (ok && !backendAvailable.value) {
      healthFailStreak.value = 0
      backendAvailable.value = true
      try {
        await Promise.all([
          fetchEmulators(),
          loadAddedServers(),
          loadAvailableServers(),
          loadAutoConfig(),
          checkApkStatus(),
          loadFriendPoolStats(),
          loadFriendPool()
        ])
        ElMessage.success('后端服务已重新连接，数据已刷新')
      } catch (e) {
        // 部分数据加载失败不影响整体
      }
    } else if (ok && backendAvailable.value) {
      // 健康，重置失败计数
      healthFailStreak.value = 0
    }
    // 同时检查物理状态
    await checkPhysicalStatus()
  }, 10000) // 每 10s 检查一次，避免过于频繁
}

async function checkPhysicalStatus() {
  try {
    const resp = await emuApi.get('/emulators/physical-status')
    physicalStatus.value = resp.data
  } catch {
    physicalStatus.value = { available: false, message: '未检测到物理模拟器' }
  }
}

function showLocalInfo() {
  ElMessageBox.alert(
    physicalStatus.value?.message || '本地环境已就绪',
    '本地环境信息',
    { confirmButtonText: '确定' }
  )
}

async function getAgentDetails() {
  try {
    const resp = await emuApi.get('/agent/details')
    agentDetails.value = resp.data
    showAgentDetails.value = true
  } catch (e) {
    ElMessage.error('获取 Agent 详情失败')
  }
}

async function disconnectAgentDevice(row) {
  try {
    await ElMessageBox.confirm(`确定要断开设备 ${row.deviceId} 的连接吗？`, '断开 Agent', {
      type: 'warning'
    })
    const resp = await emuApi.post('/agent/disconnect', { deviceId: row.deviceId })
    if (resp.data?.success) {
      ElMessage.success('Agent 已断开')
      showAgentDetails.value = false
      await checkPhysicalStatus()
    } else {
      ElMessage.error(resp.data?.message || '断开失败')
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('断开失败: ' + (e.response?.data?.message || e.message))
  }
}

async function deleteAgentDevice(row) {
  try {
    const resp = await emuApi.post('/agent/delete', { deviceId: row.deviceId })
    if (resp.data?.success) {
      ElMessage.success('Agent 已删除')
      showAgentDetails.value = false
      await checkPhysicalStatus()
    } else {
      ElMessage.error(resp.data?.message || '删除失败')
    }
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.response?.data?.message || e.message))
  }
}

function formatDuration(seconds) {
  if (!seconds && seconds !== 0) return '-'
  if (seconds < 60) return seconds + ' 秒前'
  if (seconds < 3600) return Math.floor(seconds / 60) + ' 分钟前'
  if (seconds < 86400) return Math.floor(seconds / 3600) + ' 小时前'
  return Math.floor(seconds / 86400) + ' 天前'
}

// ========== Agent 引导功能 ==========

async function openAgentGuide() {
  try {
    const [configResp, guideResp] = await Promise.all([
      getAgentConfig(),
      getAgentGuide()
    ])
    agentConfig.value = configResp
    agentGuideData.value = guideResp
    showAgentGuide.value = true
  } catch (e) {
    ElMessage.error('加载引导信息失败')
  }
}

async function generateAgentScript() {
  agentScriptLoading.value = true
  try {
    const resp = await downloadAgentScript()
    agentScriptContent.value = resp.script || ''
    showScriptModal.value = true
  } catch (e) {
    ElMessage.error('生成脚本失败')
  } finally {
    agentScriptLoading.value = false
  }
}

function copyToClipboard(text) {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    // 降级方案
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    ElMessage.success('已复制到剪贴板')
  })
}

async function handleDownloadAgentPackage() {
  downloadLoading.value = true
  try {
    const blob = await downloadAgentPackage()
    // blob is already the response data from axios interceptor
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'mumu-agent.zip'
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('下载成功')
  } catch (e) {
    ElMessage.error('下载失败: ' + (e.response?.data?.message || e.message))
  } finally {
    downloadLoading.value = false
  }
}

function downloadAsFile(content, filename) {
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
  ElMessage.success('文件已下载')
}

async function syncPhysical() {
  emuLoading.value = true
  showLoading('同步数据中...')
  try {
    const resp = await emuApi.post('/emulators/sync')
    ElMessage.success(resp.data.message || '同步完成')
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
    hideLoading()
  }
}

async function fetchEmulators() {
  try {
    const resp = await emuApi.get('/emulators')
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    await checkDiffEmulators()
  } catch { emulators.value = [] }
}

// 检测物理模拟器与数据库的差异
async function checkDiffEmulators() {
  if (!isMerchantAdmin.value) return
  
  try {
    const resp = await emuApi.get('/emulators/diff')
    const diffList = resp.data?.data || resp.data || []
    if (Array.isArray(diffList) && diffList.length > 0) {
      diffEmulators.value = diffList
      selectedDiffIndices.value = diffList.map(d => d.index)
      showDiffDialog.value = true
    }
  } catch (e) {
    console.warn('检测差异失败:', e.message)
  }
}

// 确认创建差异模拟器
async function confirmDiffEmulators() {
  if (selectedDiffIndices.value.length === 0) {
    ElMessage.warning('请至少选择一个模拟器')
    return
  }
  
  try {
    const resp = await emuApi.post('/emulators/diff/confirm', {
      indices: selectedDiffIndices.value
    })
    
    if (resp.data?.success) {
      ElMessage.success(resp.data?.message || '创建成功')
      showDiffDialog.value = false
      diffEmulators.value = []
      selectedDiffIndices.value = []
      await fetchEmulators()
    } else {
      ElMessage.error(resp.data?.message || '创建失败')
    }
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.response?.data?.message || e.message))
  }
}

// 选择/取消选择差异项
function toggleDiffSelection(index) {
  const idx = selectedDiffIndices.value.indexOf(index)
  if (idx === -1) {
    selectedDiffIndices.value.push(index)
  } else {
    selectedDiffIndices.value.splice(idx, 1)
  }
}

// 全选/取消全选
function toggleSelectAllDiff() {
  if (selectedDiffIndices.value.length === diffEmulators.value.length) {
    selectedDiffIndices.value = []
  } else {
    selectedDiffIndices.value = diffEmulators.value.map(d => d.index)
  }
}

async function checkApkStatus() {
  try {
    const resp = await friendApi.get('/discord/apk-status')
    apkDownloaded.value = resp.data.downloaded
  } catch {}
}

async function downloadApk() {
  apkLoading.value = true
  try {
    await friendApi.post('/discord/download')
    ElMessage.success('APK 下载中...')
    setTimeout(async () => {
      apkDownloaded.value = true
      apkLoading.value = false
      ElMessage.success('APK 下载完成')
    }, 30000)
  } catch (e) {
    apkLoading.value = false
    ElMessage.error('下载失败: ' + (e.response?.data?.message || e.message))
  }
}

function triggerApkUpload() { apkInput.value?.click() }

async function handleApkUpload(event) {
  const file = event.target.files?.[0]
  if (!file) return
  const formData = new FormData()
  formData.append('file', file)
  try {
    await emuApi.post('/discord/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    apkDownloaded.value = true
    ElMessage.success('APK 上传成功')
  } catch (e) {
    ElMessage.error('上传失败: ' + (e.response?.data?.message || e.message))
  }
  event.target.value = ''
}

async function installAllDiscord() {
  try {
    await friendApi.post('/discord/installAll')
    ElMessage.success('已开始安装 Discord 到所有模拟器')
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('安装失败: ' + (e.response?.data?.message || e.message))
  }
}

// ========== 账号管理 ==========

async function loadAddedAccounts() {
  try {
    const resp = await emuApi.get('/accounts/added')
    addedAccounts.value = resp.data || []
  } catch { addedAccounts.value = [] }
}

async function loadAvailableAccounts() {
  availableAccountsLoading.value = true
  try {
    const resp = await emuApi.get('/accounts/available', {
      params: { keyword: accountSearch.value || undefined }
    })
    availableAccounts.value = resp.data || []
  } catch { availableAccounts.value = [] }
  finally { availableAccountsLoading.value = false }
}

async function addAccount(discordAccountId) {
  try {
    await emuApi.post('/accounts/add', { discordAccountId })
    ElMessage.success('账号已添加')
    await Promise.all([loadAddedAccounts(), loadAvailableAccounts()])
  } catch (e) {
    ElMessage.error('添加失败: ' + (e.response?.data?.message || e.message))
  }
}

async function removeAccount(bindingId) {
  try {
    await emuApi.delete(`/accounts/${bindingId}`)
    ElMessage.success('账号已移除')
    const removedAccount = addedAccounts.value.find(a => a.id === bindingId)
    if (removedAccount && selectedAccountId.value === removedAccount.discordAccountId) {
      selectedAccountId.value = null
    }
    await Promise.all([loadAddedAccounts(), loadAvailableAccounts()])
  } catch (e) {
    ElMessage.error('移除失败: ' + (e.response?.data?.message || e.message))
  }
}

// ========== 服务器管理 ==========

async function loadAddedServers() {
  try {
    const resp = await emuApi.get('/servers/added')
    addedServers.value = resp.data || []
    // 默认选择第一个服务器（如果没有选中的）
    if (!selectedServerId.value && addedServers.value.length > 0) {
      selectedServerId.value = addedServers.value[0].serverId
    }
    // 如果选中的服务器已被删除，选择第一个
    if (selectedServerId.value && !addedServers.value.some(s => s.serverId === selectedServerId.value)) {
      selectedServerId.value = addedServers.value.length > 0 ? addedServers.value[0].serverId : null
    }
  } catch { addedServers.value = [] }
}

// 选择服务器
function selectServer(serverId) {
  selectedServerId.value = serverId
}

async function loadAvailableServers() {
  availableServersLoading.value = true
  try {
    const resp = await emuApi.get('/servers/available')
    availableServers.value = resp.data || []
  } catch { availableServers.value = [] }
  finally { availableServersLoading.value = false }
}

async function addServer(server) {
  try {
    await emuApi.post('/servers/add', {
      serverId: server.id,
      discordAccountId: server.discordAccountId || null
    })
    ElMessage.success('服务器已添加，正在同步成员...')
    await loadAddedServers()
    await loadAvailableServers()
    // 选中新添加的服务器
    selectedServerId.value = server.id
    // 自动同步好友数据
    if (server.serverId) {
      await syncFriends({ serverId: server.serverId, name: server.name })
    }
    // 刷新好友池统计
    await Promise.all([loadFriendPoolStats(), loadAllServerFriendPoolStats()])
  } catch (e) {
    ElMessage.error('添加失败: ' + (e.response?.data?.message || e.message))
  }
}

async function removeServer(bindingId) {
  try {
    // 找到要删除的服务器的 serverId
    const server = addedServers.value.find(s => s.id === bindingId)
    const serverId = server?.serverId
    
    await emuApi.delete(`/servers/${bindingId}`)
    ElMessage.success('服务器已移除')
    await Promise.all([loadAddedServers(), loadAvailableServers()])
    // 如果删除的是选中的服务器，更新选中状态
    if (serverId && selectedServerId.value === serverId) {
      selectedServerId.value = addedServers.value.length > 0 ? addedServers.value[0].serverId : null
    }
    // 刷新好友池统计
    await Promise.all([loadFriendPoolStats(), loadAllServerFriendPoolStats()])
  } catch (e) {
    ElMessage.error('移除失败: ' + (e.response?.data?.message || e.message))
  }
}

async function syncFriends(server) {
  try {
    const resp = await emuApi.post(`/servers/${server.serverId}/sync-friends`)
    const data = resp.data
    
    if (data.fetchStarted) {
      if (data.fetching) {
        // 正在抓取中
        ElMessageBox.alert(
          `<div style="line-height: 1.6;">
            <p><strong>${data.message}</strong></p>
            <p style="margin-top: 10px;">当前进度:</p>
            <ul style="margin: 5px 0; padding-left: 20px;">
              <li>已获取成员: ${data.progress?.membersUnique || 0}</li>
              <li>状态: ${data.progress?.status || '未知'}</li>
            </ul>
            <p style="margin-top: 10px; color: #909399;">请等待抓取完成后（通常需要几分钟），再点击同步</p>
          </div>`,
          '成员抓取进行中',
          { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了' }
        )
      } else {
        // 启动了新的抓取任务
        ElMessageBox.alert(
          `<div style="line-height: 1.6;">
            <p><strong>${data.message}</strong></p>
            ${data.diagnostic ? `
            <div style="margin-top: 10px; background: #f5f7fa; padding: 10px; border-radius: 4px; font-size: 12px;">
              <p>任务ID: ${data.diagnostic.taskId}</p>
              <p>预计时间: ${data.diagnostic.estimatedTime}</p>
            </div>` : ''}
          </div>`,
          '成员抓取已启动',
          { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了' }
        )
      }
    } else if (data.success) {
      // 同步成功
      ElMessageBox.alert(
        `<div style="line-height: 1.6;">
          <p><strong>${data.message}</strong></p>
          ${data.totalMembers ? `<p style="margin-top: 10px;">服务器总成员数: ${data.totalMembers}</p>` : ''}
        </div>`,
        '同步成功',
        { dangerouslyUseHTMLString: true, confirmButtonText: '确定' }
      )
      await loadFriendPoolStats()
      await loadFriendPool()
    } else {
      // 同步失败
      ElMessageBox.alert(
        `<div style="line-height: 1.6;">
          <p style="color: #f56c6c;"><strong>同步失败</strong></p>
          <p style="margin-top: 10px;">${data.message}</p>
          ${data.diagnostic ? `
          <div style="margin-top: 10px; background: #fef0f0; padding: 10px; border-radius: 4px; font-size: 12px; color: #606266;">
            <p>诊断信息:</p>
            <pre style="margin: 5px 0; white-space: pre-wrap;">${JSON.stringify(data.diagnostic, null, 2)}</pre>
          </div>` : ''}
          <p style="margin-top: 10px; color: #909399;">请检查: 1) Discord Token 是否有效 2) 服务器链接是否正确 3) 网络是否正常</p>
        </div>`,
        '同步失败',
        { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了', type: 'error' }
      )
    }
  } catch (e) {
    ElMessageBox.alert(
      `<div style="line-height: 1.6;">
        <p style="color: #f56c6c;"><strong>请求失败</strong></p>
        <p style="margin-top: 10px;">${e.response?.data?.message || e.message}</p>
        <p style="margin-top: 10px; color: #909399;">请检查后端服务是否正常运行</p>
      </div>`,
      '错误',
      { dangerouslyUseHTMLString: true, confirmButtonText: '我知道了', type: 'error' }
    )
  }
}

// ========== 好友号池 ==========

function getServerIdForPool() {
  return selectedServerId.value
}

async function loadFriendPool() {
  friendPoolLoading.value = true
  try {
    const params = { status: friendPoolFilter.value || undefined }
    const sid = getServerIdForPool()
    if (sid) params.serverId = sid
    const resp = await friendApi.get('/emu/friend-pool', { params })
    friendPool.value = resp.data || []
  } catch { friendPool.value = [] }
  finally { friendPoolLoading.value = false }
}

async function loadFriendPoolStats(silent = false) {
  try {
    const params = {}
    const sid = getServerIdForPool()
    if (sid) params.serverId = sid
    const resp = await friendApi.get('/emu/friend-pool/stats', { params })
    friendPoolStats.value = resp.data || { total: 0, pending: 0, assigned: 0, success: 0, failed: 0 }
  } catch {
    if (!silent) {
      // silently fail for polling
    }
  }
}

// 加载所有服务器的统计数据
async function loadAllServerFriendPoolStats() {
  try {
    const resp = await friendApi.get('/emu/friend-pool/stats-by-server')
    console.log('stats-by-server response:', resp)
    const list = resp.data || []
    console.log('stats-by-server data:', list)
    const statsMap = {}
    for (const item of list) {
      if (item.serverId) {
        // 统一转为字符串键名，避免类型不匹配
        const key = String(item.serverId)
        statsMap[key] = {
          total: item.total || 0,
          pending: item.pending || 0,
          assigned: item.assigned || 0,
          success: item.success || 0,
          failed: item.failed || 0
        }
      }
    }
    console.log('serverFriendPoolStats:', statsMap)
    serverFriendPoolStats.value = statsMap
  } catch (e) {
    console.error('loadAllServerFriendPoolStats error:', e)
  }
}

async function refreshFriendPool() {
  await Promise.all([loadFriendPool(), loadFriendPoolStats(), loadAllServerFriendPoolStats()])
  ElMessage.success('刷新完成')
}

function startFriendPoolPolling() {
  stopFriendPoolPolling()
  friendPoolPollTimer = setInterval(() => {
    loadFriendPoolStats(true)
    loadAllServerFriendPoolStats()
  }, 5000)
}

function stopFriendPoolPolling() {
  if (friendPoolPollTimer) {
    clearInterval(friendPoolPollTimer)
    friendPoolPollTimer = null
  }
}

function friendStatusTag(status) {
  const map = {
    'PENDING': 'info',
    'ASSIGNED': 'warning',
    'SUCCESS': 'success',
    'FAILED': 'danger'
  }
  return map[status] || 'info'
}

// 根据数字状态获取标签类型
function getFriendStatusType(status) {
  const map = {
    0: 'info',
    1: 'warning',
    2: 'success',
    3: 'danger'
  }
  return map[status] || 'info'
}

// 根据数字状态获取状态文本
function getFriendStatusText(status) {
  const map = {
    0: '待添加',
    1: '已分配',
    2: '成功',
    3: '失败'
  }
  return map[status] || '未知'
}

// ========== 自动计算间隔时间 ==========
function calculateIntervalMinutes() {
  const startTime = autoConfig.value.addStartTime || '09:00'
  const endTime = autoConfig.value.addEndTime || '18:00'
  const dailyLimit = autoConfig.value.dailyLimit || 6
  
  // 解析时间为分钟
  const [startH, startM] = startTime.split(':').map(Number)
  const [endH, endM] = endTime.split(':').map(Number)
  const startMinutes = startH * 60 + startM
  const endMinutes = endH * 60 + endM
  
  const periodMin = endMinutes - startMinutes
  if (periodMin <= 0 || dailyLimit <= 0) return 1
  
  return Math.max(1, Math.floor(periodMin / dailyLimit))
}

// 监听配置变化，自动计算间隔时间
watch(() => [autoConfig.value.addStartTime, autoConfig.value.addEndTime, autoConfig.value.dailyLimit], () => {
  autoConfig.value.calculatedIntervalMinutes = calculateIntervalMinutes()
}, { immediate: true })

// ========== 预估总时长计算 ==========
function calculateEstimatedTotalDuration() {
  const totalEmulators = emulators.value.length || 0
  const batchSize = autoConfig.value.maxConcurrentEmulators || 5
  const singleDuration = autoConfig.value.estimatedSingleDurationMin || 5
  
  if (totalEmulators <= 0 || batchSize <= 0) return singleDuration
  
  const batches = Math.ceil(totalEmulators / batchSize)
  return singleDuration * batches
}

// ========== 模拟器操作 ==========

async function loadAutoConfig() {
  try {
    const resp = await friendApi.get('/data/autoconfig')
    if (resp.data) {
      autoConfig.value = {
        intervalMinutes: resp.data.intervalSeconds !== undefined ? (Math.round(resp.data.intervalSeconds / 60) || 1) : autoConfig.value.intervalMinutes,
        delayMinMinutes: resp.data.delayMinSeconds !== undefined ? (Math.round(resp.data.delayMinSeconds / 60) || 0) : autoConfig.value.delayMinMinutes,
        delayMaxMinutes: resp.data.delayMaxSeconds !== undefined ? (Math.round(resp.data.delayMaxSeconds / 60) || 10) : autoConfig.value.delayMaxMinutes,
        autoLoginDiscord: resp.data.autoLoginDiscord !== undefined ? resp.data.autoLoginDiscord : autoConfig.value.autoLoginDiscord,
        maxConcurrentEmulators: resp.data.maxConcurrentEmulators !== undefined ? resp.data.maxConcurrentEmulators : autoConfig.value.maxConcurrentEmulators,
        emulatorStartIntervalSec: resp.data.emulatorStartIntervalSec !== undefined ? resp.data.emulatorStartIntervalSec : autoConfig.value.emulatorStartIntervalSec,
        testModeEnabled: resp.data.testModeEnabled !== undefined ? resp.data.testModeEnabled : autoConfig.value.testModeEnabled,
        // 新字段
        addStartTime: resp.data.addStartTime || autoConfig.value.addStartTime,
        addEndTime: resp.data.addEndTime || autoConfig.value.addEndTime,
        dailyLimit: resp.data.dailyLimit !== undefined ? resp.data.dailyLimit : autoConfig.value.dailyLimit,
        estimatedSingleDurationMin: resp.data.estimatedSingleDurationMin !== undefined ? resp.data.estimatedSingleDurationMin : autoConfig.value.estimatedSingleDurationMin,
        // 计算字段
        calculatedIntervalMinutes: resp.data.calculatedIntervalMinutes || calculateIntervalMinutes()
      }
    }
  } catch {}
}

async function saveAutoConfig() {
  try {
    // 自动计算间隔时间
    const calculatedInterval = calculateIntervalMinutes()
    autoConfig.value.calculatedIntervalMinutes = calculatedInterval
    
    const configToSave = {
      intervalSeconds: calculatedInterval * 60,
      delayMinSeconds: autoConfig.value.delayMinMinutes * 60,
      delayMaxSeconds: autoConfig.value.delayMaxMinutes * 60,
      autoLoginDiscord: autoConfig.value.autoLoginDiscord,
      maxConcurrentEmulators: autoConfig.value.maxConcurrentEmulators,
      emulatorStartIntervalSec: autoConfig.value.emulatorStartIntervalSec,
      testModeEnabled: autoConfig.value.testModeEnabled,
      // 新字段
      addStartTime: autoConfig.value.addStartTime,
      addEndTime: autoConfig.value.addEndTime,
      dailyLimit: autoConfig.value.dailyLimit,
      estimatedSingleDurationMin: autoConfig.value.estimatedSingleDurationMin
    }
    await friendApi.post('/data/autoconfig', configToSave)
    ElMessage.success('自动加好友配置已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

// ====== Discord账号编号 列编辑 ======
function startEditAccountNumber(row) {
  editingAccountNumberIdx.value = row.index
  editingAccountNumberValue.value = row.discordAccountNumber ? Number(row.discordAccountNumber) : Number(row.index)
}
function cancelEditAccountNumber() {
  editingAccountNumberIdx.value = null
  editingAccountNumberValue.value = null
}
async function saveEditAccountNumber(index) {
  const number = editingAccountNumberValue.value
  if (number == null || isNaN(number) || number < 1 || number > 999999) {
    ElMessage.warning('请输入 1~999999 的整数')
    return
  }
  try {
    await axios.put(`${API_BASE}/emulators/${index}/discord-account-number`, { number })
    ElMessage.success('已修改 Discord 账号编号绑定')
    cancelEditAccountNumber()
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  }
}

async function startAutoAll() {
  // 先保存配置
  await saveAutoConfig()
  
  // 计算预估总时长
  const totalEmulators = emulators.value.length || 0
  const intervalMinutes = autoConfig.value.calculatedIntervalMinutes || calculateIntervalMinutes()
  const estimatedTotalDuration = calculateEstimatedTotalDuration()
  
  // 如果预估总时长 < 间隔时长，显示确认弹窗
  if (estimatedTotalDuration < intervalMinutes) {
    try {
      await ElMessageBox.confirm(
        `当前共 ${totalEmulators} 台模拟器，按当前间隔时间设置，完成一轮添加预计时间 ${estimatedTotalDuration} 分钟，小于间隔时间（${intervalMinutes} 分钟）。`,
        '提示',
        {
          confirmButtonText: '继续启动',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
      // 用户确认后继续启动
      doStartAutoAll()
    } catch {
      // 用户取消
      return
    }
  } else {
    // 直接启动
    doStartAutoAll()
  }
}

async function doStartAutoAll() {
  try {
    const resp = await friendApi.post('/emu/autoadd/startAll')
    if (resp.data && resp.data.success !== false) {
      ElMessage.success(`已开始自动加好友 (模式: ${resp.data.mode === 'continuous' ? '连续执行' : '定时循环'})`)
      autoAddTaskRunning.value = true
      startAutoAddStatusPolling()
    } else {
      ElMessage.error(resp.data?.message || '启动失败')
    }
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('操作失败: ' + (e.response?.data?.message || e.message))
  }
}

async function stopAutoAll() {
  try {
    const resp = await friendApi.post('/emu/autoadd/stopAll')
    ElMessage.success(resp.data?.message || '已停止所有自动加好友')
    autoAddTaskRunning.value = false
    stopAutoAddStatusPolling()
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('操作失败: ' + (e.response?.data?.message || e.message))
    autoAddTaskRunning.value = false
    stopAutoAddStatusPolling()
  }
}

// 获取任务状态
async function fetchAutoAddStatus() {
  try {
    const resp = await friendApi.get('/emu/autoadd/status')
    if (resp.data) {
      autoAddTaskStatus.value = resp.data
      autoAddTaskRunning.value = resp.data.isRunning || false
    }
  } catch (e) {
    console.error('获取任务状态失败', e)
  }
}

// 开始轮询任务状态
function startAutoAddStatusPolling() {
  stopAutoAddStatusPolling()
  // 立即获取一次
  fetchAutoAddStatus()
  // 每2秒轮询一次
  autoAddStatusPollTimer = setInterval(() => {
    fetchAutoAddStatus()
  }, 2000)
}

// 停止轮询任务状态
function stopAutoAddStatusPolling() {
  if (autoAddStatusPollTimer) {
    clearInterval(autoAddStatusPollTimer)
    autoAddStatusPollTimer = null
  }
}

async function startAuto(index) {
  autoStartingEmulators.value.add(index)
  try {
    // 传递选中的服务器ID，让模拟器使用指定服务器的好友号池
    const body = selectedServerId.value ? { serverId: selectedServerId.value } : {}
    await friendApi.post(`/autoadd/${index}/start`, body)
    const serverHint = selectedServerId.value ? `（服务器ID: ${selectedServerId.value}）` : ''
    ElMessage.success(`模拟器 #${index} 已开始自动加好友${serverHint}`)
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  } finally {
    autoStartingEmulators.value.delete(index)
  }
}

async function stopAuto(index) {
  try {
    await friendApi.post(`/autoadd/${index}/stop`)
    ElMessage.success(`模拟器 #${index} 已停止自动加好友`)
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('停止失败: ' + (e.response?.data?.message || e.message))
  }
}

async function applyCount() {
  emuLoading.value = true
  showLoading('新增模拟器中...')
  try {
    const resp = await emuApi.post('/emulators/count', {
      count: targetCount.value,
      cpuCores: emuConfig.value.cpuCores,
      memoryGb: emuConfig.value.memoryGb,
      mode: 'add'
    })
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success(`已新增 ${targetCount.value} 台模拟器 (${emuConfig.value.cpuCores}核, ${emuConfig.value.memoryGb}G)`)
    setTimeout(() => fetchEmulators(), 2000)
  } catch (e) {
    ElMessage.error('新增失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
    hideLoading()
  }
}

// 新增模拟器弹窗确认
async function confirmAddEmulators() {
  if (!addEmuCount.value || addEmuCount.value < 1) {
    ElMessage.warning('请输入有效的新增数量')
    return
  }
  if (!physicalStatus.value.agentOnline) {
    ElMessage.warning('未检测到在线 Agent，无法新增模拟器')
    return
  }
  showAddDialog.value = false
  emuLoading.value = true
  showLoading(`正在创建 ${addEmuCount.value} 台模拟器...`)
  try {
    const requestBody = {
      count: addEmuCount.value,
      cpuCores: addEmuConfig.value.cpuCores,
      memoryGb: addEmuConfig.value.memoryGb,
      mode: 'add'
    }
    // 如果有多个 Agent，传递选择的 deviceId
    if (addEmuDeviceId.value) {
      requestBody.deviceId = addEmuDeviceId.value
    }
    const resp = await emuApi.post('/emulators/count', requestBody)
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success(`已新增 ${addEmuCount.value} 台模拟器 (${addEmuConfig.value.cpuCores}核, ${addEmuConfig.value.memoryGb}G)`)
    // 延迟2秒后刷新列表，确保后端同步完成
    setTimeout(() => fetchEmulators(), 2000)
  } catch (e) {
    ElMessage.error('新增失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
    hideLoading()
  }
}

async function startAll() {
  try {
    await ElMessageBox.confirm('确定要启动所有模拟器吗？', '确认', { type: 'warning' })
    emuLoading.value = true
    showLoading('全部启动中...')
    const resp = await emuApi.post('/emulators/startAll', null, { params: { count: targetCount.value } })
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success('启动指令已发送')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
    hideLoading()
  }
}

async function stopAll() {
  try {
    await ElMessageBox.confirm('确定要停止所有模拟器吗？', '确认', { type: 'warning' })
    emuLoading.value = true
    showLoading('全部停止中...')
    const resp = await emuApi.post('/emulators/stopAll')
    emulators.value = Array.isArray(resp.data) ? resp.data : []
    ElMessage.success('停止指令已发送')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('停止失败: ' + (e.response?.data?.message || e.message))
  } finally {
    emuLoading.value = false
    hideLoading()
  }
}

async function restartAll() {
  try {
    await ElMessageBox.confirm('确定要重启所有模拟器吗？', '确认', { type: 'warning' })
    emuLoading.value = true
    showLoading('全部重启中...')
    for (const emu of emulators.value.filter(e => e.status === 'RUNNING')) {
      try { await emuApi.post(`/emulators/${emu.index}/restart`) } catch {}
    }
    ElMessage.success('重启指令已发送')
    await fetchEmulators()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('重启失败')
  } finally {
    emuLoading.value = false
    hideLoading()
  }
}

function isOperating(index) {
  return operatingEmulators.value.has(index)
}

function isAutoStarting(index) {
  return autoStartingEmulators.value.has(index)
}

async function startEmulator(index) {
  operatingEmulators.value.add(index)
  showLoading(`模拟器 #${index} 启动中...`)
  try {
    const resp = await emuApi.post(`/emulators/${index}/start`)
    ElMessage.success(`模拟器 #${index} 启动指令已发送`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
    setTimeout(() => fetchEmulators(), 3000)
  } catch (e) {
    ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  } finally {
    operatingEmulators.value.delete(index)
    hideLoading()
  }
}

async function stopEmulator(index) {
  operatingEmulators.value.add(index)
  showLoading(`模拟器 #${index} 停止中...`)
  try {
    const resp = await emuApi.post(`/emulators/${index}/stop`)
    ElMessage.success(`模拟器 #${index} 停止指令已发送`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
    setTimeout(() => fetchEmulators(), 3000)
  } catch (e) {
    ElMessage.error('停止失败: ' + (e.response?.data?.message || e.message))
  } finally {
    operatingEmulators.value.delete(index)
    hideLoading()
  }
}

async function restartEmulator(index) {
  operatingEmulators.value.add(index)
  showLoading(`模拟器 #${index} 重启中...`)
  try {
    const resp = await emuApi.post(`/emulators/${index}/restart`)
    ElMessage.success(`模拟器 #${index} 重启指令已发送`)
    emulators.value = emulators.value.map(e => e.index === index ? resp.data : e)
    setTimeout(() => fetchEmulators(), 3000)
  } catch (e) {
    ElMessage.error('重启失败: ' + (e.response?.data?.message || e.message))
  } finally {
    operatingEmulators.value.delete(index)
    hideLoading()
  }
}

function showEmuDetail(row) {
  emuDetailData.value = row
  emuDetailVisible.value = true
}

async function repairEmulator(index) {
  try {
    await ElMessageBox.confirm(`确定要对模拟器 #${index} 执行一键修复吗？`, '确认', { type: 'warning' })
    showLoading(`模拟器 #${index} 修复中...`)
    await emuApi.post(`/emulators/${index}/repair`)
    ElMessage.success(`模拟器 #${index} 修复指令已发送`)
    await fetchEmulators()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('修复失败: ' + (e.response?.data?.message || e.message))
  } finally {
    hideLoading()
  }
}

async function deleteEmulator(index) {
  try {
    await ElMessageBox.confirm(`确定要删除模拟器 #${index} 吗？`, '确认', { type: 'warning' })
    showLoading(`模拟器 #${index} 删除中...`)
    const resp = await emuApi.delete(`/emulators/${index}`)
    if (resp.data?.success) {
      ElMessage.success('删除成功')
      emulators.value = emulators.value.filter(e => e.index !== index)
    }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  } finally {
    hideLoading()
  }
}

async function installDiscord(index) {
  try {
    await emuApi.post(`/discord/install/${index}`)
    ElMessage.success(`模拟器 #${index} 开始安装 Discord`)
    await fetchEmulators()
  } catch (e) {
    ElMessage.error('安装失败: ' + (e.response?.data?.message || e.message))
  }
}

async function launchDiscord(index) {
  try {
    const resp = await emuApi.post(`/discord/launch/${index}`)
    ElMessage.success(resp.data?.result || 'Discord 启动指令已发送')
  } catch (e) {
    ElMessage.error('启动失败: ' + (e.response?.data?.message || e.message))
  }
}

function statusText(status) {
  return { RUNNING: '运行中', STOPPED: '已停止', CREATING: '创建中', ERROR: '错误' }[status] || status || '未知'
}

function statusTagType(status) {
  return { RUNNING: 'success', STOPPED: 'info', CREATING: 'warning', ERROR: 'danger' }[status] || 'info'
}

function formatCountdown(timestamp) {
  if (!timestamp || timestamp <= 0) return '-'
  const diff = Math.max(0, Math.floor((timestamp - now.value) / 1000))
  if (diff <= 0) return '00:00:00'
  const h = Math.floor(diff / 3600)
  const m = Math.floor((diff % 3600) / 60)
  const s = diff % 60
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(h)}:${pad(m)}:${pad(s)}`
}
</script>

<style scoped>
/* ========== 与 GuildMembers.vue 保持一致的页面结构样式 ========== */
.guild-members-page {
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

.page-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--color-text);
}

.page-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--color-text-2);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-body {
  flex: 1;
  min-height: 0;
  padding: 20px 24px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.content { 
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 12px;
  overflow: hidden;
}

.panel { margin-bottom: 12px; }
.panel-header { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: 14px; color: var(--color-text); }
.panel-body { display: flex; flex-direction: column; gap: 8px; }

.form-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.form-row.inline-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: nowrap;
}

.inline-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.form-row.inline label,
.form-row label { min-width: auto; font-size: 13px; color: var(--color-text); }

.action-row { display: flex; gap: 6px; flex-wrap: wrap; }

.hint { font-size: 13px; color: var(--color-text-2); }
.hint-sm { font-size: 12px; color: var(--color-text-3); margin-left: 6px; }
.unit { font-size: 13px; color: var(--color-text-2); }

.batch-toolbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  margin-bottom: 12px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.pagination-container {
  display: flex;
  justify-content: flex-end;
  padding: 10px 14px;
  flex-shrink: 0;
}

/* 去除 el-card 白色背景 */
:deep(.el-card) {
  background: var(--color-bg) !important;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

:deep(.el-card__header) {
  background: var(--color-bg);
  border-bottom: 1px solid var(--color-border);
}

/* TAB 样式 - 与 GuildMembers.vue 统一 */
:deep(.el-tabs--border-card > .el-tabs__header) {
  background: var(--color-bg-2);
  border: 1px solid var(--color-border);
  border-bottom: none;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
}

:deep(.el-tabs--border-card > .el-tabs__content) {
  border: 1px solid var(--color-border);
  border-top: none;
  padding: 16px;
  background: var(--color-bg-2);
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
}

:deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* 表格容器 */
.table-container {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.table-container :deep(.el-table) {
  flex: 1;
  min-height: 0;
  background: var(--color-bg) !important;
}

.table-container :deep(.el-table__wrapper) {
  background: var(--color-bg) !important;
}

.table-container :deep(.el-table__inner-wrapper) {
  background: var(--color-bg) !important;
}

.table-container :deep(.el-table__header-wrapper) {
  background: var(--color-bg) !important;
}

.table-container :deep(.el-table__body-wrapper) {
  background: var(--color-bg) !important;
}

.table-container :deep(.el-table tr) {
  background: var(--color-bg) !important;
}

.table-container :deep(.el-table th.el-table__cell) {
  background: var(--color-bg-2) !important;
}

.table-container :deep(.el-table td.el-table__cell) {
  background: var(--color-bg) !important;
}

.table-wrap {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  width: 100%;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.batch-info { display: flex; align-items: center; gap: 8px; }
.batch-count { font-size: 13px; color: var(--color-text-2); }

.batch-actions { display: flex; gap: 4px; flex-wrap: wrap; }

.emu-name { font-weight: 600; font-size: 13px; color: var(--color-text); }

:deep(.checkbox-column .cell) {
  display: flex;
  justify-content: center;
  align-items: center;
}

:deep(.checkbox-column .el-checkbox) {
  margin-right: 0;
}

:deep(.checkbox-column .el-checkbox__inner) {
  width: 16px;
  height: 16px;
  border-color: #409eff;
  background-color: var(--color-bg);
}

:deep(.checkbox-column .el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: #409eff;
  border-color: #409eff;
}

:deep(.checkbox-column .el-checkbox__input.is-indeterminate .el-checkbox__inner) {
  background-color: #409eff;
  border-color: #409eff;
}

:deep(.selected-row) {
  background-color: var(--color-bg-2) !important;
}

:deep(.el-table .cell) {
  padding: 6px 10px;
  font-size: 13px;
}

/* 新增模拟器弹窗样式 */
.add-emu-form {
  padding: 8px 0;
}

.add-emu-form .form-row {
  margin-bottom: 12px;
}

.add-emu-form label {
  min-width: 80px;
  font-size: 13px;
  color: var(--color-text);
}

.loading-wrap,
.error-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 40px 24px;
  color: var(--color-text-2);
}

.error-wrap h3 { color: #f56c6c; }
.error-wrap pre {
  background: #2c3e50;
  color: #f1f5f9;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
}

/* 账号列表样式 */
.account-list,
.server-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.account-item,
.server-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  background: var(--color-bg-2);
  border-radius: 4px;
}

.account-info,
.server-info {
  display: flex;
  align-items: center;
  gap: 6px;
}

.account-name,
.server-name {
  font-weight: 500;
  font-size: 13px;
  color: var(--color-text);
}

.account-number {
  color: var(--color-text-3);
  font-size: 12px;
}

.server-account {
  color: #409eff;
  font-size: 12px;
}

.server-count {
  color: #67c23a;
  font-size: 12px;
}

.server-item {
  cursor: pointer;
  transition: all 0.2s;
}

.server-item:hover {
  background: var(--color-bg);
}

.server-item--selected {
  background: var(--color-bg);
  border: 1px solid #409eff;
}

.server-selected-tag {
  margin-left: 4px;
}

.friend-pool-server-tag {
  color: #409eff;
  font-size: 13px;
}

.server-actions {
  display: flex;
  gap: 6px;
}

.empty-hint {
  text-align: center;
  color: #909399;
  padding: 16px;
  font-size: 13px;
}

/* 弹窗样式 */
.dialog-section {
  margin-bottom: 12px;
}

.dialog-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #303133;
}

.dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.filter-row {
  display: flex;
  gap: 8px;
}

.loading-hint {
  text-align: center;
  color: #909399;
  padding: 16px;
}

/* 好友号池样式 */
.friend-pool-stats {
  margin-bottom: 8px;
}

.stat-card {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 6px 8px;
  text-align: center;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  line-height: 1.2;
}

.stat-progress-row {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 3px 0;
}

.stat-progress-row .el-progress {
  flex: 1;
  min-width: 0;
}

.stat-progress-row .el-progress-bar__outer {
  border-radius: 3px;
}

.stat-progress-row .el-progress-bar__inner {
  border-radius: 3px;
}

.stat-ratio {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
  flex-shrink: 0;
}

.stat-label {
  font-size: 11px;
  color: #909399;
  margin-top: 1px;
}

.stat-pending {
  background: #fdf6ec;
}
.stat-pending .stat-value {
  color: #e6a23c;
}
.stat-pending .stat-ratio {
  color: #e6a23c;
}
.stat-pending .el-progress-bar__inner {
  background-color: #e6a23c !important;
}

.stat-assigned {
  background: #faecd8;
}
.stat-assigned .stat-value {
  color: #e6a23c;
}
.stat-assigned .stat-ratio {
  color: #e6a23c;
}
.stat-assigned .el-progress-bar__inner {
  background-color: #e6a23c !important;
}

.stat-success {
  background: #f0f9eb;
}
.stat-success .stat-value {
  color: #67c23a;
}
.stat-success .stat-ratio {
  color: #67c23a;
}
.stat-success .el-progress-bar__inner {
  background-color: #67c23a !important;
}

.stat-failed {
  background: #fef0f0;
}
.stat-failed .stat-value {
  color: #f56c6c;
}
.stat-failed .stat-ratio {
  color: #f56c6c;
}
.stat-failed .el-progress-bar__inner {
  background-color: #f56c6c !important;
}

.friend-pool-filter {
  margin-bottom: 8px;
}

/* 加好友状态cell */
.friend-status-cell {
  font-size: 12px;
  line-height: 1.6;
}
.fs-stats {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.fs-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 1.4;
}
.fs-assigned {
  background: #ecf5ff;
  color: #409eff;
  border: 1px solid #d9ecff;
}
.fs-success {
  background: #f0f9eb;
  color: #67c23a;
  border: 1px solid #e1f3d8;
}
.fs-failed {
  background: #fef0f0;
  color: #f56c6c;
  border: 1px solid #fde2e2;
}
.fs-row {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.fs-pending { color: #909399; }

/* 模拟器详情 */
.emu-detail {
  padding: 4px 0;
}

/* 按钮排列 */
.batch-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

/* 操作列按钮间距：1个空格（约4px） */
:deep(.el-table .cell .el-button + .el-button) {
  margin-left: 4px !important;
}
:deep(.el-table .cell .el-button) {
  margin-right: 0 !important;
}

.more-hint {
  text-align: center;
  color: #909399;
  font-size: 11px;
  margin-top: 4px;
}

/* 全屏加载遮罩样式 */
.global-loading-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
}

.global-loading-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 32px 48px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
}

.global-loading-text {
  font-size: 16px;
  font-weight: 500;
  color: #303133;
  margin: 0;
}

/* 服务器卡片列表 - 一行显示两个 */
.server-card-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.server-card {
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: 12px 14px;
  cursor: pointer;
  transition: all 0.2s;
}

.server-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.1);
}

.server-card--selected {
  border-color: #409eff;
  background: var(--color-bg-2);
}

/* 统计信息区域 */
.server-card-stats {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

.server-card-stats .stat-item {
  flex: 1;
  text-align: center;
  padding: 8px 6px;
  background: var(--color-bg-2);
  border-radius: 6px;
  min-width: 0;
}

/* 服务器名称特殊样式 - 放在左边 */
.server-card-stats .stat-server-name {
  flex: 1.2;
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.server-card-stats .server-name-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
  padding: 0 4px;
}

/* 删除按钮样式 - 放在右边 */
.server-card-stats .stat-delete {
  flex: 0.3;
  background: transparent;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

/* 其他统计项 */
.server-card-stats .stat-pending .stat-value { color: #e6a23c; }
.server-card-stats .stat-assigned .stat-value { color: #409eff; }
.server-card-stats .stat-success .stat-value { color: #67c23a; }
.server-card-stats .stat-failed .stat-value { color: #f56c6c; }

.server-card-stats .stat-value-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  margin-bottom: 2px;
}

.server-card-stats .stat-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
  white-space: nowrap;
}

.server-card-stats .stat-pct {
  font-size: 10px;
  color: var(--color-text-3);
  white-space: nowrap;
}

.server-card-stats .stat-label {
  font-size: 11px;
  color: var(--color-text-3);
  margin-top: 2px;
  white-space: nowrap;
}

.stat-with-progress {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.stat-with-progress .stat-num-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.stat-with-progress .stat-num {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text);
}

.stat-with-progress .stat-pct {
  font-size: 11px;
  color: var(--color-text-3);
}

/* 空状态提示 */
.empty-hint {
  padding: 24px;
  text-align: center;
  color: var(--color-text-3);
  font-size: 13px;
}

/* 操作列按钮间距 - 只保留1个空格 */
:deep(.el-table .cell .el-button + .el-button) {
  margin-left: 4px;
}

:deep(.el-table .cell .el-button) {
  margin-right: 0;
}
/* Agent 引导样式 */
.agent-guide-content .guide-section {
  margin-bottom: 16px;
}

.agent-guide-content h4 {
  margin: 0 0 8px 0;
  color: var(--el-text-color-primary);
}

.install-steps {
  margin: 0;
  padding-left: 20px;
}

.install-steps li {
  margin-bottom: 12px;
}

.install-steps li strong {
  display: block;
  margin-bottom: 4px;
  color: var(--el-color-primary);
}

.install-steps li p {
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.config-preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.config-preview pre {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}

.script-preview pre {
  margin: 0;
}

code {
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
  font-family: monospace;
  font-size: 13px;
}

</style>
