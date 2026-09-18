# 04 前端 attach 接入

Status: done

## 内容

- `consultation.vue` 新增 `attachActiveReply(dbSessionId)`：创建 reactive AI 气泡（注册 inflightAiMessages，切走切回仍续显）、fetchEventSource 打开 attach 流、返回消息对象
- `handleSessionClick`：原"生成中占位符"分支替换为 attach（`isAiTyping && activeReplySessionId 匹配 && 无本地 inflight 对象`）；本标签页自己发起的流仍走本地对象续显，不重复 attach
- onmessage：done 同 /stream 契约（清理 + 情绪刷新 + getSessionPage + stopActivePolling）；非 200（404 扑空）→ 清理 + refreshSessionView 拉库 + restoreActiveReply 对齐
- onerror：deliberateStop 吞掉（停止/删除主动中止）；否则回退轮询（断开不取消，以服务器状态为准）
- 刷新恢复（restoreActiveReply）保留 /stream/active + 3s 轮询：维护锁态与徽标；attach 仅在点入生成中会话时发起

## 验收

- 刷新后点入生成中会话：先见半截（回放）后续跳字；完成后 done 收尾可续聊
- 停止按钮对 attach 流同样生效（activeCtrl 共享，后端 dispose → emitComplete → done）
