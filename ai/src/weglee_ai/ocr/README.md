# OCR 비교 실행

지라 S15P31A308-109. 사진 폴더를 두 방식으로 읽어 예측 JSON을 만들고(`scripts/ocr/run_methods.py`), 정답과 줄 정확도를 비교합니다(`scripts/ocr/evaluate.py`).

- `clova_llm` (방식 A): CLOVA General OCR로 글자를 읽고 GMS LLM이 품목 줄로 정리
- `llm_only` (방식 B): 사진을 GMS 멀티모달 LLM에 바로 넣음

## 파일

```
src/weglee_ai/ocr/
  http.py      공통 POST(urllib) · ApiError · 시간 초과 판별
  clova.py     CLOVA 요청 · 응답 → 글자(표는 행 단위 탭 구분 줄)
  gms.py       GMS chat/completions · 재시도
  schema.py    출력 JSON 스키마 · 지시문 · LLM 답 파싱
  methods.py   방식 A · B
  runner.py    환경 변수 · .env · 사진 찾기 · 이어 하기 · dry-run · 폴더 실행
  grading.py   줄 정확도 채점 · 정답 종류별 정규화 · 폴더 짝짓기
scripts/ocr/   run_methods.py(실행기) · evaluate.py(채점기)
tests/ocr/     test_ocr_*.py · ocr_fakes.py(공용 가짜 post)
```

## 키

`ai/.env`에 아래 5개를 넣습니다 (`.env`는 커밋 금지 · 이미 있는 환경 변수가 우선 · 빈 값이면 `.env` 값). GMS는 OpenAI 호환(`{GMS_BASE_URL}/chat/completions`)이라고 가정합니다.

```
GMS_KEY=
GMS_BASE_URL=
GMS_MODEL=
CLOVA_OCR_URL=
CLOVA_OCR_SECRET=
```

`.env` 줄 끝 주석은 값 뒤에 공백 + `#` 으로 씁니다(`GMS_KEY=abc # 메모`). 값에 ` #`이 들어가면 따옴표로 감쌉니다(`"a #b"`, 따옴표 안만 값).

## 명령

명령 두 줄 (`ai` 폴더에서. 한글 경로면 `uv run` 대신 `.venv` 파이썬을 직접 부릅니다):

```bash
.venv/Scripts/python.exe scripts/ocr/run_methods.py --images ../../샘플이미지_가상 --out outputs/ocr/clova_llm --method clova_llm
.venv/Scripts/python.exe scripts/ocr/evaluate.py --gold ../../샘플이미지_가상 --pred outputs/ocr/clova_llm --pred outputs/ocr/llm_only
```

- 방식 B는 첫 줄의 `clova_llm`을 `llm_only`로 바꿔 한 번 더 돌립니다
- `--dry-run`: API를 부르지 않고 가짜 응답으로 흐름만 확인. `--limit N`: 앞에서 N장만(1 이상)
- **dry-run은 출력 폴더를 따로 씁니다**(예: `outputs/ocr/dry/llm_only`). 가짜 결과에는 `"dry_run": true`가 붙어, 같은 폴더로 실제 실행하면 다시 처리하고 채점기는 표 아래에 경고를 찍지만, 섞이지 않게 처음부터 나눕니다
- 하위 폴더까지 찾고 출력도 같은 하위 경로에 씁니다. 이미 있는 출력은 건너뛰므로 끊겨도 다시 돌리면 이어서 합니다(비었거나 깨진 출력은 다시 처리). 장마다 걸린 초 · 성공 여부 · 오류는 `출력폴더/_run.csv`
- 429 · 5xx · 연결 오류는 1 · 2 · 4초 쉬고 최대 3번 재시도. 시간 초과는 이미 과금됐을 수 있어 재시도하지 않고 그 장 실패로 둡니다
- 키가 없거나 사진 폴더가 없거나 사진이 0장이면 API를 부르지 않고 종료 코드 2, 연속 5장 API 실패면 멈추고 종료 코드 1
- 채점 표는 전체 줄 아래에 정답 종류(type)별 줄을 찍습니다. `chat`(단가 없음) · `tax_invoice_list` · `ledger`(품목 줄 없음)는 채점에서 빼고 표 아래에 장수를 찍습니다
- 정답 JSON의 `qty` · `price`는 숫자(`1000`)로 적는 것이 기본이고, 엑셀 표기 문자열(`"1,000"` · `"1,000원"`)도 쉼표 · "원"을 지워 숫자로 비교합니다. 단위가 붙은 값(`"1 kg"`)은 숫자로 못 바꿔 틀린 줄이 됩니다
- 품목명은 공백 · 괄호 기호 · 대소문자를 지우고 비교하고(괄호 안 글자는 남김), 짝짓기는 품목명 유사도가 높은 쌍부터 1:1입니다. 표의 "추가 줄 비율"은 정답에 없는 예측 줄 ÷ 예측 줄입니다

## 비용

CLOVA General OCR은 장당 25원, 월 100건 무료. **실행기는 `--images` 폴더 안 사진을 전부 부릅니다.** 가상 샘플 폴더를 통째로 주면 160장 → CLOVA 160건이라, 무료 100건을 넘는 60건이 유료(약 1,500원)입니다. GMS 사용량은 따로 확인합니다.

채점 대상 75장(명세서 30 · 온라인몰 20 · POS 25)만 돌리려면 그 세 하위 폴더를 각각 `--images`로 주고, `--out`도 같은 하위 폴더 이름으로 맞춥니다(채점기가 상대 경로로 짝짓습니다). CLOVA 75건이라 무료 100건 안입니다.

```bash
for d in 01_거래명세서_납품서 04_온라인식자재몰_주문내역 10_POS_ERP_매입기록; do
  .venv/Scripts/python.exe scripts/ocr/run_methods.py --images ../../샘플이미지_가상/$d --out outputs/ocr/clova_llm/$d --method clova_llm
done
```
