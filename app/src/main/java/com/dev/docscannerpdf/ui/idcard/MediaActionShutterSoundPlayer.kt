package com.dev.docscannerpdf.ui.idcard

import android.media.MediaActionSound
import com.dev.docscannerpdf.domain.idscan.CameraShutterSoundPlayer

/**
 * Production [CameraShutterSoundPlayer]: Android's built-in [MediaActionSound] SHUTTER_CLICK —
 * no bundled audio file, no new dependency, no audio/microphone permission. The sound is loaded
 * eagerly at construction so the first capture doesn't race the lazy load. [play] swallows any
 * playback failure (a sound problem must never block or crash a capture), and [release] is
 * idempotent: the instance is nulled after the first release, so later calls are no-ops. Owned
 * by the guided capture screen's lifecycle and released when that screen leaves composition.
 */
class MediaActionShutterSoundPlayer : CameraShutterSoundPlayer {

    private var sound: MediaActionSound? = runCatching {
        MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
    }.getOrNull()

    override fun play() {
        runCatching { sound?.play(MediaActionSound.SHUTTER_CLICK) }
    }

    override fun release() {
        val current = sound ?: return
        sound = null
        runCatching { current.release() }
    }
}
