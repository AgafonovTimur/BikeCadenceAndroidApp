package com.cadence.app

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Скорость через GPS телефона (без дополнительного датчика).
 * Работает только когда явно запущена (start()) — не расходует батарею в остальное время.
 */
class SpeedLocationManager(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _speedKmh = MutableStateFlow(0.0)
    val speedKmh: StateFlow<Double> = _speedKmh

    private var isTracking = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // location.speed отдаётся в м/с, если провайдер её поддерживает
            val speedMs = location.speed
            _speedKmh.value = (speedMs * 3.6).coerceIn(0.0, 150.0)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            _speedKmh.value = 0.0
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isTracking) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, // раз в секунду
                0f,
                listener
            )
            isTracking = true
        } catch (e: SecurityException) {
            // Разрешение на геолокацию не выдано — значение останется 0
        } catch (e: IllegalArgumentException) {
            // GPS-провайдер недоступен на устройстве
        }
    }

    fun stop() {
        if (!isTracking) return
        locationManager.removeUpdates(listener)
        isTracking = false
        _speedKmh.value = 0.0
    }
}
