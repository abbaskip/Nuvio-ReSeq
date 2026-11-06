package com.nuvio.tv.updater

import androidx.annotation.StringRes
import com.nuvio.tv.R
import java.util.concurrent.TimeUnit

enum class UpdateCheckFrequency(@StringRes val label: Int, private val days: Int) {
    ALWAYS(R.string.about_update_check_always, 0),
    DAILY(R.string.about_update_check_daily, 1),
    TWO_DAYS(R.string.about_update_check_two_days, 2),
    THREE_DAYS(R.string.about_update_check_three_days, 3),
    WEEKLY(R.string.about_update_check_weekly, 7),
    NEVER(R.string.about_update_check_never, 0);

    val backgroundIntervalMs: Long?
        get() = when (this) {
            NEVER -> null
            ALWAYS -> TimeUnit.HOURS.toMillis(12)
            else -> TimeUnit.DAYS.toMillis(days.toLong())
        }

    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean = when (this) {
        ALWAYS -> true
        NEVER -> false
        else -> lastCheckMs <= 0 || lastCheckMs > nowMs || nowMs - lastCheckMs >= TimeUnit.DAYS.toMillis(days.toLong())
    }
}
