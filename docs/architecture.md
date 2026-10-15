# 구성 결정

모듈은 한 저장소에서 독립적으로 빌드합니다. 폴더는 해당 기능을 구현할 때 만듭니다.

## 구조

```text
clever-ups/
├─ apps/web/        TypeScript + Vite. 지도 중심 운영 화면
├─ apps/android/    Kotlin 네이티브. 기사 앱
├─ services/api/    Kotlin + Spring Boot. 단일 애플리케이션
│  └─ partner/      외부 API 통로(Ours24 등)
├─ engines/         API 밖에서 도는 자주 바뀌는 부품(주소 엔진). 구조는 고정
├─ scripts/         검증·보관·가져오기 도구
└─ contract/        OpenAPI 계약. 모든 모듈의 공통 기준
```

iOS는 후순위입니다. 아래 "iOS" 항목을 따릅니다.

## 결정과 이유

| 영역 | 선택 | 이유 |
| --- | --- | --- |
| 웹 | TypeScript + Vite | 지도(MapLibre), 표, 업로드 부품이 JS에 이미 있습니다. |
| API | Kotlin + Spring Boot | 한 애플리케이션으로 시작합니다. 서비스는 실제 필요가 생길 때 나눕니다. |
| 외부 API | `services/api`의 `partner` 패키지 | 외부 계약이 내부 업무 로직으로 새지 않습니다. 나중에 분리하기 쉽습니다. |
| Android | Kotlin 네이티브 | API와 같은 언어입니다. |
| 계약 | OpenAPI | 언어가 달라도 클라이언트를 생성해 서로 어긋나지 않게 합니다. |

## iOS

Swift 네이티브 앱은 만들지 않습니다. 이유: 구현과 검증에 Xcode와 시뮬레이터가 필요하고, 언어가 하나 늘어납니다.

iOS 사용자가 실제로 확인되면 아래 중 하나를 결정합니다.
- 웹을 모바일 화면에 맞춘 PWA
- Kotlin Multiplatform으로 Android 코드 공유
- Swift 네이티브

## 기각한 안

- **Kotlin 단일(Compose Multiplatform 웹):** `maplibre-compose`의 웹은 Alpha이고 Kotlin/Wasm을 지원하지 않습니다. 열린 이슈에 오버레이 동기화 버그가 있습니다.
- **Kotlin/JS + DOM:** 웹 부품을 직접 만들어야 하고, 외부 JS 라이브러리마다 `external` 선언이 필요합니다. 빌드가 느리고 웹 유지보수 인력이 제한됩니다.
- **`maplibre-js-bindings` 단독 사용:** `maplibre-compose` 내부 부품이며 패치된 엔진에 묶입니다. 0.x 버전입니다.

## 도구

- Kotlin: detekt, Gradle Wrapper
- TypeScript: Oxlint + Anti Slop
- Anti Slop은 JS/TS만 검사합니다. Kotlin에는 적용하지 않습니다.

## 상태

- 계약 대조: `ContractTest`가 계약의 GET 경로를 호출해 응답을 스키마로 검증합니다. 데이터베이스가 필요한 경로(`x-requires-database`)는 각자의 테스트가 맡습니다.
- API: 계정·세션·승인·휴대폰 인증, Ours24 읽기(GET), 주소 저장소와 서비스 문이 동작합니다. 기사·구역·센터 목록은 출처가 연결되기 전까지 비어 있습니다.
- 웹: 로그인·가입·승인 화면과 지도 중심 작업 화면이 동작합니다. 계약에서 생성한 타입을 씁니다. 지도 성능 측정은 [apps/web/README.md](../apps/web/README.md)를 따릅니다.
- 도구: Oxlint + Anti Slop(웹), detekt(API·Android). 새 clone에서 API와 웹 빌드가 됩니다.
- 미검증: 낮은 사양 PC·모바일 기기의 지도 성능, 사람이 직접 입력하는 한글 IME, Android 앱의 에뮬레이터·기기 실행, 실제 API와의 Android 통신, 실제 문자 수신.
