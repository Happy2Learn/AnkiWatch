package com.example.aa_companion.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AnkiViewModel : ViewModel() {

    private val _cards = MutableStateFlow<List<AnkiCard>>(emptyList())
    val cards: StateFlow<List<AnkiCard>> = _cards.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShowingFront = MutableStateFlow(true)
    val isShowingFront: StateFlow<Boolean> = _isShowingFront.asStateFlow()

    // How many grades are sitting on the watch waiting to upload to the phone
    private val _pendingGrades = MutableStateFlow(0)
    val pendingGrades: StateFlow<Int> = _pendingGrades.asStateFlow()

    // Short human-readable note about the last sync attempt, shown on screen so
    // failures aren't silent on a device with no other feedback.
    private val _syncStatus = MutableStateFlow<String?>(null)
    val syncStatus: StateFlow<String?> = _syncStatus.asStateFlow()

    fun setPendingGrades(count: Int) {
        _pendingGrades.value = count
    }

    fun setSyncStatus(message: String?) {
        _syncStatus.value = message
    }

    fun updateCards(newCards: List<AnkiCard>) {
        _cards.value = newCards
        _currentIndex.value = 0
        _isShowingFront.value = true
    }

    // Flips the card over when the user taps the screen
    fun flipToBack() {
        if (_isShowingFront.value) {
            _isShowingFront.value = false
        }
    }

    // Moves to the next card AFTER they press a grade button
    fun nextCard() {
        _currentIndex.value += 1
        _isShowingFront.value = true
    }
}