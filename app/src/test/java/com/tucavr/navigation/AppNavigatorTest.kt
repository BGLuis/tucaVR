package com.tucavr.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes unitários para [AppNavigator] e para o comportamento de navegação da Sidebar.
 * Valida a garantia arquitetural de que trocar de seção a partir de qualquer profundidade
 * redefine a pilha para `[Home, <destino>]`.
 */
class AppNavigatorTest {
    @Test
    fun `initial state has Home as current and stack is only Home`() {
        val nav = AppNavigator()
        assertEquals(Destination.Home, nav.current)
        assertEquals(listOf(Destination.Home), nav.stack)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `navigateTo pushes destination onto stack and canGoBack becomes true`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/movies"))

        assertEquals(Destination.LocalFiles("/movies"), nav.current)
        assertEquals(listOf(Destination.Home, Destination.LocalFiles("/movies")), nav.stack)
        assertTrue(nav.canGoBack)
    }

    @Test
    fun `back pops last destination and restores previous`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/movies"))
        nav.navigateTo(Destination.LocalFiles("/movies/action"))

        assertEquals(Destination.LocalFiles("/movies/action"), nav.current)

        assertTrue(nav.back())
        assertEquals(Destination.LocalFiles("/movies"), nav.current)

        assertTrue(nav.back())
        assertEquals(Destination.Home, nav.current)
        assertFalse(nav.canGoBack)

        // Tentar voltar na raiz não faz nada e retorna false
        assertFalse(nav.back())
        assertEquals(Destination.Home, nav.current)
    }

    @Test
    fun `switching section via backToHome followed by navigateTo leaves stack as Home and destination`() {
        val nav = AppNavigator()
        // Navega profundamente dentro de arquivos locais
        nav.navigateTo(Destination.LocalFiles("/storage"))
        nav.navigateTo(Destination.LocalFiles("/storage/movies"))
        nav.navigateTo(Destination.LocalFiles("/storage/movies/anime"))
        nav.navigateTo(Destination.LocalFiles("/storage/movies/anime/series1"))

        assertEquals(5, nav.stack.size)

        // Simula clique na sidebar para 'Playlists'
        nav.backToHome()
        nav.navigateTo(Destination.Playlists)

        assertEquals(listOf(Destination.Home, Destination.Playlists), nav.stack)
        assertEquals(Destination.Playlists, nav.current)
        assertTrue(nav.canGoBack)

        // Voltar leva de volta para a Home
        assertTrue(nav.back())
        assertEquals(Destination.Home, nav.current)
        assertEquals(listOf(Destination.Home), nav.stack)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `switching section via selectSection from arbitrary depth leaves stack as Home and destination`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/folder1"))
        nav.navigateTo(Destination.LocalFiles("/folder1/folder2"))
        nav.navigateTo(Destination.LocalFiles("/folder1/folder2/folder3"))

        nav.selectSection(Destination.NetworkHome)

        assertEquals(listOf(Destination.Home, Destination.NetworkHome), nav.stack)
        assertEquals(Destination.NetworkHome, nav.current)
        assertTrue(nav.canGoBack)

        // Agora troca de seção novamente para Downloads a partir da profundidade de Network
        nav.navigateTo(Destination.Downloads)
        nav.selectSection(Destination.Settings)

        assertEquals(listOf(Destination.Home, Destination.Settings), nav.stack)
        assertEquals(Destination.Settings, nav.current)
    }

    @Test
    fun `selecting Home section from arbitrary depth resets stack to only Home`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/folder1"))
        nav.navigateTo(Destination.LocalFiles("/folder1/folder2"))

        nav.selectSection(Destination.Home)

        assertEquals(listOf(Destination.Home), nav.stack)
        assertEquals(Destination.Home, nav.current)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun `selecting same section replaces deep stack with shallow Home and destination`() {
        val nav = AppNavigator()
        nav.navigateTo(Destination.LocalFiles("/root"))
        nav.navigateTo(Destination.LocalFiles("/root/sub1"))
        nav.navigateTo(Destination.LocalFiles("/root/sub1/sub2"))

        // Re-selecionar arquivos locais na sidebar reseta para a raiz de arquivos
        val target = Destination.LocalFiles("/root")
        nav.selectSection(target)

        assertEquals(listOf(Destination.Home, target), nav.stack)
        assertEquals(target, nav.current)
    }
}
