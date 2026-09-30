# SpeedBuilders

Minijuego **Speed Builders** para **Paper 1.20.4** (Java 17+).

Los jugadores memorizan una construcción que aparece durante 20 segundos en su plataforma de 5x5 y, cuando desaparece, deben replicarla lo más rápido posible. En cada ronda se elimina a quienes no la completan, y los eliminados pasan a modo espectador. Nunca se juegan más de 10 rondas.

## Cómo se juega

1. **Lobby**: todos los jugadores entran automáticamente a la arena al conectarse (hasta 128 por partida; quien sobra o llega con la partida en curso entra como espectador). La partida solo empieza cuando un administrador usa `/sb iniciar`.
2. **Plataformas**: se generan **simétricas alrededor del centro**, una por jugador y con espacio entre ellas: en un círculo si son pocas, o en anillos concéntricos si son muchas. Cada isla tiene una zona de construcción de **5x5** rodeada de un borde decorativo.
3. **Memorizar (20 s)**: aparece en cada plataforma una construcción con nombre en español (*Pirámide*, *Casita de madera*, *Templo griego*...).
4. **Construir**: la construcción desaparece y cada jugador recibe justo los bloques que necesita. La **action bar** muestra en tiempo real el **% de similitud** con una barra de colores.
   - Al llegar al 100 % el jugador **clasifica** y recibe título, sonido y partículas.
   - Solo clasifica un número limitado de jugadores (el cupo). Cuando se llena, la ronda termina y **los demás quedan eliminados**.
   - Si se acaba el tiempo, **quien no la completó queda eliminado**.
5. **Resultados (15 s)**: se muestra la clasificación de la ronda con los puntos de cada uno, la lista de eliminados con el **puesto en que quedaron**, y las islas de los eliminados explotan una tras otra.
   - En la siguiente ronda, las islas de los que siguen en juego se reconstruyen **más cerca del centro**.
   - Nadie puede alejarse más de 3 bloques de su isla (`distancia-maxima`). Los eliminados pasan a **espectador**.
6. **Final**: gana el último en pie, con fuegos artificiales, y se muestra el top 5 de la partida. Después todos vuelven al lobby para la siguiente partida.

### Puntos y récords

- **Puntos por orden de llegada**: 1.º 10, 2.º 8, 3.º 6, 4.º 5, 5.º 4 y el resto 3; el ganador suma 25 más (configurable en `puntos`).
- **Récords históricos**: al mostrar cada construcción, el chat enseña su top 5 de mejores tiempos de siempre. Si alguien bate el récord se anuncia. Se guardan en `records.yml`.

### Panel para operadores

- **Hologramas** sobre cada plataforma con el nombre, la barra de progreso, el porcentaje y los puntos de cada jugador. Solo los ven quienes tienen `speedbuilders.admin`.
- **Scoreboard de operador** con el top 5 de puntos en vivo (también en modo editor).
- `/sb top`: tabla con el top 5 de puntos y su progreso. `/sb progreso`: el porcentaje de cada jugador vivo.

### Reglas de eliminación

- En cada ronda se elimina al menos el `porcentaje-eliminacion` de los vivos (25 % por defecto, mínimo 1).
- El cupo se ajusta solo para que la partida **termine como máximo en 10 rondas**.
- Si nadie completa la construcción, se salvan los que tengan mayor similitud y se elimina el porcentaje correspondiente, para que la partida no acabe sin ganador.
- La dificultad sube por rondas: 1-3 fácil, 4-7 media y 8-10 difícil.

### Similitud

Se compara bloque a bloque la zona de 5x5 con la construcción original:

| Caso | Puntos |
|------|--------|
| Bloque correcto | 1 |
| Bloque que falta o que sobra | 0 |

Por defecto la **orientación no cuenta** (escaleras, troncos, calabazas, conexiones de vallas...), porque depende de cómo se coloca el bloque y podía impedir llegar al 100 %. Con `similitud.exigir-orientacion: true` se exige, y un bloque mal orientado vale 0,5.

Romper bloques de tu propia zona es instantáneo y te devuelve el bloque al inventario.

## Instalación

1. Descarga el `.jar` desde la pestaña **Actions** del repositorio (artefacto `SpeedBuilders`) o compílalo con `./gradlew build` (queda en `build/libs/`).
2. Copia el `.jar` en la carpeta `plugins/` de tu servidor Paper 1.20.4 y reinicia el servidor.

## Configuración de la arena

Lo recomendado es usar un mundo vacío (void), porque las plataformas reemplazan lo que haya en su lugar.

```
/sb setlobby          # donde esperan los jugadores
/sb setcentro         # párate en el centro: el bloque bajo tus pies fija la altura
/sb generar 8         # (opcional) ver cómo quedan 8 plataformas
/sb limpiar           # quitar las plataformas de prueba
```

Mientras configuras quedas en **modo editor**, fuera de la arena y con tu inventario. Cuando termines, usa `/sb editar` para entrar al lobby y `/sb iniciar` para empezar (también sirve para probar solo).

## Comandos

| Comando | Descripción | Permiso |
|---------|-------------|---------|
| `/sb iniciar` | Inicia la partida con los jugadores del lobby | `speedbuilders.admin` |
| `/sb top` | Tabla con el top 5 de puntos y su progreso | `speedbuilders.admin` |
| `/sb progreso` | Porcentaje de cada jugador vivo en la ronda | `speedbuilders.admin` |
| `/sb editar` | Sale o entra de la arena para configurar y construir | `speedbuilders.admin` |
| `/sb setcentro` | Fija el centro de la arena | `speedbuilders.admin` |
| `/sb setlobby` | Fija el lobby de espera | `speedbuilders.admin` |
| `/sb detener` | Detiene la partida y vuelve todos al lobby | `speedbuilders.admin` |
| `/sb generar [n]` | Genera `n` plataformas de prueba | `speedbuilders.admin` |
| `/sb limpiar` | Quita las plataformas de prueba | `speedbuilders.admin` |
| `/sb pegar <id>` | Coloca una construcción en la plataforma donde estás | `speedbuilders.admin` |
| `/sb guardar <id> <facil\|medio\|dificil> <nombre>` | Guarda lo construido en tu plataforma | `speedbuilders.admin` |
| `/sb construcciones` | Lista las construcciones | `speedbuilders.admin` |
| `/sb recargar` | Recarga los archivos de configuración | `speedbuilders.admin` |

## Archivos

- `config.yml`: jugadores, tiempos, rondas, porcentaje de eliminación y materiales de las plataformas.
- `mensajes.yml`: todos los textos, en [MiniMessage](https://docs.advntr.dev/minimessage/format.html) (chat, títulos, action bar, bossbar y scoreboard).
- `construcciones.yml`: incluye 36 construcciones (14 fáciles, 14 medias y 8 difíciles) en un formato de capas fácil de editar.
- `arena.yml`: centro y lobby (se genera solo).

### Crear construcciones desde el juego

1. Ejecuta `/sb generar 1` y párate sobre la plataforma.
2. Construye dentro de la zona blanca de 5x5.
3. Ejecuta `/sb guardar molino dificil Molino de viento`.

Estará disponible al instante y queda guardada en `construcciones.yml`.

## Compilar

```
./gradlew build
```

Requiere JDK 17 o superior. El `.jar` queda en `build/libs/SpeedBuilders-1.0.0.jar`.
