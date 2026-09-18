# 03 用户端日记接口

Status: resolved

## 内容

新建 EmotionDiaryController（`/api/emotion-diary`）+ Service + DTO，三个端点。

### POST /api/emotion-diary

- userId 取 JWT；参数校验：diaryDate 非未来（服务端 Asia/Shanghai 判定，格式 yyyy-MM-dd）、moodScore 1-10、dominantEmotion 非空 ≤20、triggers ≤1000、content ≤2000、sleep/stress 1-5 或空
- 流程：落库（deleted=0）→ 调 02 的分析 → 成功则 update ai_emotion_analysis → 响应携带完整 DTO（aiEmotionAnalysis 解析为对象）
- 分析失败：日记保留、字段 null、HTTP 200 正常返回，**不重试**
- 无需并发去重（提交天然一次性）

### GET /api/emotion-diary/page

- 参数 current/size；按 diary_date desc, id desc 倒序；过滤 deleted=1；只查 JWT 用户
- aiEmotionAnalysis 解析为对象（复用 SessionEmotionResponseDTO 结构或新建 DiaryEmotionAnalysisDTO）

### GET /api/emotion-diary/{id}

- 归属校验：userId 不符或 deleted=1 → 404（防枚举，对齐会话先例）

## 验证

- 未来日期提交 → 业务错误
- moodScore 缺失 / dominantEmotion 空 → 业务错误
- 正常提交 → 响应含分析对象；DB 中 JSON 落库
- 模拟 LLM 失败 → 日记仍保存、aiEmotionAnalysis null、响应 200
- 用户 A 查用户 B 的日记 id → 404

## Comments

- 2026-09-07 实现完成：POST（未来日期 BusinessException、分析失败不阻塞不重试）/ page（倒序+过滤已删除）/ detail（404 防枚举）。响应 aiEmotionAnalysis 为已解析对象

- 2026-09-07 追加：POST /{id}/analysis/retry 失败恢复接口——仅当 aiEmotionAnalysis 为 null 时可触发（实体前置校验 + UPDATE ... WHERE ai_emotion_analysis IS NULL 双重保证并发下不覆盖已有结果，守住 ADR-0005 不变量）；LLM 再失败抛 BusinessException（用户主动触发的操作须明确告知失败，区别于提交时的静默降级）；404 防枚举同其他端点。前端：EmotionAnalysisCard 缺失态加「重试分析」按钮（emit 事件，父控 loading），提交结果弹窗与历史详情弹窗共用；60s 超时。ADR-0005 已修订（不自动重试→不自动重试但允许手动失败恢复），CONTEXT.md 术语同步精化
