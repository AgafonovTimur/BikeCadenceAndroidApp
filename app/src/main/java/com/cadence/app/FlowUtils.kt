package com.cadence.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/**
 * Простая подписка на StateFlow без дополнительных lifecycle-зависимостей.
 * Подходит для нашего случая, так как ViewModel здесь не используется.
 */
@Composable
fun <T> StateFlow<T>.collectAsStateSafe(initial: T): State<T> {
    val state = remember { mutableStateOf(initial) }
    DisposableEffect(this) {
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        val job = scope.launch {
            collect { value -> state.value = value }
        }
        onDispose { job.cancel() }
    }
    return state
}
