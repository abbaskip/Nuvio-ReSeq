package com.nuvio.tv.livetv.data

import java.io.File

/** Only disposable import copies, before this repository starts any new producer. */
internal object ImportWorkFiles {
    /** Same-directory replacement only. Never truncate the last good copy to emulate rename. */
    fun publishDownloaded(temporary: File, target: File) {
        require(temporary.parentFile == target.parentFile)
        if(temporary.renameTo(target)) return
        if(android.os.Build.VERSION.SDK_INT >= 26) {
            java.nio.file.Files.move(temporary.toPath(),target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } else {
            android.system.Os.rename(temporary.absolutePath,target.absolutePath)
        }
    }

    fun recover(vararg directories: File) {
        directories.forEach { directory -> directory.listFiles()?.forEach { file ->
            if(file.isFile && (file.name.endsWith(".tmp") || file.name.endsWith(".migration"))) file.delete()
        } }
    }
}
