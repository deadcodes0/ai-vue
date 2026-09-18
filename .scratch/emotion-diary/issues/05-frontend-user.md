# 05 前端：提交页升级 + 历史查看

Status: resolved

## 内容

改造 [emotionDiary.vue](../../../ai-vue/src/views/emotionDiary.vue)，均在本文件内完成（不新增路由/页面）。

### 提交区

- diaryDate 加日期选择器（el-date-picker，禁未来日期，默认今天）
- 提交按钮 loading 态；api/frontend.js 的 addEmotionDiary 加 `timeout: 60000`（同步分析耗时，复用 getSessionEmotion 先例）
- 提交成功后展示 AI 分析结果卡片：primaryEmotion、emotionScore（el-progress）、riskLevel、isNegative、suggestion、improvementSuggestions；aiEmotionAnalysis 为 null 时显示"分析暂不可用"
- riskLevel=3：渲染静态危机保底红卡（12356 全国心理援助热线，复用 consultation.vue 的做法，不依赖 LLM 字段）
- dominantEmotion 校验补齐（前端必填提示，与后端约束一致）

### 历史 tab

- el-tabs：「写日记 / 我的日记」
- 我的日记：getEmotionDiaryPage（api/frontend.js 新增，GET /emotion-diary/page）倒序列表 + 分页；条目展示日期/评分/主要情绪/生活指标；点击展开或弹窗详情（含 AI 分析结果卡片，复用提交区的展示组件逻辑）

## 验证

- 选未来日期被禁；提交后 loading 数秒出分析卡片
- riskLevel=3 日记 → 红卡出现
- 历史 tab 能看到本人全部日记（不含已删除）
- 网络超时/分析缺失 → "分析暂不可用"，不白屏不报 Uncaught

## Comments

- 2026-09-07 实现完成：日期选择器（禁未来）、moodScore/dominantEmotion/diaryDate 三重前端校验、提交 loading + 60s 超时；新建 EmotionAnalysisCard.vue 组件（提交结果与历史详情共用，含 12356 危机静态红卡）；el-tabs 写日记/我的日记，历史懒加载 + 分页 + 详情弹窗。vite build 通过

- 2026-09-07 UX 追加：提交等待改为弹窗承载（spinner + 预期耗时提示），用户可「切到后台」——请求继续在飞行中（后端仍同步一次性分析，ADR-0005 不变），完成后 ElNotification 告知；后台模式下 riskLevel=3 用常驻（duration 0）红色通知兜底 12356；切后台时快照表单并清空（防重复提交），请求整体失败可恢复快照

- 2026-09-07 UX 追加 2：等待/结果共用一个弹窗——分析完成后弹窗原地从 spinner 切换为 EmotionAnalysisCard 结果展示（标题联动），关闭即清除，表单页不再有常驻分析卡片；前台业务错误补关弹窗（原会停在 spinner）；危机 12356 常驻通知仅在后台模式发（前台弹窗卡片已含红卡，避免双重提示）

- 2026-09-07 UX 追加 3：后台模式危机提示从 duration:0 常驻 ElNotification 改为 ElMessageBox.alert 模态框——通知堆叠会上移补位且被高 z-index 固定元素覆盖；模态遮罩保证置顶、须显式确认（closeOnClickModal/ESC 均 false）。危机时不再弹普通成功通知（喧宾夺主），保存信息并入模态文案
