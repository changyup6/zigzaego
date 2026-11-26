"""좌표: 행사 · 역은 위경도(EPSG:4326), 상권 중심점은 EPSG:5181(m)."""

import struct

import numpy as np
import pandas as pd
from pyproj import Transformer

_TO_5181 = Transformer.from_crs(4326, 5181, always_xy=True)
_TO_4326 = Transformer.from_crs(5181, 4326, always_xy=True)

Ring = list[tuple[float, float]]


def to_5181(lat: float, lon: float) -> tuple[float, float]:
    x, y = _TO_5181.transform(lon, lat)
    return float(x), float(y)


def to_4326(x: float, y: float) -> tuple[float, float]:
    """EPSG:5181 → (lat, lon)."""
    lon, lat = _TO_4326.transform(x, y)
    return float(lat), float(lon)


def read_polygons(shp: bytes) -> list[list[Ring]]:
    """SHP(Polygon, type 5) → 레코드마다 링 목록. 의존성 없이 82장소 영역만 읽으면 돼서 직접 파싱한다."""
    out, pos = [], 100
    while pos + 8 <= len(shp):
        length = struct.unpack(">i", shp[pos + 4:pos + 8])[0] * 2
        rec = shp[pos + 8:pos + 8 + length]
        pos += 8 + length
        if struct.unpack("<i", rec[:4])[0] == 0:  # 빈 도형
            out.append([])
            continue
        n_parts, n_points = struct.unpack("<2i", rec[36:44])
        starts = list(struct.unpack(f"<{n_parts}i", rec[44:44 + 4 * n_parts])) + [n_points]
        base = 44 + 4 * n_parts
        pts = [struct.unpack("<2d", rec[base + 16 * k: base + 16 * k + 16]) for k in range(n_points)]
        out.append([pts[starts[i]:starts[i + 1]] for i in range(n_parts)])
    return out


def point_in_polygon(x: float, y: float, rings: list[Ring]) -> bool:
    """짝홀 규칙: 모든 링을 함께 세므로 구멍(안쪽 링) 안은 바깥으로 본다."""
    inside = False
    for ring in rings:
        for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
            if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
                inside = not inside
    return inside


def fix_latlon(a, b) -> tuple[float, float] | None:
    """두 값 중 33~39 를 위도, 124~132 를 경도로 본다 (문화행사 API 는 LOT · LAT 가 뒤바뀌어 오기도 함)."""
    try:
        a, b = float(a), float(b)
    except (TypeError, ValueError):
        return None
    for lat, lon in ((a, b), (b, a)):
        if 33 <= lat <= 39 and 124 <= lon <= 132:
            return lat, lon
    return None


def districts_within(x: float, y: float, areas: pd.DataFrame, radius_m: float) -> list[str]:
    """반경 안 모든 상권 (가까운 순)."""
    d = np.hypot(areas["x"].to_numpy() - x, areas["y"].to_numpy() - y)
    order = np.argsort(d)
    return areas["TRDAR_CD"].to_numpy()[order[d[order] <= radius_m]].tolist()


def nearest_district(x: float, y: float, areas: pd.DataFrame, radius_m: float = 500) -> str | None:
    d = np.hypot(areas["x"].to_numpy() - x, areas["y"].to_numpy() - y)
    i = int(d.argmin())
    return areas["TRDAR_CD"].iloc[i] if d[i] <= radius_m else None
