# 02 日记情绪分析核心

Status: resolved

## 内容

日记专属 prompt + 分析调用，产出复用 `EmotionAnalysisResult`（7 字段不动）。

### Prompt（PromptManage 新增常量）

- 输入素材：自评情绪分、主要情绪、触发因素、感想、睡眠质量、压力水平（空字段跳过或标"未填写"）
- 要求：
  - `primaryEmotion`：约 2 字概括日记主人情绪状态
  - `negative`：极性判断（结合自评与文本）
  - `suggestion`：基调由极性驱动——负面疏导安抚、正面鼓励保持；`riskLevel=3` 时必须包含干预引导话术
  - `improvementSuggestions`：**基于日记内容**的 ≤10 字具体行动建议 2~3 条，素材过少则空列表（注意：语义与会话分析不同——不是"从 AI 回复总结"）
  - `emotionScore` 0-100、`riskLevel` 0-3
- 日记文本短，无需截断逻辑（字段长度上限已保证）

### 分析调用（EmotionAnalysisService 新增方法或独立 DiaryEmotionAnalysisService）

- 复用现有无 ChatMemory 的分析 ChatClient（`.call().entity(EmotionAnalysisResult)`）
- 钳制：emotionScore→0-100、riskLevel→0-3
- 无并发去重需求（每次提交只分析一次，无重复触发源）
- 返回序列化 JSON 字符串供落库；调用方决定失败时的降级（03）

## 验证

- 正常日记 → 7 字段齐全可解析
- 极简日记（只有 moodScore+dominantEmotion）→ 不报错，improvementSuggestions 可为空列表
- LLM 返回越界值 → 钳制
- 不产生 ChatMemory 写入

## Comments

- 2026-09-07 实现完成：DIARY_EMOTION_ANALYSIS_SYSTEM_PROMPT + EmotionAnalysisService.analyzeDiary()，复用 emotion-analysis ChatClient（无 ChatMemory）与 toResponseDTO（越界钳制）
