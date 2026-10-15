package com.isroot.lmbridgesample

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.isroot.lmbridgesample.data.ChatDatabase
import com.isroot.lmbridgesample.data.ChatStore
import com.isroot.lmbridgesample.data.MessageStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [ChatStore] — 인메모리 Room DB로 저장/복원 규칙을 검증한다(모델 불필요). */
@RunWith(AndroidJUnit4::class)
class ChatStoreTest {

    private lateinit var db: ChatDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ChatDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun streamingUpdates_areAppliedInOrder_andLoadedByPosition(): Unit = runBlocking {
        val store = ChatStore(db.chatDao())
        store.createConversation("c1", "model-a", "새 채팅 1")
        val user = ChatMessage(Role.USER, "hi")
        var reply = ChatMessage(Role.ASSISTANT, "", status = MessageStatus.STREAMING)
        store.saveMessage("c1", 0, user)
        store.saveMessage("c1", 1, reply)
        // 스트리밍 갱신을 연달아 넣어도 마지막 값이 남아야 한다(큐 직렬화).
        for (i in 1..50) {
            reply = reply.copy(text = "token".repeat(i))
            store.saveMessage("c1", 1, reply)
        }
        store.saveMessage("c1", 1, reply.copy(status = MessageStatus.COMPLETE, stats = "1 tok/s"))
        store.rename("c1", "인사")

        val loaded = store.load("model-a").single()
        assertEquals("인사", loaded.conversation.title)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), loaded.messages.map { it.role })
        assertEquals("token".repeat(50), loaded.messages[1].text)
        assertEquals(MessageStatus.COMPLETE, loaded.messages[1].status)
        assertEquals("1 tok/s", loaded.messages[1].stats)
        assertEquals(reply.createdAt, loaded.messages[1].createdAt) // 갱신해도 생성 시각 유지
        assertTrue(store.load("model-b").isEmpty())
    }

    @Test
    fun deleteConversation_cascadesMessages(): Unit = runBlocking {
        val store = ChatStore(db.chatDao())
        store.createConversation("c1", "m", "a")
        store.createConversation("c2", "m", "b")
        store.saveMessage("c1", 0, ChatMessage(Role.USER, "x"))
        store.saveMessage("c2", 0, ChatMessage(Role.USER, "y"))
        store.deleteConversation("c1")

        val loaded = store.load("m")
        assertEquals(listOf("c2"), loaded.map { it.conversation.id })
        assertTrue(db.chatDao().messages("c1").isEmpty())
    }

    @Test
    fun newStore_marksLeftoverStreamingAsInterrupted(): Unit = runBlocking {
        val first = ChatStore(db.chatDao())
        first.createConversation("c1", "m", "a")
        first.saveMessage("c1", 0, ChatMessage(Role.ASSISTANT, "부분 응답", status = MessageStatus.STREAMING))
        first.flush()

        // 앱 재시작 = 새 ChatStore 생성.
        val restarted = ChatStore(db.chatDao())
        val msg = restarted.load("m").single().messages.single()
        assertEquals(MessageStatus.INTERRUPTED, msg.status)
        assertEquals("부분 응답", msg.text)
    }
}
