# Structify 文档入口

整理日期：2026-11-01。日常只读下列活文档；历史材料在 `archive/`，正式设计/计划在 `superpowers/`。

## AI / Agent 权威规范

**所有 AI 开发必须遵循**：[AI开发规范.md](AI开发规范.md)（副标题：规矩要立住，代码写起来才不烧心）
仓库入口：[AGENTS.md](../AGENTS.md)；Cursor 每会话自动加载 `.cursor/rules/structify-ai-development.mdc`。
Spring 强制叶子包与命名细则：[architecture/backend-standards.md](architecture/backend-standards.md)。

## 新人

[项目概览](项目概览.md) → [本地开发](本地开发.md) → [测试与验证](测试与验证.md)

## 维护者

[系统架构](系统架构.md) → [核心模块与修改入口](模块/核心模块与修改入口.md) → [部署与运维](部署与运维.md)

AI 编码代理：先读 [AI开发规范](AI开发规范.md) 与 [AGENTS](../AGENTS.md)，再读对应模块和源码；不要把历史报告视为当前验收结果。

## 按功能

| 功能 | 活文档 |
|------|--------|
| AI 开发规则 | [AI开发规范.md](AI开发规范.md) |
| 学习室 | [architecture/studyroom.md](architecture/studyroom.md)、[后端 ADR](adr/backend/阅读指南.md) |
| 产品方向 | [产品方向.md](产品方向.md) |
| 现在先做什么 | [下一步工作.md](下一步工作.md) |
| 内容导入 | [guides/content-import-guide.md](guides/content-import-guide.md) |
| Node/Spring 兼容 | [architecture/node-spring-compat.md](architecture/node-spring-compat.md) |
| Spring 工程规范（强制分包与命名） | [architecture/backend-standards.md](architecture/backend-standards.md) |
| Spring 重复逻辑审计 | [architecture/backend-duplication-audit.md](architecture/backend-duplication-audit.md) |
| 部署与沙箱 | [部署与运维.md](部署与运维.md) |
| 证据边界 | [文档审计与待确认事项.md](文档审计与待确认事项.md) |

## 其他目录

- `adr/backend/`：后端架构决策，不并入指南。
- `superpowers/`：Superpowers 生成的正式设计与实施计划；不是新人主入口。
- `archive/`：历史报告、旧验收、过程材料、handoff、长篇运维手册。追溯映射见 [archive/document-moves.json](archive/document-moves.json)。

仓库根 README、PRODUCT、AGENTS 以及各模块 README 保留原位。`contracts/`、`knowledge/`、课程素材旁说明不混入工程文档树。

OpenAPI、配置、迁移、脚本均继续作为各自唯一事实来源。
