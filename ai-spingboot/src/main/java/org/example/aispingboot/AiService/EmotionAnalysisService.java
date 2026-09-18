package org.example.aispingboot.AiService;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.example.aispingboot.DTO.response.ConsultationMessageResponseDTO;
import org.example.aispingboot.DTO.response.SessionEmotionResponseDTO;
import org.example.aispingboot.entity.ConsultationMessage;
import org.example.aispingboot.entity.ConsultationSession;
import org.example.aispingboot.entity.EmotionDiary;
import org.example.aispingboot.mapper.ConsultationSessionMapper;
import org.example.aispingboot.service.ConsultationMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话情绪分析服务：懒计算+缓存（ADR-0004）
 * GET 时比较最新消息 created_at 与 last_emotion_updated_at：新鲜则直接返回库中快照；
 * 过期/从未分析才同步执行 LLM 分析并写库。分析失败降级返回旧快照或中性默认值。
 */
@Service
public class EmotionAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(EmotionAnalysisService.class);

    @Autowired
    @Qualifier("emotion-analysis")
    private ChatClient chatClient;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    @Autowired
    private ConsultationSessionMapper consultationSessionMapper;

    // 分析窗口：最近 20 条消息
    private static final int ANALYSIS_WINDOW_SIZE = 20;
    // 单条消息截断长度
    private static final int MAX_MESSAGE_LENGTH = 500;
    // 单次分析超时（秒）
    private static final long ANALYSIS_TIMEOUT_SECONDS = 60;

    // 进行中的分析任务（并发去重：同一会话的并发请求 join 同一个 Future）
    private final ConcurrentHashMap<Long, FutureTask<SessionEmotionResponseDTO>> inFlightAnalysis = new ConcurrentHashMap<>();

    /**
     * 获取会话情绪快照：缓存新鲜直接返回，否则执行分析后返回
     */
    public SessionEmotionResponseDTO getOrAnalyzeEmotion(Long sessionId) {
        ConsultationSession session = consultationSessionMapper.selectById(sessionId);

        // 空会话：无消息可分析
        ConsultationMessageResponseDTO lastMessage = consultationMessageService.getLastMessageBySessionId(sessionId);
        if (lastMessage == null) {
            return SessionEmotionResponseDTO.neutral();
        }

        // 缓存新鲜：最新消息不晚于上次分析完成时间
        if (session != null && StrUtil.isNotBlank(session.getLastEmotionAnalysis())
                && session.getLastEmotionUpdatedAt() != null
                && !lastMessage.getCreatedAt().isAfter(session.getLastEmotionUpdatedAt())) {
            SessionEmotionResponseDTO cached = parseSnapshot(session.getLastEmotionAnalysis());
            if (cached != null) {
                return cached;
            }
        }

        // 缓存过期/从未分析：并发去重下执行分析
        FutureTask<SessionEmotionResponseDTO> task = new FutureTask<>(() -> analyzeSession(sessionId));
        FutureTask<SessionEmotionResponseDTO> existing = inFlightAnalysis.putIfAbsent(sessionId, task);
        if (existing == null) {
            // 首个请求：当前线程直接执行
            try {
                task.run();
            } finally {
                inFlightAnalysis.remove(sessionId, task);
            }
        } else {
            // 已有进行中的分析，join 同一任务
            task = existing;
        }
        try {
            return task.get(ANALYSIS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return degradeToSnapshot(session);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("会话 {} 情绪分析失败，降级返回旧快照: {}", sessionId, e.getMessage());
            return degradeToSnapshot(session);
        }
    }

    /**
     * 对会话近期消息执行 LLM 情绪分析，成功后写入情绪快照
     */
    private SessionEmotionResponseDTO analyzeSession(Long sessionId) {
        List<ConsultationMessage> recentMessages =
                consultationMessageService.getRecentMessages(sessionId, ANALYSIS_WINDOW_SIZE);
        // 取出为 id 倒序，反转为时间正序
        Collections.reverse(recentMessages);

        EmotionAnalysisResult result = chatClient.prompt()
                .system(PromptManage.EMOTION_ANALYSIS_SYSTEM_PROMPT)
                .user(formatMessages(recentMessages))
                .call()
                .entity(EmotionAnalysisResult.class);

        SessionEmotionResponseDTO dto = toResponseDTO(result);
        saveSnapshot(sessionId, dto);
        return dto;
    }

    /**
     * 日记情绪分析（ADR-0005：提交时同步一次性执行，无缓存/失效重算，无需并发去重）。
     * 失败由调用方降级（日记保留、字段 null），本方法直接抛出异常。
     */
    public SessionEmotionResponseDTO analyzeDiary(EmotionDiary diary) {
        EmotionAnalysisResult result = chatClient.prompt()
                .system(PromptManage.DIARY_EMOTION_ANALYSIS_SYSTEM_PROMPT)
                .user(formatDiaryMaterial(diary))
                .call()
                .entity(EmotionAnalysisResult.class);
        return toResponseDTO(result);
    }

    /**
     * 将日记素材格式化为分析输入文本（空字段标注"未填写"）
     */
    private String formatDiaryMaterial(EmotionDiary diary) {
        return "以下是一篇情绪日记，请分析日记主人的情绪状态：\n\n" +
                "日记日期：" + diary.getDiaryDate() + "\n" +
                "自评情绪分（1-10，主观）：" + diary.getMoodScore() + "\n" +
                "主要情绪：" + StrUtil.blankToDefault(diary.getDominantEmotion(), "未填写") + "\n" +
                "情绪触发因素：" + StrUtil.blankToDefault(diary.getEmotionTriggers(), "未填写") + "\n" +
                "今日感想：" + StrUtil.blankToDefault(diary.getDiaryContent(), "未填写") + "\n" +
                "睡眠质量（1-5）：" + (diary.getSleepQuality() != null ? diary.getSleepQuality() : "未填写") + "\n" +
                "压力水平（1-5）：" + (diary.getStressLevel() != null ? diary.getStressLevel() : "未填写");
    }

    /**
     * 将窗口内消息格式化为分析输入文本（角色标注 + 单条截断）
     */
    private String formatMessages(List<ConsultationMessage> messages) {
        StringBuilder sb = new StringBuilder("以下是心理咨询对话记录（按时间正序），请分析其中用户的情绪状态：\n\n");
        for (ConsultationMessage message : messages) {
            String role = message.getSenderType() != null && message.getSenderType() == 2 ? "AI助手" : "用户";
            String content = message.getContent() == null ? "" : message.getContent();
            if (content.length() > MAX_MESSAGE_LENGTH) {
                content = content.substring(0, MAX_MESSAGE_LENGTH);
            }
            sb.append(role).append("：").append(content).append('\n');
        }
        return sb.toString();
    }

    /**
     * 映射 LLM 结构化结果到响应契约（越界值钳制，空值兜底）
     */
    private SessionEmotionResponseDTO toResponseDTO(EmotionAnalysisResult result) {
        SessionEmotionResponseDTO dto = new SessionEmotionResponseDTO();
        dto.setPrimaryEmotion(StrUtil.blankToDefault(result.primaryEmotion(), "中性"));
        dto.setEmotionScore(clamp(result.emotionScore(), 0, 100, 50));
        dto.setIsNegative(Boolean.TRUE.equals(result.negative()));
        dto.setRiskLevel(clamp(result.riskLevel(), 0, 3, 0));
        dto.setRiskDescription(StrUtil.emptyIfNull(result.riskDescription()));
        dto.setSuggestion(StrUtil.emptyIfNull(result.suggestion()));
        dto.setImprovementSuggestions(result.improvementSuggestions() != null
                ? result.improvementSuggestions() : new ArrayList<>());
        dto.setAnalyzedAt(LocalDateTime.now());
        return dto;
    }

    /**
     * 写入情绪快照：last_emotion_analysis(JSON) + last_emotion_updated_at(分析完成时间)
     */
    private void saveSnapshot(Long sessionId, SessionEmotionResponseDTO dto) {
        ConsultationSession update = new ConsultationSession();
        update.setId(sessionId);
        update.setLastEmotionAnalysis(JSONUtil.toJsonStr(dto));
        update.setLastEmotionUpdatedAt(dto.getAnalyzedAt());
        consultationSessionMapper.updateById(update);
    }

    /**
     * 分析失败降级：返回旧快照（若有），否则中性默认值
     */
    private SessionEmotionResponseDTO degradeToSnapshot(ConsultationSession session) {
        if (session != null && StrUtil.isNotBlank(session.getLastEmotionAnalysis())) {
            SessionEmotionResponseDTO old = parseSnapshot(session.getLastEmotionAnalysis());
            if (old != null) {
                return old;
            }
        }
        return SessionEmotionResponseDTO.neutral();
    }

    private SessionEmotionResponseDTO parseSnapshot(String json) {
        try {
            return JSONUtil.toBean(json, SessionEmotionResponseDTO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private int clamp(Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
