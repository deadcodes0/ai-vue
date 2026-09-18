# 03 前端情绪花园接入真实数据

Status: resolved

## 内容

consultation.vue：

1. 删除 `onclose` 中的 `loadSessionEmotion(currentSession.value.sessionId)` 调用（约 line 417）——`done` 已是正确触发点（回复完整且已落库），`onclose` 仅在异常路径代替 done 触发，届时回复残缺、分析无意义。**保留 `done` 内的 `ctrl.abort()`**——它防止 fetch-event-source 在流结束后重连重发 POST，与情绪分析无关，勿删
2. 绑定写死占位（line 21-22）：情绪名"中性" → `currentEmotion.primaryEmotion`；分数"50" → `currentEmotion.emotionScore`
3. `currentEmotion.riskLevel === 3` 时渲染静态危机保底卡片：固定热线信息（如 12356 全国心理援助热线，以实际为准）+ 提示文案；纯静态渲染，不依赖 LLM 输出内容（覆盖分析失败回退旧快照的场景）；与现有"温馨提示"卡片（`riskDescription`）并列或整合
4. 分析中 loading 态：`loadSessionEmotion` 请求期间情绪花园显示加载指示（首次同步分析需等 2~6 秒）
5. `analyzedAt` 时效标注：非空显示"分析于 HH:mm"；null（从未分析，占位数据）显示"暂无分析"

## 验证

- 新对话一轮后 → 情绪花园显示真实短语/分数/建议，非写死的"中性/50"
- 构造危机话术会话（riskLevel=3）→ 显示静态热线卡片
- 点击无新消息的旧会话 → 秒显缓存数据 + 时效标注
- 分析进行中 → loading 态可见，无双份请求（Network 面板确认仅 done 触发一次）

## Comments

- 2026-09-06 实现完成：onclose 冗余调用已删（done 内 ctrl.abort() 保留）、真实数据绑定、危机静态卡片、loading 态、analyzedAt 标注；vite build 通过
- 实现期修复：axios 全局 5s 超时打断首次分析（服务端不知情仍完成写库，故刷新可见结果）→ getSessionEmotion 单独 timeout 60s + .catch 静默
- 浏览器端首析完整路径（60s 修复后 loading→展示）建议日常使用中顺带观察
