"""이벤트 통합 표: 문화행사 · 공휴일 · 수동 이벤트 → 상권 매핑."""

import hashlib
import re
from datetime import date
from functools import cache
from pathlib import Path

import numpy as np
import pandas as pd

from weglee_ai.seoulevent import config

from weglee_ai.seoulevent.build import geo
from weglee_ai.seoulevent.build.district_day import BIG_SEATS, FESTIVAL_PREFIX
from weglee_ai.seoulevent.collect_kbo import JAMSIL
from weglee_ai.seoulevent.collect_kleague import VENUES
from weglee_ai.seoulevent.collect_stadium import SITES, classify

# 경기장 좌표 · 수용 인원 (경기 · 콘서트 규모는 경기장 크기로). shortcut: 고척은 대략값 — 공식 좌표 · 좌석 확인 시 교체
HANGANG_BIG = re.compile(r"드론|불꽃|축제|페스티벌|페스타|festival|빛섬|라이트\s*쇼|퍼레이드|마라톤|콘서트|공연", re.I)


def hangang_big(title: str) -> bool:
    """사람이 크게 모이는 한강 행사인가 (교실 · 체험 · 책 · 오픈 마이크 같은 작은 상시 프로그램이 아닌)."""
    return bool(HANGANG_BIG.search(title)) and not re.search(r"교실|체험|클래스|책\s*읽|오픈\s*마이크|접수", title)


STADIUMS = {"worldcupst": VENUES["서울 월드컵"], "skydome": (37.498222, 126.867250, 16744)}
# KFA 경기장 이름(공백 뺌) → 서울 경기장 좌표 · 수용 인원. 월드컵경기장은 경기장 달력과 장소 이름이 같아야 과거 반응을 함께 배운다 (SITES['worldcupst'])
KFA_VENUES = {SITES["worldcupst"]: STADIUMS["worldcupst"], "목동종합운동장": VENUES["목동"], "잠실종합운동장": VENUES["잠실"],
              "잠실올림픽주경기장": VENUES["잠실"]}
# 마라톤 장소 문구 키워드 → 가까운 역 (좌표는 ref/stations.csv 의 그 역 노선 평균). 앞에서부터 처음 맞는 것 — 긴 이름을 먼저
MARATHON_PLACES = [("잠실종합운동장", "종합운동장"), ("올림픽주경기장", "종합운동장"), ("종합운동장", "종합운동장"), ("올림픽공원", "올림픽공원"),
                   ("평화의문", "올림픽공원"), ("잠실한강", "잠실새내"), ("잠실", "잠실"), ("광화문", "광화문"), ("청계광장", "광화문"),
                   ("서울광장", "시청"), ("시청", "시청"), ("여의도", "여의나루"), ("여의나루", "여의나루"), ("월드컵공원", "월드컵경기장"),
                   ("평화의공원", "월드컵경기장"), ("평화광장", "월드컵경기장"), ("하늘공원", "월드컵경기장"), ("난지", "월드컵경기장"),
                   ("월드컵경기장", "월드컵경기장"), ("상암", "월드컵경기장"), ("뚝섬", "뚝섬유원지"), ("반포", "고속터미널"),
                   ("잠원", "잠원"), ("망원", "망원"), ("이촌", "이촌"), ("광나루", "광나루"), ("서울숲", "서울숲"), ("양화", "선유도"),
                   ("선유도", "선유도"), ("동대문", "동대문역사문화공원"), ("DDP", "동대문역사문화공원"), ("서울시광장", "시청"),
                   ("시청광장", "시청"), ("고척", "구일"), ("암사", "암사"), ("수서", "수서"), ("명동", "명동"), ("금천구청", "금천구청"),
                   ("도봉산", "도봉산"), ("봉은사", "봉은사")]
STATIONS_CSV = config.REF_DIR / "stations.csv"


@cache
def _station_ll() -> dict:
    st = pd.read_csv(STATIONS_CSV)
    return {k: (g["lat"].mean(), g["lon"].mean()) for k, g in st.groupby("station")}


def marathon_latlon(place) -> tuple[float, float] | None:
    """장소 문구(출발 · 도착 대략)에서 처음 맞는 키워드의 역 좌표. 못 찾으면 None (한강공원만 적힌 경우 등)."""
    text = str(place or "").replace(" ", "")
    return next((_station_ll().get(st) for kw, st in MARATHON_PLACES if kw in text), None)


COLUMNS = ["event_id", "source", "kind", "name", "start_date", "end_date", "lat", "lon", "trdar_cd", "gu", "place", "seats", "collected_at", "popularity"]  # collected_at: 그 일정을 언제 알았나 (공휴일 · 수동은 고정 일정이라 빈 값)


def _date(v) -> date | None:
    if v is None or (isinstance(v, float) and pd.isna(v)) or not str(v).strip():
        return None
    ts = pd.Timestamp(str(v)[:10])
    return None if pd.isna(ts) else ts.date()


DUP_RADIUS_M = 500


def _title_key(s) -> str:
    """괄호 속 공연장 · 부제와 기호 · 공백을 지운 제목 — '[세종문화회관] CHANGMO : MONOLITH' = 'CHANGMO: MONOLITH'."""
    return re.sub(r"\[[^\]]*\]|\([^)]*\)|<[^>]*>|〈[^〉]*〉|[^0-9A-Za-z가-힣]", "", str(s)).lower()


DUP_COLUMNS = ["name", "place", "start_date", "end_date", "name_k", "place_k", "start_date_k", "end_date_k", "dist_m"]


def drop_culture_duplicates(out: pd.DataFrame, radius_m: float = DUP_RADIUS_M, keep_sessions: bool = False) -> tuple[pd.DataFrame, pd.DataFrame]:
    """문화행사 중 KOPIS 공연과 같은 행사(정리한 제목이 같고 · 기간이 겹치고 · 좌표가 radius_m 안)를 뺀다 — 좌석 · 인기가 있는 KOPIS 쪽을 남긴다.
    2026-11-06 기준 문화행사 1,334건(7%, 대부분 클래식 · 독주회)이 해당 (ChatGPT 25 교차 원천 중복).
    지운 짝도 돌려준다 — build 가 data/out/culture_kopis_dups.csv 로 남겨 표본 검수 · 추적에 쓴다 (ChatGPT 26)."""
    none = pd.DataFrame(columns=DUP_COLUMNS)
    cul, kop = out[out["source"] == "culture"], out[out["source"] == "kopis"]
    if cul.empty or kop.empty:
        return out, none
    m = (cul.assign(k=cul["name"].map(_title_key)).reset_index()
         .merge(kop.assign(k=kop["name"].map(_title_key))[["k", "name", "place", "start_date", "end_date", "lat", "lon"]], on="k", suffixes=("", "_k")))
    m = m[(m["k"].str.len() >= 4) & (m["start_date"] <= m["end_date_k"]) & (m["start_date_k"] <= m["end_date"])
          & m["lat"].notna() & m["lat_k"].notna()]
    if m.empty:
        return out, none
    xy = np.array([geo.to_5181(a, b) for a, b in zip(m["lat"], m["lon"])])
    xyk = np.array([geo.to_5181(a, b) for a, b in zip(m["lat_k"], m["lon_k"])])
    m = m.assign(dist_m=np.hypot(*(xy - xyk).T).round(0))
    hit = m[m["dist_m"] <= radius_m]
    if keep_sessions:  # 31단계 후보: KOPIS 가 8일 이상(패키지 · 전체 기간) · 문화행사가 7일 이하(회차)면 회차 정보를 지키려고 문화행사를 남긴다 (보고 22 §2)
        days = lambda s, e: (pd.to_datetime(e) - pd.to_datetime(s)).dt.days + 1
        hit = hit[~((days(hit["start_date_k"], hit["end_date_k"]) > 7) & (days(hit["start_date"], hit["end_date"]) <= 7))]
    return out.drop(index=list(set(hit["index"]))).reset_index(drop=True), hit[DUP_COLUMNS].reset_index(drop=True)


def _event_id(*parts) -> str:
    return hashlib.sha1("|".join(map(str, parts)).encode()).hexdigest()[:12]


def build_events(cultural: pd.DataFrame, holidays: pd.DataFrame, manual: pd.DataFrame, areas: pd.DataFrame,
                 radius_m: float = 500, kopis: pd.DataFrame | None = None,
                 kleague: pd.DataFrame | None = None, stadium: pd.DataFrame | None = None,
                 hangang: pd.DataFrame | None = None, big_venue_m: float = 1000, hangang_mode: str = "bigonly",
                 park_festival_m: float = 0, stadium_legacy: bool = False, kbo: pd.DataFrame | None = None, dedup: str = "on",
                 kfa: pd.DataFrame | None = None, marathon: pd.DataFrame | None = None) -> pd.DataFrame:
    """kopis: KOPIS 공연에 공연장 좌표 · 좌석 수(lat · lon · seats)를 붙인 표. 좌석 수가 행사 규모다.
    kfa: 국가대표 경기(collect_kfa). 서울 경기장 경기만, 경기장 달력에 같은 날 국가대표 행사가 이미 있으면 뺀다.
    marathon: 마라톤 대회(collect_marathon). 서울 대회만, 취소 대회는 뺀다. 위치는 장소 문구 키워드 → 역 좌표, 큰 행사처럼 반경 안 모든 상권."""
    rows = []
    # 매일 쌓은 수집일 파일에 같은 행사가 반복된다 → 제목 · 장소 · 시작일로 하나만
    culture = cultural.sort_values("collected_at").drop_duplicates(["TITLE", "PLACE", "STRTDATE"], keep="last")
    for r in culture.itertuples(index=False):
        ll = geo.fix_latlon(r.LOT, r.LAT)
        rows.append({"event_id": _event_id("culture", r.TITLE, r.PLACE, r.STRTDATE), "source": "culture",
                     "kind": r.CODENAME, "name": r.TITLE, "start_date": _date(r.STRTDATE), "end_date": _date(r.END_DATE),
                     "lat": ll[0] if ll else None, "lon": ll[1] if ll else None, "gu": r.GUNAME, "place": r.PLACE,
                     "collected_at": r.collected_at})
    for r in holidays[holidays["is_holiday"].astype(bool)].itertuples(index=False):
        # 어린이날 · 부처님오신날처럼 같은 날 공휴일이 둘일 수 있어 이름까지 id 에 넣는다
        rows.append({"event_id": _event_id("holiday", r.date, r.name), "source": "holiday", "kind": "holiday", "name": r.name,
                     "start_date": _date(r.date), "end_date": _date(r.date), "lat": None, "lon": None, "gu": None, "place": None})
    for r in manual.itertuples(index=False):
        ll = geo.fix_latlon(r.lat, r.lon)
        rows.append({"event_id": _event_id("manual", r.name, r.venue, r.date_start), "source": "manual", "kind": r.kind,
                     "name": r.name, "start_date": _date(r.date_start), "end_date": _date(r.date_end),
                     "lat": ll[0] if ll else None, "lon": ll[1] if ll else None, "gu": None, "place": r.venue})

    for r in (kopis if kopis is not None else pd.DataFrame()).itertuples(index=False):
        ll = geo.fix_latlon(r.lat, r.lon)
        rows.append({"event_id": _event_id("kopis", r.mt20id), "source": "kopis", "kind": r.genrenm, "name": r.prfnm,
                     "start_date": _date(r.start_date), "end_date": _date(r.end_date),
                     "lat": ll[0] if ll else None, "lon": ll[1] if ll else None, "gu": None, "place": r.fcltynm, "seats": r.seats, "popularity": getattr(r, "popularity", 0.0),
                     "collected_at": getattr(r, "collected_at", None)})

    for r in (kleague if kleague is not None else pd.DataFrame()).itertuples(index=False):
        # 규모 = 같은 홈팀 이전 경기 평균 관중 (미래 경기도 알 수 있는 값)
        rows.append({"event_id": _event_id("kleague", r.date, r.home), "source": "kleague", "kind": "축구",
                     "name": f"{r.home} vs {r.away}", "start_date": _date(r.date), "end_date": _date(r.date),
                     "lat": r.lat, "lon": r.lon, "gu": None, "place": r.field_full, "seats": r.expected_audience,
                     "collected_at": getattr(r, "collected_at", None)})

    for r in (kbo if kbo is not None else pd.DataFrame(columns=["date", "away", "home", "series", "note"])).itertuples(index=False):
        # 잠실 프로야구 (collect_kbo). 시범 · 정규 · 포스트시즌을 장소 이름에 붙여 과거 반응을 따로 배운다. 취소 경기는 뺀다
        if "취소" in str(r.note):
            continue
        # ID 에 시각: 더블헤더 두 경기를 따로 (ChatGPT 25)
        rows.append({"event_id": _event_id("kbo", r.date, r.home, getattr(r, "time", "")), "source": "kbo", "kind": "야구", "name": f"{r.away} vs {r.home}",
                     "start_date": _date(r.date), "end_date": _date(r.date), "lat": JAMSIL[0], "lon": JAMSIL[1], "gu": None,
                     "place": f"잠실야구장 {r.series}", "seats": JAMSIL[2], "collected_at": getattr(r, "collected_at", None)})

    for r in (stadium if stadium is not None else pd.DataFrame(columns=["site", "date", "text", "collected_at"])).itertuples(index=False):
        # 경기장 공식 달력 (국가대표 · 친선 · 클럽 대회 · 프로야구 · 콘서트). 분류를 장소 이름에 붙여 분류마다 과거 반응을 따로 배운다
        cat = classify(r.text, legacy=stadium_legacy)
        if cat is None:
            continue
        site = getattr(r, "site", "worldcupst") or "worldcupst"
        lat, lon, capacity = STADIUMS[site]
        kind = ("대중음악" if cat == "콘서트" else "야구" if cat.startswith("프로야구") else "행사" if cat == "기타 행사" else "축구")
        rows.append({"event_id": _event_id("stadium", site, r.date, r.text), "source": "stadium", "kind": kind,
                     "name": r.text.splitlines()[0].strip(), "start_date": _date(r.date), "end_date": _date(r.date),
                     "lat": lat, "lon": lon, "gu": None, "place": f"{SITES[site]} {cat}",
                     "seats": capacity if kind != "행사" else None, "collected_at": r.collected_at})

    # 국가대표 경기 (collect_kfa): 경기장 달력에 빠진 날만 채운다 (32단계, 예: 2023-11-16 싱가포르전)
    known = {(x["start_date"], x["place"]) for x in rows if x["source"] == "stadium"}
    kfa_dups = 0
    for r in (kfa if kfa is not None else pd.DataFrame(columns=["date", "team", "opponent", "venue"])).itertuples(index=False):
        name = str(r.venue).replace(" ", "")
        if name not in KFA_VENUES:
            continue  # 서울 밖 · 해외 경기
        place, d = f"{name} 국가대표", _date(r.date)
        if (d, place) in known:
            kfa_dups += 1
            continue
        lat, lon, capacity = KFA_VENUES[name]
        rows.append({"event_id": _event_id("kfa", r.date, r.team, name), "source": "kfa", "kind": "축구",
                     "name": f"{r.team} vs {r.opponent}" if r.opponent else r.team, "start_date": d, "end_date": d,
                     "lat": lat, "lon": lon, "gu": None, "place": place, "seats": capacity, "collected_at": getattr(r, "collected_at", None)})

    for r in (marathon if marathon is not None else pd.DataFrame(columns=["no", "date", "name", "place", "is_seoul"])).itertuples(index=False):
        # 도심 마라톤 (32단계, 예: 서울마라톤 · JTBC 마라톤) — 교통 통제 · 인파로 급증. 축제로 세어지게 kind 를 '축제-' 로
        if not r.is_seoul or "취소" in str(r.name):
            continue
        ll = marathon_latlon(r.place)
        rows.append({"event_id": _event_id("marathon", r.no), "source": "marathon", "kind": "축제-마라톤", "name": r.name,
                     "start_date": _date(r.date), "end_date": _date(r.date), "lat": ll[0] if ll else None, "lon": ll[1] if ll else None,
                     "gu": None, "place": r.place, "collected_at": getattr(r, "collected_at", None)})

    for r in (hangang if hangang is not None else pd.DataFrame()).itertuples(index=False):
        # 한강공원 행사. hangang_mode: bigonly(기본, 11단계 평균정밀도 0.47 > split 0.44 > nosplit 0.42) = 대형만 · split = 대형은 축제, 나머지는 '한강-소규모' 로 따로 —
        # 소규모도 버리지 않고 공원별 과거 반응을 따로 배우게 한다 (10단계: 섞으면 축제 헛경보 4 → 19) · nosplit = 모두 축제 · bigonly = 대형만
        big = hangang_big(r.title)
        if hangang_mode == "bigonly" and not big:
            continue
        small = hangang_mode == "split" and not big
        ll = geo.fix_latlon(r.lon, r.lat)
        # ID 에 종료일: 긴 행사 대표 행과 첫 회차를 따로 (ChatGPT 25)
        rows.append({"event_id": _event_id("hangang", r.evnt_sn, r.start, r.end), "source": "hangang", "kind": "한강-소규모" if small else "축제-한강",
                     "name": r.title, "start_date": _date(r.start), "end_date": _date(r.end), "lat": ll[0] if ll else None,
                     "lon": ll[1] if ll else None, "gu": None, "place": f"{r.park} 소규모" if small else r.park, "collected_at": r.collected_at})

    out = pd.DataFrame(rows, columns=COLUMNS)
    # 하루짜리 행사는 종료일을 비워 두기 쉽다 → 시작일로. 시작일이 없거나 거꾸로인 행은 기간을 알 수 없어 버린다
    out["end_date"] = out["end_date"].where(out["end_date"].notna(), out["start_date"])
    ok = out["start_date"].notna() & (out["start_date"] <= out["end_date"])
    dropped = int((~ok).sum())
    out = out[ok].reset_index(drop=True)
    out, dups = (out, pd.DataFrame(columns=DUP_COLUMNS)) if dedup == "off" else drop_culture_duplicates(out, keep_sessions=dedup == "keep_sessions")
    # 큰 공연장 · 경기장은 반경 1km 안 모든 상권에 붙인다 — 고척돔 · 올림픽공원은 500m 안에 승하차 상권이 없어 행사가 빠졌고,
    # 실제 붐비는 곳(한성백제역 · 방이동 · 구일역 쪽)은 가장 가까운 상권이 아니었다. 작은 행사는 가장 가까운 상권 하나
    # 한강공원 행사 · 마라톤도 강변 · 광장 좌표라 500m 안 상권이 드물어 같은 반경으로
    big = (pd.to_numeric(out["seats"], errors="coerce").fillna(0) >= BIG_SEATS) | out["source"].isin(["hangang", "marathon"])
    # 공원 안 축제(장미 · 억새 · 한성백제문화제 등)는 좌표가 공원 한가운데라 500m 안 상권이 없어 빠졌다 (25단계 원인 조사 18건)
    # → 500m 안에 상권이 하나도 없는 축제만 park_festival_m 안 모든 상권에 붙인다
    fest = out["kind"].astype(str).str.startswith(FESTIVAL_PREFIX)
    codes = []
    for lat, lon, is_big, is_fest in zip(out["lat"], out["lon"], big, fest):
        if pd.isna(lat):
            codes.append([None])
            continue
        x, y = geo.to_5181(lat, lon)
        near = geo.districts_within(x, y, areas, big_venue_m) if is_big and big_venue_m > 0 else []
        code = None if near else geo.nearest_district(x, y, areas, radius_m)
        if not near and code is None and is_fest and park_festival_m > 0:
            near = geo.districts_within(x, y, areas, park_festival_m)
        codes.append(near or [code])
    out = out.assign(trdar_cd=codes).explode("trdar_cd", ignore_index=True)
    out.attrs["dropped"] = dropped
    out.attrs["kfa_dups"] = kfa_dups
    out.attrs["culture_dups"] = dups
    return out
