import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ login: vi.fn(), refresh: vi.fn(), currentUser: vi.fn(), logout: vi.fn() }))
vi.mock('../api/auth', () => api)

const user = { id: 'test-id', username: 'alice', displayName: 'Alice' }
const tokens = { accessToken: 'test-access', expiresIn: 300, user }

beforeEach(() => {
  vi.resetModules()
  Object.values(api).forEach((mock) => mock.mockReset())
})

describe('登录状态转换', () => {
  it('同标签页并发恢复只执行一次刷新', async () => {
    let complete!: (value: typeof tokens) => void
    api.refresh.mockReturnValue(new Promise((resolve) => { complete = resolve }))
    const { session } = await import('./session')
    const one = session.restore()
    const two = session.restore()
    expect(api.refresh).toHaveBeenCalledTimes(1)
    complete(tokens)
    await expect(one).resolves.toBe(true)
    await expect(two).resolves.toBe(true)
    expect(session.user.value).toEqual(user)
  })

  it('退出成功清除内存身份，退出失败不能伪装成成功', async () => {
    const { ApiError } = await import('../api/client')
    api.login.mockResolvedValue(tokens)
    const { session } = await import('./session')
    await session.signIn('alice', 'test-password')
    api.logout.mockRejectedValueOnce(new ApiError('网络故障', { code: 'NETWORK_ERROR' }))
    await expect(session.signOut()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(session.user.value).toEqual(user)
    api.logout.mockResolvedValue(undefined)
    await session.signOut()
    expect(session.user.value).toBeNull()
  })

  it('后端撤销登录后清除身份，网络失败则保留状态供重试', async () => {
    const { ApiError } = await import('../api/client')
    api.login.mockResolvedValue(tokens)
    const { session } = await import('./session')
    await session.signIn('alice', 'test-password')
    api.currentUser.mockRejectedValueOnce(new ApiError('网络故障', { code: 'NETWORK_ERROR' }))
    await expect(session.verify()).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(session.user.value).toEqual(user)
    api.currentUser.mockRejectedValueOnce(new ApiError('已失效', { code: 'UNAUTHENTICATED', status: 401 }))
    await expect(session.verify()).resolves.toBe(false)
    expect(session.user.value).toBeNull()
  })

  it('旧刷新响应不能覆盖稍后完成的新登录', async () => {
    let complete!: (value: typeof tokens) => void
    api.refresh.mockReturnValue(new Promise((resolve) => { complete = resolve }))
    api.login.mockResolvedValue({ ...tokens, user: { ...user, username: 'bob' } })
    const { session } = await import('./session')
    const pending = session.restore()
    await session.signIn('bob', 'test-password')
    complete(tokens)
    await expect(pending).resolves.toBe(false)
    expect(session.user.value?.username).toBe('bob')
  })
})
