package com.tucavr.screens

import com.tucavr.R

/**
 * Agrupamento de modos de formato de tela para exibição em abas no modal.
 */
enum class ScreenFormatGroup {
    FLAT,
    SPHERICAL_360,
    SPHERICAL_180
}

/**
 * Representa uma entrada no catálogo de formatos de tela suportados pelo player.
 *
 * @property index Índice do modo (0..9), correspondente a `ScreenMode` no C++ e `SCREEN_MODE` no Rust.
 * @property labelResId Recurso de string com o nome legível do modo.
 * @property iconResId Recurso drawable com o ícone representativo do modo.
 * @property group Agrupamento temático do modo (Plano, 360° ou 180°).
 */
data class ScreenFormatEntry(
    val index: Int,
    val labelResId: Int,
    val iconResId: Int,
    val group: ScreenFormatGroup
)

/**
 * Catálogo centralizado dos 10 modos de tela do tucaVR.
 * Garante sincronia única entre UI, JNI e renderizadores C++/Rust.
 */
object ScreenFormatCatalog {
    val entries: List<ScreenFormatEntry> = listOf(
        ScreenFormatEntry(0, R.string.player_mode_2d, R.drawable.icon_2d, ScreenFormatGroup.FLAT),
        ScreenFormatEntry(1, R.string.player_mode_sbs, R.drawable.icon_3d_sbs, ScreenFormatGroup.FLAT),
        ScreenFormatEntry(2, R.string.player_mode_sbs_half, R.drawable.icon_3d_sbs_half, ScreenFormatGroup.FLAT),
        ScreenFormatEntry(3, R.string.player_mode_ou, R.drawable.icon_3d_ou, ScreenFormatGroup.FLAT),
        ScreenFormatEntry(4, R.string.player_mode_ou_half, R.drawable.icon_3d_ou_half, ScreenFormatGroup.FLAT),
        ScreenFormatEntry(5, R.string.player_mode_360, R.drawable.icon_360, ScreenFormatGroup.SPHERICAL_360),
        ScreenFormatEntry(6, R.string.player_mode_180, R.drawable.icon_180, ScreenFormatGroup.SPHERICAL_180),
        ScreenFormatEntry(7, R.string.player_mode_360_sbs, R.drawable.icon_360_sbs, ScreenFormatGroup.SPHERICAL_360),
        ScreenFormatEntry(8, R.string.player_mode_360_ou, R.drawable.icon_360_ou, ScreenFormatGroup.SPHERICAL_360),
        ScreenFormatEntry(9, R.string.player_mode_180_sbs, R.drawable.icon_180_sbs, ScreenFormatGroup.SPHERICAL_180)
    )

    /**
     * Retorna a entrada para o índice especificado, com fallback seguro para 2D (índice 0).
     */
    fun get(index: Int): ScreenFormatEntry = entries.getOrElse(index) { entries[0] }

    /**
     * Retorna o ID do recurso de string para o índice especificado.
     */
    fun getLabelResId(index: Int): Int = get(index).labelResId

    /**
     * Retorna o ID do recurso de ícone para o índice especificado.
     */
    fun getIconResId(index: Int): Int = get(index).iconResId

    /**
     * Retorna a lista de entradas pertencentes a um grupo específico.
     */
    fun getByGroup(group: ScreenFormatGroup): List<ScreenFormatEntry> =
        entries.filter { it.group == group }

    /**
     * Indica se o modo é esférico (360° ou 180°).
     */
    fun isSpherical(index: Int): Boolean = index >= 5
}
