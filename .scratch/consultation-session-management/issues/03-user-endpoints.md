# 03 用户端 4 端点

Status: done

## 内容

PsychologicalChat 控制器新增：
- GET /sessions（pageNum/pageSize）：本人 + 未删除，聚合 lastMessageContent、messageCount、durationMinutes（startedAt 至最后消息，查询时算）
- DELETE /sessions/{id}：软删除，归属不符返回 code 404，幂等
- GET /sessions/{id}/messages：归属校验后按 id 升序返回
- GET /session/{sessionId}/emotion：接受 `session_` 前缀，归属校验，返回 `SessionEmotionResponseDTO` 中性默认值（primaryEmotion/emotionScore/isNegative/riskLevel/riskDescription/suggestion/improvementSuggestions）

新增 `ConsultationSessionResponseDTO`、`SessionEmotionResponseDTO`；ConsultationSessionService 增 findOwnedSession / softDeleteSession / 分页查询 / DTO 组装（N+1 聚合，复用现有 message 服务方法）；ConsultationMessageService 增 listMessagesBySessionId、getRecentMessages。
