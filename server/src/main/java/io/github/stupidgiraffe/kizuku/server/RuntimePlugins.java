package io.github.stupidgiraffe.kizuku.server;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** ZIP modules have explicit install, enable, action and uninstall lifecycles. */
final class RuntimePlugins {
    private static final int MAX_PLUGINS = 96;
    private static final long MAX_ARCHIVE = 128L * 1024 * 1024;
    private static final long MAX_EXPANDED = 384L * 1024 * 1024;
    private static final String CUSTOMIZE = """
        ui_print() { printf '%s\n' "$*"; }
        abort() { ui_print "$*"; exit 1; }
        set_perm() {
            chown "$2:$3" "$1" 2>/dev/null || :
            chmod "$4" "$1" || return
            if [ -n "${5:-}" ]; then chcon "$5" "$1"; fi
        }
        . "$MODPATH/customize.sh"
        """;
    private final AxRuntime runtime;
    private final File root;

    RuntimePlugins(AxRuntime runtime) throws Exception {
        this.runtime = runtime;
        root = new File(runtime.home, "plugins");
        AxRuntime.secureDirectory(root);
        for (File file : entries(root)) {
            if (!file.getName().startsWith(".previous-")) continue;
            String id = file.getName().substring(".previous-".length());
            if (!validId(id)) continue;
            File target = new File(root, id);
            if (target.exists()) delete(file.toPath());
            else Os.rename(file.getPath(), target.getPath());
        }
        for (File file : entries(runtime.cache))
            if (file.getName().startsWith("install-")) delete(file.toPath());
        updatePath();
    }

    synchronized Bundle[] list() {
        var rows = new ArrayList<Bundle>();
        for (File file : entries(root)) {
            if (!validId(file.getName()) || !file.isDirectory()) continue;
            try {
                if (!file.equals(file.getCanonicalFile())) continue;
                Bundle row = info(file);
                if (!file.getName().equals(row.getString("id"))) throw new IOException("Module ID changed");
                row.putString("moduleDir", file.getPath());
                row.putBoolean("enabled", enabled(file));
                row.putBoolean("action", new File(file, "action.sh").isFile());
                row.putBoolean("webui", new File(file, "webroot/index.html").isFile());
                rows.add(row);
            } catch (IOException | IllegalArgumentException failure) {
                Bundle row = new Bundle();
                row.putString("id", file.getName()); row.putString("name", file.getName());
                row.putString("error", failure.getMessage());
                rows.add(row);
            }
        }
        return rows.toArray(new Bundle[0]);
    }

    synchronized Bundle install(ParcelFileDescriptor input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Select a plugin ZIP first");
        try (InputStream source = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
            File stage = Files.createTempDirectory(runtime.cache.toPath(), "install-").toFile();
            try {
                File zip = new File(stage, "source.zip");
                try (OutputStream out = Files.newOutputStream(zip.toPath())) { copy(source, out, MAX_ARCHIVE); }
                File module = new File(stage, "module");
                AxRuntime.secureDirectory(module);
                unpack(zip, module);
                Bundle row = info(module);
                String id = row.getString("id");
                File target = new File(root, id);
                boolean update = target.exists();
                if (!update && list().length >= MAX_PLUGINS) throw new IOException("Plugin limit reached");
                if (update && !target.equals(target.getCanonicalFile())) throw new IOException("Invalid existing plugin directory");
                File setup = new File(module, "customize.sh");
                if (setup.isFile()) {
                    if (setup.length() > 128 * 1024) throw new IOException("Plugin installer is too large");
                    String script = new String(Files.readAllBytes(setup.toPath()), StandardCharsets.UTF_8);
                    if (java.util.regex.Pattern.compile("(?m)^\\s*(?:export\\s+)?SKIPUNZIP\\s*=\\s*1\\b").matcher(script).find())
                        throw new IOException("This plugin requires SKIPUNZIP");
                    Map<String, String> env = new HashMap<>();
                    String abi = android.os.Build.SUPPORTED_ABIS[0];
                    String arch = switch (abi) {
                        case "arm64-v8a" -> "arm64"; case "armeabi-v7a" -> "arm";
                        case "x86_64" -> "x64"; default -> "x86";
                    };
                    env.put("BOOTMODE", "true"); env.put("MODPATH", module.getPath()); env.put("ZIPFILE", zip.getPath());
                    env.put("API", Integer.toString(android.os.Build.VERSION.SDK_INT));
                    env.put("ARCH", arch); env.put("IS64BIT", Boolean.toString(arch.endsWith("64")));
                    requireSuccess(runtime.run(id, new String[]{AxRuntime.SHELL, "-c", CUSTOMIZE}, module, env, 25));
                    if (!id.equals(info(module).getString("id"))) throw new IOException("Installer changed the module ID");
                }
                if (update && !enabled(target)) new File(module, "disable").createNewFile();
                File previous = new File(root, ".previous-" + id);
                runtime.stopPlugin(id);
                if (update) Os.rename(target.getPath(), previous.getPath());
                try { Os.rename(module.getPath(), target.getPath()); }
                catch (Exception error) {
                    if (update) Os.rename(previous.getPath(), target.getPath());
                    throw error;
                }
                delete(previous.toPath());
                updatePath();
                if (enabled(target)) {
                    try { start(id, target); }
                    catch (Exception failure) {
                        new File(target, "disable").createNewFile();
                        runtime.stopPlugin(id);
                        updatePath();
                        row.putString("startup_error", failure.getMessage());
                    }
                }
                row.putBoolean("updated", update);
                return row;
            } finally { delete(stage.toPath()); }
        }
    }

    synchronized Bundle control(String id, String action) throws Exception {
        File directory = plugin(id);
        File disabled = new File(directory, "disable");
        switch (action) {
            case "enable" -> {
                boolean changed = Files.deleteIfExists(disabled.toPath());
                updatePath();
                if (changed) try { start(id, directory); }
                catch (Exception error) { disabled.createNewFile(); runtime.stopPlugin(id); updatePath(); throw error; }
            }
            case "disable" -> {
                if (enabled(directory) && !disabled.createNewFile()) throw new IOException("Cannot disable plugin");
                runtime.stopPlugin(id);
                updatePath();
            }
            case "restart" -> {
                requireEnabled(directory);
                runtime.stopPlugin(id);
                start(id, directory);
            }
            case "action" -> {
                requireEnabled(directory);
                Bundle result = script(id, directory, "action.sh");
                if (result == null) throw new IOException("No action script for this plugin");
                return result;
            }
            case "remove" -> {
                runtime.stopPlugin(id);
                Bundle result = script(id, directory, "uninstall.sh");
                if (result != null) requireSuccess(result);
                delete(directory.toPath());
                updatePath();
            }
            default -> throw new IllegalArgumentException("Invalid plugin operation");
        }
        Bundle result = new Bundle();
        result.putString("id", id);
        return result;
    }

    synchronized void startEnabled() {
        for (Bundle row : list()) {
            if (!row.getBoolean("enabled")) continue;
            String id = row.getString("id");
            try { start(id, plugin(id)); }
            catch (Exception failure) { Log.w("KizukuRuntime", "Cannot start plugin " + id, failure); }
        }
    }

    synchronized ParcelFileDescriptor asset(String id, String relative) throws Exception {
        File directory = plugin(id);
        requireEnabled(directory);
        File web = child(directory, "webroot");
        if (web.equals(directory)) throw new SecurityException("Invalid WebUI directory");
        File resource = child(web, relative);
        if (!resource.isFile() || resource.length() > 16 * 1024 * 1024) return null;
        // A regular file under the root host's data directory cannot be passed to
        // an unprivileged app on every SELinux policy. The host serves it over a pipe.
        return runtime.readAsset(resource);
    }

    synchronized Bundle webCommand(String id, String command, String cwd) throws Exception {
        File directory = plugin(id);
        requireEnabled(directory);
        File work = directory;
        if (cwd != null && !cwd.isEmpty()) {
            File requested = new File(cwd);
            work = requested.isAbsolute() ? requested.getCanonicalFile() : child(directory, cwd);
            if (!work.toPath().startsWith(directory.toPath()) || !work.isDirectory())
                throw new SecurityException("WebUI working directory must be inside the plugin");
        }
        return runtime.run(id, new String[]{AxRuntime.SHELL, "-c", AxRuntime.validate(command)}, work, null, 15);
    }

    private void start(String id, File directory) throws Exception {
        Bundle result = script(id, directory, "post-fs-data.sh");
        if (result != null) requireSuccess(result);
        File service = child(directory, "service.sh");
        if (service.isFile()) runtime.service(id, service, directory);
    }

    private Bundle script(String id, File directory, String name) throws Exception {
        File script = child(directory, name);
        return script.isFile() ? runtime.run(id, new String[]{AxRuntime.SHELL, script.getPath()}, directory, null, 20) : null;
    }

    private static void requireSuccess(Bundle result) throws IOException {
        if (result.getInt("exit") == 0) return;
        String detail = result.getString("stderr", "").trim();
        if (detail.isEmpty()) detail = result.getString("stdout", "").trim();
        throw new IOException("Plugin script exited " + result.getInt("exit")
            + (detail.isEmpty() ? "" : ": " + detail.substring(0, Math.min(240, detail.length()))));
    }

    private File plugin(String id) throws IOException {
        if (!validId(id)) throw new IllegalArgumentException("Invalid plugin ID");
        File directory = new File(root, id);
        if (!directory.isDirectory() || !directory.getCanonicalFile().equals(directory)) throw new IOException("Plugin unavailable");
        return directory;
    }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z][A-Za-z0-9._-]{1,79}"); }
    private static boolean enabled(File directory) { return !new File(directory, "disable").exists(); }
    private static void requireEnabled(File directory) throws IOException {
        if (!enabled(directory)) throw new IOException("Enable the plugin first");
    }

    private static Bundle info(File directory) throws IOException {
        File metadata = child(directory, "module.prop");
        if (!metadata.isFile() || metadata.length() > 8192) throw new IOException("Missing root-level module.prop");
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(metadata.toPath(), StandardCharsets.UTF_8)) { values.load(reader); }
        String id = values.getProperty("id", "");
        if (!validId(id)) throw new IOException("Invalid module ID");
        try {
            int minimum = Integer.parseInt(values.getProperty("axeronPlugin", ""));
            if (minimum < 1 || minimum > 30000) throw new IOException("Plugin requires a different Axeron version");
            if (Integer.parseInt(values.getProperty("versionCode", "")) < 0) throw new NumberFormatException();
        } catch (NumberFormatException invalid) { throw new IOException("versionCode and axeronPlugin must be numeric"); }
        Bundle result = new Bundle();
        result.putString("id", id);
        for (String key : new String[]{"name", "version", "author", "description"}) {
            String value = values.getProperty(key, key.equals("name") ? id : "").replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
            result.putString(key, value.substring(0, Math.min(value.length(), 240)));
        }
        return result;
    }

    private void updatePath() throws Exception {
        for (File file : entries(runtime.bins)) Files.deleteIfExists(file.toPath());
        for (Bundle row : list()) {
            if (!row.getBoolean("enabled")) continue;
            File executables = child(plugin(row.getString("id")), "system/bin");
            for (File file : entries(executables)) {
                File real = file.getCanonicalFile();
                if (!real.isFile() || !real.toPath().startsWith(executables.toPath())) continue;
                File link = new File(runtime.bins, file.getName());
                if (link.exists()) continue;
                Os.chmod(real.getPath(), 0700);
                Os.symlink(real.getPath(), link.getPath());
            }
        }
    }

    private static void unpack(File archive, File directory) throws IOException {
        long remaining = MAX_EXPANDED;
        Set<String> names = new HashSet<>();
        try (var zip = new ZipInputStream(Files.newInputStream(archive.toPath()))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
                if (!names.add(name) || names.size() > 8192) throw new IOException("Invalid or excessive ZIP entries");
                File destination = child(directory, name);
                if (entry.isDirectory()) Files.createDirectories(destination.toPath());
                else {
                    Files.createDirectories(destination.getParentFile().toPath());
                    try (OutputStream out = Files.newOutputStream(destination.toPath(), StandardOpenOption.CREATE_NEW)) {
                        remaining -= copy(zip, out, remaining);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static File child(File base, String relative) throws IOException {
        if (relative == null || relative.isEmpty() || relative.length() > 512
                || relative.indexOf('\\') >= 0 || relative.indexOf(0) >= 0)
            throw new IOException("Invalid plugin path");
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Invalid plugin path");
        }
        File target = new File(base, relative).getCanonicalFile();
        if (!target.toPath().startsWith(base.getCanonicalFile().toPath())) throw new IOException("Plugin path escapes its directory");
        return target;
    }

    private static File[] entries(File directory) {
        File[] children = directory.listFiles();
        if (children == null) return new File[0];
        Arrays.sort(children, Comparator.comparing(File::getName));
        return children;
    }

    private static void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        try (var tree = Files.walk(path)) {
            for (Path child : tree.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(child);
        }
    }

    private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[32768];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > limit) throw new IOException("Plugin ZIP exceeds the size limit");
            output.write(buffer, 0, count);
        }
        return total;
    }
}
