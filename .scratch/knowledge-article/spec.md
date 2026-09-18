# 知识文章模块

## 背景与范围

补齐管理端/用户端知识库全链路：管理端文章 CRUD + 发布流转（[knowledge.vue](../../../ai-vue/src/views/knowledge.vue)、[ArticleDialog.vue](../../../ai-vue/src/components/ArticleDialog.vue) 已有完整界面）、用户端公开列表/详情（[frontendKnowledge.vue](../../../ai-vue/src/views/frontendKnowledge.vue)、[articleDetail.vue](../../../ai-vue/src/views/articleDetail.vue) 已有）、封面图上传（`/file/upload`，走既有前端 uploadFile 封装）。后端 controller/entity/mapper/service 全部缺失。

库中已存在原项目导入的 `knowledge_article` / `knowledge_category` 表（含 7 篇已发布存量文章与 4 个分类）。本功能经 grill-with-docs 流程定稿（13 项决策），未拆 issue 工单；术语见 `CONTEXT.md` 知识内容域，主键决策见 `docs/adr/0006`。

## 决策摘要

- **管理端路径归位（ADR-0002）**：管理端 CRUD 迁 `/api/admin/knowledge/**`；用户端公开读 `/api/knowledge/article/{page,{id}}` 加 permitAll（免登录科普，路由本就放行）
- **客户端 UUID 主键（ADR-0006）**：`id char(36)` 由前端生成，与封面目录天然按文章归档（businessId 即文章 id）
- **分类**：复用既有 `knowledge_category` 平铺数据（4 条导入分类）；表预留 `parent_id` 但当前平铺，无分类管理界面，增删走 SQL
- **软删除**：`deleted` 列（本项目新增 ALTER），管理端列表含已删除行并在状态列显示红色标记（软删除=退出生命周期，不叠加生命周期标签）；用户端一律过滤
- **状态机**：0 草稿（创建即草稿，发布从列表操作）→ 1 已发布 → 2 已下线；发布/下线 UPDATE 带 `WHERE status IN (前置状态)` 防并发双击；已发布编辑直接生效不退回草稿
- **首发时间**：`published_at` 首次发布写入永不覆盖；重发布/下线不动它
- **updated_at 语义（测试后修订）**：内容编辑与状态流转都刷新（经表 `ON UPDATE CURRENT_TIMESTAMP` 自然生效）；**阅读计数显式回写原值**——访问不冒充修改
- **阅读量**：仅用户端详情接口原子 +1，不去重（虚荣指标，匿名无可靠身份；推荐位排序接口不计数，无自增回路）；管理端预览不计数
- **作者**：`author_id` 联表 user 取展示名（昵称优先），改名自动跟随
- **tags**：`varchar` 逗号串，响应时 split 为 `tagArray`；标签内禁逗号
- **XSS 双层防御**：wangEditor 编辑器层转义（实测粘贴 script 变纯文本）+ 后端入库 Jsoup 白名单清洗（实测 `<script>` 整体剥除、`on*` 属性剥除、正常标签保留）
- **文件存储**：本地磁盘 `./upload/{businessType}/{businessId}/`（配置在 yml），Spring ResourceHandler 映射 `/upload/**` 公开读；上传接口方法级 `@PreAuthorize` 限管理员（路径留 `/api/file/upload` 为将来用户端上传预留）；不建文件表（ADR-0006 后果节）；编辑时封面被替换/移除即删旧文件（含空目录清理），"上传后放弃保存"的孤儿文件为已接受残留
- **multipart 限额（测试后补齐）**：`max-file-size 5MB / max-request-size 6MB` 对齐前端契约；`server.tomcat.max-swallow-size: -1` + `GlobarExceptionHandler` 兜 `MaxUploadSizeExceededException`，超限返回友好 JSON 而非掐连接

## 契约（10 端点）

| 端点 | 权限 | 要点 |
|---|---|---|
| GET `/api/admin/knowledge/category/tree` | role 2 | 平铺数组 `{id, categoryName}` |
| GET `/api/admin/knowledge/article/page` | role 2 | 过滤 title/categoryId/status；含草稿/已下线/软删行；默认 updated_at desc |
| POST `/api/admin/knowledge/article` | role 2 | 接受客户端 UUID；创建即草稿 status=0 |
| GET `/api/admin/knowledge/article/{id}` | role 2 | 回显含 tagArray[]/categoryName；不计阅读量 |
| PUT `/api/admin/knowledge/article/{id}` | role 2 | 编辑直接生效，不动 status/published_at；封面替换时删旧文件 |
| PUT `/api/admin/knowledge/article/{id}/status` | role 2 | `{status: 1\|2}`；发布时校验 title/content 非空、首次写 published_at |
| DELETE `/api/admin/knowledge/article/{id}` | role 2 | 软删，任意状态可删 |
| GET `/api/knowledge/article/page` | 公开 | 强制 status=1 未删；排序白名单 `{publishedAt, readCount}`（防 order by 注入），默认 published_at desc |
| GET `/api/knowledge/article/{id}` | 公开 | 仅已发布未删可见（否则业务 404 防枚举）；`read_count` 原子 +1 且显式回写 updated_at |
| POST `/api/file/upload` | 方法级 role 2 | multipart：file/businessType/businessId/businessField；服务端校验图片类型 + ≤5MB + 路径段防穿越（`^[A-Z]+$` 类白名单）；返回 `{filePath: /upload/...}` |

## 数据库变更

无 migration 机制，手动执行（[docs/sql/knowledge-article.sql](../../../docs/sql/knowledge-article.sql)）：

```sql
ALTER TABLE knowledge_article ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0;
```

既有表结构差异（判定沿用）：`updated_at ON UPDATE CURRENT_TIMESTAMP`（阅读计数显式回写规避污染）；存量导入数据未过入库清洗（已扫描确认无危险标签）。

## 前端变更

- **admin.js**：7 个知识库接口迁 `/admin/knowledge/**`（uploadFile 不动）
- **config/index.js**：`fileBaseUrl` 改环境变量（`VITE_FILE_BASE_URL`，dev 回落 `http://localhost:1236`）；vite proxy `/api` 不变，图片走绝对 URL 无需代理
- **frontendKnowledge.vue**：`getImage` 硬编码外部 IP 改 `fileBaseUrl`；列表时间显示 `publishedAt`
- **articleDetail.vue**：时间显示 `publishedAt`（详情页原设计不渲染封面，仅书图标）
- **knowledge.vue**：发布时间列改绑 `publishedAt`（草稿显示"未发布"）；新增状态列（已删除红 > 草稿灰 > 已发布绿 > 已下线黄）
- **ArticleDialog.vue**：不上传封面时 `crypto.randomUUID()` 兜底生成 id（修复既有 bug：UUID 原本只在选封面时生成，裸创建提交 id=null 被后端拒）

## 测试与遗留

测试矩阵 A（管理端流程）/B（用户端匿名）/C1-C3、C5、C6（安全）/D（文件服务）全部通过（F12 脚本与结果见会话记录，用户测试笔记 error.md/explain.md 在仓库根目录）。

**遗留**：
1. **C4 复测待执行**：multipart 三处修复（5MB 限额 / max-swallow-size / 异常处理器）已编译通过，需重启后端后重跑 6MB 超限与 .txt 非图两个用例，预期 HTTP 200 + `{"code":"5004","msg":"图片大小不能超过5MB"}` 与"仅支持图片"类业务错误
2. 孤儿封面文件：`./upload` 下 3 个测试期上传后放弃保存的文件，可手动删
3. 测试文章（"草稿"/"下线"/已删"测试"）可从管理端清理
4. 无封面占位图仍指向外部 `file.itndedu.com`（原项目遗留，可用则不动）
5. ~~`/api/data-analytics/overview` 后端仍缺实现（数据看板页 404）~~ → 已实现（DataAnalyticsController + DataAnalyticsService，@PreAuthorize 限管理员，口径见 ADR-0007）
