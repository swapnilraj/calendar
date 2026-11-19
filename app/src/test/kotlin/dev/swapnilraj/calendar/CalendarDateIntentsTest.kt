package dev.swapnilraj.calendar

import android.content.Intent
import android.net.Uri
import dev.swapnilraj.calendar.ui.CalendarDateIntents
import dev.swapnilraj.calendar.ui.CalendarDateNavigation
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarDateIntentsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = "ce8510c5-17f1-4e86-9ce8-fc5d16dbe496"
    private fun valid(instant: Instant = Instant.EPOCH) =
        CalendarDateIntents.intent(context, CalendarDateNavigation(id, instant))
    private fun decodeUri(raw: String) = CalendarDateIntents.decode(context, valid().setData(Uri.parse(raw)))

    @Test fun authoredDatesRoundTripAcrossSupportedYearsAndNegativeEpochs() {
        listOf("0001-01-01T00:00:00Z", "1969-12-31T23:59:59.999Z", "1970-01-01T00:00:00Z",
            "9999-12-31T23:59:59.999Z").forEach { raw ->
            val value = CalendarDateNavigation(id, Instant.parse(raw))
            assertEquals(value, CalendarDateIntents.decode(context, valid(value.instant)))
        }
    }

    @Test fun duplicateSessionParametersCannotSelectOneOfSeveralNavigationIdentities() {
        val uri = requireNotNull(valid().data).toString()
        listOf(id, "9e1016f8-3ad3-4889-8317-c81173c1e8a1").forEach { other ->
            assertNull(decodeUri("$uri&session=$other"))
        }
        assertNull(decodeUri("$uri&%73ession=$id"))
    }

    @Test fun numericAndEncodedAliasesDoNotCreateAlternativeRepresentationsOfTheSameDate() {
        listOf("00", "+0", "%30", "0/", "0//").forEach { path ->
            assertNull("Noncanonical date path $path", decodeUri("daylight://calendar-date/$path?session=$id"))
        }
        assertNull(decodeUri("daylight://calendar-date/0?%73ession=$id"))
        assertNull(decodeUri("daylight://calendar-date/0?session=${id.replace("-", "%2D")}"))
    }

    @Test fun foreignDestinationsAndUnsupportedDatesRemainRejected() {
        assertNull(CalendarDateIntents.decode(context, valid().setAction(Intent.ACTION_VIEW)))
        assertNull(CalendarDateIntents.decode(context, valid().setClassName(context, "foreign.Activity")))
        listOf("daylight://other/0?session=$id", "daylight://calendar-date/0?session=$id&other=1",
            "daylight://calendar-date/0?session=$id#fragment", "daylight://calendar-date/253402300800000?session=$id",
            "daylight://calendar-date/0?session=invalid").forEach { raw -> assertNull(decodeUri(raw)) }
    }
}
