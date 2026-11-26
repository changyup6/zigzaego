package com.zigzaego.api.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ItemMatcher} 가 크롤러 {@code match.py} 의 {@code is_exact} 와 같은 답을 내는지 (S15P31A308-232).
 * 기대값은 2026-11-02 데이터셋(products_20261031) 상품명으로 Python 을 돌려 만들었다. 규칙을 바꾸면 양쪽을 같이 고친다.
 */
class ItemMatcherTest {

	/** (기대값, 검색어, 상품명). Python: {@code is_exact(name, query)}. */
	private record Case(boolean exact, String query, String name) {
	}

	private static final List<Case> CASES = List.of(
			// 날계란: 중심 낱말이 계란 · 달걀 · 등급 이름(대란 · 신선란)으로 끝난다
			new Case(true, "계란", "통통알 특란 계란"),
			new Case(true, "계란", "계란(대란/30알/국내산)판"),
			new Case(true, "계란", "★초특가★ 대란 30알 (5판 묶음판매,계란,달걀) 최저가"),
			new Case(true, "계란", "[KF365] 무항생제 달걀 L(대란) 20구"),
			new Case(true, "계란", "1+등급 특란 60구 계란 달걀 신선란 식자재 업소용 급식재료 식당 대용량"),
			new Case(true, "계란", "무항생제 인증 창녕 우포 계란 60구 /옵션선택"),
			new Case(true, "계란", "[성원] 선비란 무항생제 계란 60구/90구 (옵션선택)"),
			// 계란 가공품 · 조리란 · 도구
			new Case(false, "계란", "계란찜 1kg"),
			new Case(false, "계란", "새우 계란 볶음밥 250g"),
			new Case(false, "계란", "[KF365] 깐 계란 700g"),
			new Case(false, "계란", "동물복지 구운 유정란 10구(350g이상)"),
			new Case(false, "계란", "촉촉한 반숙 계란 20구 업소용"),
			new Case(false, "계란", "[99달걀] 99구운란 30구 (15구*2개입)"),
			new Case(false, "계란", "미소란 김밥용 계란 지단 (167g*6개입 1kg/EA)"),
			new Case(false, "계란", "[유니맥스] 다용도 2단 계란 찜기 UM-EDG2135"),
			new Case(false, "계란", "[구디푸디] 투명 2단 32구 계란 보관함 에그트레이 2종 (택1)"),
			new Case(false, "계란", "(W)골드마요네즈(오뚜기/500G/EA/계란:국산)"),
			new Case(false, "계란", "디벨라 라자냐 계란 500g"),
			new Case(false, "계란", "초밥용 계란 슬라이스 500g 1팩"),
			new Case(false, "계란", "[자연애찬] 쫄깃한 계란 (20개입)"),
			new Case(false, "계란", "[Kurly's] 깐 메추리 유정란 450g"),
			new Case(false, "계란", "다림 새우계란죽 500gX7팩"),
			new Case(false, "계란", "계란 감자 샐러드 (110g x 2개입)"),
			// 다른 품목 합성어 · 같은 품목 합성어
			new Case(false, "배추", "국내산 양배추(특)"),
			new Case(true, "배추", "국내산 알배기배추(특)"),
			new Case(false, "버터", "땅콩버터 500g"),
			new Case(true, "버터", "(D)무염버터(프랑스/라꽁비에트/냉장/AOP/150g(10입))EA"),
			new Case(true, "간장", "삼화 맑은 국간장 13"),
			new Case(true, "간장", "진간장 1.8L"),
			new Case(true, "대파", "흙대파(대아청과 1099번 10단입 망 9Kg/EA)"),
			new Case(false, "대파", "대파함박스테이크 1kg"),
			new Case(false, "쌀", "보리쌀 1kg"),
			new Case(true, "쌀", "신동진 쌀 10kg"),
			new Case(false, "깨", "닭어깨살 1kg"),
			new Case(true, "깨", "볶음참깨 1kg 통깨"),
			// 가공품 낱말 · 검색어에 든 말은 가공품으로 보지 않음
			new Case(true, "김치", "종가 맛김치1.2kg(PET,6입)"),
			new Case(false, "고춧가루", "포기김치 10kg 고춧가루"),
			new Case(false, "소고기", "담백한 소고기 미역국 300g"),
			// 알려진 한계(크롤러 docs/search_price_check.md) — 지금 규칙의 답 그대로. 고치면 Python 도 같이 고친다
			new Case(true, "소고기", "다시다골드쇠고기"),
			new Case(true, "소고기", "[테일스키친] 말랑트릿 소고기 (460g)"),
			new Case(true, "새우", "깐쇼새우 1kg"),
			new Case(true, "새우", "흰다리새우 20미"));

	@Test
	void 크롤러_is_exact_와_같은_답() {
		for (Case c : CASES) {
			assertThat(ItemMatcher.isExact(c.name(), c.query()))
					.as("%s ← '%s'", c.name(), c.query())
					.isEqualTo(c.exact());
		}
	}

	@Test
	void 반려동물_상품은_품목이_맞아도_제외() {
		assertThat(ItemMatcher.isExact("강아지 간식 닭가슴살", "닭가슴살")).isFalse();
		assertThat(ItemMatcher.isExact("[테일스키친] DOG 소고기 큐브", "소고기")).isFalse();
	}

	@Test
	void 빈_검색어_빈_상품명은_false() {
		assertThat(ItemMatcher.isExact("", "계란")).isFalse();
		assertThat(ItemMatcher.isExact("계란 30구", "")).isFalse();
		assertThat(ItemMatcher.isExact("계란 30구", "   ")).isFalse();
		assertThat(ItemMatcher.isExact(null, "계란")).isFalse();
		assertThat(ItemMatcher.isExact("계란 30구", null)).isFalse();
	}

	@Test
	void 두_낱말_검색어는_마지막_낱말이_중심() {
		assertThat(ItemMatcher.isExact("흙대파 1단", "흙 대파")).isTrue();
		assertThat(ItemMatcher.isExact("대파 김치 1kg", "대파 김치")).isTrue();
	}

	@Test
	void 중심_낱말은_괄호를_빼고_꾸밈말을_건너뛴_마지막_한글_낱말() {
		assertThat(ItemMatcher.head("계란(대란/30알/국내산)판").last()).isEqualTo("계란");
		assertThat(ItemMatcher.head("1+등급 특란 60구 계란 달걀 신선란 식자재 업소용 급식재료 식당 대용량").last()).isEqualTo("신선란");
		assertThat(ItemMatcher.head("순후추100g").last()).isEqualTo("순후추");
		assertThat(ItemMatcher.head("[99달걀] 99구운란 30구 (15구*2개입)").last()).isEmpty();
	}

	@Test
	void 후보_정규식은_다른_이름을_포함하고_뒤에_한글이_붙으면_제외() {
		String regex = ItemMatcher.candidateRegex("계란");
		assertThat(regex).startsWith("(계란|달걀|특란|").endsWith(")([^가-힣]|$)");
		assertThat("통통알 특란 계란".matches(".*" + regex + ".*")).isTrue();
		assertThat("계란찜 1kg".matches(".*" + regex + ".*")).isFalse();
		assertThat(ItemMatcher.candidateRegex("대파")).isEqualTo("(대파)([^가-힣]|$)");
	}

	@Test
	void 후보_정규식은_특수문자를_백슬래시로_이스케이프한다() {
		assertThat(ItemMatcher.candidateRegex("청경채(소)")).isEqualTo("(청경채\\(소\\))([^가-힣]|$)");
		assertThat(ItemMatcher.candidateRegex("1+등급")).isEqualTo("(1\\+등급)([^가-힣]|$)");
		assertThat(ItemMatcher.candidateRegex("a.b*c|d")).isEqualTo("(a\\.b\\*c\\|d)([^가-힣]|$)");
		assertThat(ItemMatcher.candidateRegex("계란\\소")).isEqualTo("(계란\\\\소)([^가-힣]|$)");
		assertThat(ItemMatcher.candidateRegex("A$[b]{c}^?")).doesNotContain("\\Q").doesNotContain("\\E");
		// Java 에서도 글자 그대로 — 괄호가 그룹이 되지 않는다
		String regex = ItemMatcher.candidateRegex("청경채(소)");
		assertThat("청경채(소) 500g".matches(".*" + regex + ".*")).isTrue();
		assertThat("청경채소 500g".matches(".*" + regex + ".*")).isFalse();
		assertThat("1+등급 특란".matches(".*" + ItemMatcher.candidateRegex("1+등급") + ".*")).isTrue();
		assertThat("11등급".matches(".*" + ItemMatcher.candidateRegex("1+등급") + ".*")).isFalse();
	}
}
