# ADR-0013: 영수증 원본 저장을 인터페이스로 나누고 운영만 S3 에 둔다

- 상태: 채택
- 영역: backend · infra
- 결정 로그: [DECISIONS #73](../DECISIONS.md)
- 관련 이슈: S15P31A308-261 · S15P31A308-262
- 대체하는 것: DECISIONS #34 의 "서버 디스크(/data)" 를 운영에서만 S3 로 바꾼다(로컬 · 스테이징은 그대로)

## 맥락

- 영수증 원본은 `ReceiptFileStorage` 가 `/data/receipts` 아래에 쓰고, DB 에는 상대 경로(`purchase_doc.file_path`, `{storeId}/{jobId}/{uuid}.{jpg|png|pdf}`)만 남긴다.
- 운영은 서버 3대(#65)인데 `/data` 볼륨(local-path)이 서버 한 대에 묶여, 그 서버가 멈추면 BE 가 다른 서버에서 뜨지 못한다. DB 는 CloudNativePG 3인스턴스로 이 문제를 풀었다.
- 운영에는 아직 앱을 배포하지 않아 `/data` 에서 S3 로 옮길 기존 원본이 없다(2026-11-04 확인: 운영 클러스터에 PVC 없음). 원본 이전 절차는 두지 않는다.
- AWS 사용 기간이 끝나면 운영은 서버 1대로 돌아간다(`infra/terraform/README.md`). 그때는 디스크 저장이 다시 맞다.
- 저장 클래스를 밖에서 부르는 곳은 `save`(ReceiptJobService) · `read`(ReceiptAnalyzer) · `deleteQuietly`(롤백 정리)뿐이고, 파일을 화면에 내려 주는 API 는 없다.

## 결정

- 인터페이스 `ReceiptStorage`(`save` · `read` · `deleteQuietly`)를 두고, 지금 클래스 `ReceiptFileStorage` 는 이름을 그대로 두고 파일 구현으로, 새 S3 구현은 `S3ReceiptStorage` 로 둔다. 키 만들기와 형식 판별(`newKey` · `mimeTypeOf`)은 인터페이스의 정적 메서드다. 설정 `app.receipt.storage`(`file` | `s3`, 기본 `file`)로 하나만 빈으로 만든다.
- S3 키 = `file_path` 값 그대로. 버킷은 업로드 전용이라 접두어를 붙이지 않는다. 스키마는 바꾸지 않는다.
- 예외 계약은 두 구현이 같다: 없는 객체 읽기 → `NoSuchFileException`, 그 밖의 읽기 실패 → `IOException`, 저장 실패 → `UncheckedIOException`, 지우기 실패 → 경고 로그만. S3 SDK 의 런타임 예외를 밖으로 내보내지 않는다.
- 자격 · 리전은 AWS SDK 기본 체인(운영 서버 역할, `AWS_REGION`). 버킷 `RECEIPT_S3_BUCKET` 은 `s3` 모드에서 필수이고 없으면 기동이 실패한다. 의존성은 `software.amazon.awssdk:s3` 2.56.0이고, HTTP 구현은 JDK 기반 `url-connection-client` 만 둔다. SDK 기본 구현이 끌어오는 Apache HttpClient 5 가 classpath 에 있으면 요청 팩토리를 지정하지 않은 `RestClient` 가 HttpComponents 로 바뀌어 429 · 503 을 스스로 재시도한다.
- 운영 인프라: 업로드 전용 버킷(버전 관리 · 공개 차단 · 서버 측 암호화), 노드 역할에 그 버킷의 Get · Put · Delete 와 ListBucket(없는 키를 403 이 아니라 404 로 받기 위해), 운영 BE 에서 `/data` 볼륨과 `uploads` PVC 를 뺀다. 운영 백업의 업로드 복사는 버킷 버전 관리로 대신한다.
- 테스트의 S3 대역은 Adobe S3Mock 컨테이너다.

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| 모든 환경을 S3 로 | 스테이징에 S3 호환 저장소가 따로 있어야 하고, AWS 기간이 끝나면 운영 저장소를 다시 정해야 한다 |
| 백업 버킷에 접두어로 같이 둠 | 백업 버킷은 버킷 전체에 이전 버전 30일 삭제 규칙이 있고, 노드 역할의 삭제 권한 범위가 섞인다 |
| Spring Cloud AWS | 쓰는 것은 S3 의 Put · Get · Delete 세 개뿐이라 SDK 하나로 충분하다 |
| 테스트에 LocalStack · MinIO | LocalStack 은 2026.03 부터 인증 토큰 없이 실행되지 않고, MinIO 는 무료 이미지 배포가 끊겼다 |

## 결과

- **좋아지는 것**: 운영 BE 가 어느 서버에서든 뜬다. 업로드가 서버 디스크 밖에서 버전 관리로 보호된다.
- **감수하는 것**: 저장 구현이 둘이라 저장 동작을 바꿀 때 둘 다 고친다. 스테이징은 S3 경로를 검증하지 못하고 테스트(S3Mock)와 운영에서만 드러난다. 노드 역할로 접근하므로 운영 노드의 모든 파드가 업로드 버킷에 닿는다.
- **다시 볼 시점**: AWS 기간이 끝나 운영이 서버 1대로 돌아갈 때(`file` 로 되돌리고 객체를 디스크로 복사). 영수증 외 업로드(레시피 파일 · 메뉴판 사진)를 저장하게 될 때.
