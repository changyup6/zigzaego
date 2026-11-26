# ADR-0005: 인증은 JWT, refresh token은 Redis에 저장

- 상태: 채택
- 영역: backend
- 결정 로그: [DECISIONS #21](../DECISIONS.md)
- 관련 이슈: S15P31A308-25

## 맥락

모든 API는 로그인한 계정의 가게로 스코프된다([ADR-0002](ADR-0002-multi-tenant-store-scope.md)). 서버는 요청마다 "누가, 어느 가게로" 들어왔는지 알아야 한다.
프론트는 모바일 우선 웹이고, 스테이징 · 운영에서 FE와 BE가 다른 오리진일 수 있다.
3주 안에는 계정을 팀이 만들어 주고 역할은 OWNER 하나뿐이다(3주 계획 10절).

## 결정

- **인증은 JWT.** 서버는 세션을 만들지 않는다(Spring Security `STATELESS`)
- access token은 `Authorization: Bearer <token>` 헤더로 보낸다. 클레임에 `accountId`와 현재 `storeId`를 넣고, store 스코프는 이 값으로 강제한다
- refresh token은 **Redis에 저장**한다. 재발급 때 Redis 값과 대조하고 새 토큰으로 교체(rotation), 로그아웃은 Redis 키 삭제
- 서명 키 · 만료 시간은 코드에 두지 않고 환경 변수로 주입한다. 값은 구현 MR에서 정한다
  → 구현(S15P31A308-34)에서 확정: refresh token은 응답 바디, 만료 기본값 access 30분 · refresh 14일. 세부는 [DECISIONS #43](../DECISIONS.md)
- 라이브러리: `io.jsonwebtoken:jjwt` 0.13

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| 서버 세션 (+ Spring Session Redis) | FE · BE 오리진이 다르면 쿠키 · CORS 설정 비용이 크다. 팀이 JWT에 익숙하다 |
| refresh token을 DB 테이블에 저장 | 만료 처리를 직접 해야 한다. Redis TTL이면 만료가 저절로 된다 |
| access token만, refresh 없음 | 만료를 길게 잡아야 하고 탈취 시 끊을 방법이 없다 |

## 결과

- **좋아지는 것**: 서버가 상태를 갖지 않아 스케일 · 배포가 단순하다. 로그아웃 · 강제 만료를 Redis로 처리한다
- **감수하는 것**: 발급된 access token은 만료 전까지 끊을 수 없다(짧게 잡는다). Redis가 죽으면 재발급이 안 된다
- **다시 볼 시점**: 한 계정이 여러 가게를 오가는 화면이 생겨 `storeId`를 토큰 밖에서 골라야 할 때. refresh token 전달 방식(바디 vs HttpOnly 쿠키)은 로그인 API MR에서 정한다
