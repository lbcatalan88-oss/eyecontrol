package com.example.eyecontrol

/** Conservative classifier: interrupted or mixed closures never activate an action. */
class EyeClosureDetector {
    enum class Event { NONE, NATURAL, DELIBERATE, LEFT_WINK, RIGHT_WINK }
    private var mask = 0
    private var started = 0L
    private var last = -1L
    private var mixed = false
    fun reset() { mask = 0; last = -1L; mixed = false }

    fun update(left: Boolean, right: Boolean, now: Long): Event {
        if (last >= 0 && (now <= last || now - last > 250)) reset()
        last = now
        val current = (if (left) 1 else 0) or (if (right) 2 else 0)
        if (current != 0) {
            if (mask == 0) { mask = current; started = now; mixed = false }
            else if (current != mask) mixed = true
            return Event.NONE
        }
        if (mask == 0) return Event.NONE
        val duration = now - started
        val previous = mask
        mask = 0
        if (mixed || duration > 1500) return Event.NONE
        if (duration < 500) return Event.NATURAL
        return when (previous) {
            3 -> Event.DELIBERATE
            1 -> Event.LEFT_WINK
            else -> Event.RIGHT_WINK
        }
    }
}
