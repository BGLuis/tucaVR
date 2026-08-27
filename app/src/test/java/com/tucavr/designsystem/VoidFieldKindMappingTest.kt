package com.tucavr.designsystem

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Testes unitários para garantir o mapeamento correto entre [VoidFieldKind] e os flags de [InputType].
 * Garante que senhas não virem texto claro e que tipos numéricos/URI/multilinha sejam preservados.
 */
class VoidFieldKindMappingTest {

    @Test
    fun `TEXT mapeia para TYPE_CLASS_TEXT`() {
        val type = VoidFieldKind.TEXT.toAndroidInputType()
        assertEquals(InputType.TYPE_CLASS_TEXT, type)
    }

    @Test
    fun `NUMBER mapeia para TYPE_CLASS_NUMBER`() {
        val type = VoidFieldKind.NUMBER.toAndroidInputType()
        assertEquals(InputType.TYPE_CLASS_NUMBER, type)
    }

    @Test
    fun `PASSWORD ocultada mapeia para TYPE_CLASS_TEXT com variacao PASSWORD`() {
        val type = VoidFieldKind.PASSWORD.toAndroidInputType(isPasswordRevealed = false)
        val expected = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(expected, type)
        assertNotEquals(InputType.TYPE_CLASS_TEXT, type)
    }

    @Test
    fun `PASSWORD revelada mapeia para TYPE_CLASS_TEXT com variacao VISIBLE_PASSWORD`() {
        val type = VoidFieldKind.PASSWORD.toAndroidInputType(isPasswordRevealed = true)
        val expected = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertEquals(expected, type)
    }

    @Test
    fun `URI mapeia para TYPE_CLASS_TEXT com variacao URI`() {
        val type = VoidFieldKind.URI.toAndroidInputType()
        val expected = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        assertEquals(expected, type)
    }

    @Test
    fun `MULTILINE mapeia para TYPE_CLASS_TEXT com flag MULTI_LINE`() {
        val type = VoidFieldKind.MULTILINE.toAndroidInputType()
        val expected = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        assertEquals(expected, type)
    }
}
