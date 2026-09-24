package com.mobiledivecontrol.platform

import android.content.Context
import android.util.AtomicFile
import com.mobiledivecontrol.core.DiveProfileCodec
import com.mobiledivecontrol.core.DiveProfileState
import java.io.File

class DiveProfileStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "dive-profile-v1.bin"))

    @Synchronized fun read(): DiveProfileState = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
        DiveProfileCodec.decode(file.readFully())
    } else DiveProfileState()

    @Synchronized fun save(state: DiveProfileState) {
        val bytes = DiveProfileCodec.encode(state)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }
}
