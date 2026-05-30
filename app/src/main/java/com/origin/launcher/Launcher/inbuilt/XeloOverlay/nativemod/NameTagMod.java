package com.origin.launcher.Launcher.inbuilt.XeloOverlay.nativemod;

public class NameTagMod {

    public static boolean patch() {
        try {
            if (!InbuiltModsNative.loadLibrary()) return false;
            return patchNametag();
        } catch (UnsatisfiedLinkError e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean unpatch() {
        try {
            if (!InbuiltModsNative.loadLibrary()) return false;
            return unpatchNametag();
        } catch (Throwable t) {
            return false;
        }
    }

    public static native boolean patchNametag();
    public static native boolean unpatchNametag();
}