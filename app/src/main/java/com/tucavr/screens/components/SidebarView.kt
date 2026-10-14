package com.tucavr.screens.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.designsystem.VoidTheme
import com.tucavr.navigation.AppNavigator
import com.tucavr.navigation.Destination

/**
 * Barra lateral de navegação persistente (zona 1, 288 px).
 * Hospeda os itens principais: Início, Arquivos locais, Rede, Playlists, Downloads e Configurações.
 *
 * Cada item possui altura mínima de 64 px (~2.7° no headset), garantindo alvo de toque ergonômico em VR.
 * A navegação limpa a pilha com `appNav.backToHome()` e navega até o destino solicitado.
 */
@SuppressLint("ViewConstructor")
class SidebarView(
    context: Context,
    private val appNav: AppNavigator,
    private val onDestinationChanged: (Destination) -> Unit,
) : LinearLayout(context) {
    private data class SidebarItem(
        val destination: Destination,
        val iconRes: Int,
        val titleRes: Int,
        val isMatchesCurrent: (Destination) -> Boolean,
    )

    private val items =
        listOf(
            SidebarItem(
                destination = Destination.Home,
                iconRes = R.drawable.ic_browser_home,
                titleRes = R.string.sidebar_item_home,
                isMatchesCurrent = { it is Destination.Home },
            ),
            SidebarItem(
                destination = Destination.LocalFiles(Environment.getExternalStorageDirectory().absolutePath),
                iconRes = R.drawable.ic_folder,
                titleRes = R.string.sidebar_item_files,
                isMatchesCurrent = { it is Destination.LocalFiles || it is Destination.FileDetail },
            ),
            SidebarItem(
                destination = Destination.NetworkHome,
                iconRes = R.drawable.ic_network,
                titleRes = R.string.sidebar_item_network,
                isMatchesCurrent = {
                    it is Destination.NetworkHome ||
                        it is Destination.NetworkFiles ||
                        it is Destination.NetworkNfsFiles ||
                        it is Destination.NetworkDlnaFiles ||
                        it is Destination.NetworkFtpFiles ||
                        it is Destination.NetworkSftpFiles ||
                        it is Destination.NetworkWebdavFiles
                },
            ),
            SidebarItem(
                destination = Destination.Playlists,
                iconRes = R.drawable.ic_view_list,
                titleRes = R.string.sidebar_item_playlists,
                isMatchesCurrent = { it is Destination.Playlists || it is Destination.PlaylistDetail },
            ),
            SidebarItem(
                destination = Destination.Downloads,
                iconRes = R.drawable.ic_download,
                titleRes = R.string.sidebar_item_downloads,
                isMatchesCurrent = { it is Destination.Downloads },
            ),
            SidebarItem(
                destination = Destination.Settings,
                iconRes = R.drawable.icon_settings,
                titleRes = R.string.sidebar_item_settings,
                isMatchesCurrent = { it is Destination.Settings },
            ),
        )

    private val itemViews = mutableListOf<Pair<SidebarItem, ItemViewHolder>>()

    private class ItemViewHolder(
        val container: LinearLayout,
        val icon: ImageView,
        val label: TextView,
    )

    init {
        orientation = HORIZONTAL
        layoutParams =
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        setBackgroundColor(VoidTheme.colorSurface)

        val mainContent =
            LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                val pad = VoidTheme.dpToPx(context, 16f)
                setPadding(pad, pad, pad, pad)
            }

        // Cabeçalho / Branding
        val header =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val hPad = VoidTheme.dpToPx(context, 8f)
                val vPad = VoidTheme.dpToPx(context, 12f)
                setPadding(hPad, vPad, hPad, vPad)
            }
        val appTitle =
            TextView(context).apply {
                text = context.getString(R.string.app_name)
                typeface = VoidTheme.typefaceTitle
                textSize = 22f
                setTextColor(VoidTheme.colorAccent)
            }
        header.addView(appTitle)
        mainContent.addView(header)

        // Divisor após cabeçalho
        val divider =
            View(context).apply {
                layoutParams =
                    LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, VoidTheme.dpToPx(context, 1f)).apply {
                        val marginV = VoidTheme.dpToPx(context, 12f)
                        setMargins(0, marginV, 0, marginV)
                    }
                setBackgroundColor(VoidTheme.colorBorder)
            }
        mainContent.addView(divider)

        // Lista de navegação rolável
        val scroll =
            ScrollView(context).apply {
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                isFillViewport = true
            }

        val itemsContainer =
            LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

        val minItemHeightPx = maxOf(64, VoidTheme.dpToPx(context, 64f))

        for (item in items) {
            val itemView =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = minItemHeightPx
                    val hPad = VoidTheme.dpToPx(context, 14f)
                    val vPad = VoidTheme.dpToPx(context, 10f)
                    setPadding(hPad, vPad, hPad, vPad)
                    layoutParams =
                        LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = VoidTheme.dpToPx(context, 6f)
                        }
                }

            val iconView =
                ImageView(context).apply {
                    setImageResource(item.iconRes)
                    val iconSize = VoidTheme.dpToPx(context, 26f)
                    layoutParams =
                        LayoutParams(iconSize, iconSize).apply {
                            rightMargin = VoidTheme.dpToPx(context, 14f)
                        }
                }

            val labelView =
                TextView(context).apply {
                    text = context.getString(item.titleRes)
                    typeface = VoidTheme.typefaceBody
                    textSize = 17f
                    layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

            itemView.addView(iconView)
            itemView.addView(labelView)

            itemView.setOnClickListener {
                handleItemClick(item.destination)
            }

            val holder = ItemViewHolder(itemView, iconView, labelView)
            itemViews.add(item to holder)
            itemsContainer.addView(itemView)
        }

        scroll.addView(itemsContainer)
        mainContent.addView(scroll)
        addView(mainContent)

        // Linha divisória vertical à direita separando zona 1 da zona 2
        val rightBorder =
            View(context).apply {
                layoutParams = LayoutParams(VoidTheme.dpToPx(context, 1f), ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(VoidTheme.colorBorder)
            }
        addView(rightBorder)

        updateActiveDestination(appNav.current)
    }

    private fun handleItemClick(destination: Destination) {
        appNav.selectSection(destination)
        updateActiveDestination(appNav.current)
        onDestinationChanged(appNav.current)
    }

    fun updateActiveDestination(destination: Destination) {
        val cornerRadius = VoidTheme.dp(context, VoidTheme.cornerRadiusDp)
        val strokeWidth = VoidTheme.dpToPx(context, VoidTheme.borderWidthDp)

        for ((item, holder) in itemViews) {
            val isActive = item.isMatchesCurrent(destination)

            if (isActive) {
                val activeBg =
                    GradientDrawable().apply {
                        setColor(VoidTheme.colorSurfaceAlt)
                        this.cornerRadius = cornerRadius
                        setStroke(strokeWidth, VoidTheme.colorAccent)
                    }
                val mask =
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        this.cornerRadius = cornerRadius
                    }
                holder.container.background =
                    RippleDrawable(
                        ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                        activeBg,
                        mask,
                    )
                holder.icon.setColorFilter(VoidTheme.colorAccent)
                holder.label.setTextColor(VoidTheme.colorText)
            } else {
                val inactiveBg =
                    GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        this.cornerRadius = cornerRadius
                    }
                val mask =
                    GradientDrawable().apply {
                        setColor(Color.WHITE)
                        this.cornerRadius = cornerRadius
                    }
                holder.container.background =
                    RippleDrawable(
                        ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                        inactiveBg,
                        mask,
                    )
                holder.icon.setColorFilter(VoidTheme.colorTextSecondary)
                holder.label.setTextColor(VoidTheme.colorTextSecondary)
            }
        }
    }
}
