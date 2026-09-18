# 01 表结构适配与实体层

Status: resolved

## 内容

表已存在（旧版：一天一条 unique + 无软删字段），执行 spec.md 中的 ALTER 适配，然后新建 entity + mapper。

### ALTER（手动执行，见 spec.md）

- DROP `user_date_unique`（Q2c 一天多条）
- ADD `deleted`（Q4a 软删）
- `dominant_emotion` 改 NOT NULL（Q3b；存量 NULL 数据会失败，先清/补）

### Entity（entity/EmotionDiary.java）

- 对齐 ConsultationSession 模式：`@Data @Builder @NoArgsConstructor @AllArgsConstructor`（无参构造必须显式声明，反序列化坑见项目教训）、`@TableName("emotion_diary")`、`@TableId(type = IdType.AUTO)`
- 字段：id, userId, diaryDate(**LocalDate**), moodScore, dominantEmotion, emotionTriggers, diaryContent, sleepQuality, stressLevel, aiEmotionAnalysis(String 存 JSON), aiAnalysisUpdatedAt(LocalDateTime), deleted(Boolean), createdAt, updatedAt

### Mapper（mapper/EmotionDiaryMapper.java）

- 继承 BaseMapper<EmotionDiary>，本期无自定义 SQL（管理端联表 username/nickname 在 04 中决定）

## 验证

- ALTER 执行成功
- 单测或启动自检：MyBatis Plus 能完成 insert/select roundtrip

## Comments

- 2026-09-07 实现完成：entity（LocalDate diaryDate / Boolean deleted / aiAnalysisUpdatedAt）+ BaseMapper。ALTER 由用户手动执行（表为旧版已有表）
