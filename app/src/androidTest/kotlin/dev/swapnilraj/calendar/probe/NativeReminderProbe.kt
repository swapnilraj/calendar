package dev.swapnilraj.calendar.probe

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Instrumentation
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.CalendarContract
import dev.swapnilraj.calendar.android.reminders.ReminderRuntimeOwner
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Identity/capabilities only. No provider data, store, channel, permissions, fixtures or cleanup. */
class NativeReminderProbe : Instrumentation() {
    private var arguments = Bundle()
    private val began = SystemClock.elapsedRealtime()
    private class Refusal(val code: String) : RuntimeException()

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        if (context.packageName != APP || className != APPLICATION) throw Refusal("APPLICATION_IDENTITY_REQUIRED")
        return super.newApplication(cl, className, context)
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments?.let(::Bundle) ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val report = JSONObject().put("schema", 2).put("scope", "UNBOUND_NO_DATA_OR_DELIVERY")
            .put("productionProfileActivation", "NONE").put("googleWorkspaceRoundTrip", "NOT_RUN")
        var pass = false
        try {
            if (Build.VERSION.SDK_INT != 35) throw Refusal("PINNED_API35_REQUIRED")
            collect(report)
            pass = true
        } catch (failure: Refusal) {
            report.put("failureCode", failure.code)
        } catch (_: Exception) {
            report.put("failureCode", "IDENTITY_UNAVAILABLE")
        }
        report.put("status", if (pass) "PASS" else "FAIL")
        val encoded = report.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > 32768) {
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("reminder_probe_report", "{\"schema\":2,\"status\":\"FAIL\",\"failureCode\":\"REPORT_LIMIT\"}")
            })
        } else finish(if (pass) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("reminder_probe_report", encoded) })
    }

    private fun collect(report: JSONObject) {
        if (Build.VERSION.SDK_INT < 35) throw Refusal("PINNED_API35_REQUIRED")
        expect(arguments.keySet() == setOf("probeOptIn", "probeRunId", "expectedBootId", "expectedAppSha256", "expectedTestSha256", "expectedSignerSha256"), "ARGUMENT_KEYS")
        expect(text("probeOptIn") == "REMINDER_IDENTITY_V2", "OPT_IN_REQUIRED")
        val run = text("probeRunId")
        expect(runCatching { UUID.fromString(run).toString() == run }.getOrDefault(false), "RUN_ID_REQUIRED")
        expect(text("expectedBootId") == BOOT, "BOOT_PIN_REQUIRED")
        expect(Build.FINGERPRINT == FINGERPRINT && Build.VERSION.SECURITY_PATCH == "2024-09-05", "BUILD_PIN_MISMATCH")
        val app = targetContext.applicationContext as? Application ?: throw Refusal("APPLICATION_UNAVAILABLE")
        expect(app.javaClass.name == APPLICATION && app.packageName == APP && app is ReminderRuntimeOwner, "APPLICATION_IDENTITY_REQUIRED")
        expect(Process.myUid() in 10000..99999 && app.applicationInfo.uid == Process.myUid(), "USER0_UID_REQUIRED")
        expect(app.packageManager.getPackagesForUid(Process.myUid())?.toList() == listOf(APP), "SHARED_UID_REFUSED")
        expect(Application.getProcessName() == APP, "PROCESS_NAME_REQUIRED")
        val start = Process.getStartElapsedRealtime()
        expect(start in 1..SystemClock.elapsedRealtime(), "PROCESS_START_REQUIRED")
        report.put("runId", run).put("hostBootPin", BOOT).put("targetUid", Process.myUid())
            .put("targetProcess", JSONObject().put("pid", Process.myPid()).put("startElapsedRealtime", start).put("name", APP))
        val applicationEnabledSetting = app.packageManager.getApplicationEnabledSetting(APP)
        report.put("applicationEnabledSetting", applicationEnabledSetting)
        expect(applicationEnabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, "APPLICATION_ENABLED_OVERRIDE_REFUSED")
        val expectedSigner = text("expectedSignerSha256")
        expect(expectedSigner == SIGNER, "REVIEWED_SIGNER_REQUIRED")
        val appInfo = packageIdentity(app, APP, text("expectedAppSha256"), expectedSigner)
        val testInfo = packageIdentity(app, TEST, text("expectedTestSha256"), expectedSigner)
        report.put("appIdentity", appInfo).put("testIdentity", testInfo)
        report.put("providerIdentity", providerIdentity(app))
        val components = JSONArray()
        REMINDER_COMPONENTS.forEach { (name, service) ->
            val info = component(app, name, service, activity = false)
            expect(info.enabled && info.exported == service && info.processName == APP, "REMINDER_COMPONENT_MISMATCH")
            if (service) expect((info as android.content.pm.ServiceInfo).permission == "android.permission.BIND_JOB_SERVICE", "JOB_SERVICE_PERMISSION_REQUIRED")
            components.put(componentJson(app, info, if (service) "service" else "receiver"))
        }
        DISABLED_COMPONENTS.forEach { (name, kind) ->
            val info = component(app, name, kind == "service", kind == "activity", kind == "provider")
            expect(!info.enabled && info.processName == APP, "NON_REMINDER_COMPONENT_ENABLED")
            components.put(componentJson(app, info, kind))
        }
        report.put("components", components)
        val owner = app as ReminderRuntimeOwner
        val runtime = owner.reminderRuntime
        expect(runtime === owner.reminderRuntime, "RUNTIME_OWNER_REPLACED")
        val startup = settledStartup(app)
        report.put("startup", startupJson(startup))
        val notifications = app.getSystemService(NotificationManager::class.java)
        val jobs = app.getSystemService(JobScheduler::class.java).allPendingJobs
        val posts = notifications.activeNotifications
        expect(jobs.size <= 16 && posts.size <= 16, "OWN_RESOURCE_LIMIT")
        val channel = notifications.getNotificationChannel("event_reminders")
        report.put("capabilities", JSONObject()
            .put("readCalendar", app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
            .put("writeCalendar", app.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED)
            .put("postNotifications", app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            .put("notificationsEnabled", notifications.areNotificationsEnabled())
            .put("exactAlarmAccess", app.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
            .put("channelPresent", channel != null).put("channelEnabled", channel?.let { it.importance != NotificationManager.IMPORTANCE_NONE } ?: JSONObject.NULL)
            .put("ownJobCount", jobs.size).put("ownNotificationCount", posts.size))
        expect(jobs.isEmpty() && posts.isEmpty() && channel == null, "UNBOUND_RESOURCES_PRESENT")
        expect(ProbeReminderBindings.snapshot(app) == startup, "STARTUP_CHANGED_DURING_CAPTURE")
        report.put("constraints", JSONObject().put("state", "UNBOUND").put("providerDataAccessAvailable", false)
            .put("privateStoreAccessAvailable", false).put("fixtureModesAvailable", false)
            .put("cleanupAvailable", false).put("permissionCommandsAvailable", false)
            .put("nativeJobSchedulingAvailable", false).put("deliveryAvailable", false)
            .put("settlementSeconds", 5).put("deadlineKind", "cooperative").put("deadlineSeconds", 120).put("componentCap", 18).put("ownResourceCap", 16))
        deadline()
    }

    @Suppress("DEPRECATION")
    private fun packageIdentity(context: Context, pkg: String, expectedHash: String, signer: String): JSONObject {
        expect(SHA.matches(expectedHash), "APK_HASH_REQUIRED")
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val app = info.applicationInfo ?: throw Refusal("PACKAGE_APPLICATION_INFO_REQUIRED")
        val signing = info.signingInfo ?: throw Refusal("PACKAGE_SIGNING_INFO_REQUIRED")
        expect(app.enabled && (app.splitSourceDirs ?: emptyArray()).isEmpty(), "SPLIT_OR_DISABLED_TARGET_REFUSED")
        if (pkg == APP) expect(app.className == APPLICATION && app.uid == Process.myUid() && info.longVersionCode == 1L, "TARGET_MANIFEST_MISMATCH")
        val signers = signing.apkContentsSigners.map { hash(it.toByteArray()) }.sorted()
        expect(signers == listOf(signer) && fileHash(app.sourceDir) == expectedHash, "APK_PAIR_MISMATCH")
        return JSONObject().put("package", pkg).put("versionCode", info.longVersionCode).put("baseSha256", expectedHash)
            .put("signers", JSONArray(signers)).put("splits", JSONArray())
    }

    @Suppress("DEPRECATION")
    private fun providerIdentity(context: Context): JSONObject {
        val manager = context.packageManager
        val provider = manager.resolveContentProvider(CalendarContract.AUTHORITY, 0) ?: throw Refusal("PROVIDER_METADATA_UNAVAILABLE")
        expect(provider.packageName == "com.android.providers.calendar", "PROVIDER_PACKAGE_MISMATCH")
        val pkg = manager.getPackageInfo(provider.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val app = pkg.applicationInfo ?: throw Refusal("PROVIDER_APPLICATION_INFO_REQUIRED")
        val signing = pkg.signingInfo ?: throw Refusal("PROVIDER_SIGNING_INFO_REQUIRED")
        val signers = signing.apkContentsSigners.map { hash(it.toByteArray()) }.sorted()
        val history = (signing.signingCertificateHistory ?: emptyArray()).map { hash(it.toByteArray()) }
        expect(pkg.longVersionCode == 35L && (app.splitSourceDirs ?: emptyArray()).isEmpty() &&
            signers == listOf(PROVIDER_SIGNER) && history == listOf(PROVIDER_SIGNER) && fileHash(app.sourceDir) == PROVIDER_BASE, "PROVIDER_BINARY_MISMATCH")
        return JSONObject().put("package", provider.packageName).put("versionCode", 35).put("baseSha256", PROVIDER_BASE)
            .put("splits", JSONArray()).put("signers", JSONArray(signers)).put("signingHistory", JSONArray(history))
            .put("buildFingerprint", FINGERPRINT).put("sdk", 35).put("securityPatch", "2024-09-05")
    }

    @Suppress("DEPRECATION")
    private fun component(context: Context, name: String, service: Boolean, activity: Boolean, provider: Boolean = false): ComponentInfo {
        val key = ComponentName(APP, name)
        return (if (service) context.packageManager.getServiceInfo(key, PackageManager.MATCH_DISABLED_COMPONENTS)
        else if (provider) context.packageManager.getProviderInfo(key, PackageManager.MATCH_DISABLED_COMPONENTS)
        else if (activity) context.packageManager.getActivityInfo(key, PackageManager.MATCH_DISABLED_COMPONENTS)
        else context.packageManager.getReceiverInfo(key, PackageManager.MATCH_DISABLED_COMPONENTS)).also {
            expect(it.packageName == APP && it.name == name, "COMPONENT_PACKAGE_MISMATCH")
        }
    }
    private fun componentJson(context: Context, info: ComponentInfo, kind: String): JSONObject {
        val enabledSetting = context.packageManager.getComponentEnabledSetting(ComponentName(APP, info.name))
        expect(enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, "COMPONENT_ENABLED_OVERRIDE_REFUSED")
        return JSONObject().put("package", info.packageName).put("class", info.name).put("kind", kind)
            .put("enabled", info.enabled).put("exported", info.exported).put("process", info.processName).put("enabledSetting", enabledSetting)
    }
    private fun settledStartup(app: Application): ProbeReminderStartup {
        val settlement = ProbeReminderSettlement(SystemClock.elapsedRealtime())
        while (true) {
            deadline()
            val state = ProbeReminderBindings.snapshot(app)
            val result = settlement.observe(state, SystemClock.elapsedRealtime())
            when (result.status) {
                ProbeReminderSettlementStatus.ACCEPT -> return state
                ProbeReminderSettlementStatus.REFUSE -> throw Refusal("STARTUP_" + requireNotNull(result.issue).name)
                ProbeReminderSettlementStatus.TIMEOUT -> throw Refusal("CALLBACK_SETTLEMENT_TIMEOUT")
                ProbeReminderSettlementStatus.WAIT -> SystemClock.sleep(5)
            }
        }
    }
    private fun startupJson(s: ProbeReminderStartup): JSONObject {
        val callers = JSONObject()
        ProbeReminderCaller.entries.forEach { callers.put(it.name, s.callers.getValue(it)) }
        return JSONObject().put("state", "UNBOUND").put("runtimeGetterCalls", s.runtimeGetterCalls)
            .put("configurationAttempts", s.configurationAttempts).put("operationAttempts", s.operationAttempts).put("retryAttempts", s.retryAttempts)
            .put("registrationAttempts", s.registrationAttempts).put("cancellationAttempts", s.cancellationAttempts).put("health", s.health.name)
            .put("inFlight", s.inFlight).put("callers", callers).put("retryRefusals", s.retryRefusals)
            .put("suppressionRefusals", s.suppressionRefusals).put("unexpectedDeliveryAttempts", s.unexpectedDeliveryAttempts)
    }
    private fun text(key: String): String = arguments.getString(key)?.takeIf { it.length <= 128 } ?: throw Refusal("ARGUMENT_REQUIRED")
    private fun expect(condition: Boolean, code: String) { if (!condition) throw Refusal(code); deadline() }
    private fun deadline() { if (SystemClock.elapsedRealtime() - began > 120_000) throw Refusal("COOPERATIVE_DEADLINE_EXCEEDED") }
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun fileHash(path: String): String {
        val file = File(path)
        expect(file.isFile && file.length() in 1..134_217_728L, "APK_SIZE_LIMIT")
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { deadline(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val APP = "dev.swapnilraj.calendar.reminderprobe"
        const val TEST = "$APP.tests"
        const val APPLICATION = "dev.swapnilraj.calendar.probe.ProbeReminderApplication"
        const val BOOT = "f14d0429-80d1-4b93-9eeb-da5f4f2046ac"
        const val FINGERPRINT = "google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys"
        const val SIGNER = "90936f837de57f48b69c5a1364920972880362722faf228d2f66c875f6bc6278"
        const val PROVIDER_BASE = "a1facdbec3de78c6817efe1aea40b804a66c8ccbcd23a72a426d8c21cfebd273"
        const val PROVIDER_SIGNER = "6cecc50e34ae31bfb5678986d6d6d3736c571ded2f2459527793e1f054eb0c9b"
        private val SHA = Regex("[0-9a-f]{64}")
        private val ROOT = "dev.swapnilraj.calendar."
        val REMINDER_COMPONENTS = listOf(ROOT + "android.reminders.ReminderAlarmReceiver" to false,
            ROOT + "android.reminders.ReminderActionReceiver" to false, ROOT + "android.reminders.ReminderRecoveryReceiver" to false,
            ROOT + "android.reminders.ReminderJobService" to true)
        val DISABLED_COMPONENTS = listOf("MainActivity", "android.navigation.CalendarEntryActivity", "android.navigation.CalendarEditReviewActivity",
            "android.navigation.CalendarSeedReviewActivity", "ReminderOpenActivity", "WidgetOpenActivity", "android.widgets.AgendaWidgetConfigureActivity")
            .map { ROOT + it to "activity" } + listOf(ROOT + "android.widgets.AgendaWidgetProvider" to "receiver",
                ROOT + "android.widgets.AgendaWidgetRecoveryReceiver" to "receiver", ROOT + "android.widgets.AgendaWidgetJobService" to "service") +
            listOf("androidx.compose.ui.tooling.PreviewActivity" to "activity", "androidx.activity.ComponentActivity" to "activity",
                "androidx.startup.InitializationProvider" to "provider", "androidx.profileinstaller.ProfileInstallReceiver" to "receiver")
    }
}
