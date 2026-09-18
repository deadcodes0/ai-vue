package org.example.aispingboot.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.example.aispingboot.DTO.command.ArticleCreateDTO;
import org.example.aispingboot.DTO.command.ArticleStatusDTO;
import org.example.aispingboot.DTO.command.ArticleUpdateDTO;
import org.example.aispingboot.DTO.response.AdminArticleResponseDTO;
import org.example.aispingboot.DTO.response.ArticleDetailResponseDTO;
import org.example.aispingboot.DTO.response.CategoryResponseDTO;
import org.example.aispingboot.common.Result;
import org.example.aispingboot.common.ResultCode;
import org.example.aispingboot.service.CategoryService;
import org.example.aispingboot.service.KnowledgeArticleService;
import org.example.aispingboot.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端知识库（权限规则见 SecurityConfig：/api/admin/** 需管理员角色；
 * 管理端与用户端接口分离，见 ADR-0002）
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private KnowledgeArticleService knowledgeArticleService;

    /**
     * 分类列表（当前平铺返回；接口名 tree 沿用前端既有契约）
     */
    @GetMapping("/category/tree")
    public Result<List<CategoryResponseDTO>> categoryTree() {
        return Result.ok(categoryService.listCategories());
    }

    /**
     * 管理端文章分页（含已删除行带 deleted 标记；title 模糊、categoryId/status 精确筛选）
     */
    @GetMapping("/article/page")
    public Result<Page<AdminArticleResponseDTO>> articlePage(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status) {
        return Result.ok(knowledgeArticleService.pageAdminArticles(currentPage, size, title, categoryId, status));
    }

    /**
     * 创建文章（接受客户端 UUID 为 id——ADR-0006；创建即草稿）
     */
    @PostMapping("/article")
    public Result<Void> createArticle(@Valid @RequestBody ArticleCreateDTO createDTO) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        knowledgeArticleService.createArticle(userId, createDTO);
        return Result.ok();
    }

    /**
     * 管理端文章详情（编辑回显；含 tagArray/categoryName）
     */
    @GetMapping("/article/{articleId}")
    public Result<ArticleDetailResponseDTO> articleDetail(@PathVariable String articleId) {
        return Result.ok(knowledgeArticleService.getAdminArticleDetail(articleId));
    }

    /**
     * 编辑文章（直接生效，不改变状态与首发时间）
     */
    @PutMapping("/article/{articleId}")
    public Result<Void> updateArticle(@PathVariable String articleId,
                                      @Valid @RequestBody ArticleUpdateDTO updateDTO) {
        knowledgeArticleService.updateArticle(articleId, updateDTO);
        return Result.ok();
    }

    /**
     * 发布/下线（状态机：0/2→1、1→2；首发时间仅首次发布写入）
     */
    @PutMapping("/article/{articleId}/status")
    public Result<Void> changeStatus(@PathVariable String articleId,
                                     @Valid @RequestBody ArticleStatusDTO statusDTO) {
        knowledgeArticleService.changeStatus(articleId, statusDTO.getStatus());
        return Result.ok();
    }

    /**
     * 软删除（退出生命周期，幂等；管理端列表仍可见带标记）
     */
    @DeleteMapping("/article/{articleId}")
    public Result<Void> deleteArticle(@PathVariable String articleId) {
        knowledgeArticleService.softDeleteArticle(articleId);
        return Result.ok();
    }

    // 从当前请求的 JWT 中解析用户ID（作者归属，对齐 EmotionDiaryController 先例）
    private Long getCurrentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        if (token == null) {
            return null;
        }
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }
}
