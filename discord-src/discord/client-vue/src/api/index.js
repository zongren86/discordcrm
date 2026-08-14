import http from './http'

export { http }

// 聚合API对象，兼容旧代码
const api = {
  get: (url, config) => http.get(url, config),
  post: (url, data, config) => http.post(url, data, config),
  put: (url, data, config) => http.put(url, data, config),
  delete: (url, config) => http.delete(url, config)
}
export default api
export { api }

// === 认证 ===
export function login(username, password) {
  return http.post('/auth/login', { username, password })
}
export function getAgentInfo() {
  return http.get('/auth/me')
}

// === Discord 账号 ===
export function listAccounts(params = {}) {
  return http.get('/discord-accounts', { params })
}
// 创建账号
export function createAccount(payload) {
  return http.post('/discord-accounts', {
    name: payload.nickname || payload.name,
    token: payload.token,
    email: payload.email,
    remark: payload.remark,
    merchantId: payload.merchantId,
    accountType: payload.accountType
  })
}
export function updateAccount(id, payload) {
  const body = {}
  if (payload.nickname !== undefined || payload.name !== undefined) body.name = payload.nickname || payload.name
  if (payload.token !== undefined) body.token = payload.token
  if (payload.status !== undefined) body.status = payload.status
  if (payload.remark !== undefined) body.remark = payload.remark
  if (payload.merchantId !== undefined) body.merchantId = payload.merchantId
  return http.put('/discord-accounts/' + id, body)
}
export function deleteAccount(id) {
  return http.delete('/discord-accounts/' + id)
}
export function importToken(payload) {
  return http.post('/discord-accounts/import-token', payload)
}
// 批量导入：后端字段 accounts[]，每一项email+password
export function batchImport(payload) {
  const accounts = payload.items || payload.accounts || []
  return http.post('/discord-accounts/batch-import', { accounts })
}
// 触发同步（connect接口会自动处理USER/BOT）
export function syncAccountRelationships(id) {
  return http.post('/discord-accounts/' + id + '/connect')
}
// 刷新 USER 账号的 Token
export function refreshAccountToken(id, email, password) {
  return http.post('/discord-accounts/' + id + '/refresh-token', { email, password })
}

// === 服务器 (Guild) ===
export function listAccountGuilds(accountId) {
  return http.get('/discord-accounts/' + accountId + '/guilds')
}
export function listGuildMembers(accountId, guildId, limit = 100, after) {
  const params = { limit }
  if (after) params.after = after
  return http.get('/discord-accounts/' + accountId + '/guilds/' + guildId + '/members', { params })
}

// === 会话 ===
export function listConversations(params = {}) {
  return http.get('/conversations', { params })
}
export function listConversationsByAccount(accountId) {
  return http.get('/conversations', { params: { accountId } })
}
export function listMessages(conversationId) {
  return http.get('/conversations/' + conversationId + '/messages')
}
export function loadMoreMessages(conversationId, beforeMsgId) {
  return http.get('/conversations/' + conversationId + '/messages/before/' + beforeMsgId)
}
export function sendMessage(conversationId, content) {
  return http.post('/conversations/' + conversationId + '/messages', { content })
}
export function openConversation(accountId, discordUserId) {
  return http.post('/conversations/open-dm', {
    accountId,
    friendDiscordUserId: discordUserId
  })
}
export function translateMessage(conversationId, messageId) {
  return http.post('/conversations/' + conversationId + '/messages/' + messageId + '/translate')
}

// === Discord 用户资料 ===
export function getUserProfile(userId) {
  return http.get('/discord-users/' + userId)
}
export function updateUserProfile(userId, payload) {
  return http.put('/discord-users/' + userId, payload)
}
export function searchUsers(keyword) {
  return http.get('/discord-users/search', { params: { keyword } })
}

// === 统计 ===
export function getStats(params = {}) {
  return http.get('/stats', { params })
}
export function getActiveCustomers(dateFrom) {
  return http.get('/stats/active-customers', { params: dateFrom ? { dateFrom } : {} })
}
export function getStageDistribution() {
  return http.get('/stats/stage-distribution')
}
export function getStatsTrend(days = 7) {
  return http.get('/stats/trend', { params: { days } })
}

// === 角色管理 ===
export function listRoles() {
  return http.get('/roles')
}
export function createRole(payload) {
  return http.post('/roles', payload)
}
export function updateRole(id, payload) {
  return http.put('/roles/' + id, payload)
}
export function deleteRole(id) {
  return http.delete('/roles/' + id)
}
export function getRolePermissions(id) {
  return http.get('/roles/' + id + '/permissions')
}
export function updateRolePermissions(id, permissionKeys) {
  return http.put('/roles/' + id + '/permissions', { permissionKeys })
}
export function getPermissionCatalog() {
  return http.get('/roles/permission-catalog')
}
export function getRoleMerchants(id) {
  return http.get('/roles/' + id + '/merchants')
}
export function updateRoleMerchants(id, merchantIds) {
  return http.put('/roles/' + id + '/merchants', { merchantIds })
}

// === 审计日志 ===
export function listAuditLogs(params = {}) {
  return http.get('/audit-logs', { params })
}
export function exportAuditLogs(params = {}) {
  return http.get('/audit-logs/export', { params })
}
export function getAuditFilters() {
  return http.get('/audit-logs/filters')
}

// === 提醒规则与通知 ===
export function listReminderRules() {
  return http.get('/reminders/rules')
}
export function createReminderRule(payload) {
  return http.post('/reminders/rules', payload)
}
export function updateReminderRule(id, payload) {
  return http.put('/reminders/rules/' + id, payload)
}
export function deleteReminderRule(id) {
  return http.delete('/reminders/rules/' + id)
}
export function listNotifications() {
  return http.get('/reminders/notifications')
}
export function unreadNotificationCount() {
  return http.get('/reminders/notifications/unread-count')
}
export function markNotificationRead(id) {
  return http.post('/reminders/notifications/' + id + '/read')
}
export function markAllNotificationsRead() {
  return http.post('/reminders/notifications/read-all')
}

// === AI配置 ===
export function listAISettings(merchantId) {
  const config = {}
  if (merchantId != null) config.headers = { 'X-Merchant-Id': merchantId }
  return http.get('/ai-settings', config)
}
export function getAISettingByFeature(feature, merchantId) {
  const config = {}
  if (merchantId != null) config.headers = { 'X-Merchant-Id': merchantId }
  return http.get('/ai-settings/feature/' + feature, config)
}
export function saveAISetting(payload, merchantId) {
  const config = {}
  if (merchantId != null) config.headers = { 'X-Merchant-Id': merchantId }
  return http.post('/ai-settings', payload, config)
}
export function deleteAISetting(id) {
  return http.delete('/ai-settings/' + id)
}

// === 商户管理 ===
export function listMerchants() {
  return http.get('/merchants')
}
export function createMerchant(payload) {
  return http.post('/merchants', payload)
}
export function updateMerchant(id, payload) {
  return http.put('/merchants/' + id, payload)
}
export function deleteMerchant(id) {
  return http.delete('/merchants/' + id)
}

// === 用户管理 ===
export function listUsers() {
  return http.get('/users')
}
export function createUser(payload) {
  return http.post('/users', payload)
}
export function updateUser(id, payload) {
  return http.put('/users/' + id, payload)
}
export function deleteUser(id) {
  return http.delete('/users/' + id)
}

// === 会话：漏斗/置顶/备注 ===
export function updateConversationStage(id, stage) {
  return http.put('/conversations/' + id + '/stage', { stage })
}
export function updateConversationPin(id, pinned) {
  return http.put('/conversations/' + id + '/pin', { pinned })
}
export function updateConversationRemark(id, remark) {
  return http.put('/conversations/' + id + '/remark', { remark })
}
export function markConversationAsRead(id) {
  return http.post('/conversations/' + id + '/mark-read')
}

// === 消息编辑/删除/Reaction/回复 ===
export function editMessage(conversationId, messageId, content) {
  return http.put('/conversations/' + conversationId + '/messages/' + messageId, { content })
}
export function deleteMessage(conversationId, messageId) {
  return http.delete('/conversations/' + conversationId + '/messages/' + messageId)
}
export function addReaction(conversationId, messageId, emoji, remove = false) {
  return http.post('/conversations/' + conversationId + '/messages/' + messageId + '/reactions', { emoji, remove })
}
export function replyMessage(conversationId, messageId, content) {
  return http.post('/conversations/' + conversationId + '/messages/' + messageId + '/reply', { content })
}

// === 附件 ===
export function uploadAttachment(file) {
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/attachments/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// === 客户管理 ===
export function listCustomers(params = {}) {
  return http.get('/customers', { params })
}
export function batchTags(userIds, tags, action) {
  return http.post('/customers/batch-tags', { userIds, tags, action })
}
export function batchStage(conversationIds, stage) {
  return http.post('/customers/batch-stage', { conversationIds, stage })
}
export function exportCustomers(includeMessages = false) {
  return http.get('/customers/export', { params: { includeMessages }, responseType: 'blob' })
}
export function getAiSuggestions(conversationId, tone = 'friendly', count = 3) {
  return http.get('/customers/' + conversationId + '/ai-suggestions', { params: { tone, count } })
}
export function batchSendMessage(conversationIds, content) {
  return http.post('/customers/batch-send', { conversationIds, content })
}

// === 会话分配/转移 ===
export function assignToAgent(conversationId, agentId) {
  return http.put('/conversations/' + conversationId + '/assign/' + agentId)
}
export function transferConversation(conversationId, agentId, reason) {
  return http.put('/conversations/' + conversationId + '/transfer', { agentId, reason })
}
export function listAvailableAgents() {
  return http.get('/conversations/agents')
}

// === 会话标签 ===
export function getConversationTags(conversationId) {
  return http.get('/conversation-tags/conversation/' + conversationId)
}
export function addConversationTags(conversationId, tags, color) {
  return http.post('/conversation-tags/conversation/' + conversationId, { tags, color })
}
export function removeConversationTag(conversationId, tagId) {
  return http.delete('/conversation-tags/conversation/' + conversationId + '/' + tagId)
}
export function setConversationTags(conversationId, tags, color) {
  return http.put('/conversation-tags/conversation/' + conversationId, { tags, color })
}
export function listTagNames() {
  return http.get('/conversation-tags/names')
}
export function filterConversationsByTag(tagName) {
  return http.get('/conversation-tags/filter', { params: { tagName } })
}

// === 消息模板 ===
export function listMessageTemplates(category) {
  return http.get('/message-templates', { params: category ? { category } : {} })
}
export function getTemplateCategories() {
  return http.get('/message-templates/categories')
}
export function createMessageTemplate(data) {
  return http.post('/message-templates', data)
}
export function updateMessageTemplate(id, data) {
  return http.put('/message-templates/' + id, data)
}
export function deleteMessageTemplate(id) {
  return http.delete('/message-templates/' + id)
}
export function batchDeleteTemplates(ids) {
  return http.delete('/message-templates/batch', { data: { ids } })
}

// === 数据统计增强 ===
export function getStatsByAgent(dateFrom, dateTo) {
  return http.get('/stats/by-agent', { params: { dateFrom, dateTo } })
}
export function getConversionRate() {
  return http.get('/stats/conversion-rate')
}
export function getCustomerActivity(dateFrom) {
  return http.get('/stats/customer-activity', { params: dateFrom ? { dateFrom } : {} })
}

// === 用户关联Discord账号 ===
export function listUserDiscordAccounts(userId) {
  return http.get(`/users/${userId}/discord-accounts`)
}
export function linkUserDiscordAccount(userId, accountId) {
  return http.post(`/users/${userId}/discord-accounts/${accountId}`)
}
export function unlinkUserDiscordAccount(userId, accountId) {
  return http.delete(`/users/${userId}/discord-accounts/${accountId}`)
}

// === 商户配置 ===
export function getMerchantConfig() {
  return http.get('/merchant-config')
}
export function updateMerchantConfig(data) {
  return http.put('/merchant-config', data)
}

// === 服务器管理 ===
export function listGuildServers(discordAccountId) {
  const params = {}
  if (discordAccountId) params.discordAccountId = discordAccountId
  return http.get('/guild-servers', { params })
}
export function saveGuildServer(data) {
  return http.post('/guild-servers', data)
}
export function deleteGuildServer(id) {
  return http.delete('/guild-servers/' + id)
}
export function listGuildServerMembers(id, params = {}) {
  return http.get('/guild-servers/' + id + '/members', { params })
}
export function countGuildServerMembers(id, params = {}) {
  return http.get('/guild-servers/' + id + '/members/count', { params })
}
export function fetchGuildMembers(data) {
  return http.post('/discord/members/fetch', data)
}
export function resolveMemberLink(link, discordAccountId) {
  const data = { link }
  if (discordAccountId) {
    data.discordAccountId = discordAccountId
  }
  return http.post('/discord/members/resolve', data)
}
export function getMemberFetchTask(taskId) {
  return http.get('/discord/members/task/' + taskId)
}
export function getMemberFetchTasks() {
  return http.get('/discord/members/tasks')
}
export function getLatestServerTask(serverId) {
  return http.get('/discord/members/server/' + serverId + '/latest-task')
}
export function stopMemberFetchTask(taskId) {
  return http.post('/discord/members/task/' + taskId + '/stop')
}
export function getGuildMerchantConfig() {
  return http.get('/guild-servers/merchant-config')
}

// === 账号编号管理 ===
export function listAccountNumbers(params = {}) {
  return http.get('/account-numbers', { params })
}
export function batchCreateAccountNumbers(accounts) {
  return http.post('/account-numbers/batch', { accounts })
}
export function bindAccountNumber(id, data) {
  return http.put(`/account-numbers/${id}/bind`, data)
}
export function getAccountNumberHistory(id) {
  return http.get(`/account-numbers/${id}/history`)
}
export function listUnboundAccounts(keyword) {
  return http.get('/account-numbers/unbound-accounts', { params: { keyword } })
}
export function getAccountNumber(id) {
  return http.get(`/account-numbers/${id}`)
}
export function unbindAccountNumber(id) {
  return http.put(`/account-numbers/${id}/unbind`)
}
export function deleteAccountNumber(id) {
  return http.delete(`/account-numbers/${id}`)
}

// === 用户-账号编号关联 ===
export function getUserAccountNumbers(userId) {
  return http.get(`/users/${userId}/account-numbers`)
}
export function batchLinkAccountNumbers(userId, range) {
  return http.post(`/users/${userId}/account-numbers`, { range })
}
export function unlinkAccountNumber(userId, accountNumberId) {
  return http.delete(`/users/${userId}/account-numbers/${accountNumberId}`)
}
