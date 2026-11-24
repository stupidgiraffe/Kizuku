package io.github.stupidgiraffe.kizuku

import android.os.SystemClock
import io.github.stupidgiraffe.kizuku.api.IKizukuService

/** Instrumentation restarts the manager process; the existing host must reattach first. */
internal fun awaitRuntimeTestHost(): IKizukuService {
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (SystemClock.elapsedRealtime() < deadline) {
        CoreConnection.service?.let { return it }
        SystemClock.sleep(75)
    }
    error("The existing privileged host did not reattach to the test process")
}
