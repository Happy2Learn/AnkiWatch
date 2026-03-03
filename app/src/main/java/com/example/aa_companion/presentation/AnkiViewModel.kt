package com.example.aa_companion.presentation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AnkiViewModel : ViewModel() {

    // 1. Holds the list of parsed flashcards
    private val _cards = MutableStateFlow<List<AnkiCard>>(emptyList())
    val cards: StateFlow<List<AnkiCard>> = _cards.asStateFlow()

    // 2. Tracks which card we are currently looking at
    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    // 3. Tracks if we are looking at the Question or the Answer
    private val _isShowingFront = MutableStateFlow(true)
    val isShowingFront: StateFlow<Boolean> = _isShowingFront.asStateFlow()

    // Called when DataClient receives a new batch from the phone
    fun updateCards(newCards: List<AnkiCard>) {
        _cards.value = newCards
        _currentIndex.value = 0
        _isShowingFront.value = true
    }

    // Handles the screen tap logic
    fun handleCardTap() {
        if (_isShowingFront.value) {
            // If on Front, flip to Back
            _isShowingFront.value = false
        } else {
            // If on Back, move to the next card and reset to Front
            _currentIndex.value += 1
            _isShowingFront.value = true
        }
    }
}