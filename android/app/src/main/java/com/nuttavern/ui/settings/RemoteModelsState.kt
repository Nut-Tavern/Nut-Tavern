package com.nuttavern.ui.settings

/**
 * 远端模型列表的拉取状态。从 [AvailableModelsBottomSheet] 抽出来作为模块级类型,
 * 便于外层页面持有并复用 — 实现"打开抽屉不重拉"。
 */
internal sealed interface RemoteModelsState {
    data object Loading : RemoteModelsState
    data class Failed(val message: String) : RemoteModelsState
    data class Loaded(val modelIds: List<String>) : RemoteModelsState
}
