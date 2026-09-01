package com.convoyrama.convoyrun.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.convoyrama.convoyrun.R
import com.convoyrama.convoyrun.model.*
import com.convoyrama.convoyrun.ui.theme.*

@StringRes
fun getEventTypeNameRes(type: EventType): Int {
    return when (type) {
        EventType.Convoy -> R.string.type_convoy
        EventType.TruckShow -> R.string.type_truck_show
        EventType.Exploration -> R.string.type_exploration
        EventType.Competition -> R.string.type_competition
        EventType.Other -> R.string.type_other
    }
}

fun getEventTypeColor(type: EventType): Color {
    return when (type) {
        EventType.Convoy -> EventTypeConvoy
        EventType.TruckShow -> EventTypeTruckShow
        EventType.Exploration -> EventTypeExploration
        EventType.Competition -> EventTypeCompetition
        EventType.Other -> EventTypeOther
    }
}

fun getGameColor(game: Game): Color {
    return when (game) {
        Game.ATS -> GameATS
        Game.ETS2 -> GameETS2
        Game.Other -> TextSecondary
    }
}
