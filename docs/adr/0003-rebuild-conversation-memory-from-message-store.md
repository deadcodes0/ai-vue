# AI 对话记忆按需从消息库重建

对话记忆当前是 `MessageWindowChatMemory`（纯 JVM 内存，30 条窗口），后端重启全员失忆、切回旧会话续聊 AI 失忆。决定不引入持久化记忆存储，而是在 stream 链路加守卫：会话的 conversationId 不在内存时，从 `consultation_message` 表回放消息重建记忆，再调 LLM。

## Considered Options

- Spring AI 的 JDBC ChatMemoryRepository（独立记忆表）：被否决——与 `consultation_message` 数据重复且易漂移，消息表本身就该是唯一事实来源。
- 不处理（保持纯内存）：被否决——"点开旧会话续聊"是会话管理模块亲手交付的入口，通向失忆的 AI 等于交付半残功能。

## Consequences

- stream 路径多一个内存检查 + 回放步骤，回放受 30 条窗口约束（与现状一致，不放大）。
- 现有"初始消息去重"（`messageCount == 1` 比对内容的 hack）与回放逻辑有交互，需一并清理。
