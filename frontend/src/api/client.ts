interface ApiErrorDetails {
  code: string
  status?: number
  requestId?: string
}

export class ApiError extends Error {
  readonly code: string
  readonly status?: number
  readonly requestId?: string

  constructor(message: string, details: ApiErrorDetails) {
    super(message)
    this.name = 'ApiError'
    this.code = details.code
    this.status = details.status
    this.requestId = details.requestId
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/** 只向调用方返回明确的错误信息，不把代理 HTML 或原始网络异常展示到页面。 */
export async function requestJson(
  path: string,
  options: RequestInit & { timeoutMs?: number } = {},
): Promise<unknown> {
  const { timeoutMs = 10_000, signal, ...init } = options
  const controller = new AbortController()
  let timedOut = false
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
  let requestId: string | undefined

  try {
    const headers = new Headers(init.headers)
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    const response = await fetch(path, { ...init, headers, signal: controller.signal })
    requestId = response.headers.get('X-Request-Id') || undefined
    const body: unknown = await response.json().catch((error: unknown) => {
      if (controller.signal.aborted) throw error
      return undefined
    })
    if (!response.ok) {
      const details = isRecord(body) ? body : {}
      throw new ApiError(
        typeof details.message === 'string' ? details.message : '服务暂时不可用，请稍后重试',
        {
          code: typeof details.code === 'string' ? details.code : 'HTTP_ERROR',
          status: response.status,
          requestId: requestId || (typeof details.requestId === 'string' ? details.requestId : undefined),
        },
      )
    }
    if (body === undefined) {
      throw new ApiError('服务返回了无法识别的数据，请稍后重试', { code: 'INVALID_RESPONSE', requestId })
    }
    return body
  } catch (error) {
    if (timedOut) throw new ApiError('连接超时，请检查服务后重试', { code: 'TIMEOUT', requestId })
    if (signal?.aborted) throw new ApiError('请求已取消', { code: 'CANCELLED', requestId })
    if (error instanceof ApiError) throw error
    throw new ApiError('无法连接服务，请检查网络或确认后端已启动', { code: 'NETWORK_ERROR', requestId })
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}
