import { afterEach, describe, expect, it, vi } from 'vitest'
import { currentUser, login, refresh } from './auth'

const csrf = { headerName: 'X-XSRF-TOKEN', token: 'test-csrf' }
const user = { id: 'test-id', username: 'alice', displayName: 'Alice' }

afterEach(() => vi.unstubAllGlobals())

describe('认证接口契约', () => {
  it('登录先取得 CSRF，并通过同源 Cookie 与请求头提交；令牌不进入 URL', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(csrf))
      .mockResolvedValueOnce(Response.json({ accessToken: 'test-access', tokenType: 'Bearer', expiresIn: 300, user }))
    vi.stubGlobal('fetch', fetch)
    await expect(login('alice', 'test-password')).resolves.toMatchObject({ user })
    expect(fetch.mock.calls[1]?.[0]).toBe('/api/v1/auth/login')
    const options = fetch.mock.calls[1]?.[1]
    expect(options?.credentials).toBe('same-origin')
    expect(new Headers(options?.headers).get('X-XSRF-TOKEN')).toBe('test-csrf')
    expect(JSON.parse(String(options?.body))).toEqual({ username: 'alice', password: 'test-password' })
  })

  it('刷新不读取或提交 JavaScript 中的刷新令牌', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>()
      .mockResolvedValueOnce(Response.json(csrf))
      .mockResolvedValueOnce(Response.json({ accessToken: 'test-access', tokenType: 'Bearer', expiresIn: 300, user }))
    vi.stubGlobal('fetch', fetch)
    await refresh()
    expect(fetch.mock.calls[1]?.[1]?.body).toBeUndefined()
    expect(fetch.mock.calls[1]?.[1]?.credentials).toBe('same-origin')
  })

  it('拒绝不可信响应与异常 CSRF 请求头', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ headerName: 'Authorization', token: 'unexpected' }))
    vi.stubGlobal('fetch', fetch)
    await expect(refresh()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('当前身份请求携带 Bearer 并拒绝缺字段账号', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(Response.json({ id: 'only-id' }))
    vi.stubGlobal('fetch', fetch)
    await expect(currentUser('test-access')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    expect(new Headers(fetch.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe('Bearer test-access')
  })
})
