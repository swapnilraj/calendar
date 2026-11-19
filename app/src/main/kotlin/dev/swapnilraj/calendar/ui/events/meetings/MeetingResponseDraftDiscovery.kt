package dev.swapnilraj.calendar.ui.events.meetings

import dev.swapnilraj.calendar.core.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Explicit Details action: reuse any exact occurrence draft, including a reviewed replacement.
 * Ambiguous or damaged response records are retained and block creating another operation.
 * Listing is discovery, not an atomic cross-process lock; each controller still requires its
 * exact generation/revision lease and the provider's operation journal before dispatch.
 */
suspend fun prepareOrReopenMeetingResponseDraft(source: EditableMeeting, store: AtomicDraftStore,
    clock: Clock = Clock.systemUTC()): MeetingResponsePreparation {
    val selected = source.details.occurrence.ref
    suspend fun discover(): MeetingResponsePreparation? {
        val records = try { store.list() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return MeetingResponsePreparation.Unavailable }
        val matches = mutableListOf<MeetingResponseLaunch>()
        for (record in records) {
            val feature = try { JSONObject(record.payload).opt("feature") } catch (_: Exception) { null }
            if (feature != "meeting-response" && record.version != MeetingResponseDraftCodec.VERSION) continue
            val document = try { MeetingResponseDraftCodec.decode(record) }
                catch (_: Exception) { return MeetingResponsePreparation.RetainedForReview }
            if (document.selected == selected) matches += document.launch()
        }
        return when (matches.size) {
            0 -> null
            1 -> MeetingResponsePreparation.Ready(matches.single())
            else -> MeetingResponsePreparation.RetainedForReview
        }
    }
    discover()?.let { return it }
    val id = UUID.nameUUIDFromBytes(("meeting-response-v1|" + selected.toString()).toByteArray(Charsets.UTF_8)).toString()
    val prepared = prepareMeetingResponseDraft(source, id, store, clock)
    if (prepared !is MeetingResponsePreparation.Ready) return prepared
    // Do not launch a second known record if a reviewed replacement appeared during preparation.
    return discover() ?: MeetingResponsePreparation.Unavailable
}
