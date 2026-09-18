```java
.doOnCancel(() -> {
    // 取消仅为显式停止/删除会话而发生（断开不会传播到这里）：残句按契约落库
    try {
        if (!handle.isDiscard() && fullResponse.length() > 0) {
            consultationMessageService.saveAimessage(dbSessionId, fullResponse.toString(), "openai");
        }
    } catch (Exception e) {
        log.error("停止时残句落库失败, sessionId={}: {}", dbSessionId, e.getMessage());
    }
    activeReplyRegistry.release(userId, handle);
    handle.emitComplete();
});
```
你选中的这段是 `doOnCancel` 的 lambda（一个回调函数）。**它本身不是"方法"、不是被显式调用**，而是 Reactor 在**流被取消**时自动触发的钩子。关键问题换成：**"`replyChain` 的 cancel 信号由谁发出？"**

## 触发链：谁发出 cancel → 谁触发 doOnCancel

我先把代码里能产生 cancel 的路径列全，再定位到"谁"。

### 唯一直接触发 `doOnCancel` 的入口：`handle.requestStop(...)` → `disposable.dispose()`

看 [ActiveReplyHandle.requestStop](file:///d:/Coding/health-agent/HealthAgent/ai-spingboot/src/main/java/org/example/aispingboot/AiService/ActiveReplyHandle.java#L97-L105)：

```java
void requestStop(boolean discard) {
    this.discard = discard;
    this.stopRequested = true;
    Disposable d = this.disposable;
    if (d != null && !d.isDisposed()) {
        d.dispose();   // ← dispose 传播 cancel 信号 → 触发 doOnCancel
    }
}
```

`dispose()` 是发 cancel 信号的唯一动作。而 `requestStop` 只被**[注册表](file:///d:/Coding/health-agent/HealthAgent/ai-spingboot/src/main/java/org/example/aispingboot/AiService/ActiveReplyRegistry.java#L47-L63)** 两个方法调用：

- `requestStop(userId, discard)` ← 由 **停止接口** `/stream/stop` 调用
- `stopIfSession(userId, sessionId)` ← 由 **删除会话接口** `softDeleteSession` 调用

`requestStop` 内部：
```java
handle.requestStop(discard);   // → disposable.dispose() → doOnCancel 触发
```

所以**真正"触发"这段 `doOnCancel` 代码的，是用户点了"停止生成"或"删除该会话"**。代码路径：

```
前端点停止/删除
  → /stream/stop 或 SOFT DELETE 接口
    → activeReplyRegistry.requestStop / stopIfSession
      → handle.requestStop(...)
        → disposable.dispose()
          → 流被取消 → doOnCancel 执行
```

### attach 里还有一个"第二触发点"，用于竞态对冲

[ActiveReplyHandle.attach](file:///d:/Coding/health-agent/HealthAgent/ai-spingboot/src/main/java/org/example/aispingboot/AiService/ActiveReplyHandle.java#L83-L91) 里：

```java
void attach(Disposable disposable) {
    this.disposable = disposable;
    if (stopRequested && disposable != null && !disposable.isDisposed()) {
        disposable.dispose();   // 停止请求先于订阅到达时，补一次 dispose → 也触发 doOnCancel
    }
}
```

场景：用户点击停止时流还在初始化（claim 后、attach 前），`requestStop` 因 `disposable=null` 没停到；等 attach 挂上后检测到 `stopRequested=true`，补一次 dispose。**最终结果一样——都是通过 `dispose()` 触发 `doOnCancel`。**

## 谁"不会"触发它（关键澄清）

很多人会误以为"客户端断开/刷新"会触发——**不会**。这正是 ADR 0010 的核心：

- 客户端断开只导致 **SSE 观察流减少一个订阅者**；
- 内层 `replyChain` 用的是 `Sinks.many().replay().all()` 热流，**发射与观看解耦**；
- 观察者走人不等于生成流走人 → **`doOnCancel` 不会因刷新触发**，内层继续跑完走 `complete` 回调正常落库。

所以注释里写得很清楚（[L110](file:///d:/Coding/health-agent/HealthAgent/ai-spingboot/src/main/java/org/example/aispingboot/AiService/PsychologicalSupportService.java#L110)）：*"取消仅为显式停止/删除会话而发生（断开不会传播到这里）"*。

## 总结

| 触发来源 | 谁发出 | 路径 |
|---|---|---|
| 用户停止生成 | `/stream/stop` 接口 | registry.requestStop → handle.requestStop → dispose → **doOnCancel** |
| 删除有活跃回复的会话 | 删除接口 | registry.stopIfSession → handle.requestStop(discard=true) → dispose → **doOnCancel** |
| 停止先于订阅的竞态 | attach 补刀 | attach 检测 stopRequested → dispose → **doOnCancel** |
| 客户端刷新/断开 | 无 | **不触发**（断开不取消契约）|

一句话：**触发 `doOnCancel` 的不是前端连接，而是"停止命令"（停止/删除）通过 `dispose()` 发出的取消信号；第 109-120 行这段就是"停止时把已生成的部分按残句落库并释放槽位"的收尾动作。**