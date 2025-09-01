package com.playeverywhere999.vpn_for_friends_v3.util // или ваш пакет

import androidx.lifecycle.Observer // <--- УБЕДИТЕСЬ, ЧТО ЭТОТ ИМПОРТ ПРАВИЛЬНЫЙ

// ... (класс Event<T> ) ...
open class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    fun peekContent(): T = content
}


class EventObserver<T>(private val onEventUnhandledContent: (T) -> Unit) : Observer<Event<T>> {

    // --- ИЗМЕНЕНИЕ ЗДЕСЬ: Убираем '?' у типа параметра, как предлагает лог ---
    override fun onChanged(event: Event<T>) {
        // --- ИЗМЕНЕНИЕ ЗДЕСЬ: Убираем '?' при вызове, так как event теперь не nullable ---
        // Однако, getContentIfNotHandled все еще может вернуть null, поэтому let остается
        event.getContentIfNotHandled()?.let { value ->
            onEventUnhandledContent(value)
        }
    }
}


