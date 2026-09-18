# 05 前端：导航解锁、停止按钮、状态恢复与续显

Status: done

## 内容

consultation.vue + frontend.js：

- 导航锁全部移除（切换/新建/删除不再被 isAiTyping 拦截，即用户最初报告的痛点）；发送锁保留，文案改"请等待完成或点击停止"
- startAIResponse 重构：aiMessage 改 reactive 并登记 inflightAiMessages（数字 id → 对象），切回会话时 `[...list, inflight]` 续显——闭包对象仍被 onmessage 更新；done 处理按契约即时解锁（落库与释放先于 done）
- 生成期间发送按钮 → 红色停止按钮（handleStop）：先调 stop API（残句落库后返回）再本地 abort；deliberateStop 标记吞掉 abort 触发的 onerror 误报；正看该会话时 refreshSessionView 立即刷出残句
- 刷新恢复：onMounted → restoreActiveReply()（GET active），有活跃则恢复锁定 + 徽标 + 3s 轮询；轮询发现完成后解锁并刷新视图与列表。本标签页持有流时靠 done 收尾，不起轮询
- 409 对齐：onmessage 收到非 200（含 409 守卫）→ 显示后端 msg → restoreActiveReply() 向服务器状态对齐（活跃可能来自其他标签页）；onerror 同理（断开不取消，服务端可能仍在生成）
- 会话列表"生成中"徽标（activeReplySessionId 匹配项）；删除会话时若是本地流所属会话：后端已终止，本地 abort + 状态清理 + 停轮询
- startNewSession 增加 409 甄别（拦截器对非 200 业务码返回原始 response 不 reject）

## Comments

- done 时情绪卡片刷新加守卫（仅正在查看流所属会话时刷新）——见 06
