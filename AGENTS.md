# AGENTS.md

本文是 DocWeave 仓库内所有 AI Agent 和开发者的首要协作约定。

## 1. 项目定位

DocWeave 是一个带引用证据的智能文档知识库，也是可公开展示的求职作品。每次改动都应当能说明：解决了什么问题、怎样验证、是否可以安全合并。

后端使用 Java 21、Spring Boot 4.1.1 和 Maven；前端位于 `frontend/`，使用 Vue 3、TypeScript 和 Vite。项目采用单仓库、前后端独立构建、后端模块化单体的方式演进。

## 2. 开始任务前必读

按顺序阅读：

1. 本文件 `AGENTS.md`
2. `README.md`
3. `docs/README.md`
4. 与当前任务有关的 `docs/开发规划/`、`docs/架构设计/` 文档
5. `.agents/rules/README.md` 以及与任务类型对应的规则文件
6. `pom.xml`、`src/main/resources/application*.yaml` 和本次直接相关的源码

按任务类型补充阅读：

| 任务 | 必读规则 |
| --- | --- |
| 所有改动 | `01-通用工作规则.md`、`04-Git与质量门禁.md` |
| 后端、数据库、异步任务 | `02-后端与数据规范.md` |
| 接口、异常、日志、安全 | `03-接口日志与安全规范.md` |
| Java 命名、注释、公开 API | `05-注释与命名规范.md` |
| 代码审查或准备 Pull Request | `06-代码审查规范.md` |
| 前端 | `07-前端开发规范.md` |

## 3. 目录与包结构

- Spring Boot 后端保留在仓库根目录，避免无意义迁移 Maven 初始化结构。
- Vue 前端位于 `frontend/`。
- 可长期维护、适合公开审查的项目文档放在 `docs/` 并提交 Git。
- AI 的行为规则放在 `.agents/rules/` 并提交 Git；根目录 `AGENTS.md` 是跨 Agent 的统一入口。
- AI 草稿、临时排查记录和个人笔记放在 `.local/`，该目录不提交。

后端按业务模块组织，而不是按全局 Controller、Service、Entity 目录分散代码：

```text
me.asikeida.docweave/
├─ common/       # 真正跨模块复用的类型
├─ config/       # 全局框架和基础设施配置
├─ auth/
├─ user/
├─ workspace/
├─ knowledgebase/
├─ document/
├─ ingestion/
├─ retrieval/
├─ chat/
└─ admin/
```

模块内部根据实际复杂度增加 `controller`、`service`、`domain`、`repository`、`dto`。不要预建空目录；也不要把本应归属某个模块的代码塞进 `common` 或 `utils`。

## 4. 不可违反的规则

- 不提交密码、令牌、私钥、生产主机信息、数据库真实连接信息或其他敏感数据。
- 使用环境变量或未追踪的本地文件保存私密配置；示例文件只能使用占位值。
- 未经用户在当前任务明确要求，不得提交、推送、合并、强推或改写 Git 历史。
- 不因无关任务重排、格式化或重构大量代码。
- 不创建无用途的公共类、配置类、空包或“以后可能会用”的中间件配置。
- 修改接口、鉴权、数据或浏览器行为时，必须做与风险匹配的验证。
- 日志不得记录密码、令牌、完整授权头、私密文件内容或个人敏感数据。

## 5. 文档规则

- `docs/开发规划/`：范围、阶段与验收目标。
- `docs/架构设计/`：架构、数据模型、RAG 链路。
- `docs/接口说明/`：接口约定、鉴权和集成说明。
- `docs/部署运维/`：本地启动、部署、监控与故障处理。
- `docs/决策记录/`：重要技术选择及其理由。
- `.agents/rules/`：所有 AI Agent 必须遵守的开发规范，不属于正式项目文档目录。

正式文档必须描述已经确认的事实或明确标注为规划。原始对话、临时调试输出和未整理草稿留在 `.local/`。

## 6. Git 工作方式

- `main` 必须始终可构建、可部署。
- 每个明确任务从最新 `main` 创建短期分支。
- 提交应表达一个完整、可审核的意图，例如 `infra: add local mysql`、`feat(auth): add password login`。
- 支撑同一功能所必需的配置、迁移脚本、测试和文档可以在同一个提交中。
- 可独立验证的基础能力可以单独提交，例如 `refactor: add common API error handling`。
- Issue 用于记录一个可交付问题，Pull Request 用于审查一个已完成的分支；关联时在 PR 描述中写 `Closes #编号`。

## 7. 常用验证

根据改动选择最小且有意义的验证，不为了形式重复全量构建。

```text
./mvnw test
./mvnw package
```

新增或修改 API 时，还要验证参数校验、错误响应、权限边界和 OpenAPI 文档。影响前端页面行为的改动要在真实浏览器中验证。

前端在 `frontend/` 中使用 Node 24 和 packageManager 指定的 pnpm，按顺序执行：

```text
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```
