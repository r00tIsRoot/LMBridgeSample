package com.isroot.lmbridgesample

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.isroot.lmbridgesample.data.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ChatViewModel] 실엔진 E2E — 히스토리 복원·처리 속도 통계, 그리고 Room 저장 후 ViewModel을 새로
 * 만들어도(앱 재실행과 동일) 대화와 문맥이 복원되는지 검증한다.
 *
 * 모델은 앱이 이미 내려받아 둔 것을 사용한다(인자 `modelKey`, 기본 LFM2.5-1.2B-JP).
 * 받아둔 모델이 없으면 테스트를 건너뛴다.
 */
@RunWith(AndroidJUnit4::class)
class ChatViewModelE2ETest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val modelKey = InstrumentationRegistry.getArguments()
        .getString("modelKey", "litert-community/LFM2.5-1.2B-JP")

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(vm: ChatViewModel, label: String, timeoutMs: Long = 180_000, cond: (UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond(vm.uiState.value)) return
            Thread.sleep(200)
        }
        throw AssertionError("timeout waiting for $label: ${vm.uiState.value}")
    }

    private fun sendAndWait(vm: ChatViewModel, prompt: String): ChatMessage {
        onMain { vm.send(prompt) }
        waitUntil(vm, "generation start", 10_000) { it.isGenerating }
        waitUntil(vm, "generation end") { !it.isGenerating }
        // Done 처리(stats 조회) 후 말풍선이 갱신될 시간을 준다.
        waitUntil(vm, "stats", 10_000) { it.messages.lastOrNull()?.stats != null || it.messages.last().text.contains("⚠️") }
        return vm.uiState.value.messages.last()
    }

    @Test
    fun historyIsRestoredAcrossRoomSwitch_andStatsShown() {
        val vm = onMain { ChatViewModel(instrumentation.targetContext.applicationContext as Application) }
        val model = vm.uiState.value.models.firstOrNull { it.key == modelKey }
        assumeTrue("model $modelKey not downloaded", model?.downloaded == true)

        onMain { vm.selectModel(modelKey) }
        waitUntil(vm, "model load") { it.screen == Screen.Chat && !it.preparing && it.activeRoomId != null }
        // 이전 실행의 저장된 대화가 열릴 수 있으므로 빈 방에서 시작한다.
        val restoredRoom = vm.uiState.value.activeRoomId
        onMain { vm.createRoom() }
        waitUntil(vm, "fresh room") { !it.preparing && it.activeRoomId != restoredRoom }
        val firstRoom = vm.uiState.value.activeRoomId!!

        val first = sendAndWait(vm, "My name is Jiwoo. Please remember it.")
        Log.i(TAG, "turn1 text='${first.text}' stats=${first.stats}")
        assertNotNull("stats should be shown after Done", first.stats)
        assertTrue("stats should include tok/s (benchmark on)", first.stats!!.contains("tok/s"))

        // 다른 방으로 갔다가 돌아오면 네이티브 세션이 새로 열린다 → 히스토리 주입으로 문맥 복원돼야 함.
        onMain { vm.createRoom() }
        waitUntil(vm, "room create") { !it.preparing && it.activeRoomId != firstRoom }
        val emptyRoom = vm.uiState.value.activeRoomId!!
        onMain { vm.selectRoom(firstRoom) }
        waitUntil(vm, "room switch back") { !it.preparing && it.activeRoomId == firstRoom }
        assertEquals("transcript kept", 2, vm.uiState.value.messages.size)

        val second = sendAndWait(vm, "What is my name? Answer with the name only.")
        Log.i(TAG, "turn2 text='${second.text}' stats=${second.stats}")
        assertTrue("model should recall name from restored history", second.text.contains("Jiwoo", ignoreCase = true))
        val transcript = vm.uiState.value.messages

        onMain { vm.changeModel() }
        Thread.sleep(3_000) // 백그라운드 release 완료 대기

        // ── 앱 재실행 상황: 새 ViewModel은 메모리 캐시가 비어 있어 DB에서 방/메시지를 읽는다 ──
        val vm2 = onMain { ChatViewModel(instrumentation.targetContext.applicationContext as Application) }
        onMain { vm2.selectModel(modelKey) }
        waitUntil(vm2, "model reload") { it.screen == Screen.Chat && !it.preparing && it.activeRoomId != null }
        assertEquals("most recently used room reopens", firstRoom, vm2.uiState.value.activeRoomId)
        assertTrue("rooms restored", vm2.uiState.value.rooms.map { it.id }.containsAll(listOf(firstRoom, emptyRoom)))
        val restored = vm2.uiState.value.messages
        assertEquals("texts restored", transcript.map { it.role to it.text }, restored.map { it.role to it.text })
        assertTrue("all complete", restored.all { it.status == MessageStatus.COMPLETE })
        assertEquals("stats restored", transcript.last().stats, restored.last().stats)

        val third = sendAndWait(vm2, "Tell me my name again. Answer with the name only.")
        Log.i(TAG, "turn3(after restore) text='${third.text}'")
        assertTrue("model should recall name from DB history", third.text.contains("Jiwoo", ignoreCase = true))

        // 테스트가 만든 방 정리
        onMain { vm2.deleteRoom(emptyRoom) }
        waitUntil(vm2, "delete empty room") { !it.preparing && it.rooms.none { r -> r.id == emptyRoom } }
        onMain { vm2.deleteRoom(firstRoom) }
        waitUntil(vm2, "delete first room") { !it.preparing && it.rooms.none { r -> r.id == firstRoom } }
        onMain { vm2.changeModel() }
        Thread.sleep(3_000)
    }

    private companion object {
        const val TAG = "VME2E"
    }
}
