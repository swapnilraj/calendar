package dev.swapnilraj.calendar.ui.events

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.swapnilraj.calendar.R
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun SeriesTemporalControls(controller: SeriesEventEditorController) {
    val fields = controller.state.fields.temporal ?: return
    val context = controller.editingContext ?: return
    val timed = context.start is RecurrenceStart.Timed
    fun change(transform: (SeriesTemporalFields) -> SeriesTemporalFields) = controller.change { it.copy(temporal = transform(fields)) }
    Text(stringResource(if (controller.launch.scope == RecurrenceScope.FOLLOWING) R.string.series_tail_time_heading else R.string.series_master_time_heading), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(if (timed) R.string.series_kind_timed_fixed else R.string.series_kind_allday_fixed))
    OutlinedTextField(fields.startDate, { value -> change { it.copy(startDate = value) } }, enabled = controller.canChangeTime,
        label = { Text(stringResource(R.string.events_editor_start_date)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    if (timed) {
        OutlinedTextField(fields.startTime, { value -> change { it.copy(startTime = value) } }, enabled = controller.canChangeTime,
            label = { Text(stringResource(R.string.events_editor_start_time)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(fields.zone, { value -> change { it.copy(zone = value) } }, enabled = controller.canChangeZone,
            label = { Text(stringResource(R.string.events_editor_zone)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        val offsets = runCatching { ZoneId.of(fields.zone).rules.getValidOffsets(LocalDate.parse(fields.startDate).atTime(LocalTime.parse(fields.startTime))) }.getOrNull()
        if (offsets != null && offsets.size > 1) {
            Text(stringResource(R.string.series_choose_fold))
            offsets.forEach { offset -> FilterChip(selected = fields.offset == offset.id, enabled = controller.canChangeTime,
                onClick = { change { it.copy(offset = offset.id) } }, label = { Text(offset.id) }) }
        }
        if (offsets?.isEmpty() == true) Text(stringResource(R.string.events_editor_nonexistent), color = MaterialTheme.colorScheme.error)
    }
    Text(stringResource(R.string.series_duration_heading))
    FilterChip(selected = fields.durationMode == "KEEP", enabled = controller.canChangeTime,
        onClick = { change { it.copy(durationMode = "KEEP") } }, label = { Text(stringResource(R.string.series_keep_duration)) })
    FilterChip(selected = fields.durationMode == if (timed) "ELAPSED" else "DAYS", enabled = controller.canChangeTime,
        onClick = { change { it.copy(durationMode = if (timed) "ELAPSED" else "DAYS") } },
        label = { Text(stringResource(if (timed) R.string.series_set_elapsed else R.string.series_set_days)) })
    if (fields.durationMode == "ELAPSED" && timed) {
        SeriesDurationInput(fields.hours, R.string.series_duration_hours, controller.canChangeTime) { value -> change { it.copy(hours = value) } }
        SeriesDurationInput(fields.minutes, R.string.series_duration_minutes, controller.canChangeTime) { value -> change { it.copy(minutes = value) } }
        SeriesDurationInput(fields.seconds, R.string.series_duration_seconds, controller.canChangeTime) { value -> change { it.copy(seconds = value) } }
    } else if (fields.durationMode == "DAYS" && !timed) SeriesDurationInput(fields.days, R.string.series_duration_days, controller.canChangeTime) { value -> change { it.copy(days = value) } }
    val resolved = runCatching {
        val (anchor, duration) = seriesTemporalIntent(context, fields, SeriesScopeCapabilities(fields = controller.fieldsAllowed))
        val start = (anchor as? FieldChange.Set)?.value ?: context.start
        val raw = when (duration) { RecurrenceDurationChange.KeepRaw -> context.durationRaw
            is RecurrenceDurationChange.TimedElapsed -> "PT${duration.duration.seconds}S"; is RecurrenceDurationChange.AllDayDays -> "P${duration.days}D" }
        (RecurrenceMasterTemplates.resolve(start, raw) as? RecurrenceMasterTemplateResult.Ready)?.template
    }.getOrNull()
    resolved?.let { SeriesLogicalInterval(it, if (controller.launch.scope == RecurrenceScope.FOLLOWING) R.string.series_after_tail else R.string.series_resulting_master_interval) }
}

@Composable
private fun SeriesDurationInput(value: String, label: Int, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, enabled = enabled, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun SeriesLogicalInterval(template: RecurrenceMasterTemplate, label: Int) {
    val locale = LocalLocale.current.platformLocale
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
    Text(when (val time = template.time) { is EventTime.AllDay -> allDayDateText(time, locale)
        is EventTime.Timed -> timedDateText(time, template.eventZone, locale, true) })
    if (template.time is EventTime.Timed) Text(stringResource(R.string.recurrence_editor_zone_value,
        android.text.BidiFormatter.getInstance(locale).unicodeWrap(if (template.eventZone.normalized() == ZoneOffset.UTC) "UTC" else template.eventZone.id)))
}

@Composable
internal fun SeriesExistingLimit(context: SeriesMasterContext) {
    val locale = LocalLocale.current.platformLocale
    val bidi = android.text.BidiFormatter.getInstance(locale)
    val end = context.parsed?.end
    val text = when (end) {
        RecurrenceEnd.Never -> stringResource(R.string.recurrence_editor_no_end)
        is RecurrenceEnd.Count -> stringResource(R.string.recurrence_editor_end_count, end.count)
        is RecurrenceEnd.ThroughDate -> stringResource(R.string.recurrence_editor_end_date, bidi.unicodeWrap(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(end.date)))
        is RecurrenceEnd.ExactUntil -> stringResource(R.string.series_exact_until, bidi.unicodeWrap(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
            .withZone((context.start as? RecurrenceStart.Timed)?.zone ?: ZoneOffset.UTC).format(end.instant)))
        null -> stringResource(R.string.recurrence_custom)
    }
    Text(stringResource(R.string.series_existing_limit, text))
}
