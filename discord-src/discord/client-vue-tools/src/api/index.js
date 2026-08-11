import http from './http'

export function login(username, password) {
  return http.post('/auth/login', { username, password })
}

export function listAccounts(params = {}) {
  return http.get('/discord-accounts', { params })
}
export function createAccount(payload) {
  return http.post('/discord-accounts', {
    name: payload.nickname || payload.name,
    botToken: payload.token || payload.botToken,
    email: payload.email,
    remark: payload.remark,
    merchantId: payload.merchantId,
    accountType: payload.accountType
  })
}
export function updateAccount(id, payload) {
  const body = {}
  if (payload.nickname !== undefined || payload.name !== undefined) body.name = payload.nickname || payload.name
  if (payload.token !== undefined || payload.botToken !== undefined) body.botToken = payload.token || payload.botToken
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
export function batchImport(payload) {
  const accounts = payload.items || payload.accounts || []
  return http.post('/discord-accounts/batch-import', { accounts })
}
export function syncAccountRelationships(id) {
  return http.post('/discord-accounts/' + id + '/connect')
}

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
export function fetchGuildMembers(data) {
  return http.post('/discord/members/fetch', data)
}
export function resolveMemberLink(link) {
  return http.post('/discord/members/resolve', { link })
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
export function getGuildMerchantConfig() {
  return http.get('/guild-servers/merchant-config')
}

export function listMerchants() {
  return http.get('/merchants')
}
