import test from 'node:test';
import assert from 'node:assert/strict';
import { validateMessage, validatePullRequest, issueNumbers, githubJson, getCommits } from './policy.mjs';

const message = 'infra(ci): 添加提交信息门禁\n\nRefs: #14';
const pr = { title: 'infra(ci): 添加提交信息门禁', body: 'Closes #14' };
const commits = [{ commit: { message } }];
test('中文提交、infra 类型与独立 PR 标题通过', async () => {
  await validateMessage(message);
  await validatePullRequest(pr, commits, async number => ({ number }));
});
for (const [name, value] of Object.entries({
  '无Issue': 'feat(auth): 新增登录',
  '无中文': 'feat(auth): add login\n\nRefs: #14',
  '错误类型': 'hello(auth): 新增登录\n\nRefs: #14',
  '中文范围': 'feat(登录): 新增登录\n\nRefs: #14',
  'fixup绕过': 'fixup! feat(auth): 新增登录\n\nRefs: #14',
  '零编号': 'feat(auth): 新增登录\n\nRefs: #0',
})) test(`拒绝${name}`, () => assert.rejects(validateMessage(value)));
test('PR 必须有关闭关联且编号不能是 PR', async () => {
  await assert.rejects(validatePullRequest({ ...pr, body: '' }, commits, async number => ({ number })));
  await assert.rejects(validatePullRequest(pr, commits, async number => ({ number, pull_request: {} })));
});
test('所有提交的引用均核验，不仅检查 PR 的 Issue', async () => {
  const seen = [];
  await validatePullRequest(pr, [{ commit: { message: message.replace('#14', '#13') } }], async number => { seen.push(number); return { number }; });
  assert.deepEqual(seen, [14, 13]);
});
test('代码块、HTML 注释和普通文字不算关联', () => {
  assert.deepEqual(issueNumbers('```\nCloses #14\n```\n<!--\nCloses #15\n-->\n请看 #16', 'Closes'), []);
  assert.deepEqual(issueNumbers('Closes #14, #15', 'Closes'), [14, 15]);
  assert.deepEqual(issueNumbers('正文\r\n\r\nRefs: #14\r\n', 'Refs'), [14]);
});
test('API 错误和网络异常必须失败，不得跳过检查', async () => {
  await assert.rejects(githubJson('/test', { fetcher: async () => ({ ok: false, status: 404 }) }));
  await assert.rejects(githubJson('/test', { fetcher: async () => { throw new Error('network'); } }));
});
test('分页完整取得101个提交，拒绝空页和超大PR', async () => {
  const calls = [];
  const result = await getCommits('a/b', 14, 101, async path => { calls.push(path); return Array(path.endsWith('page=1') ? 100 : 1).fill(commits[0]); });
  assert.equal(result.length, 101);
  assert.equal(calls.length, 2);
  await assert.rejects(getCommits('a/b', 14, 101, async () => []));
  await assert.rejects(getCommits('a/b', 14, 251, async () => []));
});
