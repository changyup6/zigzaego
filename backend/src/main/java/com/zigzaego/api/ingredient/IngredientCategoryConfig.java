package com.zigzaego.api.ingredient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 재료 카테고리 자동 분류 설정 등록 (S15P31A308-274). */
@Configuration
@EnableConfigurationProperties(IngredientCategoryProperties.class)
public class IngredientCategoryConfig {
}
