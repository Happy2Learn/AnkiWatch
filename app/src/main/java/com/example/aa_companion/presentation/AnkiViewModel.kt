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