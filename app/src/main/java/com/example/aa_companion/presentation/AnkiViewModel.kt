package com.example.aa_companion.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AnkiViewModel : ViewModel() {

    companion object {
        const val AGAIN_DELAY_MS = 10_000L
    }

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

    private val againJobs = mutableListOf<Job>()

    fun updateCards(newCards: List<AnkiCard>) {
        // A new batch replaces local Again timers; those cards will come from
        // AnkiDroid if they are still due.
        againJobs.forEach { it.cancel() }
        againJobs.clear()
        _cards.value = newCards
        _currentIndex.value = 0
        _isShowingFront.value = true
    }

    /**
     * After Again, show this card again locally in [AGAIN_DELAY_MS].
     * Appends to the end of the remaining queue so it does not interrupt the
     * current card. If the batch is already finished, the extra card makes the
     * review screen come back.
     */
    fun scheduleAgain(card: AnkiCard) {
        val job = viewModelScope.launch {
            delay(AGAIN_DELAY_MS)
            appendCard(card)
        }
        againJobs.add(job)
    }

    private fun appendCard(card: AnkiCard) {
        val cards = _cards.value
        val idx = _currentIndex.value.coerceAtLeast(0)
        val stillAhead = cards.drop(idx).any { it.id == card.id }
        if (stillAhead) return
        _cards.value = cards + card
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