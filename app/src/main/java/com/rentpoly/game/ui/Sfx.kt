package com.rentpoly.game.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.rentpoly.game.R

/** Sounds and haptics. Everything short, everything pre-loaded, nothing blocking. */
class Sfx(context: Context) {

    enum class Sound { DICE, HOP, CASH, PAY, CARD, JAIL, BUY, BUILD, TURN, WIN, LOSE }

    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids: Map<Sound, Int> = mapOf(
        Sound.DICE to pool.load(context, R.raw.dice, 1),
        Sound.HOP to pool.load(context, R.raw.hop, 1),
        Sound.CASH to pool.load(context, R.raw.cash, 1),
        Sound.PAY to pool.load(context, R.raw.pay, 1),
        Sound.CARD to pool.load(context, R.raw.card, 1),
        Sound.JAIL to pool.load(context, R.raw.jail, 1),
        Sound.BUY to pool.load(context, R.raw.buy, 1),
        Sound.BUILD to pool.load(context, R.raw.build, 1),
        Sound.TURN to pool.load(context, R.raw.turn, 1),
        Sound.WIN to pool.load(context, R.raw.win, 1),
        Sound.LOSE to pool.load(context, R.raw.lose, 1),
    )

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var enabled = true

    fun play(sound: Sound, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled) return
        val id = ids[sound] ?: return
        pool.play(id, volume, volume, 1, 0, rate)
    }

    fun tap(ms: Long = 18) {
        if (!enabled) return
        val v = vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    fun release() = pool.release()
}
