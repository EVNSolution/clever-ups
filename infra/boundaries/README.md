# 행정구역 경계 데이터

권역 편집(Draw·Snap)과 지도 표시에 쓰는 공개 행정구역 경계입니다. 파일은 `data/`에 두고 커밋하지 않습니다.

## 출처

| 항목 | 값 |
| --- | --- |
| 데이터 | vuski/admdongkor `ver20260701/HangJeongDong_ver20260701.geojson` |
| 받은 곳 | https://raw.githubusercontent.com/vuski/admdongkor/master/ver20260701/HangJeongDong_ver20260701.geojson |
| 원자료 | 통계청 SGIS 행정동 경계(공공누리 제1유형) |
| 라이선스 | CC BY 4.0. 상업적 이용·재배포 가능. 출처 표시 필요. |
| 기준일 | 2026-07-01 |
| 크기 | 34,648,875 바이트 |
| SHA-256 | `c01ef44a0eb00978662ba7a6240ccb1da287fb52abd85104a1758969d391132f` |
| 좌표계 | CRS84(WGS84 경도·위도) |
| 내용 | 행정동 3,558개, 시·군·구(일반구 포함) 256개 |

지도에 경계를 보여 줄 때 출처를 표시합니다:

> 행정동 경계: 통계청 SGIS, 가공 vuski/admdongkor, CC BY 4.0

## 받기

```bash
mkdir -p infra/boundaries/data && curl -fL -o infra/boundaries/data/HangJeongDong_ver20260701.geojson https://raw.githubusercontent.com/vuski/admdongkor/master/ver20260701/HangJeongDong_ver20260701.geojson
```

```bash
shasum -a 256 infra/boundaries/data/HangJeongDong_ver20260701.geojson
```

## DB에 넣기

`admin_boundary`(V18)의 모든 행을 이 파일로 바꿉니다. 기준일은 파일 이름(`ver20260701`)에서 읽습니다.

```bash
UPS_DB_URL=jdbc:postgresql://127.0.0.1:5432/ups_dev UPS_DB_USER=$(whoami) java -jar services/api/build/libs/ups-api-0.0.1-SNAPSHOT.jar --ups.import-boundaries=$PWD/infra/boundaries/data/HangJeongDong_ver20260701.geojson
```

- 행정동(`DONG`, 행안부 10자리 코드)은 파일 그대로입니다. 통계청 8자리 코드는 `stat_code`에 둡니다.
- 시·군·구(`SGG`, 5자리)는 그 행정동을 합친 도형입니다. 일반구(예: 성남시 분당구)도 `SGG`입니다.
- 일반구가 있는 시(`CITY`, 구 코드 앞 4자리 + 0, 예: 41130 성남시)는 그 구를 합친 도형입니다. 13개입니다.
- 도형 계산은 API의 JTS로 합니다. DB에 PostGIS가 없습니다.

## 고르지 않은 출처

- 국토교통부 (센서스경계)행정동경계(data.go.kr 15125055): 공공누리 제4유형이라 상업적 이용이 금지됩니다.
- V-World 읍면동 레이어: 법정동이고 키가 필요합니다.
- OpenStreetMap: 행정동·법정동이 섞여 있고 ODbL 동일조건 의무가 생길 수 있습니다.
- southkorea/southkorea-maps: 2013년 자료입니다.
