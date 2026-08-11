package com.isroot.lmbridgesample

import com.isroot.lmbridge.download.ModelDownloadManager
import com.isroot.lmbridge.models.ModelCatalog

/**
 * 샘플에서 고를 수 있는 모델 목록.
 *
 * 목록은 라이브러리 [ModelCatalog.ALL]을 그대로 읽어와(크기 오름차순) 노출한다. 즉
 * 라이브러리에 모델을 추가하면 샘플 선택 화면에도 자동으로 나타난다.
 *
 * @property gated HuggingFace 게이트(제한) 모델이면 true. 다운로드에 HF Access Token이 필요하다.
 */
data class SampleModel(
    val key: String,
    val label: String,
    val info: ModelDownloadManager.ModelInfo,
    val gated: Boolean,
    /** 이미지(비전) 입력 지원 여부. */
    val vision: Boolean,
    /** 오디오 입력 지원 여부. */
    val audio: Boolean,
) {
    val sizeLabel: String get() = "약 ${info.sizeInBytes / 1_000_000}MB"
    val pageUrl: String get() = "https://huggingface.co/${info.modelId}"

    /** 이미지 또는 오디오 중 하나라도 지원하면 멀티모달. */
    val multimodal: Boolean get() = vision || audio
}

object SampleModels {

    /**
     * 이미지(비전) 인코더를 가진 모델 id. 이 목록에 있는 모델만 이미지 입력을 활성화한다
     * (`setMaxNumImages`). 텍스트 전용 모델에 비전을 켜면 초기화가 실패한다.
     */
    private val VISION_MODEL_IDS = setOf(
        "litert-community/gemma-4-E2B-it-litert-lm",
        "litert-community/gemma-4-E4B-it-litert-lm",
        "google/gemma-3n-E2B-it-litert-lm",
        "google/gemma-3n-E4B-it-litert-lm",
        "litert-community/FastVLM-0.5B",
        "litert-community/MedGemma-1.5-4B-IT",
    )

    /**
     * 오디오 인코더를 가진 모델 id(Gemma 3n 계열). 이 목록에 있는 모델만 오디오 입력을
     * 활성화한다(`setAudioBackend`).
     */
    private val AUDIO_MODEL_IDS = setOf(
        "litert-community/gemma-4-E2B-it-litert-lm",
        "litert-community/gemma-4-E4B-it-litert-lm",
        "google/gemma-3n-E2B-it-litert-lm",
        "google/gemma-3n-E4B-it-litert-lm",
    )

    /** 라이브러리 카탈로그를 그대로 반영한 선택 목록. key는 modelId다. */
    val ALL: List<SampleModel> = ModelCatalog.ALL.map { info ->
        SampleModel(
            key = info.modelId,
            label = info.displayName,
            info = info,
            gated = info.gated,
            vision = info.modelId in VISION_MODEL_IDS,
            audio = info.modelId in AUDIO_MODEL_IDS,
        )
    }

    fun byKey(key: String): SampleModel? = ALL.firstOrNull { it.key == key }
}
