package com.cadence.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat

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

// Цвета тем: индекс 0 = чёрная, 1 = серая, 2 = белая (порядок по кругу)
private data class ThemePalette(val background: Color, val button: Color, val text: Color)

private object ThemeColors {
    val black = ThemePalette(
        background = Color(0xFF0A0A0A),
        button = Color(0xFF2C2C2E),
        text = Color(0xFFE0E0E0)
    )
    val gray = ThemePalette(
        background = Color(0xFF1C1C1E),
        button = Color(0xFF5A5A5C),
        text = Color(0xFFE0E0E0)
    )
    val white = ThemePalette(
        background = Color(0xFFF0F0F0),
        button = Color(0xFFFFFFFF),
        text = Color(0xFF1A1A1A)
    )

    val palettes = listOf(black, gray, white)
}

@Composable
fun CadenceApp(
    bleManager: CadenceBleManager,
    onRequestConnect: () -> Unit
) {
    // 0 = чёрная (по умолчанию), 1 = серая, 2 = белая. Круговой порядок.
    var themeIndex by remember { mutableStateOf(0) }

    val cadence by bleManager.cadence.collectAsStateSafe(0)
    val connectionState by bleManager.connectionState.collectAsStateSafe(
        CadenceBleManager.ConnectionState.DISCONNECTED
    )

    var fontSizeSp by remember { mutableFloatStateOf(120f) }

    val palette = ThemeColors.palettes[themeIndex]

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Pinch-to-zoom по всему экрану
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        fontSizeSp = (fontSizeSp * zoom).coerceIn(30f, 500f)
                    }
                }
                // Свайп для смены темы — работает только в зонах по 20% от каждого края,
                // чтобы не конфликтовать с pinch-жестом в центре экрана
                .pointerInput(Unit) {
                    var totalDrag = 0f
                    var isEdgeZone = false
                    val edgeFraction = 0.2f

                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            totalDrag = 0f
                            isEdgeZone = offset.x < size.width * edgeFraction ||
                                    offset.x > size.width * (1f - edgeFraction)
                        },
                        onDragEnd = {
                            if (isEdgeZone) {
                                when {
                                    totalDrag < -100f -> {
                                        // свайп влево -> предыдущая тема по кругу
                                        themeIndex = (themeIndex + 2) % 3
                                    }
                                    totalDrag > 100f -> {
                                        // свайп вправо -> следующая тема по кругу
                                        themeIndex = (themeIndex + 1) % 3
                                    }
                                }
                            }
                        }
                    ) { change, dragAmount ->
                        if (isEdgeZone) {
                            totalDrag += dragAmount
                            change.consume()
                        }
                    }
                }
        ) {
            Text(
                text = cadence.toString(),
                color = palette.text,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
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
                    colors = ButtonDefaults.buttonColors(containerColor = palette.button),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp)
                ) {
                    Text(text = buttonText, color = palette.text)
                }
            }
        }
    }
}
