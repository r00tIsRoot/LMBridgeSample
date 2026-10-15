package com.isroot.lmbridgesample.data

import android.content.Context
import android.util.Log
import com.isroot.lmbridgesample.ChatMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch

/** DB에서 읽은 채팅방 1개와 그 말풍선들. */
data class StoredConversation(
    val conversation: ConversationEntity,
    val messages: List<MessageEntity>,
)

/**
 * 대화 저장소. ViewModel은 메모리의 방/메시지를 화면 상태로 쓰고, 바뀔 때마다 여기로 기록한다.
 *
 * 설계 메모:
 *  - 모든 DB 작업을 **단일 큐**로 직렬화한다. 스트리밍 중 갱신이 잦아도 "생성 → 갱신 → 완료" 순서가
 *    뒤바뀌지 않고, 쓰기 메서드는 suspend가 아니어서 `finally`(취소 경로)에서도 호출할 수 있다.
 *  - 큐는 ViewModel이 아닌 프로세스 수명 스코프에서 돈다. 화면/ViewModel이 사라지는 순간의 마지막
 *    기록(중단 처리)까지 디스크에 남기기 위함이다.
 *  - 생성 시 이전 실행에서 `STREAMING`으로 남은 응답을 `INTERRUPTED`로 정리한다.
 *  - 첨부 미디어(이미지/오디오 바이트)는 저장하지 않는다. 사용자 말풍선의 첨부 요약 텍스트만 남는다.
 */
class ChatStore(
    private val dao: ChatDao,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val ops = Channel<suspend ChatDao.() -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (op in ops) {
                runCatching { dao.op() }.onFailure { Log.e(TAG, "persist failed", it) }
            }
        }
        enqueue { markStreamingInterrupted() }
    }

    private fun enqueue(op: suspend ChatDao.() -> Unit) {
        ops.trySend(op)
    }

    /** 앞서 넣은 쓰기가 모두 반영된 뒤 읽도록 조회도 같은 큐를 탄다. */
    private suspend fun <T> query(block: suspend ChatDao.() -> T): T {
        val result = CompletableDeferred<T>()
        enqueue { result.completeWith(runCatching { block() }) }
        return result.await()
    }

    /** 모델의 채팅방과 메시지 전체(생성 순). */
    suspend fun load(modelKey: String): List<StoredConversation> = query {
        conversations(modelKey).map { StoredConversation(it, messages(it.id)) }
    }

    fun createConversation(id: String, modelKey: String, title: String) {
        val now = clock()
        enqueue { insert(ConversationEntity(id, modelKey, title, createdAt = now, updatedAt = now)) }
    }

    fun rename(id: String, title: String) {
        val now = clock()
        enqueue { rename(id, title, now) }
    }

    fun deleteConversation(id: String) = enqueue { deleteConversation(id) }

    /** 말풍선을 삽입하거나 덮어쓴다(스트리밍 중 반복 호출 가능). */
    fun saveMessage(conversationId: String, position: Int, message: ChatMessage) {
        val now = clock()
        val entity = MessageEntity(
            id = message.id,
            conversationId = conversationId,
            position = position,
            role = message.role,
            text = message.text,
            thought = message.thought,
            stats = message.stats,
            status = message.status,
            createdAt = message.createdAt,
        )
        enqueue {
            upsert(entity)
            touch(conversationId, now)
        }
    }

    /** 지금까지 넣은 작업이 모두 끝날 때까지 기다린다(테스트용). */
    suspend fun flush() = query { }

    companion object {
        private const val TAG = "ChatStore"

        @Volatile
        private var instance: ChatStore? = null

        fun get(context: Context): ChatStore =
            instance ?: synchronized(this) {
                instance ?: ChatStore(ChatDatabase.get(context).chatDao()).also { instance = it }
            }
    }
}
