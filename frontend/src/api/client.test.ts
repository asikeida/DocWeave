import { afterEach, describe, expect, it, vi } from 'vitest'
import { requestJson } from './client'

const fetchMock = vi.fn<typeof fetch>()

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
  fetchMock.mockReset()
})

function mockResponse(response: Response) {
  vi.stubGlobal('fetch', fetchMock.mockResolvedValue(response))
}

function mockPendingRequest() {
  vi.stubGlobal('fetch', fetchMock.mockImplementation((_input, init) => new Promise((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  })))
}

describe('JSON 请求契约', () => {
  it('读取成功响应，并清理超时计时器', async () => {
    vi.useFakeTimers()
    mockResponse(Response.json({ status: 'UP' }))
    await expect(requestJson('/actuator/health')).resolves.toEqual({ status: 'UP' })
    expect(vi.getTimerCount()).toBe(0)
  })

  it('保留后端错误码、HTTP 状态和响应头中的请求编号', async () => {
    mockResponse(Response.json({ code: 'INTERNAL_ERROR', message: '服务暂时不可用', requestId: 'body-id' }, {
      status: 500, headers: { 'X-Request-Id': 'header-id' },
    }))
    await expect(requestJson('/api/v1/example')).rejects.toMatchObject({
      name: 'ApiError', code: 'INTERNAL_ERROR', status: 500, requestId: 'header-id', message: '服务暂时不可用',
    })
  })

  it('没有响应头时，使用错误响应体中的请求编号', async () => {
    mockResponse(Response.json({ code: 'VALIDATION_ERROR', message: '请求参数不正确', requestId: 'body-id' }, { status: 400 }))
    await expect(requestJson('/api/v1/example')).rejects.toMatchObject({ requestId: 'body-id', status: 400 })
  })

  it('代理返回 HTML 错误页时，不将内部内容暴露给用户', async () => {
    mockResponse(new Response('<html>internal proxy details</html>', { status: 502 }))
    await expect(requestJson('/actuator/health')).rejects.toMatchObject({
      code: 'HTTP_ERROR', status: 502, message: '服务暂时不可用，请稍后重试',
    })
  })

  it('成功状态但返回非 JSON 时，明确报告协议错误', async () => {
    mockResponse(new Response('<html>unexpected page</html>'))
    await expect(requestJson('/actuator/health')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('网络失败时，提供可操作提示，不透出原始异常', async () => {
    vi.stubGlobal('fetch', fetchMock.mockRejectedValue(new TypeError('internal network details')))
    await expect(requestJson('/actuator/health')).rejects.toMatchObject({
      code: 'NETWORK_ERROR', message: '无法连接服务，请检查网络或确认后端已启动',
    })
  })

  it('超过约定时间后终止请求，并返回明确的超时错误', async () => {
    vi.useFakeTimers()
    mockPendingRequest()
    const assertion = expect(requestJson('/actuator/health', { timeoutMs: 50 })).rejects.toMatchObject({ code: 'TIMEOUT' })
    await vi.advanceTimersByTimeAsync(50)
    await assertion
  })

  it('页面主动取消请求时，区别于网络故障和超时', async () => {
    mockPendingRequest()
    const controller = new AbortController()
    const assertion = expect(requestJson('/actuator/health', { signal: controller.signal })).rejects.toMatchObject({ name: 'ApiError', code: 'CANCELLED' })
    controller.abort()
    await assertion
    expect(fetchMock.mock.calls[0]?.[1]?.signal?.aborted).toBe(true)
  })
})
