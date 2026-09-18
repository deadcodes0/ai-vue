- **AI 回复流生命周期管理**：**活跃流注册表** 维护单用户单活跃回复流，惰性占槽防并发重复调用，停止/超时按残句落库收尾，保证多端状态一致、消息不丢不乱序。 
怎么理解呢？给出具体证据。

你问到关键处了——这里要区分**两个"订阅"**。`Flux.defer` 确实保证"订阅时才执行"，但"lambda 执行了"和"流成功启动"是两回事。

## 两个订阅，不是一回事

**外层订阅**：客户端连上 SSE → WebFlux 订阅 Controller 返回的 Flux → 触发 [Flux.defer 的 lambda](file:///d:/Coding/health-agent/HealthAgent/ai-spingboot/src/main/java/org/example/aispingboot/controller/PsychologicalChat.java#L161-L176)，此时 `tryClaim` 占槽。

**内层订阅**：`tryClaim` 之后**同步**调用 `streamPsychologicalChat`，里面的 `replyChain.subscribe(...)` 才是真正跑 LLM 的那条流。

两者不是绑定关系。`Flux.defer` 保证的只是：**外层订阅发生时 lambda 一定执行**。但 lambda 内部是普通同步代码，它一路执行：

```java
rebuildConversationMemory(...)        // ① 可能抛异常（DB故障）
saveUserMessage(...)                  // ② 可能抛异常
chatClient.prompt()...stream()...     // ③ 构造链可能抛异常
replyChain.subscribe(...)             // ④ 注册释放槽位的回调
handle.attach(disposable)             // ⑤ 挂载流句柄
```

僵尸的精确形成就是：**①~③ 任何一处抛异常** → `④` 和 `⑤` 永远执行不到。

## 所以你的直觉错在哪

"订阅成功才执行"——准确说是"**被订阅时**执行"。lambda 执行只代表外层订阅动作发生了；如果 lambda 中途抛异常，`Flux.defer` 返回的 Flux 直接以 **error 信号**终止，客户端收到错误/断连。但此时：

- 槽位已被 `tryClaim` 占了（外层订阅的产物）✅ 已发生
- 槽位释放 `release` 是**内层流**的收尾回调（`complete/error/cancel` 里注册的）❌ 从未注册
- `requestStop` 也救不了：`disposable` 是 null，[requestStop](file:///d:/Coding/health-agent/HealthAgent/ai-spingboot/src/main/java/org/example/aispingboot/AiService/ActiveReplyHandle.java#L97-L105) 里 `d != null` 判断直接跳过，无处可 dispose

**占槽由外层订阅触发，释放由内层流控制——两者之间有一个异常缝隙**，这就是注释里"订阅方在 claim 后消失"的意思：claim 发生了，但"真正的订阅"（内层 `replyChain.subscribe`）从未成立，于是这个用户的槽位被永久占住，只能靠 10s 宽限期 `isStale()` 兜底驱逐。

一句话总结：**`Flux.defer` 只保证"外层订阅 → 执行 lambda"，不保证"内层流成功启动"；僵尸正是"外层已占槽、内层未启动"这个缝隙的产物。**

前端发请求（建立 SSE 连接）
    └──> Controller 返回 Flux.defer 对象   ← 此时还没执行，只是"准备好了"
                                          ← lambda 还是空的
    └──> 后端 HTTP 框架真正开始往这条连接写数据
         （底层调用 subscribe）
            └──> Flux.defer 的 lambda 才真正执行
                 ├── tryClaim 占槽 ✅
                 └── 流启动，开始往连接里推片段