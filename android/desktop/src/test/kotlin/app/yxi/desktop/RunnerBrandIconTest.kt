package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Robot
import java.io.File
import javax.imageio.ImageIO

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "YXI_ISOLATED_TEST_RUN", matches = "[0-9a-f-]{36}")
class RunnerBrandIconTest {
    @kotlin.test.Test fun `official assets render in actual runtime chips`() {
        check(File("/.dockerenv").isFile)
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            Window(onCloseRequest = ::exitApplication, title = "Yxi official runtime assets",
                state = rememberWindowState(width = 900.dp, height = 300.dp)) {
                YxiTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("运行器 · 官方发布资源", style = MaterialTheme.typography.titleLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                configurationEngines.forEach { (id, label) ->
                                    FilterChip(id == "claude", {}, label = { Text(label) },
                                        leadingIcon = { RunnerBrandIcon(id, Modifier.size(20.dp)) })
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(50.dp)) {
                                configurationEngines.forEach { (id, _) -> RunnerBrandIcon(id, Modifier.size(64.dp)) }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    try {
                        delay(1800)
                        withContext(Dispatchers.IO) {
                            val bounds = java.awt.Rectangle(window.locationOnScreen, window.size)
                            check(ImageIO.write(Robot().createScreenCapture(bounds), "png", File("/results/official-runtime-icons.png")))
                        }
                    } catch (e: Throwable) { failure = e }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
    }
}
