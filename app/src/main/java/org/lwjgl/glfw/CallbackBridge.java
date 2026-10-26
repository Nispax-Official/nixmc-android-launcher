package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Keep;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Android-side callback contract consumed by the Pojav/Amethyst native GLFW
 * bridge. Keep the JNI method names and signatures stable: libpojavexec.so
 * resolves these from JNI_OnLoad before starting the embedded Java VM.
 *
 * The native library is deliberately not loaded in a static initializer;
 * NixMC first verifies the engine bundle and then controls the native load order.
 */
@Keep
public final class CallbackBridge {
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    public static volatile int windowWidth = 1280;
    public static volatile int windowHeight = 720;
    public static volatile int physicalWidth = 1280;
    public static volatile int physicalHeight = 720;
    public static volatile float mouseX;
    public static volatile float mouseY;
    public static volatile float deltaX;
    public static volatile float deltaY;

    public static volatile boolean holdingAlt;
    public static volatile boolean holdingCapslock;
    public static volatile boolean holdingCtrl;
    public static volatile boolean holdingNumlock;
    public static volatile boolean holdingShift;

    public static volatile ByteBuffer sGamepadButtonBuffer;
    public static volatile FloatBuffer sGamepadAxisBuffer;

    private static volatile Context appContext;
    private static final AtomicBoolean mouseGrabbed = new AtomicBoolean(false);
    private static volatile Runnable directGamepadEnableHandler;
    private static volatile Runnable mouseGrabChangedHandler;

    private CallbackBridge() { }

    public static void initialize(Context context) {
        appContext = context.getApplicationContext();
    }

    /** Call only after libpojavexec has been loaded and registered JNI methods. */
    public static void initializeNativeBuffers() {
        if (sGamepadButtonBuffer != null && sGamepadAxisBuffer != null) return;
        synchronized (CallbackBridge.class) {
            if (sGamepadButtonBuffer == null) {
                sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer()
                        .order(ByteOrder.LITTLE_ENDIAN);
            }
            if (sGamepadAxisBuffer == null) {
                sGamepadAxisBuffer = nativeCreateGamepadAxisBuffer()
                        .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            }
        }
    }

    public static void setDirectGamepadEnableHandler(Runnable handler) {
        directGamepadEnableHandler = handler;
    }

    public static void setMouseGrabChangedHandler(Runnable handler) {
        mouseGrabChangedHandler = handler;
    }

    public static boolean isMouseGrabbed() {
        return mouseGrabbed.get();
    }

    @Keep
    public static String accessAndroidClipboard(int type, String text) {
        Context context = appContext;
        if (context == null) return type == CLIPBOARD_PASTE ? "" : null;
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return type == CLIPBOARD_PASTE ? "" : null;
            if (type == CLIPBOARD_COPY) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Minecraft", text == null ? "" : text));
                return null;
            }
            if (type == CLIPBOARD_PASTE) {
                if (!clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null ||
                        clipboard.getPrimaryClip().getItemCount() == 0) return "";
                CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
                return value == null ? "" : value.toString();
            }
            if (type == CLIPBOARD_OPEN && text != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(text));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (RuntimeException ignored) {
            // Clipboard and external-link APIs can reject background requests.
        }
        return null;
    }

    @Keep
    public static void onGrabStateChanged(boolean grabbing) {
        mouseGrabbed.set(grabbing);
        Runnable handler = mouseGrabChangedHandler;
        if (handler != null) handler.run();
    }

    @Keep
    public static void onDirectInputEnable() {
        Runnable handler = directGamepadEnableHandler;
        if (handler != null) handler.run();
    }

    @Keep
    public static float getAndroidDPI() {
        Context context = appContext;
        if (context == null) return 1.0f;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return metrics.density;
    }

    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        return false;
    }

    public static void sendKey(int keyCode, int scancode, int action, int modifiers) {
        nativeSendKey(keyCode, scancode, action, modifiers);
    }

    public static void sendKey(int keyCode, boolean down) {
        nativeSendKey(keyCode, 0, down ? 1 : 0, currentModifiers());
    }

    public static void sendMouseButton(int button, boolean down) {
        nativeSendMouseButton(button, down ? 1 : 0, currentModifiers());
    }

    public static void sendCursorPosition(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(x, y);
    }

    public static void sendScroll(float x, float y) {
        nativeSendScroll(x, y);
    }

    public static void sendScreenSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        windowWidth = physicalWidth = width;
        windowHeight = physicalHeight = height;
        nativeSendScreenSize(width, height);
    }

    public static int currentModifiers() {
        int modifiers = 0;
        if (holdingAlt) modifiers |= 0x0004;
        if (holdingCapslock) modifiers |= 0x0010;
        if (holdingCtrl) modifiers |= 0x0002;
        if (holdingNumlock) modifiers |= 0x0020;
        if (holdingShift) modifiers |= 0x0001;
        return modifiers;
    }

    public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    public static native boolean nativeSendChar(char codepoint);
    public static native boolean nativeSendCharMods(char codepoint, int modifiers);
    public static native void nativeSendKey(int key, int scancode, int action, int modifiers);
    public static native void nativeSendCursorPos(float x, float y);
    public static native void nativeSendMouseButton(int button, int action, int modifiers);
    public static native void nativeSendScroll(double xoffset, double yoffset);
    public static native void nativeSendScreenSize(int width, int height);
    private static native ByteBuffer nativeCreateGamepadButtonBuffer();
    private static native ByteBuffer nativeCreateGamepadAxisBuffer();
}
