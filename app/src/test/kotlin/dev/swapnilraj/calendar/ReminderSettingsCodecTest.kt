package dev.swapnilraj.calendar

import androidx.datastore.preferences.core.*
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.reminders.*
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderSettingsCodecTest {
    private val id = CalendarId(7)
    private val identity = ReminderCalendarIdentity("issued-source-fingerprint", "known-calendar-uid")
    private val pin = """{"id":7,"fingerprint":"issued-source-fingerprint","uid":"known-calendar-uid"}"""
    private fun document(pins: String = pin, defaults: String = "", enabled: String = "true", version: String = "1") =
        """{"version":$version,"enabled":$enabled,"pins":[$pins],"defaults":[$defaults]}"""
    private fun fallback(timed: String = "null", allDay: String = "null", method: String = "UNRESOLVED", calendar: String = "7") =
        """{"id":$calendar,"timedMinutes":$timed,"allDayMinutes":$allDay,"method":"$method"}"""
    private fun decoded(encoded: String): SavedReminderSettings = (ReminderSettingsCodec.decode(encoded) as ReminderSettingsRead.Ready).settings
    private fun unavailable(encoded: String) = assertTrue(encoded, ReminderSettingsCodec.decode(encoded) is ReminderSettingsRead.Unavailable)

    @Test fun absentOwnedValueMigratesToDisabledWithoutWritingOrImportingEditorAndDisplayDefaults() {
        val prefs = mutablePreferencesOf(booleanPreferencesKey("editor_enabled") to true,
            intPreferencesKey("default_timed_reminder") to 10, intPreferencesKey("default_all_day_reminder") to 900,
            stringSetPreferencesKey("selected_calendar_ids") to setOf("7", "8"))
        val before = prefs.asMap().toMap()
        val settings = (ReminderSettingsCodec.read(prefs) as ReminderSettingsRead.Ready).settings
        assertEquals(SavedReminderSettings(), settings)
        assertFalse(settings.toConfiguration(ZoneOffset.UTC).enabled)
        assertTrue(settings.toConfiguration(ZoneOffset.UTC).enabledCalendars.isEmpty())
        assertTrue(settings.explicitDefaults.isEmpty())
        assertEquals(before, prefs.asMap())
    }

    @Test fun roundTripSingleOwnedValuePreservesPinsExplicitPoliciesAndUnrelatedPreferences() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("theme") to "dark", intPreferencesKey("hour_height") to 64)
        val settings = SavedReminderSettings(true, mapOf(id to identity), mapOf(id to ReminderDefaults(10, 900, ReminderDefaultMethod.LOCAL_ALERT)))
        assertEquals(ReminderSettingsSave.Saved(settings), ReminderSettingsCodec.write(prefs, settings))
        assertEquals(ReminderSettingsRead.Ready(settings), ReminderSettingsCodec.read(prefs))
        assertEquals("dark", prefs[stringPreferencesKey("theme")])
        assertEquals(64, prefs[intPreferencesKey("hour_height")])
        assertEquals(3, prefs.asMap().size)
        assertTrue(prefs[ReminderSettingsCodec.key]!!.startsWith("{\"version\":1,"))
    }

    @Test fun nullableUidAndMaximumLongCalendarIdRemainExact() {
        val maximum = CalendarId(Long.MAX_VALUE)
        val settings = SavedReminderSettings(true, mapOf(maximum to ReminderCalendarIdentity("weak-issued-identity")))
        val prefs = mutablePreferencesOf()
        assertTrue(ReminderSettingsCodec.write(prefs, settings) is ReminderSettingsSave.Saved)
        assertEquals(settings, (ReminderSettingsCodec.read(prefs) as ReminderSettingsRead.Ready).settings)
        assertTrue(prefs[ReminderSettingsCodec.key]!!.contains("\"uid\":null"))
        assertEquals(ReminderCalendarIdentity("issued-source-fingerprint"),
            decoded(document(pins = pin.replace("\"known-calendar-uid\"", "null"))).expectedCalendarIdentities.getValue(id))
    }

    @Test fun savedResultAndConfigurationDetachFromMutableCallerMaps() {
        val pins = linkedMapOf(id to identity)
        val defaults = linkedMapOf(id to ReminderDefaults(20))
        val settings = SavedReminderSettings(true, pins, defaults)
        val configuration = settings.toConfiguration(ZoneOffset.UTC)
        val prefs = mutablePreferencesOf()
        val result = ReminderSettingsCodec.write(prefs, settings) as ReminderSettingsSave.Saved
        pins.clear(); defaults.clear()
        assertEquals(setOf(id), configuration.enabledCalendars)
        assertEquals(mapOf(id to identity), configuration.expectedCalendarIdentities)
        assertEquals(mapOf(id to ReminderDefaults(20)), result.settings.explicitDefaults)
        assertEquals(ReminderSettingsRead.Ready(result.settings), ReminderSettingsCodec.read(prefs))
    }

    @Test fun configurationDerivesCalendarIdsExactlyFromPinsAndUsesCurrentDeviceZone() {
        val settings = decoded(document())
        val zone = ZoneId.of("Pacific/Kiritimati")
        assertEquals(setOf(id), settings.toConfiguration(zone).enabledCalendars)
        assertEquals(zone, settings.toConfiguration(zone).deviceZone)
        assertEquals(mapOf(id to identity), settings.toConfiguration(zone).expectedCalendarIdentities)
        assertTrue(settings.toConfiguration(zone).defaults.isEmpty())
        assertEquals(setOf(id), settings.copy(enabled = false).toConfiguration(zone).enabledCalendars)
    }

    @Test fun existingRowsStayUnresolvedUnlessExplicitFallbackAndMethodAreSaved() {
        val defaults = decoded(document(defaults = fallback())).explicitDefaults.getValue(id)
        assertNull(defaults.timedMinutesBefore)
        assertNull(defaults.allDayMinutesBefore)
        assertEquals(ReminderDefaultMethod.UNRESOLVED, defaults.defaultMethod)
        val configured = decoded(document(defaults = fallback("10", "900", "NON_LOCAL")))
        assertEquals(ReminderDefaultMethod.NON_LOCAL, configured.explicitDefaults.getValue(id).defaultMethod)
    }

    @Test fun signedOffsetsRoundTripIncludingAfterStartButReservedMinusOneIsRejected() {
        for (offset in listOf(Int.MIN_VALUE, -900, -2, 0, 10, Int.MAX_VALUE)) {
            val prefs = mutablePreferencesOf()
            val settings = SavedReminderSettings(true, mapOf(id to identity), mapOf(id to ReminderDefaults(offset, offset)))
            assertTrue(ReminderSettingsCodec.write(prefs, settings) is ReminderSettingsSave.Saved)
            assertEquals(settings, (ReminderSettingsCodec.read(prefs) as ReminderSettingsRead.Ready).settings)
        }
        unavailable(document(defaults = fallback("-1")))
        unavailable(document(defaults = fallback(allDay = "-1")))
    }

    @Test fun malformedPresentValuesNeverBecomeDisabledAndReadNeverRepairsCorruption() {
        for (encoded in listOf("", "null", "[]", "false", "{", "{}", document() + "{}", document() + " trailing")) {
            val prefs = mutablePreferencesOf(ReminderSettingsCodec.key to encoded, stringPreferencesKey("theme") to "light")
            val before = prefs.asMap().toMap()
            assertTrue(ReminderSettingsCodec.read(prefs) is ReminderSettingsRead.Unavailable)
            assertEquals(before, prefs.asMap())
        }
        val future = mutablePreferencesOf(ReminderSettingsCodec.key to document(version = "2", enabled = "false"))
        assertEquals(ReminderSettingsRead.Unavailable(ReminderSettingsProblem.UNSUPPORTED_VERSION), ReminderSettingsCodec.read(future))
        assertEquals(document(version = "2", enabled = "false"), future[ReminderSettingsCodec.key])
    }

    @Test fun strictBooleanIntegerAndStringTypesRejectCoercionAndOutOfRangeValues() {
        for (enabled in listOf("\"false\"", "0", "null", "[]")) unavailable(document(enabled = enabled))
        for (raw in listOf("\"7\"", "7.0", "7e0", "9223372036854775808", "-1", "null", "true"))
            unavailable(document(pins = pin.replace("\"id\":7", "\"id\":$raw")))
        for (raw in listOf("\"10\"", "10.0", "1e1", "2147483648", "-2147483649", "true"))
            unavailable(document(defaults = fallback(timed = raw)))
        unavailable(document(pins = pin.replace("\"issued-source-fingerprint\"", "7")))
        unavailable(document(pins = pin.replace("\"known-calendar-uid\"", "false")))
    }

    @Test fun duplicateIdsDuplicateFieldsBlankIdentitiesUnknownFieldsAndMethodsAreUnavailable() {
        unavailable(document(pins = "$pin,$pin"))
        unavailable(document(defaults = "${fallback()},${fallback()}"))
        unavailable(document().replace("\"enabled\":true", "\"enabled\":false,\"enabled\":true"))
        unavailable(document(pins = pin.replace("\"id\":7", "\"id\":7,\"id\":7")))
        unavailable(document(pins = pin.replace("issued-source-fingerprint", "   ")))
        unavailable(document(pins = pin.replace("known-calendar-uid", "   ")))
        unavailable(document().replace("\"defaults\":[]", "\"defaults\":[],\"unknown\":true"))
        unavailable(document(defaults = fallback(method = "FUTURE_METHOD")))
        unavailable(document(defaults = fallback(calendar = "8")))
        unavailable(document(defaults = fallback()).replace(",\"uid\":\"known-calendar-uid\"", ""))
    }

    @Test fun wrongPreferenceTypeIsUnavailableWithoutMutatingIt() {
        val prefs = mutablePreferencesOf(intPreferencesKey(ReminderSettingsCodec.key.name) to 1)
        val before = prefs.asMap().toMap()
        assertTrue(ReminderSettingsCodec.read(prefs) is ReminderSettingsRead.Unavailable)
        assertEquals(before, prefs.asMap())
    }

    @Test fun invalidSaveLeavesPreviousSnapshotAndEveryOtherKeyExactlyUntouched() {
        val prefs = mutablePreferencesOf(ReminderSettingsCodec.key to document(), stringPreferencesKey("other") to "untouched")
        val before = prefs.asMap().toMap()
        val mismatched = SavedReminderSettings(true, mapOf(id to identity), mapOf(CalendarId(8) to ReminderDefaults(10)))
        assertTrue(ReminderSettingsCodec.write(prefs, mismatched) is ReminderSettingsSave.Invalid)
        assertEquals(before, prefs.asMap())
        val recursive = SavedReminderSettings(true, mapOf(id to identity), mapOf(id to ReminderDefaults(-1)))
        assertTrue(ReminderSettingsCodec.write(prefs, recursive) is ReminderSettingsSave.Invalid)
        assertEquals(before, prefs.asMap())
    }

    @Test fun strengthenOnlyMatchingExistingWeakPinWithoutAdoptionDowngradeOrMissingRemoval() {
        val weak = ReminderCalendarIdentity(identity.fingerprint)
        val otherId = CalendarId(8)
        val settings = SavedReminderSettings(true, mapOf(id to weak), mapOf(id to ReminderDefaults(30)))
        val enriched = settings.strengthen(mapOf(id to identity, otherId to ReminderCalendarIdentity("another-account")))
        assertEquals(setOf(id), enriched.strengthened)
        assertTrue(enriched.conflicts.isEmpty())
        assertEquals(mapOf(id to identity), enriched.settings.expectedCalendarIdentities)
        assertEquals(settings.explicitDefaults, enriched.settings.explicitDefaults)
        assertEquals(enriched.settings, enriched.settings.strengthen(mapOf(id to weak)).settings)
        assertEquals(enriched.settings, enriched.settings.strengthen(emptyMap()).settings)
        assertEquals(settings, settings.strengthen(mapOf(otherId to identity)).settings)
    }

    @Test fun namespaceAndKnownUidMismatchAreReportedWithoutChangingPinsOrFallbackPolicies() {
        val settings = SavedReminderSettings(true, mapOf(id to identity), mapOf(id to ReminderDefaults(20)))
        for (replacement in listOf(ReminderCalendarIdentity("replacement-account", identity.providerUidFingerprint),
            ReminderCalendarIdentity(identity.fingerprint, "replacement-calendar-uid"))) {
            val result = settings.strengthen(mapOf(id to replacement))
            assertEquals(setOf(id), result.conflicts)
            assertTrue(result.strengthened.isEmpty())
            assertEquals(settings, result.settings)
        }
    }

    @Test fun explicitSaveCanDisableWithoutErasingSelectionsAndEncodingIsCanonicalAcrossMapOrder() {
        val a = CalendarId(1); val b = CalendarId(2)
        val first = SavedReminderSettings(false, linkedMapOf(b to identity, a to ReminderCalendarIdentity("a")),
            linkedMapOf(b to ReminderDefaults(20), a to ReminderDefaults(30)))
        val second = first.copy(expectedCalendarIdentities = first.expectedCalendarIdentities.toSortedMap(compareBy { it.value }),
            explicitDefaults = first.explicitDefaults.toSortedMap(compareBy { it.value }))
        val one = mutablePreferencesOf(); val two = mutablePreferencesOf()
        ReminderSettingsCodec.write(one, first); ReminderSettingsCodec.write(two, second)
        assertEquals(one[ReminderSettingsCodec.key], two[ReminderSettingsCodec.key])
        assertEquals(setOf(a, b), (ReminderSettingsCodec.read(one) as ReminderSettingsRead.Ready).settings.toConfiguration(ZoneOffset.UTC).enabledCalendars)
    }
}
