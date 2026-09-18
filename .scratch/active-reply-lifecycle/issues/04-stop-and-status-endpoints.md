# 04 停止命令与活跃状态查询端点

Status: done

## 内容

- `POST /stream/stop`：requestStop(userId, discard=false)，同步等待 doOnCancel 落残句、释放槽位完成后返回；无活跃回复 404。停止是命令而非断开连接（传输层二者同为连接断开，不可依赖连接事件实现）
- `GET /stream/active`：返回 ActiveReplyStatusDTO(sessionId, startedAt)，无活跃回复 data=null。供前端刷新后恢复输入锁定态、会话列表"生成中"徽标
- 两端点均落在 anyRequest().authenticated() 下，JWT 过滤器自动覆盖，无安全配置变更
