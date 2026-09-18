# 活跃回复生命周期（ADR-0009 实现）

## 背景与范围

刷新可绕过 ADR-0008 导航锁的实测暴露两个事实：单活跃流只是前端内存布尔值、系统并未执行；"断开后回复仍生成并落库"靠取消信号未传播到内层订阅的偶然容错。本模块把三条契约落进代码：断开不取消、停止是显式命令、单活跃流不变量下沉后端；同时废止导航锁（切换/新建/删除自由），并发语义经拷问后定为单流（多流分区为文档化升级路径，见 ADR-0009 被否选项）。术语见 `CONTEXT.md` 活跃回复/停止生成词条。

## 决策摘要

- 断开不取消：内层 LLM 订阅不接入外层 sink 取消生命周期，断开后跑完完整落库（有意的不作为，防回归注释在 streamPsychologicalChat）
- 取消仅为显式命令：停止/删除所属会话经注册表 dispose 流句柄，doOnCancel 落残句（discard 场景不落库）；120s 超时由 take 按停止语义收尾
- 落库 → 释放槽位 → done 的顺序：收到 done 即可续聊、新消息不排错位
- 单活跃流 per-user 下沉：ConcurrentHashMap 注册表（复用 ADR-0004 模式），Flux.defer 使抢占发生在订阅时刻（不订阅不占槽）
- 残句即最终形态、无标记（与自然完成无异，进对话记忆与情绪分析窗口）
- 删除带活跃回复的会话：删除优先，终止生成且残句不落库

## 端点清单

| 端点 | 行为 |
|---|---|
| POST /api/psychological-chat/stream（重构） | 订阅时 tryClaim 槽位，冲突返回 SSE error 事件（409）；守卫先于用户消息落库 |
| POST /api/psychological-chat/stream/stop（新增） | 显式停止：落残句、释放槽位后返回；无活跃回复返回 404 |
| GET /api/psychological-chat/stream/active（新增） | 活跃状态查询：sessionId + startedAt，无则 data=null |
| POST /api/psychological-chat/session/start（加守卫） | 已有活跃回复返回 409（避免孤儿空会话） |
| DELETE /api/psychological-chat/sessions/{id}（加终止） | 先 stopIfSession（discard），再软删除 |

无数据库变更。

## 文件清单

- 新增：`AiService/ActiveReplyHandle`（句柄：挂载/停止/discard/僵尸宽限）、`AiService/ActiveReplyRegistry`（tryClaim/release/requestStop/stopIfSession/findActive）、`DTO/response/ActiveReplyStatusDTO`
- 重构：`AiService/PsychologicalSupportService.streamPsychologicalChat`（生命周期契约注释 + take 超时 + doOnCancel 残句 + 顺序保证）
- 前端：`api/frontend.js`（stopActiveReply/getActiveReply）；`views/consultation.vue`（导航锁移除、生成中徽标、停止按钮、刷新恢复 + 3s 轮询、inflightAiMessages 切回续显、409 对齐、删除会话本地流中止）

## 已知边界（设计内，非 bug）

- 刷新恢复的活跃回复无本地流对象：表现为"用户消息 + 生成中占位"，完成后整条出现（轮询周期 ≤3s）；半截实时续显需多流分区（升级路径）
- 409 守卫路径：被拒标签页的用户消息仅本地暂显（后端未落库），刷新后消失
- 跨标签页停止：发起方气泡停在最后收到的分片，与落库残句可能有 50ms 在途差，重进会话后精确
- 120s 超时收尾无法自然触发（正常回复远短于 120s），代码路径与停止共用落库逻辑，停止用例覆盖即可
