package com.tucavr.filebrowser

import android.content.Context
import com.tucavr.history.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Poda de pastas de rede sem mídia — orquestra a varredura recursiva feita no Rust
 * (`rust/protocols/src/folder_scan.rs`, uma chamada JNI por subpasta de topo, sem
 * reconectar por nível — ver `nativeXxxScanFolderHasMedia` em `VRActivity.kt`) com um
 * cache persistente (Room, `folder_media_status`).
 *
 * Comportamento pedido pelo usuário (decisão registrada no plano de implementação):
 * - Pasta de rede NUNCA visitada: espera resolver (varredura completa, sem limite de
 *   profundidade fixo, com deadline de segurança de 10s no lado Rust) antes de expor a
 *   listagem — sem "pisca-pisca" de pastas aparecendo e sumindo.
 * - Pasta já visitada: cache-first, quase instantâneo; revalida em segundo plano.
 * - A pasta em que o usuário está navegando agora sempre tem prioridade sobre
 *   revalidação de outras pastas — ver os dois semáforos abaixo.
 *
 * Deliberadamente NÃO usa [FolderSummary]/[MediaFilterEngine.matchesFolderFilter]: a
 * varredura de rede só sabe "tem mídia ou não" (presença, early-exit), não contagens por
 * tipo/formato 3D como o resumo local — forçar isso pelo caminho de `FolderSummary`
 * quebraria os filtros de tipo/3D em pastas de rede (hoje nunca aplicados a pastas de
 * rede, já que `folderSummary` sempre é `null` nas telas de rede). Em vez disso,
 * [pruneEmptyFolders] filtra diretamente a lista de entradas antes dela chegar em
 * `MediaFilterEngine`, preservando o comportamento de filtro por tipo/3D já existente.
 */
object NetworkFolderProber {

    private const val TTL_FRESH_MS = 24L * 60 * 60 * 1000 // resultado definitivo (scanCompletedFully)
    private const val TTL_TIMED_OUT_MS = 60L * 60 * 1000 // resultado por deadline: revalida bem mais cedo

    // Concorrência da pasta ATUAL (navegação em foco): teto generoso — é uma chamada de
    // listagem "só nomes" (sem decode de mídia), o custo real é número de conexões
    // simultâneas, não CPU/memória (mesmo raciocínio de `MemoryBudgetGate`, mas aqui o
    // recurso limitado é conexão de rede, não memória).
    private val activeFolderSemaphore = Semaphore(3)

    // Concorrência de REVALIDAÇÃO em segundo plano (pastas já cacheadas, fora do foco
    // atual): teto menor, nunca compete pela pasta que o usuário está olhando agora.
    private val backgroundRevalidationSemaphore = Semaphore(1)

    // Escopo compartilhado só para revalidação em segundo plano — deliberadamente não
    // amarrado ao lifecycle de nenhuma tela (a revalidação deve sobreviver à navegação
    // que a disparou), SupervisorJob pra uma revalidação falhando não derrubar as outras.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Filtra `entries` removendo pastas (MediaType.DIRECTORY) confirmadas sem mídia
     * reprodutível. Arquivos (não-diretório) passam direto. Resolve todas as subpastas
     * em paralelo (throttled por [activeFolderSemaphore]) antes de retornar — é o
     * "esperar resolver antes de exibir" pedido para a primeira visita; visitas
     * seguintes são rápidas porque [resolveFolderHasMedia] consulta o cache primeiro.
     *
     * @param folderKeyFor chave de cache (ver [CacheKeys.forFolder]) para cada entrada de pasta.
     * @param scanFnFor a chamada JNI bloqueante específica do protocolo para aquela pasta
     *   (ex. `{ activity.nativeSmbScanFolderHasMedia(...) }`) — só invocada em cache miss
     *   ou revalidação, nunca em cache hit fresco.
     */
    suspend fun pruneEmptyFolders(
        context: Context,
        sourceKind: String,
        entries: List<MediaEntry>,
        folderKeyFor: (MediaEntry) -> String,
        scanFnFor: (MediaEntry) -> suspend () -> String
    ): List<MediaEntry> = coroutineScope {
        val directories = entries.filter { it.type == MediaType.DIRECTORY }
        if (directories.isEmpty()) return@coroutineScope entries

        val hasMediaByPath = directories
            .map { entry ->
                async {
                    entry.path to resolveFolderHasMedia(context, sourceKind, folderKeyFor(entry), scanFnFor(entry))
                }
            }
            .awaitAll()
            .toMap()

        entries.filter { it.type != MediaType.DIRECTORY || hasMediaByPath[it.path] != false }
    }

    /**
     * Resolve se a pasta identificada por `folderKey` tem mídia, priorizando o cache.
     * `scanFn` só é invocada em cache miss (pasta nunca vista, ou TTL expirado) — nesse
     * caso a chamada é bloqueante e usa a concorrência "pasta atual" ([activeFolderSemaphore]).
     * Num cache hit fresco, retorna na hora e (se expirado) dispara revalidação em
     * segundo plano com prioridade baixa, sem bloquear o chamador.
     */
    suspend fun resolveFolderHasMedia(
        context: Context,
        sourceKind: String,
        folderKey: String,
        scanFn: suspend () -> String
    ): Boolean {
        val dao = AppDatabase.getInstance(context).folderMediaStatusDao()
        val cached = dao.find(folderKey)

        if (cached != null) {
            val ttl = if (cached.scanCompletedFully) TTL_FRESH_MS else TTL_TIMED_OUT_MS
            val isStale = System.currentTimeMillis() - cached.lastCheckedAt >= ttl
            if (isStale) {
                revalidateInBackground(context, sourceKind, folderKey, scanFn)
            }
            return cached.hasPlayableMedia
        }

        return activeFolderSemaphore.withPermit {
            // Pode ter sido preenchido por outra chamada concorrente enquanto esperava o permit.
            dao.find(folderKey)?.let { return@withPermit it.hasPlayableMedia }
            scanAndCache(context, sourceKind, folderKey, scanFn)
        }
    }

    private fun revalidateInBackground(context: Context, sourceKind: String, folderKey: String, scanFn: suspend () -> String) {
        backgroundScope.launch {
            backgroundRevalidationSemaphore.withPermit {
                scanAndCache(context, sourceKind, folderKey, scanFn)
            }
        }
    }

    private suspend fun scanAndCache(context: Context, sourceKind: String, folderKey: String, scanFn: suspend () -> String): Boolean {
        val wire = withContext(Dispatchers.IO) { runCatching { scanFn() }.getOrNull() }
        // Falha de rede/parse: NÃO esconde a pasta (assume que pode ter conteúdo), mas com
        // completedFully=false pra revalidar bem mais cedo (mesmo tratamento do deadline).
        val (hasMedia, completedFully) = parseWire(wire) ?: (true to false)

        val entry = FolderMediaStatus(
            folderKey = folderKey,
            hasPlayableMedia = hasMedia,
            scanCompletedFully = completedFully,
            lastCheckedAt = System.currentTimeMillis(),
            sourceKind = sourceKind
        )
        runCatching { AppDatabase.getInstance(context).folderMediaStatusDao().upsert(entry) }
        return hasMedia
    }

    // "1\t1" (has_media\tcompleted_fully) -> (true, true); "ERROR:..."/null/qualquer coisa
    // que não seja exatamente 2 campos "0"/"1" -> null (tratado como falha, nunca cacheado
    // como "confirmado vazio"). Função pura, testável na JVM sem tocar Room/JNI.
    internal fun parseWire(wire: String?): Pair<Boolean, Boolean>? {
        if (wire == null || wire.startsWith("ERROR:")) return null
        val parts = wire.split("\t")
        if (parts.size != 2) return null
        val hasMedia = when (parts[0]) { "1" -> true; "0" -> false; else -> return null }
        val completedFully = when (parts[1]) { "1" -> true; "0" -> false; else -> return null }
        return hasMedia to completedFully
    }
}
