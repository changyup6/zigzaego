-- 전체 주소는 배송 판정용 시군구와 별도 보관한다 (S15P31A308-357, ADR-0023).
ALTER TABLE store ADD COLUMN address TEXT;

-- 기존 가게도 같은 placeId로 확인된 최신 비어 있지 않은 SEARCH 후보 주소만 연결한다.
-- 다른 가게 후보나 메뉴/분석 저장본은 주소의 근거로 쓰지 않는다.
UPDATE store s
SET address = candidate.address
FROM (
    SELECT DISTINCT ON (entry ->> 'id')
        entry ->> 'id' AS place_id,
        btrim(entry ->> 'address') AS address
    FROM onboarding_replay r
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN r.kind = 'SEARCH' AND jsonb_typeof(r.payload) = 'array'
             THEN r.payload ELSE '[]'::jsonb END
    ) AS candidates(entry)
    WHERE r.kind = 'SEARCH'
      AND jsonb_typeof(entry -> 'address') = 'string'
      AND nullif(btrim(entry ->> 'address'), '') IS NOT NULL
    ORDER BY entry ->> 'id', r.captured_at DESC, r.id DESC
) candidate
WHERE s.naver_place_id = candidate.place_id;

COMMENT ON COLUMN store.address IS '선택한 네이버 플레이스의 서버 수집 전체 주소. 더보기 표시용이며 배송 판정은 sigungu를 사용한다. 미확인 주소는 NULL.';
