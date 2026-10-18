package dev.po4yka.chur.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The native cover stays up until Compose has rendered the neutral lock destination. */
object IosLauncherActions {
    private val _maskRequest = MutableStateFlow(0L)
    val maskRequest = _maskRequest.asStateFlow()
    private val _sceneReady = MutableStateFlow(false)
    val sceneReady = _sceneReady.asStateFlow()
    var onMaskRendered: (() -> Unit)? = null

    fun requestMask() { _maskRequest.value += 1 }

    /** UIKit activation and the incoming-share scan both precede private navigation. */
    fun setSceneReady(ready: Boolean) { _sceneReady.value = ready }
}
