package com.isroot.lmbridgesample

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.isroot.lmbridge.LMBridge
import com.isroot.lmbridge.LMBridgeClient
import com.isroot.lmbridge.download.ModelDownloadManager
import com.isroot.lmbridge.download.ModelDownloadManager.DownloadStatus
import com.isroot.lmbridge.models.Chat
import com.isroot.lmbridge.models.ChatConfig
import com.isroot.lmbridge.models.GenerationChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 앱이 그리는 최상위 화면. */
enum class Screen { ModelSelect, Downloading, Chat }

enum class Role { USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

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
)

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
 *    단 네이티브 대화 문맥은 초기화되므로, 모델을 왕복한 뒤에는 모델이 이전 턴을 "기억"하지 않는다.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext: Application = app
    private val chatSystemPrompt = "너는 친절하고 간결한 한국어 도우미야."

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
    )

    // 모델별 채팅방(전환해도 기록 유지). key = SampleModel.key.
    private val roomsByModel = linkedMapOf<String, MutableList<Room>>()
    private var activeRoomId: String? = null
    private var roomCounter = 0
    private var generateJob: Job? = null
    private var downloadJob: Job? = null

    // 네이티브 세션 생성/해제(newChat·close·release)는 블로킹이다. 메인 스레드에서 호출하면
    // ANR로 이어지므로 항상 백그라운드 디스패처로 넘기고, 이 플래그로 중복 실행을 막는다.
    private var roomBusy = false

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

        _uiState.update {
            it.copy(
                screen = Screen.Chat,
                preparing = true,
                activeModelLabel = sm.label,
                isGenerating = false,
                loadError = null,
            )
        }

        viewModelScope.launch {
            releaseActiveClient() // 이전 모델 자원 해제(방 기록은 유지) — 네이티브 close/release는 off-main

            val client = LMBridgeClient.Builder(appContext)
                .setModel(sm.info)
                .setBackend(LMBridge.Backend.CPU)
                .setMaxNumTokens(1024)
                .build()
            activeClient = client
            activeModelKey = key
            try {
                client.initialize() // suspend, 멱등
                val rooms = roomsByModel.getOrPut(key) { mutableListOf() }
                if (rooms.isEmpty()) rooms.add(newRoom())
                val first = rooms.first()
                activeRoomId = first.id
                bindSession(client, first) // 활성 방에만 세션을 연다(엔진당 1개 제약)
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
     * 트레이드오프: 방을 전환하면 네이티브 대화 컨텍스트가 초기화된다(화면의 대화 기록은 유지).
     */
    private suspend fun bindSession(client: LMBridgeClient, target: Room) {
        val rooms = activeRooms() ?: return
        val toClose = rooms.mapNotNull { r -> r.chat?.also { r.chat = null } }
        val fresh = withContext(Dispatchers.Default) {
            toClose.forEach { runCatching { it.close() } }
            client.newChat(ChatConfig(systemInstruction = chatSystemPrompt))
        }
        target.chat = fresh
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

    private fun newRoom(): Room {
        roomCounter += 1
        return Room(id = "room-$roomCounter", title = "새 채팅 $roomCounter")
    }

    /** 현재 모델에 새 채팅방을 만들고 활성화한다. 세션은 새 방으로 옮겨진다(엔진당 1개 제약). */
    fun createRoom() {
        val client = activeClient ?: return
        val key = activeModelKey ?: return
        if (roomBusy || _uiState.value.isGenerating || _uiState.value.preparing) return
        roomBusy = true
        _uiState.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val room = newRoom()
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
        if (roomBusy || _uiState.value.isGenerating || _uiState.value.preparing) return
        val list = activeRooms() ?: return
        val room = list.firstOrNull { it.id == id } ?: return
        roomBusy = true
        _uiState.update { it.copy(preparing = true) }
        viewModelScope.launch {
            try {
                val wasActive = activeRoomId == id
                // 비활성 방은 세션이 없어(null) 닫을 게 없고, 활성 방만 살아있는 세션을 갖는다.
                val closing = room.chat
                room.chat = null
                list.remove(room)
                withContext(Dispatchers.Default) { runCatching { closing?.close() } }
                if (list.isEmpty()) list.add(newRoom())
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

    // ── 생성 ─────────────────────────────────────────────────────────────────

    /** 프롬프트를 전송하고 토큰을 스트리밍으로 받아 마지막 말풍선에 채운다. */
    fun send(prompt: String) {
        val room = currentRoom() ?: return
        val chat = room.chat ?: return
        if (prompt.isBlank() || _uiState.value.isGenerating) return

        room.messages.add(ChatMessage(Role.USER, prompt))
        if (room.title.startsWith("새 채팅")) room.title = prompt.take(18)
        room.messages.add(ChatMessage(Role.ASSISTANT, ""))
        _uiState.update { it.copy(isGenerating = true) }
        renderChat() // 방 제목이 바뀔 수 있으므로 목록까지 갱신

        val builder = StringBuilder()
        generateJob = viewModelScope.launch {
            chat.send(prompt).collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Token -> {
                        builder.append(chunk.text)
                        replaceLastAssistant(room, builder.toString())
                        renderMessages()
                    }

                    is GenerationChunk.ToolCall -> {
                        builder.append("\n[도구 호출: ${chunk.name} ${chunk.argsJson}]")
                        replaceLastAssistant(room, builder.toString())
                        renderMessages()
                    }

                    is GenerationChunk.Done ->
                        _uiState.update { it.copy(isGenerating = false) }

                    is GenerationChunk.Error -> {
                        replaceLastAssistant(room, builder.toString() + "\n⚠️ ${chunk.error.message}")
                        renderMessages()
                        _uiState.update { it.copy(isGenerating = false) }
                    }
                }
            }
            // 스트림이 (취소 등으로) Done 없이 끝나도 상태를 되돌린다.
            _uiState.update { it.copy(isGenerating = false) }
        }
    }

    /** 진행 중 생성 취소. */
    fun stop() {
        currentRoom()?.chat?.stop()
        generateJob?.cancel()
        _uiState.update { it.copy(isGenerating = false) }
    }

    private fun replaceLastAssistant(room: Room, text: String) {
        val idx = room.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (idx >= 0) room.messages[idx] = room.messages[idx].copy(text = text)
    }

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
}
