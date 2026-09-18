# 咨询会话管理模块

## 背景与范围

前端已调用、后端缺失的 4 个用户端端点 + 管理端 2 端点 + stream 链路修复。决策记录见 `docs/adr/0001~0003`，术语见 `CONTEXT.md`。

## 决策摘要

- 用户端/管理端接口分离（ADR-0002）：用户端 `/api/psychological-chat/*`（按 JWT userId 过滤），管理端 `/api/admin/consultation/*`（hasRole("2")）
- 软删除（ADR-0001）：用户端不可见、管理端带标记可见、已删除会话拒绝新消息
- 对话记忆按需重建（ADR-0003）：stream 时内存无记录则从 consultation_message 回放最近 30 条；初始消息保存从 startSession 移到 stream（消灭去重 hack）；删除手动 chatMemory.add（advisor 自动写回，见字节码验证）
- 情绪端点为契约占位：返回中性默认值 DTO（Q4=c / Q9=a），生产逻辑后续接入
- 归属不符返回 404 防枚举；durationMinutes 查询时由 startedAt 与最后消息时间推导

## 数据库变更（需手工执行）

```sql
ALTER TABLE consultation_session
  ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记 0:正常 1:已删除';
```

## 端点清单

| 端点 | 行为 |
|---|---|
| GET /api/psychological-chat/sessions?pageNum&pageSize | 本人 + 未删除，按 startedAt 倒序，聚合 lastMessageContent/messageCount/durationMinutes |
| DELETE /api/psychological-chat/sessions/{id} | 软删除，归属不符 404，重复删除幂等 |
| GET /api/psychological-chat/sessions/{id}/messages | 归属校验，按 id 升序返回数组 |
| GET /api/psychological-chat/session/{id}/emotion | 归属校验，返回中性默认 DTO |
| GET /api/admin/consultation/sessions?currentPage&size | 全部会话含已删除，带 userNickname/deleted |
| GET /api/admin/consultation/sessions/{id}/messages | 消息数组 |
| POST /api/psychological-chat/stream（修复） | 归属+已删除校验；记忆缺失时回放重建；错误以 SSE error 事件返回 |

## 前端变更

- admin.js：getConsultationPage、getSessionDetail URL 改为 /admin/consultation/*
- consultations.vue：已删除会话显示"用户已删除"标记
- consultation.vue：SSE 错误提示读 payload.msg（原读 payload.message 是 bug，新错误路径依赖它）
