import { ApiError, requestJson } from './client'
export interface KnowledgeBase { id: string; name: string; workspaceId: string; documentCount: number; state: 'NOT_INDEXED' }
export interface DocumentSummary { id: string; knowledgeBaseId: string; title: string; sampleKey: string; seedVersion: string; sha256: string; indexState: 'NOT_INDEXED' }
export interface DocumentDetail { document: DocumentSummary; content: string }
const record = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object'
function invalid(): never { throw new ApiError('知识库响应格式不正确', { code: 'INVALID_RESPONSE' }) }
export function parseKnowledgeBase(v: unknown): KnowledgeBase {
  if (!record(v) || typeof v.id !== 'string' || !v.id || typeof v.name !== 'string' || typeof v.workspaceId !== 'string'
    || !Number.isInteger(v.documentCount) || Number(v.documentCount) < 0 || v.state !== 'NOT_INDEXED') return invalid()
  return { id: v.id, name: v.name, workspaceId: v.workspaceId, documentCount: Number(v.documentCount), state: 'NOT_INDEXED' }
}
export function parseDocument(v: unknown): DocumentSummary {
  if (!record(v) || !['id', 'knowledgeBaseId', 'title', 'sampleKey', 'seedVersion', 'sha256'].every(k => typeof v[k] === 'string' && !!v[k])
    || !/^[a-f0-9]{64}$/.test(String(v.sha256)) || v.indexState !== 'NOT_INDEXED') return invalid()
  return v as unknown as DocumentSummary
}
async function request(token: string, path: string, initialize: boolean, signal?: AbortSignal): Promise<unknown> {
  const headers: Record<string, string> = { Authorization: `Bearer ${token}` }
  if (initialize) {
    const csrf = await requestJson('/api/v1/auth/csrf', { cache: 'no-store', credentials: 'same-origin', signal })
    if (!record(csrf) || csrf.headerName !== 'X-XSRF-TOKEN' || typeof csrf.token !== 'string') return invalid()
    headers[csrf.headerName] = csrf.token
  }
  return requestJson(path, { method: initialize ? 'POST' : 'GET', headers, credentials: 'same-origin', cache: 'no-store', signal })
}
export async function knowledgeBases(token: string, initialize: boolean, signal?: AbortSignal): Promise<KnowledgeBase[]> {
  const result = await request(token, initialize ? '/api/v1/personal-area/sample-import' : '/api/v1/knowledge-bases', initialize, signal)
  if (!Array.isArray(result)) return invalid()
  return result.map(parseKnowledgeBase)
}
export async function documents(token: string, kb: string, signal?: AbortSignal): Promise<DocumentSummary[]> {
  const result = await request(token, `/api/v1/knowledge-bases/${encodeURIComponent(kb)}/documents`, false, signal)
  if (!Array.isArray(result)) return invalid()
  return result.map(parseDocument)
}
export async function document(token: string, id: string, signal?: AbortSignal): Promise<DocumentDetail> {
  const result = await request(token, `/api/v1/documents/${encodeURIComponent(id)}`, false, signal)
  if (!record(result) || typeof result.content !== 'string') return invalid()
  const doc = parseDocument(result.document)
  if (doc.id !== id) return invalid()
  return { document: doc, content: result.content }
}
