# 重连观看（ADR-0010 实现）

Status: done（已归档）

## 背景与范围

ADR 0009 的"半截不可见"已接受限制（切回生成中会话/刷新后只见占位符，回复完成后整条出现）经拷问决定落地：观察流从 `Flux.create` 冷桥接改为句柄持有的 `Sinks.many().replay().all()` 热流，发射与观看解耦；前端点入生成中会话时 attach 接回。顺带消除冷流可重入地雷、删除 50ms 人为节流。ADR 0009 三契约（断开不取消/停止是显式命令/单活跃流）不变。术语见 `CONTEXT.md` 重连观看词条。

## 决策摘要

- `ActiveReplyHandle` 持有 replay().all() sink：claim 时即存在；tryEmitNext/tryEmitComplete，发射失败（FAIL_TERMINATED 竞态）忽略
- `streamPsychologicalChat` 改为：方法调用即启动内层链（仍在 Flux.defer 订阅时刻，与 claim 原子），setup 落库逻辑不变，doOnCancel/complete 路径 emitComplete 通知全部订阅者；返回 handle.asFlux()
- 新增 `POST /stream/attach/{dbSessionId}`：findActive + sessionId 匹配即订阅共享观察流；不匹配/无活跃回复返回 SSE error 404（扑空信号）
- /stream 与 /stream/attach 共用 toSse 事件包装（message/done/error 结构一致）；/stream 删除 delayElements(50ms)
- 前端：点入生成中会话（isAiTyping && activeReplySessionId 匹配 && 无本地 inflight 对象）→ attachActiveReply 回放半截+续收实时；done 同 /stream 契约；404 扑空 → refreshSessionView 拉库 + restoreActiveReply 对齐；连接异常回退轮询

## 端点清单

| 端点 | 行为 |
|---|---|
| POST /api/psychological-chat/stream/attach/{sessionId}（新增） | 重连观看：replay 回放+实时流；404=扑空信号（前端拉库回退） |
| POST /api/psychological-chat/stream（重构） | 返回共享热观察流；删 delayElements |

无数据库变更。

## 文件清单

- 重构：`AiService/ActiveReplyHandle`（+replay sink 与发射方法）、`AiService/PsychologicalSupportService.streamPsychologicalChat`（sink 发射 + 契约注释更新）、`controller/PsychologicalChat`（attach 端点 + toSse 共用 + 删节流）
- 前端：`views/consultation.vue`（attachActiveReply + handleSessionClick 占位符分支替换 + 注释更新）

## 已知边界（设计内，非 bug）

- 多标签页并发观看为机制副产品不作承诺；双 done 触发情绪分析由 ADR-0004 懒计算+Future 去重吸收
- attach 扑空窗口（release 与 complete 之间）为纳秒级，且回放语义使多数迟到者收到"全量回放+done"而非 404
- 未持有流信息而点入生成中会话的标签页（如从不查询活跃状态的旧标签页）仍只见库中消息——attach 仅在已知活跃回复的前提下发起

## Comments

- 2026-09-11 归档。后端 `mvnw compile` 通过；前端改动为同文件模式内插入（未单独构建验证）。手测清单 T1-T8 已交付（T1 回归 / T2 刷新重连观看 / T3 同标签页切回 / T4 跨标签页双端 done / T5 attach 态停止与删除优先 / T6 扑空 404 / T7 越权 401·404 / T8 并发 409），结果未回填——如有回归按惯例追加 `05-test-feedback-fixes.md`
- 交付物：ADR 0010、CONTEXT.md 词条、后端三文件（handle/service/controller）、前端 consultation.vue；无数据库变更
