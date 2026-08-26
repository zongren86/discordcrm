export const config = {
  API_BASE_URL: 'http://localhost:9090',

  WS_URL: '/ws',

  EMU_API_URL: 'http://localhost:9090',

  USE_ABSOLUTE_URL: false
}

export const API_BASE = config.USE_ABSOLUTE_URL
  ? `${config.API_BASE_URL}/api`
  : '/api'

export const WS_BASE = config.USE_ABSOLUTE_URL
  ? `ws://${new URL(config.API_BASE_URL).host}/ws`
  : config.WS_URL

export const EMU_API_BASE = config.USE_ABSOLUTE_URL
  ? `${config.EMU_API_URL}/api`
  : '/emu-api'

export const EMU_WS_BASE = config.USE_ABSOLUTE_URL
  ? `ws://${new URL(config.EMU_API_URL).host}/ws`
  : '/emu-ws'

export default config
