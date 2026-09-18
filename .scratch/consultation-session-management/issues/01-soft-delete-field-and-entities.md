# 01 软删除字段与实体加固

Status: done

## 内容

- `consultation_session` 表加 `deleted TINYINT(1) NOT NULL DEFAULT 0`（SQL 见 spec.md，需手工执行）
- `ConsultationSession` 实体加 `deleted` Boolean 字段
- `ConsultationSession`、`ConsultationMessage` 补 `@NoArgsConstructor` + `@AllArgsConstructor`（当前 @Data+@Builder 只有全参构造器，MyBatis SELECT 反序列化有风险）
