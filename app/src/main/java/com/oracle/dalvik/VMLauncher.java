package com.oracle.dalvik;

import java.io.File;

/**
 * JNI compatibility entry point used by the Android Java launcher bridge.
 *
 * The implementation library is deliberately loaded by verified absolute path
 * rather than System.loadLibrary(), because NixMC keeps bridge bundles in its
 * private files directory and validates them before mapping native code.
 */
public final class VMLauncher {
    private VMLauncher() {
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

    /**
     * Calls the native JLI launcher in-process. This method remains unavailable
     * until a verified, ABI-compatible bridge binary is installed and loaded.
     */
    public static native int launchJVM(String[] args);
}
