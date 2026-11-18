package dev.swapnilraj.calendar

import dev.swapnilraj.calendar.android.widgets.*
import dev.swapnilraj.calendar.core.CalendarId
import dev.swapnilraj.calendar.core.CalendarIdentity
import dev.swapnilraj.calendar.core.CalendarAccount
import dev.swapnilraj.calendar.data.widgets.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetConfigurationDocumentTest {
    private val epoch = WidgetEpoch(1, "confirmed-epoch")
    private fun document(selection: WidgetCalendarSelection = WidgetCalendarSelection.FollowApp) = WidgetConfigurationDocument("installation",
        mapOf(1 to WidgetConfigurationRow(epoch, AgendaWidgetConfiguration("configuration", selection, WidgetPrivacy.EVENT_TITLES)),
            2 to WidgetConfigurationRow(WidgetEpoch(2, "tombstone"), null)))
    private fun encoded() = WidgetConfigurationDocuments.encode(document())
    private fun invalid(text: String) = assertEquals(text, WidgetDocumentRead.Unavailable, WidgetConfigurationDocuments.decode(text, "installation"))
    @Test fun roundTripPinsTombstonesExplicitEmptyAndMaximumCalendarIdExactly() {
        for (selection in listOf(WidgetCalendarSelection.FollowApp, WidgetCalendarSelection.Explicit(emptySet()),
            WidgetCalendarSelection.Explicit(setOf(CalendarId(0), CalendarId(Long.MAX_VALUE))))) {
            val original = document(selection)
            assertEquals(WidgetDocumentRead.Ready(original), WidgetConfigurationDocuments.decode(WidgetConfigurationDocuments.encode(original), "installation"))
        }
    }
    @Test fun namespacePinsRoundTripStrictlyAndLegacyExplicitNeverInventsPins() {
        val id = CalendarId(7)
        val pin = CalendarIdentity(id, CalendarAccount("synthetic@example.invalid", "local"), "owner", "provider-uid")
        val configuration = AgendaWidgetConfiguration("generation", WidgetCalendarSelection.Explicit(setOf(id)),
            WidgetPrivacy.EVENT_TITLES, mapOf(id to pin))
        val document = WidgetConfigurationDocument("installation", mapOf(1 to WidgetConfigurationRow(epoch, configuration)))
        val encoded = WidgetConfigurationDocuments.encode(document)
        assertEquals(WidgetDocumentRead.Ready(document), WidgetConfigurationDocuments.decode(encoded, "installation"))
        invalid(encoded.replace("\"id\":7,\"accountName\"", "\"id\":\"7\",\"accountName\""))
        invalid(encoded.replace("\"providerId\":\"provider-uid\"", "\"providerId\":false"))
        invalid(encoded.replace("synthetic@example.invalid", ""))
        invalid(encoded.replace("\"accountType\":\"local\"", "\"accountType\":\"\""))
        val legacy = encoded.replace(Regex(",\"pins\":\\[.*?\\]"), "")
        val read = WidgetConfigurationDocuments.decode(legacy, "installation") as WidgetDocumentRead.Ready
        assertTrue(read.document.rows.getValue(1).configuration!!.expectedCalendarIdentities.isEmpty())
    }
    @Test fun absentIsEmptyButNotLifecycleProofAndWrongInstallationNeverAdopts() {
        assertEquals(WidgetDocumentRead.Ready(WidgetConfigurationDocument("installation")), WidgetConfigurationDocuments.decode(null, "installation"))
        assertEquals(WidgetDocumentRead.Unavailable, WidgetConfigurationDocuments.decode(encoded(), "replacement-installation"))
    }
    @Test fun malformedFutureUnknownDuplicateAndCoercedValuesAreUnavailable() {
        for (value in listOf("", "{}", "null", "[]", encoded() + "{}", encoded().replace("\"version\":1", "\"version\":2"),
            encoded().replace("\"version\":1", "\"version\":1.0"), encoded().replace("\"id\":1", "\"id\":\"1\""),
            encoded().replace("\"id\":1", "\"id\":0"), encoded().replace("\"id\":1", "\"id\":2147483648"),
            encoded().replace("\"id\":1", "\"id\":1,\"id\":1"), encoded().replace("confirmed-epoch", " "),
            encoded().replace("EVENT_TITLES", "FUTURE_PRIVACY"), encoded().replace("FOLLOW_APP", "UNKNOWN_SELECTION"),
            encoded().replace("\"version\":1", "\"version\":1,\"extra\":true"), encoded().replace("\"id\":2", "\"id\":1"))) invalid(value)
    }
    @Test fun explicitSelectionRejectsDuplicateNegativeFractionalOrStringIds() {
        val good = WidgetConfigurationDocuments.encode(document(WidgetCalendarSelection.Explicit(setOf(CalendarId(7)))))
        for (ids in listOf("[7,7]", "[-1]", "[7.0]", "[\"7\"]", "null")) invalid(good.replace("[7]", ids))
    }
}
