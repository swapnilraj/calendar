package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.provider.CalendarContract
import dev.swapnilraj.calendar.core.*
import java.time.*
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

/** Independent public-API proof. The first three tests reproduce existing one-off ACK defects;
 * generated UID/early identity checks are new receipt6 guarantees, not historical receipt2 claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class OrdinaryCreateReceiptSixProofTest {
    private enum class Ack { CANONICAL, MALFORMED_CHILD, MISSING_CHILD, REORDERED_CHILD, QUERIED_MASTER }
    private val start=Instant.parse("2028-01-03T09:00:00Z")
    private val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("UTC"),
        "Synthetic receipt6 fixture",description="Synthetic authored description",alertMinutes=listOf(10,30),targetIdentity=identity)
    private val recurring=draft.copy(recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(3)))

    @Test fun oneOffMalformedReminderAckCannotClaimSave()=badChild(Ack.MALFORMED_CHILD)
    @Test fun oneOffMissingReminderAckCannotClaimSave()=badChild(Ack.MISSING_CHILD)
    @Test fun oneOffReorderedReminderAckCannotClaimSave()=badChild(Ack.REORDERED_CHILD)
    @Test fun invalidOneOffMasterStillHasNoKnownIdAndCannotReplay()=runBlocking {fixture(Ack.QUERIED_MASTER) {f->
        assertEquals(MutationResult.Uncertain("create",null),f.writer.create("create",draft))
        assertEquals("ATTEMPTED",f.store.operation("create")!!.phase)
        f.p.base.queries.clear()
        assertEquals(MutationResult.Uncertain("create",null),f.writer.recover("create"))
        assertEquals(MutationResult.Uncertain("create",null),f.writer.create("create",draft))
        assertTrue(f.p.base.queries.all {it.path=="calendars"});assertEquals(1,f.p.base.batchCount)
    }}

    @Test fun newOneOffUidIsStableAcrossReadOnlyRecoveryAndRepeatedPublicCreateCalls()=runBlocking {fixture {f->
        val saved=f.writer.create("create",draft) as MutationResult.LocalSaved
        val uid=f.p.uid(saved.ref.eventId);assertFalse("New CREATE must issue a stable UID",uid.isNullOrBlank())
        f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots")
        assertEquals(saved,f.writer.recover("create"));assertEquals(uid,f.p.uid(saved.ref.eventId))
        f.permissions+=Manifest.permission.WRITE_CALENDAR
        assertEquals(saved,f.writer.create("create",draft));assertEquals(uid,f.p.uid(saved.ref.eventId));assertEquals(1,f.p.base.batchCount)
    }}
    @Test fun identicalPublicDraftsWithDifferentOperationIdsGetDistinctGeneratedUids()=runBlocking {fixture {f->
        val first=f.writer.create("first",draft) as MutationResult.LocalSaved
        val second=f.writer.create("second",draft) as MutationResult.LocalSaved
        assertNotEquals(first.ref.eventId,second.ref.eventId)
        val one=f.p.uid(first.ref.eventId);val two=f.p.uid(second.ref.eventId)
        assertFalse(one.isNullOrBlank());assertFalse(two.isNullOrBlank());assertNotEquals(one,two);assertEquals(2,f.p.base.batchCount)
    }}
    @Test fun oneOffUidReplacementFailsBeforePrivateEventOrChildPayloadRead()=replacement(draft)
    @Test fun recurringUidReplacementFailsBeforePrivateEventOrChildPayloadRead()=replacement(recurring)
    @Test fun knownCommitInEitherModeRecoversAfterReopenWithReadOnlyAccessAndNoReplay()=runBlocking {
        for(input in listOf(draft,recurring)) fixture {f->
            f.p.base.failReadsAfterCommit=true
            val first=f.writer.create("create",input)
            assertTrue(first.toString(),first is MutationResult.Uncertain)
            assertEquals("COMMITTED",f.store.operation("create")!!.phase)
            val id=checkNotNull(f.p.master);val uid=f.p.uid(id)
            assertFalse("New frozen native plan must include UID",uid.isNullOrBlank())
            f.p.base.failReadsAfterCommit=false;f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
            f.store.writableDatabase.execSQL("DELETE FROM edit_snapshots")
            val context=RuntimeEnvironment.getApplication();val reopened=CalendarLocalStore(context,f.store.databaseName)
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            try {
                val reader=CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0)
                val second=CalendarWriter(context.contentResolver,reader,reopened,f.permissions::contains)
                val recovered=second.recover("create")
                assertTrue(recovered.toString(),recovered is MutationResult.LocalSaved)
                val ref=(recovered as MutationResult.LocalSaved).ref
                assertEquals(id,ref.eventId);assertEquals(start,ref.instanceStart);assertEquals(start.plusSeconds(3600),ref.instanceEnd)
                assertEquals(input.recurrence!=null,ref.recurrence!=null);assertEquals(uid,f.p.uid(id))
                assertEquals(recovered,second.recover("create"));assertEquals(1,f.p.base.batchCount)
            } finally {scope.cancel();scope.coroutineContext[Job]!!.join();reopened.close()}
        }
    }

    private fun badChild(mode:Ack)=runBlocking {fixture(mode) {f->
        val result=f.writer.create("create",draft);val id=checkNotNull(f.p.master)
        assertEquals(MutationResult.Uncertain("create",id),result)
        assertEquals("COMMITTED",f.store.operation("create")!!.phase)
        assertFalse(JSONObject(f.store.operation("create")!!.resultRef!!).has("firstRef"))
        f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertEquals(MutationResult.Uncertain("create",id),f.writer.recover("create"))
        f.permissions+=Manifest.permission.WRITE_CALENDAR
        assertEquals(MutationResult.Uncertain("create",id),f.writer.create("create",draft));assertEquals(1,f.p.base.batchCount)
    }}
    private fun replacement(input:EventDraft)=runBlocking {fixture {f->
        val saved=f.writer.create("create",input) as MutationResult.LocalSaved
        f.p.base.db.execSQL("UPDATE events SET uid2445='foreign-synthetic-uid@probe.invalid' WHERE _id=?",arrayOf(saved.ref.eventId))
        f.p.privateEventReads.clear();f.p.privateChildReads.clear();f.p.base.queries.clear()
        val result=f.writer.recover("create")
        assertTrue("UID replacement must stay uncertain: result=$result, private event columns=${f.p.privateEventReads}, child columns=${f.p.privateChildReads}",result is MutationResult.Uncertain)
        assertTrue("No private event cells may be read before UID binding",f.p.privateEventReads.isEmpty())
        assertTrue("No child payload cells may be read before UID binding",f.p.privateChildReads.isEmpty())
        assertEquals(1,f.p.base.batchCount)
    }}
    private class Provider(private val mode:Ack):ContentProvider() {
        val base=CalendarRecurringCreateTest.Provider()
        var master:Long?=null
        val privateEventReads=mutableListOf<String>();val privateChildReads=mutableListOf<String>()
        fun uid(id:Long):String?=base.db.query("events",arrayOf("uid2445"),"_id=?",arrayOf(id.toString()),null,null,null).use {check(it.moveToFirst());it.getString(0)}
        override fun onCreate()=true
        override fun getType(uri:Uri):String?=null
        override fun query(uri:Uri,fields:Array<out String>?,where:String?,args:Array<out String>?,sort:String?):Cursor {
            val cursor=base.query(uri,fields,where,args,sort);val path=uri.pathSegments.first()
            return object:CursorWrapper(cursor) {
                private fun accessed(column:Int) {
                    val field=getColumnName(column)
                    if(path=="events"&&field !in setOf("_id","calendar_id","uid2445","_sync_id"))privateEventReads+=field
                    if(path=="instances"&&field !in setOf("event_id","calendar_id"))privateEventReads+="instances.$field"
                    if(path in setOf("reminders","attendees","extendedproperties")&&field !in setOf("_id","event_id"))privateChildReads+=field
                }
                override fun getString(column:Int):String? {accessed(column);return super.getString(column)}
                override fun getLong(column:Int):Long {accessed(column);return super.getLong(column)}
                override fun getInt(column:Int):Int {accessed(column);return super.getInt(column)}
            }
        }
        override fun insert(uri:Uri,values:ContentValues?)=base.insert(uri,values)
        override fun update(uri:Uri,values:ContentValues?,where:String?,args:Array<out String>?)=base.update(uri,values,where,args)
        override fun delete(uri:Uri,where:String?,args:Array<out String>?)=base.delete(uri,where,args)
        override fun applyBatch(operations:ArrayList<ContentProviderOperation>):Array<ContentProviderResult> {
            val result=base.applyBatch(operations);master=ContentUris.parseId(checkNotNull(result[1].uri))
            when(mode) {
                Ack.CANONICAL->Unit
                Ack.MALFORMED_CHILD->result[2]=ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/reminders/not-an-id"))
                Ack.MISSING_CHILD->return result.dropLast(1).toTypedArray()
                Ack.REORDERED_CHILD->{val first=result[2];result[2]=result[3];result[3]=first}
                Ack.QUERIED_MASTER->result[1]=ContentProviderResult(checkNotNull(result[1].uri).buildUpon().appendQueryParameter("unexpected","1").build())
            };return result
        }
    }
    private data class Fixture(val p:Provider,val writer:CalendarWriter,val store:CalendarLocalStore,val permissions:MutableSet<String>)
    private suspend fun fixture(mode:Ack=Ack.CANONICAL,block:suspend(Fixture)->Unit) {
        val context=RuntimeEnvironment.getApplication();val p=Provider(mode)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val store=CalendarLocalStore(context,"synthetic-receipt6-${UUID.randomUUID()}.db")
        val permissions=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val writer=CalendarWriter(context.contentResolver,CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0),store,permissions::contains)
        try {block(Fixture(p,writer,store,permissions))}finally {scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.base.db.close();context.deleteDatabase(store.databaseName)}
    }
}
