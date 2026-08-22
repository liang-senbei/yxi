package app.yxi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yxi.ssh.HostConfig
import app.yxi.term.G2Screen
import app.yxi.ui.theme.YxiTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // G2 临时接线：私钥用 `adb push` 放到应用私有目录。
        // G3 会换成 App 内生成 + Android Keystore 保管，届时这段删掉。
        val keyFile = File(filesDir, "g2-key")
        setContent {
            YxiTheme {
                Scaffold { p ->
                    if (keyFile.exists()) {
                        G2Screen(
                            cfg = HostConfig(
                                alias = "本机",
                                hostname = "10.0.2.2",   // 模拟器眼里的宿主机
                                port = 22,
                                username = "root",
                                auth = HostConfig.Auth.PrivateKey(keyFile.readText()),
                            ),
                            attachTo = "yxi-g2",   // 专用测试会话（纯 shell），不碰真实的 cc-* 会话
                            modifier = Modifier.padding(p),
                        )
                    } else {
                        Shell(Modifier.padding(p))
                    }
                }
            }
        }
    }
}

@Composable
private fun Shell(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Yxi", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "手机指挥台",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        // 面的明度分层——这块存在的意义就是让 G1 的截图能一眼看出 M3 主题真的生效了
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("G1 · 空壳可装", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "minSdk 26 · compileSdk 37",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
