import { computed, shallowRef } from 'vue'
import { ApiError } from '../api/client'
import * as api from '../api/auth'

const user = shallowRef<api.CurrentUser | null>(null)
let accessToken: string | null = null
let expiresAt = 0
let revision = 0
let refreshInFlight: Promise<boolean> | null = null

function clear() {
  revision += 1
  accessToken = null
  expiresAt = 0
  user.value = null
}

function apply(tokens: api.LoginTokens) {
  accessToken = tokens.accessToken
  expiresAt = Date.now() + tokens.expiresIn * 1000
  user.value = tokens.user
}

// 跨标签页只广播失效事件，绝不在持久存储或广播消息中保存令牌。
const channel = typeof window !== 'undefined' && typeof BroadcastChannel !== 'undefined'
  ? new BroadcastChannel('docweave-auth') : null
if (channel) channel.onmessage = () => clear()

export const session = {
  user: computed(() => user.value),

  async signIn(username: string, password: string, signal?: AbortSignal) {
    const startedAt = ++revision
    const tokens = await api.login(username, password, signal)
    if (startedAt !== revision) throw new ApiError('登录状态已变化，请重试', { code: 'CANCELLED' })
    apply(tokens)
    channel?.postMessage('changed')
  },

  async restore(): Promise<boolean> {
    if (accessToken && Date.now() < expiresAt - 10_000) return true
    if (!refreshInFlight) {
      const startedAt = revision
      refreshInFlight = api.refresh().then((tokens) => {
        if (startedAt !== revision) return false
        apply(tokens)
        return true
      }).catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 401) {
          if (startedAt === revision) clear()
          return false
        }
        throw error
      }).finally(() => { refreshInFlight = null })
    }
    return refreshInFlight
  },

  async verify(signal?: AbortSignal): Promise<boolean> {
    if (!await this.restore() || !accessToken) return false
    const startedAt = revision
    try {
      const verifiedUser = await api.currentUser(accessToken, signal)
      if (startedAt !== revision) return false
      user.value = verifiedUser
      return true
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        if (startedAt === revision) clear()
        return false
      }
      throw error
    }
  },

  async personalArea(initialize = false, signal?: AbortSignal) {
    if (!await this.restore() || !accessToken) throw new ApiError('请先登录', { status: 401, code: 'UNAUTHENTICATED' })
    const startedAt = revision
    const area = await api.personalArea(accessToken, initialize, signal)
    if (startedAt !== revision) throw new ApiError('登录状态已变化', { code: 'CANCELLED' })
    return area
  },

  async signOut() {
    // 先等待本标签页刷新完成，再发送退出，避免并发轮换令牌污染浏览器 Cookie。
    if (refreshInFlight) await refreshInFlight.catch(() => false)
    await api.logout()
    clear()
    channel?.postMessage('changed')
  },
}
