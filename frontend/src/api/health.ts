import { ApiError, requestJson } from './client'

export async function checkHealth(signal: AbortSignal): Promise<void> {
  const result = await requestJson('/actuator/health', { signal })
  if (typeof result !== 'object' || result === null || !('status' in result) || result.status !== 'UP') {
    throw new ApiError('服务尚未就绪，请稍后重试', { code: 'SERVICE_NOT_READY' })
  }
}
