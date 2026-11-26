package com.zigzaego.api.purchasedoc.service;

import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.entity.UnitStatus;
import com.zigzaego.api.purchasedoc.entity.PurchaseDocLine;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 영수증 줄을 사장님이 확인해야 하는 이유 (S15P31A308-112 · S15P31A308-113). 순수 계산 — DB 를 읽지 않는다.
 * S15P31A308-112 의 확정 검사(단위 미확정이면 포장 환산 필수)와 S15P31A308-111 의 자동 확정, S15P31A308-113 의 확인 목록 {@code needsReview} · {@code reasons}
 * (FE {@code types.ts} {@code UNIT_UNSET} · {@code LOW_CONFIDENCE} · {@code UNMAPPED})가 같은 규칙을 쓴다.
 */
public final class ReceiptReview {

	/** 사전 상품명 칸 길이(supplier_product.product_name VARCHAR(150)). 넘으면 사전에 넣지 않는다. */
	public static final int PRODUCT_NAME_MAX = 150;
	/** NUMERIC(14,3) 의 소수 자리 · 전체 자리. */
	private static final int QTY_SCALE = 3;
	private static final int QTY_PRECISION = 14;
	/**
	 * LLM 제안 줄의 확신도가 이 값 미만이거나 비어 있으면 LOW_CONFIDENCE (S15P31A308-113). 팀의 다른 확신도 기준
	 * ({@code QuestionRule.CONFIDENCE_THRESHOLD} 0.7)과 같다. 표시용 — 자동 확정 범위는 바꾸지 않는다.
	 */
	public static final BigDecimal LOW_CONFIDENCE_BELOW = new BigDecimal("0.700");

	/**
	 * UNIT_UNSET 포장 환산을 모르거나 재료 단위가 아직 추정값 / UNMAPPED 재료가 정해지지 않음 /
	 * LOW_CONFIDENCE LLM 제안 줄의 확신도가 {@link #LOW_CONFIDENCE_BELOW} 미만이거나 비어 있음(사전 매칭 줄은 내지 않는다).
	 */
	public enum Reason {
		UNIT_UNSET, LOW_CONFIDENCE, UNMAPPED
	}

	private ReceiptReview() {
	}

	/**
	 * @param line          영수증 줄
	 * @param docSupplierId 문서의 거래처. 모르면 null — 그 줄은 사전에 들어가지 않으니 포장 환산을 묻지 않는다
	 * @param product       문서 거래처의 같은 상품명 사전 상품. 없으면 null
	 * @param ingredient    연결될 재료. 없으면 null
	 */
	public static Set<Reason> reasons(PurchaseDocLine line, Long docSupplierId, SupplierProduct product,
			Ingredient ingredient) {
		Set<Reason> reasons = EnumSet.noneOf(Reason.class);
		boolean dictionaryTarget = dictionaryTarget(line, docSupplierId);
		if (ingredient == null) {
			reasons.add(Reason.UNMAPPED);
			// 주기형으로 확정해도 사전에 들어갈 줄이면 포장 환산이 필요하다(S15P31A308-112 확정 검사) — 화면이 미리 묻게
			if (dictionaryTarget && (product == null || product.getBaseQtyPerPack() == null)) {
				reasons.add(Reason.UNIT_UNSET);
			}
			return reasons;
		}
		if (lowConfidence(line)) {
			reasons.add(Reason.LOW_CONFIDENCE);
		}
		if (unitUnset(dictionaryTarget, product == null ? null : product.getBaseQtyPerPack(),
				ingredient.getUnitStatus())) {
			reasons.add(Reason.UNIT_UNSET);
		}
		return reasons;
	}

	/**
	 * 확신도 낮음: 사전 매칭이 아닌 줄(supplier_product_id 없음 — LLM 제안)인데 확신도가 비었거나 {@link #LOW_CONFIDENCE_BELOW}
	 * 미만. 사전 매칭 줄은 S15P31A308-111 이 확신도를 비워 두므로 보지 않는다. 자동 확정 후보는 늘 사전 매칭 줄이라 자동 확정 판정은 그대로다.
	 */
	public static boolean lowConfidence(PurchaseDocLine line) {
		if (line.getSupplierProductId() != null) {
			return false;
		}
		BigDecimal confidence = line.getConfidence();
		return confidence == null || confidence.compareTo(LOW_CONFIDENCE_BELOW) < 0;
	}

	/**
	 * 확인 목록 묶음 키 (S15P31A308-113): 상품명 앞뒤 공백만 지운다 — 사전 키({@link #productName})와 주기형 이름과 같은 규칙이라
	 * 한 묶음을 주기형으로 확정하면 재료가 하나만 생긴다. 길이 제한은 두지 않는다. 이름이 없으면 {@code ""}.
	 */
	public static String groupKey(String rawName) {
		return rawName == null ? "" : rawName.strip();
	}

	/**
	 * 단위 미확정: 재료 단위가 추정값(ESTIMATED)이거나, 사전에 들어갈 줄인데 포장 환산을 모른다.
	 * LLM 이 제안한 포장은 여기서 풀지 않는다 — 사장님이 직접 확인해야 풀린다(설계 3.1).
	 */
	public static boolean unitUnset(boolean dictionaryTarget, BigDecimal knownBaseQtyPerPack, UnitStatus unitStatus) {
		return unitStatus == UnitStatus.ESTIMATED || (dictionaryTarget && knownBaseQtyPerPack == null);
	}

	/** 사전에 들어갈 줄: 문서 거래처가 있고 상품명(앞뒤 공백 제거)이 비지 않고 150자 이하. */
	public static boolean dictionaryTarget(PurchaseDocLine line, Long docSupplierId) {
		return docSupplierId != null && productName(line) != null;
	}

	/** 사전 키 = 줄 상품명 앞뒤 공백 제거(S15P31A308-111 lookupDictionary 와 같은 키). 비었거나 150자를 넘으면 null. */
	public static String productName(PurchaseDocLine line) {
		String name = line.getRawName() == null ? "" : line.getRawName().strip();
		if (name.isEmpty() || name.codePointCount(0, name.length()) > PRODUCT_NAME_MAX) {
			return null;
		}
		return name;
	}

	/**
	 * LLM 이 제안한 포장 1개 양({@code raw.match.pack}) — 단위가 재료 기준 단위와 같고 칸에 들어갈 때만. 확인 목록의 기본값용.
	 *
	 * @return 없으면 null
	 */
	public static BigDecimal suggestedBaseQty(JsonNode raw, IngredientUnit unit) {
		JsonNode pack = raw == null ? null : raw.path("match").path("pack");
		if (pack == null || !pack.isObject() || unit == null) {
			return null;
		}
		IngredientUnit packUnit = packUnit(raw);
		JsonNode qty = pack.get("qty");
		if (packUnit != unit || qty == null || !qty.isNumber()) {
			return null;
		}
		if ((qty.isDouble() || qty.isFloat()) && !Double.isFinite(qty.asDouble())) {
			return null;
		}
		BigDecimal value = qty.decimalValue();
		if (value.signum() <= 0) {
			return null;
		}
		value = value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
		return validBaseQty(value) ? value : null;
	}

	/** LLM 포장 단위(g · ml · ea) → 재료 기준 단위. 없거나 모르는 값이면 null. */
	public static IngredientUnit packUnit(JsonNode raw) {
		JsonNode unit = raw == null ? null : raw.path("match").path("pack").get("unit");
		if (unit == null || !unit.isString()) {
			return null;
		}
		return switch (unit.asString().strip().toLowerCase(Locale.ROOT)) {
			case "g" -> IngredientUnit.G;
			case "ml" -> IngredientUnit.ML;
			case "ea" -> IngredientUnit.EA;
			default -> null;
		};
	}

	/** 포장 환산 값이 NUMERIC(14,3) 양수로 들어가는지: 0 보다 크고 소수 셋째 자리까지, 전체 14자리 이하. */
	public static boolean validBaseQty(BigDecimal value) {
		if (value == null || value.signum() <= 0) {
			return false;
		}
		if (value.stripTrailingZeros().scale() > QTY_SCALE) {
			return false;
		}
		return value.setScale(QTY_SCALE, RoundingMode.UNNECESSARY).precision() <= QTY_PRECISION;
	}
}
