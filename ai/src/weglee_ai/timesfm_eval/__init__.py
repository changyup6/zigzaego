"""TimesFM 채택 검증 실험 — 요일 평균(baseline.py) 대비 TimesFM 2.5 를 공개 데이터로 비교한다 (DECISIONS #24 근거 검증).

- adoption: rolling 하루 앞 예측으로 두 모델을 채점하고 이력 길이별로 판정
- blend: 저장된 예측값만으로 두 모델을 비율 w 로 섞어 주 단위로 다시 채점
- equivalence: 두 모델 오차 차이를 7일 블록 부트스트랩으로 '같다 · 낫다 · 못하다 · 판단 불가' 판정

실행 스크립트는 scripts/timesfm_eval/, 결과 문서는 docs/plans/2026-10-19_TimesFM_채택검증.md.
"""
