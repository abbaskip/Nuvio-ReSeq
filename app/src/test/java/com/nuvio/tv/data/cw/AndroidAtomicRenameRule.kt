package com.nuvio.tv.data.cw

import android.system.Os
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Host filesystem equivalent of Android's native rename(2), including on Windows. */
class AndroidAtomicRenameRule : TestWatcher() {
    override fun starting(description: Description) {
        mockkStatic(Os::class)
        every { Os.rename(any(), any()) } answers {
            Files.move(File(firstArg<String>()).toPath(), File(secondArg<String>()).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        }
    }

    override fun finished(description: Description) = unmockkStatic(Os::class)
}
