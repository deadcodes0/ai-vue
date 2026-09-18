# 03 attach 端点与事件包装共用

Status: done

## 内容

- `PsychologicalChat` 新增 `POST /stream/attach/{sessionId}`（SSE）：getCurrentUserId → findActive → 句柄存在且 sessionId 匹配 → 订阅 `handle.asFlux()`；否则 SSE error 404"没有正在生成的回复"（扑空信号，前端拉库回退）
- 注册表按 userId 键控：句柄即本人活跃回复，claim 时已校验会话归属，sessionId 匹配即安全
- 抽取 `toSse(Flux<String>)` 共用事件包装（message/done），/stream 与 /stream/attach 事件结构一致
- /stream 删除 `delayElements(50ms)`（回放追赶不被拖慢，正常观看不再附加延迟）；移除 Duration import

## 验收

- attach 404 事件可被前端 onmessage 的非 200 分支识别
