# AI 开发规范索引

这些规则是 DocWeave 的 AI 协作规范源。它们使用中文编写，适用于 Codex、ZCode、OpenCode 以及其他能够读取仓库 Markdown 的 Agent。

根目录 `AGENTS.md` 是所有 Agent 的入口。开始工作时先读 `AGENTS.md`，再根据任务类型阅读下列文件。

| 文件 | 使用时机 |
| --- | --- |
| `01-通用工作规则.md` | 所有任务 |
| `02-后端与数据规范.md` | Java、Spring Boot、JPA、MySQL、Flyway、异步处理 |
| `03-接口日志与安全规范.md` | Controller、API、异常、日志、认证与配置 |
| `04-Git与质量门禁.md` | 分支、提交、测试、Issue、Pull Request 和发布 |
| `05-注释与命名规范.md` | Java 命名、Javadoc、行内注释和 TODO |
| `06-代码审查规范.md` | 审查代码或准备 Pull Request |

规则的目的不是限制正常开发，而是让每次改动具有清晰边界、可验证结果和可追溯记录。新规则应放入最贴近主题的文件；如果规则会影响所有任务，再同步更新根目录 `AGENTS.md`。
