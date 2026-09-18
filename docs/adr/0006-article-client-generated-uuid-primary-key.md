# 知识文章主键采用客户端生成的 UUID

article 是全项目唯一一张主键由前端生成、类型为 varchar 的表：封面图上传发生在文章落库**之前**，前端将上传用的 businessId 直接复用为文章 id 提交（上传→创建一条链路），后端接受该契约，以 char(36) UUID 作主键。这样封面文件天然按文章 ID 归档（`/upload/ARTICLE/{articleId}/cover_xxx.jpg`），前端 ArticleDialog 零改动；代价是偏离项目其他表的自增 Long 惯例。

## Considered Options

- 客户端生成 UUID（char(36) PK）：选定——契约已在前端定型且自洽；文件与文章 ID 绑定，磁盘结构可人肉追溯。
- 后端自增 Long（项目惯例）：被否决——需改前端提交逻辑并切断封面文件与文章 ID 的关联，收益仅是主键类型统一；千级文章量下 varchar 索引的劣势可忽略。

## Consequences

- article.id 为 char(36)，`@TableId(type = IdType.INPUT)`：后端不生成，只校验 UUID 格式；主键冲突按业务异常返回（重复提交/重放）。
- 用户端详情 URL 为 `/knowledge/article/{uuid}`。
- 若未来有人想"统一主键风格"迁回自增，需要数据迁移 + 前端契约改造——本 ADR 的存在就是阻止这次顺手统一。
- 封面上传成功但放弃创建文章时，磁盘残留孤儿文件（接受，无清理机制；见知识库设计：本地磁盘存储、不建文件元数据表）。
