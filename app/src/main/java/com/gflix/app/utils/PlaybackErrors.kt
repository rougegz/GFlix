package com.gflix.app.utils

import androidx.media3.common.PlaybackException

/**
 * Playback-error classifier (Just Player TrueHD/DTS lesson: bad audio must
 * never kill video).
 */
object PlaybackErrors {

    /**
     * True when the failure comes from the audio pipeline (unsupported codec
     * like TrueHD/DTS/E-AC-3, audio sink error). Callers retry once with
     * `setTrackTypeDisabled(TRACK_TYPE_AUDIO, true)`.
     */
    fun isAudioCodecFailure(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        ) {
            return false
        }
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 12) {
            val name = cause.javaClass.name.lowercase()
            if ("audio" in name) return true
            cause = cause.cause
            depth++
        }
        return false
    }
}
