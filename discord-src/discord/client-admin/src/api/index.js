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

// === 服务器 (Guild) ===
export function listAccountGuilds(accountId) {
  return http.get('/discord-accounts/' + accountId + '/guilds')
}
export function listGuildMembers(accountId, guildId, limit = 100, after) {
  const params = { limit }
  if (after) params.after = after
  return http.get('/discord-accounts/' + accountId + '/guilds/' + guildId + '/members', { params })
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
export function generateAccountNumbers(quantity) {
  return http.post('/account-numbers/generate', { quantity })
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

// === GIF 收藏 ===
export function listGifFavorites(accountId, type) {
  const params = { accountId }
  if (type) params.type = type
  return http.get('/gif-favorites', { params })
}
export function addGifFavorite(accountId, gifUrl, title, type, convertedGifUrl) {
  const body = { accountId, gifUrl, title }
  if (type) body.type = type
  if (convertedGifUrl) body.convertedGifUrl = convertedGifUrl
  return http.post('/gif-favorites', body)
}

export function uploadGifFile(file) {
  const formData = new FormData();
  formData.append('file', file);
  return http.post('/gif-favorites/upload-gif', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000
  });
}

export function removeGifFavorite(id, accountId) {
  return http.delete(`/gif-favorites/${id}`, { params: { accountId } })
}
export function checkGifFavorited(accountId, gifUrl) {
  return http.get('/gif-favorites/check', { params: { accountId, gifUrl } })
}
export function normalizeGifUrl(url) {
  return http.get('/gif-favorites/normalize-url', { params: { url } })
}
export function resolveGifUrl(url) {
  return http.get('/proxy/resolve-gif-url', { params: { url } })
}

export function getAgentStatus() {
  return http.get('/emu/agent/status')
}
export function getAgentConfig() {
  return http.get('/emu/agent/config')
}
export function downloadAgentScript() {
  return http.get('/emu/agent/download-script')
}
export function getAgentGuide() {
  return http.get('/emu/agent/guide')
}
export function downloadAgentPackage() {
  return http.get('/emu/agent/download-package', { responseType: 'blob' })
}
