package com.isroot.lmbridgesample

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.isroot.lmbridge.LMBridge
import com.isroot.lmbridge.LMBridgeClient
import com.isroot.lmbridge.models.GenerationChunk
import com.isroot.lmbridge.models.ImageEncoding
import com.isroot.lmbridge.models.LMBridgeError
import com.isroot.lmbridge.models.MultimodalInput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 실엔진(gemma-4-E2B-it) 멀티모달 E2E 계측 테스트.
 *
 * 모델은 테스트 이전에 앱 외부 전용 디렉터리(getExternalFilesDir)로 미리 배치되며,
 * [LMBridgeClient.Builder.setModelPath]로 그 경로를 로드한다. (테스트 소스에 HF 토큰을
 * 하드코딩하지 않는다 — 다운로드는 테스트 밖에서 수행.)
 *
 * 검증 범위:
 *  - 텍스트 baseline 생성(Token + Done, 오류 없음)
 *  - 이미지+텍스트(바이트 소스 / 비트맵+COMPACT_JPEG 소스) 실추론이 Done까지 도달
 *  - 텍스트 문서 주입 정상 경로가 Done까지 도달
 *  - **오류 표면화**: 없는 문서/바이너리 문서/없는 이미지 파일 → GenerationChunk.Error(InvalidInput),
 *    Done에 도달하지 않음(조용한 삭제 금지 회귀 방지)
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MultimodalE2ETest {

    companion object {
        private const val TAG = "MME2E"
        private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        private val modelFile: File
            get() = File(ctx.getExternalFilesDir(null), "gemma-4-E2B-it.litertlm")

        private lateinit var client: LMBridgeClient

        @JvmStatic
        @BeforeClass
        fun setup() {
            assumeTrue(
                "model not present at ${modelFile.absolutePath} (push it before running)",
                modelFile.exists() && modelFile.length() > 0L,
            )
            val args = InstrumentationRegistry.getArguments()
            fun backendOf(name: String, default: String) = when (args.getString(name, default).uppercase()) {
                "GPU" -> LMBridge.Backend.GPU
                "NPU" -> LMBridge.Backend.NPU
                else -> LMBridge.Backend.CPU
            }
            val backend = backendOf("backend", "CPU")
            // 이미지 샘플러(OpenCL image sampler)가 없는 GPU에서 비전이 실패할 수 있어, 비전
            // 인코더는 기본적으로 CPU로 분리한다(-e visionBackend GPU 로 강제 지정 가능).
            val visionBackend = backendOf("visionBackend", "CPU")
            Log.i(
                TAG,
                "model=${modelFile.absolutePath} size=${modelFile.length()} " +
                    "backend=$backend visionBackend=$visionBackend",
            )
            client = LMBridgeClient.Builder(ctx)
                .setModelPath(modelFile.absolutePath)
                .setBackend(backend)
                .setMaxNumTokens(256)
                // 비전(이미지) 입력 활성화 — 없으면 native max_num_images:0 로 이미지 주입 시 크래시.
                .setMaxNumImages(1)
                .setVisionBackend(visionBackend)
                .build()
            runBlocking { client.initialize() }
            Log.i(TAG, "engine initialized")
        }

        @JvmStatic
        @AfterClass
        fun teardown() {
            if (Companion::client.isInitialized) client.release()
        }
    }

    private fun collect(input: MultimodalInput): List<GenerationChunk> =
        runBlocking { client.generate(input).toList() }

    private fun tokenText(chunks: List<GenerationChunk>): String =
        chunks.filterIsInstance<GenerationChunk.Token>().joinToString("") { it.text }

    private fun errors(chunks: List<GenerationChunk>) =
        chunks.filterIsInstance<GenerationChunk.Error>()

    private fun assertReachesDoneWithoutError(chunks: List<GenerationChunk>) {
        assertTrue("unexpected Error chunk(s): ${errors(chunks).map { it.error }}", errors(chunks).isEmpty())
        assertTrue("expected Done", chunks.any { it is GenerationChunk.Done })
    }

    private fun assertInvalidInput(chunks: List<GenerationChunk>) {
        val err = errors(chunks).firstOrNull()
        assertNotNull("expected an Error chunk, got: $chunks", err)
        assertTrue(
            "expected LMBridgeError.InvalidInput, got: ${err!!.error}",
            err.error is LMBridgeError.InvalidInput,
        )
        assertFalse("must NOT reach Done on invalid input", chunks.any { it is GenerationChunk.Done })
    }

    // ---- 정상 경로 ----

    @Test
    fun a_text_producesTokensAndDone() {
        val chunks = collect(
            MultimodalInput.text("In one short sentence, what is the capital of France?"),
        )
        val out = tokenText(chunks)
        Log.i(TAG, "TEXT_OUT=<$out>")
        assertReachesDoneWithoutError(chunks)
        assertTrue("expected non-empty text output", out.isNotBlank())
    }

    @Test
    fun b_imageBytesPlusText_reachesDone() {
        val png = solidPng(Color.RED, 96, 96)
        val chunks = collect(
            MultimodalInput.Builder()
                .imageBytes(png)
                .text("What is the dominant color of this image? Answer with a single word.")
                .build(),
        )
        Log.i(TAG, "IMAGE_BYTES_OUT=<${tokenText(chunks)}>")
        assertReachesDoneWithoutError(chunks)
    }

    @Test
    fun c_bitmapCompactJpegPlusText_reachesDone() {
        // 큰 비트맵 + COMPACT_JPEG 옵트인 → 다운스케일/JPEG 경로가 실엔진에서 동작.
        val bmp = solidBitmap(Color.rgb(20, 120, 220), 2000, 1500)
        val chunks = collect(
            MultimodalInput.Builder()
                .image(bmp, ImageEncoding.COMPACT_JPEG)
                .text("Describe this image in a few words.")
                .build(),
        )
        Log.i(TAG, "IMAGE_JPEG_OUT=<${tokenText(chunks)}>")
        assertReachesDoneWithoutError(chunks)
    }

    @Test
    fun d_validTextDocument_reachesDone() {
        val doc = File(ctx.cacheDir, "notes_${System.nanoTime()}.txt").apply {
            writeText("Project Zephyr ships on March 3rd. The lead engineer is Dana Kim.")
        }
        try {
            val chunks = collect(
                MultimodalInput.Builder()
                    .document(doc.absolutePath)
                    .text("Who is the lead engineer? Reply with just the name.")
                    .build(),
            )
            Log.i(TAG, "DOC_OUT=<${tokenText(chunks)}>")
            assertReachesDoneWithoutError(chunks)
        } finally {
            doc.delete()
        }
    }

    // ---- 오류 표면화(회귀 방지) ----

    @Test
    fun e_missingDocument_surfacesInvalidInput() {
        val missing = File(ctx.cacheDir, "nope_${System.nanoTime()}.txt").absolutePath
        val chunks = collect(
            MultimodalInput.Builder().document(missing).text("Summarize.").build(),
        )
        Log.i(TAG, "MISSING_DOC errors=${errors(chunks).map { it.error }}")
        assertInvalidInput(chunks)
    }

    @Test
    fun f_binaryDocument_surfacesInvalidInput() {
        val bin = File(ctx.cacheDir, "bin_${System.nanoTime()}.dat").apply {
            writeBytes(byteArrayOf(0x01, 0x02, 0x00, 0x03, 0x00, 0x7F, 0x10))
        }
        try {
            val chunks = collect(
                MultimodalInput.Builder().document(bin.absolutePath).text("Summarize.").build(),
            )
            assertInvalidInput(chunks)
        } finally {
            bin.delete()
        }
    }

    @Test
    fun g_missingImageFile_surfacesInvalidInput() {
        val missing = File(ctx.cacheDir, "nope_${System.nanoTime()}.jpg").absolutePath
        val chunks = collect(
            MultimodalInput.Builder().imageFile(missing).text("Describe.").build(),
        )
        assertInvalidInput(chunks)
    }

    // ---- helpers ----

    private fun solidBitmap(color: Int, w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun solidPng(color: Int, w: Int, h: Int): ByteArray =
        ByteArrayOutputStream().use { bos ->
            solidBitmap(color, w, h).compress(Bitmap.CompressFormat.PNG, 100, bos)
            bos.toByteArray()
        }
}
