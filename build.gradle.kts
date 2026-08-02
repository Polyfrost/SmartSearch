import net.fabricmc.loom.build.nesting.NestableJarGenerationTask
import org.cadixdev.mercury.shadow.org.eclipse.jdt.core.dom.ModuleModifier.isTransitive
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.4.0"
    id("net.fabricmc.fabric-loom")
}

group = "org.polyfrost"
version = "1.0"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven("https://redirector.kotlinlang.org/maven/compose-dev")
    maven("https://repo.polyfrost.org/releases")
    maven("https://repo.polyfrost.org/snapshots")
    maven("https://central.sonatype.com/repository/maven-snapshots") {
        content { includeGroup("net.kyori") }
    }
    maven("https://maven.terraformersmc.com/releases/") {
        content { includeGroup("com.terraformersmc") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")

    val oneConfigVersion = "1.0.13+SEARCH1"
    runtimeOnly("org.polyfrost.oneconfig:26.2-fabric:$oneConfigVersion")
    for (module in listOf("config", "config-impl", "events", "internal")) {
        implementation("org.polyfrost.oneconfig:$module:$oneConfigVersion")
    }

    // Include langchain4j BOM
    implementation(platform(libs.langchain4j.bom))
    include(platform(libs.langchain4j.bom))
    implementation(libs.bundles.embedding) {
        isTransitive = false
    }
    include(libs.bundles.embedding)

    // Include lucene
    implementation(libs.bundles.database) {
        isTransitive = false
    }
    include(libs.bundles.database)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

// Allow us to run 26.2
configurations.runtimeClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}
configurations.testRuntimeClasspath {
    exclude(group = "org.polyfrost.oneconfig", module = "26.2-fabric")
}

tasks.test {
    useJUnitPlatform()
}

// Strip out Linux ARM natives, almost no consumer PCs run Linux on ARM right now
// these are mostly for servers, and stripping them out saves us like 10MB
val excludedEntries = listOf(
    "ai/onnxruntime/native/linux-aarch64/",
    "native/lib/linux-aarch64/cpu", // tokenizer lib
    // Exclude debug symbols, IDK why they are included
    "ai/onnxruntime/native/osx-aarch64/libonnxruntime.dylib.dSYM",
    "ai/onnxruntime/native/osx-aarch64/libonnxruntime4j_jni.dylib.dSYM",
)
tasks.named("processIncludeJars") {
    doLast {
        val outputDir = (this as NestableJarGenerationTask).outputDirectory.get().asFile
        outputDir.walkTopDown().filter { it.isFile && it.extension == "jar" }.forEach { jar ->
            val stripped = File(jar.parentFile, "${jar.name}.stripped")
            var removed = false
            ZipFile(jar).use { zip ->
                ZipOutputStream(stripped.outputStream().buffered()).use { out ->
                    for (entry in zip.entries()) {
                        if (excludedEntries.any { entry.name.startsWith(it) }) {
                            removed = true
                            continue
                        }
                        out.putNextEntry(ZipEntry(entry).apply { compressedSize = -1 })
                        if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            if (removed) {
                stripped.copyTo(jar, overwrite = true)
            }
            stripped.delete()
        }
    }
}