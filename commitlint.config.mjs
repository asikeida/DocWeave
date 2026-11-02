export default {
  extends: ['@commitlint/config-conventional'],
  defaultIgnores: false,
  plugins: [{ rules: {
    'chinese-subject': ({ subject }) => [Boolean(subject && /\p{Script=Han}/u.test(subject)), '摘要必须包含中文'],
    'issue-footer': ({ raw }) => [/^Refs: #[1-9]\d*(?:, #[1-9]\d*)*\s*$/m.test(raw), '正文末尾添加 Refs: #Issue编号'],
    'module-scope': ({ scope }) => [!scope || /^[a-z][a-z0-9-]*$/.test(scope), '范围必须为小写英文模块名'],
  } }],
  rules: {
    'type-enum': [2, 'always', ['feat', 'fix', 'docs', 'style', 'refactor', 'perf', 'test', 'build', 'ci', 'chore', 'revert', 'infra']],
    'subject-case': [0],
    'body-max-line-length': [0],
    'footer-max-line-length': [0],
    'chinese-subject': [2, 'always'],
    'module-scope': [2, 'always'],
    'issue-footer': [2, 'always'],
  },
};
