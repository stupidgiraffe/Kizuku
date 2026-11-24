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
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** ZIP modules have explicit install, enable, action and uninstall lifecycles. */
final class RuntimePlugins {
    private static final int MAX_PLUGINS = 96;
    private static final long MAX_ARCHIVE = 128L * 1024 * 1024;
    private static final long MAX_EXPANDED = 384L * 1024 * 1024;
    private static final String BACKGROUND = ".kizuku-background";
    private static final Set<String> RESERVED_WEB_ENV = new HashSet<>(Arrays.asList(
        "PATH", "IFS", "ENV", "BASH_ENV", "MODDIR", "MODPATH", "ASH_STANDALONE", "ARCH", "KIZUKU_AXERON_PROCESS"));
    private static final String SHEVERY_SCRIPT = "ui_print() { printf '%s\\n' \"$*\"; }; . \"$0\"";
    private static final String CUSTOMIZE = """
        ui_print() { printf '%s\n' "$*"; }
        abort() { ui_print "$*"; exit 1; }
        set_perm() {
            chown "$2:$3" "$1" 2>/dev/null || :
            chmod "$4" "$1" || return
            if [ -n "${5:-}" ]; then chcon "$5" "$1"; fi
        }
        set_perm_recursive() {
            chown -R "$2:$3" "$1" 2>/dev/null || :
            find "$1" -type d -exec chmod "$4" {} \\;
            find "$1" -type f -exec chmod "$5" {} \\;
        }
        . "$MODPATH/customize.sh"
        """;
    private final AxRuntime runtime;
    private final File root;
    private final Map<String, WebSession> webSessions = new HashMap<>();
    private final Map<String, String> serviceRuns = new HashMap<>();

    private record WebSession(String token, boolean shell) {}

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
                row.putBoolean("background", isShevery(row) && new File(file, BACKGROUND).isFile());
                row.putBoolean("service_running", runtime.pluginRunning(row.getString("id")));
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
                File extracted = new File(stage, "module");
                AxRuntime.secureDirectory(extracted);
                unpack(zip, extracted);
                File module = payloadRoot(extracted);
                Bundle row = info(module);
                String id = row.getString("id");
                File target = new File(root, id);
                boolean update = target.exists();
                if (!update && list().length >= MAX_PLUGINS) throw new IOException("Plugin limit reached");
                if (update && !target.equals(target.getCanonicalFile())) throw new IOException("Invalid existing plugin directory");
                File setup = new File(module, "customize.sh");
                if (isShevery(row)) {
                    if (!row.getBoolean("action") && !row.getBoolean("service") && !row.getBoolean("webui"))
                        throw new IOException("No compatible action, service or WebUI found");
                    try (var paths = Files.find(module.toPath(), 64, (path, attrs) ->
                            attrs.isRegularFile() && path.getFileName().toString().endsWith(".sh"))) {
                        for (Path path : paths.toArray(Path[]::new)) Os.chmod(path.toString(), 0700);
                    }
                } else if (setup.isFile()) {
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
                    env.put("ABI", abi); env.put("ABI32", android.os.Build.SUPPORTED_32_BIT_ABIS.length == 0 ? "" : android.os.Build.SUPPORTED_32_BIT_ABIS[0]);
                    env.put("MODID", id); env.put("MODNAME", row.getString("name", id)); env.put("MODAUTH", row.getString("author", ""));
                    requireSuccess(runtime.run(id, new String[]{AxRuntime.SHELL, "-c", CUSTOMIZE}, module, env, 25));
                    if (!id.equals(info(module).getString("id"))) throw new IOException("Installer changed the module ID");
                }
                if (update && !enabled(target)) new File(module, "disable").createNewFile();
                File previous = new File(root, ".previous-" + id);
                invalidate(id);
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
        Bundle row = info(directory);
        switch (action) {
            case "enable" -> {
                boolean changed = Files.deleteIfExists(disabled.toPath());
                updatePath();
                if (changed) try { start(id, directory); }
                catch (Exception error) { disabled.createNewFile(); runtime.stopPlugin(id); updatePath(); throw error; }
            }
            case "disable" -> {
                if (enabled(directory) && !disabled.createNewFile()) throw new IOException("Cannot disable plugin");
                invalidate(id);
                runtime.stopPlugin(id);
                updatePath();
            }
            case "restart" -> {
                requireEnabled(directory);
                serviceRuns.remove(id);
                runtime.stopPlugin(id);
                start(id, directory);
            }
            case "action" -> {
                requireEnabled(directory);
                Bundle result = isShevery(row)
                    ? moduleScript(id, directory, row, "action")
                    : script(id, directory, "action.sh");
                if (result == null) throw new IOException("No action script for this plugin");
                return result;
            }
            case "run-service" -> {
                requireEnabled(directory);
                if (!isShevery(row)) throw new IOException("Use Restart for Axeron services");
                serviceRuns.remove(id);
                runtime.stopPlugin(id);
                Bundle result = moduleScript(id, directory, row, "service");
                if (result == null) throw new IOException("No service script for this module");
                return result;
            }
            case "background-on" -> {
                requireEnabled(directory);
                if (!isShevery(row) || !row.getBoolean("service")) throw new IOException("No service script for this module");
                File approval = child(directory, BACKGROUND);
                if (approval.createNewFile()) {
                    try { start(id, directory); }
                    catch (Exception failure) { Files.deleteIfExists(approval.toPath()); throw failure; }
                }
            }
            case "background-off" -> {
                if (!isShevery(row)) throw new IOException("Background policy is for Shevery modules");
                Files.deleteIfExists(child(directory, BACKGROUND).toPath());
                serviceRuns.remove(id);
                runtime.stopPlugin(id);
            }
            case "action-log" -> { return lastLog(directory, "action"); }
            case "service-log" -> { return lastLog(directory, "service"); }
            case "post-fs-data-log" -> { return lastLog(directory, "post-fs-data"); }
            case "web-read", "web-shell" -> {
                requireEnabled(directory);
                if (!row.getBoolean("webui")) throw new IOException("This plugin has no WebUI");
                boolean shell = action.equals("web-shell");
                if (shell && isShevery(row) && !row.getBoolean("declares_shell"))
                    throw new SecurityException("This module did not declare WebUI shell support");
                WebSession session = new WebSession(UUID.randomUUID().toString(), shell);
                webSessions.put(id, session);
                row.putString("web_session", session.token());
                row.putInt("hostUid", android.os.Process.myUid());
                row.putString("moduleDir", directory.getPath());
                row.putString("webRoot", child(directory, row.getString("web_path")).getPath());
                row.putBoolean("enabled", true);
                row.putBoolean("background", isShevery(row) && new File(directory, BACKGROUND).isFile());
                return row;
            }
            case "remove" -> {
                invalidate(id);
                runtime.stopPlugin(id);
                Bundle result = isShevery(row) ? null : script(id, directory, "uninstall.sh");
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
        Bundle row = info(directory);
        if (!row.getBoolean("webui")) return null;
        File web = child(directory, row.getString("web_path"));
        File resource = child(web, relative);
        if (!resource.isFile() || resource.length() > 16 * 1024 * 1024) return null;
        // A regular file under the root host's data directory cannot be passed to
        // an unprivileged app on every SELinux policy. The host serves it over a pipe.
        return runtime.readAsset(resource);
    }

    synchronized Bundle webCommand(String id, String command, String cwd) throws Exception {
        File directory = plugin(id);
        requireEnabled(directory);
        if (isShevery(info(directory))) throw new SecurityException("Open the module WebUI with shell access first");
        File work = directory;
        if (cwd != null && !cwd.isEmpty()) {
            File requested = new File(cwd);
            work = requested.isAbsolute() ? requested.getCanonicalFile() : child(directory, cwd);
            if (!work.toPath().startsWith(directory.toPath()) || !work.isDirectory())
                throw new SecurityException("WebUI working directory must be inside the plugin");
        }
        return runtime.run(id, new String[]{AxRuntime.SHELL, "-c", AxRuntime.validate(command)}, work, null, 15);
    }

    synchronized void closeWebSessions() { webSessions.clear(); }

    synchronized ParcelFileDescriptor webSessionAsset(String id, String token, String resource) throws Exception {
        requireSession(id, token);
        return asset(id, resource);
    }

    Bundle webSessionCommand(String id, String token, String command, String rawOptions) throws Exception {
        return prepareWebSessionCommand(id, token, command, rawOptions).call();
    }

    private synchronized java.util.concurrent.Callable<Bundle> prepareWebSessionCommand(
            String id, String token, String command, String rawOptions) throws Exception {
        WebSpec spec = webSpec(id, token, rawOptions);
        return runtime.prepare(id, new String[]{AxRuntime.SHELL, "-c", AxRuntime.validate(command)},
            spec.directory(), spec.environment(), spec.timeout(), spec.stdin());
    }

    private record WebSpec(File directory, Map<String, String> environment, int timeout, byte[] stdin) {}

    synchronized frb.axeron.server.IAxshProcess webSpawn(io.github.stupidgiraffe.kizuku.core.ClientIdentity owner,
            String id, String token, String command, String arguments, String options,
            ParcelFileDescriptor stdin, ParcelFileDescriptor stdout, ParcelFileDescriptor stderr,
            ParcelFileDescriptor status) throws Exception {
        WebSpec spec = webSpec(id, token, options);
        org.json.JSONArray args = new org.json.JSONArray(arguments);
        if (args.length() > 128) throw new IllegalArgumentException("Too many WebUI arguments");
        String[] argv = new String[args.length() + 1];
        argv[0] = AxRuntime.validate(command);
        for (int i = 0; i < args.length(); i++) argv[i + 1] = args.getString(i);
        String[] env = spec.environment().entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return runtime.launch(owner, id, argv, env, spec.directory().getPath(), stdin, stdout, stderr, false, 0, status);
    }

    private WebSpec webSpec(String id, String token, String rawOptions) throws Exception {
        if (!requireSession(id, token).shell()) throw new SecurityException("This WebUI was opened without shell access");
        File directory = plugin(id);
        requireEnabled(directory);
        Bundle row = info(directory);
        if (isShevery(row) && !row.getBoolean("declares_shell")) throw new SecurityException("WebUI shell support was removed");
        if (rawOptions != null && rawOptions.length() > 196_608) throw new IllegalArgumentException("WebUI options are too large");
        JSONObject options = rawOptions == null || rawOptions.trim().isEmpty() ? new JSONObject() : new JSONObject(rawOptions);
        byte[] stdin = options.optString("stdin", "").getBytes(StandardCharsets.UTF_8);
        if (stdin.length > 65_536) throw new IllegalArgumentException("WebUI stdin exceeds 64 KiB");
        int timeout = Math.max(1, Math.min(120, options.optInt("timeoutSeconds", isShevery(row) ? 60 : 15)));
        String cwd = options.optString("cwd", "");
        File work = cwd.isEmpty() ? directory
            : isShevery(row) || !new File(cwd).isAbsolute() ? child(directory, cwd)
            : new File(cwd).getCanonicalFile();
        if (!work.isDirectory() || isShevery(row) && !work.toPath().startsWith(directory.toPath()))
            throw new SecurityException("Invalid WebUI working directory");
        Map<String, String> env = moduleEnv(id, directory, work);
        JSONObject requested = options.optJSONObject("env");
        if (requested != null) {
            if (requested.length() > 32) throw new IllegalArgumentException("Too many WebUI environment values");
            for (var it = requested.keys(); it.hasNext();) {
                String key = it.next();
                String value = requested.getString(key);
                if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || value.length() > 4096 || value.indexOf(0) >= 0)
                    throw new IllegalArgumentException("Invalid WebUI environment value");
                String upper = key.toUpperCase(java.util.Locale.ROOT);
                if (RESERVED_WEB_ENV.contains(upper) || upper.startsWith("LD_") || upper.startsWith("SHIZUKU_"))
                    throw new SecurityException("Reserved WebUI environment key: " + key);
                env.put(key, value);
            }
        }
        return new WebSpec(work, env, timeout, stdin);
    }

    private WebSession requireSession(String id, String token) {
        WebSession session = webSessions.get(id);
        if (session == null || !session.token().equals(token))
            throw new SecurityException("This WebUI session ended. Reopen it from Modules.");
        return session;
    }

    private void invalidate(String id) {
        webSessions.remove(id);
        serviceRuns.remove(id);
    }

    private void start(String id, File directory) throws Exception {
        Bundle row = info(directory);
        if (isShevery(row)) {
            String relative = row.getString("service_path");
            if (relative == null || !new File(directory, BACKGROUND).isFile()) return;
            File service = child(directory, relative);
            String run = UUID.randomUUID().toString();
            serviceRuns.put(id, run);
            try {
                runtime.moduleService(id, moduleArgs(service), directory, moduleEnv(id, directory, directory),
                    result -> {
                        synchronized (this) {
                            if (!run.equals(serviceRuns.get(id))) return;
                            serviceRuns.remove(id);
                            try { writeLog(directory, "service", result); }
                            catch (IOException failure) { Log.w("KizukuRuntime", "Cannot write module service log", failure); }
                        }
                    });
            } catch (Exception failure) { serviceRuns.remove(id); throw failure; }
            return;
        }
        File properties = child(directory, "system.prop");
        if (properties.isFile()) {
            if (properties.length() > 8192) throw new IOException("system.prop is too large");
            for (String line : Files.readAllLines(properties.toPath(), StandardCharsets.UTF_8)) {
                String clean = line.trim();
                if (clean.isEmpty() || clean.startsWith("#")) continue;
                int separator = clean.indexOf('=');
                if (separator < 1) throw new IOException("Invalid system property");
                String key = clean.substring(0, separator).trim();
                String value = clean.substring(separator + 1).trim();
                if (!key.matches("[A-Za-z0-9_.-]{1,92}") || value.indexOf(0) >= 0)
                    throw new IOException("Invalid system property");
                requireSuccess(runtime.run(id, new String[]{"/system/bin/setprop", key, value},
                    directory, moduleEnv(id, directory, directory), 8));
            }
        }
        Bundle startup = script(id, directory, "post-fs-data.sh");
        if (startup != null) requireSuccess(startup);
        File service = child(directory, "service.sh");
        if (service.isFile()) {
            String run = UUID.randomUUID().toString();
            serviceRuns.put(id, run);
            runtime.moduleService(id, new String[]{AxRuntime.SHELL, service.getPath()}, directory,
                moduleEnv(id, directory, directory), result -> {
                    synchronized (this) {
                        if (!run.equals(serviceRuns.get(id))) return;
                        serviceRuns.remove(id);
                        try { writeLog(directory, "service", result); }
                        catch (IOException failure) { Log.w("KizukuRuntime", "Cannot write service log", failure); }
                    }
                });
        }
    }

    private Bundle script(String id, File directory, String name) throws Exception {
        File script = child(directory, name);
        if (!script.isFile()) return null;
        String kind = name.replace(".sh", "");
        Bundle result = runtime.run(id, new String[]{AxRuntime.SHELL, script.getPath()}, directory,
            moduleEnv(id, directory, directory), 20);
        writeLog(directory, kind, result);
        return result;
    }

    private Bundle moduleScript(String id, File directory, Bundle row, String kind) throws Exception {
        String relative = row.getString(kind + "_path");
        if (relative == null) return null;
        Bundle result = runtime.run(id, moduleArgs(child(directory, relative)), directory,
            moduleEnv(id, directory, directory), 60);
        writeLog(directory, kind, result);
        return result;
    }

    private static String[] moduleArgs(File script) {
        return new String[]{AxRuntime.SHELL, "-c", SHEVERY_SCRIPT, script.getPath()};
    }

    private static Map<String, String> moduleEnv(String id, File directory, File working) {
        Map<String, String> env = new HashMap<>();
        env.put("MODID", id);
        env.put("MODDIR", directory.getPath());
        env.put("MODPATH", directory.getPath());
        env.put("SHIZUKU_EXEC_CWD", working.getPath());
        env.put("ASH_STANDALONE", "1");
        env.put("SHIZUKU_MODULE_ID", id);
        env.put("SHIZUKU_MODULE_MODE", "custom");
        env.put("SHIZUKU_MODULE_TRUSTED", "0");
        env.put("SHIZUKU_MODULE_BACKGROUND", new File(directory, BACKGROUND).isFile() ? "1" : "0");
        env.put("ARCH", android.os.Build.SUPPORTED_ABIS[0]);
        return env;
    }

    private static void writeLog(File directory, String kind, Bundle result) throws IOException {
        File log = child(directory, "logs/" + kind + "-last.log");
        Files.createDirectories(log.getParentFile().toPath());
        String message = kind + " · exit " + result.getInt("exit") + "\n"
            + result.getString("stdout", "") + "\n" + result.getString("stderr", "");
        Files.write(log.toPath(), message.getBytes(StandardCharsets.UTF_8));
    }

    private static Bundle lastLog(File directory, String kind) throws IOException {
        File log = child(directory, "logs/" + kind + "-last.log");
        Bundle result = new Bundle();
        if (log.isFile() && log.length() <= 262_144)
            result.putString("module_log", new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
        else result.putString("module_log", "No " + kind + " output yet.");
        return result;
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
        boolean shevery = !values.containsKey("axeronPlugin");
        if (shevery) {
            // Shevery treats values as literal UTF-8 strings and does not unescape Java properties.
            values.clear();
            for (String line : Files.readAllLines(metadata.toPath(), StandardCharsets.UTF_8)) {
                String clean = line.trim();
                int separator = clean.indexOf('=');
                if (!clean.startsWith("#") && separator > 0)
                    values.setProperty(clean.substring(0, separator).trim(), clean.substring(separator + 1).trim());
            }
        }
        String id = values.getProperty("id", "");
        if (!validId(id) || shevery && id.length() > 64) throw new IOException("Invalid module ID");
        long version;
        try {
            if (!shevery) {
                int minimum = Integer.parseInt(values.getProperty("axeronPlugin", ""));
                if (minimum < 1 || minimum > 30000) throw new IOException("Plugin requires a different Axeron version");
            }
            version = Long.parseLong(values.getProperty("versionCode", shevery ? "1" : ""));
            if (version < 0) throw new NumberFormatException();
        } catch (NumberFormatException invalid) { throw new IOException("Invalid module versionCode or Axeron version"); }
        Bundle result = new Bundle();
        result.putString("id", id);
        result.putString("format", shevery ? "shevery" : "axeron");
        result.putLong("versionCode", version);
        for (String key : new String[]{"name", "version", "author", "description"}) {
            String value = values.getProperty(key, key.equals("name") ? id : "").replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
            result.putString(key, value.substring(0, Math.min(value.length(), 240)));
        }
        String action = shevery
            ? firstFile(directory, values.getProperty("action"), "action.sh", "common/action.sh", "run.sh", "main.sh", "exec.sh")
            : firstFile(directory, "action.sh");
        String service = shevery
            ? firstFile(directory, values.getProperty("service"), "service.sh", "common/service.sh", "late_start.sh")
            : firstFile(directory, "service.sh");
        String web = shevery
            ? firstWebRoot(directory, values.getProperty("webui"), "webroot", "webui", "web")
            : firstWebRoot(directory, "webroot");
        result.putString("action_path", action);
        result.putString("service_path", service);
        result.putString("web_path", web);
        result.putBoolean("action", action != null);
        result.putBoolean("service", service != null);
        result.putBoolean("webui", web != null);
        result.putBoolean("declares_shell", "true".equals(values.getProperty("usesShellBridge", values.getProperty("shellBridge"))));
        return result;
    }

    private static boolean isShevery(Bundle row) { return "shevery".equals(row.getString("format")); }

    private static String firstFile(File root, String... candidates) throws IOException {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty() && child(root, candidate).isFile()) return candidate;
        }
        return null;
    }

    private static String firstWebRoot(File root, String... candidates) throws IOException {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty() && child(root, candidate + "/index.html").isFile()) return candidate;
        }
        return null;
    }

    private static File payloadRoot(File extracted) throws IOException {
        if (new File(extracted, "module.prop").isFile()) return extracted;
        try (var metadata = Files.find(extracted.toPath(), 4, (path, attrs) ->
                attrs.isRegularFile() && "module.prop".equals(path.getFileName().toString()))) {
            var candidates = metadata.sorted(Comparator.comparingInt(Path::getNameCount)).limit(2).toArray(Path[]::new);
            if (candidates.length == 0) throw new IOException("Missing module.prop");
            if (candidates.length > 1 && candidates[0].getNameCount() == candidates[1].getNameCount())
                throw new IOException("ZIP contains more than one module");
            return candidates[0].getParent().toFile();
        }
    }

    private void updatePath() throws Exception {
        for (File file : entries(runtime.bins)) Files.deleteIfExists(file.toPath());
        for (Bundle row : list()) {
            if (!row.getBoolean("enabled") || isShevery(row)) continue;
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
                if (!names.add(name) || names.size() > 8192 ||
                        Arrays.stream(name.split("/", -1)).anyMatch(part -> part.startsWith(".kizuku-")))
                    throw new IOException("Invalid or excessive ZIP entries");
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
