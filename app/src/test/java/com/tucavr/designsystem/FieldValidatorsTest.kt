package com.tucavr.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testes unitários para [FieldValidators].
 * Executam em JVM pura sem dependências do framework Android.
 */
class FieldValidatorsTest {

    @Test
    fun `required validator aceita strings nao vazias e rejeita vazias ou espacos`() {
        val validator = FieldValidators.required("Campo obrigatorio")

        assertNull(validator("192.168.1.1"))
        assertNull(validator("  texto  "))
        assertNull(validator("a"))

        assertEquals("Campo obrigatorio", validator(""))
        assertEquals("Campo obrigatorio", validator("   "))
        assertEquals("Campo obrigatorio", validator("\t\n"))
    }

    @Test
    fun `port validator aceita portas validas e vazias (opcional)`() {
        val validator = FieldValidators.port("Porta invalida")

        assertNull(validator(""))
        assertNull(validator("   "))
        assertNull(validator("1"))
        assertNull(validator("80"))
        assertNull(validator("445"))
        assertNull(validator("2049"))
        assertNull(validator("65535"))

        assertEquals("Porta invalida", validator("0"))
        assertEquals("Porta invalida", validator("65536"))
        assertEquals("Porta invalida", validator("-1"))
        assertEquals("Porta invalida", validator("999999"))
        assertEquals("Porta invalida", validator("abc"))
        assertEquals("Porta invalida", validator("80a"))
    }

    @Test
    fun `requiredPort validator rejeita string vazia e fora de faixa`() {
        val validator = FieldValidators.requiredPort("Porta obrigatoria")

        assertNull(validator("21"))
        assertNull(validator("22"))
        assertNull(validator("445"))

        assertEquals("Porta obrigatoria", validator(""))
        assertEquals("Porta obrigatoria", validator("   "))
        assertEquals("Porta obrigatoria", validator("0"))
        assertEquals("Porta obrigatoria", validator("70000"))
        assertEquals("Porta obrigatoria", validator("port"))
    }

    @Test
    fun `url validator aceita esquemas suportados e rejeita URLs malformadas`() {
        val validator = FieldValidators.url("URL invalida")

        assertNull(validator("http://192.168.1.1/video.mp4"))
        assertNull(validator("https://example.com/stream.m3u8"))
        assertNull(validator("smb://192.168.1.100/share/movie.mkv"))
        assertNull(validator("ftp://ftp.example.com/file.mp4"))
        assertNull(validator("sftp://10.0.0.1/path/video.avi"))
        assertNull(validator("nfs://192.168.1.50/export"))
        assertNull(validator("rtsp://stream.local:554/live"))
        assertNull(validator("udp://@239.0.0.1:1234"))
        assertNull(validator("hls://example.com/live.m3u8"))

        assertEquals("URL invalida", validator(""))
        assertEquals("URL invalida", validator("   "))
        assertEquals("URL invalida", validator("invalid"))
        assertEquals("URL invalida", validator("http://"))
        assertEquals("URL invalida", validator("htp://server/video.mp4"))
        assertEquals("URL invalida", validator("file:///sdcard/video.mp4"))
    }

    @Test
    fun `combine executa validadores sequencialmente e devolve o primeiro erro`() {
        val combined = FieldValidators.combine(
            FieldValidators.required("Obrigatorio"),
            FieldValidators.requiredPort("Porta invalida")
        )

        assertEquals("Obrigatorio", combined(""))
        assertEquals("Obrigatorio", combined("   "))
        assertEquals("Porta invalida", combined("0"))
        assertEquals("Porta invalida", combined("99999"))
        assertEquals("Porta invalida", combined("abc"))
        assertNull(combined("8080"))
    }
}
