package com.sbro.emucorer.pocket;
final class PocketNative {
    static { System.loadLibrary("dukepocket"); }
    static native long open(byte[] bios, byte[] content);
    static native void close(long handle);
    static native int tick(long handle, int buttons, int[] pixels, short[] audio);
    static native byte[] card(long handle);
}
