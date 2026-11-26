# ADR-0015: 운영은 AWS EC2 3대의 k3s, 스테이징은 제공 인스턴스 1대의 k3s 에 둔다

- 상태: 채택
- 영역: infra
- 결정 로그: [DECISIONS #65](../DECISIONS.md) (#53 을 바꾼 것) · 관측 계측은 [DECISIONS #58](../DECISIONS.md) · [ADR-0008](ADR-0008-backend-observability.md)
- 관련 이슈: S15P31A308-307
- 대체하는 것: DECISIONS #53 의 "제공 인스턴스 1대에 `staging` · `prod` 네임스페이스". 스테이징은 그대로다

## 맥락

- #53 은 제공 인스턴스 1대의 k3s 에 `staging` · `prod` 를 네임스페이스로 나눠 두었다. 서버가 멈추면 두 환경이 함께 멈춘다.
- #65 로 운영을 AWS 로 옮겼는데 DECISIONS 한 줄만 있고, 그 과정에서 들어온 CloudNativePG · External Secrets · 관측 전송 구성은 어디에도 결정으로 남지 않았다.
- AWS 자원은 교보재 신청서의 AWS 행(EC2 3대, EBS 50GB 3개, ALB 1개, S3)에 맞춘다. 신청 기간이 끝나면 운영 데이터를 스테이징 서버로 옮기고 회수한다(`infra/terraform/README.md`).

## 결정

### 환경

| 환경 | 서버 | 기준 브랜치 | BE 배포 | API 주소 |
|---|---|---|---|---|
| staging | 제공 인스턴스 1대의 단일 노드 k3s, 네임스페이스 `staging` | `develop` | 머지하면 자동 배포 | `zigzaego.example.com` |
| prod | AWS EC2 3대의 k3s(내장 etcd), 네임스페이스 `prod` | `main` | 사람이 `backend:deploy-prod` 를 눌러 배포 | `zigzaego.example.com` |

- FE(Next.js)는 Vercel 이 배포한다. `develop` 은 `zigzaego.example.com`, `main` 은 `zigzaego.example.com` 이다.
- 매니페스트는 `infra/k8s/base` 하나이고 환경 차이는 `overlays/staging` · `overlays/prod` 의 값으로만 둔다.

### 운영 AWS 자원 (Terraform, `infra/terraform/`)

- EC2 t3.medium 3대, AZ 마다 1대(`compute.tf`). 첫 노드가 `cluster-init` 으로 내장 etcd 클러스터를 만들고 나머지가 합류한다(`templates/k3s-server.sh.tftpl`).
- ALB 가 ACM 인증서(`zigzaego.example.com`)로 TLS 를 끝내고 노드 Traefik 443 으로 넘긴다(`alb.tf`). 노드 보안 그룹은 ALB 에서 오는 443 과 노드끼리의 통신만 받는다. 22 · 6443 은 열지 않고 노드 관리는 SSM 세션으로 한다(`security.tf`).
- BE 이미지는 ECR `zigzaego/backend` 에 둔다. `main` 이미지를 같은 SHA 태그로 올린다(`storage.tf`, `.gitlab/ci/backend.yml` `backend:image-ecr`).
- S3 버킷 두 개(`storage.tf`): 백업 버킷(etcd 스냅샷 6시간마다 · DB 덤프), 영수증 원본 업로드 버킷(ADR-0013).
- 클러스터 안 TLS 인증서는 쓰지 않는다. 운영 overlay 에 `cert.yaml` 이 없다(`overlays/prod/kustomization.yaml`).

### 배포 경로 (GitLab Agent)

- 스테이징: Agent `zigzaego`(`.gitlab/agents/zigzaego/config.yaml`). `develop` 에서 `backend/` 가 바뀌면 `backend:deploy-staging`, `base/` · `overlays/staging/` 만 바뀌면 `manifests:deploy-staging` 이 적용한다.
- 운영: Agent `zigzaego-prod`(`.gitlab/agents/zigzaego-prod/config.yaml`). `backend:deploy-prod` 가 `main` 에서 수동 실행으로 ECR 이미지를 적용한다. Agent 권한은 `cluster/gitlab-agent-rbac.yaml` 이 네임스페이스로 제한한다.
- 두 잡 모두 `infra/scripts/deploy.sh <env> <이미지>` 를 실행한다.

### DB — CloudNativePG

- Postgres 는 CloudNativePG 오퍼레이터의 Cluster `postgres` 다(`base/postgres.yaml`). 주소는 쓰기 `postgres-rw`, 읽기 `postgres-ro`.
- 인스턴스: staging 1개(base 값), prod 3개를 노드마다 하나씩(`overlays/prod` 패치 `instances: 3` · `podAntiAffinityType: required`). 주 서버 노드가 멈추면 오퍼레이터가 복제본을 주 서버로 올린다.
- 운영 오퍼레이터는 2대, 노드마다 1대(리더 선출, `infra/k8s/aws/cnpg/kustomization.yaml`). 스테이징은 `server/bootstrap.sh` 가 설치한다.
- 백업은 운영만. CronJob `backup` 이 매일 04:00 KST 에 `pg_dump` 를 백업 버킷에 올린다(`overlays/prod/backup.yaml`).

### 시크릿 — External Secrets

- 운영: 원본은 AWS SSM Parameter Store `/zigzaego/prod/app/<키>`(SecureString)다. External Secrets 가 `ClusterSecretStore` `aws-ssm` 으로 읽어 1시간마다 `prod` 의 Secret `app-secrets` 로 동기화한다(`infra/k8s/aws/external-secrets.yaml`). 자격은 노드 역할이고, 노드 역할은 SSM `/zigzaego/*` 읽기만 갖는다(`iam.tf`).
- 스테이징: 환경 범위 `staging` GitLab CI 변수를 배포 잡이 `render-secrets.sh` 로 `secrets.env` 로 만들고 `secretGenerator` 가 Secret 을 만든다.
- DB 비밀번호는 둘 다 CloudNativePG 가 만든 Secret `postgres-app` 에 있다.

### 관측 스택

- 저장 · 조회 · 경보는 스테이징 서버 클러스터의 `observability` 네임스페이스 한 곳에 둔다: Prometheus · Loki · Grafana · Alertmanager · blackbox exporter(`infra/k8s/observability/`).
- 운영 클러스터에는 수집기만 둔다: Prometheus agent(메트릭) · Alloy(로그)가 `cluster="prod"` 를 붙여 `https://zigzaego.example.com` 로 보낸다(`observability/prod-agent/`). 수신 쪽은 쓰기 경로 두 개만 기본 인증으로 연다(`push-ingress.yaml`).
- 운영 공개 health 는 스테이징 서버의 blackbox exporter 가 밖에서 확인한다(`prometheus/prod-health.json`).

## 버린 대안

| 대안 | 왜 안 골랐나 |
|---|---|
| 제공 인스턴스 1대에 `staging` · `prod` 네임스페이스 분리(#53) | 운영이 스테이징 서버와 같은 서버에 묶이고, 그 서버 1대가 멈추면 운영도 멈춘다(#65 근거) |

CloudNativePG · External Secrets · 관측 스택(스테이징 서버에 모으고 운영은 전송만)은 다른 선택지를 검토한 기록이 없다. 지금 구성은 위 "결정" 절과 같다.

## 결과

- **좋아지는 것**:
  - 운영 데이터 · 자원이 스테이징 서버와 분리된다.
  - 노드 1대가 멈춰도 클러스터는 유지된다(세 노드가 모두 k3s server 이고 내장 etcd 를 나눠 가진다).
  - 이중화되는 것: DB(인스턴스 3개, 자동 승격) · 인입(퍼블릭 서브넷 3개에 걸친 ALB, Traefik 2대 노드마다 1대) · CloudNativePG 오퍼레이터(2대) · CoreDNS(2대) · 컨트롤 플레인(etcd 3대). 장애 노드 판정 20초, 파드 축출 30초로 줄였다(`infra/README.md` "prod 노드 1대 장애 대비").
  - 운영 시크릿 값이 git 과 CI 변수에 없다.
- **감수하는 것**:
  - BE 는 `replicas: 1`, 배포 전략 `Recreate` 다(`base/be.yaml`). 배포 · 재시작 중에는 BE 가 응답하지 않는다(매니페스트 주석 기준 1분가량). 두 파드가 겹치면 `JobRecovery` 가 이전 파드의 진행 중 job 을 실패로 바꾸므로 겹치지 않게 한 것이다.
  - BE 가 있는 노드가 멈추면 파드 축출(30초) 뒤 다른 노드에서 다시 뜰 때까지 API 가 응답하지 않는다. 운영 BE 는 노드 볼륨을 쓰지 않아(ADR-0013) 다른 노드로 옮겨질 수 있다.
  - Redis 는 `replicas: 1` 이고 볼륨이 `emptyDir` 다(`base/redis.yaml`). 배포 전략은 적지 않아 기본값(RollingUpdate)이다. 파드가 다른 노드로 옮겨지면 refresh token 이 남지 않는다.
  - 관측 스택이 스테이징 서버 1대에 있다. 스테이징 서버가 멈추면 운영 메트릭 · 로그 저장과 경보도 멈춘다.
  - 노드를 동시에 2대 이상 잃으면 etcd 정족수를 잃는다. 노드 교체는 한 대씩 한다(`infra/terraform/README.md`).
  - 단일 서버 시절의 대체 경로가 남아 있다: ECR 변수가 없을 때 `backend:deploy-prod` 가 스테이징 서버 클러스터의 `prod` 네임스페이스로 배포하는 분기, `secrets.example.yaml` 로 시크릿을 직접 만드는 절차.
- **다시 볼 시점**: AWS 신청 기간이 끝나 운영을 스테이징 서버로 되돌릴 때(`infra/terraform/README.md` "주의"). BE 를 2벌 이상으로 늘릴 때(`base/be.yaml` 주석의 `JobRecovery` 겹침 문제를 먼저 푼다).
