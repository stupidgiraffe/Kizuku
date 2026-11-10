package io.github.stupidgiraffe.kizuku;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.stupidgiraffe.kizuku.api.IKizukuService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
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
        } finally {
            for (Bundle plugin : service.runtimePlugins())
                if (id.equals(plugin.getString("id"))) service.controlRuntimePlugin(id, "remove");
            Files.deleteIfExists(file.toPath());
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
