# 01 LLM 情绪分析核心

Status: resolved

## 内容

新建情绪分析核心：分析结果类 + prompt + 分析服务。

### 分析结果类（AiService 包）

- 字段命名干净（Boolean 命名 `negative` 而非 `isNegative`），避免 BeanOutputConverter 生成 JSON schema 时 LLM 输出歧义
- 字段：`primaryEmotion`、`emotionScore`、`negative`、`riskLevel`、`riskDescription`、`suggestion`、`improvementSuggestions`
- 解析后映射到 `SessionEmotionResponseDTO`（`isNegative` ← `negative`）并填 `analyzedAt`

### Prompt（PromptManage 新增常量）

- 输入：`getRecentMessages(sessionId, 20)` 反转为时间正序，标注角色（用户/AI），单条截断 500 字
- 要求：
  - `primaryEmotion`：约 2 字概括**用户**情绪状态（不设词表，AI 回复仅作语境）
  - `negative`：极性判断
  - `suggestion`：基调由极性驱动——负面疏导安抚、正面鼓励保持；`riskLevel=3` 时必须包含干预引导话术
  - `improvementSuggestions`：**仅从窗口内 AI 回复中总结** ≤10 字行动建议 2~3 条，AI 未给过则空列表
  - `emotionScore` 0-100、`riskLevel` 0-3

### 分析服务（如 EmotionAnalysisService）

- `chatClient.prompt(...).entity(分析结果类)` 结构化输出；**不挂 ChatMemory advisor**
- 校验：`emotionScore` 越界钳制到 0-100、`riskLevel` 钳制到 0-3
- 成功后写库：`last_emotion_analysis` = JSON、`last_emotion_updated_at` = now（即原 `touchEmotionUpdatedAt` 的语义更正版：由"被读取时"改为"分析完成时"）

## 验证

- 正常多轮消息 → 结构化输出可解析、7 字段齐全、`analyzedAt` 非空
- 窗口内 AI 未给行动建议 → `improvementSuggestions` 为空列表（非 null）
- LLM 返回越界值 → 钳制后落库
- 分析过程不产生 ChatMemory 写入（对话记忆不受污染）

## Comments

- 2026-09-06 验证通过：真实对话触发分析，结构化输出可解析，情绪花园展示真实短语/分数（非占位"中性/50"）；ChatMemory 不受污染由构造保证（emotion-analysis 专用 ChatClient 未挂 advisor）
- 实现期修正：`.entity()` 在当前 Spring AI 版本需 `.call().entity()` 链式调用
