# 06 测试反馈修复：流式重复请求 + 情绪时间戳

Status: done

## Bug 1：同一条消息重复入库、AI 重复回复

根因：`@microsoft/fetch-event-source` 的 visibilitychange 机制——切走标签页中断 SSE，切回时用原请求体重新 POST `/stream`，后端每次都完整执行 saveUserMessage + LLM 调用。中断路径跳过库的清理逻辑，监听器持续存活，随每次切换标签页重复触发。

修复：consultation.vue 的 fetchEventSource 增加 `openWhenHidden: true`，禁止切页重发。

## Bug 2：last_emotion_updated_at 恒为 null

按 Q4=c 共识情绪分析为占位实现，此前无任何写入。修复：emotion 端点被调用时更新该字段（情绪快照服务时间）。真实情绪分析生产逻辑接入后应在分析完成处写入。

## 附：AI 声称"没有长期记忆"

机制验证（MessageChatMemoryAdvisor 字节码）：before() 读记忆注入 prompt 并写入用户消息，after() 写入 AI 回复——记忆链路完整。16:53 的失忆回复疑为 LLM 对"你记得吗"类问题的习惯性否认，待复测：重启后端后切回旧会话问"我们刚才聊了什么"，若不能复述则需进一步排查。

## 验证

- 后端 `mvn compile` BUILD SUCCESS
- 复测指引见对话记录：多轮记忆 + 切标签页不再重复 + 重启后记忆恢复
