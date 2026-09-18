# 05 stream 链路修复

Status: done

## 内容

PsychologicalChat.streamChat 与 PsychologicalSupportService.streamPsychologicalChat：
- 控制器先校验：会话存在且属于当前用户（否则 SSE error 事件"会话不存在"）、未删除（否则"会话已删除"）
- 记忆重建：conversationId 在 ChatMemory 中为空时，从 consultation_message 回放最近 30 条（按 id 倒序取后反转）重建
- 初始消息保存从 startSession 移入 stream（消灭 messageCount==1 内容比对去重 hack；startSession 返回的 messageCount 改为 0）
- 删除两处手动 chatMemory.add（Spring AI 1.0.0 MessageChatMemoryAdvisor 的 before/after 已自动写回用户消息与 AI 回复——字节码已验证，手动加是重复写入）
- consultation.vue SSE 错误提示改读 payload.msg（原 payload.message 恒为 undefined）
