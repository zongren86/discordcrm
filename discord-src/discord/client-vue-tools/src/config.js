export const config = {
  API_BASE_URL: 'http://localhost:8090',
  WS_URL: '/ws',
  EMU_API_URL: 'http://localhost:8088',
  EMU_FRONTEND_URL: 'http://localhost:5273',
  USE_ABSOLUTE_URL: true
}

export const API_BASE = config.USE_ABSOLUTE_URL 
  ? `${config.API_BASE_URL}/api` 
  : '/api'

export const EMU_API_BASE = config.USE_ABSOLUTE_URL
  ? `${config.EMU_API_URL}/api`
  : '/emu-api'

export default config
