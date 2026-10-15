package com.isroot.lmbridgesample

import android.app.Application
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ChatViewModel] 실엔진 E2E — LMBridge 0.2.6 적용분(히스토리 복원·처리 속도 통계) 검증.
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
        val firstRoom = vm.uiState.value.activeRoomId!!

        val first = sendAndWait(vm, "My name is Jiwoo. Please remember it.")
        Log.i(TAG, "turn1 text='${first.text}' stats=${first.stats}")
        assertNotNull("stats should be shown after Done", first.stats)
        assertTrue("stats should include tok/s (benchmark on)", first.stats!!.contains("tok/s"))

        // 다른 방으로 갔다가 돌아오면 네이티브 세션이 새로 열린다 → 히스토리 주입으로 문맥 복원돼야 함.
        onMain { vm.createRoom() }
        waitUntil(vm, "room create") { !it.preparing && it.activeRoomId != firstRoom }
        onMain { vm.selectRoom(firstRoom) }
        waitUntil(vm, "room switch back") { !it.preparing && it.activeRoomId == firstRoom }
        assertEquals("transcript kept", 2, vm.uiState.value.messages.size)

        val second = sendAndWait(vm, "What is my name? Answer with the name only.")
        Log.i(TAG, "turn2 text='${second.text}' stats=${second.stats}")
        assertTrue("model should recall name from restored history", second.text.contains("Jiwoo", ignoreCase = true))

        onMain { vm.changeModel() }
        Thread.sleep(3_000) // 백그라운드 release 완료 대기
    }

    private companion object {
        const val TAG = "VME2E"
    }
}
