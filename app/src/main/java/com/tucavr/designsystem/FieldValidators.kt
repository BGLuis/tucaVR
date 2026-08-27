package com.tucavr.designsystem

/**
 * Validadores puros para campos de texto.
 *
 * Todas as funções são puras (sem dependência do framework Android), permitindo
 * execução direta e testes unitários no ambiente JVM de desenvolvimento.
 */
object FieldValidators {

    /**
     * Valida se o texto não é vazio nem composto apenas por espaços em branco.
     */
    fun required(errorMsg: String = "Campo obrigatório"): (String) -> String? = { value ->
        if (value.trim().isEmpty()) errorMsg else null
    }

    /**
     * Valida porta TCP/UDP (1–65535). Se vazio, retorna null (porta opcional com valor padrão no caller).
     */
    fun port(errorMsg: String = "Porta inválida (1–65535)"): (String) -> String? = { value ->
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            null
        } else {
            val num = trimmed.toIntOrNull()
            if (num != null && num in 1..65535) null else errorMsg
        }
    }

    /**
     * Valida porta TCP/UDP (1–65535) obrigatória (não pode ser vazia).
     */
    fun requiredPort(errorMsg: String = "Porta inválida (1–65535)"): (String) -> String? = { value ->
        val trimmed = value.trim()
        val num = trimmed.toIntOrNull()
        if (num != null && num in 1..65535) null else errorMsg
    }

    /**
     * Valida formato de URL com esquemas de mídia suportados (http, https, smb, ftp, sftp, nfs, etc.).
     */
    fun url(errorMsg: String = "Formato de URL inválido"): (String) -> String? = { value ->
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            errorMsg
        } else {
            val supportedSchemes = listOf(
                "http://", "https://", "smb://", "ftp://", "sftp://", "nfs://", "rtsp://", "udp://", "hls://"
            )
            val lower = trimmed.lowercase()
            val hasValidScheme = supportedSchemes.any { lower.startsWith(it) }
            if (hasValidScheme && trimmed.length > 7) null else errorMsg
        }
    }

    /**
     * Combina múltiplos validadores em cadeia, retornando a mensagem do primeiro que falhar.
     */
    fun combine(vararg validators: (String) -> String?): (String) -> String? = { value ->
        var error: String? = null
        for (validator in validators) {
            val res = validator(value)
            if (res != null) {
                error = res
                break
            }
        }
        error
    }
}
