package com.nuttavern.ui.chat.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHtmlTest {

    @Test
    fun detectsComment() {
        assertTrue(MarkdownHtml.isComment("<!-- hidden -->"))
        assertTrue(MarkdownHtml.isComment("  <!-- multi\nline -->  "))
        assertFalse(MarkdownHtml.isComment("<div>"))
    }

    @Test
    fun detectsLineBreak() {
        assertTrue(MarkdownHtml.isLineBreak("<br>"))
        assertTrue(MarkdownHtml.isLineBreak("<br/>"))
        assertTrue(MarkdownHtml.isLineBreak("<br />"))
        assertTrue(MarkdownHtml.isLineBreak("<BR>"))
        assertFalse(MarkdownHtml.isLineBreak("<break>"))
    }

    @Test
    fun decodesNamedEntities() {
        assertEquals(
            "a & b <tag> 'q'",
            MarkdownHtml.decodeEntities("a &amp; b &lt;tag&gt; &#39;q&#39;"),
        )
    }

    @Test
    fun decodesNumericEntities() {
        assertEquals("A", MarkdownHtml.decodeEntities("&#65;"))
        assertEquals("A", MarkdownHtml.decodeEntities("&#x41;"))
    }

    @Test
    fun leavesUnknownEntityUntouched() {
        assertEquals("&unknownentity;", MarkdownHtml.decodeEntities("&unknownentity;"))
    }

    @Test
    fun noAmpersandReturnsSameText() {
        assertEquals("plain text", MarkdownHtml.decodeEntities("plain text"))
    }

    @Test
    fun stripsBlockCommentToEmpty() {
        assertEquals("", MarkdownHtml.stripBlockHtml("<!-- hidden note -->"))
    }

    @Test
    fun stripsTagsKeepsText() {
        assertEquals(
            "hello",
            MarkdownHtml.stripBlockHtml("<div class=\"x\">\nhello\n</div>"),
        )
    }

    @Test
    fun blockBrBecomesNewline() {
        assertEquals(
            "line1\nline2",
            MarkdownHtml.stripBlockHtml("line1<br>line2"),
        )
    }

    @Test
    fun stripsCommentInsideBlockButKeepsRest() {
        assertEquals(
            "visible",
            MarkdownHtml.stripBlockHtml("<div><!-- note -->visible</div>"),
        )
    }
}
