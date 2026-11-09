import { ApiError, requestJson } from './client'

export interface CurrentUser {
  id: string
  username: string
  displayName: string
}

export interface LoginTokens {
  accessToken: string
  expiresIn: number
  user: CurrentUser
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function parseUser(value: unknown): CurrentUser {
  if (!isRecord(value) || typeof value.id !== 'string' || !value.id
    || typeof value.username !== 'string' || !value.username
    || typeof value.displayName !== 'string' || !value.displayName) {
    throw new ApiError('账号信息格式不正确，请重新登录', { code: 'INVALID_RESPONSE' })
  }
  return { id: value.id, username: value.username, displayName: value.displayName }
}

function parseTokens(value: unknown): LoginTokens {
  if (!isRecord(value) || typeof value.accessToken !== 'string' || !value.accessToken
    || value.tokenType !== 'Bearer' || typeof value.expiresIn !== 'number'
    || !Number.isFinite(value.expiresIn) || value.expiresIn <= 0) {
    throw new ApiError('登录响应格式不正确，请重试', { code: 'INVALID_RESPONSE' })
  }
  return { accessToken: value.accessToken, expiresIn: value.expiresIn, user: parseUser(value.user) }
}

/** CSRF 令牌与访问令牌只在当前内存使用；浏览器自动管理 HttpOnly 刷新 Cookie。 */
async function executeMutation(path: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
  const csrf = await requestJson('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store', signal })
  if (!isRecord(csrf) || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string' || !csrf.token) {
    throw new ApiError('安全校验初始化失败，请重试', { code: 'INVALID_RESPONSE' })
  }
  return requestJson(path, {
    method: 'POST', credentials: 'same-origin', cache: 'no-store', signal,
    headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

async function authMutation(path: string, body?: unknown, signal?: AbortSignal): Promise<unknown> {
  // 刷新 Cookie 为同源标签页共享，轮换必须跨标签页串行，避免正常并发被识别为重放。
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return navigator.locks.request('docweave-auth-mutation', () => executeMutation(path, body, signal))
  }
  return executeMutation(path, body, signal)
}

export async function login(username: string, password: string, signal?: AbortSignal): Promise<LoginTokens> {
  return parseTokens(await authMutation('/api/v1/auth/login', { username, password }, signal))
}

export async function refresh(): Promise<LoginTokens> {
  return parseTokens(await authMutation('/api/v1/auth/refresh'))
}

export async function currentUser(accessToken: string, signal?: AbortSignal): Promise<CurrentUser> {
  return parseUser(await requestJson('/api/v1/auth/me', {
    headers: { Authorization: `Bearer ${accessToken}` }, cache: 'no-store', signal,
  }))
}

export async function logout(): Promise<void> {
  await authMutation('/api/v1/auth/logout')
}
