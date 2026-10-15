# contract

OpenAPI 계약. 웹·Android·API가 공유하는 기준입니다. 현재는 헬스 확인만 있습니다.

```bash
npx --yes @redocly/cli@2.59.0 lint openapi.yaml
```

엔드포인트를 추가하거나 바꿀 때 이 파일을 먼저 수정합니다. 클라이언트 생성기는 아직 정하지 않았습니다.

`services/api`의 `ContractTest`가 이 파일의 모든 GET 경로를 실제 API로 호출해 응답을 검증합니다.
