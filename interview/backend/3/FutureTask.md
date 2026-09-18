`FutureTask` 是 Java 并发包（`java.util.concurrent`）里的一个类，用一句话说：

> 它同时是把任务装起来的"**可执行任务**"、又是个"**未来结果容器**"，并且只算一次、结果可被多线程共享读取。

## 它到底是个啥

`FutureTask<V>` 类声明是：

```java
public class FutureTask<V> implements RunnableFuture<V>
```

而 `RunnableFuture<V>` 接口同时继承了**两个接口**：

```java
public interface RunnableFuture<V> extends Runnable, Future<V> { }
```

所以一个 FutureTask 天生具备两副面孔：

| 面孔 | 继承自 | 干什么用 |
|------|--------|---------|
| `Runnable` | Runnable | 能当任务"**启动执行**" → `.run()` |
| `Future` | Future | 能当"**结果的凭证**" → `.get()` 取结果 |

这两个角色在 `FutureTask` 里**由同一个对象**承担，这正是它最妙的地方。

## 在你的代码里它一个对象干了三件事

对照 [EmotionAnalysisService.java](file:///d:/Coding/health-agent/HealthAgent/ai-spingboot/src/main/java/org/example/aispingboot/AiService/EmotionAnalysisService.java#L78-L100)：

**① 当 Runnable —— 装着干活代码，随时能 run**

```java
FutureTask<...> task = new FutureTask<>(() -> analyzeSession(sessionId));
```
构造时传一个 `Callable`（这里用 lambda 简写），这就是"真正调 LLM 的逻辑"，但此刻**什么都没跑**，只是把代码装进了任务盒。要启动才 `.run()`。

**② 当 Future —— 给别人"取结果"，且只取到一次算出的值**

```java
task.run();                       // 抢位的线程执行，真正调 LLM
...
return task.get(ANALYSIS_TIMEOUT_SECONDS, TimeUnit.SECONDS);   // 等结果 / 共享结果
```
`get()` 会**阻塞**直到任务完成，然后返回结果。关键是：**N 个线程可以 `get()` 同一个 FutureTask，都拿到那一次算出的同一个结果** —— 这就是并发去重能成立的根基。

**③ 自带"结果状态机"——自动处理只算一次、线程安全**

FutureTask 内部维护状态 `NEW → COMPLETING → NORMAL/EXCEPTIONAL` ：
- 谁先调 `run()` 真正执行 Callable，结果存进内部状态。
- 之后再有人 `get()`，**直接读已存结果**，不会再重复执行 Callable。
- 多线程 get 都是线程安全的，不用你自己加锁。

## 一句话区分几个容易混的类

- `Callable`：一段"能返回结果、能抛异常"的代码（`.call()`）。只描述**做什么**。
- `Future`：一个"**结果的票/凭证**"（`.get()`）。只描述**怎么取**。
- `FutureTask`：**把 Callable 装起来，同时当 Runnable（能跑）又当 Future（能取结果）** 的成品。你代码里实际 new 的就是它。

## 对照一处细节

你这个场景里没新开线程、`task.run()` 就是抢位请求线程自己同步跑（见上一条交流）。`FutureTask` 在**单线程 run + 多线程 get** 的模式下，凭"结果只算一次、读取线程安全"就足够实现去重了——这是它在这个设计里被选中（而非每次 new 一个 Future 或裸 Lock）的原因。