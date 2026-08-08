plugins {
    id("com.android.application") version "8.1.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    // T9.1: KSP para o Room (androidx.room:room-compiler) — mais rapido que
    // kapt e e o caminho recomendado atual para Room 2.6+. Versao
    // "1.9.0-1.0.13" confirmada como existente no Maven Central (repositorio
    // com.google.devtools.ksp) e casada com o Kotlin 1.9.0 ja usado no
    // projeto (ver plugin acima) — o sufixo apos o "-" e a versao do proprio
    // KSP, independente da versao do Kotlin.
    id("com.google.devtools.ksp") version "1.9.0-1.0.13" apply false
}
