# SpeedBuilders

Minijuego **Speed Builders** para **Paper 1.20.4** (Java 17+).

Los jugadores memorizan una construcción que aparece durante 20 segundos en su plataforma de 5x5 y, cuando desaparece, deben replicarla lo más rápido posible. En cada ronda se elimina a quienes no la completan, y los eliminados pasan a modo espectador. Nunca se juegan más de 10 rondas.

## Cómo se juega

1. **Lobby**: los jugadores entran con `/sb unirse`. Al llegar al mínimo empieza una cuenta atrás con títulos, sonidos, bossbar y scoreboard.
2. **Plataformas**: se generan en círculo, **simétricas alrededor del centro**, una por jugador y con espacio entre ellas. Cada isla tiene una zona de construcción de **5x5** rodeada de un borde decorativo.
3. **Memorizar (20 s)**: aparece en cada plataforma una construcción con nombre en español (*Pirámide*, *Casita de madera*, *Templo griego*...).
4. **Construir**: la construcción desaparece y cada jugador recibe justo los bloques que necesita. La **action bar** muestra en tiempo real el **% de similitud** con una barra de colores.
   - Al llegar al 100 % el jugador **clasifica** y recibe título, sonido y partículas.
   - Solo clasifica un número limitado de jugadores (el cupo). Cuando se llena, la ronda termina y **los demás quedan eliminados**.
   - Si se acaba el tiempo, **quien no la completó queda eliminado**.
5. **Resultados**: se muestra la clasificación de la ronda, y las islas de los eliminados explotan. Los eliminados pasan a **espectador**.
6. **Final**: gana el último en pie, con fuegos artificiales. Después todos recuperan su inventario, modo de juego y posición originales.

### Reglas de eliminación

- En cada ronda se elimina al menos el `porcentaje-eliminacion` de los vivos (25 % por defecto, mínimo 1).
- El cupo se ajusta solo para que la partida **termine como máximo en 10 rondas**.
- Si nadie completa la construcción, se salvan los que tengan mayor similitud y se elimina el porcentaje correspondiente, para que la partida no acabe sin ganador.
- La dificultad sube por rondas: 1-3 fácil, 4-7 media y 8-10 difícil.

### Similitud

Se compara bloque a bloque la zona de 5x5 con la construcción original:

| Caso | Puntos |
|------|--------|
| Bloque exacto (incluida la orientación que exige la construcción) | 1 |
| Bloque correcto pero mal orientado | 0,5 |
| Bloque que falta o que sobra | 0 |

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

Para probar solo, únete con `/sb unirse` y fuerza el inicio con `/sb iniciar`.

## Comandos

| Comando | Descripción | Permiso |
|---------|-------------|---------|
| `/sb unirse` | Unirse a la partida (como espectador si ya empezó) | `speedbuilders.jugar` |
| `/sb salir` | Salir de la partida | `speedbuilders.jugar` |
| `/sb setcentro` | Fija el centro de la arena | `speedbuilders.admin` |
| `/sb setlobby` | Fija el lobby de espera | `speedbuilders.admin` |
| `/sb iniciar` | Fuerza el inicio | `speedbuilders.admin` |
| `/sb detener` | Detiene la partida y limpia la arena | `speedbuilders.admin` |
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
