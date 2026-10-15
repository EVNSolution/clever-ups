# infra/route — 경로 엔진(OSRM + VROOM)

기사 한 명의 정차지를 방문 순서로 정렬하고, 도착 예상 시각과 도로 선을 계산합니다. API의 `POST /api/stop-sequence`가 사용합니다.

## 실행

Docker와 메모리 약 5GB가 필요합니다. 지도 파일은 `data/`에 생기며 커밋하지 않습니다.

```bash
./prepare.sh              # 한국 도로 지도(Geofabrik, 약 275MB) 받기 + OSRM 파일 만들기(MLD)
docker compose up -d      # osrm: 127.0.0.1:5005, vroom: 127.0.0.1:3005
```

API는 `UPS_VROOM_URL=http://127.0.0.1:3005/`일 때만 엔진을 씁니다. 없으면 `/api/stop-sequence`는 503입니다.

## 출처

| 항목 | 내용 |
| --- | --- |
| 원본 | `EVNSolution/clever-route-server` 리비전 `aff739cfc07a05dab1d5322ee4db072eb102f7ed` |
| 원본 파일 | `infra/compose/docker-compose.prod.yml`(osrm-korea, vroom-korea), `infra/vroom/config.korea.yml`, `docs/deployment/route-ops-vroom.md` |
| 가져온 것 | MLD 알고리즘, `car` 프로필, VROOM 설정값, VROOM 이미지 digest |
| 바꾼 것 | OSRM 이미지를 `latest` 대신 `v5.27.1`로 고정, `--max-table-size 1000`(기본 100은 한 노선 최대 150곳보다 작음), 포트를 127.0.0.1에만 열기, 지도 준비 스크립트 추가. DSV의 서버·데이터·설정은 연결하지 않습니다. |
| 라이선스 | OSRM BSD-2-Clause, VROOM BSD-2-Clause, 지도 데이터 © OpenStreetMap 기여자(ODbL) |

## 미검증

- 운영 서버 배포와 메모리 크기.
- 지도 갱신 주기(지금은 `prepare.sh`를 다시 돌릴 때만).
