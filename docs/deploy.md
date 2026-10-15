# 배포 결정

UPS 운영 환경을 EC2 한 대에 고정 IP로 올리는 방식입니다. 2026-10-15에 정했습니다. 실행 파일과 절차는 [deploy/](../deploy/README.md)에 있습니다.

## 결정

| 항목 | 선택 | 이유 |
| --- | --- | --- |
| 실행 위치 | EC2 한 대(서울 리전) | 단일 애플리케이션 원칙입니다. 웹·API·DB·경로 엔진을 같은 서버에 둡니다. |
| 서버 크기 | t4g.large(Graviton, 2 vCPU, 8GB), gp3 40GB | 경로 엔진(한국 OSRM 약 1.7GB)·API·PostgreSQL이 8GB에 들어갑니다. |
| 고정 IP | Elastic IP | 도메인 없이 IP로 접속합니다. |
| HTTPS | Caddy + Let's Encrypt IP 인증서 | 2026-01부터 IP 인증서를 정식 발급합니다. 6일짜리 `shortlived` 인증서이고 Caddy가 자동 갱신합니다. |
| DB | 같은 EC2의 PostgreSQL 컨테이너 | EBS 볼륨에 둡니다. 매일 `pg_dump`를 S3에 올립니다. |
| 경로 엔진 | 같은 EC2의 OSRM + VROOM | 범위는 한국 추출본(Geofabrik `south-korea-latest`)입니다. 데이터 준비는 서버에서 한 번 합니다. |
| 저장소 | Public 전환 | 조직의 Actions 사용 분이 소진됐습니다. Public 저장소는 Actions 사용 분이 무료입니다. |
| CI/CD | EVN-MARKETING 방식 | CI가 이미지를 만들고 시험합니다. 배포는 수동 실행이며, 시험한 이미지를 그대로 켭니다. |

## 구성

```text
EC2 (Elastic IP)
├─ caddy      :80, :443  HTTPS, 웹 정적 파일, /api·/actuator → api
├─ api        :8080      Spring Boot (127.0.0.1만)
├─ postgres   :5432      EBS 볼륨 (컨테이너 망 안에서만)
├─ osrm       :5000      한국 도로망 (컨테이너 망 안에서만)
└─ vroom      :3000      방문 순서 (컨테이너 망 안에서만)
```

- 밖으로 여는 포트는 80과 443뿐입니다. SSH는 열지 않고 SSM Session Manager로 접속합니다.
- 비밀값(DB 비밀번호, Ours24·V-World 키, 서비스 토큰)은 SSM Parameter Store SecureString에 둡니다.
  - 서버가 켜질 때 파일로 내려받습니다(mode 600). API는 기존 방식대로 `*_FILE` 경로로 읽습니다.
  - 저장소와 이미지에는 비밀값을 넣지 않습니다.
- 지도 타일은 OpenFreeMap을 그대로 씁니다. 서버에 타일을 두지 않습니다.

## 배포 흐름

EVN-MARKETING의 `ci.yml`·`deploy.yml` 구조를 따릅니다.

1. CI(`ci.yml`): PR과 main push 때 실행합니다.
   - API 테스트·detekt와 웹 검사(`npm run check`)를 실행합니다.
   - main이면 arm64 러너(`ubuntu-24.04-arm`)에서 이미지를 만듭니다. Public 저장소는 이 러너도 무료입니다.
   - 이미지는 ECR에 digest로 올리고, digest를 산출물로 남깁니다.
2. 배포(`deploy.yml`): main CI가 모두 통과하면 CI가 바로 호출합니다(승인 대기 없음, 2026-10-15 변경). 되돌리기와 재배포는 사람이 `workflow_dispatch`로 실행합니다.
   - 지금 main인지, CI가 성공했는지, digest가 맞는지 확인합니다.
   - GitHub OIDC로 배포 역할을 받습니다. 장기 키는 쓰지 않습니다.
   - SSM 명령으로 서버에 digest를 넘깁니다.
   - 서버는 새 API를 받아 `/actuator/health`가 정상일 때만 전환합니다. 실패하면 이전 digest로 되돌립니다.
3. DB 마이그레이션은 API가 시작할 때 Flyway로 실행합니다(지금과 같음). 배포 전에 서버가 `pg_dump`를 한 번 받습니다.

## Public 저장소 보안

- 배포 역할의 신뢰 조건: 이 저장소, `main` 브랜치, `production` environment만 허용합니다.
- `production` environment는 main 브랜치만 허용합니다. main은 CI 통과가 필수라, 검사를 통과한 커밋만 배포됩니다. fork의 PR은 비밀값과 OIDC를 받지 못합니다.
- 워크플로의 외부 액션은 커밋 SHA로 고정합니다.
- PR 본문·리뷰·이슈도 함께 공개됩니다.

## Public 전환 전 점검(2026-10-15)

- git 이력 전체를 점검했습니다. AWS·Google·GitHub 키, 개인키, 실제 전화번호, UPS 운송장 번호는 없습니다.
  - 전화번호는 테스트 값(010-1234-…, 010-5555-…, 010-9999-…)뿐입니다.
- 에이전트 파일(`AGENTS.md`, `CLAUDE.md`, `.claude/` 등)과 `secrets/`는 이력에 없습니다.
- 정한 것(2026-10-15):
  - Ours24 파트너 API 명세(`openapi-partner.json`)는 공개하지 않습니다. `docs/references/ours24/`는 Git에서 뺍니다.
  - UPS 로고는 둡니다.
  - 이력에서 명세를 확실히 빼기 위해 기존 Private 저장소를 삭제하고(PR 기록 포함), 같은 이름의 Public 저장소를 명세를 뺀 커밋 하나로 새로 시작했습니다.

## 아직 정하지 않은 것

- 백업 보관 기간(제안: S3 30일).
- 운영 첫 개발 관리자 만들기. 터미널 명령(`--ups.bootstrap-owner`)을 SSM 세션에서 실행합니다.
- 비용(미검증): t4g.large 온디맨드, EBS 40GB, Elastic IP(공인 IPv4 요금), S3 백업.
