package io.github.stupidgiraffe.kizuku;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalViewClient;

/** Shared defaults for terminal UI callbacks. Command input and output are never logged. */
abstract class TerminalCallbacks implements TerminalSessionClient, TerminalViewClient {
    @Override public void onTextChanged(TerminalSession session) {}
    @Override public void onTitleChanged(TerminalSession session) {}
    @Override public void onSessionFinished(TerminalSession session) {}
    @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
    @Override public void onPasteTextFromClipboard(TerminalSession session) {}
    @Override public void onBell(TerminalSession session) {}
    @Override public void onColorsChanged(TerminalSession session) {}
    @Override public void onTerminalCursorStateChange(boolean state) {}
    @Override public void setTerminalShellPid(TerminalSession session, int pid) {}
    @Override public Integer getTerminalCursorStyle() { return 0; }
    @Override public float onScale(float scale) { return scale; }
    @Override public void onSingleTapUp(MotionEvent event) {}
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return false; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copy) {}
    @Override public boolean onKeyDown(int key, KeyEvent event, TerminalSession session) { return false; }
    @Override public boolean onKeyUp(int key, KeyEvent event) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return false; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int code, boolean ctrl, TerminalSession session) { return false; }
    @Override public void onEmulatorSet() {}
    @Override public void logError(String tag, String message) { Log.e(tag, message); }
    @Override public void logWarn(String tag, String message) { Log.w(tag, message); }
    @Override public void logInfo(String tag, String message) {}
    @Override public void logDebug(String tag, String message) {}
    @Override public void logVerbose(String tag, String message) {}
    @Override public void logStackTraceWithMessage(String tag, String message, Exception failure) { Log.e(tag, message, failure); }
    @Override public void logStackTrace(String tag, Exception failure) { Log.e(tag, "Terminal failure", failure); }
}
