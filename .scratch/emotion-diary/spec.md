# 情绪日记模块

## 背景与范围

补齐情绪日记全链路：用户端提交（[emotionDiary.vue](../../../ai-vue/src/views/emotionDiary.vue) 已有表单骨架）+ 提交时同步 AI 分析落库 + 用户端历史查看（新增）+ 管理端分页/详情/软删（[emotional.vue](../../../ai-vue/src/views/emotional.vue) 已有页面）。后端 `emotion_diary` 表与全套 entity/mapper/service/controller 均缺失。决策记录见 `docs/adr/0005`，术语见 `CONTEXT.md` 情绪日记域。

## 决策摘要

- **同步一次性分析（ADR-0005）**：POST 内 保存 → LLM 分析 → 落库 → 响应携带完整结果。危机场景（riskLevel=3）红卡当场交付。失败不阻塞保存，`ai_emotion_analysis` 置 null，**不重试**（日记不可变，无失效重算语义）
- **不可变日记**：无编辑、无用户删除；写错再写一条（同日多条允许）。管理端软删对齐 ADR-0001
- **日期规则**：`user_id + diary_date` **无唯一约束**（一天多条）；服务端按 Asia/Shanghai 拒绝未来日期；允许补写过去
- **必填**：moodScore(1-10) + dominantEmotion（≤20 字，**不做枚举校验**——前端提交页 8 选项与管理端 tag 映射存在漂移，后端不背这个锅）；triggers(≤1000)/content(≤2000)/sleep(1-5)/stress(1-5) 可空
- **双度量并存**：自评情绪分 moodScore(1-10) 与 AI 情绪强度 emotionScore(0-100) 语义不同，不换算（CONTEXT.md 已钉死）
- **分析结果结构**：复用 `EmotionAnalysisResult`（7 字段含 riskDescription、improvementSuggestions）；prompt 为日记专属素材；improvementSuggestions 语义调整为"基于日记内容的具体行动建议"（非"从 AI 回复总结"）
- **管理端路径修正**：前端 admin.js 从 `/emotion-diary/admin/*` 改为 `/admin/emotion-diary/*`，落回 `/api/admin/**` 保护规则（ADR-0002），否则现有 SecurityConfig 不覆盖
- **`moodScreRange` 照收**：管理端筛选参数名的 typo（少个 o）不改前端，后端按此名接收，解析为 BETWEEN

## 契约

### POST /api/emotion-diary（用户端）

请求（userId 一律取 JWT，不收前端值）：

| 字段 | 类型 | 约束 |
|---|---|---|
| diaryDate | String (yyyy-MM-dd) | 必填，非未来（服务端时区判定） |
| moodScore | Integer | 必填 1-10 |
| dominantEmotion | String | 必填 ≤20 字 |
| emotionTriggers | String | 可空 ≤1000 |
| diaryContent | String | 可空 ≤2000 |
| sleepQuality | Integer | 可空 1-5 |
| stressLevel | Integer | 可空 1-5 |

响应：创建后的日记 DTO，`aiEmotionAnalysis` 为**已解析对象**（非 JSON 字符串），前端直接渲染 + riskLevel=3 触发红卡；分析失败时该字段为 null。

### GET /api/emotion-diary/page（用户端，新增）

参数：current、size。返回本人日记倒序分页（按 diary_date desc, id desc），**过滤已删除**。`aiEmotionAnalysis` 同样解析为对象。

### GET /api/emotion-diary/{id}（用户端，新增）

详情。归属校验（userId 不符返回 404 防枚举，对齐会话先例）；已删除 404。

### GET /api/admin/emotion-diary/page（管理端）

参数：current、size、userId（可空，精确匹配）、moodScreRange（可空，"1-3"/"4-6"/"7-10" 解析为 moodScore BETWEEN）。含已删除记录，联表 username/nickname，行数据携带 `aiEmotionAnalysis` 为 **JSON 字符串**（emotional.vue 既有 `JSON.parse` 契约）。响应形如 MyBatis Plus Page：`{ records, total }`。

### DELETE /api/admin/emotion-diary/{id}（管理端）

软删除（deleted=1）。

## 处理流程

```
POST /api/emotion-diary
 1. JWT 取 userId；参数校验（含 diaryDate 非未来）
 2. 落库（deleted=0, ai_emotion_analysis=null）
 3. 组装日记素材 prompt → diaryChatClient（无 ChatMemory）.call().entity(EmotionAnalysisResult)
 4. 钳制（emotionScore 0-100、riskLevel 0-3）→ ai_emotion_analysis = JSON
 5. 任何一步分析失败 → 日记保留、字段 null、响应正常返回（HTTP 200，分析缺失由前端兜底）
```

## 数据库变更

表已存在（旧版设计），ALTER 适配共识（项目无 migration 机制，手动执行）：

```sql
ALTER TABLE emotion_diary
    DROP INDEX user_date_unique,                                              -- Q2c 一天多条
    ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记（管理端）' AFTER ai_emotion_analysis,  -- Q4a
    MODIFY COLUMN dominant_emotion VARCHAR(50) NOT NULL COMMENT '主要情绪（必填）';  -- Q3b
```

存量数据含 `dominant_emotion IS NULL` 时 MODIFY 失败，先清/补值。

**保留的旧结构差异（判定无害）**：
- `emotion_triggers`/`diary_content` 为 text：长度上限由后端 @Size 校验兜住
- `ai_analysis_updated_at`：保留并启用——分析成功时写入，作为 analyzedAt 的落库来源
- 外键 `ON DELETE CASCADE`（user）：仅物理删用户时触发（本系统不物理删用户），保留
- `dominant_emotion varchar(50)`：表宽后端严（校验 ≤20）

entity 字段：id, userId, diaryDate(LocalDate), moodScore, dominantEmotion, emotionTriggers, diaryContent, sleepQuality, stressLevel, aiEmotionAnalysis(String JSON), aiAnalysisUpdatedAt(LocalDateTime), deleted(Boolean), createdAt, updatedAt。

## 前端变更

- **emotionDiary.vue（提交页）**：加日期选择器（禁未来日期，默认今天）；提交按钮 loading 态 + 请求超时 60s（复用 getSessionEmotion 先例）；提交成功后展示 AI 分析结果卡片（情绪/强度/风险/建议/改善建议）；riskLevel=3 渲染 12356 红卡（复用 consultation.vue 静态保底逻辑）
- **emotionDiary.vue（历史 tab）**：页内加 el-tabs「写日记 / 我的日记」，历史 tab 列表 + 详情展示（含分析结果），不新增路由
- **admin.js**：`/emotion-diary/admin/page` → `/admin/emotion-diary/page`；`/emotion-diary/admin/{id}` → `/admin/emotion-diary/{id}`
- **emotional.vue**：已删除标记列；修掉"会话ID"列的会话管理页复制残留（显示用户昵称/用户名）；tag 映射统一为提交页 8 选项（开心/平静/焦虑/悲伤/兴奋/疲惫/惊讶/困惑）

## Issues

- issues/01-ddl-and-entity.md
- issues/02-diary-analysis.md
- issues/03-user-api.md
- issues/04-admin-api.md
- issues/05-frontend-user.md
- issues/06-frontend-admin.md
