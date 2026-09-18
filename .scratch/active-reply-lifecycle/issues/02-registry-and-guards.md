# 02 后端在途注册表与单活跃流守卫

Status: done

## 内容

- `ActiveReplyHandle`：sessionId、startedAt、volatile Disposable；attach（停止先于订阅的竞态立即 dispose）、requestStop(discard)（dispose 同步触发取消传播）、isStale（10s 宽限驱逐从未挂载的僵尸槽位）
- `ActiveReplyRegistry`：ConcurrentHashMap&lt;userId, handle&gt;（复用 ADR-0004 模式）；tryClaim 用 putIfAbsent 原子抢占；release 仅当仍是该句柄（remove(k,v)）
- stream 端点用 Flux.defer：槽位抢占发生在订阅时刻，订阅不发生不占槽，无泄漏窗口；冲突返回 SSE error 事件 409（守卫先于用户消息落库，被拒请求不留痕）
- startSession 前置 findActive 守卫（409）：避免"空会话已建、首条流被拒"的孤儿会话
- deleteSession 先 stopIfSession(userId, sessionId)（discard=true，残句不落库）再软删除——删除优先
