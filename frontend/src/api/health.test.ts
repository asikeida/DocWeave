import { afterEach, expect, it, vi } from 'vitest'
import { checkHealth } from './health'

afterEach(() => vi.unstubAllGlobals())

it('只有健康响应为 UP 时，才认为服务已就绪', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ status: 'UP' })))
  await expect(checkHealth(new AbortController().signal)).resolves.toBeUndefined()
})

it.each([{ status: 'DOWN' }, {}, null])('不把异常健康响应 %j 显示为连接正常', async (body) => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json(body)))
  await expect(checkHealth(new AbortController().signal)).rejects.toMatchObject({ code: 'SERVICE_NOT_READY' })
})
