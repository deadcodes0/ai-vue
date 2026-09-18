# 02 MyBatis-Plus 分页配置

Status: done

## 内容

新增 `MybatisPlusConfig`：注册 `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.MYSQL)`。项目当前无此配置，`selectPage` 不会真正分页。
