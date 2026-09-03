package com.samsung.android.panorama;

/** Java callback ABI consumed by the panorama library in the installed Samsung Camera APK. */
public interface PanoCallbackInterface {
    void onProgress(int progress);
    void onResult(ResultParam result);
}
