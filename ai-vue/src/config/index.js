// 文件服务地址：环境变量注入（VITE_FILE_BASE_URL），本地开发回落到本地后端
export const fileBaseUrl = import.meta.env.VITE_FILE_BASE_URL || 'http://localhost:1236'
