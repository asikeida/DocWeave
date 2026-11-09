import { expect, it } from 'vitest'
import { parseKnowledgeBase, parseDocument } from './knowledge'
it('拒绝把未索引资源伪装为可问答，校验归属和摘要', () => {
  const kb = { id: 'kb', name: '示例', workspaceId: 'workspace', documentCount: 2, state: 'NOT_INDEXED' }
  expect(parseKnowledgeBase(kb)).toEqual(kb)
  expect(() => parseKnowledgeBase({ ...kb, state: 'READY' })).toThrow()
  expect(() => parseKnowledgeBase({ ...kb, documentCount: -1 })).toThrow()
  expect(() => parseDocument({ id: 'doc', indexState: 'READY' })).toThrow()
})
