# 03 流生命周期重构（断开契约 + 残句 + 超时）

Status: done

## 内容

`PsychologicalSupportService.streamPsychologicalChat` 重写，契约集中注释在方法上方：

- 断开不取消：内层 LLM 订阅（replyChain.subscribe）不接入外层 sink 的取消生命周期——客户端断开只终止 SSE 发射，内层跑完并完整落库。有意的不作为，勿"优化"成接线取消
- 取消仅为显式命令：doOnCancel 落残句（handle.isDiscard() 时跳过——删除会话场景），随后 release 槽位、sink.complete()（停止可能来自其他标签页，本连接须经 done 得知终止）
- take(Duration 120s)：超时按停止语义收尾，走 complete 路径残句落库
- 顺序契约：落库 → 释放槽位 → sink.complete()/done。收到 done 即可续聊不撞 409；落库先于释放，新流用户消息不会排到上一条 AI 回复之前
- 错误路径不落库（部分落库仅属显式停止与超时，ADR-0009），但必须 release 槽位
- 落库与注册表操作全部 try-catch：落库失败不阻塞槽位释放，避免死锁用户
