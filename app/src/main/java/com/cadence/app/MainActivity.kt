package com.cadence.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: CadenceBleManager
    private lateinit var speedManager: SpeedLocationManager

    private val requestBlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            bleManager.startScanAndConnect()
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            speedManager.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bleManager = CadenceBleManager(applicationContext)
        speedManager = SpeedLocationManager(applicationContext)

        val prefs = getSharedPreferences("cadence_prefs", MODE_PRIVATE)
        val savedFontSize = prefs.getFloat("font_size_sp", 216f)

        setContent {
            CadenceApp(
                bleManager = bleManager,
                speedManager = speedManager,
                onRequestConnect = { requestBlePermissionsAndConnect() },
                onSpeedEnabled = { requestLocationPermissionAndStart() },
                onSpeedDisabled = { speedManager.stop() },
                initialFontSize = savedFontSize,
                onFontSizeChanged = { newSize ->
                    prefs.edit().putFloat("font_size_sp", newSize).apply()
                }
            )
        }
    }

    private fun requestBlePermissionsAndConnect() {
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
            requestBlePermissionLauncher.launch(permissions)
        }
    }

    private fun requestLocationPermissionAndStart() {
        val granted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            speedManager.start()
        } else {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Приложение полностью закрыто -> разрываем связь с датчиком и останавливаем GPS
        bleManager.disconnect()
        speedManager.stop()
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
    speedManager: SpeedLocationManager,
    onRequestConnect: () -> Unit,
    onSpeedEnabled: () -> Unit,
    onSpeedDisabled: () -> Unit,
    initialFontSize: Float = 216f,
    onFontSizeChanged: (Float) -> Unit = {}
) {
    // 0 = чёрная (по умолчанию), 1 = серая, 2 = белая. Круговой порядок.
    var themeIndex by remember { mutableStateOf(0) }

    val cadence by bleManager.cadence.collectAsStateSafe(0)
    val connectionState by bleManager.connectionState.collectAsStateSafe(
        CadenceBleManager.ConnectionState.DISCONNECTED
    )
    val speedKmh by speedManager.speedKmh.collectAsStateSafe(0.0)

    // Размер шрифта по умолчанию 216sp (на 80% больше исходных 120sp).
    // Сохраняется между запусками через SharedPreferences (см. MainActivity.kt).
    var fontSizeSp by remember { mutableFloatStateOf(initialFontSize) }

    // Режим отображения скорости — включается/выключается тремя одновременными
    // касаниями экрана. GPS работает только пока этот режим включён.
    var isSpeedVisible by remember { mutableStateOf(false) }

    val palette = ThemeColors.palettes[themeIndex]

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)

                        var totalDrag = 0f
                        var isZoomGesture = false
                        var threeFingerHandled = false
                        var prevDistance = 0f

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }

                            when {
                                pressed.size >= 3 -> {
                                    // Три и более одновременных касания -> переключаем
                                    // отображение скорости. Срабатывает один раз за жест.
                                    if (!threeFingerHandled) {
                                        isSpeedVisible = !isSpeedVisible
                                        if (isSpeedVisible) {
                                            onSpeedEnabled()
                                        } else {
                                            onSpeedDisabled()
                                        }
                                        threeFingerHandled = true
                                    }
                                    pressed.forEach { it.consume() }
                                }
                                pressed.size == 2 && !threeFingerHandled -> {
                                    // Два пальца на экране -> режим масштабирования,
                                    // работает в любой точке экрана
                                    isZoomGesture = true
                                    val p1 = pressed[0].position
                                    val p2 = pressed[1].position
                                    val dx = p1.x - p2.x
                                    val dy = p1.y - p2.y
                                    val distance = sqrt(dx * dx + dy * dy)

                                    if (prevDistance > 0f) {
                                        val zoomChange = distance / prevDistance
                                        fontSizeSp =
                                            (fontSizeSp * zoomChange).coerceIn(30f, 500f)
                                    }
                                    prevDistance = distance
                                    pressed.forEach { it.consume() }
                                }
                                pressed.size == 1 && !isZoomGesture && !threeFingerHandled -> {
                                    // Один палец в любой точке экрана — свайп смены темы
                                    val change = pressed[0]
                                    totalDrag += change.positionChange().x
                                    change.consume()
                                }
                            }

                            if (pressed.isEmpty()) {
                                break
                            }
                        }

                        when {
                            threeFingerHandled -> {
                                // Переключение скорости уже обработано выше
                            }
                            isZoomGesture -> {
                                // Жест масштабирования завершён — сохраняем итоговый размер
                                onFontSizeChanged(fontSizeSp)
                            }
                            else -> {
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
                    }
                }
        ) {
            if (isSpeedVisible) {
                // Экран поровну пополам, без разделительных линий:
                // сверху каденс, снизу скорость
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cadence.toString(),
                            color = palette.text,
                            fontSize = fontSizeSp.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = speedKmh.roundToInt().toString(),
                            color = palette.text,
                            fontSize = fontSizeSp.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Text(
                    text = cadence.toString(),
                    color = palette.text,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

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
