package app.yxi.desktop

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX)
class ProviderModelsTest {
    @Test fun `production discovery follows endpoint and credential boundaries`() {
        val dir = Files.createTempDirectory("provider-model-tests").toFile()
        try {
            val service = dir.resolve("discovery.py").apply { writeText(ProviderModels.script) }
            val tests = dir.resolve("tests.py").apply { writeText(javaClass.getResource("/provider_models_test.py")!!.readText()) }
            val output = dir.resolve("result.txt")
            val process = ProcessBuilder("python3", tests.path, service.path).redirectErrorStream(true).redirectOutput(output).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), output.readText())
            } finally { if (process.isAlive) process.destroyForcibly() }
        } finally { dir.deleteRecursively() }
    }
}
