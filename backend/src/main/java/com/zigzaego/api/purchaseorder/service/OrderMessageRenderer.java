package com.zigzaego.api.purchaseorder.service;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 거래처 주문 메시지 렌더링 (3주 계획 4.7절). 거래처마다 템플릿 한 개({@code supplier.message_template}), 품목은 포장 단위로 적는다
 * (대파 1,600g → "대파 2단", 설계 3절). 템플릿이 없는 거래처는 4.7절 기본 문구를 쓴다 — 가게 이름은 데이터({@code store.display_name})다.
 *
 * <p>자리표시자 — {@code {가게}} 가게 이름, {@code {배송일}} 입고 예정일(M/d(요일)), {@code {품목}} 품목 줄("- 상품명 수량단위", 줄바꿈 구분).
 * 영문 {@code {store}} · {@code {arrival_date}} · {@code {items}} 도 같다. 치환은 한 번에 한다 — 가게 이름에 자리표시자 글자가 있어도
 * 다시 바뀌지 않는다. 템플릿에 {@code {품목}} 이 없으면 맨 끝에 품목 줄을 덧붙인다 — 품목이 빠진 주문 메시지는 쓸모가 없다.
 */
public final class OrderMessageRenderer {

	/** 4.7절 기본 템플릿. */
	static final String DEFAULT_TEMPLATE = """
			안녕하세요, {가게}입니다.
			{배송일} 배송으로 아래 부탁드립니다.
			{품목}
			감사합니다.""";

	/** 포장 환산은 알지만 포장 이름을 모르는 상품의 단위 표기. */
	static final String UNKNOWN_PACK_UNIT = "팩";

	private static final DateTimeFormatter ARRIVAL_DATE = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN);
	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(가게|store|배송일|arrival_date|품목|items)}");

	private OrderMessageRenderer() {
	}

	/**
	 * 메시지에 들어갈 품목 한 줄.
	 *
	 * @param productName    거래처 상품명
	 * @param packQty        포장 단위 수량(올림한 값). 환산을 모르는 상품은 기준 단위 수량
	 * @param packUnit       포장 단위 이름(단 · 박스). 모르면 null
	 * @param baseQtyPerPack 포장 1개 = 기준 단위 몇. 모르면 null — 그러면 수량도 단위도 기준 단위다
	 * @param baseUnit       재료 기준 단위
	 */
	public record Line(String productName, BigDecimal packQty, String packUnit, BigDecimal baseQtyPerPack,
			IngredientUnit baseUnit) {

		/**
		 * 메시지에 적는 단위. 환산을 모르면 수량이 기준 단위라 포장 이름이 있어도 기준 단위("1,600g")로 적는다 —
		 * 안 그러면 "1,600박스"가 된다. 환산은 알고 이름만 모르면 "팩".
		 */
		public String displayUnit() {
			if (baseQtyPerPack == null) {
				return baseUnitLabel(baseUnit);
			}
			return packUnit == null || packUnit.isBlank() ? UNKNOWN_PACK_UNIT : packUnit.trim();
		}

		String render() {
			return "- " + productName + " " + formatQty(packQty) + displayUnit();
		}
	}

	/** 기준 단위의 메시지 표기. */
	public static String baseUnitLabel(IngredientUnit unit) {
		return switch (unit) {
			case EA -> "개";
			case G -> "g";
			case ML -> "ml";
		};
	}

	/** 천 단위 쉼표, 소수는 셋째 자리까지, 끝의 0 은 뗀다. DecimalFormat 은 스레드 안전하지 않아 매번 만든다. */
	static String formatQty(BigDecimal qty) {
		return new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.KOREA)).format(qty);
	}

	/**
	 * @param template    거래처 템플릿. null · 빈 문자열이면 기본 템플릿
	 * @param storeName   가게 이름
	 * @param arrivalDate 입고 예정일
	 * @param lines       품목 줄 (비어 있지 않다)
	 */
	public static String render(String template, String storeName, LocalDate arrivalDate, List<Line> lines) {
		String base = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
		String items = String.join("\n", lines.stream().map(Line::render).toList());
		String date = arrivalDate.format(ARRIVAL_DATE);
		Map<String, String> values = Map.of("가게", storeName, "store", storeName, "배송일", date, "arrival_date", date,
				"품목", items, "items", items);

		boolean hasItems = false;
		StringBuilder out = new StringBuilder();
		Matcher m = PLACEHOLDER.matcher(base);
		while (m.find()) {
			String key = m.group(1);
			hasItems |= key.equals("품목") || key.equals("items");
			m.appendReplacement(out, Matcher.quoteReplacement(values.get(key)));
		}
		m.appendTail(out);
		return hasItems ? out.toString() : out.toString().stripTrailing() + "\n" + items;
	}
}
