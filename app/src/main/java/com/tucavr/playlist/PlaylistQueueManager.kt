package com.tucavr.playlist

import java.util.Collections
import java.util.Random

/**
 * Modos de reprodução suportados pelo player.
 */
enum class PlaybackMode {
    NORMAL,      // Reprodução sequencial direta até o final
    REPEAT_ALL,  // Ao atingir o final da playlist, retorna ao início
    REPEAT_ONE,  // Repete continuamente a mesma mídia
    SHUFFLE      // Reproduz os itens em ordem pseudo-aleatória sem repetição
}

/**
 * T9.3: Gerenciador de fila e reprodução sequencial de playlists.
 *
 * Controla os itens da playlist ativa, a ordem de reprodução, os modos (Normal,
 * Repetir Tudo, Repetir Uma, Aleatório) e o auto-avanço ao final da duração do vídeo.
 */
class PlaylistQueueManager(
    private var random: Random = Random()
) {

    interface Listener {
        fun onQueueChanged()
        fun onItemChanged(item: PlaylistItem?, index: Int)
        fun onModeChanged(mode: PlaybackMode)
    }

    private val listeners = mutableListOf<Listener>()

    var currentPlaylist: Playlist? = null
        private set

    private var originalItems: List<PlaylistItem> = emptyList()
    private var playOrderIndices: MutableList<Int> = mutableListOf()
    private var currentOrderPos: Int = -1

    var playbackMode: PlaybackMode = PlaybackMode.NORMAL
        private set

    private var hasTriggeredEndForCurrent: Boolean = false

    /** Callback invocado quando o gerenciador decide iniciar a reprodução de um item. */
    var onPlayItemRequested: ((PlaylistItem) -> Unit)? = null

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setRandomGenerator(rng: Random) {
        this.random = rng
    }

    /**
     * Inicia a reprodução de uma playlist a partir do índice especificado.
     */
    fun startPlaylist(playlist: Playlist, items: List<PlaylistItem>, startIndex: Int = 0) {
        currentPlaylist = playlist
        originalItems = items.sortedBy { it.position }
        hasTriggeredEndForCurrent = false
        rebuildPlayOrder(startIndex.coerceIn(0, (originalItems.size - 1).coerceAtLeast(0)))
        notifyQueueChanged()

        if (originalItems.isNotEmpty()) {
            val validIndex = startIndex.coerceIn(0, originalItems.size - 1)
            playItemAtIndex(validIndex)
        } else {
            notifyItemChanged(null, -1)
        }
    }

    /**
     * Atualiza os itens da playlist atual sem interromper a mídia em reprodução caso ainda exista.
     */
    fun updateItems(items: List<PlaylistItem>) {
        val currentItem = getCurrentItem()
        originalItems = items.sortedBy { it.position }
        if (currentItem != null) {
            val newIdx = originalItems.indexOfFirst { it.id == currentItem.id }
            if (newIdx != -1) {
                rebuildPlayOrder(newIdx)
            } else {
                rebuildPlayOrder(0)
            }
        } else {
            rebuildPlayOrder(0)
        }
        notifyQueueChanged()
    }

    /**
     * Define o modo de reprodução e reconfigura a ordem se necessário.
     */
    fun setPlaybackMode(mode: PlaybackMode) {
        if (playbackMode == mode) return
        val currentIdx = getCurrentIndex()
        playbackMode = mode
        rebuildPlayOrder(if (currentIdx >= 0) currentIdx else 0)
        listeners.forEach { it.onModeChanged(mode) }
    }

    /**
     * Alterna ciclicamente entre os modos de reprodução:
     * NORMAL -> REPEAT_ALL -> REPEAT_ONE -> SHUFFLE -> NORMAL
     */
    fun cyclePlaybackMode(): PlaybackMode {
        val nextMode = when (playbackMode) {
            PlaybackMode.NORMAL -> PlaybackMode.REPEAT_ALL
            PlaybackMode.REPEAT_ALL -> PlaybackMode.REPEAT_ONE
            PlaybackMode.REPEAT_ONE -> PlaybackMode.SHUFFLE
            PlaybackMode.SHUFFLE -> PlaybackMode.NORMAL
        }
        setPlaybackMode(nextMode)
        return nextMode
    }

    fun getItems(): List<PlaylistItem> = originalItems

    fun getCurrentItem(): PlaylistItem? {
        val idx = getCurrentIndex()
        return if (idx in originalItems.indices) originalItems[idx] else null
    }

    fun getCurrentIndex(): Int {
        if (currentOrderPos in playOrderIndices.indices) {
            val itemIdx = playOrderIndices[currentOrderPos]
            if (itemIdx in originalItems.indices) return itemIdx
        }
        return -1
    }

    fun hasNext(): Boolean {
        if (originalItems.isEmpty()) return false
        return when (playbackMode) {
            PlaybackMode.NORMAL -> currentOrderPos < playOrderIndices.size - 1
            PlaybackMode.REPEAT_ALL, PlaybackMode.REPEAT_ONE -> true
            PlaybackMode.SHUFFLE -> currentOrderPos < playOrderIndices.size - 1 || playOrderIndices.size > 1
        }
    }

    fun hasPrevious(): Boolean {
        if (originalItems.isEmpty()) return false
        return when (playbackMode) {
            PlaybackMode.NORMAL -> currentOrderPos > 0
            PlaybackMode.REPEAT_ALL, PlaybackMode.REPEAT_ONE -> true
            PlaybackMode.SHUFFLE -> currentOrderPos > 0 || playOrderIndices.size > 1
        }
    }

    /**
     * Avança para a próxima faixa conforme o modo de reprodução ativo.
     * Retorna o item selecionado ou `null` se atingiu o fim da fila.
     */
    fun playNext(): PlaylistItem? {
        if (originalItems.isEmpty()) return null

        if (playbackMode == PlaybackMode.REPEAT_ONE) {
            val item = getCurrentItem()
            if (item != null) {
                hasTriggeredEndForCurrent = false
                onPlayItemRequested?.invoke(item)
                notifyItemChanged(item, getCurrentIndex())
            }
            return item
        }

        if (currentOrderPos < playOrderIndices.size - 1) {
            currentOrderPos++
        } else {
            // Chegamos ao final da ordem
            when (playbackMode) {
                PlaybackMode.REPEAT_ALL -> {
                    currentOrderPos = 0
                }
                PlaybackMode.SHUFFLE -> {
                    // Ao terminar uma passagem embaralhada com shuffle, reembaralha mantendo ciclo
                    val currentIdx = getCurrentIndex()
                    rebuildPlayOrder(currentIdx)
                    currentOrderPos = if (playOrderIndices.size > 1) 1 else 0
                }
                PlaybackMode.NORMAL -> {
                    // Fim da playlist
                    return null
                }
                PlaybackMode.REPEAT_ONE -> {}
            }
        }

        val nextItem = getCurrentItem()
        if (nextItem != null) {
            hasTriggeredEndForCurrent = false
            onPlayItemRequested?.invoke(nextItem)
            notifyItemChanged(nextItem, getCurrentIndex())
        }
        return nextItem
    }

    /**
     * Retrocede para a faixa anterior conforme o modo de reprodução ativo.
     */
    fun playPrevious(): PlaylistItem? {
        if (originalItems.isEmpty()) return null

        if (playbackMode == PlaybackMode.REPEAT_ONE) {
            val item = getCurrentItem()
            if (item != null) {
                hasTriggeredEndForCurrent = false
                onPlayItemRequested?.invoke(item)
                notifyItemChanged(item, getCurrentIndex())
            }
            return item
        }

        if (currentOrderPos > 0) {
            currentOrderPos--
        } else {
            when (playbackMode) {
                PlaybackMode.REPEAT_ALL -> {
                    currentOrderPos = playOrderIndices.size - 1
                }
                PlaybackMode.SHUFFLE -> {
                    currentOrderPos = playOrderIndices.size - 1
                }
                PlaybackMode.NORMAL -> {
                    return null
                }
                PlaybackMode.REPEAT_ONE -> {}
            }
        }

        val prevItem = getCurrentItem()
        if (prevItem != null) {
            hasTriggeredEndForCurrent = false
            onPlayItemRequested?.invoke(prevItem)
            notifyItemChanged(prevItem, getCurrentIndex())
        }
        return prevItem
    }

    /**
     * Salta diretamente para o item no índice [targetIndex] da lista original.
     */
    fun skipTo(targetIndex: Int): PlaylistItem? {
        if (targetIndex !in originalItems.indices) return null
        val orderPos = playOrderIndices.indexOf(targetIndex)
        if (orderPos != -1) {
            currentOrderPos = orderPos
        } else {
            rebuildPlayOrder(targetIndex)
        }
        val item = originalItems[targetIndex]
        hasTriggeredEndForCurrent = false
        onPlayItemRequested?.invoke(item)
        notifyItemChanged(item, targetIndex)
        return item
    }

    /**
     * Notificado pelo player durante a reprodução para detectar o término da mídia (EOF/duração).
     */
    fun onPlaybackProgress(currentSec: Float, totalSec: Float) {
        if (originalItems.isEmpty() || totalSec <= 1.0f) return

        // Se o usuário voltou para trás, reabilita detecção de fim
        if (currentSec < totalSec - 2.0f) {
            hasTriggeredEndForCurrent = false
        }

        // Detecta proximidade do fim (últimos 800ms de vídeo)
        val isNearEnd = currentSec >= (totalSec - 0.8f)
        if (isNearEnd && !hasTriggeredEndForCurrent) {
            hasTriggeredEndForCurrent = true
            playNext()
        }
    }

    /**
     * Notificado quando a mídia começa a tocar.
     */
    fun onMediaStarted() {
        hasTriggeredEndForCurrent = false
    }

    fun clearQueue() {
        currentPlaylist = null
        originalItems = emptyList()
        playOrderIndices.clear()
        currentOrderPos = -1
        hasTriggeredEndForCurrent = false
        notifyQueueChanged()
        notifyItemChanged(null, -1)
    }

    private fun playItemAtIndex(index: Int) {
        val orderPos = playOrderIndices.indexOf(index)
        currentOrderPos = if (orderPos != -1) orderPos else 0
        val item = originalItems.getOrNull(index)
        hasTriggeredEndForCurrent = false
        if (item != null) {
            onPlayItemRequested?.invoke(item)
        }
        notifyItemChanged(item, index)
    }

    private fun rebuildPlayOrder(pivotIndex: Int) {
        playOrderIndices.clear()
        val total = originalItems.size
        if (total == 0) {
            currentOrderPos = -1
            return
        }

        val allIndices = (0 until total).toMutableList()
        if (playbackMode == PlaybackMode.SHUFFLE) {
            allIndices.remove(pivotIndex)
            Collections.shuffle(allIndices, random)
            playOrderIndices.add(pivotIndex)
            playOrderIndices.addAll(allIndices)
            currentOrderPos = 0
        } else {
            playOrderIndices.addAll(allIndices)
            currentOrderPos = pivotIndex.coerceIn(0, total - 1)
        }
    }

    private fun notifyQueueChanged() {
        listeners.forEach { it.onQueueChanged() }
    }

    private fun notifyItemChanged(item: PlaylistItem?, index: Int) {
        listeners.forEach { it.onItemChanged(item, index) }
    }
}
