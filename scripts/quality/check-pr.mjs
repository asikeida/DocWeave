import { readFile } from 'node:fs/promises';
import { githubJson, getCommits, validatePullRequest } from './policy.mjs';

try {
  const event = JSON.parse(await readFile(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const repository = process.env.GITHUB_REPOSITORY;
  const number = event.pull_request?.number;
  if (!/^[\w.-]+\/[\w.-]+$/.test(repository || '') || !Number.isSafeInteger(number) || !process.env.GITHUB_TOKEN) {
    throw new Error('需要 PR 事件、仓库名和只读 GitHub Token');
  }
  const getJson = path => githubJson(path, { token: process.env.GITHUB_TOKEN });
  const path = `/repos/${repository}/pulls/${number}`;
  // 回读当前正文，避免 edited 事件只验证过期的事件快照。
  const pr = await getJson(path);
  if (pr.head.sha !== event.pull_request.head.sha) throw new Error('PR 已有新提交，等待最新检查');
  const commits = await getCommits(repository, number, pr.commits, getJson);
  await validatePullRequest(pr, commits, issue => getJson(`/repos/${repository}/issues/${issue}`));
  const latest = await getJson(path);
  if (latest.updated_at !== pr.updated_at || latest.head.sha !== pr.head.sha) throw new Error('检查期间 PR 已变化，请重新运行');
  console.log(`PR #${number}：提交格式、中文标题及真实 Issue 关联检查通过`);
} catch (error) {
  console.error(`协作门禁失败：${error.message}`);
  process.exitCode = 1;
}
