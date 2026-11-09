import { describe, expect, it, vi, afterEach } from 'vitest'
import { parseDocument } from './knowledge'
import { download, remove } from './files'
afterEach(() => vi.unstubAllGlobals())
const uploaded = { id: 'doc', knowledgeBaseId: 'kb', title: '资料.txt', sampleKey: null, seedVersion: null, sha256: 'a'.repeat(64), indexState: 'NOT_INDEXED', source: 'UPLOAD', byteSize: 12, mediaType: 'text/plain', createdAt: '2026-11-09T00:00:00Z' }
describe('uploaded document contract', () => {
  it('accepts explicit uploaded metadata without inventing sample identity', () => { expect(parseDocument(uploaded).source).toBe('UPLOAD'); expect(parseDocument(uploaded).sampleKey).toBeNull() })
  it('rejects missing size and false sample identity', () => {
    expect(() => parseDocument({ ...uploaded, byteSize: -1 })).toThrow()
    expect(() => parseDocument({ ...uploaded, sampleKey: 'fake' })).toThrow()
  })
  it('propagates protected download rejection with request id', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND', message: '不可访问' }), { status: 404, headers: { 'X-Request-Id': 'req-file' } })))
    await expect(download('token', 'doc')).rejects.toMatchObject({ code: 'RESOURCE_NOT_FOUND', requestId: 'req-file' })
  })
  it('sends csrf for deletion and accepts no-content response', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-XSRF-TOKEN', token: 'csrf-token' }), { headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetcher)
    await remove('token', 'doc')
    expect(fetcher.mock.calls[1]?.[1]).toMatchObject({ method: 'DELETE', headers: { Authorization: 'Bearer token', 'X-XSRF-TOKEN': 'csrf-token' } })
  })
})
