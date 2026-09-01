package com.rella.ankiwear.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure, device-free logic in AnkiLogic.kt.
 *
 * These run on the plain JVM (no phone, no watch, no Android emulator). They
 * exist to catch the subtle bugs that are easy to miss by eyeballing code:
 * bit-packing edge cases, JSON parsing, and ordering.
 */
class AnkiLogicTest {

    // ---------- CardCoding ----------

    @Test
    fun `encode then decode round-trips a card id`() {
        val noteId = 123456789L
        val cardOrd = 1
        val packed = CardCoding.encode(noteId, cardOrd)
        val (decodedNote, decodedOrd) = CardCoding.decode(packed)

        assertEquals(noteId, decodedNote)
        assertEquals(cardOrd, decodedOrd)
    }

    @Test
    fun `decode of zero ordinal keeps note id intact`() {
        val noteId = 42L
        val (decodedNote, decodedOrd) = CardCoding.decode(CardCoding.encode(noteId, 0))

        assertEquals(42L, decodedNote)
        assertEquals(0, decodedOrd)
    }

    @Test
    fun `encode handles a large note id without losing the ordinal`() {
        // A note id near the top of the old 32-bit range should survive intact
        // under the corrected scheme.
        val noteId = 0x7FFFFFFFFL
        val cardOrd = 3
        val (decodedNote, decodedOrd) = CardCoding.decode(CardCoding.encode(noteId, cardOrd))

        assertEquals(noteId, decodedNote)
        assertEquals(cardOrd, decodedOrd)
    }

    @Test
    fun `encode preserves a real millisecond-timestamp note id`() {
        // AnkiDroid note IDs are millisecond timestamps, e.g. ~1.77e12 today.
        // These need more than 32 bits, which is exactly the bug this protects
        // against.
        val noteId = 1770000000000L
        val cardOrd = 2
        val (decodedNote, decodedOrd) = CardCoding.decode(CardCoding.encode(noteId, cardOrd))

        assertEquals(noteId, decodedNote)
        assertEquals(cardOrd, decodedOrd)
    }

    // ---------- DeckCount ----------

    @Test
    fun `deck count sums learn review and new`() {
        assertEquals(17, DeckCount.parse("[2, 5, 10]"))
    }

    @Test
    fun `deck count parses a single entry`() {
        assertEquals(4, DeckCount.parse("[4]"))
    }

    @Test
    fun `deck count returns zero for null or blank`() {
        assertEquals(0, DeckCount.parse(null))
        assertEquals(0, DeckCount.parse(""))
        assertEquals(0, DeckCount.parse("   "))
    }

    @Test
    fun `deck count returns zero for malformed json`() {
        assertEquals(0, DeckCount.parse("not json at all"))
        assertEquals(0, DeckCount.parse("{\"nope\": 1}"))
    }

    @Test
    fun `deck count ignores non-numeric entries`() {
        assertEquals(5, DeckCount.parse("[5, \"oops\"]"))
    }

    // ---------- CardText ----------

    @Test
    fun `strips the anki answer separator`() {
        // The exact markup that leaked onto the watch screen.
        assertEquals("Paris", CardText.toPlain("<hr id=answer>Paris"))
        assertEquals("Paris", CardText.toPlain("<hr id=\"answer\">Paris"))
    }

    @Test
    fun `separator becomes a break when text surrounds it`() {
        assertEquals(
            "Capital of France\nParis",
            CardText.toPlain("Capital of France<hr id=answer>Paris")
        )
    }

    @Test
    fun `converts br tags to newlines`() {
        assertEquals("one\ntwo", CardText.toPlain("one<br>two"))
        assertEquals("one\ntwo", CardText.toPlain("one<br/>two"))
        assertEquals("one\ntwo", CardText.toPlain("one<BR />two"))
    }

    @Test
    fun `removes style and script blocks entirely`() {
        assertEquals(
            "Answer",
            CardText.toPlain("<style>.card { color: red; }</style>Answer")
        )
        assertEquals(
            "Answer",
            CardText.toPlain("<script>var x = 1;</script>Answer")
        )
    }

    @Test
    fun `decodes html entities`() {
        assertEquals("a & b", CardText.toPlain("a &amp; b"))
        assertEquals("5 < 10", CardText.toPlain("5 &lt; 10"))
        assertEquals("a b", CardText.toPlain("a&nbsp;b"))
    }

    @Test
    fun `strips remaining tags but keeps their text`() {
        assertEquals("bold and italic", CardText.toPlain("<b>bold</b> and <i>italic</i>"))
    }

    @Test
    fun `keeps cloze answer text`() {
        assertEquals(
            "The capital is Paris",
            CardText.toPlain("The capital is <span class=\"cloze\">Paris</span>")
        )
    }

    @Test
    fun `removes sound markers`() {
        assertEquals("Hello", CardText.toPlain("Hello [sound:greeting.mp3]"))
    }

    @Test
    fun `collapses excess blank lines from block tags`() {
        assertEquals(
            "first\nsecond",
            CardText.toPlain("<div>first</div><div><br></div><div>second</div>")
        )
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("Just a normal card", CardText.toPlain("Just a normal card"))
    }

    @Test
    fun `handles blank input`() {
        assertEquals("", CardText.toPlain(""))
        assertEquals("", CardText.toPlain("   "))
    }

    @Test
    fun `media is still detectable before stripping`() {
        // Order matters: detect first, then strip. This documents why.
        val html = "Listen [sound:a.mp3]"
        assertTrue(MediaDetect.hasMedia(html))
        assertFalse(MediaDetect.hasMedia(CardText.toPlain(html)))
    }

    // ---------- MediaDetect ----------

    @Test
    fun `detects image audio and video media`() {
        assertTrue(MediaDetect.hasMedia("See <img src='x.png'> here"))
        assertTrue(MediaDetect.hasMedia("Play [sound:hello.mp3] now"))
        assertTrue(MediaDetect.hasMedia("Listen <audio src='a.mp3'>"))
        assertTrue(MediaDetect.hasMedia("Watch <video src='v.mp4'>"))
    }

    @Test
    fun `detects media case-insensitively`() {
        assertTrue(MediaDetect.hasMedia("see <IMG SRC='x.png'>"))
        assertTrue(MediaDetect.hasMedia("play [SOUND:x]"))
    }

    @Test
    fun `does not flag plain text as media`() {
        assertFalse(MediaDetect.hasMedia("Just a normal front side"))
        assertFalse(MediaDetect.hasMedia(""))
    }

    // ---------- orderGradesForReplay ----------

    private fun grade(id: Long, reviewedAtMs: Long) =
        AnkiDroidHelper.Grade(
            noteId = id,
            cardOrd = 0,
            ease = AnkiDroidHelper.EASE_GOOD,
            timeTakenMs = 0,
            reviewedAtMs = reviewedAtMs
        )

    @Test
    fun `replay orders grades by review time ascending`() {
        val outOfOrder = listOf(
            grade(3, 3000),
            grade(1, 1000),
            grade(2, 2000)
        )

        val ordered = orderGradesForReplay(outOfOrder)

        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.noteId })
    }

    @Test
    fun `replay keeps already-sorted grades stable`() {
        val inOrder = listOf(grade(1, 1000), grade(2, 2000))
        assertEquals(listOf(1L, 2L), orderGradesForReplay(inOrder).map { it.noteId })
    }

    @Test
    fun `replay handles an empty list`() {
        assertTrue(orderGradesForReplay(emptyList()).isEmpty())
    }
}