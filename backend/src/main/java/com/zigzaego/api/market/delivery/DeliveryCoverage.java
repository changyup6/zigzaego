package com.zigzaego.api.market.delivery;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 가게 하나의 시군구에서 어느 판매처(사이트 · 점포)가 배달하는지 (S15P31A308-230, ADR-0011).
 * {@code market_site_delivery_area} 전체와 가게 시군구로 만들고, 시세 줄마다 {@link #check} 로 묻는다.
 *
 * <ul>
 *   <li>가게 시군구가 없으면 모든 판매처가 {@link Status#UNKNOWN} — 위치를 받기 전엔 판정하지 않는다</li>
 *   <li>배송 지역 줄이 하나도 없는 사이트는 {@link Status#UNKNOWN} ('확인 필요')</li>
 *   <li>줄이 있는 사이트는 (점포 일치 또는 점포 무관) 그리고 (시군구 일치 또는 전국) 인 줄이 있으면 {@link Status#AVAILABLE}, 없으면 {@link Status#BLOCKED}</li>
 * </ul>
 */
public final class DeliveryCoverage {

	public enum Status { AVAILABLE, BLOCKED, UNKNOWN }

	/** 배송 지역 한 줄. {@code storeRegion} null = 사이트 전체, {@code sigungu} null = 전국, {@code dawnDelivery} null = 모름. */
	public record Area(String site, String storeRegion, String sigungu, Boolean dawnDelivery) {
	}

	/** 판정 결과. {@code dawnDelivery} 는 AVAILABLE 일 때 맞은 줄의 값(모르면 null), 그 밖엔 null. */
	public record Verdict(Status status, Boolean dawnDelivery) {

		public static final Verdict UNKNOWN = new Verdict(Status.UNKNOWN, null);
		public static final Verdict BLOCKED = new Verdict(Status.BLOCKED, null);

		public boolean allowed() {
			return status != Status.BLOCKED;
		}
	}

	private final List<Area> areas;
	private final Set<String> knownSites = new HashSet<>();
	private final String sigungu;

	private DeliveryCoverage(List<Area> areas, String sigungu) {
		this.areas = areas;
		this.sigungu = normalize(sigungu);
		for (Area a : areas) {
			knownSites.add(a.site());
		}
	}

	public static DeliveryCoverage of(List<Area> areas, String storeSigungu) {
		return new DeliveryCoverage(areas, storeSigungu);
	}

	/** 판정에 쓴 가게 시군구(공백 정리 뒤). 모르면 null 이고 모든 판정이 UNKNOWN 이다. */
	public String storeSigungu() {
		return sigungu;
	}

	public Verdict check(String site, String storeRegion) {
		if (sigungu == null || !knownSites.contains(site)) {
			return Verdict.UNKNOWN;
		}
		String region = normalize(storeRegion);
		for (Area a : areas) {
			if (!a.site().equals(site)) {
				continue;
			}
			boolean regionOk = a.storeRegion() == null || normalize(a.storeRegion()).equals(region);
			boolean sigunguOk = a.sigungu() == null || normalize(a.sigungu()).equals(sigungu);
			if (regionOk && sigunguOk) {
				return new Verdict(Status.AVAILABLE, a.dawnDelivery());
			}
		}
		return Verdict.BLOCKED;
	}

	/** 앞뒤 공백을 떼고 연속 공백을 하나로. 빈 글자는 null. */
	public static String normalize(String s) {
		if (s == null) {
			return null;
		}
		String t = s.strip().replaceAll("\\s+", " ");
		return t.isEmpty() ? null : t;
	}
}
