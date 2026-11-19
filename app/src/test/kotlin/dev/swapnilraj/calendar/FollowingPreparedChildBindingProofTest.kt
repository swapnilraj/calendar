package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.SyntheticCloneProviderPolicy
import dev.swapnilraj.calendar.core.*
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Controlled native-shaped SQL race. Private-cell diagnostics contain column names only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class FollowingPreparedChildBindingProofTest {
    private val start=Instant.parse("2028-01-03T09:00:00Z")
    private fun common(result:MutationResult)=SeriesMutationResult.Common(result)
    @Test fun preparedReminderReplacementCannotReadForeignPayloadOrDispatch()=race("reminders")
    @Test fun preparedAttendeeReplacementCannotReadForeignPayloadOrDispatch()=race("attendees")
    private fun race(table:String)=runBlocking { fixture { f->
        val prepared=f.prepare()
        f.provider.arm=table
        val result=f.family.mutate("change",f.selected,f.edit.token,f.command)
        assertTrue("Controlled race must execute after a minimal parent bind",f.provider.fired)
        assertEquals(common(MutationResult.Conflict(null)),result)
        assertEquals("No transaction may follow changed source ownership",0,f.provider.base.batchCount)
        assertEquals(prepared,f.store.operation("change"));assertEquals(1,f.provider.base.count("events"))
        assertTrue("Foreign child payload was read after replacement; query=${f.provider.raceQuery}, cells=${f.provider.foreignCells}",f.provider.foreignCells.isEmpty())
        assertEquals(common(MutationResult.Conflict(null)),f.family.mutate("change",f.selected,f.edit.token,f.command))
        assertEquals(prepared,f.store.operation("change"));assertEquals(0,f.provider.base.batchCount)
    }}
    @Test fun stablePreparedSourceStillRetriesWithoutPlanningAndCommitsOnce()=runBlocking { fixture { f->
        val prepared=f.prepare()
        val saved=f.family.mutate("change",f.selected,f.edit.token,f.command) as SeriesMutationResult.LocalSaved
        val before=FollowingExecutionReceipt.parse(prepared,1024*1024).second
        assertEquals(before.uid,f.provider.base.db.rawQuery("SELECT uid2445 FROM events WHERE _id=?",arrayOf(saved.identity.masterEventId.toString())).use{check(it.moveToFirst());it.getString(0)})
        assertEquals("VERIFIED",f.store.operation("change")!!.phase);assertEquals(1,f.provider.base.batchCount)
        assertEquals(saved,f.family.recover("change"));assertEquals(1,f.provider.base.batchCount)
        assertTrue(f.provider.foreignCells.isEmpty())
    }}
    private class Provider:ContentProvider() {
        val base=CalendarRecurringCreateTest.Provider().apply {
            nativeMeetingMode=true;expandInstances=true;allowUpdates=true
            db.execSQL("ALTER TABLE calendars ADD COLUMN allowedAttendeeTypes TEXT DEFAULT '0,1,2'")
        }
        var arm:String?=null;var fired=false;var minimalReads=0;var raceQuery:String?=null
        val foreignCells=mutableListOf<String>()
        override fun onCreate()=true
        override fun getType(uri:Uri):String?=null
        override fun query(uri:Uri,fields:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            val path=uri.pathSegments.first()
            if(!fired&&arm==path&&uri.pathSegments.size==1){
                check(minimalReads>0)
                raceQuery="path=$path, projection=${fields?.toList()}, selection=$selection"
                base.db.execSQL("UPDATE events SET uid2445='replacement-synthetic-uid' WHERE _id=1")
                if(path=="reminders")base.db.execSQL("UPDATE reminders SET minutes=77 WHERE event_id=1")
                else base.db.execSQL("UPDATE attendees SET attendeeName='Foreign synthetic owner' WHERE event_id=1")
                fired=true
            }
            val cursor=base.query(uri,fields,selection,args,sort)
            return object:CursorWrapper(cursor) {
                private fun read(column:Int){
                    val name=getColumnName(column)
                    if(fired&&path in setOf("reminders","attendees")&&name !in setOf("_id","event_id","calendar_id","uid2445","_sync_id"))foreignCells+="$path.$name"
                }
                override fun getString(column:Int):String? {
                    read(column);val result=super.getString(column)
                    if(path=="events"&&fields?.toSet()==setOf("_id","calendar_id","uid2445","_sync_id")&&getColumnName(column)=="_sync_id")minimalReads++
                    return result
                }
                override fun getLong(column:Int):Long {read(column);return super.getLong(column)}
                override fun getInt(column:Int):Int {read(column);return super.getInt(column)}
            }
        }
        override fun insert(uri:Uri,values:ContentValues?)=base.insert(uri,values)
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=base.update(uri,values,selection,args)
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=base.delete(uri,selection,args)
        override fun applyBatch(ops:ArrayList<ContentProviderOperation>)=base.applyBatch(ops)
    }
    private inner class Fixture(val context:Application,val provider:Provider,val store:CalendarLocalStore,val scope:CoroutineScope,val selected:EventRef) {
        val permission={p:String->p in setOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)}
        val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
        val source=CalendarSeriesReader(context.contentResolver,reader,store,permission)
        val owner=CalendarSeriesWriter(context.contentResolver,source,store,permission,providerClonePolicy=SyntheticCloneProviderPolicy)
        val family=CalendarFollowingSeriesWriter(context.contentResolver,source,store,permission,owner,Dispatchers.Default,RecurrencePlanningBudget(),ProviderReadBudgets(),SyntheticCloneProviderPolicy)
        lateinit var edit:EditableSeries
        val command=SeriesCommand.FollowingEdit(RecurrenceEditIntent.FollowingSeriesEdit(RecurrenceKey.Timed(selected.instanceStart),EventPatch(title=FieldChange.Set("Synthetic tail"))))
        suspend fun prepare():CalendarLocalStore.Operation {
            store.writableDatabase.execSQL("CREATE TRIGGER hold_prepared BEFORE UPDATE OF phase ON mutation_journal WHEN NEW.phase='ATTEMPTED' BEGIN SELECT RAISE(FAIL,'Synthetic pre-dispatch hold'); END")
            assertEquals(common(MutationResult.Unavailable),family.mutate("change",selected,edit.token,command))
            val prepared=store.operation("change")!!;assertEquals("PREPARED",prepared.phase);assertEquals(0,provider.base.batchCount)
            store.writableDatabase.execSQL("DROP TRIGGER hold_prepared");store.writableDatabase.execSQL("DELETE FROM edit_snapshots")
            family.frozenPreparationProbe={error("Frozen retry must not invoke current planning")}
            provider.minimalReads=0;provider.foreignCells.clear()
            return prepared
        }
    }
    private suspend fun fixture(block:suspend(Fixture)->Unit) {
        val context=RuntimeEnvironment.getApplication();val p=Provider();ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val store=CalendarLocalStore(context,"following-child-proof-${UUID.randomUUID()}.db");val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
            val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
            val seed=CalendarWriter(context.contentResolver,reader,store,{true}).create("seed",EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("Europe/London"),"Synthetic prefix",alertMinutes=listOf(10,30),targetIdentity=identity,recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(8)))) as MutationResult.LocalSaved
            p.base.db.execSQL("UPDATE events SET uid2445='prefix-issued',_sync_id='prefix-sync' WHERE _id=1")
            p.base.db.execSQL("INSERT INTO attendees(event_id,attendeeName,attendeeEmail,attendeeStatus,attendeeRelationship,attendeeType) VALUES(1,'Synthetic owner','owner@example.invalid',1,2,0)")
            p.base.db.execSQL("UPDATE events SET selfAttendeeStatus=1 WHERE _id=1")
            val selected=seed.ref.copy(instanceStart=start.plusSeconds(172800),instanceEnd=start.plusSeconds(176400),recurrence=RecurrenceRef(1,start.plusSeconds(172800)))
            val f=Fixture(context,p,store,scope,selected);f.edit=(f.owner.loadForEdit(selected) as SeriesReadResult.Ready).series
            p.base.batchCount=0;p.base.committed=false;block(f)
        } finally {scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.base.db.close();context.deleteDatabase(store.databaseName)}
    }
}
