package com.wasimaster.wmkeyboard.ime.homeassistant

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.homeassistant.HaWord
import com.wasimaster.wmkeyboard.ime.R

/** The string resource for each state word, so tiles read in the user's language. */
internal object HaWordStrings {
    @StringRes
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    fun resource(w: HaWord): Int = when (w) {
        HaWord.ON -> R.string.ime_ha_w_on
        HaWord.OFF -> R.string.ime_ha_w_off
        HaWord.OPEN -> R.string.ime_ha_w_open
        HaWord.CLOSED -> R.string.ime_ha_w_closed
        HaWord.OPENING -> R.string.ime_ha_w_opening
        HaWord.CLOSING -> R.string.ime_ha_w_closing
        HaWord.STOPPED -> R.string.ime_ha_w_stopped
        HaWord.LOCKED -> R.string.ime_ha_w_locked
        HaWord.UNLOCKED -> R.string.ime_ha_w_unlocked
        HaWord.LOCKING -> R.string.ime_ha_w_locking
        HaWord.UNLOCKING -> R.string.ime_ha_w_unlocking
        HaWord.JAMMED -> R.string.ime_ha_w_jammed
        HaWord.PLAYING -> R.string.ime_ha_w_playing
        HaWord.PAUSED -> R.string.ime_ha_w_paused
        HaWord.IDLE -> R.string.ime_ha_w_idle
        HaWord.STANDBY -> R.string.ime_ha_w_standby
        HaWord.BUFFERING -> R.string.ime_ha_w_buffering
        HaWord.HEATING -> R.string.ime_ha_w_heating
        HaWord.COOLING -> R.string.ime_ha_w_cooling
        HaWord.DRYING -> R.string.ime_ha_w_drying
        HaWord.FAN -> R.string.ime_ha_w_fan
        HaWord.HEAT -> R.string.ime_ha_w_heat
        HaWord.COOL -> R.string.ime_ha_w_cool
        HaWord.HEAT_COOL -> R.string.ime_ha_w_heat_cool
        HaWord.AUTO -> R.string.ime_ha_w_auto
        HaWord.DRY -> R.string.ime_ha_w_dry
        HaWord.FAN_ONLY -> R.string.ime_ha_w_fan_only
        HaWord.ARMED_HOME -> R.string.ime_ha_w_armed_home
        HaWord.ARMED_AWAY -> R.string.ime_ha_w_armed_away
        HaWord.ARMED_NIGHT -> R.string.ime_ha_w_armed_night
        HaWord.ARMED_VACATION -> R.string.ime_ha_w_armed_vacation
        HaWord.ARMED_CUSTOM -> R.string.ime_ha_w_armed_custom
        HaWord.DISARMED -> R.string.ime_ha_w_disarmed
        HaWord.ARMING -> R.string.ime_ha_w_arming
        HaWord.DISARMING -> R.string.ime_ha_w_disarming
        HaWord.PENDING -> R.string.ime_ha_w_pending
        HaWord.TRIGGERED -> R.string.ime_ha_w_triggered
        HaWord.CLEANING -> R.string.ime_ha_w_cleaning
        HaWord.DOCKED -> R.string.ime_ha_w_docked
        HaWord.RETURNING -> R.string.ime_ha_w_returning
        HaWord.ERROR -> R.string.ime_ha_w_error
        HaWord.HOME -> R.string.ime_ha_w_home
        HaWord.AWAY -> R.string.ime_ha_w_away
        HaWord.UNAVAILABLE -> R.string.ime_ha_w_unavailable
        HaWord.UNKNOWN -> R.string.ime_ha_w_unknown
        HaWord.DETECTED -> R.string.ime_ha_w_detected
        HaWord.CLEAR -> R.string.ime_ha_w_clear
        HaWord.SCENE -> R.string.ime_ha_w_scene
        HaWord.SCRIPT -> R.string.ime_ha_w_script
        HaWord.RUNNING -> R.string.ime_ha_w_running
        HaWord.ECO -> R.string.ime_ha_w_eco
        HaWord.PERFORMANCE -> R.string.ime_ha_w_performance
        HaWord.GAS -> R.string.ime_ha_w_gas
        HaWord.ELECTRIC -> R.string.ime_ha_w_electric
        HaWord.HEAT_PUMP -> R.string.ime_ha_w_heat_pump
        HaWord.HIGH_DEMAND -> R.string.ime_ha_w_high_demand
        HaWord.OFF_MODE -> R.string.ime_ha_w_off
        HaWord.ACTIVE -> R.string.ime_ha_w_active
        HaWord.BUTTON -> R.string.ime_ha_w_button
        HaWord.MUTED -> R.string.ime_ha_w_muted
        HaWord.CLEAR_NIGHT -> R.string.ime_ha_w_clear_night
        HaWord.CLOUDY -> R.string.ime_ha_w_cloudy
        HaWord.FOG -> R.string.ime_ha_w_fog
        HaWord.HAIL -> R.string.ime_ha_w_hail
        HaWord.LIGHTNING -> R.string.ime_ha_w_lightning
        HaWord.LIGHTNING_RAINY -> R.string.ime_ha_w_lightning_rainy
        HaWord.PARTLY_CLOUDY -> R.string.ime_ha_w_partly_cloudy
        HaWord.POURING -> R.string.ime_ha_w_pouring
        HaWord.RAINY -> R.string.ime_ha_w_rainy
        HaWord.SNOWY -> R.string.ime_ha_w_snowy
        HaWord.SNOWY_RAINY -> R.string.ime_ha_w_snowy_rainy
        HaWord.SUNNY -> R.string.ime_ha_w_sunny
        HaWord.WINDY -> R.string.ime_ha_w_windy
        HaWord.EXCEPTIONAL -> R.string.ime_ha_w_exceptional
    }
}
