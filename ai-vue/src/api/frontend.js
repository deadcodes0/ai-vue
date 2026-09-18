import service from '@/utils/request'

export const register = (data) => {
    return service.post('/user/add', data)
}

export const startSession = (data) => {
    return service.post('/psychological-chat/session/start', data)
}

export const getSessionList = (params) => {
    return service.get('/psychological-chat/sessions', { params })
}

export const deleteSession = (sessionId) => {
    return service.delete(`/psychological-chat/sessions/${sessionId}`)
}

export const getSessionDetail = (sessionId) => {
    return service.get(`/psychological-chat/sessions/${sessionId}/messages`)
}

export const getSessionEmotion = (sessionId) => {
    // 首次分析同步等待 LLM（2~6秒以上），放宽至与后端分析超时一致的 60 秒（覆盖全局 5 秒超时）
    return service.get(`/psychological-chat/session/${sessionId}/emotion`, { timeout: 60000 })
}

export const stopActiveReply = () => {
    // 显式停止（ADR 0009）：停止是命令而非断开连接；残句由后端落库后返回
    return service.post('/psychological-chat/stream/stop')
}

export const getActiveReply = () => {
    // 活跃回复状态查询（ADR 0009）：刷新后恢复输入锁定态、会话列表"生成中"徽标
    return service.get('/psychological-chat/stream/active')
}

export const addEmotionDiary = (data) => {
    // 提交时后端同步执行 LLM 分析（ADR-0005），放宽至 60 秒（覆盖全局 5 秒超时）
    return service.post('/emotion-diary', data, { timeout: 60000 })
}

export const getEmotionDiaryPage = (params) => {
    return service.get('/emotion-diary/page', { params })
}

export const retryEmotionDiaryAnalysis = (id) => {
    // 同步重试 LLM 分析（仅失败恢复；已有结果后端拒绝重算），放宽至 60 秒
    return service.post(`/emotion-diary/${id}/analysis/retry`, {}, { timeout: 60000 })
}

export const getKnowledgeList = (params) => {
    return service.get('/knowledge/article/page', { params })
}

export const getKnowledgeDetail = (articleId) => {
    return service.get(`/knowledge/article/${articleId}`)
}