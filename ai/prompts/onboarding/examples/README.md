# 온보딩 프롬프트 데모

> **GMS 아님, Claude 수기 데모.** GMS 키가 아직 없어 실제 LLM 호출 결과가 아니다.
> Claude 가 `../menu_extraction.md` · `../ingredient_classify.md` 지시를 그대로 따라 손으로 쓴 출력이다.
> GMS 가 붙으면 같은 입력으로 다시 돌려 이 파일을 바꾸고, 차이를 결과 문서에 적는다.

| 파일 | 내용 |
|---|---|
| `demo_menus.json` | 입력 8개. `docs/spec/PRODUCT.md` 공개 메뉴 7개 + 프론트 목 데이터의 "더블 불고기 파스타볼". 설명은 공개 정보에 있는 세트 구성만 넣었다 |
| `demo_extraction.json` | 구성 추출 출력 (`../menu_extraction.schema.json` 모양) |
| `demo_classify.json` | 범용 재료표에 없는 재료 6개의 분류 출력 (`../ingredient_classify.schema.json` 모양) |

질문 목록은 `ai/scripts/onboarding/demo_questions.py` 로 본다.

백엔드 테스트 픽스처로 그대로 복사해 써도 된다. 이 데모는 특정 가게 데이터이므로 프롬프트 · 코드에는 넣지 않는다.
