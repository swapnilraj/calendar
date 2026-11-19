package dev.swapnilraj.calendar

import android.content.Intent
import android.net.Uri
import dev.swapnilraj.calendar.android.navigation.CalendarIntentDecoder
import dev.swapnilraj.calendar.android.navigation.CalendarIntentRoute
import java.time.ZoneOffset
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarEntryActivationDecoderTest {
    private val decoder = CalendarIntentDecoder(ZoneOffset.UTC)
    @Test fun mimeOnlyInsertResolutionNeverAuthorizesForeignAuthorityOrScheme() {
        for (uri in listOf("content://evil.example/events", "content://com.android.calendar.evil/events",
            "https://com.android.calendar/events", "content://user@com.android.calendar/events")) {
            val intent = Intent(Intent.ACTION_INSERT).setDataAndType(Uri.parse(uri), "vnd.android.cursor.item/event")
            assertTrue(uri, decoder.decode(intent) is CalendarIntentRoute.Invalid)
        }
        assertTrue(decoder.decode(Intent(Intent.ACTION_INSERT).setType("vnd.android.cursor.item/event")) is CalendarIntentRoute.CreateDraftSeed)
    }
}
