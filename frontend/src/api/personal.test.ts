import { describe, expect, it } from 'vitest'
import { parsePersonalArea } from './auth'

describe('个人区域响应契约', () => {
  it('保留待接入状态，拒绝伪造READY和缺少归属的响应', () => {
    const area = { organizationId: 'tenant', workspaceId: 'space', name: '我的资料', sampleState: 'WAITING_FEATURE' }
    expect(parsePersonalArea(area)).toEqual(area)
    expect(() => parsePersonalArea({ ...area, sampleState: 'READY' })).toThrow()
    expect(() => parsePersonalArea({ ...area, organizationId: '' })).toThrow()
  })
})
