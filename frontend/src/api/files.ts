import { ApiError, requestJson } from './client'
import { parseDocument } from './knowledge'

const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object'
function apiError(value: unknown, status?: number, requestId?: string) {
  return new ApiError(record(value) && typeof value.message === 'string' ? value.message : '文件操作失败，请稍后重试', {
    code: record(value) && typeof value.code === 'string' ? value.code : 'FILE_REQUEST_FAILED', status, requestId,
  })
}
async function csrf(signal?: AbortSignal) {
  const result = await requestJson('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store', signal })
  if (!record(result) || result.headerName !== 'X-XSRF-TOKEN' || typeof result.token !== 'string') throw apiError(null)
  return { [result.headerName]: result.token }
}
/** XHR 提供真实传输进度；请求中断不表示服务端一定未保存，重试应保留原幂等键。 */
export async function upload(token: string, kb: string, file: File, key: string, progress: (value: number) => void, signal?: AbortSignal) {
  const protection = await csrf(signal)
  const result: unknown = await new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    const abort = () => xhr.abort()
    const clean = () => signal?.removeEventListener('abort', abort)
    xhr.open('POST', `/api/v1/knowledge-bases/${encodeURIComponent(kb)}/documents`)
    xhr.timeout = 120000
    xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    xhr.setRequestHeader('Idempotency-Key', key)
    for (const [name, value] of Object.entries(protection)) xhr.setRequestHeader(name, value)
    xhr.upload.onprogress = event => { if (event.lengthComputable) progress(Math.round(event.loaded / event.total * 100)) }
    xhr.onload = () => {
      clean()
      let body: unknown
      try { body = JSON.parse(xhr.responseText) } catch { reject(apiError(null, xhr.status)); return }
      if (xhr.status < 200 || xhr.status >= 300) reject(apiError(body, xhr.status, xhr.getResponseHeader('X-Request-Id') ?? undefined))
      else resolve(body)
    }
    xhr.onerror = () => { clean(); reject(apiError(null)) }
    xhr.ontimeout = () => { clean(); reject(new ApiError('上传结果暂未确定，可用同一文件重试', { code: 'UPLOAD_TIMEOUT' })) }
    xhr.onabort = () => { clean(); reject(new ApiError('已停止等待；服务端可能已完成保存，可重新加载确认', { code: 'CANCELLED' })) }
    if (signal?.aborted) { reject(new ApiError('请求已取消', { code: 'CANCELLED' })); return }
    signal?.addEventListener('abort', abort, { once: true })
    const form = new FormData(); form.append('file', file); xhr.send(form)
  })
  if (!record(result) || typeof result.replayed !== 'boolean') throw apiError(null)
  return { document: parseDocument(result.document), replayed: result.replayed }
}
export async function download(token: string, id: string, signal?: AbortSignal) {
  let response: Response
  try { response = await fetch(`/api/v1/documents/${encodeURIComponent(id)}/download`, { headers: { Authorization: `Bearer ${token}` }, credentials: 'same-origin', cache: 'no-store', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(30000)]) : AbortSignal.timeout(30000) }) }
  catch { throw new ApiError('下载中断或超时，请重试', { code: signal?.aborted ? 'CANCELLED' : 'DOWNLOAD_FAILED' }) }
  if (!response.ok) throw apiError(await response.json().catch(() => null), response.status, response.headers.get('X-Request-Id') ?? undefined)
  try { return await response.blob() }
  catch { throw new ApiError('下载内容未完整接收，请重试', { code: 'DOWNLOAD_FAILED', requestId: response.headers.get('X-Request-Id') ?? undefined }) }
}
export async function remove(token: string, id: string, signal?: AbortSignal) {
  const headers = { Authorization: `Bearer ${token}`, ...await csrf(signal) }
  let response: Response
  try { response = await fetch(`/api/v1/documents/${encodeURIComponent(id)}`, { method: 'DELETE', headers, credentials: 'same-origin', signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(30000)]) : AbortSignal.timeout(30000) }) }
  catch { throw new ApiError('删除结果暂未确定，请重新加载确认', { code: signal?.aborted ? 'CANCELLED' : 'DELETE_TIMEOUT' }) }
  if (!response.ok) throw apiError(await response.json().catch(() => null), response.status, response.headers.get('X-Request-Id') ?? undefined)
}
