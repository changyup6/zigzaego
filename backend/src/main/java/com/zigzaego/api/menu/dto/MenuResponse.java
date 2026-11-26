package com.zigzaego.api.menu.dto;

import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.menu.entity.Menu;
import com.zigzaego.api.menu.entity.MenuKind;
import com.zigzaego.api.menu.entity.MenuSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 메뉴 하나. 목록 · 조회 · 생성 · 수정 응답이 같다. */
@Schema(description = "메뉴 하나")
public record MenuResponse(
		@Schema(description = "메뉴 id (문자열)", example = "7")
		String id,

		@Schema(description = "메뉴 이름", example = "김치찌개")
		String name,

		@Schema(description = "MAIN=일반 / OPTION=유료 추가 옵션 / SET=세트", example = "MAIN")
		MenuKind kind,

		@Schema(description = "판매 중인지", example = "true")
		boolean active,

		@Schema(description = "메뉴 설명. 없으면 null", example = "돼지고기와 묵은지", nullable = true)
		String description,

		@Schema(description = "대표 메뉴 사진 원본 HTTP(S) URL. 없으면 null", nullable = true)
		String photoUrl,

		@Schema(description = "가져온 곳", example = "NAVER")
		MenuSource source,

		@Schema(description = "판매가(원, 정수). 모르면 null", example = "12000", nullable = true)
		Long price,

		@Schema(description = "확인 필요 — 이 메뉴에 AI 추정 레시피 줄이 남아 있다", example = "false")
		boolean needsReview,

		@Schema(description = "확인 필요 이유 코드. UNANSWERED=AI 추정 레시피 줄을 사장님이 아직 확인하지 않음", example = "[]")
		List<ReviewReason> reviewReasons
) {

	public static MenuResponse of(Menu menu, List<ReviewReason> reviewReasons) {
		return new MenuResponse(String.valueOf(menu.getId()), menu.getName(), menu.getKind(), menu.isActive(),
				menu.getDescription(), menu.getPhotoUrl(), menu.getSource(), won(menu.getPrice()), !reviewReasons.isEmpty(),
				reviewReasons);
	}

	/** 금액은 원 단위 정수 (DECISIONS #55). 다른 경로로 소수가 들어와 있어도 반올림해 정수로 준다. */
	private static Long won(BigDecimal amount) {
		return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
	}
}
