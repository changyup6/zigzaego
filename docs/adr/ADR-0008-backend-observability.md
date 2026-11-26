# ADR-0008: 백엔드 관측은 Micrometer 메트릭 · Micrometer Tracing · 구조화 로그, actuator는 관리 포트로 분리

- 상태: 채택
- 영역: backend
- 결정 로그: [DECISIONS #58](../DECISIONS.md)
- 관련 이슈: S15P31A308-24

## 맥락

- 배포된 BE의 장애를 요청 단위로 추적할 수단이 없다. 로그에 요청 식별자가 없고, 비동기 job 로그는 그 job을 만든 요청과 이어지지 않는다.
- 응답 오류율 · 지연 · JVM 메모리를 볼 메트릭이 없다. 관측 스택(Prometheus)은 준비되어 있다.
- BE Ingress는 `/` 전체를 공개한다. actuator를 같은 포트에 두면 메트릭 같은 내부 정보가 인터넷에 노출된다.
- 상태 확인이 `/actuator/health` 하나라, DB가 잠깐 끊겨도 생존 판정이 실패해 파드가 재시작된다.

## 결정

| 항목 | 선택 |
|---|---|
| 메트릭 | `micrometer-registry-prometheus`. 관리 포트의 `/actuator/prometheus`로 노출 |
| 추적 ID | `spring-boot-micrometer-tracing-opentelemetry` + `micrometer-tracing-bridge-otel`. 로그에 traceId · spanId가 들어간다. 트레이스 내보내기 모듈은 넣지 않는다 |
| 비동기 전파 | job 실행기(`JobExecutorConfig`)에 `ContextPropagatingTaskDecorator` |
| 로그 형식 | 배포 환경은 Boot 내장 구조화 로그(ECS JSON). 로컬은 기존 텍스트 |
| actuator 위치 | 배포 환경은 관리 포트 8081. Service · Ingress에 연결하지 않는다. 공개 포트에는 `/livez` · `/readyz`만 남긴다 |
| 상태 확인 | 생존(`liveness`)은 프로세스 상태만, 준비(`readiness`)는 DB · Redis 연결을 포함한다 |

- 배포 환경 설정은 매니페스트 환경변수로 넣는다(`infra/k8s/base/be.yaml`). `application.yaml`과 로컬 · 테스트 동작은 바뀌지 않는다.
- 보안 필터 체인은 관리 포트에도 적용되므로 `/actuator/health/**` · `/actuator/prometheus` · `/livez` · `/readyz`를 공개 경로에 둔다. 관리 포트는 클러스터 밖에서 닿지 않는다.

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| OpenTelemetry Java agent | 코드 변경 없이 되지만 Buildpacks 이미지에 에이전트를 넣는 구성이 추가된다. 로그 traceId만 필요한 지금은 의존성 하나로 충분하다 |
| actuator를 공개 포트에 두고 Ingress에서 경로 차단 | 경로 규칙을 빠뜨리면 바로 노출된다. 포트 분리는 연결하지 않는 것만으로 막힌다 |
| `spring-boot-starter-opentelemetry` | OTLP 메트릭 · 로그 · 트레이스 내보내기 모듈까지 함께 들어온다. 내보낼 대상이 없으면 1분마다 접속 오류를 남긴다 |
| logstash-logback-encoder | Boot 내장 구조화 로그로 같은 결과를 의존성 없이 얻는다 |
| 트레이스 저장소(Tempo) 동시 도입 | 로그 traceId로 요청 하나는 따라갈 수 있다. 필요해지면 OTLP 트레이스 내보내기 모듈과 대상 주소를 추가한다 |

## 결과

- **좋아지는 것**: Grafana에서 traceId로 요청과 그 job의 로그를 함께 찾는다. 5xx 비율 · 지연 · JVM 메모리를 보고 경보를 건다. 메트릭이 외부에 노출되지 않는다. DB가 잠깐 끊겨도 파드가 재시작되지 않는다.
- **감수하는 것**: 배포 환경의 actuator 경로가 관리 포트로 바뀐다. 외부에서 상태를 확인할 때는 `/readyz`를 쓴다.
- **다시 볼 시점**: 로그 traceId만으로 원인 추적이 부족해 구간별 소요 시간이 필요해질 때(트레이스 저장소 도입).
