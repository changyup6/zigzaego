package com.zigzaego.api.market.catalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 품목군 기준표 조회 · 재료 이름 → 품목군 매핑 (ADR-0012, 기준 문서 8절).
 * 매핑은 이름 · 별칭과 **정확히 같을 때만**(공백 · 대소문자 무시). 같은 별칭을 두 품목군이 쓰면(등심) 매핑하지 않는다 — 부르는 쪽이 이름 검색(규칙)으로 넘어간다.
 * 표는 요청마다 읽는다(수백 줄). 캐시가 필요해지면 그때 둔다.
 */
@Service
@RequiredArgsConstructor
public class MarketItemGroupCatalog {

	private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-_/·,]+", Pattern.UNICODE_CHARACTER_CLASS);

	private final MarketItemGroupJdbcRepository repository;

	/** 재료 이름 비교용 정규화: 소문자, 공백 · 구분 기호 제거(다진 마늘 == 다진마늘). 크롤러 {@code taxonomy.normalize} 와 같다. */
	public static String normalize(String text) {
		return SEPARATORS.matcher(text == null ? "" : text.toLowerCase()).replaceAll("");
	}

	@Transactional(readOnly = true)
	public Snapshot load() {
		return new Snapshot(repository.findAll());
	}

	/** 한 요청 안에서 쓰는 표 복사본. */
	public static final class Snapshot {

		private final Map<String, MarketItemGroup> byCode = new LinkedHashMap<>();
		private final Map<String, List<String>> byAlias = new HashMap<>();
		/** 상품명에서 별칭을 낱말로 찾는 정규식들 (LLM 분류 대상 거르기, S15P31A308-229). */
		private final List<Pattern> finders = new ArrayList<>();

		public Snapshot(List<MarketItemGroup> groups) {
			for (MarketItemGroup g : groups) {
				byCode.put(g.code(), g);
				for (String alias : g.aliases()) {
					List<String> codes = byAlias.computeIfAbsent(normalize(alias), k -> new ArrayList<>());
					if (!codes.contains(g.code())) {
						codes.add(g.code());
					}
					finders.add(wordPattern(alias));
				}
			}
		}

		/**
		 * 상품명에서 별칭을 낱말로 찾는 정규식. 별칭 바로 뒤에 한글이 붙으면 다른 낱말이다(대파함박스테이크 · 양파링). 앞은 괜찮다(흙대파).
		 * 크롤러 {@code taxonomy.word_re} · {@code ItemMatcher.candidateRegex} 와 같은 뜻 — 후보를 거르는 기준이 양쪽에서 같아야 한다.
		 */
		static Pattern wordPattern(String alias) {
			return Pattern.compile(Pattern.quote(alias.toLowerCase()) + "([^가-힣]|$)");
		}

		/**
		 * 상품명에 어느 품목군의 별칭이 낱말로 들어 있는가 (LLM 분류 대상, 기준 문서 9절). 상품이 그 품목군이라는 뜻은 아니다 — '계란 찜기' 도 후보다.
		 * 크롤러 {@code Taxonomy.candidate_codes} 가 비어 있지 않은 것과 같다.
		 */
		public boolean isCandidate(String productName) {
			String low = productName == null ? "" : productName.toLowerCase();
			for (Pattern p : finders) {
				if (p.matcher(low).find()) {
					return true;
				}
			}
			return false;
		}

		/**
		 * LLM 에 주는 품목군 목록 한 줄씩: {@code CODE | 이름 | 별칭 … | 메모}. 부모 품목군은 '부모' 라고 적어 고르지 않게 한다.
		 * 크롤러 {@code Taxonomy.prompt_table} 과 같은 모양 — 프롬프트가 두 쪽에서 같아야 라벨 버전이 뜻을 가진다.
		 */
		public String promptTable() {
			StringBuilder sb = new StringBuilder();
			for (MarketItemGroup g : byCode.values()) {
				String aliases = g.aliases().stream().filter(a -> !a.equals(g.name())).collect(Collectors.joining(" · "));
				sb.append(g.code()).append(" | ").append(g.name()).append(" | ").append(aliases);
				if (g.note() != null && !g.note().isBlank()) {
					sb.append(" | ").append(g.note());
				}
				if (!g.comparable()) {
					sb.append(" | 부위 품목군의 부모 — 부위를 모를 때만");
				}
				sb.append('\n');
			}
			return sb.toString().stripTrailing();
		}

		public Optional<MarketItemGroup> get(String code) {
			return Optional.ofNullable(code == null ? null : byCode.get(code));
		}

		/** 재료 이름 → 품목군. 정확히 같은 이름 · 별칭이 하나의 품목군에만 있을 때. */
		public Optional<MarketItemGroup> forIngredientName(String name) {
			List<String> codes = byAlias.getOrDefault(normalize(name), List.of());
			return codes.size() == 1 ? get(codes.get(0)) : Optional.empty();
		}

		/** 부모 품목군(돼지고기)의 부위 품목군들, 이름순. */
		public List<MarketItemGroup> children(String parentCode) {
			return byCode.values().stream().filter(g -> parentCode.equals(g.parentCode()))
					.sorted(java.util.Comparator.comparing(MarketItemGroup::name)).toList();
		}

		public int size() {
			return byCode.size();
		}
	}
}
