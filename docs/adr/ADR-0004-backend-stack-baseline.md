# ADR-0004: 백엔드 기술 스택 기준선

- 상태: 채택 (배치 항목만 일부 대체됨 → [ADR-0014](ADR-0014-market-label-spring-batch.md): 식자재몰 상품명 라벨 배치는 Spring Batch. 추천 생성 등 다른 배치는 그대로 `@Scheduled`)
- 영역: backend
- 결정 로그: [DECISIONS #20](../DECISIONS.md)
- 관련 이슈: S15P31A308-25

## 맥락

DECISIONS #5로 "Spring Boot 백엔드 1개"까지만 정해졌고, 버전 · 빌드 도구 · 마이그레이션 · 테스트 도구는 정해지지 않았다.
`docs/spec/아키텍처_바이블.md` 30절의 스택은 "후보이며 확정 아님"이다.
BE 2명이 같은 날 착수하므로 프로젝트 뼈대(`backend/`)를 만들 때 기준을 한 번에 고정한다.

## 결정

| 항목 | 선택 |
|---|---|
| 런타임 | Java 21 (LTS), Spring Boot 4.1 |
| 빌드 | Gradle (Groovy DSL). 검증 게이트는 `cd backend && ./gradlew test` |
| 패키지 | `com.zigzaego.api` |
| DB | PostgreSQL + Spring Data JPA |
| 마이그레이션 | Flyway. `src/main/resources/db/migration/V<번호>__<설명>.sql`, 기존 파일 수정 금지 |
| 캐시 · 토큰 저장 | Redis (Spring Data Redis). 용도는 [ADR-0005](ADR-0005-jwt-auth-redis-refresh-token.md) |
| API 문서 | springdoc-openapi 3.x ([ADR-0003](ADR-0003-code-first-swagger.md)) |
| 엑셀 파싱 | Apache POI (`poi-ooxml`). POS 엑셀 업로드 |
| 테스트 | JUnit 5 + Testcontainers(PostgreSQL). 로컬 실행은 `compose.yaml`(Postgres · Redis) |
| 배치 | Spring `@Scheduled`. Spring Batch · Airflow는 쓰지 않는다 |

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| Liquibase | XML/YAML 변경 세트보다 SQL 파일이 팀에 익숙하다. "새 파일 추가만" 규칙이 Flyway 버전 번호와 그대로 맞는다 |
| `ddl-auto=update` | 스키마가 코드에 숨고 스테이징 DB와 갈라진다. 마이그레이션 리뷰가 불가능하다 |
| H2 인메모리 테스트 | Postgres 문법 · 제약과 달라 통합 테스트(가게 2개 격리)를 믿을 수 없다 |
| Spring Batch | 하루 1회 추천 생성뿐이다. 잡 메타 테이블과 설정 비용이 더 크다 |

## 결과

- **좋아지는 것**: 로컬 · 테스트 · 스테이징이 같은 Postgres를 쓴다. 스키마 변경 이력이 파일로 남는다
- **감수하는 것**: 테스트에 Docker가 필요하다. Boot 4는 Jackson 3를 쓰므로 Jackson 2 전용 라이브러리를 넣을 때 주의한다
- **다시 볼 시점**: 배치가 여러 개로 늘거나, AI 서버(FastAPI)가 추가될 때
