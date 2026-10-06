package com.tracel.plugin.command.action.support

import com.tracel.plugin.services.TracelServices
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.entity.Player
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToLong
import org.bukkit.Sound as BukkitSound

/**
 * There's nothing we can do Easter egg.
 *
 * In a failed undo, for anyone who clicks it, the first eight bars of "Amour Plastique" plays.
 *
 * Picked out by ear at the piano and some notes from the Internet. I'm not a good arranger, but I like
 * the way it sounds. Never thought I'd be writing a piano roll for Minecraft plugin, but here we are.
 */
internal class NothingWeCanDo(private val services: TracelServices) {
    /** [word] made clickable. */
    fun on(word: Component): Component = word.clickEvent(
        ClickEvent.callback(
            { audience: Audience -> (audience as? Player)?.let(::play) },
            ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build(),
        ),
    )

    /** Who is still hearing it, and until when. One run at a time each, or the tune piles up on itself. */
    private val busyUntil = ConcurrentHashMap<UUID, Long>()

    /** From the click to the last note dying away. */
    private val lengthMillis by lazy { score.maxOf { it.tick } * MILLIS_PER_TICK + RING_OUT_MILLIS }

    private fun play(player: Player) {
        val now = System.currentTimeMillis()
        var started = false
        busyUntil.compute(player.uniqueId) { _, until ->
            if (until != null && until > now) until else (now + lengthMillis).also { started = true }
        }
        if (!started) return
        for (note in score) {
            player.scheduler.runDelayed(services.plugin, { player.playSound(note.sound()) }, null, note.tick)
        }
    }

    private enum class Instrument(val sound: BukkitSound, val lowest: String) {
        HARP(BukkitSound.BLOCK_NOTE_BLOCK_HARP, "F#3"), // Tune
        PLING(BukkitSound.BLOCK_NOTE_BLOCK_PLING, "F#3"), // Under the tune, quietly, for the synth shine
        GUITAR(BukkitSound.BLOCK_NOTE_BLOCK_GUITAR, "F#2"), // The left hand's chords
        BASS(BukkitSound.BLOCK_NOTE_BLOCK_BASS, "F#1"), // The left hand's low notes
    }

    private object Loudness {
        const val TUNE = 1.0f
        const val TUNE_SHINE = 0.4f
        const val ROLLED_CHORD = 0.7f // Arpeggio (4 bars)
        const val RIGHT_HAND_CHORD = 0.5f
        const val LEFT_HAND_CHORD = 0.6f
        const val BASS_LOW = 1.2f
        const val BASS_OCTAVE = 1.0f
    }

    // D, F#m, Bm, F#m, D, F#m, Bm, A

    private class Chord(
        val bass: String,
        val leftHand: String,
        val rolled: String = leftHand,
        val rightLow: String,
        val rightHigh: String,
    )

    private val d = Chord(bass = "D2", leftHand = "F#3 A3 D4", rightLow = "F#3 A3 D4", rightHigh = "A3 D4 F#4")
    private val fSharpMinor =
        Chord(bass = "F#2", leftHand = "A3 C#4 F#4", rightLow = "F#3 A3 C#4", rightHigh = "A3 C#4 F#4")
    private val bMinor = Chord(
        bass = "B1", leftHand = "D3 F#3 B3", rolled = "B2 D3 F#3 B3",
        rightLow = "F#3 B3 D4", rightHigh = "B3 D4 F#4",
    )
    private val a = Chord(bass = "A1", leftHand = "A3 C#4 E4", rightLow = "A3 C#4", rightHigh = "A3 C#4 F#4")

    private val score: List<Note> = buildList {
        val song = Song(this)

        // Intro
        song.rolled(bar = 1, d)
        song.tune(bar = 1, beat = 1.0, "D4")
        song.tune(bar = 1, beat = 3.5, "F#4")

        song.rolled(bar = 2, fSharpMinor)
        song.tune(bar = 2, beat = 1.0, "C#4")
        song.tune(bar = 2, beat = 3.5, "F#4")

        song.rolled(bar = 3, bMinor)
        song.tune(bar = 3, beat = 1.0, "D4")
        song.tune(bar = 3, beat = 3.5, "G4")

        song.rolled(bar = 4, fSharpMinor)
        song.tune(bar = 4, beat = 1.0, "F#4")
        song.tune(bar = 4, beat = 3.5, "G4")

        // The groove
        song.groove(bar = 5, d, low = listOf(1.0, 1.5, 2.0, 2.5), high = listOf(3.0, 3.5, 3.75))

        // F#m idea
        song.groove(bar = 6, fSharpMinor, low = listOf(1.0, 1.5, 2.0, 4.0), high = listOf(3.0, 3.5))
        song.groove(bar = 7, bMinor, low = listOf(1.0, 1.5, 2.0), high = listOf(3.0, 3.5, 3.75))
        song.groove(bar = 8, a, low = listOf(1.0, 1.5, 2.0, 2.5), high = listOf(3.0, 3.5, 3.75))
    }

    private class Song(private val into: MutableList<Note>) {
        fun tune(bar: Int, beat: Double, note: String) {
            into += Note(bar, beat, Instrument.HARP, note, Loudness.TUNE)
            into += Note(bar, beat, Instrument.PLING, note, Loudness.TUNE_SHINE)
        }

        fun rolled(bar: Int, chord: Chord) {
            into += Note(bar, 1.0, Instrument.BASS, chord.bass, Loudness.BASS_LOW)
            chord.rolled.split(' ').forEachIndexed { i, note ->
                into += Note(bar, 1.0 + i * SIXTEENTH, Instrument.GUITAR, note, Loudness.ROLLED_CHORD)
            }
        }

        fun groove(bar: Int, chord: Chord, low: List<Double>, high: List<Double>) {
            low.forEach { rightHand(bar, it, chord.rightLow) }
            high.forEach { rightHand(bar, it, chord.rightHigh) }
            leftHand(bar, chord)
        }

        private fun rightHand(bar: Int, beat: Double, notes: String) {
            val voices = notes.split(' ')
            tune(bar, beat, voices.last())
            voices.dropLast(1).forEach { into += Note(bar, beat, Instrument.HARP, it, Loudness.RIGHT_HAND_CHORD) }
        }

        private fun leftHand(bar: Int, chord: Chord) {
            for (half in 0..1) {
                val beat = 1.0 + half * 2
                into += Note(bar, beat, Instrument.BASS, chord.bass, Loudness.BASS_LOW)
                into += Note(bar, beat + 0.5, Instrument.BASS, octaveAbove(chord.bass), Loudness.BASS_OCTAVE)
                chord.leftHand.split(' ').forEach {
                    into += Note(bar, beat + 1.0, Instrument.GUITAR, it, Loudness.LEFT_HAND_CHORD)
                }
            }
        }
    }

    private class Note(
        bar: Int,
        beat: Double,
        private val instrument: Instrument,
        name: String,
        private val volume: Float
    ) {
        private val key = midi(name)

        // A quarter at 120 a minute is half a second
        val tick: Long = (((bar - 1) * BEATS_PER_BAR + (beat - 1)) * TICKS_PER_BEAT).roundToLong() + 1

        @Suppress("REMOVAL", "DEPRECATION")
        fun sound(): Sound {
            val step = key - midi(instrument.lowest)
            return Sound.sound(
                instrument.sound.key(),
                Sound.Source.RECORD,
                volume,
                2.0.pow((step - 12) / 12.0).toFloat()
            )
        }
    }

    private companion object {
        const val MILLIS_PER_TICK = 50L
        const val RING_OUT_MILLIS = 1000L
        const val BEATS_PER_BAR = 4
        const val TICKS_PER_BEAT = 10 // 120
        const val SIXTEENTH = 0.25

        private val SEMITONES = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        fun midi(name: String): Int {
            val sharp = if ('#' in name) 1 else 0
            val octave = name.last().digitToInt()
            return (octave + 1) * 12 + SEMITONES.getValue(name.first()) + sharp
        }

        fun octaveAbove(name: String): String = name.dropLast(1) + (name.last().digitToInt() + 1)
    }
}
