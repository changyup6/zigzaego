package com.zigzaego.api.domain.market;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 식자재몰 상품명이 재료 이름(검색어)과 "정확히 같은 품목" 인지 판정한다 (S15P31A308-232).
 * 크롤러 {@code foodspring-crawler/src/wiggly/prices/match.py} 의 {@code is_exact} 를 그대로 옮긴 것 —
 * 규칙을 바꾸면 양쪽을 같이 고치고 {@code ItemMatcherTest} 의 기대값을 Python 으로 다시 만든다.
 *
 * <ul>
 *   <li>중심 낱말: 괄호 · [] · ★…★ 를 지우고, 끝에 붙은 꾸밈말(업소용 · 냉동 · 최저가 …)을 건너뛴 마지막 한글 낱말.
 *       이것이 검색어(또는 다른 이름: 계란 ↔ 달걀 · 특란)로 끝나야 한다 (흙대파 ✓, 새우 계란 볶음밥 ✗)</li>
 *   <li>가공품 낱말(김치 · 볶음밥 · 지단 · 찜기 …)로 끝나는 낱말이 있으면 그 재료로 만든 음식 · 물건이다 (포기김치 … 고춧가루 ✗)</li>
 *   <li>다른 품목 합성어(양배추 · 땅콩버터 · 보리쌀 · 어깨)는 목록으로 뺀다</li>
 *   <li>조리 접두어 + 계란(깐 계란 · 구운 유정란), 메추리알 · 오리알, 반려동물 상품은 뺀다</li>
 * </ul>
 * 알려진 한계(크롤러 docs/search_price_check.md): 부위 이름으로 파는 고기(소고기 검색에 조미료가 남음), 이름에 표시 없는 가공품, 한 글자 검색어.
 */
public final class ItemMatcher {

	/** 같은 것을 부르는 다른 이름. 검색어가 이 중 하나면 나머지로도 찾는다. 구운란 · 훈제란 · 반숙란은 조리된 것이라 없다. */
	private static final List<List<String>> SYNONYMS = List.of(
			List.of("계란", "달걀", "특란", "대란", "왕란", "중란", "소란", "유정란", "무정란", "신선란", "세척란", "생란", "등급란",
					"백색란", "갈색란"),
			List.of("돼지고기", "돈육"), List.of("소고기", "쇠고기", "우육"), List.of("닭고기", "계육"));

	private static final Map<String, List<String>> SYN = new HashMap<>();

	static {
		for (List<String> group : SYNONYMS) {
			for (String word : group) {
				SYN.put(word, group);
			}
		}
	}

	/** 상품명에 이 말로 끝나는 낱말이 있으면 재료가 아니라 그 재료로 만든 음식 · 물건이다. 검색어에 든 말은 빼고 본다. */
	private static final List<String> PROCESSED = List.of(
			"볶음밥", "덮밥", "비빔밥", "김밥", "주먹밥", "죽", "만두", "교자", "찌개", "김치", "소스", "드레싱", "스프", "수프",
			"라면", "국수", "우동", "과자", "스낵", "쿠키", "케이크", "빵", "샌드위치", "도시락", "볶음", "조림", "무침",
			"전골", "스테이크", "까스", "튀김", "피자", "버거", "샐러드", "장아찌", "절임", "젓갈", "즙", "주스", "음료",
			"치즈", "아이스크림", "요거트", "샴푸", "사료", "간식", "육개장", "미역국", "무국", "곰탕", "국밥", "다시",
			"다시다", "조미료",
			"지단", "지단채", "구운란", "훈제란", "반숙란", "구운계란", "구운달걀", "삶은계란", "삶은달걀", "깐계란", "깐달걀",
			"삶은", "반숙", "훈제", "맥반석", "오믈렛", "마요네즈", "비스킷", "샐러드", "완자", "카츠", "장조림", "찜기",
			"트레이", "보관함", "디스펜서", "분리기", "뒤집개", "채망", "메이커", "골프공", "헤어핀", "스프레드",
			"초밥용", "초밥", "쫄깃한", "라자냐", "파스타", "건면");

	/** 검색어로 끝나지만 다른 품목인 합성어. 흙대파 · 진간장처럼 앞말이 손질 · 종류인 경우가 훨씬 많아 목록으로만 둔다. */
	private static final List<String> DIFFERENT = List.of(
			"양배추", "넛버터", "땅콩버터", "아몬드버터", "보리쌀", "쌀보리", "초고추장", "초생강", "어깨");

	private static final Pattern PET = Pattern.compile("강아지|고양이|반려|펫|\\bdog\\b|\\bcat\\b",
			Pattern.UNICODE_CHARACTER_CLASS);

	/** 조리 접두어 + 계란("깐 계란 700g", "구운 유정란 10구"). 메추리알 · 오리알은 닭 계란이 아니다. */
	private static final Pattern COOKED_EGG = Pattern.compile(
			"(깐|삶은|구운|반숙|훈제|맥반석)\\s*(계란|달걀|[가-힣]{0,3}란)|메추리|오리\\s*알|오리\\s*유정란",
			Pattern.UNICODE_CHARACTER_CLASS);

	/** 품목이 아닌 꾸밈 · 포장 · 맛 · 색 · 상표 말. 상품명 끝에 오면 중심 낱말에서 건너뛴다. */
	private static final Set<String> MODIFIERS = Set.of(("업소용 대용량 국내산 국산 수입산 냉동 냉장 상온 세트 선물세트 기획세트 "
			+ "슬라이스 블랙 화이트 레드 블루 핑크 골드 아이보리 오리지널 오리지날 클래식 제로 리필 박스 포장 개별포장 용기 실링용기 "
			+ "뚜껑 파우치 캔 벌크 미니 대형 대 중 소 특 상 내외 플러스 프리미엄 매운맛 순한맛 마일드 플레인 원액 골라담기 증정 "
			+ "구이용 베이스 분말 가루 스틱 홀 청정원 최저가 초특가 특가 행사 첫구매 묶음 묶음판매 옵션선택 농장직영 인증 식당 "
			+ "식당용 급식재료 식자재 도매 단체간식 편의점 판").split(" "));

	private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)|★[^★]*★");

	private static final Pattern SPLIT = Pattern.compile("[\\s/,+_]+", Pattern.UNICODE_CHARACTER_CLASS);

	private static final Pattern DIGIT = Pattern.compile("[0-9]");

	private static final Pattern HANGUL = Pattern.compile("[가-힣]");

	private static final Pattern HANGUL_THEN_DIGIT = Pattern.compile("([가-힣]{2,})[0-9]");

	private static final Pattern TRAILING_NON_HANGUL = Pattern.compile("[^가-힣]+$");

	private static final Pattern LEADING_HANGUL = Pattern.compile("^[가-힣]+");

	private static final Pattern NON_HANGUL = Pattern.compile("[^가-힣]");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	private ItemMatcher() {
	}

	/** 검색어의 다른 이름들(자기 자신 포함). */
	public static List<String> alternatives(String term) {
		return SYN.getOrDefault(term, List.of(term));
	}

	/**
	 * 후보를 DB 에서 거를 정규식: 낱말(과 다른 이름) 바로 뒤에 한글이 붙으면 다른 상품이다(대파함박스테이크 · 양파링). 앞은 괜찮다(흙대파).
	 * PostgreSQL {@code ~*} 와 Java 양쪽에서 같은 뜻이다.
	 */
	public static String candidateRegex(String term) {
		StringBuilder sb = new StringBuilder("(");
		List<String> alts = alternatives(term.toLowerCase());
		for (int i = 0; i < alts.size(); i++) {
			if (i > 0) {
				sb.append('|');
			}
			sb.append(escapeRegex(alts.get(i)));
		}
		return sb.append(")([^가-힣]|$)").toString();
	}

	private static final Pattern REGEX_SPECIAL = Pattern.compile("[\\\\^$.|?*+()\\[\\]{}]");

	/**
	 * 정규식 특수문자를 백슬래시로 이스케이프한다. {@code Pattern.quote} 의 {@code \Q...\E} 는 PostgreSQL 이 모르므로 쓰지 않는다.
	 * 재료명에 괄호 · 더하기(청경채(소) · 1+등급)가 들어와도 글자 그대로 찾는다.
	 */
	static String escapeRegex(String literal) {
		return REGEX_SPECIAL.matcher(literal).replaceAll(r -> Matcher.quoteReplacement("\\" + r.group()));
	}

	/** 상품명이 검색어(띄어 쓴 마지막 낱말 기준)와 정확히 같은 품목인가. */
	public static boolean isExact(String name, String query) {
		if (name == null || query == null) {
			return false;
		}
		List<String> terms = Arrays.stream(WHITESPACE.split(query.toLowerCase().strip()))
				.filter(t -> !t.isEmpty()).toList();
		if (terms.isEmpty()) {
			return false;
		}
		Head head = head(name);
		String last = head.last();
		String alt = null;
		if (!last.isEmpty()) {
			for (String a : alternatives(terms.get(terms.size() - 1))) {
				if (last.endsWith(a)) {
					alt = a;
					break;
				}
			}
		}
		if (alt == null || PET.matcher(name.toLowerCase()).find()) {
			return false;
		}
		if (SYN.getOrDefault("계란", List.of()).contains(alt) && COOKED_EGG.matcher(name).find()) {
			return false;
		}
		for (String d : DIFFERENT) {
			if (keep(d, terms) && last.endsWith(d)) {
				return false;
			}
		}
		List<String> words = new ArrayList<>();
		for (String t : head.parts()) {
			words.add(TRAILING_NON_HANGUL.matcher(t).replaceAll(""));  // 포기김치10kg → 포기김치
		}
		for (String t : head.parts()) {
			Matcher m = LEADING_HANGUL.matcher(t);  // 튀김2kgx5개 → 튀김 (숫자 뒤 "개" 에 가려지지 않게)
			if (m.find()) {
				words.add(m.group());
			}
		}
		words.add(last.substring(0, last.length() - alt.length()));  // 검색어 앞에 붙은 말: 진국다시쇠고기 → 진국다시
		for (String w : words) {
			for (String x : PROCESSED) {
				if (keep(x, terms) && w.endsWith(x)) {
					return false;
				}
			}
		}
		return true;
	}

	/** 검색어에 든 말(김치 검색의 김치 · 치즈 검색의 치즈)은 가공품 · 합성어로 보지 않는다. */
	private static boolean keep(String word, List<String> terms) {
		for (String t : terms) {
			if (t.contains(word)) {
				return false;
			}
		}
		return true;
	}

	/** 띄어쓰기 낱말들과 중심 낱말. */
	record Head(List<String> parts, String last) {
	}

	/**
	 * 중심 낱말 = 괄호 · [] · ★…★ 를 빼고, 끝에 붙은 꾸밈말을 건너뛴 마지막 한글 낱말(없으면 ""). 한글 뒤에 숫자가 붙은 낱말은
	 * 앞 한글만 쓴다(순후추100g → 순후추, 고수1단 → 고수).
	 */
	static Head head(String name) {
		String cleaned = BRACKETS.matcher(name.toLowerCase()).replaceAll(" ");
		List<String> parts = Arrays.asList(SPLIT.split(cleaned, -1));
		List<String> toks = new ArrayList<>();
		for (String t : parts) {
			if (!DIGIT.matcher(t).find()) {
				if (HANGUL.matcher(t).find()) {
					toks.add(t);
				}
			} else {
				Matcher m = HANGUL_THEN_DIGIT.matcher(t);
				if (m.lookingAt()) {
					toks.add(m.group(1));
				}
			}
		}
		while (toks.size() > 1 && MODIFIERS.contains(NON_HANGUL.matcher(toks.get(toks.size() - 1)).replaceAll(""))) {
			toks.remove(toks.size() - 1);
		}
		return new Head(parts, toks.isEmpty() ? "" : toks.get(toks.size() - 1));
	}
}
