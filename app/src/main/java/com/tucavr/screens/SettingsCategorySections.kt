package com.tucavr.screens

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.tucavr.FeatureFlags
import com.tucavr.LanguagePreferenceStore
import com.tucavr.R
import com.tucavr.VRActivity
import com.tucavr.designsystem.SegmentOption
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidSegmentedSelector
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import com.tucavr.network.NetworkPreferenceStore
import com.tucavr.network.SftpCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Builders for the unified Settings sections added in #67 (Dominant Hand, Accessibility,
 * Language, Network, and About -> Restore Defaults), extracted to keep [SettingsScreen] concise.
 */
internal object SettingsCategorySections {
    fun buildCard(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 12f) }
            background =
                GradientDrawable().apply {
                    setColor(VoidTheme.colorSurface)
                    cornerRadius = VoidTheme.dp(context, 12f)
                }
            setPadding(
                VoidTheme.dpToPx(context, 20f),
                VoidTheme.dpToPx(context, 16f),
                VoidTheme.dpToPx(context, 20f),
                VoidTheme.dpToPx(context, 16f),
            )
        }

    fun buildDominantHandRow(
        context: Context,
        activity: VRActivity,
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }

            addView(
                VoidText.title(context, context.getString(R.string.settings_dominant_hand_label), sizeSp = 16f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )
            addView(
                VoidText.body(context, context.getString(R.string.settings_dominant_hand_desc), sizeSp = 13f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
                },
            )

            val currentMode = FeatureFlags.getDominantHandMode(context)
            addView(
                VoidSegmentedSelector(
                    context = context,
                    options =
                        listOf(
                            SegmentOption(
                                FeatureFlags.DominantHandMode.AUTO,
                                context.getString(R.string.settings_dominant_hand_auto),
                            ),
                            SegmentOption(
                                FeatureFlags.DominantHandMode.RIGHT,
                                context.getString(R.string.settings_dominant_hand_right),
                            ),
                            SegmentOption(
                                FeatureFlags.DominantHandMode.LEFT,
                                context.getString(R.string.settings_dominant_hand_left),
                            ),
                        ),
                    initialSelectedValue = currentMode,
                    onOptionSelected = { mode ->
                        FeatureFlags.setDominantHandMode(context, mode)
                        activity.nativeSetDominantHand(mode.id)
                    },
                ),
            )
        }

    fun buildAccessibilityCategory(
        context: Context,
        activity: VRActivity,
        content: LinearLayout,
    ) {
        val resetCard = buildCard(context)
        resetCard.addView(
            VoidText.title(context, context.getString(R.string.settings_reset_screen_label), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        resetCard.addView(
            VoidText.body(context, context.getString(R.string.settings_reset_screen_desc), sizeSp = 13f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 12f))
            },
        )
        val statusText =
            VoidText.body(
                context,
                context.getString(R.string.settings_reset_screen_done),
                sizeSp = 13f,
                secondary = false,
            ).apply {
                setTextColor(VoidTheme.colorAccent)
                setPadding(0, VoidTheme.dpToPx(context, 8f), 0, 0)
                visibility = View.GONE
            }
        resetCard.addView(
            VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
                text = context.getString(R.string.settings_reset_screen_button)
                textSize = 14f
                minHeight = VoidTheme.dpToPx(context, 48f)
                setOnClickListener {
                    activity.resetScreenPosition()
                    statusText.visibility = View.VISIBLE
                }
            },
        )
        resetCard.addView(statusText)
        content.addView(resetCard)

        val ipdCard = buildCard(context)
        ipdCard.addView(
            VoidText.title(context, context.getString(R.string.settings_ipd_hint_label), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        ipdCard.addView(
            VoidText.body(context, context.getString(R.string.settings_ipd_hint_desc), sizeSp = 14f, secondary = true),
        )
        content.addView(ipdCard)
    }

    fun buildLanguageCategory(
        context: Context,
        activity: VRActivity,
        content: LinearLayout,
    ) {
        content.addView(
            VoidText.title(context, context.getString(R.string.settings_language_label), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        content.addView(
            VoidText.body(context, context.getString(R.string.settings_language_desc), sizeSp = 13f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 10f))
            },
        )

        val currentLanguage = activity.languageStore.get()
        content.addView(
            VoidSegmentedSelector(
                context = context,
                options =
                    listOf(
                        SegmentOption(
                            LanguagePreferenceStore.AppLanguage.AUTO,
                            context.getString(R.string.settings_language_auto),
                        ),
                        SegmentOption(
                            LanguagePreferenceStore.AppLanguage.PT_BR,
                            context.getString(R.string.settings_language_pt_br),
                        ),
                        SegmentOption(
                            LanguagePreferenceStore.AppLanguage.EN,
                            context.getString(R.string.settings_language_en),
                        ),
                        SegmentOption(
                            LanguagePreferenceStore.AppLanguage.ES,
                            context.getString(R.string.settings_language_es),
                        ),
                    ),
                initialSelectedValue = currentLanguage,
                onOptionSelected = { selected ->
                    activity.setAppLanguage(selected)
                },
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }
            },
        )

        val previewCard = buildCard(context)
        previewCard.addView(
            VoidText.mono(
                context,
                context.getString(R.string.settings_language_preview_title),
                sizeSp = 12f,
                secondary = false,
            ).apply {
                setTextColor(VoidTheme.colorAccent)
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 6f))
            },
        )
        val sample =
            context.getString(
                R.string.settings_language_preview_sample_format,
                context.getString(R.string.sidebar_item_home),
                context.getString(R.string.sidebar_item_files),
                context.getString(R.string.sidebar_item_network),
                context.getString(R.string.sidebar_item_settings),
                context.getString(R.string.file_detail_btn_play),
            )
        previewCard.addView(VoidText.body(context, sample, sizeSp = 15f))
        content.addView(previewCard)
    }

    fun buildNetworkCategory(
        context: Context,
        activity: VRActivity,
        scope: CoroutineScope,
        content: LinearLayout,
    ) {
        content.addView(
            VoidText.title(context, context.getString(R.string.settings_network_timeout_label), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        content.addView(
            VoidText.body(context, context.getString(R.string.settings_network_timeout_desc), sizeSp = 13f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 8f))
            },
        )

        val currentTimeout = activity.networkPreferenceStore.getConnectTimeoutSeconds()
        content.addView(
            VoidSegmentedSelector(
                context = context,
                options =
                    NetworkPreferenceStore.VALID_TIMEOUT_SECONDS.map { secs ->
                        SegmentOption(secs, context.getString(R.string.settings_network_timeout_seconds_format, secs))
                    },
                initialSelectedValue = currentTimeout,
                onOptionSelected = { secs ->
                    activity.networkPreferenceStore.setConnectTimeoutSeconds(secs)
                    activity.nativeSetNetworkConnectTimeoutSecs(secs)
                },
            ).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = VoidTheme.dpToPx(context, 16f) }
            },
        )

        val trustedCard = buildCard(context)
        trustedCard.addView(
            VoidText.title(context, context.getString(R.string.settings_network_trusted_sftp_title), sizeSp = 16f).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
            },
        )
        trustedCard.addView(
            VoidText.body(context, context.getString(R.string.settings_network_trusted_sftp_desc), sizeSp = 13f, secondary = true).apply {
                setPadding(0, 0, 0, VoidTheme.dpToPx(context, 12f))
            },
        )

        val listContainer =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        trustedCard.addView(listContainer)
        content.addView(trustedCard)

        val sftpStore = SftpCredentialStore(context)

        fun reloadTrustedServers() {
            scope.launch {
                val trusted =
                    withContext(Dispatchers.IO) {
                        sftpStore.list().filter { !it.hostKeyFingerprint.isNullOrBlank() }
                    }
                listContainer.removeAllViews()
                if (trusted.isEmpty()) {
                    listContainer.addView(
                        VoidText.body(
                            context,
                            context.getString(R.string.settings_network_trusted_sftp_empty),
                            sizeSp = 14f,
                            secondary = true,
                        ),
                    )
                } else {
                    trusted.forEachIndexed { index, server ->
                        val row =
                            LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        if (index < trusted.lastIndex) {
                                            bottomMargin = VoidTheme.dpToPx(context, 8f)
                                        }
                                    }
                                background =
                                    GradientDrawable().apply {
                                        setColor(VoidTheme.colorSurfaceAlt)
                                        cornerRadius = VoidTheme.dp(context, 10f)
                                    }
                                setPadding(
                                    VoidTheme.dpToPx(context, 14f),
                                    VoidTheme.dpToPx(context, 10f),
                                    VoidTheme.dpToPx(context, 14f),
                                    VoidTheme.dpToPx(context, 10f),
                                )
                            }

                        val infoCol =
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                layoutParams =
                                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                        marginEnd = VoidTheme.dpToPx(context, 12f)
                                    }
                                addView(VoidText.body(context, server.name, sizeSp = 15f))
                                addView(
                                    VoidText.mono(
                                        context,
                                        context.getString(
                                            R.string.settings_network_trusted_sftp_host_format,
                                            server.host,
                                            server.port,
                                        ),
                                        sizeSp = 12f,
                                        secondary = true,
                                    ),
                                )
                                addView(
                                    VoidText.mono(
                                        context,
                                        server.hostKeyFingerprint.orEmpty(),
                                        sizeSp = 11f,
                                        secondary = true,
                                    ),
                                )
                            }
                        row.addView(infoCol)

                        row.addView(
                            VoidButton(context, VoidButtonStyle.SECONDARY, isCompact = true).apply {
                                text = context.getString(R.string.settings_network_trusted_sftp_forget)
                                textSize = 13f
                                minHeight = VoidTheme.dpToPx(context, 48f)
                                setOnClickListener {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            sftpStore.setHostKeyFingerprint(server.id, null)
                                        }
                                        reloadTrustedServers()
                                    }
                                }
                            },
                        )
                        listContainer.addView(row)
                    }
                }
            }
        }
        reloadTrustedServers()
    }

    fun buildRestoreDefaultsCard(
        context: Context,
        activity: VRActivity,
    ): LinearLayout =
        buildCard(context).apply {
            addView(
                VoidText.body(context, context.getString(R.string.settings_restore_defaults_title), sizeSp = 18f).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 4f))
                },
            )
            addView(
                VoidText.body(context, context.getString(R.string.settings_restore_defaults_desc), sizeSp = 13f, secondary = true).apply {
                    setPadding(0, 0, 0, VoidTheme.dpToPx(context, 12f))
                },
            )
            addView(
                VoidButton(context, VoidButtonStyle.PRIMARY).apply {
                    text = context.getString(R.string.settings_restore_defaults_button)
                    textSize = 14f
                    minHeight = VoidTheme.dpToPx(context, 48f)
                    setOnClickListener {
                        activity.openConfirmDeleteModal(
                            title = context.getString(R.string.settings_restore_defaults_confirm_title),
                            message = context.getString(R.string.settings_restore_defaults_confirm_message),
                            confirmText = context.getString(R.string.settings_restore_defaults_confirm_action),
                            cancelText = context.getString(R.string.network_delete_server_cancel),
                            onConfirm = {
                                activity.dismissModalPanel()
                                activity.restoreAllSettingsDefaults()
                            },
                        )
                    }
                },
            )
        }
}
