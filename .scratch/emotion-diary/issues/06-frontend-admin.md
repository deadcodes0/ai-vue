# 06 前端：管理端页面修正

Status: resolved

## 内容

修正 [emotional.vue](../../../ai-vue/src/views/emotional.vue) 与 [admin.js](../../../ai-vue/src/api/admin.js)。

### admin.js 路径修正（安全相关，必做）

- `/emotion-diary/admin/page` → `/admin/emotion-diary/page`
- `/emotion-diary/admin/${id}` → `/admin/emotion-diary/${id}`
- 原路径不匹配后端 `/api/admin/**` 保护规则（ADR-0002），接口裸奔

### emotional.vue

- 已删除标记列（deleted=true 显示 tag）
- "会话ID"列是会话管理页复制残留（显示的是 nickname avatar）：改为显示用户名/昵称
- getEmotionTagType / getAiEmotionTagType 映射统一为提交页 8 选项：开心/平静/焦虑/悲伤/兴奋/疲惫/惊讶/困惑（删掉映射外的"快乐""愤怒"，补齐缺失项）
- 详情弹窗 AI 分析区已兼容 null（aiData={} 兜底），确认 JSON.parse 对后端返回的 JSON 字符串正常工作即可

## 验证

- 管理员登录后列表/筛选/删除/详情全链路走通
- 非管理员 token 直接 curl /api/admin/emotion-diary/page → 403
- 软删记录带已删除标记，用户端同步消失

## Comments

- 2026-09-07 实现完成：admin.js 路径改为 /admin/emotion-diary/*（落回 /api/admin/** 保护规则）；用户ID列错绑 id 修正（原 prop=id 实为日记ID）、'会话ID'列复制残留改为用户列、新增状态列（已删除/正常）；riskLevel tag 修复（原把数字传进字符串 key 的 map 恒灰，改用既有 getRiskLevelTagType/Text）；getEmotionTagType 统一为提交页 8 选项；分析缺失态兜底（ADR-0005 合法状态）。偏差说明：getAiEmotionTagType 保留宽词表并扩充常见短语——primaryEmotion 是 LLM 自由短语不设词表（ADR-0004），不能限制为 8 选项
