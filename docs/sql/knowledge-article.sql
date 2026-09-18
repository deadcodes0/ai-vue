-- ============================================================
-- 知识库文章功能 · 数据库增量脚本
--
-- 基础表 knowledge_category / knowledge_article 随原项目数据库
-- 导入已存在（含 category_code/sort_order 等本功能未消费的列，
-- updated_at 带 ON UPDATE CURRENT_TIMESTAMP，见 Service 中的回写处理）。
--
-- 本功能对库的唯一增量：为 knowledge_article 补软删除列
-- （决策：软删除 = 退出生命周期，管理端带标记可见，用户端过滤）。
--
-- 无 migration 框架，手工执行；本脚本已在 2026-09-07 对本地库执行过一次。
-- ============================================================

ALTER TABLE `knowledge_article`
    ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记（退出生命周期，管理端带标记可见）';
