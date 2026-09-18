# 04 管理端日记接口

Status: resolved

## 内容

AdminEmotionDiaryController（`/api/admin/emotion-diary`，落入既有 hasRole("2") 规则）。

### GET /api/admin/emotion-diary/page

- 参数：current、size、userId（可空精确）、moodScreRange（可空，"1-3"/"4-6"/"7-10" → moodScore BETWEEN；**参数名照收 typo**）
- 含已删除记录；联表 username/nickname（方案：records 查出后按 userId 批量查 User 组装，避免 XML 联表）
- 行数据：id、userId、username、nickname、diaryDate、moodScore、dominantEmotion、emotionTriggers、diaryContent、sleepQuality、stressLevel、deleted、aiEmotionAnalysis（**JSON 字符串**，emotional.vue 做过 JSON.parse）、createdAt、updatedAt
- 响应 `{ records, total }`（MyBatis Plus Page 形状）

### DELETE /api/admin/emotion-diary/{id}

- 软删：deleted=1；重复删除幂等（已 deleted 仍返回成功）

## 验证

- 无 role=2 的 JWT 访问 → 403
- moodScreRange=4-6 → 只返回 moodScore 4~6 的记录
- 删除后用户端 page 不再出现该条；管理端 page 仍出现且 deleted=true
- 非法 moodScreRange（如 "abc"）→ 忽略该筛选或业务错误，不 500

## Comments

- 2026-09-07 实现完成：/api/admin/emotion-diary（hasRole(2) 覆盖）；moodScreRange 正则解析非法忽略；软删 update where deleted=0 幂等；用户名批量 selectBatchIds。后端 mvnw compile 通过
