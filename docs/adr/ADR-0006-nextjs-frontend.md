# ADR-0006: 프론트엔드는 Next.js App Router, 스타일은 CSS 변수 토큰 + CSS Modules

- 상태: 채택
- 영역: frontend
- 결정 로그: [DECISIONS #23](../DECISIONS.md)
- 관련 이슈: S15P31A308-27

## 맥락

화면 5개를 FE 1명이 3주 안에 만든다. 프로토타입이 단일 HTML + CSS 변수로 이미 완성돼 있고, `DESIGN.md`의 토큰과 1:1이다.
사장님은 폰으로 본다. 모바일 우선, 데스크톱 2열.

## 결정

- **Next.js 16 App Router + TypeScript**, `src/` 디렉터리, 라우트 하나 = 폴더 하나
- **Tailwind 없음.** 프로토타입의 CSS 변수를 `src/styles/tokens.css`로 그대로 옮기고, 화면별 스타일은 CSS Modules. 컴포넌트에 hex · px를 직접 쓰지 않는다
- 서체는 `next/font/google`로 자체 호스팅 (Archivo 가변 + Gothic A1). 외부 `<link>` 없음
- 다크 모드는 토큰 재정의만 (`prefers-color-scheme` + `[data-theme]`)
- ESLint(next/core-web-vitals + typescript) + Prettier. `npm run lint && npm run build`가 MR 게이트

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| Vite + React | 라우팅 · 폰트 · 이미지 최적화를 직접 붙여야 한다. Next가 기본으로 준다 |
| Tailwind | 토큰이 이미 CSS 변수로 있다. 유틸 클래스로 다시 옮기면 프로토타입 CSS를 재사용 못 한다 |
| 전역 CSS만 | 화면 5개가 서로의 클래스를 덮는다. Modules가 격리해 준다 |

## 결과

- **좋아지는 것**: 프로토타입 CSS를 거의 복사해 쓴다. 토큰 바뀌면 파일 하나
- **감수하는 것**: Next 16은 학습 데이터보다 새 버전이다. `node_modules/next/dist/docs/` 를 먼저 읽는다
- **다시 볼 시점**: 서버 컴포넌트 · 캐시 기능이 필요 없는 채로 끝나면 다음 프로젝트에서 Vite 재검토
