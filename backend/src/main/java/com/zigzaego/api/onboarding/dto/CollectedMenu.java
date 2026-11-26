package com.zigzaego.api.onboarding.dto;

import java.util.List;

/**
 * 네이버 플레이스에서 수집한 메뉴 한 줄 (설계 2.1 ②). 받은 그대로 — 같은 이름 중복 제거 · 표기 통일은 온보딩 job 정규화 단계에서 한다.
 *
 * @param name        메뉴명
 * @param price       가격(원). 숫자로 읽을 수 없으면 null
 * @param description 메뉴 설명. 없으면 null (음료 등)
 * @param imageUrls   메뉴 사진 URL. LLM 구성 추출에 넘기고, 메뉴 목록 사진으로 저장한다 (DECISIONS #45 — 저장은 온보딩 job 쪽)
 */
public record CollectedMenu(String name, Integer price, String description, List<String> imageUrls) {

	public CollectedMenu {
		imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
	}
}
