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
) {
    val sizeLabel: String get() = "약 ${info.sizeInBytes / 1_000_000}MB"
    val pageUrl: String get() = "https://huggingface.co/${info.modelId}"
}

object SampleModels {
    /** 라이브러리 카탈로그를 그대로 반영한 선택 목록. key는 modelId다. */
    val ALL: List<SampleModel> = ModelCatalog.ALL.map { info ->
        SampleModel(
            key = info.modelId,
            label = info.displayName,
            info = info,
            gated = info.gated,
        )
    }

    fun byKey(key: String): SampleModel? = ALL.firstOrNull { it.key == key }
}
