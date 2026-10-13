package org.polyfrost.smartsearch.model

import ai.djl.huggingface.tokenizers.jni.LibUtils
import ai.djl.util.Platform
import ai.djl.util.Utils
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenizerCacheTest {

    private fun extract(): Path {
        val companions = if (System.getProperty("os.name").startsWith("Windows")) {
            arrayOf("libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll")
        } else {
            emptyArray()
        }
        val copy = LibUtils::class.java.getDeclaredMethod("copyJniLibrary", Array<String>::class.java, Platform::class.java)
        copy.isAccessible = true
        return copy.invoke(null, companions, Platform.detectPlatform("tokenizers")) as Path
    }

    @Test
    fun dropsOnlyTruncatedLibraries() {
        val previous = System.getProperty("ENGINE_CACHE_DIR")
        System.setProperty("ENGINE_CACHE_DIR", createTempDirectory("djl").toString())
        try {
            val cacheDir = Utils.getEngineCacheDir("tokenizers")
            val dir = extract()
            assertTrue(dir.startsWith(cacheDir.toAbsolutePath()), "DJL should extract into the temp cache")

            dropTruncatedTokenizerCache(cacheDir)
            assertTrue(dir.exists(), "intact libraries should be kept")

            for (index in dir.listDirectoryEntries().indices) {
                extract()
                dir.listDirectoryEntries().sorted()[index].writeBytes(ByteArray(1024))
                dropTruncatedTokenizerCache(cacheDir)
                assertFalse(dir.exists(), "truncated libraries should be removed")
            }
        } finally {
            if (previous == null) System.clearProperty("ENGINE_CACHE_DIR") else System.setProperty("ENGINE_CACHE_DIR", previous)
        }
    }
}
