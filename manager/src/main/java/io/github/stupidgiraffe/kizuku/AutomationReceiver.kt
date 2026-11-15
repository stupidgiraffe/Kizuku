package io.github.stupidgiraffe.kizuku

import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

internal enum class AutomationCommand(val label: String) {
    START("Start"), STOP("Stop"), RESTART("Restart"), TOGGLE("Toggle"), STATUS("Status");

    val action get() = "${BuildConfig.APPLICATION_ID}.automation.$name"
}

/** Only lifecycle controls are exposed. Automation does not grant Shizuku or Axeron access. */
internal object AutomationCommands {
    const val JOB_ID = 347120
    const val GENERATION = "generation"
    const val COMMAND = "command"
    const val TICKET = "ticket"
    const val TOKEN = "token"
    private const val LAST_RESULT = "last_result"

    private fun settings(context: Context) = context.getSharedPreferences("automation", Context.MODE_PRIVATE)
    private fun scheduler(context: Context) = context.getSystemService(JobScheduler::class.java)

    fun token(context: Context): String? = settings(context).getString(TOKEN, null)

    fun setEnabled(context: Context, enabled: Boolean) {
        val edit = settings(context).edit().clear()
        if (enabled) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            edit.putString(TOKEN, Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            edit.putString(GENERATION, UUID.randomUUID().toString())
        }
        check(edit.commit()) { "Could not save automation settings." }
        scheduler(context).cancel(JOB_ID)
    }

    fun authenticate(context: Context, supplied: String?): String? {
        val expected = token(context) ?: return null
        if (supplied == null || supplied.length != expected.length ||
            !MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))
        ) return null
        return settings(context).getString(GENERATION, null)
    }

    fun current(context: Context, generation: String, ticket: String): Boolean {
        val preferences = settings(context)
        return generation == preferences.getString(GENERATION, null) &&
            ticket == preferences.getString(TICKET, null)
    }

    fun status(context: Context): String {
        if (scheduler(context).getPendingJob(JOB_ID) != null) return "A command is pending or running."
        return settings(context).getString(LAST_RESULT, null) ?: "No command has completed."
    }

    fun recordResult(context: Context, generation: String, ticket: String, result: String) {
        if (current(context, generation, ticket)) {
            settings(context).edit().remove(TICKET).putString(LAST_RESULT, result.take(240)).apply()
        }
    }

    fun cancel(context: Context) {
        val preferences = settings(context)
        if (preferences.contains(TICKET)) {
            preferences.edit().remove(TICKET).putString(LAST_RESULT, "Pending command cancelled.").apply()
            scheduler(context).cancel(JOB_ID)
        }
    }

    fun enqueue(context: Context, command: AutomationCommand, generation: String): String? {
        val jobs = scheduler(context)
        if (generation != settings(context).getString(GENERATION, null)) return "Automation key changed."
        if (jobs.getPendingJob(JOB_ID) != null) {
            if (command != AutomationCommand.STOP) return "A command is already pending or running."
            cancel(context)
        }
        val ticket = UUID.randomUUID().toString()
        val arguments = PersistableBundle().apply {
            putString(COMMAND, command.name)
            putString(GENERATION, generation)
            putString(TICKET, ticket)
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutomationJobService::class.java))
            .setExtras(arguments)
            .setMinimumLatency(1)
            .setOverrideDeadline(1000)
            .build()
        settings(context).edit().putString(TICKET, ticket).apply()
        if (jobs.schedule(job) == JobScheduler.RESULT_SUCCESS) return null
        return "Android could not schedule the command.".also { recordResult(context, generation, ticket, it) }
    }
}

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = AutomationCommand.entries.firstOrNull { it.action == intent.action }
        val generation = try {
            AutomationCommands.authenticate(context, intent.getStringExtra(AutomationCommands.TOKEN))
        } catch (_: RuntimeException) {
            null
        }
        if (command == null || generation == null) {
            if (isOrderedBroadcast) setResult(Activity.RESULT_CANCELED, "Automation request refused.", null)
            return
        }
        if (command == AutomationCommand.STATUS) {
            if (isOrderedBroadcast) {
                val running = CoreConnection.service?.asBinder()?.isBinderAlive == true
                setResult(Activity.RESULT_OK, if (running) "Running" else "Stopped", Bundle().apply {
                    putBoolean("running", running)
                    putString("command_status", AutomationCommands.status(context))
                })
            }
            return
        }
        val failure = try {
            AutomationCommands.enqueue(context, command, generation)
        } catch (_: RuntimeException) {
            "Android could not schedule the command."
        }
        if (isOrderedBroadcast) {
            setResult(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                failure ?: "Queued", null)
        }
    }
}

/** Android keeps the manager alive only for this bounded, one-shot command. */
class AutomationJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var work: Pair<String, Job>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val generation = params.extras.getString(AutomationCommands.GENERATION) ?: return false
        val ticket = params.extras.getString(AutomationCommands.TICKET) ?: return false
        fun authorized() = AutomationCommands.current(this, generation, ticket)
        if (!authorized()) return false
        val command = AutomationCommand.entries.firstOrNull {
            it.name == params.extras.getString(AutomationCommands.COMMAND)
        } ?: return false
        if (command == AutomationCommand.STATUS) return false
        work?.second?.cancel()
        work = ticket to scope.launch {
            val failure = try {
                withTimeout(95000) { ActivationController.get(this@AutomationJobService).runAutomation(command, ::authorized) }
            } catch (_: TimeoutCancellationException) {
                "Command timed out."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "Command failed."
            }
            val result = if (failure == null) "${command.label} completed." else "${command.label} failed. $failure"
            AutomationCommands.recordResult(this@AutomationJobService, generation, ticket, result)
            work = null
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val ticket = params.extras.getString(AutomationCommands.TICKET)
        val generation = params.extras.getString(AutomationCommands.GENERATION)
        if (ticket != null && work?.first == ticket) {
            work?.second?.cancel()
            work = null
        }
        if (ticket != null && generation != null) recordInterrupted(generation, ticket)
        return false
    }

    private fun recordInterrupted(generation: String, ticket: String) {
        AutomationCommands.recordResult(this, generation, ticket, "Android interrupted the command.")
    }
    override fun onDestroy() {
        work?.second?.cancel()
        super.onDestroy()
    }
}
