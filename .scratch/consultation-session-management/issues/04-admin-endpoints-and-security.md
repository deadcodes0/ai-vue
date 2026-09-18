# 04 管理端端点与权限

Status: done

## 内容

- 新增 AdminConsultationController：GET /api/admin/consultation/sessions（currentPage/size，含已删除，带 userNickname、deleted 标记）、GET /api/admin/consultation/sessions/{id}/messages
- SecurityConfig：`/api/admin/**` 要求 hasRole("2")（JWT 过滤器已设 ROLE_2 权限）
- admin.js：getConsultationPage、getSessionDetail 两处 URL 改为 /admin/consultation/*
- consultations.vue：会话标题旁显示"用户已删除" el-tag（scope.row.deleted）
