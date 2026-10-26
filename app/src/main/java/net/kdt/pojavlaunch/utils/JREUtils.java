package net.kdt.pojavlaunch.utils;

import android.content.Context;

import java.io.File;

/**
 * Narrow JNI surface compatible with the native methods used by the
 * Android Minecraft launch bridge. Libraries are loaded explicitly in the
 * order declared by the verified bridge manifest; this class has no static
 * System.loadLibrary() side effects.
 */
public final class JREUtils {
    private JREUtils() {
    }

    public static void loadNativeLibrary(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty()) {
            throw new IllegalArgumentException("Native library path is empty.");
        }
        File library = new File(absolutePath);
        if (!library.isAbsolute() || !library.isFile()) {
            throw new IllegalArgumentException("Native library does not exist at an absolute path: " + absolutePath);
        }
        System.load(library.getAbsolutePath());
    }

    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeHooks();
    public static native void setupExitMethod(Context context);
    public static native int[] renderAWTScreenFrame();
}
