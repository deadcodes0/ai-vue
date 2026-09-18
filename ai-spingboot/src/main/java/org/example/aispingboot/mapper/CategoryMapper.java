package org.example.aispingboot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.aispingboot.entity.Category;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
