# 02 句柄挂载 replay sink + 服务改为 sink 发射

Status: done

## 内容

- `ActiveReplyHandle`：新增 `Sinks.many().replay().all()` 字段（claim 时随句柄构造即存在）；`asFlux()`（public，供控制器跨包订阅）、`emitNext/emitComplete/emitError`（包内，服务专用）；发射失败（FAIL_TERMINATED 良性竞态）忽略
- `streamPsychologicalChat`：去掉 `Flux.create` 桥接——方法调用即完成 setup（记忆重建、用户消息落库）并订阅内层 LLM 链发射到 sink；doOnCancel/错误/完成三路径不变（残句落库→release→emitComplete/emitError）；返回 `handle.asFlux()` 热流
- 生成启动时机仍原子于订阅时刻（Flux.defer 内调用），冷流可重入地雷随热流消除

## 验收

- 编译通过；停止路径（doOnCancel）落库→release→emitComplete 顺序保持 ADR 0009 契约
