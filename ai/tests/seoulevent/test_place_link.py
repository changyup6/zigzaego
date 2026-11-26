import struct
from datetime import date, timedelta

import pandas as pd
import pytest

from weglee_ai.seoulevent.build import geo, place_link

# 서울 시청 부근 0.01도 정사각형 + 가운데 구멍
SQUARE = [(126.97, 37.56), (126.98, 37.56), (126.98, 37.57), (126.97, 37.57), (126.97, 37.56)]
HOLE = [(126.974, 37.564), (126.974, 37.566), (126.976, 37.566), (126.976, 37.564), (126.974, 37.564)]


def _shp(polygons: list[list[list[tuple[float, float]]]]) -> bytes:
    """테스트용 최소 SHP (Polygon, type 5)."""
    records = b""
    for i, parts in enumerate(polygons, 1):
        pts = [p for part in parts for p in part]
        starts, n = [], 0
        for part in parts:
            starts.append(n)
            n += len(part)
        content = struct.pack("<i4d2i", 5, 0, 0, 0, 0, len(parts), len(pts))
        content += struct.pack(f"<{len(parts)}i", *starts) + b"".join(struct.pack("<2d", *p) for p in pts)
        records += struct.pack(">2i", i, len(content) // 2) + content
    header = struct.pack(">7i", 9994, 0, 0, 0, 0, 0, (100 + len(records)) // 2) + struct.pack("<2i4d4d", 1000, 5, 0, 0, 0, 0, 0, 0, 0, 0)
    return header + records


def test_read_polygons_and_point_in_polygon_with_hole():
    polys = geo.read_polygons(_shp([[SQUARE, HOLE], [SQUARE]]))
    assert len(polys) == 2 and len(polys[0]) == 2
    assert geo.point_in_polygon(126.971, 37.561, polys[0])
    assert not geo.point_in_polygon(126.975, 37.565, polys[0])  # 구멍 안
    assert not geo.point_in_polygon(126.99, 37.565, polys[0])


def _places():
    return pd.DataFrame({"area_cd": ["POI009"], "area_nm": ["광화문·덕수궁"], "parts": [[SQUARE]]})


def test_map_districts_by_centroid_inside_place():
    x_in, y_in = geo.to_5181(37.565, 126.975)
    x_out, y_out = geo.to_5181(37.60, 126.975)
    areas = pd.DataFrame({"TRDAR_CD": ["IN", "OUT"], "TRDAR_CD_NM": ["안", "밖"], "x": [x_in, x_out], "y": [y_in, y_out]})
    m = place_link.map_districts(_places(), areas)
    assert m.to_dict("records") == [{"area_cd": "POI009", "trdar_cd": "IN"}]


def test_place_events_inside_polygon_only():
    ev = pd.DataFrame({"event_id": ["a", "b", "h"], "source": ["culture", "culture", "holiday"], "kind": ["축제-기타", "클래식", "holiday"],
                       "name": ["축제", "공연", "공휴일"], "start_date": [date(2026, 10, 10)] * 3, "end_date": [date(2026, 10, 10)] * 3,
                       "lat": [37.561, 37.70, None], "lon": [126.971, 126.97, None]})
    out = place_link.place_events(_places(), ev)
    assert out["event_id"].tolist() == ["a"] and out["area_cd"].tolist() == ["POI009"]


def test_place_day_baseline_festival_holiday():
    start = date(2026, 10, 5)  # 월요일
    rtd = pd.DataFrame({"area_cd": "POI009", "area_nm": "광화문·덕수궁", "date": [start + timedelta(days=i) for i in range(28)],
                        "amt": [100] * 28, "cnt": [10] * 28})
    rtd.loc[rtd["date"] == date(2026, 10, 10), "amt"] = 300  # 토요일 하나만 3배
    pev = pd.DataFrame({"area_cd": ["POI009"], "event_id": ["a"], "kind": ["축제-기타"], "name": ["축제"],
                        "start_date": [date(2026, 10, 10)], "end_date": [date(2026, 10, 10)]})
    hol = pd.DataFrame({"date": [date(2026, 10, 9)], "name": ["한글날"], "is_holiday": [True]})
    weather = pd.DataFrame({"date": [date(2026, 10, 10)], "avg_ta": [15.0], "sum_rn": [0.0]})
    day = place_link.build_place_day(rtd, pev, hol, weather).set_index("date")
    sat = day.loc[date(2026, 10, 10)]
    assert sat["amt_base"] == 100 and sat["sales_ratio"] == pytest.approx(3.0)  # 자기 자신은 기준선에서 뺀다
    assert sat["festival_count"] == 1 and sat["events"] == ["축제"] and sat["avg_ta"] == 15.0
    assert bool(day.loc[date(2026, 10, 9), "is_holiday"]) and day.loc[date(2026, 10, 11), "festival_count"] == 0
