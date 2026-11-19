package dev.swapnilraj.calendar.data.calendar

import android.Manifest
import android.app.Application
import android.content.*
import android.database.Cursor
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

/** Dedicated synthetic provider acknowledgments only. A committed matching payload cannot replace
 * missing or contradictory insert identity evidence. No Google sync or OS provider is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RecurringCreateAcknowledgmentProofTest {
    private enum class Ack { SCHEME, QUERY, FRAGMENT, ZERO, MALFORMED_CHILD, MISSING_CHILD, WRONG_PARENT_CHILD, REORDERED_CHILD, DUPLICATE_CHILD, FOREIGN_ONE_OFF }
    private val start=Instant.parse("2028-01-03T09:00:00Z")
    private val identity=CalendarIdentity(CalendarId(2),CalendarAccount("owner@example.invalid","com.google"),"owner@example.invalid","calendar-A")
    private val draft=EventDraft(CalendarId(2),EventTime.Timed(start,start.plusSeconds(3600)),ZoneId.of("UTC"),
        "Synthetic recurring acknowledgment fixture",alertMinutes=listOf(10,30),targetIdentity=identity,
        recurrence=RecurrenceSpec(RecurrencePattern.Daily,end=RecurrenceEnd.Count(3)))

    @Test fun recurringWrongSchemeMasterAcknowledgmentCannotBecomeKnownSave()=master(Ack.SCHEME)
    @Test fun recurringQueriedMasterAcknowledgmentCannotBecomeKnownSave()=master(Ack.QUERY)
    @Test fun recurringFragmentedMasterAcknowledgmentCannotBecomeKnownSave()=master(Ack.FRAGMENT)
    @Test fun recurringZeroMasterAcknowledgmentCannotBecomeKnownSaveEvenWhenSuchARowExists()=master(Ack.ZERO)
    @Test fun ordinaryOneOffForeignAuthorityAcknowledgmentCannotBorrowItsNumericEventId()=master(Ack.FOREIGN_ONE_OFF)
    @Test fun malformedReminderAcknowledgmentRetainsKnownCommitWithoutGuessingChildIdentity()=child(Ack.MALFORMED_CHILD)
    @Test fun missingReminderAcknowledgmentRetainsKnownCommitWithoutGuessingChildIdentity()=child(Ack.MISSING_CHILD)
    @Test fun wrongParentReminderAcknowledgmentCannotBeReplacedByMatchingPayloadReadback()=child(Ack.WRONG_PARENT_CHILD)
    @Test fun reorderedReminderAcknowledgmentsCannotBeAcceptedAsTheIssuedInsertOrder()=child(Ack.REORDERED_CHILD)
    @Test fun duplicateReminderAcknowledgmentsCannotBecomeDistinctLinkedChildren()=child(Ack.DUPLICATE_CHILD)

    private fun master(mode:Ack)=runBlocking {fixture(mode) {f->
        val input=if(mode==Ack.FOREIGN_ONE_OFF)draft.copy(recurrence=null) else draft
        val result=f.writer.create("synthetic-create",input)
        assertEquals(MutationResult.Uncertain("synthetic-create",null),result)
        assertEquals("ATTEMPTED",f.store.operation("synthetic-create")!!.phase)
        assertEquals(1,f.p.base.count("events"));assertEquals(1,f.p.base.batchCount)
        f.p.base.queries.clear()
        assertEquals(MutationResult.Uncertain("synthetic-create",null),f.writer.recover("synthetic-create"))
        assertEquals(MutationResult.Uncertain("synthetic-create",null),f.writer.create("synthetic-create",input))
        assertTrue(f.p.base.queries.all {it.path=="calendars"})
        assertEquals(1,f.p.base.batchCount)
    }}
    private fun child(mode:Ack)=runBlocking {fixture(mode) {f->
        val result=f.writer.create("synthetic-create",draft)
        val id=checkNotNull(f.p.master)
        assertEquals(MutationResult.Uncertain("synthetic-create",id),result)
        val receipt=JSONObject(f.store.operation("synthetic-create")!!.resultRef!!)
        assertEquals("COMMITTED",f.store.operation("synthetic-create")!!.phase)
        assertEquals(id,receipt.getLong("master"));assertFalse(receipt.has("firstRef"))
        assertEquals(1,f.p.base.count("events"));assertEquals(1,f.p.base.batchCount)
        f.permissions.remove(Manifest.permission.WRITE_CALENDAR)
        assertEquals(MutationResult.Uncertain("synthetic-create",id),f.writer.recover("synthetic-create"))
        f.permissions+=Manifest.permission.WRITE_CALENDAR
        assertEquals(MutationResult.Uncertain("synthetic-create",id),f.writer.create("synthetic-create",draft))
        assertEquals(1,f.p.base.batchCount)
    }}

    private class Provider(private val mode:Ack):ContentProvider() {
        val base=CalendarRecurringCreateTest.Provider()
        var master:Long?=null
        override fun onCreate()=true
        override fun getType(uri:Uri):String?=null
        override fun query(uri:Uri,fields:Array<out String>?,where:String?,args:Array<out String>?,sort:String?):Cursor=base.query(uri,fields,where,args,sort)
        override fun insert(uri:Uri,values:ContentValues?)=base.insert(uri,values)
        override fun update(uri:Uri,values:ContentValues?,where:String?,args:Array<out String>?)=base.update(uri,values,where,args)
        override fun delete(uri:Uri,where:String?,args:Array<out String>?)=base.delete(uri,where,args)
        override fun applyBatch(operations:ArrayList<ContentProviderOperation>):Array<ContentProviderResult> {
            val result=base.applyBatch(operations)
            val event=checkNotNull(result[1].uri);master=ContentUris.parseId(event)
            when(mode) {
                Ack.SCHEME->result[1]=ContentProviderResult(event.buildUpon().scheme("https").build())
                Ack.QUERY->result[1]=ContentProviderResult(event.buildUpon().appendQueryParameter("unexpected","1").build())
                Ack.FRAGMENT->result[1]=ContentProviderResult(event.buildUpon().fragment("unexpected").build())
                Ack.ZERO->{
                    base.db.execSQL("UPDATE events SET _id=0 WHERE _id=?",arrayOf(master))
                    base.db.execSQL("UPDATE reminders SET event_id=0 WHERE event_id=?",arrayOf(master))
                    result[1]=ContentProviderResult(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,0))
                }
                Ack.MALFORMED_CHILD->result[2]=ContentProviderResult(Uri.parse("content://${CalendarContract.AUTHORITY}/reminders/not-an-id"))
                Ack.MISSING_CHILD->return result.dropLast(1).toTypedArray()
                Ack.WRONG_PARENT_CHILD->{
                    base.db.execSQL("INSERT INTO reminders(_id,event_id,minutes,method) VALUES(9999,777,10,1)")
                    result[2]=ContentProviderResult(ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI,9999))
                }
                Ack.REORDERED_CHILD->{val first=result[2];result[2]=result[3];result[3]=first}
                Ack.DUPLICATE_CHILD->result[3]=result[2]
                Ack.FOREIGN_ONE_OFF->result[1]=ContentProviderResult(event.buildUpon().authority("foreign.synthetic.invalid").build())
            }
            return result
        }
    }
    private data class Fixture(val p:Provider,val writer:CalendarWriter,val store:CalendarLocalStore,val permissions:MutableSet<String>)
    private suspend fun fixture(mode:Ack,block:suspend(Fixture)->Unit) {
        val context=RuntimeEnvironment.getApplication();val p=Provider(mode)
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY,p)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store=CalendarLocalStore(context,"synthetic-ack-proof-${UUID.randomUUID()}.db")
        val permissions=mutableSetOf(Manifest.permission.READ_CALENDAR,Manifest.permission.WRITE_CALENDAR)
        val writer=CalendarWriter(context.contentResolver,CalendarReader(context.contentResolver,scope,stopTimeoutMillis=0),store,permissions::contains)
        try {block(Fixture(p,writer,store,permissions))}finally {scope.cancel();scope.coroutineContext[Job]!!.join();store.close();p.base.db.close();context.deleteDatabase(store.databaseName)}
    }
}
