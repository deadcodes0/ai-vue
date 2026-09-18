package org.example.aispingboot.AiService;

import org.example.aispingboot.DTO.command.ConsultationSessionCreateDTO;
import org.example.aispingboot.entity.ConsultationMessage;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.service.ConsultationMessageService;
import org.example.aispingboot.service.ConsultationSessionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class PsychologicalSupportService {
    private static final Logger log = LoggerFactory.getLogger(PsychologicalSupportService.class);

    // 回复最大时长：超时由 take 按停止语义收尾（已累积内容落库，ADR 0009）
    private static final long REPLY_MAX_SECONDS = 120;

    @Autowired
    @Qualifier("open-ai")
    private ChatClient chatClient;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private ActiveReplyRegistry activeReplyRegistry;

    // 对话记忆窗口大小，与 ChatClientConfig 中 maxMessages 保持一致
    private static final int MEMORY_WINDOW_SIZE = 30;

    public StructOutPut.StreamChatSession startSession(Long userId, ConsultationSessionCreateDTO createDTO) {
        // 创建数据库会话记录（初始消息由 stream 接口统一保存，避免重复写入）
        ConsultationSession consultationSession = consultationSessionService.createSession(userId, createDTO);

        // 创建会话信息
        String sessionId = "session_" + consultationSession.getId();
        return new StructOutPut.StreamChatSession(
                sessionId,
                userId,
                createDTO.getInitialMessage(),
                System.currentTimeMillis(),
                System.currentTimeMillis() + 86400000L, // 24小时
                0,
                "ACTIVE"
        );
    }

    /**
     * 流式对话生命周期契约（ADR 0009；观察流为可重放 sink，ADR 0010）：
     * - 断开不取消：内层 LLM 订阅不接入任何 SSE 订阅者的取消生命周期（replay sink 使发射
     *   独立于观看）。客户端断开（刷新/关页）只减少一个订阅者，内层继续跑完并完整落库。
     *   勿"优化"成接线取消——那会把用户刷新变成数据截断。
     * - 取消仅为显式命令而发生：停止接口 / 删除所属会话经由注册表 dispose 流句柄（doOnCancel
     *   落残句，discard 场景不落库）；回复超过最大时长由 take 按停止语义收尾（残句落库）。
     *   emitComplete 通知全部订阅者：停止可能来自其他标签页或重连观看中的连接。
     * - 顺序约束：落库 → 释放槽位 → 终止观察流。释放先于 done，保证客户端收到 done 后立即
     *   续聊不撞 409；落库先于释放，保证新流的用户消息不会排到上一条 AI 回复之前。
     * - 错误不落库：部分落库仅属于显式停止与超时路径（ADR 0009）。
     */
    public Flux<String> streamPsychologicalChat(ConsultationSession session, String userMessage,
                                                Long userId, ActiveReplyHandle handle) {
        Long dbSessionId = session.getId();

        // 生成对话记忆管理
        String conversationId = "conversation_session_" + dbSessionId;

        // 记忆缺失时从消息库重建（服务重启或旧会话续聊场景）
        if (chatMemory.get(conversationId).isEmpty()) {
            rebuildConversationMemory(conversationId, dbSessionId);
        }

        // 保存用户消息到数据库（含会话的初始消息）
        consultationMessageService.saveUserMessage(dbSessionId, userMessage, null);

        //用于存储AI完成的响应
        StringBuilder fullResponse = new StringBuilder();

        // 注：用户消息与AI回复由 MessageChatMemoryAdvisor 自动写入 chatMemory，无需手动 add
        Flux<String> replyChain = chatClient.prompt()
                .user(userMessage)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .take(Duration.ofSeconds(REPLY_MAX_SECONDS))
                .doOnNext(fullResponse::append)
                .doOnCancel(() -> {
                    // 取消仅为显式停止/删除会话而发生（断开不会传播到这里）：残句按契约落库
                    try {
                        if (!handle.isDiscard() && !fullResponse.isEmpty()) {
                            consultationMessageService.saveAimessage(dbSessionId, fullResponse.toString(), "openai");
                        }
                    } catch (Exception e) {
                        log.error("停止时残句落库失败, sessionId={}: {}", dbSessionId, e.getMessage());
                    }
                    activeReplyRegistry.release(userId, handle);
                    handle.emitComplete();
                });

        // 生成启动于订阅时刻（本方法由控制器的 Flux.defer 在订阅时调用，与槽位抢占原子衔接），
        // 返回句柄持有的热观察流：原订阅者与重连观看者共享同一发射历史（replay，ADR 0010）。
        // 热流同时消除旧 Flux.create 冷桥接的可重入地雷（重复订阅=重复 LLM 调用+重复落库）。
        Disposable disposable = replyChain.subscribe(
                handle::emitNext,
                error -> {
                    // 错误不落库，但必须释放槽位
                    activeReplyRegistry.release(userId, handle);
                    handle.emitError(error);
                },
                () -> {
                    // 自然完成或超时收尾：完整回复/残句落库
                    try {
                        if (!fullResponse.isEmpty()) {
                            consultationMessageService.saveAimessage(dbSessionId, fullResponse.toString(), "openai");
                        }
                    } catch (Exception e) {
                        log.error("AI回复落库失败, sessionId={}: {}", dbSessionId, e.getMessage());
                    }
                    activeReplyRegistry.release(userId, handle);
                    handle.emitComplete();
                });
        // 挂载流句柄：停止接口经由注册表定位到此 Disposable；停止先于订阅到达时 attach 内立即终止
        handle.attach(disposable);

        return handle.asFlux();
    }

    /**
     * 从消息库回放最近消息，重建对话记忆（受窗口大小约束）
     */
    private void rebuildConversationMemory(String conversationId, Long sessionId) {
        List<ConsultationMessage> recentMessages = consultationMessageService.getRecentMessages(sessionId, MEMORY_WINDOW_SIZE);
        if (recentMessages.isEmpty()) {
            return;
        }
        // 取出时为 id 倒序，反转为时间正序后回放
        Collections.reverse(recentMessages);
        List<Message> messages = recentMessages.stream()
                .map(message -> message.getSenderType() != null && message.getSenderType() == 2
                        ? (Message) new AssistantMessage(message.getContent())
                        : (Message) new UserMessage(message.getContent()))
                .toList();

        chatMemory.add(conversationId, messages);
    }

    // 获取参数中的sessionId
    public Long extractSessionId(String sessionId) {
        if (sessionId != null && sessionId.startsWith("session_")) {
            String idStr = sessionId.substring("session_".length());
            try {
                return Long.parseLong(idStr);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
