package dev.swapnilraj.calendar.android.widgets

/** Non-serializable form context for known app-owned fences, not a host-incarnation witness. */
class WidgetSetupContext internal constructor(
    internal val runtime: Any,
    val widgetId: Int,
    internal val version: Long,
)

sealed interface WidgetSetupForm {
    data class Ready(val context: WidgetSetupContext, val configuration: AgendaWidgetConfigurationState) : WidgetSetupForm
    data object Stale : WidgetSetupForm
    data object Unavailable : WidgetSetupForm
}

internal sealed interface WidgetSetupClaim {
    data class Claimed(val epoch: WidgetEpoch) : WidgetSetupClaim
    data object Stale : WidgetSetupClaim
    data object Unavailable : WidgetSetupClaim
}
