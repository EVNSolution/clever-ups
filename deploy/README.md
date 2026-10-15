# 운영 배포

결정과 이유는 [docs/deploy.md](../docs/deploy.md)에 있습니다. 이 문서는 실행 절차입니다.

## 파일

| 파일 | 하는 일 |
| --- | --- |
| `api.Dockerfile` | API jar를 JRE 위에서 uid 10001로 실행합니다. |
| `web.Dockerfile` | 웹을 빌드해 Caddy 이미지에 넣습니다. |
| `Caddyfile` | HTTPS(IP 인증서), 보안 헤더, `/api`와 `/actuator/health`만 API로 넘깁니다. |
| `compose.yml` | 서버의 다섯 컨테이너. 외부 포트는 Caddy의 80·443뿐입니다. |
| `host/setup.sh` | 서버 준비: Docker, 디렉터리, 비밀값 파일, 경로 엔진 데이터, 백업 타이머. |
| `host/release.sh` | 서버의 `ups-release`. 이미지 전환, 상태 확인, 실패 시 되돌리기. |
| `host/backup.sh` | 서버의 `ups-backup`. `pg_dump`를 S3에 올립니다(30일 보관). |
| `aws/stack.yml` | ECR, S3, 보안 그룹, EC2, Elastic IP, SSM 문서, CI·배포 역할. |

## 보안

- 외부에 여는 포트는 80과 443뿐입니다. SSH 포트는 없고, 서버 접속은 SSM Session Manager로만 합니다.
- 서버 메타데이터는 IMDSv2만 받고, 디스크는 암호화합니다.
- API 컨테이너 설정:
  - 읽기 전용 파일 시스템, 권한(capabilities) 없음, 비루트 사용자.
  - 쿠키는 항상 Secure입니다.
  - DB·경로 엔진은 외부 인터넷이 없는 내부 망에 있습니다.
- actuator는 health만 공개합니다. 나머지 actuator 경로는 404입니다.
- 비밀값은 SSM Parameter Store `/clever-ups/*`(SecureString)에 둡니다.
  - 서버에서는 읽을 서비스 사용자 소유, mode 600 파일로 둡니다.
  - 저장소·이미지·로그에는 남기지 않습니다.
- 역할별 권한:
  - CI 역할: main 브랜치에서만 받습니다. 두 ECR 저장소에 이미지를 올릴 수만 있습니다.
  - 배포 역할: `production` environment(승인 필요)에서만 받습니다. 서버에서 `CleverUps-Release` 문서 하나만 실행할 수 있고, 인자는 패턴으로 검사합니다.
- PR의 CI는 비밀값과 AWS 권한 없이 돕니다.

## 처음 한 번

1. SSM 파라미터를 만듭니다. 값은 파일에서 읽게 하고 명령 인자에 쓰지 않습니다.
   - SecureString: `db-password`, `service-token`(운영용으로 새로 생성), `ours24-api-key`, `vworld-api-key`.
   - String: `default-center`.
2. 스택을 만듭니다. `GitHubOidcProviderArn`에는 계정에 이미 있는 GitHub OIDC 공급자를 넣습니다.

   ```bash
   aws cloudformation deploy --stack-name clever-ups --template-file deploy/aws/stack.yml --capabilities CAPABILITY_NAMED_IAM --parameter-overrides GitHubOidcProviderArn=<arn>
   ```

3. 저장소 변수 `AWS_ACCOUNT_ID`, `UPS_INSTANCE_ID`를 넣고, environment `production`에 승인자를 둡니다.
4. 서버를 준비합니다. SSM Run Command(`AWS-RunShellScript`)로 main 커밋의 `deploy/host/setup.sh`를 받아 실행합니다(`bash setup.sh <sha>`). 경로 엔진 데이터 준비에 수십 분이 걸립니다.
5. main의 CI가 성공하면 Actions의 Deploy를 `release`, 커밋 SHA, CI 실행 ID로 실행합니다.
6. 행정구역 경계를 넣습니다([infra/boundaries/README.md](../infra/boundaries/README.md)). 서버에서 파일을 받고 SHA-256을 확인한 뒤 실행합니다: `docker compose ... run --rm api --ups.import-boundaries=<파일>`.
7. 첫 개발 관리자는 운영자가 Session Manager에서 직접 만듭니다(`--ups.bootstrap-owner`). 비밀번호는 그 터미널에서만 입력합니다.

## 평소

- main에 병합하면 CI가 이미지를 만들어 ECR에 올립니다.
- Actions → Deploy → `release`를 실행하고 승인합니다. 서버가 새 API의 health를 확인한 뒤 전환합니다. 3분 안에 정상이 아니면 이전 버전으로 돌아갑니다.
- 문제가 있으면 Deploy → `rollback`을 실행합니다. 직전 버전으로 돌아갑니다.
- 배포 직전과 매일 03:00(한국)에 DB를 S3에 백업합니다.
