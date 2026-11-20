package dev.swapnilraj.calendar

import android.Manifest
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import dev.swapnilraj.calendar.data.calendar.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarSeriesGraphDurationTest {
    /** Existing real SQLite fixture is unchanged. This private wrapper adds the reviewed native
     * dirty/mutator/lastDate effects and transaction/ACK controls, not codec-only fake outcomes. */
    private class Provider(val caller: String) : ContentProvider() {
        val sql = CalendarRecurringCreateTest.Provider().apply { allowUpdates = true; expandInstances = true; cacheExpandedTemporalRows = true }
        val db get() = sql.db
        var batches = 0
        var beforeBatch: (() -> Unit)? = null
        var afterCommit: (() -> Unit)? = null
        var ack: String? = null
        var wrongManagedEffect = false
        init {
            db.execSQL("ALTER TABLE calendars ADD COLUMN canPartiallyUpdate INTEGER DEFAULT 0")
            for (definition in listOf("dirty INTEGER DEFAULT 0", "mutators TEXT", "lastDate INTEGER", "lastSynced INTEGER DEFAULT 0", "opaque_oem TEXT DEFAULT 'retained'"))
                db.execSQL("ALTER TABLE events ADD COLUMN $definition")
        }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun query(uri: Uri, fields: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            if (uri.pathSegments.first() == "instances" && args?.firstOrNull() in setOf("21", "22")) {
                if (sql.delayedInstances) return MatrixCursor(fields!!)
                val projected = fields!!.map { when(it) { "event_id" -> "_id AS event_id"; "begin" -> "dtstart AS begin"; "end" -> "dtend AS end"; else -> it } }.toTypedArray()
                return db.query("events", projected,"_id = ? AND calendar_id = ? AND deleted = 0 AND eventStatus != 2",arrayOf(args!![0],args[1]),null,null,null)
            }
            return sql.query(uri,fields,selection,args,sort)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No native duration insertion is permitted")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("No native duration deletion is permitted")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            check(uri.pathSegments.first() == "events" && values!!.keySet() == setOf("duration", "dtstart"))
            val count = sql.update(uri,values,selection,args)
            db.update("events",ContentValues().apply { put("dirty",1);put("mutators",if(wrongManagedEffect)"unexpected.package" else caller);putNull("lastDate") },"_id = ?",arrayOf(uri.lastPathSegment!!))
            return count
        }
        override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
            batches++;beforeBatch?.also { beforeBatch = null }?.invoke();db.beginTransaction()
            val results = try { super.applyBatch(operations).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            sql.committed = true;afterCommit?.invoke()
            val index = operations.indexOfFirst { it.isUpdate }
            return when(ack) {
                "lost" -> error("Synthetic loss after real SQL commit")
                "short" -> results.dropLast(1).toTypedArray()
                "extra" -> results + ContentProviderResult(1)
                "count" -> results.apply { this[index] = ContentProviderResult(0) }
                "uri" -> results.apply { this[index] = ContentProviderResult(Uri.parse("content://com.android.calendar/events/10")) }
                "exception" -> results.apply { this[index] = ContentProviderResult(IllegalStateException("Synthetic native result")) }
                "extras" -> results.apply { this[index] = ContentProviderResult(Bundle()) }
                else -> results
            }
        }
        fun row(id: Long) = db.query("events",null,"_id = ?",arrayOf(id.toString()),null,null,null).use { c -> check(c.moveToFirst()); c.columnNames.associateWith { key ->
            val i = c.getColumnIndexOrThrow(key); if(c.isNull(i))null else c.getString(i)
        }.filterKeys { it != "native_duration_ms" } }
        fun rows(table: String) = db.query(table,null,null,null,null,null,"_id ASC").use { c -> buildList { while(c.moveToNext()) add(c.columnNames.associateWith { key -> val i=c.getColumnIndexOrThrow(key);if(c.isNull(i))null else c.getString(i) }) } }
        fun set(column: String, value: String?, id: Long = 10) = db.update("events",ContentValues().apply { if(value==null)putNull(column)else put(column,value) },"_id = ?",arrayOf(id.toString()))
    }
    private class Fixture(val allDay: Boolean, val moved: Boolean, val budgets: ProviderReadBudgets) {
        val context = RuntimeEnvironment.getApplication() as Context
        val provider = Provider(context.packageName)
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val name = "native-duration-${UUID.randomUUID()}.db"
        var store = CalendarLocalStore(context,name)
        val permissions = mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val reader = CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        var source = CalendarSeriesReader(context.contentResolver,reader,store,permissions::contains)
        var verified: SeriesGraphDurationNativeBinding? = SeriesGraphDurationNativeBinding("com.android.calendar","com.android.providers.calendar/.CalendarProvider2","com.android.providers.calendar",1,"a".repeat(64),35,context.packageName,context.applicationInfo.uid,1)
        var verifierReads = 0
        var onVerifier: ((Int) -> Unit)? = null
        val verifier = SeriesGraphDurationNativeVerifier { verifierReads++;onVerifier?.invoke(verifierReads);verified }
        fun adapter(default: Boolean = false) = CalendarSeriesGraphDurationAdapter(context,source,store,permissions::contains,
            if(default)SeriesGraphDurationNativeVerifier.Unverified else verifier,budgets=budgets)
        var writer = adapter()
        val seed = Instant.parse(if(allDay)"2026-11-17T00:00:00Z" else "2026-11-17T09:00:00Z")
        val span = if(allDay)86_400L else 3_600L
        val selected = if(moved)EventRef(CalendarId(2),21,seed.plusSeconds(4*86400),seed.plusSeconds(4*86400+span),RecurrenceRef(10,seed.plusSeconds(86400)))
            else EventRef(CalendarId(2),10,seed.plusSeconds(3*86400),seed.plusSeconds(3*86400+span),RecurrenceRef(10,seed.plusSeconds(3*86400)))
        init {
            ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,provider)
            fun event(id:Long,original:Instant?,actual:Instant,status:Int) = ContentValues().apply {
                put("_id",id);put("calendar_id",2);put("dtstart",actual.toEpochMilli());put("allDay",if(allDay)1 else 0)
                put("eventTimezone","UTC");putNull("eventEndTimezone");put("title","Synthetic fixture");put("eventLocation","Room");put("description","Notes")
                put("organizer","owner@example.invalid");put("isOrganizer",1);put("hasAttendeeData",0);put("guestsCanSeeGuests",1);put("eventStatus",status)
                put("_sync_id","source-$id");put("uid2445","uid-$id");put("dirty",0);put("mutators","previous.package");putNull("lastDate");put("lastSynced",0)
                if(original==null){putNull("dtend");put("duration",if(allDay)"P1D"else"PT3600S");put("rrule","FREQ=DAILY");put("exdate",if(allDay)"20261122,20261122"else"20261122T090000Z,20261122T090000Z");put("native_duration_ms",span*1000)}
                else{put("dtend",actual.plusSeconds(span).toEpochMilli());putNull("duration");put("original_id",10);put("original_sync_id","source-10");put("originalInstanceTime",original.toEpochMilli());put("originalAllDay",if(allDay)1 else 0)}
            }
            provider.db.insertOrThrow("events",null,event(10,null,seed,1))
            provider.db.insertOrThrow("events",null,event(21,seed.plusSeconds(86400),seed.plusSeconds(4*86400),1))
            provider.db.insertOrThrow("events",null,event(22,seed.plusSeconds(2*86400),seed.plusSeconds(2*86400),2))
            provider.db.execSQL("INSERT INTO reminders(_id,event_id,minutes,method) VALUES(31,21,27,2)")
            provider.db.execSQL("INSERT INTO extendedproperties(_id,event_id,name,value) VALUES(41,22,'opaque','retained')")
        }
        suspend fun command(): SeriesGraphDurationCommand {
            val read = writer.loadForEdit(selected)
            assertTrue("Eligibility: $read",read is SeriesGraphDurationReadResult.Ready)
            val access = (read as SeriesGraphDurationReadResult.Ready).access
            assertEquals(AccessDecision.ALLOWED,access.duration)
            return SeriesGraphDurationCommand(selected,access.series.token,if(allDay)SeriesGraphDuration.AllDayDays(2)else SeriesGraphDuration.TimedSeconds(5400))
        }
        fun reopen() { store.close();store=CalendarLocalStore(context,name);source=CalendarSeriesReader(context.contentResolver,reader,store,permissions::contains);writer=adapter() }
        suspend fun close() { scope.cancel();scope.coroutineContext[Job]!!.join();store.close();provider.db.close();context.deleteDatabase(name) }
    }
    private suspend fun fixture(allDay:Boolean=false,moved:Boolean=false,budgets:ProviderReadBudgets=ProviderReadBudgets(),block:suspend(Fixture)->Unit) {
        val f=Fixture(allDay,moved,budgets);try{block(f)}finally{f.close()}
    }
    private fun uncertain(result:SeriesMutationResult) = assertTrue(result.toString(),result is SeriesMutationResult.Common&&result.outcome is MutationResult.Uncertain)

    @Test fun cleanAndDirtySourcesSaveExactManagedEffectsAndFreshGeneratedReference() = runBlocking {
        for(dirty in listOf("0","1")) fixture { f ->
            f.provider.set("dirty",dirty)
            val command=f.command();val before=f.provider.row(10);val exceptions=listOf(f.provider.row(21),f.provider.row(22))
            val children=f.provider.rows("reminders") to f.provider.rows("extendedproperties")
            val outcome=f.writer.mutate("save",command) as SeriesMutationResult.LocalSaved
            assertEquals(command.selected.instanceStart,outcome.selected!!.instanceStart)
            assertEquals(command.selected.instanceStart.plusSeconds(5400),outcome.selected!!.instanceEnd)
            assertEquals(before+mapOf("duration" to "PT5400S", "dirty" to "1", "mutators" to f.context.packageName),f.provider.row(10))
            assertEquals(exceptions,listOf(f.provider.row(21),f.provider.row(22)));assertEquals(children,f.provider.rows("reminders") to f.provider.rows("extendedproperties"))
            assertEquals(setOf("duration","dtstart"),f.provider.sql.updatedEventValues.single().second.keySet())
            assertEquals(before["dtstart"],f.provider.sql.updatedEventValues.single().second.getAsString("dtstart"))
            assertEquals(1,f.provider.batches);assertEquals("VERIFIED",f.store.operation("save")!!.phase)
            assertFalse(f.reader.readDetails(command.selected) is CalendarReadState.Ready)
        }
    }
    @Test fun allDayDurationRetainsUtcOriginalsCancelledAndMovedActualBounds() = runBlocking { fixture(allDay=true) { f ->
        val command=f.command();val exceptions=listOf(f.provider.row(21),f.provider.row(22))
        val outcome=f.writer.mutate("all-day",command) as SeriesMutationResult.LocalSaved
        assertEquals(command.selected.instanceStart.plusSeconds(2*86400),outcome.selected!!.instanceEnd)
        assertEquals("P2D",f.provider.row(10)["duration"]);assertEquals(exceptions,listOf(f.provider.row(21),f.provider.row(22)))
        assertEquals("20261122,20261122",f.provider.row(10)["exdate"])
    } }
    @Test fun selectedMovedExceptionReturnsOnlyItsUnchangedActualReference() = runBlocking { fixture(moved=true) { f ->
        val command=f.command();val before=f.provider.row(21)
        assertEquals(f.selected,(f.writer.mutate("moved",command) as SeriesMutationResult.LocalSaved).selected)
        assertEquals(before,f.provider.row(21));assertEquals("2",f.provider.row(22)["eventStatus"])
    } }
    @Test fun unchangedDtstartGateIsNecessaryForTheSyntheticNativeCache() = runBlocking {
        fixture { f ->
            val command=f.command()
            f.provider.sql.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,10),ContentValues().apply{put("duration","PT5400S")},null,null)
            assertTrue(f.reader.readDetails(command.selected) is CalendarReadState.Ready)
            assertFalse(f.reader.readDetails(command.selected.copy(instanceEnd=command.selected.instanceStart.plusSeconds(5400))) is CalendarReadState.Ready)
        }
        fixture { f -> assertNotNull((f.writer.mutate("gated",f.command()) as SeriesMutationResult.LocalSaved).selected) }
    }
    @Test fun unverifiedOrChangingBindingNeverPreparesOrDispatches() = runBlocking {
        fixture { f -> assertTrue(f.adapter(default=true).loadForEdit(f.selected) is SeriesGraphDurationReadResult.NeedsReview);assertEquals(0,f.provider.batches) }
        fixture { f -> val command=f.command();f.verifierReads=0;f.onVerifier={n->if(n==2)f.verified=f.verified!!.copy(providerVersion=2)}
            assertTrue(f.writer.mutate("changed-binding",command) is SeriesMutationResult.PlanningReview)
            assertNull(f.store.operationHeader("changed-binding"));assertEquals(0,f.provider.batches)
        }
    }
    @Test fun finiteDatesPartialCopyAndUnknownManagedStateCannotIssueRights() = runBlocking {
        val cases=listOf("count","until","rdate","exrule","partial","dirty","lastDate","lastSynced")
        for(case in cases) fixture { f ->
            when(case){"count"->f.provider.set("rrule","FREQ=DAILY;COUNT=8");"until"->f.provider.set("rrule","FREQ=DAILY;UNTIL=20261201T090000Z");"rdate"->f.provider.set("rdate","20261129T090000Z");"exrule"->f.provider.set("exrule","FREQ=WEEKLY");"partial"->f.provider.sql.setCalendar("canPartiallyUpdate","1");"dirty"->f.provider.set("dirty",null);"lastDate"->f.provider.set("lastDate","1");else->f.provider.set("lastSynced","1")}
            assertFalse(case,f.writer.loadForEdit(f.selected) is SeriesGraphDurationReadResult.Ready)
            assertEquals(0,f.provider.batches);assertEquals(0,f.store.readableDatabase.rawQuery("SELECT count(*) FROM mutation_journal",null).use{it.moveToFirst();it.getInt(0)})
        }
    }
    @Test fun stalePrivateSourceConflictsBeforeJournalAndPhantomRaceNeverSaves() = runBlocking {
        fixture { f ->val command=f.command();f.provider.set("opaque_oem","changed",21)
            assertEquals(SeriesMutationResult.Common(MutationResult.Conflict(null)),f.writer.mutate("stale",command));assertNull(f.store.operationHeader("stale"));assertEquals(0,f.provider.batches)
        }
        fixture { f ->val command=f.command();f.provider.beforeBatch={f.provider.db.execSQL("INSERT INTO reminders(event_id,minutes,method) VALUES(21,90,1)")}
            uncertain(f.writer.mutate("race",command));assertEquals("PT3600S",f.provider.row(10)["duration"]);assertEquals("ATTEMPTED",f.store.operationHeader("race")!!.phase)
        }
    }
    @Test fun unexpectedManagedEffectRollsBackInsteadOfIgnoringIt() = runBlocking { fixture { f ->
        val command=f.command();val before=f.provider.row(10);f.provider.wrongManagedEffect=true
        uncertain(f.writer.mutate("wrong-effects",command));assertEquals(before,f.provider.row(10));assertEquals("ATTEMPTED",f.store.operationHeader("wrong-effects")!!.phase)
        uncertain(f.writer.mutate("wrong-effects",command));assertEquals(1,f.provider.batches)
    } }
    @Test fun lostOrMalformedAcknowledgementsNeverReplayAcrossRealJournalReopen() = runBlocking {
        for(ack in listOf("lost","short","extra","count","uri","exception","extras")) fixture { f ->
            val command=f.command();f.provider.ack=ack
            uncertain(f.writer.mutate("unknown",command));assertEquals("PT5400S",f.provider.row(10)["duration"]);assertEquals("ATTEMPTED",f.store.operationHeader("unknown")!!.phase)
            f.reopen();uncertain(f.writer.recover("unknown"));uncertain(f.writer.mutate("unknown",command));assertEquals(1,f.provider.batches)
        }
    }
    @Test fun committedDelayedExpansionRecoversReadOnlyAfterSourceTokenLoss() = runBlocking { fixture { f ->
        val command=f.command();f.provider.afterCommit={f.provider.sql.delayedInstances=true}
        uncertain(f.writer.mutate("delayed",command));assertEquals("COMMITTED",f.store.operationHeader("delayed")!!.phase)
        f.reopen();f.store.writableDatabase.delete("edit_snapshots",null,null);f.permissions.remove(Manifest.permission.WRITE_CALENDAR);f.provider.sql.delayedInstances=false
        val result=f.writer.recover("delayed") as SeriesMutationResult.LocalSaved
        assertEquals(command.selected.instanceStart.plusSeconds(5400),result.selected!!.instanceEnd);assertEquals("VERIFIED",f.store.operationHeader("delayed")!!.phase);assertEquals(1,f.provider.batches)
    } }
    @Test fun changedPostgraphAndChangedProviderCannotVerifyKnownCommit() = runBlocking {
        fixture { f ->val command=f.command();f.provider.afterCommit={f.provider.set("opaque_oem","changed",21)}
            uncertain(f.writer.mutate("postgraph",command));assertEquals("COMMITTED",f.store.operationHeader("postgraph")!!.phase);f.reopen();uncertain(f.writer.recover("postgraph"));assertEquals(1,f.provider.batches)
        }
        fixture { f ->val command=f.command();f.provider.afterCommit={f.verified=f.verified!!.copy(providerVersion=2)}
            uncertain(f.writer.mutate("profile",command));assertEquals("COMMITTED",f.store.operationHeader("profile")!!.phase);assertEquals(1,f.provider.batches)
        }
    }
    @Test fun exactPreparedWinnerCanRetryButForeignBindingWinnerNeverGetsReplaced() = runBlocking {
        for(foreign in listOf(false,true)) fixture { f ->
            val command=f.command();val source=f.source.checkedSource(command.selected,command.token).canonical()
            val binding=if(foreign)f.verified!!.copy(providerVersion=2)else f.verified!!
            val raw=SeriesGraphDurationNativeReceipt.create("winner",command,source,binding,f.budgets,RecurrencePlanningBudget()).encode()
            f.store.prepare("winner",SeriesGraphDurationNativeReceipt.fingerprint(command),SeriesGraphDurationNativeReceipt.FAMILY,raw)
            if(foreign){assertEquals(SeriesMutationResult.Common(MutationResult.Conflict(null)),f.writer.mutate("winner",command));assertEquals(raw,f.store.operation("winner")!!.resultRef);assertEquals(0,f.provider.batches)}
            else{assertEquals(SeriesMutationResult.Common(MutationResult.SafeToRetry("winner")),f.writer.recover("winner"));assertTrue(f.writer.mutate("winner",command) is SeriesMutationResult.LocalSaved);assertEquals(1,f.provider.batches)}
        }
    }
    @Test fun independentWritersShareDurableOneAttemptFence() = runBlocking { fixture { f ->
        val command=f.command();val second=f.adapter()
        val results=coroutineScope { listOf(async{f.writer.mutate("concurrent",command)},async{second.mutate("concurrent",command)}).awaitAll() }
        assertEquals(1,f.provider.batches);assertTrue(results.any{it is SeriesMutationResult.LocalSaved})
        assertTrue(f.writer.recover("concurrent") is SeriesMutationResult.LocalSaved)
    } }
    @Test fun smallEnvelopeAndAggregateOperationBudgetsRefuseBeforeNativeAttempt() = runBlocking {
        fixture { f ->val command=f.command();val tiny=f.budgets.copy(maxCanonicalBytes=128)
            val adapter=CalendarSeriesGraphDurationAdapter(f.context,f.source,f.store,f.permissions::contains,f.verifier,budgets=tiny)
            assertTrue(adapter.mutate("small",command) is SeriesMutationResult.PlanningReview);assertNull(f.store.operationHeader("small"));assertEquals(0,f.provider.batches)
        }
        fixture { f ->repeat(1100){f.provider.db.execSQL("INSERT INTO reminders(event_id,minutes,method) VALUES(10,?,2)",arrayOf(it))}
            assertFalse(f.writer.loadForEdit(f.selected) is SeriesGraphDurationReadResult.Ready);assertEquals(0,f.provider.batches)
        }
    }
    @Test fun structuredSemanticEvidenceAndNativeFramesRejectCrossFamilyOrTampering() = runBlocking { fixture { f ->
        val command=f.command();val source=f.source.checkedSource(command.selected,command.token).canonical();val proof=SeriesGraphDurationReceipt.create("codec",command,source).encode()
        val native=SeriesGraphDurationNativeReceipt.create("codec",command,source,f.verified!!,f.budgets,RecurrencePlanningBudget()).encode()
        val j=JSONObject(native);assertEquals(proof,j.getJSONObject("semanticPrepared").toString());assertFalse(j.has("semanticPreparedRaw"))
        fun refused(raw:String,kind:String=SeriesGraphDurationNativeReceipt.FAMILY){try{SeriesGraphDurationNativeReceipt.parse(CalendarLocalStore.Operation("codec",SeriesGraphDurationNativeReceipt.fingerprint(command),kind,"PREPARED",raw),f.budgets,RecurrencePlanningBudget());fail("Expected native proof refusal")}catch(_:Exception){}}
        refused(proof);refused(native,SeriesGraphDurationProtocol.FAMILY)
        refused(JSONObject(native).apply{getJSONObject("effectPlan").getJSONObject("request").put("dtstart","0")}.toString())
        refused(JSONObject(native).apply{getJSONObject("nativeBinding").put("callerPackageCount",2)}.toString())
        refused(JSONObject(native).apply{put("future",true)}.toString())
        assertEquals(0,f.provider.batches)
    } }

    @Test fun permissionRevocationAtFinalCapabilityVerifierNeverIssuesAllowed() = runBlocking {
        val observed=mutableListOf<String>()
        for(permission in listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) fixture { f ->
            f.verifierReads=0;f.onVerifier={n->if(n==2)f.permissions.remove(permission)}
            val result=f.writer.loadForEdit(f.selected)
            observed += "${permission.substringAfterLast('.')}:${result.javaClass.simpleName}:${f.provider.batches}"
        }
        println("M6 capability permission observations=$observed")
        assertEquals(listOf("READ_CALENDAR:PermissionRequired:0","WRITE_CALENDAR:PermissionRequired:0"),observed)
    }

    @Test fun permissionRevocationAtMutationVerifierBoundariesCannotPrepareAttemptOrDispatchLate() = runBlocking {
        val observed=mutableListOf<String>();val expected=mutableListOf<String>()
        for(gate in 1..4) for(permission in listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) fixture { f ->
            val command=f.command();f.verifierReads=0;f.onVerifier={n->if(n==gate)f.permissions.remove(permission)}
            val result=f.writer.mutate("permission",command)
            val outcome=if(result is SeriesMutationResult.Common)result.outcome.javaClass.simpleName else result.javaClass.simpleName
            val label="$gate:${permission.substringAfterLast('.')}"
            observed += "$label:$outcome:${f.store.operationHeader("permission")?.phase}:${f.provider.batches}"
            expected += "$label:${if(gate==4)"Uncertain"else"PermissionRequired"}:${when(gate){1,2->null;3->"PREPARED";else->"ATTEMPTED"}}:0"
        }
        println("M6 mutation permission observations=$observed")
        assertEquals(expected,observed)
    }

    @Test fun permissionRevocationDuringRecoveryCannotPublishReadOrRetryAuthority() = runBlocking {
        val observed=mutableListOf<String>()
        for(permission in listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) fixture { f ->
            val command=f.command();val source=f.source.checkedSource(command.selected,command.token).canonical()
            val raw=SeriesGraphDurationNativeReceipt.create("prepared-permission",command,source,f.verified!!,f.budgets,RecurrencePlanningBudget()).encode()
            f.store.prepare("prepared-permission",SeriesGraphDurationNativeReceipt.fingerprint(command),SeriesGraphDurationNativeReceipt.FAMILY,raw)
            f.provider.sql.afterQuery={path->if(path=="events")f.permissions.remove(permission)}
            val result=f.writer.recover("prepared-permission")
            val outcome=if(result is SeriesMutationResult.Common)result.outcome.javaClass.simpleName else result.javaClass.simpleName
            observed += "prepared:${permission.substringAfterLast('.')}:$outcome:${f.store.operationHeader("prepared-permission")!!.phase}:${f.provider.batches}"
        }
        for(permission in listOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)) fixture { f ->
            val command=f.command();f.provider.afterCommit={f.provider.sql.delayedInstances=true}
            uncertain(f.writer.mutate("committed-permission",command));assertEquals("COMMITTED",f.store.operationHeader("committed-permission")!!.phase)
            f.reopen();f.provider.sql.delayedInstances=false;f.verifierReads=0;f.onVerifier={n->if(n==2)f.permissions.remove(permission)}
            val result=f.writer.recover("committed-permission")
            val outcome=if(result is SeriesMutationResult.Common)result.outcome.javaClass.simpleName else result.javaClass.simpleName
            observed += "committed:${permission.substringAfterLast('.')}:$outcome:${f.store.operationHeader("committed-permission")!!.phase}:${f.provider.batches}"
        }
        println("M6 recovery permission observations=$observed")
        assertEquals(listOf("prepared:READ_CALENDAR:PermissionRequired:PREPARED:0","prepared:WRITE_CALENDAR:PermissionRequired:PREPARED:0",
            "committed:READ_CALENDAR:PermissionRequired:COMMITTED:1","committed:WRITE_CALENDAR:LocalSaved:VERIFIED:1"),observed)
    }
}
