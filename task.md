完整盘点完毕。**前端共调用 20 个接口，后端仅实现 5 个**（登录、注册、当前用户、创建会话、SSE 流式对话），缺口 15 个，按模块预估如下：

| 模块 | 缺失接口 | 工作量 | 说明 |
|---|---|---|---|
| **咨询会话管理** | `GET /sessions` 分页、`DELETE /sessions/{id}`、`GET /sessions/{id}/messages` | ~0.5 天 | 两张表和 mapper 都已存在，纯补查询逻辑，**最简单** |
| **会话情绪分析** | `GET /session/{id}/emotion` | ~1 天 | **唯一有技术含量的**：调 AI 结构化输出情绪评分/风险等级/建议（[StructOutPut.java](file:///D:/Coding/health-agent/心理健康助手/ai-spingboot/src/main/java/org/example/aispingboot/AiService/StructOutPut.java) 已有雏形），需考虑缓存避免重复调用烧 token |
| **情绪日记** | `POST /emotion-diary`、管理端分页、删除 | ~0.5-1 天 | 需新建 `emotion_diary` 表 + entity/mapper/service/controller 全套，标准 CRUD |
| **知识库文章** | 分类树、文章分页、增删改查、上下架 共 7 个 | ~1-1.5 天 | 需建 2 张表，分类树有递归组装逻辑，接口量最大 |
| **文件上传** | `POST /file/upload` | ~0.5-1 天 | 封面图 + 富文本编辑器图片上传，本地磁盘版简单，OSS/MinIO 版翻倍 |
| **数据分析** | `GET /data-analytics/overview` | ~0.5 天 | 几个 count/group by 聚合查询喂 ECharts |
| **登出** | `POST /user/logout` | ~0.5 小时 | JWT 无状态，前端清 token 即可；若要求服务端失效需引入 Redis 黑名单 |
| **权限隔离补全** | 上次发现的 `hasRole` 规则 | ~0.5 天 | 管理端接口路径规范化 + SecurityConfig 加规则 + 联调 |

**合计约 5-7 个人日**（全职投入、不含上线后修 bug）。

**建议实施顺序**（按价值密度）：

1. **咨询会话管理** → 半天就能让用户端核心闭环（会话列表/历史/删除）全部跑通，性价比最高
2. **情绪日记 + 情绪分析** → 这是项目的差异化特色，也是简历上“AI 情绪分析”亮点的真实支撑
3. **知识库 + 文件上传** → 管理端核心功能
4. **数据分析 + 登出 + 权限规则** → 收尾

其中**情绪分析**值得多花心思：做成“对话 done 后自动分析 + 结果落库 + 前端读库”的模式（而不是每次请求实时调 AI），既省 API 成本又响应快，还能往简历上写“AI 结果持久化缓存”。
