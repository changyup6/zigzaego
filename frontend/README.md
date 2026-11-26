# frontend — 위글리 발주 화면

Next.js 16 (App Router) · React 19 · TypeScript · CSS Modules. Tailwind 없음.

## 실행

```bash
npm install
npm run dev        # http://localhost:3000
npm run lint       # eslint
npm run build      # 타입 검사 + 프로덕션 빌드. MR 전 필수
npm run format     # prettier --write
```

## 구조

```
src/
  app/            라우트. 화면 하나 = 폴더 하나 (page.tsx + page.module.css)
    layout.tsx    서체 로드, 토큰·전역 CSS import
    globals.css   base 규칙, .num / .n40 .n24 .n17 .n14 / .unit
  styles/
    tokens.css    디자인 토큰 (색 · 서체 · 숫자 4단계 · 모서리 · 간격). 값은 여기서만 바꾼다
  components/     화면 둘 이상이 쓰는 UI (S15P31A308-29 부터)
  lib/            API 클라이언트 · 타입 (S15P31A308-30 부터)
```

## 규칙

- 토큰 정본은 `docs/design/DESIGN.md`. 바뀌면 `tokens.css` 한 곳만 고친다. 컴포넌트에 hex · px 직접 쓰지 않는다
- 화면 모양은 `docs/design/prototype/app_all.html` 그대로(DECISIONS #60). 미색 바닥 위 유리 카드, 그림자는 토스트 · 폰 탭 막대만, 설명 문장 없음
- 숫자는 `.num .n24` 처럼 네 단계만, 단위는 `.unit`
- 라이트 하나. 다크 모드는 3주 범위 밖 (DECISIONS #44)
