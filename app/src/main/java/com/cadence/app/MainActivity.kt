package com.cadence.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: CadenceBleManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            bleManager.startScanAndConnect()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = CadenceBleManager(applicationContext)

        setContent {
            CadenceApp(
                bleManager = bleManager,
                onRequestConnect = { requestPermissionsAndConnect() }
            )
        }
    }

    private fun requestPermissionsAndConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val allGranted = permissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            bleManager.startScanAndConnect()
        } else {
            requestPermissionLauncher.launch(permissions)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Приложение полностью закрыто -> разрываем связь с датчиком
        bleManager.disconnect()
    }
}

// Цвета тем
private object ThemeColors {
    val blackBg = Color(0xFF0A0A0A)
    val blackButton = Color(0xFF2C2C2E)

    val grayBg = Color(0xFF1C1C1E)
    val grayButton = Color(0xFF5A5A5C)

    val textColor = Color(0xFFE0E0E0)
}

@Composable
fun CadenceApp(
    bleManager: CadenceBleManager,
    onRequestConnect: () -> Unit
) {
    // 0 = чёрная тема (главная), 1 = серая тема
    var themeIndex by remember { mutableStateOf(0) }

    val cadence by bleManager.cadence.collectAsStateSafe(0)
    val connectionState by bleManager.connectionState.collectAsStateSafe(
        CadenceBleManager.ConnectionState.DISCONNECTED
    )

    var fontSizeSp by remember { mutableFloatStateOf(120f) }

    val backgroundColor = if (themeIndex == 0) ThemeColors.blackBg else ThemeColors.grayBg
    val buttonColor = if (themeIndex == 0) ThemeColors.blackButton else ThemeColors.grayButton

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        if (dragAmount > 50) {
                            themeIndex = 0 // свайп вправо -> чёрная
                        } else if (dragAmount < -50) {
                            themeIndex = 1 // свайп влево -> серая
                        }
                    }
                }
        ) {
            // Цифра каденса по центру, с pinch-to-zoom
            val transformState = rememberTransformableState { zoomChange, _, _ ->
                fontSizeSp = (fontSizeSp * zoomChange).coerceIn(30f, 500f)
            }

            Text(
                text = cadence.toString(),
                color = ThemeColors.textColor,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.Center)
                    .transformable(state = transformState)
            )

            // Кнопка подключения — правый нижний угол, видна только если не подключено
            if (connectionState != CadenceBleManager.ConnectionState.CONNECTED) {
                val buttonText = when (connectionState) {
                    CadenceBleManager.ConnectionState.SCANNING -> "Поиск..."
                    CadenceBleManager.ConnectionState.CONNECTING -> "Подключение..."
                    else -> "Подключить"
                }

                Button(
                    onClick = { onRequestConnect() },
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp)
                ) {
                    Text(text = buttonText, color = ThemeColors.textColor)
                }
            }
        }
    }
}
