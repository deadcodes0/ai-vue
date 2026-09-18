# 用户端与管理端接口分离，不共用端点

咨询会话数据曾由用户端与管理端页面共用同一路径（`/api/psychological-chat/sessions` 等），但两者语义冲突：用户端只应看到自己的会话，管理端要看全部用户的会话。决定拆分为两套接口：用户端 `/api/psychological-chat/*` 一律按 JWT 中的 userId 过滤；管理端走独立的 `/api/admin/consultation/*` 前缀并要求管理员角色。前端 `ai-vue/src/api/admin.js` 中 `getConsultationPage`、`getSessionDetail` 两个函数的 URL 需相应修改。

## Consequences

- 同一数据存在两套读取路径，聚合字段需求不同（管理端需 join 用户昵称）。
- 用户端端点做归属校验时，不匹配返回 404 而非 403，避免会话 ID 枚举探测。
- 现有 `/stream` 端点的越权写入（拿到任意 sessionId 即可写入他人会话）在同一模块内一并修复。
