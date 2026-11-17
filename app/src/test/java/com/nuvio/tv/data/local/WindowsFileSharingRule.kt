package com.nuvio.tv.data.local

import java.io.FileNotFoundException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Retry isolated preference fixtures only when Windows briefly denies an open file handle. */
class WindowsFileSharingRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            repeat(5) { attempt ->
                try { base.evaluate(); return } catch (error: Throwable) {
                    val sharing = generateSequence(error) { it.cause }.any {
                        it is FileNotFoundException && it.message.orEmpty().contains("being used by another process")
                    }
                    if (attempt == 4 || !System.getProperty("os.name").startsWith("Windows") || !sharing) throw error
                    System.err.println("Retrying ${description.methodName}: transient Windows file sharing")
                    Thread.sleep(25)
                }
            }
        }
    }
}
