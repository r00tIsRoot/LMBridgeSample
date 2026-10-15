package com.isroot.lmbridgesample

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isroot.lmbridge.LMBridge
import com.isroot.lmbridge.LMBridgeClient
import com.isroot.lmbridge.download.ModelDownloadManager
import com.isroot.lmbridge.download.ModelDownloadManager.DownloadStatus
import com.isroot.lmbridge.models.Chat
import com.isroot.lmbridge.models.ChatConfig
import com.isroot.lmbridge.models.ChatMessage as HistoryMessage
import com.isroot.lmbridge.models.GenerationChunk
import com.isroot.lmbridge.models.GenerationStats
import com.isroot.lmbridge.models.MultimodalInput
import com.isroot.lmbridgesample.data.ChatStore
import com.isroot.lmbridgesample.data.MessageStatus
import com.isroot.lmbridgesample.data.StoredConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** 앱이 그리는 최상위 화면. */
enum class Screen { ModelSelect, Downloading, Chat }

enum class Role { USER, ASSISTANT }

/**
 * 말풍선 1개.
 *
 * @property thought 모델의 추론(thinking) 텍스트. 답변과 분리된 채널로 오며, 추론 모델에서만 채워진다.
 * @property stats 응답 완료 후 표시하는 처리 속도 요약(어시스턴트 말풍선만).
 * @property status 생성 상태. 저장된 대화를 복원할 때 중단/오류 응답을 구분하는 데 쓴다.
 * @property id DB 행 id(UUID). 스트리밍 중 같은 행을 덮어쓰기 위해 클라이언트에서 만든다.
 */
data class ChatMessage(
    val role: Role,
    val text: String,
    val thought: String = "",
    val stats: String? = null,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** 첨부 종류. */
enum class AttachmentKind { IMAGE, AUDIO, DOCUMENT }

/**
 * 전송 대기 중인 첨부의 UI 표현(칩 렌더링용). 실제 바이트/경로는 ViewModel 내부에만 둔다.
 *
 * @property preview 이미지 첨부의 축소 미리보기(다른 종류는 null).
 */
data class AttachmentUi(
    val id: String,
    val kind: AttachmentKind,
    val label: String,
    val preview: Bitmap? = null,
)

/** 모델 선택 화면 카드 1개. */
data class ModelUi(
    val key: String,
    val label: String,
    val sizeLabel: String,
    val gated: Boolean,
    val downloaded: Boolean,
)

/** 드로어의 채팅방 목록 항목. */
data class RoomUi(val id: String, val title: String)

/** 화면이 구독하는 전체 상태. */
data class UiState(
    val screen: Screen = Screen.ModelSelect,
    // 모델 선택
    val models: List<ModelUi> = emptyList(),
    val loadError: String? = null,
    // 다운로드
    val downloadModelLabel: String = "",
    val downloadPercent: Int = 0,
    val downloadStatus: String = "",
    val downloadError: String? = null,
    val requiresToken: Boolean = false,
    val modelPageUrl: String = "",
    val isDownloading: Boolean = false,
    // 채팅
    val preparing: Boolean = false,
    val activeModelLabel: String = "",
    val rooms: List<RoomUi> = emptyList(),
    val activeRoomId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    // 멀티모달
    val visionActive: Boolean = false,
    val audioActive: Boolean = false,
    val attachments: List<AttachmentUi> = emptyList(),
    val attachError: String? = null,
) {
    val multimodalActive: Boolean get() = visionActive || audioActive
}

/**
 * 다중 모델 · 다중 채팅방을 관리하는 ViewModel.
 *
 * 흐름: 모델 선택 → (필요 시)다운로드 → 엔진 로드 → 여러 채팅방에서 스트리밍 대화.
 *
 * 설계 메모:
 *  - [LMBridgeClient]는 build 시점에 모델이 고정되고 네이티브 자원을 점유한다. 온디바이스
 *    메모리 한계상 **한 번에 하나의 모델만** 로드하며, 모델을 바꾸면 이전 클라이언트를 release한다.
 *  - 채팅방은 로드된 모델에 종속된 [Chat] 세션이다. 모델을 바꾸면 그 세션들은 닫히지만,
 *    화면에 보이던 대화 기록(transcript)은 메모리에 남겨 다시 그 모델을 고르면 복원한다.
 *    세션을 다시 열 때 그 기록을 [ChatConfig.history]로 주입하므로, 모델/방을 왕복해도
 *    모델이 이전 턴을 "기억"한다.
 *  - 방과 말풍선은 [ChatStore](Room)에 기록돼 앱을 다시 켜도 남는다. 메모리의 방 목록은 화면용
 *    캐시이고, 모델을 처음 로드할 때 DB에서 채운다. 스트리밍 중에는 [PERSIST_INTERVAL_MS]
 *    간격으로 묶어 저장하고, 완료·오류·중단 시 최종 상태를 기록한다.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application = app
    private val chatSystemPrompt = "너는 친절하고 간결한 한국어 도우미야."
    private val store = ChatStore.get(app)

    // 다운로드 전용 클라이언트: 어떤 모델을 로드하든 무관하게 models(ModelDownloadManager)만 쓴다.
    // initialize()를 호출하지 않으므로 네이티브 자원은 점유하지 않는다.
    private val downloadClient: LMBridgeClient =
        LMBridgeClient.Builder(appContext).setModel(SampleModels.ALL.first().info).build()
    private val downloads: ModelDownloadManager = downloadClient.models

    // 현재 로드된(추론용) 클라이언트. 모델 전환 시 교체된다.
    private var activeClient: LMBridgeClient? = null
    private var activeModelKey: String? = null

    /** 로드된 모델에 종속된 채팅방. chat 은 그 모델이 로드돼 있을 때만 non-null. */
    private class Room(
        val id: String,
        var title: String,
        val messages: MutableList<ChatMessage> = mutableListOf(),
        var chat: Chat? = null,
        var updatedAt: Long = System.currentTimeMillis(),
    )

    // 모델별 채팅방(전환해도 기록 유지). key = SampleModel.key. DB([store])의 화면용 캐시.
    private val roomsByModel = linkedMapOf<String, MutableList<Room>>()
    private var activeRoomId: String? = null
    private var generateJob: Job? = null
    private var downloadJob: Job? = null

    // 네이티브 세션 생성/해제(newChat·close·release)는 블로킹이다. 메인 스레드에서 호출하면
    // ANR로 이어지므로 항상 백그라운드 디스패처로 넘기고, 이 플래그로 중복 실행을 막는다.
    private var roomBusy = false

    /**
     * 전송 대기 중인 첨부. 실제 미디어 바이트/경로는 여기(내부)에만 두고, UI에는 [AttachmentUi]로
     * 요약해 노출한다(큰 바이트 배열을 StateFlow로 흘리지 않기 위함).
     */
    private sealed class PendingAttachment(
        val id: String,
        val kind: AttachmentKind,
        val label: String,
    ) {
        abstract fun toUi(): AttachmentUi

        class Image(id: String, label: String, val bytes: ByteArray, val preview: Bitmap?) :
            PendingAttachment(id, AttachmentKind.IMAGE, label) {
            override fun toUi() = AttachmentUi(id, kind, label, preview)
        }

        class Audio(id: String, label: String, val bytes: ByteArray) :
            PendingAttachment(id, AttachmentKind.AUDIO, label) {
            override fun toUi() = AttachmentUi(id, kind, label)
        }

        class Document(id: String, label: String, val path: String) :
            PendingAttachment(id, AttachmentKind.DOCUMENT, label) {
            override fun toUi() = AttachmentUi(id, kind, label)
        }
    }

    private val pendingAttachments = mutableListOf<PendingAttachment>()
    private var attachmentCounter = 0

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        LMBridge.setLogLevel(3) // DEBUG
        _uiState.update { it.copy(screen = Screen.ModelSelect, models = modelUiList()) }
    }

    // ── 모델 선택 ────────────────────────────────────────────────────────────

    private fun modelUiList(): List<ModelUi> = SampleModels.ALL.map {
        ModelUi(
            key = it.key,
            label = it.label,
            sizeLabel = it.sizeLabel,
            gated = it.gated,
            downloaded = downloads.isModelDownloaded(it.info),
        )
    }

    /** 모델 카드 선택: 이미 받아둔 모델이면 바로 로드, 아니면 다운로드 화면으로. */
    fun selectModel(key: String) {
        val sm = SampleModels.byKey(key) ?: return
        if (downloads.isModelDownloaded(sm.info)) {
            loadModel(key)
        } else {
            _uiState.update {
                it.copy(
                    screen = Screen.Downloading,
                    downloadModelLabel = "${sm.label} · ${sm.sizeLabel}",
                    downloadPercent = 0,
                    downloadStatus = if (sm.gated) {
                        "이 모델은 게이트(제한) 모델이라 HF Access Token이 필요합니다."
                    } else {
                        "모델을 내려받으세요."
                    },
                    downloadError = null,
                    requiresToken = sm.gated,
                    modelPageUrl = sm.pageUrl,
                    isDownloading = false,
                    loadError = null,
                )
            }
            pendingDownloadKey = key
        }
    }

    // ── 다운로드 ─────────────────────────────────────────────────────────────

    /** 다운로드 화면의 현재 대상 모델 key. UiState 밖에 두어 렌더링과 분리한다. */
    private var pendingDownloadKey: String? = null

    /** HuggingFace에서 대상 모델을 내려받는다. 게이트 모델은 token이 필요하다. */
    fun downloadModel(accessToken: String?) {
        val key = pendingDownloadKey ?: return
        val sm = SampleModels.byKey(key) ?: return
        if (downloadJob?.isActive == true) return
        _uiState.update { it.copy(isDownloading = true, downloadStatus = "다운로드 준비 중…", downloadError = null) }

        downloadJob = viewModelScope.launch {
            downloads.downloadModel(sm.info, accessToken?.ifBlank { null }).collect { status ->
                when (status) {
                    is DownloadStatus.NotStarted ->
                        _uiState.update { it.copy(downloadStatus = "시작 대기…") }

                    is DownloadStatus.Downloading ->
                        _uiState.update {
                            it.copy(
                                downloadPercent = status.progressPercent,
                                downloadStatus = "다운로드 중 ${status.progressPercent}%  " +
                                    "(${status.receivedBytes / 1_000_000}/${status.totalBytes / 1_000_000}MB)",
                            )
                        }

                    is DownloadStatus.Verifying ->
                        _uiState.update { it.copy(downloadStatus = "무결성 검증 중…") }

                    is DownloadStatus.Completed -> {
                        _uiState.update { it.copy(isDownloading = false) }
                        loadModel(key)
                    }

                    is DownloadStatus.Failed -> {
                        val m = status.message
                        _uiState.update {
                            it.copy(
                                isDownloading = false,
                                downloadError = if (m.contains("401") || m.contains("403")) {
                                    "접근 권한이 없습니다(게이트 모델). ①${sm.pageUrl} 에서 로그인 후 " +
                                        "라이선스에 동의하고 ②읽기 권한 토큰(hf_…)을 발급해 입력했는지 확인하세요."
                                } else {
                                    "다운로드 실패: $m"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 엔진 로드 / 모델 전환 ─────────────────────────────────────────────────

    private fun loadModel(key: String) {
        val sm = SampleModels.byKey(key) ?: return

        clearAttachmentsInternal()
        _uiState.update {
            it.copy(
                screen = Screen.Chat,
                preparing = true,
                activeModelLabel = sm.label,
                isGenerating = false,
                loadError = null,
                // 멀티모달 능력은 로드 시작 시점에 확정(첨부 버튼 노출 판단에 사용).
                visionActive = sm.vision,
                audioActive = sm.audio,
                attachments = emptyList(),
                attachError = null,
            )
        }

        viewModelScope.launch {
            releaseActiveClient() // 이전 모델 자원 해제(방 기록은 유지) — 네이티브 close/release는 off-main

            // 멀티모달 모델은 메인 백엔드를 GPU로 둔다(대형 비전 모델의 CPU 텍스트 초기화가
            // 메모리 한계로 실패하는 기기가 있음). 비전/오디오 인코더는 CPU로 분리한다 — 일부
            // GPU는 이미지 샘플러(OpenCL)를 지원하지 않아 GPU 비전이 실패할 수 있기 때문.
            // setMaxNumTokens는 호출하지 않는다 → 각 모델 번들에 내장된 최대 컨텍스트
            // (파일명 ekvNNNN, 예: ekv4096)를 그대로 사용한다. 과거 1024 고정은 큰 문서/
            // 오디오 입력을 총 컨텍스트(입력+출력) 1024 토큰으로 묶어버렸다.
            val client = LMBridgeClient.Builder(appContext)
                .setModel(sm.info)
                .setBackend(if (sm.multimodal) LMBridge.Backend.GPU else LMBridge.Backend.CPU)
                .apply {
                    if (sm.vision) {
                        setMaxNumImages(1)
                        setVisionBackend(LMBridge.Backend.CPU)
                    }
                    if (sm.audio) setAudioBackend(LMBridge.Backend.CPU)
                }
                .setMaxNumTokens(8500)
                .setEnableBenchmark(true) // 응답마다 tokens/s를 말풍선에 표시
                .build()
            activeClient = client
            activeModelKey = key
            try {
                client.initialize() // suspend, 멱등
                val rooms = roomsByModel.getOrPut(key) { mutableListOf() }
                if (rooms.isEmpty()) rooms.addAll(store.load(key).map(::toRoom)) // 저장된 대화 복원
                if (rooms.isEmpty()) rooms.add(newRoom(key))
                val recent = rooms.maxBy { it.updatedAt } // 마지막으로 쓰던 방부터 연다
                activeRoomId = recent.id
                bindSession(client, recent) // 활성 방에만 세션을 연다(엔진당 1개 제약)
                _uiState.update { it.copy(preparing = false) }
                renderChat()
            } catch (e: Throwable) {
                Log.e("LMBridgeSample", "initialize failed", e)
                val causes = generateSequence(e.cause) { it.cause }
                    .joinToString(" ⟵ ") { "${it.javaClass.simpleName}: ${it.message}" }
                    .ifEmpty { "(원인 없음)" }
                releaseActiveClient()
                _uiState.update {
                    it.copy(
                        screen = Screen.ModelSelect,
                        preparing = false,
                        models = modelUiList(),
                        loadError = "초기화 실패: ${e.message}\n원인: $causes",
                    )
                }
            }
        }
    }

    /** 채팅 화면 → 모델 선택 화면으로. 활성 클라이언트를 해제한다(방 기록은 메모리에 유지). */
    fun changeModel() {
        clearAttachmentsInternal()
        // UI는 즉시 전환하고(스냅), 블로킹 네이티브 해제는 백그라운드에서 진행한다.
        _uiState.update {
            it.copy(
                screen = Screen.ModelSelect,
                models = modelUiList(),
                activeModelLabel = "",
                rooms = emptyList(),
                activeRoomId = null,
                messages = emptyList(),
                isGenerating = false,
                loadError = null,
                visionActive = false,
                audioActive = false,
                attachments = emptyList(),
                attachError = null,
            )
        }
        viewModelScope.launch { releaseActiveClient() }
    }

    /**
     * 활성 세션을 [target] 방으로 옮긴다.
     *
     * litertlm 엔진은 **엔진당 대화 세션을 1개만** 허용한다("Only one session is supported at a
     * time"). 그래서 방마다 세션을 동시에 열어둘 수 없다. 살아있는 세션을 모두 닫은 뒤
     * target 방에만 새 세션을 연다. 네이티브 close/createConversation은 블로킹이라 off-main.
     *
     * 방을 전환하면 네이티브 대화 컨텍스트는 새로 만들어지지만, 방의 대화 기록을
     * [ChatConfig.history]로 주입해 문맥을 복원한다.
     */
    private suspend fun bindSession(client: LMBridgeClient, target: Room) {
        val rooms = activeRooms() ?: return
        val toClose = rooms.mapNotNull { r -> r.chat?.also { r.chat = null } }
        val fresh = withContext(Dispatchers.Default) {
            toClose.forEach { runCatching { it.close() } }
            client.newChat(ChatConfig(systemInstruction = chatSystemPrompt, history = historyOf(target)))
        }
        target.chat = fresh
    }

    /**
     * 방의 화면 기록을 세션 히스토리로 변환한다. 빈 말풍선(생성 중단 등)은 제외하고, 컨텍스트
     * 한도를 넘지 않도록 최근 [MAX_HISTORY_MESSAGES]개만 주입한다.
     */
    private fun historyOf(room: Room): List<HistoryMessage> =
        room.messages
            .filter { it.text.isNotBlank() && it.status != MessageStatus.ERROR }
            .takeLast(MAX_HISTORY_MESSAGES)
            .map {
                when (it.role) {
                    Role.USER -> HistoryMessage.user(it.text)
                    Role.ASSISTANT -> HistoryMessage.model(it.text)
                }
            }

    /** 활성 자원을 필드에서 떼어내고(메인, 저비용) 닫아야 할 대상만 돌려준다. */
    private fun detachActiveResources(): Pair<List<Chat>, LMBridgeClient?> {
        generateJob?.cancel()
        generateJob = null
        val chats = activeRooms()?.mapNotNull { it.chat }?.toList() ?: emptyList()
        activeRooms()?.forEach { it.chat = null }
        val client = activeClient
        activeClient = null
        activeModelKey = null
        return chats to client
    }

    /** 활성 클라이언트/세션 해제. 네이티브 close/release는 블로킹이라 off-main에서 처리한다. */
    private suspend fun releaseActiveClient() {
        val (chats, client) = detachActiveResources()
        withContext(Dispatchers.Default) {
            chats.forEach { runCatching { it.close() } }
            runCatching { client?.release() }
        }
    }

    // ── 채팅방 ───────────────────────────────────────────────────────────────

    private fun activeRooms(): MutableList<Room>? = activeModelKey?.let { roomsByModel[it] }

    private fun currentRoom(): Room? = activeRooms()?.firstOrNull { it.id == activeRoomId }

    /** 새 방을 만들고 DB에 기록한다. 번호는 그 모델의 방 개수 기준. */
    private fun newRoom(modelKey: String): Room {
        val number = (roomsByModel[modelKey]?.size ?: 0) + 1
        val room = Room(id = UUID.randomUUID().toString(), title = "$NEW_ROOM_TITLE $number")
        store.createConversation(room.id, modelKey, room.title)
        return room
    }

    private fun toRoom(stored: StoredConversation) = Room(
        id = stored.conversation.id,
        title = stored.conversation.title,
        messages = stored.messages.map {
            ChatMessage(
                role = it.role,
                text = it.text,
                thought = it.thought,
                stats = it.stats,
                status = it.status,
                id = it.id,
                createdAt = it.createdAt,
            )
        }.toMutableList(),
        updatedAt = stored.conversation.updatedAt,
    )

    /** 현재 모델에 새 채팅방을 만들고 활성화한다. 세션은 새 방으로 옮겨진다(엔진당 1개 제약). */
    fun createRoom() {
        val client = activeClient ?: return
        val key = activeModelKey ?: return
        if (roomBusy || _uiState.value.isGenerating || _uiState.value.preparing) return
        clearAttachmentsInternal()
        roomBusy = true
        _uiState.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val room = newRoom(key)
                roomsByModel.getOrPut(key) { mutableListOf() }.add(room)
                bindSession(client, room) // 이전 활성 세션을 닫고 새 방에 세션을 연다
                activeRoomId = room.id
            } finally {
                roomBusy = false
                _uiState.update { it.copy(preparing = false) }
                renderChat()
            }
        }
    }

    /** 채팅방 선택. 세션을 대상 방으로 옮긴다(전환 시 네이티브 컨텍스트는 초기화). */
    fun selectRoom(id: String) {
        val client = activeClient ?: return
        if (id == activeRoomId) return
        if (roomBusy || _uiState.value.isGenerating || _uiState.value.preparing) return
        val target = activeRooms()?.firstOrNull { it.id == id } ?: return
        clearAttachmentsInternal()
        roomBusy = true
        _uiState.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                bindSession(client, target)
                activeRoomId = target.id
            } finally {
                roomBusy = false
                _uiState.update { it.copy(preparing = false) }
                renderChat()
            }
        }
    }

    /** 채팅방 삭제. 마지막 방을 지우면 빈 방 하나를 새로 만든다. 네이티브 close/newChat은 off-main. */
    fun deleteRoom(id: String) {
        val client = activeClient ?: return
        val key = activeModelKey ?: return
        if (roomBusy || _uiState.value.isGenerating || _uiState.value.preparing) return
        val list = activeRooms() ?: return
        val room = list.firstOrNull { it.id == id } ?: return
        clearAttachmentsInternal()
        roomBusy = true
        _uiState.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val wasActive = activeRoomId == id
                // 비활성 방은 세션이 없어(null) 닫을 게 없고, 활성 방만 살아있는 세션을 갖는다.
                val closing = room.chat
                room.chat = null
                list.remove(room)
                store.deleteConversation(room.id)
                withContext(Dispatchers.Default) { runCatching { closing?.close() } }
                if (list.isEmpty()) list.add(newRoom(key))
                if (wasActive) {
                    val next = list.first()
                    activeRoomId = next.id
                    bindSession(client, next) // 새로 활성화된 방에 세션을 연다
                }
            } finally {
                roomBusy = false
                _uiState.update { it.copy(preparing = false) }
                renderChat()
            }
        }
    }

    // ── 첨부(멀티모달) ─────────────────────────────────────────────────────────

    /**
     * 이미지 첨부. content:// URI를 이미 인코딩된 바이트로 읽어 그대로 모델에 전달한다
     * (재인코딩 없음). 미리보기용 축소 비트맵만 별도로 디코드한다.
     */
    fun attachImage(uri: Uri) {
        if (!_uiState.value.visionActive) {
            setAttachError("이 모델은 이미지 입력을 지원하지 않습니다.")
            return
        }
        resolveAttachment(uri, "이미지") { bytes, name ->
            PendingAttachment.Image(nextAttachmentId(), name, bytes, decodePreview(bytes))
        }
    }

    /** 오디오 첨부. URI 바이트를 그대로 전달한다. */
    fun attachAudio(uri: Uri) {
        if (!_uiState.value.audioActive) {
            setAttachError("이 모델은 오디오 입력을 지원하지 않습니다.")
            return
        }
        resolveAttachment(uri, "오디오") { bytes, name ->
            PendingAttachment.Audio(nextAttachmentId(), name, bytes)
        }
    }

    /**
     * 텍스트 문서 첨부. URI 내용을 앱 캐시의 임시 파일로 복사해 그 경로를 전달한다
     * (라이브러리 [MultimodalInput.Builder.document]가 경로에서 텍스트를 읽어 컨텍스트로 주입).
     * 문서는 모든 모델에서 동작한다(텍스트 주입).
     */
    fun attachDocument(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri, "document.txt")
                    val file = copyToCache(uri, name)
                    PendingAttachment.Document(nextAttachmentId(), name, file.absolutePath)
                }
            }
            result
                .onSuccess { addAttachment(it) }
                .onFailure { setAttachError("문서 첨부 실패: ${it.message}") }
        }
    }

    /** 대기 중 첨부 1개 제거. */
    fun removeAttachment(id: String) {
        pendingAttachments.removeAll { it.id == id }
        renderAttachments()
    }

    /** 대기 중 첨부 전체 제거. */
    fun clearAttachments() {
        clearAttachmentsInternal()
        renderAttachments()
    }

    /** 첨부 오류 배너 소거. */
    fun dismissAttachError() = _uiState.update { it.copy(attachError = null) }

    private fun resolveAttachment(
        uri: Uri,
        label: String,
        build: (bytes: ByteArray, name: String) -> PendingAttachment,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = readBytes(uri) ?: error("내용을 읽을 수 없습니다.")
                    build(bytes, displayName(uri, label))
                }
            }
            result
                .onSuccess { addAttachment(it) }
                .onFailure { setAttachError("$label 첨부 실패: ${it.message}") }
        }
    }

    private fun addAttachment(a: PendingAttachment) {
        pendingAttachments.add(a)
        _uiState.update { it.copy(attachError = null) }
        renderAttachments()
    }

    private fun clearAttachmentsInternal() {
        pendingAttachments.clear()
    }

    private fun setAttachError(message: String) =
        _uiState.update { it.copy(attachError = message) }

    private fun nextAttachmentId(): String {
        attachmentCounter += 1
        return "att-$attachmentCounter"
    }

    private fun renderAttachments() {
        _uiState.update { s -> s.copy(attachments = pendingAttachments.map { it.toUi() }) }
    }

    private fun readBytes(uri: Uri): ByteArray? =
        appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    private fun displayName(uri: Uri, fallback: String): String = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull() ?: fallback

    private fun copyToCache(uri: Uri, name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
        val out = File(appContext.cacheDir, "attach_${nextAttachmentId()}_$safe")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: error("내용을 읽을 수 없습니다.")
        return out
    }

    /** 첨부 미리보기용 축소 비트맵. 실패해도(디코드 불가) null 로 무시한다. */
    private fun decodePreview(bytes: ByteArray, target: Int = 256): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge > 0 && longEdge / sample > target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

    private fun AttachmentKind.icon(): String = when (this) {
        AttachmentKind.IMAGE -> "🖼️"
        AttachmentKind.AUDIO -> "🎵"
        AttachmentKind.DOCUMENT -> "📄"
    }

    // ── 생성 ─────────────────────────────────────────────────────────────────

    /**
     * 프롬프트(+첨부)를 전송하고 토큰을 스트리밍으로 받아 마지막 말풍선에 채운다.
     *
     * 대기 중 첨부가 있으면 [MultimodalInput]으로 묶어 보낸다(첨부 → 텍스트 순서). 첨부는
     * 전송 직후 비워진다.
     */
    fun send(prompt: String) {
        val room = currentRoom() ?: return
        val chat = room.chat ?: return
        val attachments = pendingAttachments.toList()
        val hasAttachments = attachments.isNotEmpty()
        if ((prompt.isBlank() && !hasAttachments) || _uiState.value.isGenerating) return

        // 사용자 말풍선에는 첨부 요약 줄 + 프롬프트를 함께 보여준다.
        val summary = attachments.joinToString("") { "${it.kind.icon()} ${it.label}\n" }
        val userText = (summary + prompt).trimEnd().ifBlank { summary.trim() }
        addMessage(room, ChatMessage(Role.USER, userText))
        if (room.title.startsWith(NEW_ROOM_TITLE)) {
            room.title = prompt.ifBlank { attachments.firstOrNull()?.label ?: "첨부" }.take(18)
            store.rename(room.id, room.title)
        }
        addMessage(room, ChatMessage(Role.ASSISTANT, "", status = MessageStatus.STREAMING))

        // 첨부는 소비했으므로 비운다.
        clearAttachmentsInternal()
        _uiState.update { it.copy(isGenerating = true, attachments = emptyList()) }
        renderChat() // 방 제목이 바뀔 수 있으므로 목록까지 갱신

        val stream = if (hasAttachments) {
            val builder = MultimodalInput.Builder()
            attachments.forEach { a ->
                when (a) {
                    is PendingAttachment.Image -> builder.imageBytes(a.bytes)
                    is PendingAttachment.Audio -> builder.audioBytes(a.bytes)
                    is PendingAttachment.Document -> builder.document(a.path)
                }
            }
            if (prompt.isNotBlank()) builder.text(prompt)
            chat.send(builder.build())
        } else {
            chat.send(prompt)
        }

        val builder = StringBuilder()
        val thought = StringBuilder()
        var lastPersistAt = 0L

        // 스트리밍 중 저장은 PERSIST_INTERVAL_MS 간격으로 묶는다(토큰마다 쓰지 않음).
        fun persistThrottled() {
            val now = System.currentTimeMillis()
            if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                lastPersistAt = now
                persistLastAssistant(room)
            }
        }

        generateJob = viewModelScope.launch {
            try {
                stream.collect { chunk ->
                    when (chunk) {
                        is GenerationChunk.Token -> {
                            builder.append(chunk.text)
                            updateLastAssistant(room) { it.copy(text = builder.toString()) }
                            renderMessages()
                            persistThrottled()
                        }

                        is GenerationChunk.Thought -> {
                            thought.append(chunk.text)
                            updateLastAssistant(room) { it.copy(thought = thought.toString()) }
                            renderMessages()
                            persistThrottled()
                        }

                        is GenerationChunk.ToolCall -> {
                            builder.append("\n[도구 호출: ${chunk.name} ${chunk.argsJson}]")
                            updateLastAssistant(room) { it.copy(text = builder.toString()) }
                            renderMessages()
                            persistThrottled()
                        }

                        is GenerationChunk.Done -> {
                            val stats = runCatching { chat.stats() }.getOrNull()?.let(::formatStats)
                            updateLastAssistant(room) { it.copy(stats = stats, status = MessageStatus.COMPLETE) }
                            persistLastAssistant(room)
                            renderMessages()
                            _uiState.update { it.copy(isGenerating = false) }
                        }

                        is GenerationChunk.Error -> {
                            updateLastAssistant(room) {
                                it.copy(
                                    text = builder.toString() + "\n⚠️ ${chunk.error.message}",
                                    status = MessageStatus.ERROR,
                                )
                            }
                            persistLastAssistant(room)
                            renderMessages()
                            _uiState.update { it.copy(isGenerating = false) }
                        }
                    }
                }
            } finally {
                // 중지·모델 전환·ViewModel 해제로 Done 없이 끝났다면 중단으로 확정한다.
                // (취소된 코루틴에서도 동작하도록 저장 호출은 suspend가 아니다.)
                if (lastAssistant(room)?.status == MessageStatus.STREAMING) {
                    updateLastAssistant(room) { it.copy(status = MessageStatus.INTERRUPTED) }
                    persistLastAssistant(room)
                    renderMessages()
                }
                // 스트림이 (취소 등으로) Done 없이 끝나도 상태를 되돌린다.
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /** 진행 중 생성 취소. */
    fun stop() {
        currentRoom()?.chat?.stop()
        generateJob?.cancel()
        _uiState.update { it.copy(isGenerating = false) }
    }

    private fun addMessage(room: Room, message: ChatMessage) {
        room.messages.add(message)
        room.updatedAt = System.currentTimeMillis()
        store.saveMessage(room.id, room.messages.lastIndex, message)
    }

    private fun lastAssistant(room: Room): ChatMessage? =
        room.messages.lastOrNull { it.role == Role.ASSISTANT }

    private fun updateLastAssistant(room: Room, transform: (ChatMessage) -> ChatMessage) {
        val idx = room.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (idx >= 0) room.messages[idx] = transform(room.messages[idx])
    }

    private fun persistLastAssistant(room: Room) {
        val idx = room.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (idx >= 0) {
            room.updatedAt = System.currentTimeMillis()
            store.saveMessage(room.id, idx, room.messages[idx])
        }
    }

    /** 처리 속도 요약. 벤치마크 수치가 없으면 컨텍스트 토큰 수만 표시한다. */
    private fun formatStats(s: GenerationStats): String = buildList {
        s.decodeTokensPerSecond?.let { add("%.1f tok/s".format(it)) }
        s.timeToFirstTokenSeconds?.let { add("첫 토큰 %.1fs".format(it)) }
        add("컨텍스트 ${s.contextTokens} 토큰")
    }.joinToString(" · ")

    // ── 렌더링 헬퍼 ──────────────────────────────────────────────────────────

    private fun renderChat() {
        val rooms = activeRooms()?.map { RoomUi(it.id, it.title) } ?: emptyList()
        val msgs = currentRoom()?.messages?.toList() ?: emptyList()
        _uiState.update { it.copy(rooms = rooms, activeRoomId = activeRoomId, messages = msgs) }
    }

    private fun renderMessages() {
        val msgs = currentRoom()?.messages?.toList() ?: emptyList()
        _uiState.update { it.copy(messages = msgs) }
    }

    override fun onCleared() {
        // viewModelScope는 이미 취소됐으므로 코루틴을 못 쓴다. 자원만 떼어내(메인) 별도
        // 스레드에서 best-effort로 닫는다 — 메인 스레드 블로킹(ANR)을 피하기 위함.
        val (chats, client) = detachActiveResources()
        kotlin.concurrent.thread(name = "lmbridge-release") {
            chats.forEach { runCatching { it.close() } }
            runCatching { client?.release() }
        }
        downloadClient.release()
        super.onCleared()
    }

    private companion object {
        /** 세션 재생성 시 주입할 최대 히스토리 메시지 수(컨텍스트 한도 보호). */
        const val MAX_HISTORY_MESSAGES = 20

        /** 스트리밍 중 DB 저장 간격. */
        const val PERSIST_INTERVAL_MS = 500L

        const val NEW_ROOM_TITLE = "새 채팅"
    }
}
