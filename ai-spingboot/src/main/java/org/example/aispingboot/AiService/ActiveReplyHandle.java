package org.example.aispingboot.AiService;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 活跃回复句柄：在途注册表的值对象（ADR 0009）。
 * 一个句柄对应一位用户当前唯一的一条活跃回复，承载四个职责所需的全部状态：
 * 守卫（存在即拒绝新流）、停止（显式 dispose 流句柄）、状态查询（sessionId + startedAt）、
 * 观察流（ADR 0010：replay sink，发射与观看解耦，重连观看的数据源）。
 */
public class ActiveReplyHandle {

    /**
     * 订阅宽限期：正常流程 claim 与订阅在同一调用栈内瞬时完成，
     * 超过宽限期仍无流句柄说明订阅方已消失（僵尸槽位，可驱逐）
     */
    private static final long STALE_GRACE_MS = 10_000;

    private final Long sessionId;
    private final long startedAt = System.currentTimeMillis();

    /**
     * 重连观看（ADR 0010）：replay().all() 热流——迟到订阅者收全量回放后接实时流。
     * 单条回复 ≤120s、几十 KB 量级，随句柄从注册表释放而被 GC，无界缓冲担忧不成立。
     * 不选 multicast 系：其默认 autoCancel 会在全部订阅者离开后清缓冲拒绝新订阅，断连间隙即废掉重连。
     */
    private final Sinks.Many<String> sink = Sinks.many().replay().all();

    private volatile Disposable disposable;
    private volatile boolean stopRequested;
    private volatile boolean discard;

    ActiveReplyHandle(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public long getStartedAt() {
        return startedAt;
    }

    /**
     * 观察流视图：/stream 原订阅者与 /stream/attach 重连订阅者共享同一发射历史。
     */
    public Flux<String> asFlux() {
        //调用 asFlux() 不会：
        //订阅 replyChain；
        //启动 LLM；
        //抢占槽位；
        //产生任何副作用。
        return sink.asFlux();
        //它只是返回一个可订阅的引用。真正触发 LLM 的是链 A 里那句：replyChain.subscribe(handle::emitNext, ...);
//        而链 B 的订阅：
//        handle.asFlux().subscribe(sseSubscriber);
//        只是“接上来看”，不影响生成。
    }

    /**
     * 发射一个片段。发射失败（如停止已 complete sink 而分片在途的 FAIL_TERMINATED）
     * 为良性竞态：分片丢给已终止流无害，忽略。
     */
    void emitNext(String fragment) {
        sink.tryEmitNext(fragment);
    }

    /**
     * 终止观察流：完成/停止/超时后通知全部订阅者——跨标签页停止的 done 传播
     * 由多播原生覆盖（ADR 0009 的单连接补救升级为 ADR 0010 的原生能力）。
     */
    void emitComplete() {
        sink.tryEmitComplete();
    }

    void emitError(Throwable error) {
        sink.tryEmitError(error);
    }

    /**
     * 流订阅后挂载句柄。若停止请求先于订阅到达（claim 与订阅间的竞态窗口），立即终止该流。
     */
    void attach(Disposable disposable) {
        this.disposable = disposable;
        if (stopRequested && disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /**
     * 显式停止。discard=true 用于删除会话场景：残句不落库（会话已退出业务流程，ADR 0009）。
     * dispose 同步触发取消传播，落库与槽位释放在内层 doOnCancel 中完成后再返回。
     */
    void requestStop(boolean discard) {
        this.discard = discard;
        this.stopRequested = true;
        Disposable d = this.disposable;
        if (d != null && !d.isDisposed()) {
            d.dispose();
        }
    }

    boolean isDiscard() {
        //isDiscard() 为 true（删除会话）
        return discard;
    }

    boolean isStale() {
        return disposable == null && System.currentTimeMillis() - startedAt > STALE_GRACE_MS;
    }
}
