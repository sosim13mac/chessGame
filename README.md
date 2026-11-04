# 비디오 캐처 (VideoCatcher)

웹페이지에서 **동영상을 재생하면 자동으로 감지해서 내려받을 수 있게 해 주는** 안드로이드 웹 브라우저 앱입니다.
WebView가 페이지를 불러올 때 동영상 감지 스크립트를 **페이지 스크립트보다 먼저** 삽입하고,
재생되는 동영상을 찾아 바로 다운로드할 수 있게 합니다.

- 언어/플랫폼: Kotlin, Android 8.0(API 26) 이상, targetSdk 36
- 외부 라이브러리: `androidx.webkit`, `androidx.annotation`만 사용 (UI는 안드로이드 기본 위젯)

## 주요 기능

| 기능 | 설명 |
| --- | --- |
| 브라우저 | 주소창(검색어 입력 시 구글 검색), 뒤로/앞으로/홈/새로고침, 전체화면 동영상, 데스크톱 모드, 링크 공유 받기 |
| 재생 감지 | 동영상이 재생되면 화면 아래에 **“동영상 감지됨 · 다운로드”** 알림이 뜸 |
| 감지 목록 | 아래 막대의 동영상 버튼(숫자 배지)으로 페이지에서 찾은 동영상 목록 보기 |
| 일반 파일 다운로드 | mp4, webm, mov 등은 안드로이드 `DownloadManager`로 저장 (쿠키·Referer·User-Agent 포함) |
| HLS(.m3u8) 다운로드 | 화질 선택 → 조각(segment)을 병렬로 받아 하나로 합친 뒤 **MP4로 변환**(재인코딩 없음) |
| 전체화면 다운로드 | 전체화면으로 보는 중에도 오른쪽 위 버튼으로 바로 다운로드 |
| 저장 위치 | `다운로드/VideoCatcher` 폴더 (갤러리·파일 앱에서 보임) |

## 동작 원리

```
 페이지 로드 ──► [document start] video_sniffer.js 가 모든 프레임(iframe 포함)에 먼저 삽입됨
                    │
                    ├─ <video> 의 play / playing / loadedmetadata 이벤트 감시 (캡처 단계, Shadow DOM 포함)
                    ├─ fetch() / XMLHttpRequest 감시 → .m3u8 / .mpd / 동영상 파일 요청 기록
                    └─ HTMLMediaElement.play() 감시
                    │
                    ▼  window.VideoCatcherBridge.postMessage(JSON)   (addJavascriptInterface)
 WebViewClient.shouldInterceptRequest ──► 모든 네트워크 요청에서 동영상 주소 감지 (Web Worker 요청 포함)
                    │
                    ▼
               VideoStore: 중복 병합, blob:(MediaSource) 재생 ↔ 원본 .m3u8 연결,
                           화질별 하위 재생목록·스트림 조각 숨김, DRM 표시
                    │
                    ▼
     재생 감지 알림 / 감지 목록 ──► 다운로드
                                   ├─ 일반 파일: DownloadManager
                                   └─ HLS: HlsDownloadService (포그라운드 서비스)
                                         재생목록 분석 → 조각 병렬 다운로드 → 합치기
                                         → MediaExtractor/MediaMuxer 로 MP4 변환 → 다운로드 폴더 저장
```

- 스크립트 삽입은 `WebViewCompat.addDocumentStartJavaScript()`(androidx.webkit)를 사용합니다.
  이 기능이 없는 아주 오래된 WebView에서는 페이지 로딩 중 `evaluateJavascript()`로 주입합니다(메인 프레임만).
- hls.js 같은 플레이어로 `blob:`(MediaSource) 주소에서 재생하는 사이트는, 플레이어가 받아 간 원본 재생목록(.m3u8)을 찾아 그 주소로 다운로드합니다.

## 프로젝트 구조

```
app/src/main/
├── assets/
│   ├── video_sniffer.js        # 페이지에 미리 삽입되는 동영상 감지 스크립트
│   └── home.html               # 시작 페이지
├── java/com/sosim/videocatcher/
│   ├── MainActivity.kt         # 브라우저 화면 (WebView, 주소창, 감지 알림, 다운로드 흐름)
│   ├── VideoCatcherApp.kt      # 알림 채널 생성
│   ├── sniffer/                # 감지: 스크립트 주입, JS 브리지, URL 분류, 감지 목록(VideoStore)
│   ├── hls/                    # HLS 재생목록 파서, RFC 3986 URL 해석기
│   ├── net/                    # HTTP 클라이언트(리다이렉트·Range·쿠키), 요청 헤더 구성
│   ├── download/               # DownloadManager, HLS 다운로드 서비스, MP4 변환, 저장
│   ├── ui/                     # 감지 목록 바텀시트 등
│   └── util/                   # 파일 이름, 표시 형식, 주소창 입력 처리
└── res/                        # 레이아웃, 아이콘(벡터), 문자열(한국어), 테마(라이트/다크)
app/src/test/                   # JVM 단위 테스트 (파서, 감지 목록, HLS 다운로더 등)
```

## 빌드

준비물: Android Studio(또는 Android SDK) + JDK 17 이상

- **Android Studio**: 이 폴더를 열고 ▶ Run
- **명령줄**:

```bash
./gradlew assembleDebug
```

APK 위치: `app/build/outputs/apk/debug/app-debug.apk`

단위 테스트:

```bash
./gradlew testDebugUnitTest
```

GitHub에 올리면 `.github/workflows/android.yml`이 자동으로 테스트와 APK 빌드를 하고,
Actions 실행 결과의 **Artifacts**에서 `VideoCatcher-debug-apk`를 내려받을 수 있습니다.

빌드 구성: AGP 8.13.2, Kotlin 2.3.21, Gradle 8.14.3, compileSdk/targetSdk 36, minSdk 26.

## 사용 방법

1. 앱을 열고 주소창에 사이트 주소나 검색어를 입력합니다.
2. 동영상을 **재생**하면 화면 아래에 “동영상 감지됨” 알림이 뜹니다. **다운로드**를 누르세요.
3. HLS 스트림은 화질을 고른 뒤 파일 이름을 확인하면 알림창에서 진행 상황이 보입니다.
4. 지나간 감지 결과는 아래 막대의 동영상 버튼에서 다시 볼 수 있습니다. 항목을 길게 누르면 주소 복사·공유·다른 앱으로 열기가 가능합니다.

권한: 알림(Android 13+, 진행 상황 표시용), 저장소(Android 9 이하에서만).

## 제한 사항

- **DRM(Widevine 등)이나 암호화(AES-128, SAMPLE-AES)된 스트림은 다운로드하지 않습니다.** 감지 목록에 “DRM 보호/암호화됨”으로 표시됩니다.
- 라이브 방송(끝이 없는 HLS)은 받을 수 없습니다.
- DASH(.mpd)는 감지만 하고 다운로드는 아직 지원하지 않습니다(주소 복사 가능).
- 재생목록을 쓰지 않는 독자 스트리밍 방식(예: 유튜브의 자체 프로토콜)은 받을 수 없습니다.
- MP4 변환은 H.264/H.265 영상과 AAC 음성에서 동작합니다. 다른 코덱이거나 변환에 실패하면 원본 형식(.ts 또는 조각 MP4)으로 저장합니다.
- 일부 사이트는 앱(WebView)에서의 로그인이나 재생을 막을 수 있습니다.

## 주의

저작권이 있는 동영상은 **권리자가 허락한 경우에만** 내려받고, 각 사이트의 이용약관을 지켜 주세요.
이 앱은 DRM 등 기술적 보호 조치를 우회하지 않습니다.
