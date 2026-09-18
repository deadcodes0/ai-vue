package org.example.aispingboot.controller;

import cn.hutool.json.JSONUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.example.aispingboot.AiService.ActiveReplyHandle;
import org.example.aispingboot.AiService.ActiveReplyRegistry;
import org.example.aispingboot.AiService.EmotionAnalysisService;
import org.example.aispingboot.AiService.PsychologicalSupportService;
import org.example.aispingboot.AiService.StructOutPut;
import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.DTO.command.ConsultationStreamDTO;
import org.example.aispingboot.DTO.response.ActiveReplyStatusDTO;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.DTO.response.ConsultationSessionResponseDTO;
import org.example.aispingboot.DTO.response.SessionEmotionResponseDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.service.ConsultationMessageService;
import org.example.aispingboot.service.ConsultationSessionService;
import org.example.aispingboot.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/psychological-chat")
public class PsychologicalChat {
    @Autowired
    private PsychologicalSupportService psychologicalSupportService;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private EmotionAnalysisService emotionAnalysisService;

    @Autowired
    private ActiveReplyRegistry activeReplyRegistry;

    @PostMapping("/session/start")
    public Result<StructOutPut.StreamChatSession> startSession(@Valid @RequestBody ConsultationSessionCreateDTO createDTO) {
        // 获取当前用户
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        // 单活跃流守卫（ADR 0009）：新会话的第一条消息也是消息，
        // 此处前置拒绝可避免"空会话已建、首条流被 409"的孤儿会话
        if (activeReplyRegistry.findActive(userId) != null) {
            return Result.error("409", "您有一条AI回复正在生成中，请等待完成或点击停止", null);
        }
        StructOutPut.StreamChatSession session = psychologicalSupportService.startSession(userId, createDTO);
        return Result.ok(session);
    }

    /**
     * 用户端会话分页列表（仅本人、未删除）
     */
    @GetMapping("/sessions")
    public Result<Page<ConsultationSessionResponseDTO>> getSessionList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(consultationSessionService.pageUserSessions(userId, pageNum, pageSize));
    }

    /**
     * 用户端软删除会话（归属不符返回 404 防枚举）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        // 删除优先（ADR 0009）：被删会话若有活跃回复，终止且残句不落库
        // （会话已退出业务流程，软删除定义：不再接受新消息）
        activeReplyRegistry.stopIfSession(userId, sessionId);
        boolean deleted = consultationSessionService.softDeleteSession(userId, sessionId);
        if (!deleted) {
            return Result.error("404", "会话不存在", null);
        }
        return Result.ok();
    }

    /**
     * 用户端会话消息（按 id 升序）
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessageResponseDTO>> getSessionDetail(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        ConsultationSession session = consultationSessionService.findOwnedSession(userId, sessionId);
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            return Result.error("404", "会话不存在", null);
        }
        return Result.ok(consultationMessageService.listMessagesBySessionId(sessionId));
    }

    /**
     * 会话情绪分析（懒计算+缓存：新鲜则返回库中快照，过期则同步分析；失败降级旧快照/中性值）
     */
    @GetMapping("/session/{sessionId}/emotion")
    public Result<SessionEmotionResponseDTO> getSessionEmotion(@PathVariable String sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        Long dbSessionId = psychologicalSupportService.extractSessionId(sessionId);
        ConsultationSession session = dbSessionId != null
                ? consultationSessionService.findOwnedSession(userId, dbSessionId)
                : null;
        if (session == null || Boolean.TRUE.equals(session.getDeleted())) {
            return Result.error("404", "会话不存在", null);
        }
        return Result.ok(emotionAnalysisService.getOrAnalyzeEmotion(session.getId()));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ConsultationStreamDTO streamDTO) {
        // 获取当前用户

        Long userId = getCurrentUserId();
        if (userId == null) {
            return errorEvent(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
        }

        // 校验会话归属与状态（修复越权写入）
        Long dbSessionId = psychologicalSupportService.extractSessionId(streamDTO.getSessionId());
        ConsultationSession session = dbSessionId != null
                ? consultationSessionService.findOwnedSession(userId, dbSessionId)
                : null;
        if (session == null) {
            return errorEvent("404", "会话不存在");
        }
        if (Boolean.TRUE.equals(session.getDeleted())) {
            return errorEvent("404", "会话已删除");
        }

        // 单活跃流守卫（ADR 0009）：Flux.defer 使槽位抢占发生在订阅时刻，与流启动原子衔接——
        // 订阅不发生则不占槽，无泄漏窗口；并发抢占由 tryClaim 的 putIfAbsent 原子性保证
        // 只有在被订阅那一刻才执行
        return Flux.defer(() -> {
            ActiveReplyHandle handle = activeReplyRegistry.tryClaim(userId, session.getId());
            if (handle == null) {
                return errorEvent("409", "您有一条AI回复正在生成中，请等待完成或点击停止");
            }

            // 开始流式对话：返回句柄持有的共享热观察流（replay，ADR 0010），
            // 原订阅者与重连观看者同源；不再附加 delayElements 人为节流（ADR 0010 删除）
            return toSse(psychologicalSupportService
                    .streamPsychologicalChat(session, streamDTO.getUserMessage(), userId, handle));
        });
    }

    /**
     * 重连观看（ADR 0010）：页面刷新后重进、或本标签页切回生成中会话时，重新订阅活跃回复的
     * 观察流。replay 语义：迟到订阅者收全量回放后接实时片段；完成瞬间的迟到者直接收
     * "全量回放+done"。404（无活跃回复/不属于该会话）是正常扑空信号，前端回退拉取消息列表。
     * 注册表按 userId 键控：句柄即本人活跃回复，claim 时已校验会话归属，sessionId 匹配即安全。
     */
    @PostMapping(value = "/stream/attach/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> attachStream(@PathVariable Long sessionId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return errorEvent(ResultCode.UNAUTHORIZED.getCode(), "用户未登录");
        }
        ActiveReplyHandle handle = activeReplyRegistry.findActive(userId);
        if (handle == null || !sessionId.equals(handle.getSessionId())) {
            return errorEvent("404", "没有正在生成的回复");
        }
        return toSse(handle.asFlux());
    }

    /**
     * 显式停止当前活跃回复（ADR 0009）：停止是命令而非断开连接。
     * 残句按契约落库后才返回，前端收到响应即可刷新视图。
     */
    @PostMapping("/stream/stop")
    public Result<Void> stopStream() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        Long stoppedSessionId = activeReplyRegistry.requestStop(userId, false);
        if (stoppedSessionId == null) {
            return Result.error("404", "没有正在生成的回复", null);
        }
        return Result.ok();
    }

    /**
     * 活跃回复状态查询（ADR 0009）：前端刷新后恢复输入锁定态、会话列表"生成中"徽标。
     */
    @GetMapping("/stream/active")
    public Result<ActiveReplyStatusDTO> getActiveReply() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        ActiveReplyHandle handle = activeReplyRegistry.findActive(userId);
        if (handle == null) {
            return Result.ok(null);
        }
        return Result.ok(new ActiveReplyStatusDTO(handle.getSessionId(), handle.getStartedAt()));
    }

    // 从当前请求的 JWT 中解析用户ID
    private Long getCurrentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        if (token == null) {
            return null;
        }
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }

    // 构造 SSE 错误事件
    private Flux<ServerSentEvent<String>> errorEvent(String code, String msg) {
        return Flux.just(ServerSentEvent.<String>builder()
                .event("error")
                .data(JSONUtil.toJsonStr(Result.error(code, msg, null)))
                .build());
    }

    // 观察流 → SSE 事件（/stream 与 /stream/attach 共用同一事件结构，ADR 0010）
    private Flux<ServerSentEvent<String>> toSse(Flux<String> fragments) {
        return fragments
                .map(fragment -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(JSONUtil.toJsonStr(Result.ok(Map.of("content", fragment, "type", "normal"))))
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("{}")
                        .build()));
    }
}
