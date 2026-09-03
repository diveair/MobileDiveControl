package com.samsung.android.panorama;

import java.nio.ByteBuffer;

/** Result ABI populated by Samsung's JNI bridge. This contains no vendor implementation. */
public final class ResultParam {
    public ByteBuffer resultBuffer;
    public int size;
    public int width;
    public int height;
    public int format;
    public int orientation;
    public int croppedWidth;
    public int croppedHeight;
    public int fullPanoWidth;
}
