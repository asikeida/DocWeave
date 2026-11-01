import { ApiError, requestJson } from './client'
export interface SampleSummary {
  id: string
  title: string
  version: string
  sha256: string
  readyForChat: false
  suggestedQuestions: string[]
}
export interface SamplePreview { sample: SampleSummary; format: 'plain-text'; content: string }
export function parseSummary(value: unknown): SampleSummary {
  if (!value || typeof value !== 'object') throw new ApiError('示例清单格式不正确', { code: 'INVALID_RESPONSE' })
  const item = value as Record<string, unknown>
  if (!['employee-handbook', 'travel-expenses'].includes(String(item.id)) || typeof item.title !== 'string'
    || item.version !== 'v1' || typeof item.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(item.sha256)
    || item.readyForChat !== false || !Array.isArray(item.suggestedQuestions) || !item.suggestedQuestions.every(q => typeof q === 'string')) {
    throw new ApiError('示例清单格式不正确', { code: 'INVALID_RESPONSE' })
  }
  return item as unknown as SampleSummary
}
export async function listSamples(token: string, signal?: AbortSignal): Promise<SampleSummary[]> {
  const result = await requestJson('/api/v1/personal-area/samples', { headers: { Authorization: `Bearer ${token}` }, cache: 'no-store', signal })
  if (!Array.isArray(result) || result.length !== 2) throw new ApiError('示例清单格式不正确', { code: 'INVALID_RESPONSE' })
  return result.map(parseSummary)
}
export async function previewSample(token: string, id: string, signal?: AbortSignal): Promise<SamplePreview> {
  if (!['employee-handbook', 'travel-expenses'].includes(id)) throw new ApiError('未知示例', { code: 'INVALID_SAMPLE' })
  const value = await requestJson(`/api/v1/personal-area/samples/${id}`, { headers: { Authorization: `Bearer ${token}` }, cache: 'no-store', signal })
  if (!value || typeof value !== 'object') throw new ApiError('示例正文格式不正确', { code: 'INVALID_RESPONSE' })
  const result = value as Record<string, unknown>
  const sample = parseSummary(result.sample)
  if (sample.id !== id || result.format !== 'plain-text' || typeof result.content !== 'string') throw new ApiError('示例正文格式不正确', { code: 'INVALID_RESPONSE' })
  return { sample, format: 'plain-text', content: result.content }
}
