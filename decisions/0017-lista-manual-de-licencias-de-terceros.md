# ADR-0017: Lista manual de licencias de terceros, no un plugin automático

- Estado: Aceptado e implementado
- Fecha: 2026-09-28

## Contexto

RNF-11 exige que la app muestre los avisos de licencia de sus dependencias.
Dos frentes ya se sabían de antemano imposibles de automatizar del todo:

- **libwebp**, vendorizado en `:webp` (ADR-0005): no tiene coordenada Maven,
  así que ninguna herramienta que lea el grafo de dependencias de Gradle
  puede encontrarlo. Su `COPYING` y su `PATENTS` hay que incluirlos a mano.
- **Fredoka y Karla**, vendorizadas como recursos (`res/font`) por la
  dirección visual "Plancha de stickers": tampoco tienen coordenada Maven.
  Sus textos OFL-1.1 ya estaban en `assets/licenses/` antes de este ADR.

Quedaba por decidir qué hacer con el resto: todo lo que sí llega por
Gradle (AndroidX, Compose, Kotlin, kotlinx.coroutines, y lo que traigan de
transitivo). Dos opciones de terceros se descartaron sin necesidad de
evaluarlas más:

- `com.google.android.gms:oss-licenses-plugin` — trae Play Services,
  prohibido explícitamente por las restricciones del proyecto.
- Cualquier plugin de licencias que resuelva por red en tiempo de build
  (por ejemplo, consultando un registro de SPDX en línea) — no hay problema
  de RNF-01 en sí (es tiempo de build, no la app en el teléfono), pero suma
  un punto de fallo a un build que ya se documentó como frágil (ADR-0005) y
  que hoy compila sin red más allá de lo que Gradle ya necesita.

Un plugin de licencias "normal" (que lee el POM resuelto de cada
dependencia, sin red adicional, tipo `gradle-license-plugin`) sí era viable
técnicamente. Se optó por no usarlo, por lo que sigue.

## Decisión

**Lista a mano** (`ThirdPartyLicense.kt`), agrupada por quién publica cada
dependencia y no artefacto por artefacto — `releaseRuntimeClasspath` con
transitivas tiene 114 coordenadas reales (medido con
`./gradlew app:dependencies --configuration releaseRuntimeClasspath`), casi
todas AndroidX/Compose/Kotlin bajo la misma licencia; listarlas una por una
sería ilegible en una pantalla de créditos y no aporta nada que agrupar por
publicador no dé ya.

Motivo para no usar un plugin, más allá de los dos descartes de arriba: aun
con un plugin, libwebp y las fuentes vendorizadas siguen necesitando trabajo
manual (no tienen coordenada Maven). El conjunto que un plugin sí resolvería
automáticamente —AndroidX, Kotlin, kotlinx— cambia con poca frecuencia (la
última dependencia nueva fue `navigation-compose`, ADR-0013, hace dos días)
y ya estaba verificado a mano para esta fase. Sumar una herramienta nueva al
build para automatizar la parte más chica y estable del problema, dejando
la parte más difícil (libwebp, fuentes) igual de manual que antes, no
compensaba el costo de una dependencia de build nueva.

**Verificación de cada licencia, no asumida:** para las coordenadas fuera
de AndroidX/Kotlin (`com.google.guava:listenablefuture`,
`org.jetbrains:annotations`, `org.jspecify:jspecify`), la licencia se
verificó contra el `LICENSE`/manifiesto real de cada proyecto (extraído de
los `.jar`/`.aar` en caché de Gradle, o del repositorio de origen), no
asumida por ser "del ecosistema de Kotlin/Google". Las 114 coordenadas de
`releaseRuntimeClasspath` son Apache License 2.0.

**Detección de drift, en vez de disciplina manual sin red de seguridad:**

1. `releaseRuntimeClasspathReport` (`app/build.gradle.kts`) vuelca
   `releaseRuntimeClasspath` —con transitivas, no `gradle/libs.versions.toml`
   a mano— a un archivo antes de correr los tests.
2. `ThirdPartyLicensesCoverageTest` compara ese archivo contra
   `KnownRuntimeDependencies` (qué prefijos de grupo ya están cubiertos en
   la pantalla) y falla si aparece una coordenada nueva sin clasificar. La
   prueba no verifica la licencia por sí sola —eso exigiría red o asumir
   sin comprobar—, exige que quien vea fallar el test la verifique a mano
   antes de sumar la coordenada a la lista cubierta, igual que se hizo acá.
3. `LicenseAssetsSyncTest` compara las copias embebidas en `assets/`
   (`gpl-3.0.txt`, `libwebp-COPYING.txt`, `libwebp-PATENTS.txt`) contra sus
   fuentes de verdad (`LICENSE` de la raíz, y la copia vendorizada de
   libwebp en `:webp/src/main/cpp/third_party/libwebp/`). Importa sobre
   todo para libwebp: el procedimiento de actualización manual de ADR-0005
   reemplaza esos archivos y no menciona refrescar la copia de
   `assets/` — sin esta prueba, un `COPYING`/`PATENTS` nuevo podría quedar
   sin reflejarse en la pantalla en silencio.

**Revisado si alguna dependencia trae un `NOTICE`** (Apache License 2.0
§4d): no. Se inspeccionaron los `.aar`/`.jar` en caché de las dependencias
representativas de cada grupo (AndroidX, Kotlin, kotlinx.coroutines,
JetBrains Annotations, JSpecify, Guava ListenableFuture) buscando
`META-INF/NOTICE*`. Varias AAR de AndroidX embeben su propio
`LICENSE.txt` (el texto de Apache-2.0 sin modificar, sin atribución
adicional), pero ninguna trae un `NOTICE` separado. No hay nada que sumar
a la pantalla por esta cláusula.

## Consecuencias

- La pantalla de licencias no se actualiza sola cuando cambia una
  dependencia: hay que correr `./gradlew test` (ya documentado en "Cómo
  compilar"), ver si `ThirdPartyLicensesCoverageTest` falla, verificar la
  licencia de la coordenada nueva a mano, y sumarla a
  `KnownRuntimeDependencies` y a `ThirdPartyLicense.kt`.
- Actualizar libwebp (procedimiento de ADR-0005) o cambiar la licencia
  propia del repo sin refrescar `assets/licenses/` hace fallar
  `LicenseAssetsSyncTest` en vez de dejar la pantalla desactualizada en
  silencio.
- Si en algún momento el número de dependencias reales crece mucho más
  allá de lo que hay hoy (114 coordenadas, ~10 filas agrupadas), vale la
  pena reabrir esta decisión: un plugin de licencias amortiza mejor cuanto
  más grande y más rotativo es el árbol de dependencias.
