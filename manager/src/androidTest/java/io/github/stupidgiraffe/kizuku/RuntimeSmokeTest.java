package io.github.stupidgiraffe.kizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.content.Intent;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class RuntimeSmokeTest {
    @Test public void exercisesModuleLifecycleAndRuntimeBoundaries() throws Exception {
        IKizukuService host = CoreConnection.INSTANCE.getService();
        if (host == null) {
            long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
            while (host == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(100);
                host = CoreConnection.INSTANCE.getService();
            }
        }
        assertNotNull(host);
        final IKizukuService service = host;
        Bundle status = service.status();
        assertTrue(status.getString("runtime_error"), status.getBoolean("axeron_runtime"));
        assertEquals(Integer.toString(status.getInt("uid")), service.runtimeCommand("id -u").getString("stdout").trim());

        File cache = InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir();
        File file = File.createTempFile("plugin", ".zip", cache);
        String id = "runtime_" + UUID.randomUUID().toString().replace("-", "");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
                write(zip, "../escape", "invalid");
            }
            assertThrows(IllegalStateException.class, () -> install(service, file));
            try (var zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
                write(zip, "module.prop", "id=" + id + "\nname=Runtime test\nversionCode=1\naxeronPlugin=30000\n");
                write(zip, "customize.sh", "printf configured > \"$MODPATH/install-state\"\n");
                write(zip, "post-fs-data.sh", "printf started > start-state\n");
                write(zip, "service.sh", "printf '%s' \"$$\" > service-pid\nexec sleep 300\n");
                write(zip, "action.sh", "printf '%s:' \"$AXERON_PLUGIN\"; cat install-state start-state\n");
                write(zip, "webroot/index.html", "<!doctype html><title>Runtime fixture</title>");
                write(zip, "uninstall.sh", "test -f install-state\n");
            }
            assertEquals(id, install(service, file).getString("id"));
            Bundle action = service.controlRuntimePlugin(id, "action");
            assertEquals(0, action.getInt("exit"));
            assertEquals(id + ":configuredstarted", action.getString("stdout"));
            try (var asset = new ParcelFileDescriptor.AutoCloseInputStream(service.openRuntimeWebAsset(id, "index.html"))) {
                var content = new ByteArrayOutputStream();
                byte[] buffer = new byte[256];
                for (int count; (count = asset.read(buffer)) != -1;) content.write(buffer, 0, count);
                assertTrue(content.toString("UTF-8").contains("Runtime fixture"));
            }
            assertThrows(IllegalStateException.class, () -> service.openRuntimeWebAsset(id, "../module.prop"));
            assertEquals(id, service.runtimeWebCommand(id, "printf '%s' \"$AXERON_PLUGIN\"", null).getString("stdout"));
            service.controlRuntimePlugin(id, "disable");
            assertThrows(IllegalStateException.class, () -> service.controlRuntimePlugin(id, "action"));
            service.controlRuntimePlugin(id, "enable");
            assertEquals(0, service.controlRuntimePlugin(id, "action").getInt("exit"));
            service.controlRuntimePlugin(id, "remove");
            for (Bundle plugin : service.runtimePlugins()) assertNotEquals(id, plugin.getString("id"));
            exerciseShevery(service, file, id + "_sv", status.getInt("uid"));
            exerciseAxManagerWebUi(service, status.getInt("uid"));
        } finally {
            for (Bundle plugin : service.runtimePlugins())
                if (id.equals(plugin.getString("id"))) service.controlRuntimePlugin(id, "remove");
            Files.deleteIfExists(file.toPath());
        }
    }

    private static void exerciseShevery(IKizukuService host, File file, String id, int uid) throws Exception {
        try (var zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            write(zip, "wrapped/module.prop", "id=" + id + "\nname=Shevery lifecycle\nusesShellBridge=true\n");
            write(zip, "wrapped/common/action.sh", "printf action-ok\n");
            write(zip, "wrapped/common/service.sh", "printf service-ok\n");
            write(zip, "wrapped/web/index.html", "<!doctype html><title>Shevery lifecycle</title>");
        }
        boolean installed = false;
        try {
            Bundle module = install(host, file);
            installed = true;
            assertEquals("shevery", module.getString("format"));
            assertEquals(id, module.getString("id"));
            assertFalse(module.getBoolean("background"));
            assertEquals("action-ok", host.controlRuntimePlugin(id, "action").getString("stdout"));
            assertTrue(host.controlRuntimePlugin(id, "action-log").getString("module_log").contains("action-ok"));
            assertEquals("service-ok", host.controlRuntimePlugin(id, "run-service").getString("stdout"));
            host.controlRuntimePlugin(id, "background-on");
            assertTrue(findPlugin(host, id).getBoolean("background"));
            host.controlRuntimePlugin(id, "background-off");
            assertFalse(findPlugin(host, id).getBoolean("background"));
            String readOnly = host.controlRuntimePlugin(id, "web-read").getString("web_session");
            assertThrows(SecurityException.class, () -> host.runtimeWebSessionCommand(id, readOnly, "id -u", ""));
            String shell = host.controlRuntimePlugin(id, "web-shell").getString("web_session");
            assertEquals(Integer.toString(uid), host.runtimeWebSessionCommand(id, shell, "id -u", "").getString("stdout").trim());
            assertThrows(IllegalStateException.class, () -> host.runtimeWebSessionCommand(id, shell, "pwd", "{\"cwd\":\"../\"}"));
            host.controlRuntimePlugin(id, "disable");
            assertThrows(SecurityException.class, () -> host.openRuntimeWebSessionAsset(id, shell, "index.html"));
        } finally {
            if (installed) host.controlRuntimePlugin(id, "remove");
        }

        String fixture = InstrumentationRegistry.getArguments().getString("shevery_fixture");
        if (fixture == null) return;
        assertEquals("/data/local/tmp/kizuku-sysinfo.zip", fixture);
        final String sysinfo = "sysinfo";
        try (var archive = new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand("cat " + fixture));
             var out = Files.newOutputStream(file.toPath())) {
            archive.transferTo(out);
        }
        assertEquals(sysinfo, install(host, file).getString("id"));
        try {
            Bundle output = host.controlRuntimePlugin(sysinfo, "action");
            assertEquals(output.getString("stderr"), 0, output.getInt("exit"));
            assertTrue(output.getString("stdout").contains("module=sysinfo"));
            assertTrue(output.getString("stdout").contains("MemTotal"));
            verifyWebView(sysinfo, "document.title === 'Sys Info' && typeof Shizuku !== 'undefined' && "
                + "JSON.parse(Shizuku.getModuleInfo()).id === 'sysinfo' && "
                + "JSON.parse(Shizuku.exec('id -u')).stdout.trim() === '" + uid + "'");
        } finally {
            host.controlRuntimePlugin(sysinfo, "remove");
        }
    }

    private static Bundle findPlugin(IKizukuService host, String id) throws Exception {
        for (Bundle row : host.runtimePlugins()) if (id.equals(row.getString("id"))) return row;
        throw new AssertionError("Module is missing: " + id);
    }

    private static void exerciseAxManagerWebUi(IKizukuService host, int uid) throws Exception {
        File fixture = new File("/data/local/tmp/kizuku-axmanager-webui.zip");
        if (!fixture.isFile()) return;
        String plugin = "test_function";
        Bundle installed = install(host, fixture);
        assertEquals(plugin, installed.getString("id"));
        assertFalse(installed.getString("startup_error"), installed.containsKey("startup_error"));
        try {
            Bundle action = host.controlRuntimePlugin(plugin, "action");
            assertEquals(action.getString("stderr"), 0, action.getInt("exit"));
            assertTrue(host.controlRuntimePlugin(plugin, "action-log").getString("module_log").contains("exit 0"));
            String verifyOriginalBridge = """
                (() => {
                  if (document.title !== 'Test Function' || typeof axeronExecAsync !== 'function') return false;
                  if (!window.kizukuTestCommand) {
                    window.kizukuTestCommand = true;
                    axeronExecAsync('id -u', {}).then(result => { window.kizukuTestResult = result; });
                  }
                  return window.kizukuTestResult?.errno === 0 &&
                         window.kizukuTestResult.stdout.trim() === '%d';
                })()
                """.formatted(uid);
            verifyWebView(plugin, verifyOriginalBridge);
            String verifyStreaming = """
                (() => {
                  if (!window.kizukuSpawnStarted) {
                    window.kizukuSpawnStarted = true;
                    const child = spawnDirect('sh', ['-c', 'rm -f run/stream-release; printf STREAM_ONE; while [ ! -f run/stream-release ]; do sleep 0.05; done; printf STREAM_TWO; exit 19'], {});
                    window.kizukuChunks = [];
                    child.stdout.on('data', value => {
                      window.kizukuChunks.push(value);
                      if (window.kizukuChunks.join('').includes('STREAM_ONE') && !window.kizukuReleased) {
                        window.kizukuReleased = true;
                        axeronExecAsync('touch run/stream-release', {});
                      }
                    });
                    child.on('error', value => { window.kizukuSpawnError = String(value); });
                    child.on('exit', code => { window.kizukuSpawnExit = code; });
                  }
                  return window.kizukuSpawnExit === 19 && window.kizukuChunks.length >= 2 &&
                         window.kizukuChunks.join('').includes('STREAM_ONESTREAM_TWO');
                })()
                """;
            verifyWebView(plugin, verifyStreaming);
            // Run the original plugin's own helper tests without rewriting its JavaScript.
            verifyWebView(plugin, """
                (() => {
                  if (typeof tests === 'undefined') return false;
                  if (!window.kizukuHelperStarted) {
                    window.kizukuHelperStarted = true;
                    window.kizukuHelperLogs = [];
                    Promise.all(['kernelsu-module', 'axeron-module'].map(id =>
                      tests.find(test => test.id === id).run(text => window.kizukuHelperLogs.push(text))))
                      .then(results => { window.kizukuHelpersPassed = results.every(Boolean); })
                      .catch(error => { window.kizukuHelperLogs.push(String(error)); });
                  }
                  return window.kizukuHelpersPassed === true;
                })()
                """);
            // Update the exact upstream plugin, exercise restart and validate the API's JSON query.
            String previousPids = pluginDaemonPids(host, plugin);
            assertTrue(install(host, fixture).getBoolean("updated"));
            assertProcessesStopped(host, previousPids);
            // The original service deliberately creates an env -i child with no marker.
            String restartedPids = pluginDaemonPids(host, plugin);
            host.controlRuntimePlugin(plugin, "disable");
            assertProcessesStopped(host, restartedPids);
            host.controlRuntimePlugin(plugin, "enable");
        } finally {
            host.controlRuntimePlugin(plugin, "remove");
        }
    }

    private static String pluginDaemonPids(IKizukuService host, String plugin) throws Exception {
        String directory = findPlugin(host, plugin).getString("moduleDir");
        long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
        do {
            Bundle output = host.runtimeCommand("cat " + directory + "/run/heartbeat.pid " + directory + "/run/clean-env.pid");
            String pids = output.getString("stdout", "").trim().replace('\n', ' ');
            if (output.getInt("exit") == 0 && pids.matches("[1-9][0-9]* [1-9][0-9]*")) return pids;
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The original plugin service did not create its two real processes");
    }

    private static void assertProcessesStopped(IKizukuService host, String pids) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
        do {
            Bundle output = host.runtimeCommand("for pid in " + pids + "; do " +
                "[ ! -e /proc/$pid/stat ] || [ \"$(cut -d' ' -f3 /proc/$pid/stat)\" = Z ] || exit 1; done");
            if (output.getInt("exit") == 0) return;
            android.os.SystemClock.sleep(100);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Plugin update/disable left a daemon or its clean-environment child running");
    }

    private static void verifyWebView(String plugin, String expression) throws Exception {
        var tests = InstrumentationRegistry.getInstrumentation();
        var intent = new Intent(tests.getTargetContext(), PluginWebActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("plugin", plugin)
                .putExtra("shell", true);
        var activity = tests.startActivitySync(intent);
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + 15_000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                var completed = new CountDownLatch(1);
                var answer = new AtomicReference<String>();
                tests.runOnMainSync(() -> {
                    android.view.ViewGroup page = activity.findViewById(android.R.id.content);
                    if (page == null || !(page.getChildAt(0) instanceof WebView view)) {
                        completed.countDown();
                        return;
                    }
                    view.evaluateJavascript(expression, value -> {
                        answer.set(value);
                        completed.countDown();
                        });
                });
                if (completed.await(3, TimeUnit.SECONDS) && "true".equals(answer.get())) return;
                android.os.SystemClock.sleep(250);
            }
            var diagnostics = new AtomicReference<String>();
            var ready = new CountDownLatch(1);
            tests.runOnMainSync(() -> {
                android.view.ViewGroup page = activity.findViewById(android.R.id.content);
                if (page != null && page.getChildAt(0) instanceof WebView view) {
                    view.evaluateJavascript("JSON.stringify({title:document.title,ready:document.readyState," +
                        "exec:window.kizukuTestResult,spawnExit:window.kizukuSpawnExit,spawnError:window.kizukuSpawnError," +
                        "chunks:window.kizukuChunks,helpers:window.kizukuHelperLogs})", value -> {
                            diagnostics.set(value); ready.countDown();
                        });
                } else ready.countDown();
            });
            ready.await(3, TimeUnit.SECONDS);
            fail(plugin + " did not render and execute through its original WebUI bridge: " + diagnostics.get());
        } finally {
            tests.runOnMainSync(activity::finish);
        }
    }

    private static Bundle install(IKizukuService host, File file) throws Exception {
        try (var archive = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return host.installRuntimePlugin(archive);
        }
    }

    private static void write(ZipOutputStream zip, String path, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
