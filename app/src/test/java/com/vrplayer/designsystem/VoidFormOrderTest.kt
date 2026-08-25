package com.vrplayer.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testes unitários para a máquina de resolução de ordem e navegação do [VoidForm].
 * Executam em JVM pura testando a lógica de salto de campos invisíveis (ex.: convidado SMB, chave SFTP).
 */
class VoidFormOrderTest {

    data class MockField(val name: String, var isVisible: Boolean = true)

    @Test
    fun `findNextVisibleItem avanca sequencialmente quando todos os campos estao visiveis`() {
        val fields = listOf(
            MockField("host"),
            MockField("port"),
            MockField("user"),
            MockField("pass")
        )

        val nextFrom0 = VoidForm.findNextVisibleItem(fields, 0) { it.isVisible }
        assertEquals("port", nextFrom0?.name)

        val nextFrom1 = VoidForm.findNextVisibleItem(fields, 1) { it.isVisible }
        assertEquals("user", nextFrom1?.name)

        val nextFrom2 = VoidForm.findNextVisibleItem(fields, 2) { it.isVisible }
        assertEquals("pass", nextFrom2?.name)

        val nextFrom3 = VoidForm.findNextVisibleItem(fields, 3) { it.isVisible }
        assertNull(nextFrom3)
    }

    @Test
    fun `findNextVisibleItem pula campos invisiveis no modo convidado do SMB`() {
        // No formulário SMB: [host, port, share, user, pass, domain]
        // Se checkbox "convidado" estiver marcado, user e pass ficam invisíveis (GONE).
        val smbFields = listOf(
            MockField("host", isVisible = true),
            MockField("port", isVisible = true),
            MockField("share", isVisible = true),
            MockField("user", isVisible = false),
            MockField("pass", isVisible = false),
            MockField("domain", isVisible = true)
        )

        // Ao dar Next a partir do campo 'share' (índice 2), deve ir direto para 'domain' (índice 5)
        val nextFromShare = VoidForm.findNextVisibleItem(smbFields, 2) { it.isVisible }
        assertEquals("domain", nextFromShare?.name)

        // Ao dar Next a partir de 'domain' (último visível), retorna null (ação Done/Submit)
        val nextFromDomain = VoidForm.findNextVisibleItem(smbFields, 5) { it.isVisible }
        assertNull(nextFromDomain)
    }

    @Test
    fun `findNextVisibleItem alterna entre senha e chave privada no SFTP`() {
        // No formulário SFTP: [host, port, user, pass, key]
        // Caso 1: Senha ativa (key invisível)
        val sftpPasswordMode = listOf(
            MockField("host", isVisible = true),
            MockField("port", isVisible = true),
            MockField("user", isVisible = true),
            MockField("pass", isVisible = true),
            MockField("key", isVisible = false)
        )

        val nextFromUserInPassMode = VoidForm.findNextVisibleItem(sftpPasswordMode, 2) { it.isVisible }
        assertEquals("pass", nextFromUserInPassMode?.name)

        val nextFromPass = VoidForm.findNextVisibleItem(sftpPasswordMode, 3) { it.isVisible }
        assertNull(nextFromPass) // key está invisível, termina

        // Caso 2: Chave PEM ativa (pass invisível)
        val sftpKeyMode = listOf(
            MockField("host", isVisible = true),
            MockField("port", isVisible = true),
            MockField("user", isVisible = true),
            MockField("pass", isVisible = false),
            MockField("key", isVisible = true)
        )

        val nextFromUserInKeyMode = VoidForm.findNextVisibleItem(sftpKeyMode, 2) { it.isVisible }
        assertEquals("key", nextFromUserInKeyMode?.name) // pulou pass direto pra key

        val nextFromKey = VoidForm.findNextVisibleItem(sftpKeyMode, 4) { it.isVisible }
        assertNull(nextFromKey)
    }

    @Test
    fun `findNextVisibleItem lida com indices fora de limites com seguranca`() {
        val fields = listOf(MockField("f1"), MockField("f2"))

        assertNull(VoidForm.findNextVisibleItem(fields, -1) { it.isVisible })
        assertNull(VoidForm.findNextVisibleItem(fields, 10) { it.isVisible })
        assertNull(VoidForm.findNextVisibleItem(emptyList<MockField>(), 0) { it.isVisible })
    }
}
