package org.example.aispingboot.AiService;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 活跃回复在途注册表（ADR 0009）：按 userId 键控，per-user 单活跃流不变量的唯一事实源。
 * 职责三合一：新流守卫（tryClaim 原子抢占）、停止命令定位（requestStop）、活跃状态查询（findActive）。
 * 复用 ADR 0004 情绪分析已验证的 ConcurrentHashMap 并发去重模式。
 */
@Component
public class ActiveReplyRegistry {

    private final ConcurrentHashMap<Long, ActiveReplyHandle> activeReplies = new ConcurrentHashMap<>();

    /**
     * 原子抢占 per-user 槽位。已有活跃回复时返回 null（守卫拒绝）。
     * 遇到从未挂载流句柄的僵尸槽位（订阅方在 claim 后消失）则驱逐后重试一次。
     */
    public ActiveReplyHandle tryClaim(Long userId, Long sessionId) {
        ActiveReplyHandle handle = new ActiveReplyHandle(sessionId);
        ActiveReplyHandle existing = activeReplies.putIfAbsent(userId, handle);
        if (existing == null) {
            return handle;
        }
        if (existing.isStale() && activeReplies.remove(userId, existing)) {
            return activeReplies.putIfAbsent(userId, handle) == null ? handle : null;
        }
        return null;
    }

    /**
     * 释放槽位（仅当仍是该句柄，防止误删后到的新流）。
     * 由内层流的 complete/error/cancel 路径调用；requestStop 兜底调用，幂等。
     */
    public void release(Long userId, ActiveReplyHandle handle) {
        if (userId != null && handle != null) {
            activeReplies.remove(userId, handle);
        }
    }

    /**
     * 显式停止当前活跃回复。返回被停止的会话 id；无活跃回复返回 null。
     * discard=true：停止且残句不落库（删除会话场景）。
     */
    public Long requestStop(Long userId, boolean discard) {
        ActiveReplyHandle handle = activeReplies.get(userId);
        if (handle == null) {
            return null;
        }
        handle.requestStop(discard);
        return handle.getSessionId();
    }

    /**
     * 若活跃回复属于指定会话则停止（删除会话场景：删除优先，残句不落库）。
     */
    public void stopIfSession(Long userId, Long sessionId) {
        ActiveReplyHandle handle = activeReplies.get(userId);
        if (handle != null && sessionId.equals(handle.getSessionId())) {
            handle.requestStop(true);
        }
    }

    /**
     * 活跃状态查询：供前端刷新后恢复输入锁定态、会话列表"生成中"徽标。
     */
    public ActiveReplyHandle findActive(Long userId) {
        return activeReplies.get(userId);
    }
}
