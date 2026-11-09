import { expect, it } from 'vitest'
import { parseSummary } from './samples'
it('只接受白名单只读未索引模板元数据', () => {
  const item = { id: 'employee-handbook', title: '手册', version: 'v1', sha256: 'a'.repeat(64), readyForChat: false, suggestedQuestions: ['问题'] }
  expect(parseSummary(item)).toEqual(item)
  expect(() => parseSummary({ ...item, readyForChat: true })).toThrow()
  expect(() => parseSummary({ ...item, id: '../application.yaml' })).toThrow()
  expect(() => parseSummary({ ...item, sha256: '' })).toThrow()
})
