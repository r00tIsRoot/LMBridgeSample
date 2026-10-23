package com.isroot.lmbridgesample

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.isroot.lmbridgesample.data.MessageStatus
import com.isroot.lmbridgesample.ui.theme.LMBridgeSampleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LMBridgeSampleTheme {
                val vm: ChatViewModel = viewModel()
                val state by vm.uiState.collectAsStateWithLifecycle()
                when (state.screen) {
                    Screen.ModelSelect -> ModelSelectScreen(state, onSelect = vm::selectModel)
                    Screen.Downloading -> DownloadScreen(state, onDownload = vm::downloadModel)
                    Screen.Chat -> ChatScreen(
                        state = state,
                        onSend = vm::send,
                        onStop = vm::stop,
                        onNewRoom = vm::createRoom,
                        onSelectRoom = vm::selectRoom,
                        onDeleteRoom = vm::deleteRoom,
                        onChangeModel = vm::changeModel,
                        onAttachImage = vm::attachImage,
                        onAttachAudio = vm::attachAudio,
                        onAttachDocument = vm::attachDocument,
                        onRemoveAttachment = vm::removeAttachment,
                        onDismissAttachError = vm::dismissAttachError,
                    )
                }
            }
        }
    }
}

// ── 모델 선택 ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectScreen(state: UiState, onSelect: (String) -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("모델 선택") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text(
                "사용할 온디바이스 모델을 고르세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (state.loadError != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        state.loadError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.models) { m -> ModelCard(m, onSelect) }
            }
        }
    }
}

@Composable
private fun ModelCard(m: ModelUi, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(m.key) },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(m.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.sizeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.downloaded) Badge("다운로드됨", MaterialTheme.colorScheme.primary)
                    if (m.gated) Badge("게이트", MaterialTheme.colorScheme.tertiary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onSelect(m.key) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (m.downloaded) "선택" else "다운로드 후 선택")
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ── 다운로드 ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadScreen(state: UiState, onDownload: (String?) -> Unit) {
    var token by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("모델 다운로드") }) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(state.downloadModelLabel, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                state.downloadError ?: state.downloadStatus,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (state.downloadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(24.dp))

            if (state.isDownloading) {
                if (state.downloadPercent > 0) {
                    LinearProgressIndicator(progress = { state.downloadPercent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else {
                if (state.requiresToken) {
                    Text(
                        "게이트(제한) 모델입니다. 다운로드하려면:\n" +
                            "① ${state.modelPageUrl} 에서 로그인 후 라이선스 동의\n" +
                            "② huggingface.co/settings/tokens 에서 읽기 권한 토큰(hf_…) 발급\n" +
                            "③ 아래에 붙여넣기",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(if (state.requiresToken) "HF Access Token (필수)" else "HF Access Token (선택)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onDownload(token) },
                    enabled = !(state.requiresToken && token.isBlank()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.downloadError != null) "다시 시도" else "다운로드")
                }
            }
        }
    }
}

// ── 채팅(드로어 + 대화) ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewRoom: () -> Unit,
    onSelectRoom: (String) -> Unit,
    onDeleteRoom: (String) -> Unit,
    onChangeModel: () -> Unit,
    onAttachImage: (Uri) -> Unit,
    onAttachAudio: (Uri) -> Unit,
    onAttachDocument: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissAttachError: () -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    state = state,
                    onNewRoom = { onNewRoom(); scope.launch { drawerState.close() } },
                    onSelectRoom = { onSelectRoom(it); scope.launch { drawerState.close() } },
                    onDeleteRoom = onDeleteRoom,
                    onChangeModel = { onChangeModel(); scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.activeModelLabel.ifEmpty { "채팅" }) },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰") }
                    },
                )
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                if (state.preparing) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("준비 중…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    ChatArea(
                        state = state,
                        onSend = onSend,
                        onStop = onStop,
                        onAttachImage = onAttachImage,
                        onAttachAudio = onAttachAudio,
                        onAttachDocument = onAttachDocument,
                        onRemoveAttachment = onRemoveAttachment,
                        onDismissAttachError = onDismissAttachError,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    state: UiState,
    onNewRoom: () -> Unit,
    onSelectRoom: (String) -> Unit,
    onDeleteRoom: (String) -> Unit,
    onChangeModel: () -> Unit,
) {
    val busy = state.isGenerating || state.preparing
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("모델", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(4.dp))
        Text(state.activeModelLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.activeBackend.isNotEmpty()) {
            Text("백엔드: ${state.activeBackend}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onChangeModel,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("모델 변경") }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "채팅방",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNewRoom, enabled = !busy) { Text("＋ 새 채팅방") }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.rooms) { room ->
                RoomRow(
                    room = room,
                    selected = room.id == state.activeRoomId,
                    enabled = !busy,
                    onSelect = { onSelectRoom(room.id) },
                    onDelete = { onDeleteRoom(room.id) },
                )
            }
        }
    }
}

@Composable
private fun RoomRow(
    room: RoomUi,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onSelect() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                room.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = onDelete, enabled = enabled) { Text("🗑") }
        }
    }
}

@Composable
private fun ChatArea(
    state: UiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (Uri) -> Unit,
    onAttachAudio: (Uri) -> Unit,
    onAttachDocument: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onDismissAttachError: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 파일 선택기: 이미지는 사진 선택기(PickVisualMedia), 오디오/문서는 GetContent(MIME 지정).
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onAttachImage) }
    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAttachAudio) }
    val pickDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAttachDocument) }

    // 맨 아래 따라가기. 대화 진입·생성 중에는 맨 아래를 유지하되, 사용자가 직접 스크롤하면 그쪽을 우선한다.
    var followBottom by remember { mutableStateOf(true) }

    // 사용자가 끌기 시작하면 따라가기를 멈춘다. 이때 진행 중인 자동 스크롤은 사용자 입력 우선순위에 밀려 취소된다.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) followBottom = false
        }
    }
    // 사용자가 스스로 맨 아래까지 내려오면 따라가기를 재개한다.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) -> if (!scrolling && !canScrollForward) followBottom = true }
    }
    // 방 진입, 생성 시작·완료 시에는 사용자가 위로 올려 두었더라도 맨 아래로 돌아간다.
    LaunchedEffect(state.activeRoomId, state.isGenerating) { followBottom = true }

    // 마지막 말풍선(본문·생각·통계)이 자랄 때마다 맨 아래로. animateScrollToItem(index)는 항목의 "윗변"을
    // 화면 위에 맞춰 긴 답변의 첫 몇 줄만 보였다. 오프셋을 최대로 주면 목록 끝(마지막 항목의 아랫변)에서 멈춘다.
    val last = state.messages.lastOrNull()
    LaunchedEffect(state.messages.size, last, followBottom) {
        if (followBottom && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "메시지를 입력해 대화를 시작하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { msg -> MessageBubble(msg) }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 첨부 오류 배너
        state.attachError?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissAttachError) { Text("닫기") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 첨부 칩 목록
        if (state.attachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.attachments) { att ->
                    AttachmentChip(att, enabled = !state.isGenerating) { onRemoveAttachment(att.id) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 첨부 버튼(모델 능력에 따라 노출). 문서는 모든 모델에서 동작.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.visionActive) {
                OutlinedButton(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !state.isGenerating,
                ) { Text("🖼️ 이미지") }
            }
            if (state.audioActive) {
                OutlinedButton(
                    onClick = { pickAudio.launch("audio/*") },
                    enabled = !state.isGenerating,
                ) { Text("🎵 오디오") }
            }
            OutlinedButton(
                onClick = { pickDocument.launch("text/*") },
                enabled = !state.isGenerating,
            ) { Text("📄 문서") }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("메시지를 입력…") },
                enabled = !state.isGenerating,
            )
            if (state.isGenerating) {
                OutlinedButton(onClick = onStop, modifier = Modifier.padding(start = 8.dp)) { Text("중지") }
            } else {
                Button(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    // 텍스트가 없어도 첨부가 있으면 전송 가능.
                    enabled = input.isNotBlank() || state.attachments.isNotEmpty(),
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("전송") }
            }
        }
    }
}

/** 전송 대기 중 첨부 1개를 나타내는 칩. 이미지 첨부는 축소 미리보기를 함께 보여준다. */
@Composable
private fun AttachmentChip(att: AttachmentUi, enabled: Boolean, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val preview = att.preview
            if (att.kind == AttachmentKind.IMAGE && preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)),
                )
            } else {
                Text(
                    when (att.kind) {
                        AttachmentKind.IMAGE -> "🖼️"
                        AttachmentKind.AUDIO -> "🎵"
                        AttachmentKind.DOCUMENT -> "📄"
                    },
                )
            }
            Text(
                att.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 140.dp),
            )
            TextButton(
                onClick = onRemove,
                enabled = enabled,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) { Text("✕") }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                if (msg.thought.isNotEmpty()) {
                    // 추론(thinking) 과정은 답변과 구분되도록 흐리게 표시
                    Text(
                        text = "💭 ${msg.thought.trim()}",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    text = msg.text.ifEmpty { if (msg.status == MessageStatus.INTERRUPTED) "(응답 없음)" else "…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (msg.status == MessageStatus.INTERRUPTED) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "⏹ 중단됨",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                msg.stats?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}
