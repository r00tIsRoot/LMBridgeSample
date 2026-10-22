# LMBridge Sample

[LMBridge](https://github.com/r00tIsRoot/LMBridge) 라이브러리를 **Maven 의존성으로** 가져와
온디바이스 LLM 추론을 시연하는 최소 안드로이드 앱입니다.

## 무엇을 보여주나

- GitHub Pages Maven 저장소에서 `com.isroot:lmbridge:0.3.0` 의존성 해석
- 모델 다운로드(진행률 · 무결성 검증) → 엔진 초기화 → **스트리밍 대화**
- `LMBridgeClient` / `Chat` / `GenerationChunk` / `ModelDownloadManager` 실사용
- **대화 저장(Room)** — 앱을 다시 켜도 채팅방·대화가 남고, `ChatConfig.history`로 모델 문맥까지 복원

## 라이브러리 연동 지점

두 곳만 보면 됩니다.

`settings.gradle.kts` — 저장소 등록:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://r00tisroot.github.io/packages/") }
    }
}
```

`app/build.gradle.kts` — 의존성 추가:

```kotlin
dependencies {
    implementation("com.isroot:lmbridge:0.3.0")
}
```

> LMBridge 0.3.0은 litertlm 0.18(kotlin-stdlib 2.4)에 의존하므로 **앱의 Kotlin 플러그인이 2.3 이상**
> 이어야 합니다. 이 샘플은 Kotlin·Compose 컴파일러 플러그인 2.4.21을 사용합니다.

litertlm·coroutines 등 라이브러리 런타임 의존성은 AAR + Gradle Module Metadata를 통해
자동으로 따라옵니다.

## 실행

1. Android Studio로 이 디렉터리를 열거나, CLI로 빌드:

   ```bash
   ./gradlew :app:assembleDebug
   ```

2. 기기/에뮬레이터에 설치 후 실행 → **모델 다운로드**(약 584MB, `Gemma3-1B-IT`).
   기본 모델은 HuggingFace **게이트(제한) 모델**이라 **HF Access Token**이 필요합니다
   (① 모델 페이지에서 라이선스 동의 → ② 읽기 권한 토큰 `hf_…` 발급 → 앱에 입력).

3. 다운로드·초기화가 끝나면 채팅으로 스트리밍 응답을 확인합니다.

### "초기화 실패: Backend unavailable: CPU"의 진짜 원인 (해결됨)

증상은 백엔드 문제처럼 보이지만 실제 원인은 **라이브러리 버그**였습니다. 배포본 0.2.0은
MTP(**speculative decoding**)를 **항상 켜둔 채** 엔진을 생성하는데, 카탈로그의 일반
`.litertlm` 모델(Gemma3-1B·Qwen2.5·DeepSeek **모두**)은 MTP용 `embedding_lookup`
테이블을 포함하지 않습니다. 그래서 네이티브 초기화가
`embedding_lookup == nullptr` (RET_CHECK)로 실패하고, 어댑터가 이를 뭉뚱그려
`Backend unavailable: CPU`로 표기했습니다. **모델 선택과 무관한** 문제였습니다.

**해결:** 라이브러리에서 speculative decoding을 **기본 off**로 바꾸고, 필요 시에만 켜도록
`LMBridgeClient.Builder.setEnableSpeculativeDecoding(true)`를 추가했습니다(0.2.1).

### 공개(비게이트) 모델로 바꾸려면

기본 모델 Gemma3-1B는 게이트 모델이라 토큰이 필요합니다. 토큰 없이 바로 받는 공개 모델
(예: `Qwen2.5-1.5B-Instruct`)로 바꾸려면 `ChatViewModel`에서 두 값만 교체하면 됩니다
(게이트가 아니므로 토큰 입력 UI는 자동으로 숨겨집니다).

```kotlin
private val model = ModelCatalog.QWEN2_5_1_5B_INSTRUCT // ~1.6GB, 공개
private val modelIsGated = false
```

## 구성

| 파일 | 역할 |
|------|------|
| `ChatViewModel.kt` | 다운로드 → 초기화 → 대화 → 해제 전 과정 |
| `MainActivity.kt` | Jetpack Compose UI(설정 화면 · 채팅 화면) |
| `data/` | 대화 저장소 — `ChatDatabase`(Room) · `ChatDao` · `ChatStore`(쓰기 직렬화 큐) |
| `app/schemas/` | Room 스키마 JSON(마이그레이션 검토용, 버전 관리 대상) |

## 채팅방 세션

- litertlm 0.18부터 한 엔진에서 대화 세션을 여러 개 동시에 열 수 있어, 채팅방마다 세션을 열어 둡니다.
  방을 오가도 KV 캐시가 유지되어 다시 prefill하지 않습니다.
- 열린 세션 수는 기기 RAM에 따라 제한합니다(5GB 미만 2개 · 9GB 미만 3개 · 그 이상 4개). 넘치면 가장
  오래 안 쓴 방의 세션을 닫고, 그 방을 다시 열 때 대화 기록을 히스토리로 주입해 복원합니다.

## 대화 저장

- 채팅방(`conversations`)과 말풍선(`messages`)을 모델별로 Room에 저장합니다. 모델을 로드하면
  저장된 방을 읽어 오고, 마지막으로 쓰던 방을 엽니다. 방의 최근 20개 메시지는 세션 히스토리로
  주입돼 모델이 이전 대화를 기억합니다.
- 응답은 생성 시작 시 `STREAMING` 상태로 넣고, 스트리밍 중에는 0.5초 간격으로 묶어 갱신한 뒤
  완료(`COMPLETE`)·오류(`ERROR`)·중지(`INTERRUPTED`)로 확정합니다. 생성 도중 앱이 종료되면
  다음 실행에서 `INTERRUPTED`로 정리되어 "⏹ 중단됨"으로 표시됩니다.
- 모든 DB 작업은 `ChatStore`의 단일 큐로 직렬화되어 순서가 보장되며, 프로세스 수명 스코프에서
  실행되므로 화면이 사라지는 순간의 마지막 기록도 남습니다.
- 첨부 미디어(이미지·오디오 바이트)는 저장하지 않고, 말풍선의 첨부 요약 텍스트만 남깁니다.
- 샘플이므로 암호화(SQLCipher)·백업 제외 규칙은 적용하지 않았습니다(`allowBackup="true"`).

## 요구 사항

- Android **API 26+**
- Kotlin 2.4.21 · AGP 8.13.2 · Gradle 8.14.4 · KSP 2.3.12 · Room 2.8.5
- 모델 저장을 위한 충분한 저장공간(약 600MB+)
