#include <jni.h>
#include <atomic>
#include <cerrno>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <system_error>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
constexpr int TTY_IN = 1, TTY_OUT = 2, TTY_ERR = 4, RUNNING = 0x7fffffff;
struct Shell {
    pid_t pid = -1;
    int master = -1;
    int status = RUNNING;
    std::shared_ptr<std::atomic_bool> cancelled = std::make_shared<std::atomic_bool>(false);

    ~Shell() {
        *cancelled = true;
        if (master >= 0) close(master);
    }
};

void closeFd(int fd) { if (fd >= 0) close(fd); }
int duplicate(int fd) { return fcntl(fd, F_DUPFD_CLOEXEC, 3); }

void makeNonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
}

void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

std::string stringBytes(JNIEnv* env, jbyteArray bytes) {
    if (!bytes) return {};
    std::string text(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, text.size(), reinterpret_cast<jbyte*>(text.data()));
    return text;
}

std::vector<std::string> stringArray(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> strings;
    if (!array) return strings;
    auto size = env->GetArrayLength(array);
    strings.reserve(size);
    for (jsize index = 0; index < size; ++index) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(array, index));
        strings.push_back(stringBytes(env, bytes));
        env->DeleteLocalRef(bytes);
    }
    return strings;
}

std::vector<char*> pointers(std::vector<std::string>& strings) {
    std::vector<char*> result;
    result.reserve(strings.size() + 1);
    for (auto& string : strings) result.push_back(string.data());
    result.push_back(nullptr);
    return result;
}

bool ready(int fd, short events, const std::atomic_bool& cancelled) {
    while (!cancelled) {
        pollfd event{fd, events, 0};
        int result = poll(&event, 1, 100);
        if (result > 0) return (event.revents & (events | POLLHUP | POLLERR)) != 0;
        if (result < 0 && errno != EINTR) return false;
    }
    return false;
}

void relay(int from, int to, const std::shared_ptr<std::atomic_bool>& cancelled) {
    sigset_t pipeSignal;
    sigemptyset(&pipeSignal);
    sigaddset(&pipeSignal, SIGPIPE);
    pthread_sigmask(SIG_BLOCK, &pipeSignal, nullptr);

    char buffer[4096];
    while (ready(from, POLLIN, *cancelled)) {
        ssize_t count = TEMP_FAILURE_RETRY(read(from, buffer, sizeof(buffer)));
        if (count < 0 && errno == EAGAIN) continue;
        if (count <= 0) break;
        ssize_t offset = 0;
        while (offset < count && ready(to, POLLOUT, *cancelled)) {
            ssize_t written = TEMP_FAILURE_RETRY(write(to, buffer + offset, count - offset));
            if (written < 0 && errno == EAGAIN) continue;
            if (written <= 0) break;
            offset += written;
        }
        if (offset < count) break;
    }
    close(from);
    close(to);
}

int result(Shell* shell) {
    if (shell->status != RUNNING) return shell->status;
    siginfo_t state{};
    if (TEMP_FAILURE_RETRY(waitid(P_PID, shell->pid, &state, WEXITED | WNOHANG | WNOWAIT)) < 0) {
        shell->pid = -1;
        shell->status = 1;
    } else if (state.si_pid == 0) {
        return RUNNING;
    } else {
        shell->status = state.si_code == CLD_EXITED ? state.si_status : 128 + state.si_status;
    }
    return shell->status;
}

void stop(pid_t pid) {
    if (pid <= 0) return;
    // Signal the group while its unreaped leader still reserves the PID.
    kill(-pid, SIGKILL);
    kill(pid, SIGKILL);
    if (TEMP_FAILURE_RETRY(waitpid(pid, nullptr, WNOHANG)) == 0) {
        try {
            std::thread([pid]() { TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0)); }).detach();
        } catch (const std::system_error&) {
            TEMP_FAILURE_RETRY(waitpid(pid, nullptr, 0));
        }
    }
}

int terminalFd(int tty) {
    if (tty & TTY_IN) return STDIN_FILENO;
    if (tty & TTY_OUT) return STDOUT_FILENO;
    if (tty & TTY_ERR) return STDERR_FILENO;
    return -1;
}

termios original{};
int rawFd = -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_stupidgiraffe_kizuku_server_LegacyRish_start(
        JNIEnv* env, jclass, jobjectArray args, jobjectArray environment, jbyteArray directory,
        jbyte tty, jint in, jint out, jint err) {
    auto command = stringArray(env, args);
    command.insert(command.begin(), "/system/bin/sh");
    auto envStrings = stringArray(env, environment);
    auto dir = stringBytes(env, directory);
    auto argv = pointers(command);
    auto envp = pointers(envStrings);
    if (env->ExceptionCheck()) return 0;

    auto shell = std::make_unique<Shell>();
    char slave[256]{};
    const bool pipeIn = (tty & TTY_IN) != 0;
    const bool pipeOut = (tty & (TTY_OUT | TTY_ERR)) != 0;
    int terminal = -1;
    if (tty) {
        shell->master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
        if (shell->master < 0 || grantpt(shell->master) < 0 || unlockpt(shell->master) < 0
            || ptsname_r(shell->master, slave, sizeof(slave)) != 0
            || (terminal = open(slave, O_RDWR | O_NOCTTY | O_CLOEXEC)) < 0) {
            fail(env, "Could not open shell terminal");
            return 0;
        }
    }

    int inRelay = pipeIn ? duplicate(in) : -1;
    int inMaster = pipeIn ? duplicate(shell->master) : -1;
    int outRelay = pipeOut ? duplicate(out) : (tty ? open("/dev/null", O_WRONLY | O_CLOEXEC) : -1);
    int outMaster = tty ? duplicate(shell->master) : -1;
    auto closeRelays = [&]() {
        closeFd(inRelay); closeFd(inMaster); closeFd(outRelay); closeFd(outMaster);
    };
    if ((pipeIn && (inRelay < 0 || inMaster < 0)) || (tty && (outRelay < 0 || outMaster < 0))) {
        closeFd(terminal);
        closeRelays();
        fail(env, "Could not duplicate shell descriptors");
        return 0;
    }

    pid_t parent = getpid();
    pid_t child = fork();
    if (child == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent || setsid() < 0) _exit(126);
        if (tty) {
            // The pre-fork descriptor keeps the slave open until this session acquires it.
            int controlling = open(slave, O_RDWR | O_CLOEXEC);
            if (controlling < 0) _exit(126);
            close(terminal);
            terminal = controlling;
        }
        if (dup2(tty & TTY_IN ? terminal : in, STDIN_FILENO) < 0
            || dup2(tty & TTY_OUT ? terminal : out, STDOUT_FILENO) < 0
            || dup2(tty & TTY_ERR ? terminal : err, STDERR_FILENO) < 0) _exit(126);
        closeFd(terminal);
        closeFd(shell->master);
        closeFd(in); closeFd(out); closeFd(err);
        closeRelays();
        signal(SIGPIPE, SIG_DFL);
        if (!dir.empty()) chdir(dir.c_str());
        if (environment) execve(argv[0], argv.data(), envp.data());
        else execv(argv[0], argv.data());
        _exit(127);
    }
    closeFd(terminal);
    if (child < 0) {
        closeRelays();
        fail(env, "Could not fork shell");
        return 0;
    }

    shell->pid = child;
    try {
        if (pipeIn) {
            makeNonblocking(inRelay);
            std::thread(relay, inRelay, inMaster, shell->cancelled).detach();
            inRelay = inMaster = -1;
        }
        if (tty) {
            if (tty & TTY_OUT) makeNonblocking(outRelay);
            std::thread(relay, outMaster, outRelay, shell->cancelled).detach();
        }
    } catch (const std::system_error&) {
        closeRelays();
        stop(child);
        fail(env, "Could not start terminal relay");
        return 0;
    }
    return reinterpret_cast<jlong>(shell.release());
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_stupidgiraffe_kizuku_server_LegacyRish_resize(JNIEnv*, jclass, jlong handle, jlong size) {
    auto shell = reinterpret_cast<Shell*>(handle);
    if (shell->master < 0) return;
    winsize window{};
    static_assert(sizeof(window) == sizeof(size));
    memcpy(&window, &size, sizeof(window));
    ioctl(shell->master, TIOCSWINSZ, &window);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_stupidgiraffe_kizuku_server_LegacyRish_status(JNIEnv*, jclass, jlong handle) {
    return result(reinterpret_cast<Shell*>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_stupidgiraffe_kizuku_server_LegacyRish_destroy(JNIEnv*, jclass, jlong handle) {
    std::unique_ptr<Shell> shell(reinterpret_cast<Shell*>(handle));
    stop(shell->pid);
}

extern "C" JNIEXPORT jbyte JNICALL
Java_moe_shizuku_manager_shell_Shell_terminalMode(JNIEnv*, jclass) {
    return (isatty(STDIN_FILENO) ? TTY_IN : 0) | (isatty(STDOUT_FILENO) ? TTY_OUT : 0)
        | (isatty(STDERR_FILENO) ? TTY_ERR : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_moe_shizuku_manager_shell_Shell_setRawTerminal(JNIEnv*, jclass, jbyte tty) {
    int fd = terminalFd(tty);
    if (!(tty & TTY_IN) || tcgetattr(fd, &original) < 0) return;
    termios raw = original;
    cfmakeraw(&raw);
    if (tcsetattr(fd, TCSANOW, &raw) == 0) rawFd = fd;
}

extern "C" JNIEXPORT void JNICALL
Java_moe_shizuku_manager_shell_Shell_restoreTerminal(JNIEnv*, jclass) {
    if (rawFd >= 0) tcsetattr(rawFd, TCSANOW, &original);
    rawFd = -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_moe_shizuku_manager_shell_Shell_windowSize(JNIEnv*, jclass, jbyte tty) {
    winsize window{};
    jlong size = 0;
    static_assert(sizeof(window) == sizeof(size));
    if (ioctl(terminalFd(tty), TIOCGWINSZ, &window) == 0) memcpy(&size, &window, sizeof(size));
    return size;
}
