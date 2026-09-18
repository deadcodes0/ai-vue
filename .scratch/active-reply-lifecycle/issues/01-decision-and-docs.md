# 01 决策与文档：ADR 0009 与词汇表修订

Status: done

## 内容

- 拷问收敛三条契约：断开不取消、停止是显式命令、单活跃流不变量下沉后端；并发语义定为单流（用户确认），多流分区为升级路径
- `docs/adr/0009-disconnect-contract-explicit-stop-multi-stream.md` 新建（Considered Options 含维持现状/多流/断开即取消/残句标记与丢弃四种否决）
- `docs/adr/0008` 标注 superseded：导航锁废止；闭包捕获、done 绑定、新建清空三个修复仍有效
- `CONTEXT.md`：活跃回复词条重定义（per-user 至多一条、导航不受限、断开不终止、可停止）；新增"停止生成"词条（Avoid 与断开连接划界）
