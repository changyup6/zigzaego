"""`python -m weglee_ai.seoulevent.build` → data/out/{events,district_quarter,district_day}.parquet

아직 안 모인 원천(문화행사 · 공휴일 · 날씨 · 역 좌표)은 빈 표로 두고 만들 수 있는 것만 만든다.
"""

import os
from datetime import date
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import collect_kbo, collect_kopis, collect_kopis_rank, collect_subway, config
from weglee_ai.seoulevent.build import place_link
from weglee_ai.seoulevent.build.candidates import rank_candidates
from weglee_ai.seoulevent.build.district_day import build_bus_day, build_district_day, clean_baseline, with_venue_areas
from weglee_ai.seoulevent.build.district_quarter import build_district_quarter
from weglee_ai.seoulevent.build.events import build_events
from weglee_ai.seoulevent.build.spikes import detect_spikes

MANUAL = config.REF_DIR / "manual_events.csv"
STATIONS = config.REF_DIR / "stations.csv"


def _read(path: Path, columns: list[str]) -> pd.DataFrame:
    if path.is_dir():
        files = sorted(path.glob("*.parquet"))
        return pd.concat([pd.read_parquet(f) for f in files], ignore_index=True) if files else pd.DataFrame(columns=columns)
    return pd.read_parquet(path) if path.exists() else pd.DataFrame(columns=columns)


def main() -> None:
    raw = config.load().data_dir / "raw"
    out = raw.parent / "out"
    out.mkdir(parents=True, exist_ok=True)
    missing = []

    areas = with_venue_areas(pd.read_parquet(raw / "district_areas" / "areas.parquet"))
    cultural = _read(raw / "cultural_events", ["TITLE", "CODENAME", "GUNAME", "PLACE", "STRTDATE", "END_DATE", "LOT", "LAT", "collected_at"])
    holidays = _read(raw / "holidays.parquet", ["date", "name", "is_holiday"])
    manual = pd.read_csv(MANUAL, dtype=str)
    manual = manual[~manual["name"].str.startswith("(예시)")]  # 양식의 예시 행은 빼고
    for name, df in (("문화행사", cultural), ("공휴일", holidays), ("수동 이벤트", manual)):
        if df.empty:
            missing.append(name)

    kopis = None
    kdir = raw / "kopis"
    if (kdir / "performances.parquet").exists() and (kdir / "facilities.parquet").exists():
        perf, fac = pd.read_parquet(kdir / "performances.parquet"), pd.read_parquet(kdir / "facilities.parquet")
        names = dict(zip(fac["fcltynm"], fac["mt10id"]))
        perf["mt10id"] = perf["fcltynm"].map(collect_kopis.venue_matcher(names))
        kopis = perf.merge(fac[["mt10id", "lat", "lon", "max_hall_seats"]].rename(columns={"max_hall_seats": "seats"}), on="mt10id", how="left")
        box_path = kdir / "boxoffice.parquet"  # 공연 시작 전 서울 예매 순위 → 인기 (collect_kopis_rank)
        box = pd.read_parquet(box_path) if box_path.exists() else pd.DataFrame(columns=["week_end", "mt20id", "rnum"])
        kopis["popularity"] = collect_kopis_rank.pre_show_popularity(kopis, box)
    else:
        missing.append("KOPIS 공연(collect_kopis)")
    games_path = raw / "kleague" / "games.parquet"
    kleague = pd.read_parquet(games_path) if games_path.exists() else None
    if kleague is None:
        missing.append("K리그 경기(collect_kleague)")
    kbo_path = raw / "kbo" / "games.parquet"
    kbo = pd.read_parquet(kbo_path) if kbo_path.exists() else None
    if kbo is None:
        missing.append("잠실 프로야구(collect_kbo)")
    stadium_path = raw / "stadium" / "events.parquet"
    stadium = pd.read_parquet(stadium_path) if stadium_path.exists() else None
    if stadium is None:
        missing.append("월드컵경기장 일정(collect_stadium)")
    kfa_path = raw / "kfa" / "matches.parquet"
    kfa = pd.read_parquet(kfa_path) if kfa_path.exists() else None
    if kfa is None:
        missing.append("국가대표 경기(collect_kfa)")
    marathon_path = raw / "marathon" / "races.parquet"
    marathon = pd.read_parquet(marathon_path) if marathon_path.exists() else None
    if marathon is None:
        missing.append("마라톤 대회(collect_marathon)")
    # SEOULEVENT_VARIANT=near · 2km · 3km (연결 반경) · hg_nosplit · hg_bigonly (한강 행사 구분) · fix_none · fix_coupang · fix_fest (25단계): 비교용 → events_<v> · district_day_<v> 만 만든다
    # 31단계 정정별 되돌리기 (ChatGPT 26 — 몫 기록용, 채택 재결정 아님): rev_station = 양평역 분리 전 · rev_hangang = 한강 회차 장소 읽기 전
    # (raw/hangang/events_before_session_place.parquet, f48101a 이전 수집본) · rev_dedup = 문화행사↔KOPIS 중복 제거 전 · dedup_keep = 중복 규칙 개선 후보
    variant = os.getenv("SEOULEVENT_VARIANT", "")
    hangang_path = raw / "hangang" / ("events_before_session_place.parquet" if variant == "rev_hangang" else "events.parquet")
    hangang = pd.read_parquet(hangang_path) if hangang_path.exists() else None
    if hangang is None:
        missing.append("한강공원 행사(collect_hangang)")
    suffix = f"_{variant}" if variant else ""
    # 34단계: 마라톤 전부(서울 840개, 대부분 동네 하천 대회)를 큰 행사로 붙이면 평균정밀도 0.622 → 0.610 → 기본 빌드에서 뺀다 (수집은 계속).
    # marathon_all = 전부(34단계 재현) · marathon_full = 풀코스 종목이 있는 대회만(서울 131개, 큰 대회 대용 — 손으로 고르지 않음)
    if marathon is not None:
        marathon = (marathon if variant == "marathon_all" else
                    marathon[marathon["events"].fillna("").str.contains("풀")] if variant == "marathon_full" else None)
    events = build_events(cultural, holidays, manual, areas, kopis=kopis, kleague=kleague, stadium=stadium, hangang=hangang, kfa=kfa, marathon=marathon,
                          big_venue_m={"near": 0, "2km": 2000, "3km": 3000}.get(variant, 1000),
                          hangang_mode={"hg_nosplit": "nosplit", "hg_split": "split"}.get(variant, "bigonly"),
                          # 25단계 수정 효과 분리 비교: fix_none = 두 수정 전, fix_coupang = 쿠팡플레이 분류만, fix_fest = 공원 축제 연결만
                          park_festival_m=1000 if variant == "fix_fest" else 0,  # 공원 축제 1km: 25단계 비교에서 새 연결 143 상권-일 중 급증 4, 경보로 못 잡음 · 평균정밀도 0.49 → 0.47 → 미채택
                          stadium_legacy=variant in ("fix_none", "fix_fest"),
                          dedup={"rev_dedup": "off", "dedup_keep": "keep_sessions"}.get(variant, "on"),
                          kbo=None if variant in ("fix_none", "fix_coupang", "fix_fest", "no_kbo") or kbo is None
                          else kbo[~collect_kbo.late_known(kbo)] if variant == "kbo_strict" else kbo)
    # 지운 문화행사 ↔ KOPIS 중복 짝 (표본 검수 · 추적용, ChatGPT 26). 표는 parquet attrs(JSON)에 못 넣어 먼저 꺼낸다
    events.attrs.pop("culture_dups", pd.DataFrame()).to_csv(out / f"culture_kopis_dups{suffix}.csv", index=False, encoding="utf-8-sig")
    events.to_parquet(out / f"events{suffix}.parquet", index=False)
    sales_path = raw / "district_sales" / "sales.parquet"  # 상권 분기 매출(분석용, 예측 입력 아님) — 없으면 건너뛴다
    if not suffix and not sales_path.exists():
        missing.append("상권 분기 매출(collect_district) → district_quarter 건너뜀")
    quarter = None if suffix or not sales_path.exists() else build_district_quarter(pd.read_parquet(sales_path), events)
    if quarter is not None:
        quarter.to_parquet(out / "district_quarter.parquet", index=False)
    if quarter is not None:
        pass

    if STATIONS.exists():
        subway, unmatched = collect_subway.attach_coords(pd.read_parquet(raw / "subway_daily.parquet"), pd.read_csv(STATIONS),
                                                         collect_subway.load_alias())
        weather = _read(raw / "weather_seoul.parquet", ["date", "avg_ta", "sum_rn"])
        if weather.empty:
            missing.append("날씨")
        day = build_district_day(subway, weather, events, holidays, areas, split_far_stations=variant != "rev_station")
        city = config.REF_DIR / "citywide_days.csv"  # v3 정답: 기준선에서 큰 행사 · 도시 행사 날을 뺀다 (47단계)
        day = clean_baseline(day, pd.read_csv(city)["date"] if city.exists() else ())
        day.to_parquet(out / f"district_day{suffix}.parquet", index=False)
        if suffix:
            config.write_manifest(out, f"_build_manifest{suffix}.json", {"variant": variant})
            return
        bus_path, stops_path = raw / "bus_daily.parquet", raw / "bus" / "stops.csv"
        if bus_path.exists() and stops_path.exists():  # 버스(collect_bus) — 지하철 표는 그대로 두고 따로 (실험에서 특징으로 붙인다)
            bus_day = build_bus_day(pd.read_parquet(bus_path), pd.read_csv(stops_path, dtype={"stop_id": str}), areas)
            bus_day.to_parquet(out / "district_bus_day.parquet", index=False)
        else:
            missing.append("버스 승하차(collect_bus)")
    else:
        missing.append("역 좌표(ref/stations.csv) → district_day 건너뜀")

    day = pd.read_parquet(out / "district_day.parquet") if (out / "district_day.parquet").exists() else pd.DataFrame(
        columns=["trdar_cd", "date", "riders", "riders_base", "ratio", "event_count", "short_event_count"])
    spikes = detect_spikes(day, areas)
    spikes.to_csv(out / "spikes.csv", index=False, encoding="utf-8-sig")
    citydata_trdars: set[str] = set()
    place_zip = raw.parent / "ref" / "places_82_area.zip"
    if place_zip.exists():
        places = place_link.load_place_areas(place_zip)
        pd_map = place_link.map_districts(places, areas)
        pd_map.to_csv(out / "place_district.csv", index=False, encoding="utf-8-sig")
        citydata_trdars = set(pd_map["trdar_cd"])
        pev = place_link.place_events(places, events)
        pev.to_parquet(out / "place_events.parquet", index=False)
        rtd_path = raw / "rtd" / "rtd_daily.parquet"
        if rtd_path.exists():
            pday = place_link.build_place_day(pd.read_parquet(rtd_path), pev, holidays,
                                              _read(raw / "weather_seoul.parquet", ["date", "avg_ta", "sum_rn"]))
            pday.to_parquet(out / "place_day.parquet", index=False)
        else:
            missing.append("82장소 일별 결제(collect_rtd) → place_day 건너뜀")
    else:
        missing.append("82장소 영역(data/ref/places_82_area.zip) → 장소 연결 건너뜀")

    as_of = max(day["date"]) if len(day) else date.today()  # 승하차 파일은 한 달쯤 늦게 나온다
    cands = rank_candidates(events, day, areas, as_of, spikes=spikes, citydata_trdars=citydata_trdars)
    if len(cands):
        cands.to_csv(out / "candidates.csv", index=False, encoding="utf-8-sig")
    if missing:
        pass
    config.write_manifest(out, "_build_manifest.json", {"variant": "", "missing_sources": missing})


if __name__ == "__main__":
    main()
