// 应用配置文件
// 部署时只需修改此文件，不需要修改主代码

export const config = {
  // API 后端地址
  // 开发环境: http://localhost:8090
  // 生产环境: http://your-server:8090 或相对路径 /api
  API_BASE_URL: 'http://localhost:8090',
  
  // WebSocket 地址
  // 开发环境: ws://localhost:8090/ws
  // 生产环境: ws://your-server:8090/ws 或 /ws
  WS_URL: '/ws',

  // 模拟器后端地址
  EMU_API_URL: 'http://localhost:8088',
  
  // 模拟器前端地址（用于 iframe 嵌入）
  EMU_FRONTEND_URL: 'http://localhost:5273',
  
  // 是否使用绝对路径（跨域部署时设为 true）
  USE_ABSOLUTE_URL: true
}

// 计算导出的 baseURL
export const API_BASE = config.USE_ABSOLUTE_URL 
  ? `${config.API_BASE_URL}/api` 
  : '/api'

// 计算导出的 WebSocket URL
export const WS_BASE = config.USE_ABSOLUTE_URL
  ? `ws://${new URL(config.API_BASE_URL).host}/ws`
  : config.WS_URL

// 模拟器 API 基础路径（通过 Vite 代理转发）
export const EMU_API_BASE = config.USE_ABSOLUTE_URL
  ? `${config.EMU_API_URL}/api`
  : '/emu-api'

// 模拟器 WebSocket 基础路径
export const EMU_WS_BASE = config.USE_ABSOLUTE_URL
  ? `ws://${new URL(config.EMU_API_URL).host}/ws`
  : '/emu-ws'

export default config
