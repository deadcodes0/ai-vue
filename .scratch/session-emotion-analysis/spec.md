# 会话情绪分析模块

## 背景与范围

将 GET /api/psychological-chat/session/{id}/emotion 从占位实现（恒返回中性默认值）升级为基于 LLM 的真实情绪分析。本期消费方仅用户端咨询页（情绪花园）；管理端、情绪日记的 `aiEmotionAnalysis` 渲染位留待各自模块复用同一契约。决策记录见 `docs/adr/0004`，术语见 `CONTEXT.md`。

## 决策摘要

- **懒计算+缓存（ADR-0004）**：GET 时比较最新消息 `created_at` 与 `last_emotion_updated_at`；新鲜 → 直接返回缓存 JSON（零 LLM 调用）；过期/从未分析 → 同步分析 → 写库 → 返回。GET 中的 `touchEmotionUpdatedAt()` 调用移除（时间戳语义更正为"分析完成于"）
- **分析窗口**：最近 20 条消息（用户+AI），单条截断 500 字
- **LLM 结构化输出**：Spring AI `.entity()`；专用分析结果类（Boolean 字段命名 `negative`，避免 `isNegative` 的 schema 歧义）；分析调用不挂 ChatMemory advisor（直接拼窗口进 prompt，不污染对话记忆、不依赖记忆重建）
- **失败降级**：返回旧快照；从未分析 → `neutral()`；HTTP 恒 200；半成品不落库
- **并发去重**：`ConcurrentHashMap<sessionId, Future<快照>>`，分析进行中的并发 GET join 同一 Future；异常时移除 entry（不毒化后续请求）
- **危机干预（riskLevel=3）**：前端静态保底卡片（固定热线信息，不依赖 LLM）+ prompt 要求 `suggestion` 含个性化干预话术；后端不做硬编码追加
- **契约新增 `analyzedAt`**：区分"从未分析"（null → 中性占位）与"旧快照"，前端可标注时效
- **`primaryEmotion`**：LLM 自由短语（约 2 字）概括用户情绪状态，不设词表（主要依据用户消息，AI 回复作语境）
- **`improvementSuggestions`**：从窗口内 AI 回复中总结的 ≤10 字行动建议，2~3 条，无则空列表
- **`suggestion` 基调由极性驱动**：负面 → 疏导安抚；正面 → 鼓励保持

## 契约（SessionEmotionResponseDTO）

| 字段 | 类型 | 语义 |
|---|---|---|
| primaryEmotion | String | 约 2 字情绪短语，概括用户近期情绪状态 |
| emotionScore | Integer | 0-100 情绪评分 |
| isNegative | Boolean | 极性，驱动 suggestion 基调 |
| riskLevel | Integer | 0 正常 / 1 关注 / 2 预警 / 3 危机 |
| riskDescription | String | 风险描述 |
| suggestion | String | 温暖建议（负面疏导 / 正面鼓励） |
| improvementSuggestions | List&lt;String&gt; | 从 AI 回复总结的 ≤10 字行动建议，2~3 条可空 |
| analyzedAt | LocalDateTime | 分析完成时间；null 表示从未分析 |

## 处理流程

```
GET /session/{id}/emotion
 1. 归属校验（现有逻辑不动；已删除会话 404）
 2. 空会话（无消息）→ neutral()，不调 LLM
 3. 新鲜度判断：最新消息 created_at ≤ last_emotion_updated_at 且快照非空？
      是 → 解析缓存 JSON 返回（含 analyzedAt）
      否 → 4
 4. 并发去重下执行分析：computeIfAbsent 放入 Future 并启动
      → chatClient.entity(分析结果类)（不挂 ChatMemory）
      → 校验（emotionScore 0-100、riskLevel 0-3，越界钳制）
      → 写库：last_emotion_analysis=JSON、last_emotion_updated_at=now
      → 完成/异常后移除 Future entry
 5. 降级：分析失败/不可解析 → 旧快照（若有）；从未分析 → neutral()
```

## 数据库变更

无。`last_emotion_analysis`（String，存 JSON）与 `last_emotion_updated_at`（datetime）字段已存在，语义变更见 ADR-0004。

## 前端变更（consultation.vue）

- 删除 `onclose` 中 `loadSessionEmotion` 冗余调用（`done` 是正确触发点；**保留 done 内 `ctrl.abort()`**——它防 fetch-event-source 结束后重连重发，与情绪分析无关）
- 绑定写死的"中性"/"50"（line 21-22）→ `currentEmotion.primaryEmotion` / `currentEmotion.emotionScore`
- `riskLevel===3` 时渲染静态危机保底卡片（固定热线信息，如 12356 全国心理援助热线，以实际为准）
- 分析中 loading 态（首次同步分析 2~6 秒）
- `analyzedAt` 时效标注（如"分析于 14:32"）；null 显示"暂无分析"

## Issues

- issues/01-llm-analysis-core.md
- issues/02-emotion-endpoint-rework.md
- issues/03-frontend-emotion-garden.md
