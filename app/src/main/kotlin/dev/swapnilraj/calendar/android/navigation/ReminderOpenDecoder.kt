package dev.swapnilraj.calendar.android.navigation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.swapnilraj.calendar.android.reminders.*
import dev.swapnilraj.calendar.core.ReminderPostIdentity

/** Public Activity input is untrusted even for an app-private action name. Require canonical data
 * and a complete post identity, then let the resolver verify the durable post and provider namespace.
 */
object ReminderOpenDecoder {
    fun decode(context: Context, intent: Intent?, activity: ComponentName): ReminderPostIdentity? = try {
        require(intent != null && intent.action == ReminderIntents.ACTION_OPEN && intent.component == activity)
        require(activity.packageName == context.packageName)
        val identity = requireNotNull(ReminderIntents.identity(intent))
        val post = requireNotNull(identity.postIdentityOrNull())
        val targets = ReminderIntentTargets(activity, ComponentName(context, ReminderActionReceiver::class.java))
        require(intent.data == ReminderIntents.intent(context, targets, identity, ReminderIntents.ACTION_OPEN).data)
        post
    } catch (_: RuntimeException) { null }
}
