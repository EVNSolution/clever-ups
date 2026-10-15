# apps/android

Kotlin 네이티브 기사 앱. iOS는 [구성 결정](../../docs/architecture.md)에 따라 후순위입니다.

- 로그인: 기사 계정(역할 `driver`)만 씁니다. 다른 계정은 "기사 계정이 아닙니다"로 막힙니다.
- 노선: 그날 전송된 자기 노선을 세로 타임라인(순번·도착 예정·주소·결과)으로 봅니다.
- 정차: 도착, 배송 완료(상업 C·주거 R, 대면은 수령인 영문 이름·비대면은 사진), 예외 보고(UPS 코드 목록).
- 사진: 카메라가 앱 캐시에 찍고(FileProvider), 1600px JPEG로 줄여 보낸 뒤 지웁니다. 갤러리에 남지 않습니다.
- 세션: 쿠키는 앱이 켜져 있는 동안만 기억합니다. 401이면 갱신을 한 번 시도하고, 실패하면 로그인 화면으로 갑니다.
- 고객 이름·전화번호는 서버가 보내지 않으므로 앱에 없습니다.
- 위치: 로그인 뒤 위치 권한을 묻고, 앱이 화면에 있는 동안만 1분·50m 간격으로 위치를 보냅니다(`POST /api/driver/position`). 서버는 기사마다 마지막 위치만 둡니다. 도착에도 그때 위치를 붙입니다.

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk   # 또는 local.properties의 sdk.dir
./gradlew testDebugUnitTest assembleDebug detekt lintDebug
```

- API 주소: 기본은 `http://10.0.2.2:8080`(에뮬레이터에서 본 호스트 PC)입니다. USB로 연결한 휴대폰은 `adb reverse tcp:8080 tcp:8080` 후 `-PupsApiBase=http://127.0.0.1:8080`으로 빌드합니다. 평문 HTTP는 debug 빌드에서만 허용합니다.
- `DriverApiContractTest`: 계약의 `DriverRoute` 필수 필드를 파서가 모두 읽는지, 401 → 갱신 → 재시도, 보고에 CSRF 토큰이 실리는지 확인합니다.

JDK 21, Android SDK 36(platform, build-tools)이 필요합니다. AGP 9.4.1은 Gradle 9.6.0 이상을 요구하므로 이 모듈의 wrapper만 `services/api`와 버전이 다릅니다.
