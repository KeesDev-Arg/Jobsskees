# JobsSkees (etapa 1)

Plugin aparte que da **1 punto de talento por cada nivel** de cada trabajo de Jobs, y los gasta en un **árbol propio de cada trabajo**. Se abre con `/oficios` (alias `/talentos`).

## Estado: NO COMPILADO Y NO PROBADO

Se escribió sin poder compilarlo ni ejecutarlo. Puede tener errores de compilación; si aparecen, copiá el mensaje completo para corregirlo. Tampoco se verificó con Purpur 26.3: se compila contra la API de Paper 1.21.4 (la misma base que usó RangoSync).

Esta etapa trae **un solo árbol: Minero**, con 34 talentos y 200 puntos en total (se completa al nivel 200). Los otros 11 trabajos se agregan después, como archivos de configuración, sin tocar el código.

## Nombre, comando y permisos

- **Plugin**: `JobsSkees` (archivo `JobsSkees-0.1.0.jar`, carpeta de datos `plugins/JobsSkees/`).
- **Comando**: se mantiene `/oficios`, con los alias `/talentos` y `/jobsskees`.
- **Permisos**: `jobsskees.use` (abrir el menú, activo para todos por defecto) y `jobsskees.admin` (subcomandos de administración, solo OP por defecto).

## Cómo funciona

- **Puntos por trabajo**: puntos totales = nivel del trabajo × `points-per-level`. Cada trabajo tiene su propia reserva.
- **Nivel**: se lee con PlaceholderAPI. Por defecto `%jobsr_user_jlevel_{job}%`, el que figura en la documentación oficial de Jobs. **Hay que comprobarlo** (ver "Primera prueba").
- **Si un jugador baja de nivel** (por ejemplo, al dejar el trabajo): se suspenden primero los talentos más recientes hasta que los puntos alcancen. Los datos no se borran y se reactivan al recuperar nivel.
- **Reiniciar un árbol**: botón con confirmación (dos clicks) y espera de 24 h entre reinicios.
- **Datos**: `plugins/JobsSkees/data/<uuid>.yml`.

## Tipos de efecto (por ahora)

| Tipo | Qué hace |
|---|---|
| `drop_bonus` | Cada efecto tiene su propia probabilidad de sumar **1 objeto extra** al drop principal de los bloques indicados. Se suma a la Fortuna del pico. No actúa con Toque de seda ni en creativo. |
| `durability_save` | Probabilidad de que un uso no gaste durabilidad de ciertos objetos (por ejemplo, `_PICKAXE`). |
| `attribute` | Modificador de un atributo de Minecraft (por ejemplo, `minecraft:block_break_speed`). Los modificadores son propios del plugin y se quitan solos si se pierde el talento. |
| `commands` | Comandos de consola al activar o desactivar el talento. Sirve para dar permisos con LuckPerms: `lp user {uuid} permission set ...`. |

Variables en los comandos: `{player}` y `{uuid}`.

## El árbol de Minero

| Rama | Talentos | Efecto total | Puntos |
|---|---|---|---|
| Veta (fila 1) | 6 | +0,30 de Fortuna (probabilidad de objeto extra al picar menas) | 54 |
| Caída suave (fila 1) | 3 | +3 bloques de caída segura | 12 |
| Rapidez (fila 2) | 9 | +18 % de velocidad al romper bloques | 38 |
| Resistencia (fila 3) | 8 | +16 % de no gastar durabilidad del pico | 36 |
| Alcance (fila 4) | 5 | +0,5 bloques de alcance | 26 |
| Comodidad (fila 4) | 3 | `/workbench`, `/enderchest` y `/anvil` | 34 |

Cada talento de una rama pide el anterior. Los de comodidad son independientes.

## Requisitos

- Purpur/Paper con **PlaceholderAPI** y **Jobs** (ya los tenés). LuckPerms solo si usás talentos con permisos.

## Cómo compilarlo

Igual que RangoSync: **Opción A** en tu PC con JDK 21 y Maven (`mvn package`, el `.jar` queda en `target/JobsSkees-0.1.0.jar`), u **Opción B** subiendo el proyecto a GitHub (el flujo `.github/workflows/build.yml` ya viene incluido).

## Primera prueba (en tu copia local)

1. Copiá el `.jar` a `plugins/` y arrancá.
2. **Comprobá el placeholder del nivel** con un jugador conectado que tenga el trabajo Miner:
   `papi parse <jugador> %jobsr_user_jlevel_Miner%`
   Tiene que devolver un número. Si no, ejecutá `jobs placeholders` en la consola para ver la lista que acepta tu versión, y corregí `level-placeholder` en `plugins/JobsSkees/config.yml`.
3. `oficios info <jugador>` en la consola: muestra el nivel que lee el plugin, los puntos y los nodos.
4. Con una cuenta de nivel 4 o más en Miner: `/oficios` y abrí el árbol. Tiene que dejarte desbloquear "Veta I" (4 puntos).
5. Probá cada tipo de efecto con valores exagerados: en `plugins/JobsSkees/trees/miner.yml` poné temporalmente `chance: 1.0` en un talento de Veta y de Resistencia, y `oficios reload`.
   - Con Veta: cada mena tiene que soltar 1 objeto extra.
   - Con Resistencia: el pico no tiene que perder durabilidad.
   - Atributos: `attribute <jugador> minecraft:block_break_speed get`.
   - Permisos: `lp user <jugador> permission info` después de desbloquear "Mesa portátil".
6. Reiniciá el árbol con el botón, y comprobá que se quitan los efectos y el permiso.
7. Si en la consola aparece "El atributo ... no existe en esta versión", copiame el aviso.

## Cuidados y límites

- **El nombre del placeholder, los atributos de Minecraft y los permisos de Essentials** (`essentials.workbench`, `essentials.enderchest`, `essentials.anvil`) **no están verificados** en tu servidor.
- **`drop_bonus` no distingue** si la mena fue colocada por un jugador. Fortuna hace lo mismo.
- **No se toca la vida ni el daño del jugador**, porque EliteMobs los gestiona.
- **Bedrock**: los menús son de inventario y deberían funcionar, pero no se probaron.
- Los textos van sin tildes, a propósito, para evitar problemas de codificación.

## Reversión

1. En `plugins/JobsSkees/config.yml` poné `enabled: false` y ejecutá `oficios reload`. Los jugadores conectados pierden los atributos y los permisos de los talentos.
2. Los jugadores desconectados lo pierden la próxima vez que entren **con el plugin todavía instalado**. Dejalo así hasta que todos hayan entrado.
3. Después podés borrar el `.jar`. Los datos de `plugins/JobsSkees/data/` no hacen falta para nada más.

**Si borrás el plugin sin hacer esto**, los modificadores de atributos que ya se aplicaron quedan guardados en el jugador y los permisos siguen dados en LuckPerms.
