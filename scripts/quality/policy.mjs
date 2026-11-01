import lint from '@commitlint/lint';
import load from '@commitlint/load';
import { fileURLToPath } from 'node:url';

export async function validateMessage(message, { title = false } = {}) {
  const config = await load({}, { cwd: fileURLToPath(new URL('../../', import.meta.url)) });
  const rules = { ...config.rules };
  if (title) delete rules['issue-footer'];
  const result = await lint(message, rules, { parserOpts: config.parserPreset?.parserOpts, plugins: config.plugins, defaultIgnores: false });
  if (!result.valid) throw new Error(result.errors.map(item => item.message).join('；'));
}

// 只识别约定的独立行，避免从代码片段或随意的 #数字误判关联。
export function issueNumbers(text, keyword) {
  const withoutCode = text.replace(/\r\n?/g, '\n').replace(/```[\s\S]*?```/g, '').replace(/<!--[\s\S]*?-->/g, '');
  return [...new Set([...withoutCode.matchAll(new RegExp(`^${keyword}:? (#[1-9]\\d*(?:, #[1-9]\\d*)*)[ \\t]*$`, 'gm'))]
    .flatMap(match => [...match[1].matchAll(/#(\d+)/g)].map(item => Number(item[1]))))];
}

export async function validatePullRequest(pr, commits, getIssue) {
  await validateMessage(pr.title, { title: true });
  const closing = issueNumbers(pr.body || '', 'Closes');
  if (!closing.length) throw new Error('PR 正文需要独立一行 Closes #Issue编号');
  const refs = new Set(closing);
  for (const commit of commits) {
    await validateMessage(commit.commit.message);
    const linked = issueNumbers(commit.commit.message, 'Refs');
    if (!linked.length) throw new Error('提交需要独立的 Refs: #Issue编号');
    linked.forEach(number => refs.add(number));
  }
  if (!commits.length) throw new Error('无法取得 PR 提交记录');
  for (const number of refs) {
    const issue = await getIssue(number);
    if (issue.pull_request || issue.number !== number) throw new Error(`#${number} 不是本仓库的 Issue`);
  }
}

export async function githubJson(path, { token, fetcher = fetch } = {}) {
  const response = await fetcher(`https://api.github.com${path}`, {
    headers: { Accept: 'application/vnd.github+json', Authorization: `Bearer ${token}`, 'X-GitHub-Api-Version': '2022-11-28' },
    signal: AbortSignal.timeout(15000), redirect: 'error',
  });
  if (!response.ok) throw new Error(`GitHub API 请求失败（HTTP ${response.status}），门禁未通过`);
  return response.json();
}

export async function getCommits(repository, number, count, getJson) {
  if (!Number.isInteger(count) || count < 1 || count > 250) throw new Error('PR 必须包含 1～250 个提交，请拆分过大的 PR');
  const commits = [];
  for (let page = 1; commits.length < count; page++) {
    const batch = await getJson(`/repos/${repository}/pulls/${number}/commits?per_page=100&page=${page}`);
    if (!Array.isArray(batch) || !batch.length) throw new Error('PR 提交分页不完整');
    commits.push(...batch);
  }
  if (commits.length !== count) throw new Error('PR 提交数量变化，请重新运行检查');
  return commits;
}
