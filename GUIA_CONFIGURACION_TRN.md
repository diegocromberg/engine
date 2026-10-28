# OPTIMIST Engine — Guía de configuración de TRN (YAML)

**Versión del motor: V52** · Esta guía se actualiza con cada versión. Reemplaza a `TRN_YAML.md`.

> **Desde la separación de OPTIMIST Engine (28/10/2026):** esta guía vive en el repositorio `engine`, junto con el motor,
> el Designer y las herramientas. Donde dice `herramientas/...` es `engine/herramientas/...`; donde dice `src/main/resources/...`
> es la carpeta de recursos de cada aplicación (por ejemplo `optimist-erp/src/main/resources/...`). Los ejemplos salen de
> OPTIMIST ERP, la primera aplicación hecha con el motor.

---

## 1. Qué archivos intervienen

| Archivo | Para qué |
|---|---|
| `src/main/resources/trn/<Trn>.yaml` | Una transacción completa: tabla, campos, referencias, detalles, pantalla y WorkWith. **Una TRN nueva = un archivo nuevo. Sin Java.** |
| `src/main/resources/dominios.yaml` | Dominios compartidos por todas las TRN. |
| `src/main/resources/menu.yaml` | Menú lateral: categorías, carpetas e ítems. Una TRN no aparece en el menú hasta que se la agrega acá. |
| `herramientas/gx2yaml.py` | Genera el YAML de una TRN leyendo el export XML de la KB GeneXus. |
| `src/main/resources/proc/<Nombre>.proc` | (V34) Un procedimiento, en el lenguaje de GeneXus. Lo usan las fórmulas (y más adelante reglas y botones). Ver sección 16. |
| `herramientas/gx2proc.py` | (V34) Traduce procedimientos de la KB GeneXus a `.proc`. |
| `herramientas/parchar_reglas.py` | (V35) Pasa las reglas y el evento Start de la KB a las secciones `reglas:` e `inicio:` de los YAML. Ver sección 17. |
| `src/main/resources/consultas/<Nombre>.yaml` | (V52) Una consulta: WorkWith de solo lectura con parámetros y columnas calculadas por el motor (ej. el stock). Ver sección 21. |

La TRN se abre en `/trn/<transaccion en minúsculas>` (por ejemplo `/trn/articulos`).
Después de tocar un YAML hay que volver a generar el WAR (o recargar recursos en desarrollo).

---

## 2. Ejemplo mínimo (2 niveles)

```yaml
transaccion: Monedas
titulo: Monedas
singular: moneda
plural: monedas
genero: f
tabla: Monedas
orden: [MonedasDescrip]

campos:
  - {nombre: MonedasId,      dominio: C04,          etiqueta: Moneda, clave: true}
  - {nombre: MonedasDescrip, dominio: Descrip,      etiqueta: Descripción, obligatorio: true}
  - {nombre: MonedasCodAFIP, dominio: Character(3), etiqueta: Código según AFIP}

detalles:
  - nombre: Cotizacion
    titulo: Cotizaciones
    tabla: MonedasCotizacion
    orden: [MonedasCotizacionFecha DESC]
    campos:
      - {nombre: MonedasCotizacionFecha, dominio: Fecha,       etiqueta: Fecha, clave: true}
      - {nombre: MonedasCotizacionValor, dominio: Importe4Dec, etiqueta: Cotización, obligatorio: true}

workwith:
  columnas: [MonedasId, MonedasDescrip, MonedasCodAFIP]
```

Y en `menu.yaml`:
```yaml
Menú General:
  - {titulo: Monedas, trn: Monedas}
```

---

## 3. Datos generales de la TRN

| Clave | Obligatoria | Descripción |
|---|---|---|
| `transaccion` | sí | Nombre de la TRN. Define la ruta `/trn/<nombre en minúsculas>`. |
| `titulo` | no | Título de la pantalla (por defecto, el nombre). |
| `singular` / `plural` | no | Para los textos: "Nueva **moneda**", "4 **monedas**". |
| `genero` | no | `f` para femenino (Nueva / guardada / borrada). Por defecto masculino. |
| `ruta` | no | Otra URL en lugar del nombre (minúsculas, números, `-`, `_`). |
| `tabla` | no | Tabla física de la cabecera (por defecto, el nombre de la TRN). |
| `orden` | no | Orden del WorkWith: `[Campo]` o `[Campo DESC]`. Por defecto, la clave. (V41) También un campo inferido, ej. la razón social del cliente; en los detalles, solo campos propios. |
| `auditoria` | no | Campos que el motor completa solo al grabar (ver 3.1). |
| `encabezado` | no | Título del formulario con campos entre llaves (ver 3.2). |

### 3.1 Auditoría
```yaml
auditoria: {altaFecha: ArticulosCreateDate, altaUsuario: ArticulosCreateUserName,
            modificacionFecha: ArticulosLastModifiedDate, modificacionUsuario: ArticulosLastModifiedUserName}
```
En el alta se completan `altaFecha`/`altaUsuario`; en cada modificación, `modificacionFecha`/`modificacionUsuario`. El usuario es el que inició sesión. (V61b) En el alta, `modificacionFecha` queda en la fecha vacía de GeneXus (1000-01-01) y `modificacionUsuario` vacío, también en una duplicación (antes la copia se quedaba con los del original).

**En la pantalla (V61c)**, la auditoría va siempre en dos renglones (en todas las TRN con `auditoria:`, y así la genera `gx2yaml.py`):
```
  <solapa Auditoría>
    <tabla anchos=300px,1fr>
      TcbteCreateDate          TcbteCreateUserName
      TcbteLastModifiedDate    TcbteLastModifiedUserName
    <endtable>
  <endsolapa>
```

`anchos=300px,1fr` deja la primera columna (las fechas) de ancho fijo, así el usuario queda al lado y no en el otro extremo del formulario. Va en una tabla propia: si los renglones de auditoría compartieran tabla con otros campos, el ancho cambiaría también los demás.

**Fecha vacía (V61b):** como en GeneXus, una fecha vacía se graba como `1000-01-01`, aunque el campo tenga `nulo: true` (en un procedimiento, `SetEmpty()` también; `SetNull()` deja NULL). Al leer, cualquier fecha del año 1000 o anterior se toma como vacía: no se muestra en el formulario, los web panels, el WorkWith ni el Excel, y `IsEmpty()` da verdadero.

### 3.2 Encabezado (título del formulario)
```yaml
encabezado: "Artículo {ArticulosId} - {ArticulosDescrip}"
encabezado: "Pedido {PedidosNumero} de {ClientesRazonSocial}"     # -> Pedido 45.345 de Industrias Meloni S.A.
```
- Va arriba de todo, fuera de las solapas: siempre sabés en qué registro estás.
- Admite campos propios e inferidos, todos los que quieras.
- Números enteros con separador de miles, fechas dd/MM/yyyy, combos con su texto.
- Se actualiza mientras se edita (al cambiar el cliente, cambia la razón social del título).
- En un alta muestra "Nuevo artículo" hasta que se completen los campos del encabezado.
- Sin `encabezado`: singular + clave ("Artículo 436").

---

## 4. Dominios

Se definen en `dominios.yaml` (para todas las TRN) o en un bloque `dominios:` dentro de la TRN (pisa al global).

**Notación GeneXus** (lo más cómodo):
```yaml
dominios:
  Fecha: Date
  N09: Numeric(9)
  Importe4Dec: Numeric(14.4)     # 14 dígitos en total, 4 decimales
  Descrip: VarChar(50)
  C04: Character(4)
```

**Punto de miles** (V33): el número se muestra `1.234.567,89` en el formulario, el WorkWith y los detalles. Se escribe como uno quiera (`1234,5`, `1.234,50` o con el punto del teclado numérico) y al salir del campo queda formateado.
- Para todas las TRN, en `dominios.yaml`: `conMiles: [Precio, Total, Cantidad]` (vale aunque la TRN defina el dominio en su archivo). Ya vienen los que en GeneXus tienen "Thousands separator", más Precio.
- Para uno solo: `Precio: Numeric(14.4) miles` o, como bloque, `miles: true`.

**Como bloque** (necesario para combos):
```yaml
  ManejoStock:
    tipo: Numeric(1)
    valores:                      # combo: se guarda 'valor', se muestra 'texto'
      - {valor: 1, texto: Por Bulto}
      - {valor: 2, texto: Por Cantidad}
      - {valor: 3, texto: Por Bulto y Cantidad}
```

| Tipo | Control en pantalla | MySQL |
|---|---|---|
| `Character(n)` | texto, máximo n | CHAR |
| `VarChar(n)` / `LongVarChar(n)` | texto, máximo n | VARCHAR |
| `Numeric(n)` | número entero, alineado a la derecha | INT / BIGINT / DECIMAL |
| `Numeric(n.d)` | número con d decimales (formato argentino) | DECIMAL(n,d) |
| `Date` | fecha dd/MM/yyyy | DATE |
| `DateTime` | fecha y hora | DATETIME |
| `Boolean` | casilla | TINYINT(1) / BIT |
| cualquiera con `valores` | combo | el del tipo |

En un campo también se puede poner el tipo directo: `dominio: Character(3)`.

**Ojo con los códigos con cero adelante** (V59f): en `valores`, un código como `001` o `010` va **entre comillas** (`valor: "001"`). Sin comillas YAML lo lee como número (y en octal: `010` es 8) y el combo no coincide con lo grabado ("valor no permitido").

**Dominio `Password`** (V59d): un dominio de texto que se llame `Password` (en `dominios.yaml`, en la TRN o en el web panel: `Password: VarChar(100)`) se ve como campo de contraseña: no se ve lo que se escribe (el ojo lo muestra). Sirve en las TRN y en los web panels. **Necesita el motor V59d** (con un motor anterior se ve como texto común).

---

## 5. Campos

Cada campo es una línea `- {nombre: ..., dominio: ..., etiqueta: ..., <opciones>}`.
`nombre` es el nombre de la columna en MySQL.

| Opción | Qué hace |
|---|---|
| `etiqueta` | Texto que ve el usuario. |
| `clave: true` | Parte de la clave primaria. Es obligatoria y no se modifica una vez grabada. |
| `autonumerico: true` | Clave numerada por MySQL (AUTO_INCREMENT). En el alta muestra "automático". |
| `siguiente: true` | (V65) Clave numérica entera: en el alta propone el mayor de la tabla + 1 y se puede cambiar (si otro graba antes el mismo número, avisa "Ya existe"). Con clave compuesta, entre los registros con las demás claves en su `default` (ej. `&Empresa`). En la clave de un **detalle**: el renglón nuevo toma el mayor de la grilla + 1 al empezar a cargarlo (ej. el número de cuota de CondPago). |
| `obligatorio: true` | No se puede grabar vacío. |
| `mensaje: "..."` | Mensaje propio cuando falta un obligatorio (el `Error(...)` de GeneXus). |
| `default: valor` | Valor inicial en el alta. En combos, el `valor` (no el texto). Con `"&Empresa"` (entre comillas) toma el valor del contexto del usuario: ver sección 9.1. Con `"&Today"` la fecha del día y con `"&Now"` la fecha y hora (V48c); con `"&NewGuid"` un UUID nuevo (V83, el `GUID.NewGuid()` de GeneXus); vale también para las altas que hace un procedimiento con Business Components (ej. Generar Pedido). |
| `soloLectura: true` | Se ve pero no se edita (el `NoAccept` de GeneXus). |
| `oculto: true` | Se guarda pero no se muestra. Al modificar conserva su valor; en el alta va vacío o con su `default`. Para volver a mostrarlo, se borra el `oculto: true` (no hace falta escribir `false`). |
| `inferido: true` | Viene de otra tabla por una referencia (ver 6). No se guarda en esta tabla. |
| `formula: "..."` | (V34) Atributo fórmula de GeneXus: **se muestra de solo lectura y se recalcula en vivo** con cada cambio del formulario. No se guarda. Ver 5.1. |
| `guardar: true` | Junto con `formula`: fórmula **redundante** (GeneXus la guarda en la tabla). El motor la calcula al grabar, con todo lo de 5.1. (V42) Si no se puede calcular, no se graba: queda lo que había. Como está en la tabla, puede ser columna, oculta u orden del WorkWith. Puede sumar campos de los detalles. |
| `nulo: true` | Vacío se graba como NULL (por defecto: `''` en textos, `0` en números, NULL en fechas). |
| `editableEnModificacion: false` | Solo se carga en el alta. |
| `visibleSi: ModuloMutual` | Solo se ve si se cumple la condición (condiciones registradas en el motor). |
| `ancho: 300px` | Ancho del campo (también `30%`, `20ch`; `300` = `300px`). Por defecto, según el dominio. |
| `estilo: {negrita: true, italica: true, letra: Consolas, tamaño: "+2px", color: "30,100,180"}` | (V33) Letra de la etiqueta y del contenido del campo. (V46) **El `tamaño:` es relativo a la letra que eligió el usuario**: ver 5.3. Cualquier combinación; tiene prioridad sobre Apariencia. El color entre comillas: `"30,100,180"` (rojo, verde, azul, V45), `"#1e64b4"` o un nombre (`red`). (V45) `letra:` es la familia de letra (Arial, Calibri, "Courier New"...; varias separadas por coma, se usa la primera que tenga la PC) y **manda sobre la letra que eligió el usuario**. `negrita: false` / `italica: false` le sacan a un campo lo que le pone su clase. Manda sobre la clase (ver 5.2). En los detalles se aplica a cada línea. |
| `clase: Importe` | (V45) Estilo con nombre, definido una vez en `clases:` (ver 5.2). Varias: `clase: [Importe, Rojo]` (la de la derecha manda). |
| `insignia: {Aprobado: verde, Perdido: rojo}` | (V40) El valor se ve como una **pastilla** (recuadro redondeado de color) en el WorkWith y en el formulario. Cada valor del combo (por su texto o su código) con un color; los que no figuran, en gris. Colores: `azul, verde, verde-oscuro, rojo, marron, magenta, celeste, turquesa, violeta, naranja, amarillo, gris, gris-oscuro` (cada uno con su tono para modo claro y oscuro). |
| `insignia: {colorDe: StatusLogisticoColor}` | (V40) El color sale de otro campo de la misma fila, cuyo texto es un color ("Azul", "Marrón"...): así lo elige el cliente desde su tabla. Ese campo puede estar oculto; el WorkWith lo trae solo. |
| `renglones: 4` | Alto de un campo de texto, en renglones (1 a 30). **Automático:** los textos de 512 caracteres o más (`VarChar(512)`, `LongVarChar`) ya salen con 3 renglones sin escribir nada. `renglones: 1` fuerza una sola línea. En las grillas de detalle siempre es una línea. |

**GUID (V83)**: `dominio: GUID` es el GUID de GeneXus: texto de 36 caracteres. Con `default: "&NewGuid"` se genera solo en el alta.

### 5.4 Archivos (el Blob de GeneXus, V83)

Los atributos Blob de la cabecera NO van en `campos`: van en `archivos:`. El formulario los muestra al pie, en el panel **Archivos**:
por cada uno, el nombre del archivo grabado, **Bajar**, **Elegir otro…** y **Quitar**. Lo elegido o quitado se graba al **Confirmar**
(en la misma transacción que el resto; en el alta también), antes de las reglas de después de grabar (AfterInsert,
AfterUpdate, BeforeComplete), que ya ven el archivo nuevo, como en GeneXus. Bajar trae lo que está grabado en la base en ese momento.

```yaml
archivos:
  - {nombre: SistemaDDLMini, etiqueta: DDL Mini, nombreArchivo: SistemaDDLMiniFileName, tipoArchivo: SistemaDDLMiniFileType}
```

| Opción | Qué hace |
|---|---|
| `nombre` | La columna (Blob) de la tabla de la cabecera. |
| `etiqueta` | Texto que ve el usuario. |
| `nombreArchivo` | (optativo) Campo de `campos` donde va el nombre del archivo, sin la extensión (el `AttFileNameAtt` de GeneXus). |
| `tipoArchivo` | (optativo) Campo de `campos` donde va la extensión (el `AttFileTypeAtt` de GeneXus). |

El contenido queda en la columna como lo deja GeneXus, así cualquier otro programa que lea la tabla lo sigue leyendo igual.
`gx2yaml.py` los arma solo desde la KB.

**Ocultar un campo: `oculto` vs. `visibleSi`**

| Caso | Cómo se hace |
|---|---|
| No se usa en ningún cliente | `oculto: true` en el campo. Puede quedar en la `pantalla` (su lugar queda vacío) o sacarse de ahí. |
| Se ve o no según el cliente | Se deja en la `pantalla` y se pone `visibleSi: Condicion`. Cada cliente lo ve o no según su parámetro. Hoy existe `ModuloMutual`; las demás condiciones llegan con los parámetros (`GetUsrProc`/`GetParamGlb`). |

Ejemplo:
```yaml
  - {nombre: ArticulosHH, dominio: Numeric(4), etiqueta: HH, nulo: true, oculto: true}
```

---

### 5.1 Fórmulas (V34)

Se escriben como en GeneXus, en una sola línea:

| Qué | Ejemplo |
|---|---|
| Operadores | `+ - * / ^`, paréntesis, `= <> < > <= >=`, `and`, `or`, `not`, `in ("A","B")`, `like "AB%"` |
| Totales de un detalle | `sum(PRSDetSubtotal)`, `sum(PRSDetImporteIva, PRSDetTasasIvaId=PRSTasaIvaId01, 0)` (condición y valor si no hay líneas); también `count`, `max`, `min`, `average` |
| Alternativas | `PRSValorTotal/PRSCostoTotal*100 IF PRSCostoTotal<>0; 0 OTHERWISE` |
| Otra fórmula | Se puede usar cualquier otro campo fórmula; el motor las calcula en el orden que haga falta. |
| Leer de la base | `find(ParamGlb.ParamGlbValorNumerico, ParamGlbId=16)` (tabla.columna, condición, valor por defecto opcional) |
| Procedimientos | `AplicarDescuentos.udp(PRSSubtotal, PRSPorBonPie)` o `CalcularIva(PRSId)`: llaman a `proc/<Nombre>.proc` |
| Funciones | `round(x,n)`, `abs`, `int`, `year`, `month`, `day`, `today`, `trim`, `len`, `iif`, `StrReplace`, `SubStr`, `Val`, `Str`, `PadL`… y métodos `.IsEmpty()`, `.ToString()`, `.Trim()` |
| Fechas | fecha + número = fecha + días; fecha − fecha = días |

- Los atributos que usa una fórmula tienen que estar en la TRN (aunque sea `oculto: true`): si vienen de otra tabla, como `inferido` con su referencia.
- Una fórmula que no se puede calcular (por ejemplo, porque falta el `.proc` que llama) queda **vacía** y el motivo se ve al pasar el mouse por el campo. Las demás se calculan igual.
- Las constantes de dominios enumerados van con su valor (`16`, no `Parametro.IvaTipo1`): el generador ya las reemplaza.
- Las filas vacías de un detalle no suman.

### 5.2 Clases de campo (V45)

Para no repetir el mismo `estilo:` campo por campo, se le pone nombre en `clases:`. En `dominios.yaml` valen para **todas** las TRN; en una TRN, solo para ella (si se llaman igual, manda la de la TRN):

```yaml
clases:
  Cabecera: {color: "30,100,180"}               # se aplica sola a todos los campos de la cabecera
  Detalle: {tamaño: "-2px"}                     # se aplica sola a todos los campos de los detalles
  Destacado: {negrita: true, tamaño: "+3px", color: "30,100,180"}
  Importe: {negrita: true, letra: Consolas}
```

- Propiedades: las mismas de `estilo:` (`negrita`, `italica`, `letra`, `tamaño`, `color`).
- `Cabecera` y `Detalle` son especiales: se aplican solas. Una TRN puede usar otra para su cabecera con `clase: OtraClase` (arriba, junto a `titulo:`) o ninguna con `clase: ninguna`; un detalle, lo mismo dentro de su bloque.
- Un campo usa una clase con `clase: Importe`. Se suman en este orden (lo último manda): clase de la cabecera o del detalle → clase del campo → `estilo:` del campo.
- Ej.: con `Cabecera: {color: "30,100,180"}` todos los campos de la cabecera salen en azul; uno que tenga `estilo: {color: "#b00020"}` sale en rojo.
- Una clase que no existe es un error al abrir la TRN (dice cuáles hay).
- Designer: en las propiedades del campo, "Clase" y "Letra"; el formulario muestra el resultado (clase del nivel + clase + estilo).
- (V48f) Ojo con `estilo: {negrita: false}` en un campo: le saca la negrita a cualquier clase que tenga. El Designer lo avisa ("Estilo propio del campo") y lo borra al cambiar de clase.
- (V48e) Cada objeto tiene sus propias clases: el campo en el formulario (`clase:`), la columna del WorkWith (`workwith.clases`, ver 9) y la variable de un web panel (`clase:`). La misma clase se puede usar en todos.
- (V48e) Designer: las clases se crean y se cambian con **🎨 Clases** (o "+ Nueva clase" en el Aspecto del campo), viendo en el momento cómo quedan. "Todas las TRN" graba en `dominios.yaml`; "Solo esta TRN", en el `clases:` de la TRN (con Guardar).

### 5.3 Tamaño de letra relativo al del usuario (V46)

Cada usuario elige su tamaño de letra en Apariencia (15px de entrada). Un campo importante tiene que seguir **más grande que el resto** aunque el usuario haya agrandado su letra; por eso el `tamaño:` se escribe respecto de la letra del usuario:

| `tamaño:` | Resultado | Con letra 15px | Con letra 30px |
|---|---|---|---|
| `"+4px"` | la letra del usuario más 4px | 19px | 34px |
| `"-2px"` | la letra del usuario menos 2px | 13px | 28px |
| `"125%"` | proporcional a la letra del usuario | 18,75px | 37,5px |
| `"20px"` | **fijo**, no cambia con la letra del usuario | 20px | 20px |

- Con `+`/`-` el campo se agranda siempre lo mismo; con `%` destaca en la misma proporción con cualquier tamaño (con letra grande, `+4px` se nota menos que `125%`).
- **Entre comillas**: YAML lee `+4` sin comillas como el número 4. Un número suelto del 0 al 8 da error y avisa cómo escribirlo; de 9 en adelante (`tamaño: 14`) es fijo en px, como antes de V46.
- Rangos: de `"-30px"` a `"+30px"` y de `"50%"` a `"300%"`. También se aceptan `em`/`rem` (fijos respecto de la pantalla).
- Vale igual en `estilo:` y en `clases:` (5.2), en la cabecera y en las líneas de los detalles.
- Designer: "Tamaño de letra" ofrece `+1px`…`+10px`, `-1px`…`-3px` y `110%`…`200%`; por voz, "letra +4", "letra 125%", "más grande" (+2px), "más chica" (-2px). "letra 20" la deja fija.

---

## 6. Referencias (claves foráneas y descripciones)

Una referencia dice: "este campo apunta a otra tabla; traé estos datos de ahí".

```yaml
campos:
  - {nombre: TasasIvaId,      dominio: N04,     etiqueta: Tasa, obligatorio: true}
  - {nombre: TasasIvaDescrip, dominio: Descrip, etiqueta: Descripción, inferido: true}
  - {nombre: TasasIvaTasa,    dominio: Porcentaje, etiqueta: Porcentaje, inferido: true}
referencias:
  - tabla: TasasIva
    campos: {TasasIvaId: TasasIvaId}                 # campo local: columna en la otra tabla
    trae: {TasasIvaDescrip: TasasIvaDescrip, TasasIvaTasa: TasasIvaTasa}
    descripcion: TasasIvaDescrip                     # columna que muestra el buscador (opcional)
```

Qué hace el motor:
- Al abrir el registro trae las descripciones.
- Cuando el usuario cambia el código, las actualiza en el momento.
- Al grabar valida que el código exista ("no existe Tasa 99").
- Agrega una **lupa** para buscar en el último campo de la clave foránea. En la lista, **un toque en el tilde ✓** de la izquierda elige y cierra (como el prompt de GeneXus); doble clic en la fila también.
- **Claves compuestas (segundo nivel)**: en Tipo + Subtipo o Marca + Modelo, la lupa va en Subtipo / Modelo y muestra **solo los del padre ya elegido** (los subtipos de ese tipo). Si el padre está vacío, avisa "Elegí primero Tipo Artículo". No hay que configurar nada: sale de `campos:` de la referencia, en el orden en que están escritos (el padre primero).
```yaml
  - tabla: TipoArtsSubTipos
    campos: {TipoArtsId: TipoArtsId, TipoArtsSubTiposId: TipoArtsSubTiposId}   # padre, hijo
    trae: {TipoArtsSubTiposDescrip: TipoArtsSubTiposDescrip}
```
- Sirve para subtipos (`campos: {UnidadCompra: UnidadesId}`), claves compuestas y cadenas (una descripción que depende de otra referencia).

### 6.1 Referencias con condición fija (`donde`)

Para "este código, pero solo de tal tipo". Ejemplo: el Proveedor Habitual es un `EntidadesId`, pero solo de entidades **proveedor**:

```yaml
campos:
  - {nombre: ArticulosProveedorHabitual, dominio: Numeric(8), etiqueta: Proveedor Habitual}
  - {nombre: ProvHabitualTipoEntidad,  dominio: TipoEntidad, etiqueta: Tipo Entidad, inferido: true}
  - {nombre: ProvHabitualRazonSocial,  dominio: Nombre,      etiqueta: Razón Social, inferido: true}
referencias:
  - tabla: Entidades
    campos: {ArticulosProveedorHabitual: EntidadesId}
    donde: {EntidadesTipoEntidad: 2}            # solo proveedores (constante)
    trae: {ProvHabitualTipoEntidad: EntidadesTipoEntidad, ProvHabitualRazonSocial: EntidadesRazonSocial}
    descripcion: EntidadesRazonSocial           # lo que muestra la lupa al lado del código
```
- `donde` son condiciones fijas sobre la tabla de la referencia: `{Columna: valor, ...}`. Valores: número, texto o `true`/`false`.
- Se aplica al traer las descripciones, en el WorkWith, en la lupa (solo lista proveedores) y **al grabar valida** ("no existe Proveedor Habitual 123").
- (V65) **Al salir del campo** se controla que lo cargado exista: si no, el campo de la lupa queda en rojo ("No existe ..."). No hay que configurar nada. Lo mismo en las lupas de los web panels.
- (V65) `dondeLupa: {ArticulosMostrarEnVentas: true}`: condiciones que filtran **solo la lupa** (escrito a mano se acepta). `mensaje: no está activo`: lo que se avisa (al salir del campo y al grabar) cuando el registro existe pero no cumple el `donde`; sin `mensaje`, "no está habilitado". Ej. el artículo en los procesos de ventas:
  ```yaml
      - tabla: Articulos
        campos: {ArticulosPK: ArticulosPK}
        donde: {ArticulosEstado: 1}                     # activo: lupa, escrito a mano y al grabar
        dondeLupa: {ArticulosMostrarEnVentas: true}     # solo en la lupa
        mensaje: no está activo
  ```
  Al grabar, el `donde` se controla en lo nuevo y en lo que cambió esa referencia: lo ya grabado que no se tocó no se vuelve a controlar.
- (V65) `opcional: true` en la referencia: con 0 o vacío **no se valida** al grabar. Para los atributos que en GeneXus tenían `Prompt` pero no eran clave foránea (la columna no admite nulo y sin valor queda en 0). Ej. la forma de pago de CondPago. Si la columna admite nulo, alcanza con `nulo: true` en el campo.
- Los campos inferidos pueden tener cualquier nombre (no existen en la tabla): son los que en GeneXus eran variables cargadas por un procedimiento.
- Si el campo inferido usa un dominio con valores (como `TipoEntidad`), se muestra el texto ("Proveedor").
- El generador todavía no detecta este caso (en GeneXus son variables + `Prompt` + procedimiento): se agrega a mano.
- (V47) **Condición que depende de un campo de la TRN**: en vez de una constante, `{campo: CampoDeLaTrn, valor: resultado, ...}`. En PRS, PED, CPBC y CPBP el cliente o proveedor sale del CPI del comprobante:
```yaml
  - tabla: Entidades
    campos: {PRSEntidadesPK: EntidadesPK}
    donde: {EntidadesTipoEntidad: {campo: PRSCPI, C: 1, P: 2}}   # CPI "C" -> clientes (1); "P" -> proveedores (2)
```
  Si el campo está vacío o tiene un valor que no figura (CPI "I", Interno), esa condición no se aplica. Vale para la lupa, para escribir el código y al grabar. Con `{campo: X}` sin valores se compara con el valor del campo tal cual. El generador no lo pone: hay que volver a agregarlo si se regenera la TRN.

- (V49u) **Condición con el contexto del usuario**: `donde: {ListasPreciosNrosEmp: "&EmpLst", ListasPreciosNrosDiv: "&DivLst"}` (las variables de 9.1, entre comillas). Sin valor en el contexto, esa condición no se aplica.

### 6.1.1 Lupa armada por un procedimiento (`lupa:`, V49u)

Para los prompts de GeneXus que no son una tabla sino una grilla calculada (ej. *PromptArticulosListasPreciosModificado*: el precio del artículo en cada lista). En el campo del detalle (o de la cabecera):
```yaml
      - nombre: PRSListasPreciosId
        dominio: N06
        etiqueta: LP
        lupa:
          proc: PromptArticulosListasPreciosModificado   # proc/<nombre>.proc; su último parámetro out es una colección
          parametros: [PRSCatIvaDesglosa, ArticulosPK]  # campos de la línea o de la cabecera, o &variables de las reglas
          titulo: Precios del artículo por lista
          columnas: {ListasPreciosId: Lista, ListasPreciosDescrip: Descripción, Precio: Precio de Lista}
          devuelve: {PRSListasPreciosId: ListasPreciosId, PRSDetPrecio: Precio}   # campo de la TRN: campo de la fila elegida
```
- El procedimiento arma las filas con `&Fila = New()`, `&Fila.Campo = ...`, `&Filas.Add(&Fila)`.
- Al elegir, cada campo de `devuelve` se copia en orden y corren sus reglas (como el Prompt de GeneXus): el último queda como se eligió.
- En los detalles y, desde la V65, también en la cabecera.
- Si un parámetro que es un campo está vacío, no abre: avisa "Elegí primero <campo>" (ej. el almacén sin el artículo).
- **Almacén de un renglón con artículo** (V65): siempre esta lupa, con los almacenes del artículo (`ArticulosAlmacenes`), no la de toda la tabla Almacenes. La referencia a `Almacenes` se deja (trae la descripción y valida):
  `lupa: {proc: PromptArticulosAlmacenes, parametros: [ArticulosPK], titulo: Almacenes del artículo, columnas: {AlmacenesId: Almacén, AlmacenesDescrip: Descripción, BultosStock: Bultos en stock, CantidadStock: Cantidad en stock}, devuelve: {STAlmacenesId: AlmacenesId}, procesos: {BultosStock: 156, CantidadStock: 157}}`
- `procesos:` (V55, opcional): columnas de la lupa que se ven solo si el usuario tiene habilitado ese proceso (GetUsrProc). Ej. en la lupa del almacén del PED: `procesos: {BultosStock: 156, CantidadStock: 157}`. Sin el proceso, la columna no está.
- **Padre de solo lectura (V80)**: en una referencia compuesta, si un campo anterior al de la lupa está vacío y es de solo lectura (no se puede elegir antes, ej. la Clase en Modelos de Comprobantes), la lupa no pide "Elegí primero…": busca sin ese filtro, lo muestra como primera columna (con su filtro y orden) y al elegir lo completa con el de la fila elegida, como el Prompt de GeneXus. Los padres que se completan solos (valor por defecto o regla `Hijo = Padre;`) siguen filtrando.
- La lupa de un campo va en el **último** campo de `campos:` de la referencia. Con clave compuesta (Contacto = Entidad + Contacto) el campo propio va último: `campos: {PRSEntidadesPK: EntidadesPK, PRSContactosId: ContactosId}`.

### 6.1.2 Combo con los registros de una tabla (`combo:`, V49u)

El *Dynamic Combo Box* de GeneXus: `combo: {tabla: MediosDeContacto, valor: MediosDeContactoId, texto: MediosDeContactoDescrip}` en el campo. Se guarda `valor` y se ve `texto`, ordenado por `texto`, con la cruz para dejarlo vacío (`vacio: false` la saca). Los registros se leen una vez al abrir la pantalla. (V66t) `donde: "Columna = 'C'"`: solo los registros que cumplen esa condición SQL fija; `vacioTexto: (Ninguno)`: lo que se ve sin elegir. También en los web panels (sección 19).

### 6.1.3 Campos que solo se muestran (`pantalla: true`, V50b)

Para mostrar variables que calcula el inicio (en GeneXus, variables en el web form): `- {nombre: TRPRSCostoTotal, dominio: Total, etiqueta: Costo Total, pantalla: true}` y en las reglas `TRPRSCostoTotal = &TRPRSCostoTotal;`. No se graba ni necesita referencia; la regla corre al abrir el registro. Con `TRPRSCostoTotal.Visible = &Variable;` se muestra u oculta.

### 6.1.4 Vínculo a otra TRN (`enlace:`, V50d)

`enlace: PED` (el valor es la clave de PED) o `enlace: {trn: PED, clave: PRSPEDId}` (se muestra este campo y se abre por la clave de otro): `- {nombre: PRSPEDNumero, dominio: N09, etiqueta: Pedido, soloLectura: true, enlace: {trn: PED, clave: PRSPEDId}}`. Se ve como vínculo en el formulario y en la columna del WorkWith, y abre esa TRN en Visualizar (V50e). Para mostrarlo solo cuando hay pedido: `PRSPEDNumero.Visible = not PRSPEDId.IsEmpty();`.

### 6.1.5 Lupas que abren una consulta (`ver:`, V66k)

Como las lupas de GeneXus que abren otra lista con datos del registro (ej. en Asientos, `&VerDetalleAsiento` arriba del asiento y `&VerDetalle` en cada renglón, que abrían el Detalle de Imputaciones). En la TRN (para la cabecera) y/o en un detalle:

```yaml
ver:                     # arriba del formulario (no en el alta)
  - {titulo: Ver imputaciones del asiento, cns: ExtraWWDetAsientosDetalle, parametros: [AsientosId]}
detalles:
  - nombre: Det
    ver:                 # una lupa en cada renglón ya grabado
      - {titulo: Ver imputaciones del renglón, cns: ExtraWWDetAsientosDetalle, parametros: [AsientosId, AsientosDetId], si: "AsientosNumeroCbte = 0"}
```

- `cns`: la consulta (`consultas/`, sección 21). `parametros`: los campos que recibe, en el orden de su `recibe:` (en un renglón, de la cabecera o del renglón). `si`: condición opcional.
- Abre la consulta en la pantalla; su botón **Volver** (o el Atrás del navegador) vuelve al formulario. En Modificar pregunta antes, porque lo no guardado se pierde. **Necesita el motor V66k.**

### 6.2 Código en lugar de la clave interna (`codigos`, V47)

Como el *Input Type = Descriptions* de GeneXus: en la base se guarda la clave interna (`ArticulosPK` 75, `EntidadesPK` 3603), pero el usuario ve y escribe el **código** (`ArticulosId` "TTESP0500002", `EntidadesId` 2222). Se define **una vez por tabla** en `dominios.yaml` y vale para todas las TRN:

```yaml
codigos:
  Articulos: {codigo: ArticulosId}
  Entidades: {codigo: EntidadesId, segun: EntidadesTipoEntidad}
```

- Todo campo que es la clave de una referencia a esa tabla (en la cabecera o en las líneas, se llame `ArticulosPK`, `PartidasArticulosPK` o como sea) muestra el código.
- **Escribir el código**: al salir del campo se busca la clave interna y se traen la descripción y lo demás, como siempre. Respeta el `donde` de la referencia (en PRS, solo clientes si el CPI es "C"). Si no existe, el campo queda en rojo ("No existe TTESP05 en Articulos") y la descripción se borra.
- **Mismo código en dos registros**: `segun` es la columna que los distingue (un cliente y un proveedor pueden tener el mismo `EntidadesId`). Si la TRN no dice cuál (no tiene `donde` por tipo), aparece una ventana con los dos (código, razón social, Cliente / Proveedor) para elegir.
- **La lupa** busca por código o descripción y muestra el código.
- **WorkWith y Excel**: la columna de la clave interna muestra el código.
- Reglas, fórmulas, procedimientos y la grabación siguen trabajando con la clave interna: no cambia nada de eso.
- Una referencia puede usar otra columna con `codigo: OtraColumna` o mostrar la clave interna con `codigo: ninguno`. Las claves compuestas (dos o más campos) siguen como antes.

---

### 6.3 Columnas de la lupa (`workwith.prompt`, V48)

Cuando otra TRN busca en esta con la lupa (por ejemplo, el artículo de un renglón de PRS), por defecto se ve código y
descripción con un solo filtro. Con `prompt` dentro de `workwith:` la lupa muestra esas columnas, cada una con su filtro
("contiene"; los filtros se combinan):

```yaml
workwith:
  columnas: [...]
  prompt: [ArticulosId, ArticulosDescrip, TipoArtsId, TipoArtsDescrip, MarcasDescrip, MarcasModeloDescrip]
```

Van campos de la cabecera, propios o inferidos (los trae una referencia). Se respetan el `donde` de la referencia que
busca y los campos padre (en una referencia compuesta).

**V50k: todas las lupas son iguales** (las de las TRN y las de los web panels):
- cada columna con su filtro **como en el WorkWith**: texto (contiene), número (`=`, `!=`, `<`, `<=`, `>`, `>=`, `entre`),
  fecha (desde - hasta) y combo (por el texto que se ve, ej. "CUIT");
- **carga por páginas**: muestra las primeras filas y, al bajar, va trayendo las siguientes (antes algunas se quedaban en 300);
- la ventana toma el ancho de sus columnas; tilde ✓ o doble clic eligen y cierran.

La lupa de un **web panel** (`prompts:` del panel, sección 19) usa las columnas de `workwith.prompt` de la TRN de esa
tabla, si las tiene; si no, código y descripción, cada una con su filtro. Ejemplo, la de clientes y proveedores (en
`Entidades.yaml`):

```yaml
workwith:
  prompt: [EntidadesRazonSocial, "EntidadesId: Código", EntidadesTipoDocumento, "EntidadesCUIT: CUIT", "EntidadesNroDocumento: Nro. Documento"]
```

`"Campo: Título"` cambia el título de la columna en la lupa. Que sean clientes o proveedores lo decide el `donde` de quien busca
(la referencia de la TRN o el `donde` del prompt del panel).

### 6.4 Ayuda de la pantalla (V48)

Si existe `src/main/resources/ayuda/<TRN>-lista.md`, la lista (Trabajar con) muestra un botón **?** que la abre; con
`ayuda/<TRN>.md`, lo muestra el formulario. Se escriben en Markdown (títulos `#`, listas, tablas `| a | b |`, `**negrita**`,
`` `código` ``, citas `>`). No hace falta tocar el YAML de la TRN. Ver `manual/README.md`.

## 7. Detalles (segundo nivel)

```yaml
detalles:
  - nombre: Embalajes                  # nombre del detalle (se usa en la pantalla)
    titulo: Embalajes
    tabla: ArticulosEmbalajes
    numerador: ArticulosLastEmbalaje   # opcional: columna de la cabecera que numera las líneas nuevas
    orden: [ArticulosEmbalajesId]
    campos:
      - {nombre: ArticulosEmbalajesId,       dominio: Numeric(4), clave: true}
      - {nombre: ArticulosEmbalajesCantidad, dominio: Cantidad,   etiqueta: Cantidad x Embalaje, obligatorio: true}
    referencias: [...]                 # igual que en la cabecera
```
- La clave de la tabla del detalle es **la clave de la cabecera + las claves del detalle** (la de la cabecera no se declara).
- Con `numerador`, la clave de las líneas nuevas se asigna al grabar (el `Serial` de GeneXus); no se muestra.
- (V65) Sin columna para el `numerador`: `siguiente: true` en la clave del detalle (se ve y se puede cambiar; ver 5).
- Dos detalles pueden compartir el mismo `numerador` (V30): usan un único contador, como dos `Serial` sobre la misma columna en GeneXus.
- Una clave con `oculto: true` y `default` se completa sola (claves que en GeneXus no se ven en la grilla).
- Se pueden declarar varios detalles. El formato viejo `detalle:` (uno solo) sigue funcionando.
- Reglas: la clave de una línea existente no se edita; se graba todo en una sola transacción.
- Filas nuevas: si el usuario no tocó nada (todo vacío o con su valor por defecto), la fila se ignora.
- `oculto: true` en el detalle (a la altura de `titulo:`): el detalle no se muestra, no se lee ni se graba. **Sus filas en la base quedan como están** (no se borran al grabar). Al borrar el registro completo, sí se borran. Para volver a mostrarlo, se saca el `oculto`.

---

## 8. Pantalla

El diseño del formulario va en un bloque de texto. **Cada línea es un renglón; los campos se separan con espacios.** Conviene alinearlos para que se lea como la pantalla.

```yaml
pantalla: |
  <solapa Datos Generales>
    <tabla anchos=260px,1fr,1fr>
      ArticulosPK      ArticulosDescrip:2
      ArticulosId      ArticulosDescort:2
      -                ArticulosEstado
      ArticulosHH      ArticulosMM          ArticulosSS
    <endtable>
  <endsolapa>

  <solapa Listas de Precios>
    <tabla>
      ArticulosImprimeLista    ArticulosOrdenListasPrecios
    <endtable>
    <detalle ListasPrecios>
  <endsolapa>
```

| Marca | Qué hace |
|---|---|
| `<solapa Título>` … `<endsolapa>` | Una solapa. |
| `<tabla>` … `<endtable>` | Una tabla estilo GeneXus: columnas alineadas de arriba a abajo. |
| `<detalle Nombre>` | Dónde va un detalle (su grilla con "Agregar Filas"). |
| `<detalle Nombre completo>` | (V39) La grilla ocupa todo el ancho: las columnas se estiran en proporción a su ancho. |
| `<detalle Nombre alinear=derecha>` | (V39) El detalle toma el ancho de su grilla y va a la `izquierda`, al `centro` o a la `derecha`. |
| `<detalle Nombre sinTitulo>` | (V65f) No muestra el título arriba de la grilla (cuando el panel ya dice qué es, ej. "Detalle de Artículos"). El `titulo:` del detalle se sigue usando en los mensajes ("Artículos, renglón 3: …"). Se combina con las demás: `<detalle Det completo sinTitulo>`. |
| `<panel Título>` … `<endpanel>` | (V33) Un panel como el de GeneXus: recuadro con título que se abre y se cierra con su ícono. Adentro, tablas, detalles, otros paneles o renglones. Opciones: `cerrado` (arranca cerrado; por defecto abierto), `ancho=40%`, (V39) `alinear=derecha` (toma el ancho de su contenido y va a la izquierda, al centro o a la derecha) y (V49t) `ocultarVacio` (el panel no se ve mientras sus detalles no tengan renglones; ej. el Detalle de Impuestos de un cliente sin percepciones). En una `<tabla alinear=derecha>` (los totales) los campos numéricos van contra el borde derecho. |
| `<renglon>` … `<endrenglon>` | (V33) Lo de adentro va **uno al lado del otro** (ej. dos paneles en el mismo renglón). En el celular bajan uno debajo del otro. |
| `Campo:2` | El campo ocupa 2 columnas. |
| `-` | Celda vacía. |
| `# texto` | Comentario. |

Opciones de `<tabla ...>` (todas optativas, en cualquier orden):

| Opción | Ejemplo | Qué hace |
|---|---|---|
| título | `<tabla Identificación>` | Título arriba de la tabla. |
| `anchos=` | `anchos=260px,1fr,1fr` | Ancho de cada columna de campos: `px`, `%`, `fr` (reparte lo que sobra), `ch`, `auto`. Sin anchos: columnas iguales. |
| `etiquetas=` | `etiquetas=arriba` | `izquierda` (por defecto, como GeneXus) o `arriba`. |
| `alinear=` | `alinear=centro` | (V33) La tabla toma el ancho de su contenido y va a la `izquierda`, al `centro` o a la `derecha`. Sin esto, ocupa todo el ancho. |
| `anchoEtiquetas=` | `anchoEtiquetas=180px` | Ancho de la columna de etiquetas. Por defecto se ajusta al texto más largo de esa columna, hasta 150px. |

Paneles (V33), ejemplo con dos paneles en el mismo renglón y uno adentro de otro:
```yaml
pantalla: |
  <solapa Datos Principales>
    <renglon>
      <panel Cliente ancho=55%>
        <tabla>
          PRSEntidadesPK      PRSEntidadesRazonSocial
        <endtable>
      <endpanel>
      <panel Condiciones cerrado>
        <tabla>
          PRSFecha            PRSFechaVto
        <endtable>
        <panel Observaciones>
          PRSDescrip
        <endpanel>
      <endpanel>
    <endrenglon>
    <detalle Det>
  <endsolapa>
```
Totales alineados a la derecha, con el detalle a todo el ancho (V39, así queda PRS):
```yaml
    <panel Detalle de Artículos>
      <detalle Det completo>
    <endpanel>
    <panel Detalle de Impuestos alinear=derecha>
      <detalle Impuestos>
    <endpanel>
    <tabla alinear=derecha>
      PRSSubtotal
      ...
    <endtable>
```
En el Designer: elegí el detalle y tildá "Ocupa todo el ancho"; en el detalle, el panel o la tabla, "Alinear". (V65f) En el detalle también: "Título" (el de los mensajes) y "Mostrar el título arriba de la grilla".

Los cierres también se pueden escribir `<fin panel>`, `<fin renglon>`, `<fin tabla>`, `<fin solapa>`.

Reglas:
- Una solapa puede tener varias tablas, detalles, paneles y renglones, y se muestran **en el orden en que se escriben**.
- Una `<solapa>` no puede ir dentro de un panel.
- Renglones fuera de una `<tabla>` forman una tabla sola.
- Sin ninguna `<solapa>`, el formulario no tiene solapas.
- Un campo `oculto` o no visible deja su celda vacía: **los demás campos no se corren de columna** (la tabla no se desarma).
- Un renglón en el que todos sus campos están ocultos no ocupa lugar.
- Los campos nunca se aplastan: cada columna tiene un ancho mínimo según sus campos. Si la tabla no entra en la pantalla, aparece la barra horizontal.
- Un campo multirenglón lleva la etiqueta arriba, alineada con el primer renglón.
- Un campo visible que no figure en la pantalla va al final (o a una solapa "Otros datos").
- **Usar espacios, no tabs**: YAML no acepta tabs al comienzo de una línea.
- **En el celular** (pantallas de menos de 700 px) cada `<tabla>` se muestra en una sola columna: etiqueta arriba y campo abajo, en el orden de los renglones. No hay que configurar nada; en la PC se ve como está escrito.
- Los errores indican la línea: campo inexistente o repetido, falta `<endtable>`/`<endsolapa>`, detalle inexistente.

---

## 9. WorkWith (la grilla de búsqueda)

```yaml
workwith:
  columnas: [ArticulosId, ArticulosDescrip, TipoArtsDescrip, MarcasDescrip, ArticulosEstado]
```
- Admite campos propios **y campos inferidos** (descripciones): el motor los trae con un `LEFT JOIN` en la misma consulta.
- **Título propio de una columna** (solo en el WorkWith y el Excel; el formulario sigue usando la `etiqueta` del campo):
  ```yaml
  workwith:
    columnas: [ArticulosId, ArticulosDescrip, "MarcasDescrip: Marca", "TipoArtsDescrip: Tipo"]
  ```
  Una columna no se puede repetir.
- **Orden**: `orden: [Campo]` (o `[Campo DESC]`, o varios) es el orden inicial. Al tocar el encabezado de una columna se ordenan **todas** las filas filtradas (no solo la página); el tercer toque vuelve al orden del YAML. El orden elegido queda guardado por usuario. (V41) El orden del YAML puede incluir campos inferidos (descripciones de otras tablas): `orden: [DtoArtTipoEntidadesRazonSocial]`. (V66) En el OPTIMIST Designer, debajo del WorkWith: "Orden inicial de la lista" (campo, ascendente / descendente, ↑ ↓, "+ campo" y ✕) y, con el botón derecho en una columna, "Ordenar por esta columna"; graba solo el renglón `orden:`.
- **Qué trae la consulta** (V41): solo las claves y los campos de `columnas` (más `ocultas`), con sus descripciones. Los demás campos de la tabla no se leen, aunque sean 80: el formulario, al abrir un registro, lee ese registro solo.
- **Filtro fijo** (`workwith.filtro`): condiciones que siempre se aplican. Un valor o una variable del usuario (`"&Empresa"`) es igualdad; (V43) también comparaciones: `">0"`, `"<>0"`, `">=5"`, `"<X"`, entre comillas.
  ```yaml
  workwith:
    filtro: {PRSEmpresa: "&Empresa", PRSDivision: "&Division", PRSNumero: ">0"}
  ```
  En el Designer, debajo del WorkWith: campo, operador y valor, "+ condición" y ✕. El generador toma de GeneXus las condiciones fijas del WW (`PRSNumero > 0`).
- **Columnas ocultas** (V41): campos que el WorkWith trae pero no muestra, para las acciones del renglón o para llamar a un procedimiento. El usuario tampoco puede mostrarlos desde ⚙.
  ```yaml
  workwith:
    columnas: [PEDId, PEDNumero, PEDFecha, PEDStatus]
    ocultas: [PEDFacturado, PEDEntidadesPK]
  ```
  Un campo va en `columnas` o en `ocultas`, no en las dos. En el Designer: botón derecho sobre una columna → "Pasar a ocultas", o "agregar oculta".
- **Clases del WorkWith** (V48e): el WorkWith tiene **sus propias clases**, aparte de las del formulario (la clase de un campo en el formulario no pasa a su columna). Son las mismas clases de `dominios.yaml` o de `clases:` de la TRN: una clase se diseña una vez y se usa en el formulario, en el WorkWith o en un web panel.
  ```yaml
  workwith:
    columnas: [PRSNumero, PRSFecha, PRSRazonSocial, PRSTotal]
    clase: Grilla                                  # para todas las celdas (opcional)
    clases: {PRSTotal: Importe, PRSNumero: [Destacado, Rojo]}   # por columna; manda sobre la de toda la grilla
  ```
  Sin `clase:` se aplica sola la clase `WorkWith` si existe (como `Cabecera` y `Detalle` en el formulario); `clase: ninguna` la apaga. Se pintan las celdas; el título de la columna queda igual. Una columna de `clases:` que no está en `columnas` es un error al abrir la TRN. En el Designer: vista WorkWith, elegí la columna → "Aspecto en el WorkWith" (clase de la columna y de toda la grilla, "+ Nueva clase"); botón derecho sobre la columna → "Nueva clase para esta columna…". Los renglones de muestra de cada columna se ven con su clase.
- **Columnas por usuario**: el botón ⚙ (al lado de PDF) muestra la lista de columnas para destildar las que no se quieren ver y ordenarlas arrastrando; también se pueden arrastrar los encabezados. Queda guardado por usuario y por TRN; "Restablecer" vuelve a lo del YAML. Si se agrega una columna al YAML, al usuario le aparece al lado de la que la precede en el YAML (V43; antes, al final).
- **Ancho de columna por usuario** (V66s): en la ventana ⚙, cada columna muestra su ancho y un ícono ↔ que abre una ventanita para escribirlo en px (40 a 1500); su **Restablecer** vuelve solo esa columna al ancho original. También se puede arrastrar el borde del título en la grilla, como en Excel. Queda guardado por usuario y por pantalla (WorkWith y consultas). El `ancho:` del YAML es el ancho original: si después se cambia en el YAML, lo ven los usuarios que no eligieron uno propio. El ancho elegido es el mínimo: si la pantalla es más ancha, lo que sobra se sigue repartiendo entre todas las columnas. El Restablecer de la ventana ⚙ vuelve todo (columnas, orden y anchos).
- **Ancho de las columnas** (V32): cada columna toma el ancho de su dato (fechas, números y códigos angostos; los textos largos se reparten el espacio que sobra). El texto que no entra sigue en otro renglón. Los números van a la derecha. Para forzar un ancho:
  ```yaml
  workwith:
    columnas: [PRSId, PRSFecha, PRSEntidadesRazonSocial]
    anchos: {PRSEntidadesRazonSocial: 100px}     # un número solo = píxeles; también em, rem, ch, %
  ```
- **Renglones** (V32): los pares un poco más oscuros; el que se toca, más oscuro todavía y en negrita. Al volver de Modificar o Visualizar sigue marcado (y si por el orden pasó a otra página, la grilla va a esa página). La grilla muestra todos los renglones de la página elegida (10, 20, 50 o 100).
- Cada columna tiene su filtro y se puede ordenar. Los combos muestran el texto.
- Las filas por página las elige cada usuario (Apariencia).
- Navegación: Primera, Anterior, Siguiente, Última.
- **Excel**: el botón exporta a `.xlsx` **todas las filas que pasan los filtros** (no solo la página visible), con las columnas que el usuario tiene visibles, en su orden, y con el orden de filas elegido. Números como números (se pueden sumar), fechas como fechas, combos con su texto, encabezado en negrita, primera fila congelada y autofiltro. Archivo: `<Trn>_<fecha>_<hora>.xlsx`. No hay que configurar nada.
- **PDF** (V66k): lo mismo que el Excel (todas las filas que pasan los filtros, las columnas visibles en su orden), en una hoja A4 apaisada con título, empresa, fecha y "Página N de M"; números 1.234,56 a la derecha. Se abre en la misma ventana, como las impresiones. Vale para todos los WorkWith y las consultas; no hay que configurar nada.

**Filtros (V50)**: cada columna tiene su filtro según el dominio del campo, sin configurar nada: números con operador (`=`, `!=`, `<`, `<=`, `>`, `>=`, `entre`), fechas con desde/hasta y el resto "contiene". Las fechas se escriben como en GeneXus (`0410` = 04/10 del año actual).

**Filtro de selección múltiple (V60)**: las columnas con dominio enumerado (un combo: ClaseCbte, CPI, Status, Tipo Documento AFIP...) tienen como filtro un **combo de selección múltiple** con los valores del dominio: se tildan uno o varios (se puede escribir para buscar en la lista) y la lista trae los registros que tienen **cualquiera** de ellos (`IN (...)` en la consulta a la base). Sin ninguno tildado, no filtra. Vale para las TRN, las vistas y las consultas, sin configurar nada. (En GeneXus el combo del filtro dejaba elegir uno solo.) **Necesita el motor V60.**

**Columnas fórmula en la lista (V82p, `workwith.columnasSql`)**: una fórmula que no se graba (ej. el saldo del comprobante) puede ser columna del WorkWith si se da su expresión SQL (`t0` es la tabla de la TRN): `columnasSql: {CPBCSaldo: "IFNULL((SELECT m.MovCtaCteSaldo FROM MovCtaCte m WHERE m.MovCtaCteCBTEId = t0.CPBCId ...), 0)"}`. La lista la trae, la filtra, la ordena y la suma en la base; en el formulario se calcula con su `formula:`. Ejemplo completo en `trn/CPBC.yaml`.

**Columnas según una condición (V84, `workwith.columnasSi`)**: una columna va en la lista solo si su condición da verdadero; la condición usa las `&variables` del inicio de las acciones (`workwith.acciones.inicio`). Si no va, tampoco sale en el Excel, el PDF ni la ventana ⚙. Ej. en `trn/CPBC.yaml`, el Total, el Saldo y la Moneda según VERCampos (como el WWCPBC de GeneXus):
```yaml
workwith:
  acciones:
    inicio: |
      ...
      &VerTotales = true
      GetParamGlb.Call(97, &ParamGlbValorAlfa, &ParamGlbValorNumerico, &ParamGlbBoolean)
      if &ParamGlbBoolean
          VERCampo.Call("CPBC", 214, &VerTotales, &VERCamposTRNDetAncho)
      endif
  columnasSi: {CPBCTotal: "&VerTotales", CPBCSaldo: "&VerTotales", CPBCMonedasId: "&VerTotales"}
```
**Necesita el motor V84.**

**Paginado en la base (V50o; V82o: por defecto en todas las TRN y vistas).** Con `paginarEnServidor: false` dentro de `workwith:` una TRN vuelve a traer todo y trabajar en memoria (solo si hiciera falta). La lista le pide a la base solo la página que muestra (`LIMIT`), con los filtros de las columnas en el `WHERE` (texto: contiene; combos y Sí/No por el texto que se ve; números con operadores; fechas desde-hasta; el código en lugar de la clave interna) y el orden en el `ORDER BY`; el total sale de un `COUNT(*)`. En memoria (`false`) la lista trae todos los registros (lento con decenas de miles: PED de Baires4SRL, 219.000, tardaba 13 s; Comprobantes de InPack, 6 s cada vez que se entraba). Al volver de un registro se queda en la misma página. La página trae los renglones que el usuario eligió por página (10, 20, 50…); al pasar de página con los mismos filtros, la cantidad y los totales no se vuelven a pedir. El Excel trae todos los que pasan los filtros.

### 9.0 Totales al pie del WorkWith (V75)

**V84i — quién los ve y cuándo se calculan**: los totales de TODAS las listas (WorkWith, consultas y listas de resultados de los web panels) están apagados por defecto: ni se muestran ni se calculan. Solo los usuarios autorizados en el `optimist.cfg` del cliente ven el botón **Σ** (entre Apariencia y PostIt; en los web panels, a la izquierda del PostIt) y con él los prenden; con **Σ tachada** los apagan. Queda recordado por usuario y por lista.

```
# optimist.cfg del cliente: los usuarios (SecUserName) que pueden ver los totales; * = todos. Sin la línea, nadie.
# Se lee al día: un cambio vale sin reiniciar, desde la próxima vez que el usuario entra a una lista.
totales.usuarios=diego,mariana
```

Lo de abajo (qué columnas se suman) sigue igual.

Todas las listas (los WorkWith de las TRN y las consultas) llevan una **fila de TOTALES al pie** con la suma de las columnas de importes. La suma es de **todo lo que pasa los filtros**, no solo de la página que se ve; se recalcula al cambiar un filtro y no al pasar de página.

- **Qué columnas se suman solas**: las de los dominios de `totalizar:` en `dominios.yaml` (hoy `Total, Total14, TotalConSigno, Total2DecZ, Importe2Dec, Importe2DecConMiles, Importe2DecSignedMiles`). Para que todo el sistema sume otro dominio, se agrega ahí. Quedan afuera a propósito `Importe4Dec` (cotizaciones, costos unitarios), `Precio`, `Porcentaje` y las cantidades.
- **Por lista**, dentro de `workwith:` (TRN) o en la raíz de la consulta:
  ```yaml
  totales: [Debe, Haber]                 # se suman además (columnas de la TRN o columnasSql de la consulta)
  sinTotales: [EntidadesLimiteCredito]   # no se suman aunque su dominio sea de importe
  ```
- Las `columnasSql` de una consulta se suman **solo** si figuran en `totales:` (el motor no sabe si una es un saldo acumulado).
- La suma se calcula en la base, en la misma consulta del recuento (`SELECT COUNT(*), SUM(...)`): no agrega lecturas. Una fórmula que se calcula en Java (sin columna en la base) no se puede sumar así y queda sin total. Las columnas de stock de las consultas de stock tampoco se suman.
- **Por moneda (V77)**: si la lista tiene importes en distintas monedas, el pie muestra un renglón por moneda, **Total en $**, **Total en U$S** (el código como está en la tabla Monedas del cliente); con una sola moneda en lo filtrado, un solo renglón. La moneda se toma sola de la primera columna de la lista que termina en `MonedasId` (ej. `CPBCMonedasId`). Si la moneda no es columna, se indica: `totalesMoneda: ChequesMonedasId` (dentro de `workwith:` o en la raíz de la consulta; se trae aunque no se vea). `totalesMoneda: no` = no se separa. Va en la misma lectura (`GROUP BY` moneda). El rótulo va en la columna anterior a la primera que suma, y la columna de moneda filtra con un combo de las monedas de la tabla Monedas (igual, no "contiene": "$" no trae "U$S").
- Cuidado con sumar importes que se repiten (ej. el importe de la cobranza en una consulta que trae un renglón por imputación): para esos, `sinTotales:`.

### 9.1 Contexto del usuario: empresa y división (el WWPContext de GeneXus)

> **E3**: el motor solo pone `&SecUserId` y `&SecUserName` (de `SecUser`). Lo demás de esta sección es de **OPTIMIST ERP**: lo agrega su extensión (`OptimistExtensionErp`, sección 23). Una aplicación sin extensión (ej. Factory) tiene solo esas dos variables.

En OPTIMIST ERP, al iniciar sesión se arma el contexto del usuario igual que `SecLogIn` → `SetDefaultUsuario`: empresa y división de `PerfilUsuario`, las de stock, costos, listas de precios y cta2 de `EmpDivStkCst` (stock y costos vacías = la del usuario) y `SecUserEntidadesPK`. La empresa/división se ve en el menú, debajo del usuario. Un usuario sin empresa asignada no puede entrar (salvo `Administrator`).

En el YAML se usan con `&`, **siempre entre comillas**:
```yaml
campos:
  - {nombre: PRSEmpresa,  dominio: Numeric(4), etiqueta: Empresa,  default: "&Empresa",  oculto: true}
  - {nombre: PRSDivision, dominio: Numeric(4), etiqueta: División, default: "&Division", oculto: true}
workwith:
  columnas: [PRSId, PRSNumero, PRSFecha]
  filtro: {PRSEmpresa: "&Empresa", PRSDivision: "&Division"}
```
- `default: "&Empresa"`: el alta se graba en la empresa del usuario (como `Default(PRSEmpresa, &WWPContext.Empresa)`).
- `workwith.filtro`: el WorkWith (y su Excel) muestra solo esas filas (como las Conditions `PRSEmpresa=&WWPContext.Empresa` del WW). También acepta valores fijos: `{ArticulosEstado: 1}`. Si el contexto no tiene valor (Administrator sin empresa), ese filtro no se aplica.
- Variables (OPTIMIST ERP): `&SecUserId`, `&SecUserName`, `&Empresa`, `&Division`, `&EmpStk`, `&DivStk`, `&EmpCst`, `&DivCst`, `&EmpLst`, `&DivLst`, `&EmpCta2`, `&DivCta2`, `&PerfilUsuarioColorLogo`, `&EmpresaCaption`, `&SecUserEntidadesPK`. Otra variable da error al validar.
- Qué empresa / división usar (V65j, para todo el sistema): stock y almacenes `&EmpStk` / `&DivStk`; listas de precios `&EmpLst` / `&DivLst`; costos `&EmpCst` / `&DivCst`; comprobantes `&Empresa` / `&Division`. Ej.: `{nombre: AlmacenesEmp, ..., default: "&EmpStk"}` hace que la lupa de almacenes traiga los de la empresa de stock.

---

### 9.2 Acciones del renglón (`workwith.acciones`, V48)

Lo que en GeneXus define el WorkWithPlus para cada renglón: qué acciones se ven y con qué condición. Ej. un presupuesto Aprobado no se modifica ni se cancela.

```yaml
workwith:
  acciones:
    modificar: "(PRSStatus = 'C' or PRSStatus = 'T')"   # condición sobre el renglón; false = nunca
    borrar: false                                       # PRS no se borra: se cancela
    # visualizar: (sin poner = siempre)
    renglon:
      - {titulo: Pasar a En Trámite, icono: CLOCK, si: "PRSStatus = 'C'", proc: PRSPasarAEnTramite, parametros: [PRSId],
         confirmar: "¿Pasa el presupuesto a En Trámite?"}
      - {titulo: Cancelar Presupuesto, icono: CLOSE_BIG, si: "PRSStatus = 'T'", proc: PRSCancelar, parametros: [PRSId, "&Mensaje"]}
      - {titulo: Transferir Presupuesto, icono: EXCHANGE, panel: WPTransferirPRSPED, parametros: ["'PRS'", PRSId, PRSEmpresa]}
      - {titulo: Ver Pedido, icono: SEARCH, trn: PED, modo: Display, parametros: [PRSPEDId]}
```

- `modificar`, `visualizar`, `borrar`: sin poner = siempre; `false` = nunca; un texto = la condición.
- Las condiciones son fórmulas sobre los campos del renglón: `=`, `<>`, `<`, `>`, `and`, `or`, `not`, textos entre comillas simples. Los campos que usan se traen solos (no hace falta ponerlos en `columnas` ni en `ocultas`).
- Cada acción del renglón hace UNA cosa: `proc:` corre un procedimiento de `proc/`; `panel:` abre un web panel (`wbp/`, con su `parm:`); `trn:` abre otra TRN (`modo: Display` o `Update`) en el registro del primer parámetro.
- `parametros`: campos del renglón (`PRSId`), constantes (`1`, `'PRS'`, `true`) o `"&Variable"` (de salida: si el procedimiento devuelve un texto ahí, se muestra). Van en el orden del `Parm` del procedimiento o del panel.
- (V66k) `cns: Consulta` abre una consulta (`consultas/`, sección 21) con lo que recibe: `parametros` en el orden de su `recibe:`. Ej. Asientos: `{titulo: Ver Imputaciones, icono: LIST_UL, cns: ExtraWWDetAsientosDetalle, parametros: [AsientosId]}`.
- `confirmar`: pregunta antes de hacerlo. `icono`: un ícono de Vaadin (CLOCK, BAN, COPY, ENVELOPE, PRINT, EXCHANGE, THUMBS_DOWN, FILE_ADD, CHECK, SEARCH, PENCIL...). El color lo pone el motor según el ícono (V56d), igual en el menú y en el renglón: PENCIL amarillo, SEARCH azul, CLOSE (Eliminar) rojo, CLOSE_BIG (Cancelar) y BAN (Desautorizar) bordó, CHECK verde, PRINT gris azulado, COPY violeta, ENVELOPE naranja, FILE_ADD verde agua, EXCHANGE índigo, CLOCK marrón, CALENDAR fucsia, THUMBS_DOWN naranja oscuro; otro ícono toma un color fijo de una paleta.
- Después de un procedimiento se muestran sus `Msg()`, lo que deja en `&WWPContext.MensajeError` (como el WorkWithPlus: "El Presupuesto 69 fue Cancelado") y los textos que devuelve; la lista se actualiza.
- Si una condición está mal escrita, la TRN no abre y lo dice; si falla con los datos de un renglón, esa acción no se muestra en ese renglón.
- **V65o**: la acción cuya condición no se cumple en un renglón se muestra **en gris**, con el motivo (la primera parte unida por `and` que da falso y sus valores de hoy). Solo se saca del todo la de otra instalación (`&Instalacion` o una variable del `inicio:` que sale de ella). Conviene escribir las condiciones partidas por `and` (una parte por cosa a controlar): así el motivo dice exactamente cuál falla.
- **Lista vieja (V48c)**: la condición se vuelve a controlar contra la base en el momento de hacer la acción (después del "Sí" de `confirmar`). Si otro usuario u otra pantalla cambió el registro y la condición ya no se cumple (ej. el presupuesto ya está Aprobado), la acción no se hace, se avisa y se actualiza la lista. Vale también para `modificar` y `borrar`. No hay que configurar nada.
- **Dos pantallas sobre el mismo registro (V48c)**: al confirmar una TRN en Modificar, si la cabecera en la base cambió desde que se abrió el formulario, no se graba y se avisa qué campos cambiaron (hay que cancelar y volver a abrir). Tampoco hay que configurar nada.

**V50l: condiciones con procedimientos y variables del WorkWith.** Como en el WorkWithPlus de GeneXus, una condición puede:
- usar las `&Variables` que carga el **inicio de las acciones** (el evento Start del WorkWith de GeneXus), escrito como el `inicio:` de una TRN (bloque `Variables` + código GeneXus). Corre una vez al abrir la lista;
- llamar procedimientos: `PEDVerificarBajaCambio.Udp(PEDId, TrnMode.Update)`, `GetParamGlbValorBoolean.Udp(112)`. `TrnMode.Update` / `Insert` / `Delete` / `Display` valen como en GeneXus.

```yaml
workwith:
  acciones:
    inicio: |
      Variables
        &Instalacion            VarChar(20)
        &TransfierePedidos      Boolean
      EndVariables
      GetParamGlbValorAlfa.Call(1, &Instalacion)
      GetParamGlbValorBoolean.Call(112, &TransfierePedidos)
    modificar: "PEDVerificarBajaCambio.Udp(PEDId, TrnMode.Update)"
    renglon:
      - {titulo: Generar Entrega, si: "(PEDStatus = 'E' or PEDStatus = 'V') and PEDFacturado = false and &Instalacion = 'IEP'", proc: ProcPED_IEP_Entrega, parametros: [PEDId]}
```

Ojo: una condición con procedimientos se evalúa en cada renglón de la página (como en GeneXus): conviene que sean procedimientos livianos.

(V50m) Dentro de los procedimientos, `TrnMode.Insert` / `Update` / `Delete` / `Display` valen `"INS"` / `"UPD"` / `"DLT"` / `"DSP"`: la variable que los recibe tiene que ser `Character(3)` (como en GeneXus). Si en un `.proc` viejo aparece `&TrnMode Numeric(4)` o `&Mode Numeric(4)`, cambiarlo a `Character(3)`.

**PED (V50m)**: Modificar con `PEDVerificarBajaCambio.Udp(PEDId, TrnMode.Update)`; sin Eliminar; Cancelar, Autorizar, Desautorizar, Dar por Cumplida, Asignar Fecha de Entrega, Pasar a Pendiente (solo STONCOR y CONCORPLAST) y Transferir (parámetros 112 / 237). El `inicio` carga `&Instalacion`, `&TransfierePedidos`, `&TransfierePedidosCuenta1Cuenta2` y `&PEDPideMotivo` (algún control de autorización prendido: 76-80, 198). Es el ejemplo más completo de acciones con inicio y procedimientos.

Generarlas desde la KB (`herramientas/acciones_gx.py`, agrega o reemplaza solo el bloque `acciones:`):
```
python3 acciones_gx.py D:\ruta\OptimistU12NEW.xml --trn ..\src\main\resources\trn --probar          (solo muestra)
python3 acciones_gx.py D:\ruta\OptimistU12NEW.xml --trn ..\src\main\resources\trn --solo PRS,PED   (escribe)
```
Saca de la grilla del Trabajar con: Update / Display / Delete (si la grilla no los tiene, esa acción no va) y cada userAction con su condición (enumerados por su valor, `in (a, b)` como `or`), ícono, confirmación y parámetros. Quedan comentadas (con `# REVISAR`) las acciones que el motor todavía no puede hacer: impresiones (PDF), las que usan métodos de Business Component que el motor todavía no tiene (`Insert`, `Update`, `InsertOrUpdate`) y las que llaman a un procedimiento o panel que falta (`gx2proc.py`, `gx2wbp.py`).

Hoy: **PRS** (Modificar en En Carga / En Trámite; sin Eliminar; Pasar a En Trámite, Cancelar, Dar por Perdido, Transferir) y **PED** (V50m, ver arriba). En la KB hay 61 TRN con acciones o condiciones propias.

## 10. Menú (`menu.yaml`)

```yaml
Menú General:
  - {titulo: Monedas, trn: Monedas}                 # TRN del motor
  - {titulo: Países / Provincias, trn: Pais}
  - {titulo: Tablero, ruta: tablero}                 # pantalla programada a mano (ruta Vaadin)
  - {titulo: Favoritos, pendiente: wpfavoritos}      # V49n: todavía no está: en gris, avisa al tocarla
Stock y Producción:
  Mantenimiento de Tablas:
    - Artículos:                                     # carpeta dentro de carpeta
        - {titulo: Artículos, trn: Articulos}
    - {titulo: Días de Producción, trn: DiasProduccion}
  Procesos: []
  Consultas: []
```
- El orden del archivo es el orden en pantalla. Las carpetas se anidan a cualquier nivel.
- (V49n) **Opciones pendientes**: `pendiente:` marca una opción del menú de GeneXus que todavía no está en el motor; el valor es el programa de GeneXus. Se ve en gris e itálica, y al tocarla avisa "todavía no está en OPTIMIST ERP". No aparece en el buscador. Así se ven todas las categorías, como Estadísticas y Servicios, aunque ninguna de sus opciones esté migrada. Con el menú por permisos (tablas Menu y MenuSecUser), una opción del usuario que todavía no tiene pantalla también aparece en gris.
- (V59d) **Ocultar** (`Ocultar:` al final de `menu.yaml`): lista de títulos de opciones o carpetas, o programas de GeneXus (el MenuURL sin extensión), que el menú no muestra nunca. Hace falta porque, con el menú por permisos (tablas Menu y MenuSecUser), el menú sale de esas tablas y no de este archivo: sacar una opción de `menu.yaml` no la saca del menú del usuario. Ej.:
  ```yaml
  Ocultar:
    - Favoritos        # la carpeta entera, con lo que tenga adentro
    - rowsforwwp       # el programa de GeneXus (Filas por Web Panel)
  ```
  Las tablas no se tocan: el OPTIMIST de GeneXus las sigue usando igual. **Necesita el motor V59d.**
- El buscador del menú busca en todos los niveles.
- El menú arranca cerrado; las carpetas que el usuario abre quedan abiertas mientras navega (durante la sesión).
- Funciona como acordeón: al abrir una carpeta se cierran las demás del mismo nivel (queda una sola rama abierta).
- La pantalla actual aparece recuadrada en el menú (recuadro ceñido al texto), con el mismo color que el recuadro del WorkWith.
- (V46) **Menú vertical u horizontal**: cada usuario elige en Apariencia → "Menú". *Vertical* es el de siempre, al costado. *Horizontal* es una barra arriba, como el OPTIMIST ERP de GeneXus: cada categoría de `menu.yaml` despliega sus opciones **al hacer clic** y las carpetas de adentro se abren como submenús. La pantalla actual y sus carpetas van en negrita con el color principal. Las categorías que no entran en el ancho pasan al botón "···" del final de la barra. El buscador muestra cada opción con su carpeta ("Presupuestos — Ventas › Comprobantes") y al elegirla la abre. El logo lleva al Inicio. En el celular el menú es siempre el vertical (☰). El mismo `menu.yaml` sirve para los dos.

---

## 11. Generar una TRN desde GeneXus (`herramientas/gx2yaml.py`)

```
python3 gx2yaml.py D:\ruta\OptimistU12NEW.xml Articulos --solapas 1,2,4,6,7,10,17 --salida ..\src\main\resources\trn
```

| Parámetro | Qué hace |
|---|---|
| `--solapas` | Números de solapa del web form a incluir (1 = la primera). Sin él, todas. |
| `--workwith A,B,C` | Columnas del WorkWith. Si no se pasa, **se conservan las del YAML existente**. |
| `--titulo`, `--singular`, `--plural`, `--genero f`, `--encabezado "..."` | Textos de la TRN. Si no se pasan, se conservan los del YAML existente. Sin encabezado previo, se arma "Singular {código} - {descripción}". |
| `--cache archivo.json` | Guarda la KB procesada para que la próxima corrida de la misma TRN sea instantánea. |

**Varias TRN de una vez** (`gx2yaml_lote.py`, lee la KB una sola vez):
```
python3 gx2yaml_lote.py D:\ruta\OptimistU12NEW.xml --un-nivel --salida ..\src\main\resources\trn --cache kb_todas.json --saltear Articulos,Monedas --no-pisar
python3 gx2yaml_lote.py D:\ruta\OptimistU12NEW.xml --trns Vendedores,Bancos --salida ..\src\main\resources\trn --cache kb_todas.json
```
`--saltear` no toca esas TRN; `--no-pisar` no regenera las que ya tienen YAML. Deja un `_informe_lote.txt` con lo generado, lo salteado y los errores.

Combos definidos en el **atributo** (Control Type = Combo Box en GeneXus) se generan como dominio con valores, y el **Initial Value** del atributo pasa a `default:`. Las TRN sin web form en GeneXus salen con todos los campos visibles y sin `pantalla`.

Fórmulas redundantes (V42): las marcadas como redundantes en la estructura de la TRN de GeneXus (`isRedundant`), o las que figuran en un índice de la tabla, se generan con `guardar: true`. Para los YAML que ya existen: `python3 parchar_redundantes.py OptimistU12NEW.xml --trn ../src/main/resources/trn` agrega las que faltan y marca las que están sin `guardar` (sin regenerar nada).

Los `Default` que GeneXus tiene en bloques `[win]` solo se usan para claves que no son clave foránea (ej. `Filtro01 = 0`).

Qué trae de la KB: tipos reales (dominios, subtipos), combos, qué se guarda y qué se infiere (referencias), tablas y claves, nulables, autonumerados, la pantalla copiada del web form (renglones y tablas lado a lado), títulos de las grillas, `NoAccept`, `Default` con constante, `Error ... if X.IsEmpty()`, `Serial` y auditoría.
Lo que no sabe traducir queda al final del YAML como comentario `PENDIENTE`.

Formularios sin solapas en GeneXus (como Monedas) se generan sin `<solapa>`.

**WorkWith (V30)**: las columnas visibles, sus títulos y el orden inicial se toman del `WW<trn>` de la KB; las fórmulas no guardadas quedan afuera. Las Conditions `Campo=&WWPContext.X` pasan a `workwith.filtro` y los `Default(Campo, &WWPContext.X)` a `default: "&X"`.
Los campos que en GeneXus no son columnas (subtipos de fórmulas, o que llegan por una tabla que el nivel no referencia) quedan listados en un comentario `# Omitidos` al final.
`X.Visible = &ModuloMutual=true` se traduce a `visibleSi: ModuloMutual`.

**Ojo:** regenerar pisa el archivo. Se conservan: columnas del WorkWith, `titulo`/`singular`/`plural`/`genero`, y el `titulo` y `orden` de cada detalle. Los retoques a mano de campos o pantalla se pierden.

**Paneles de GeneXus** (`paneles_gx.py`, V33): agrega a los YAML los paneles plegables del web form sin regenerar la TRN (respeta los retoques de la pantalla). Los paneles fijos no se traen. Una TRN que ya tiene paneles no se toca.
```
python3 paneles_gx.py D:\ruta\OptimistU12NEW.xml --cache kb_todas.json --trn ..\src\main\resources\trn [--solo PRS,PED] [--probar]
```

**Fórmulas completas** (`parchar_formulas.py`, V34): agrega a los YAML existentes, ocultos, los atributos que usan sus fórmulas (otras fórmulas, como `PRSTotalIva01`, o datos de otra tabla, como `ArticulosTotalizadorItemizado`, con su referencia y dominio). También limpia fórmulas que quedaron con comentarios de GeneXus en la misma línea (solo si nadie las tocó). No regenera la TRN. Ya se aplicó a las 26 TRN que lo necesitaban; se puede volver a pasar sin riesgo (lo que ya está no se repite).
```
python3 parchar_formulas.py D:\ruta\OptimistU12NEW.xml --trn ..\src\main\resources\trn [--solo PRS,PED] [--ver]
```
Desde la V34 el generador ya incluye esos atributos ocultos en las TRN nuevas.

### 11.1 OPTIMIST Designer (`herramientas/disenador/OPTIMIST-Designer.html`)

Programa aparte: se abre con doble clic en **Chrome o Edge** (no necesita instalar nada ni internet).
1. **Abrir carpeta**: elegí la carpeta del proyecto (o `src/main/resources/trn`). La próxima vez aparece "Reabrir".
2. Elegí la TRN en la lista de arriba.
3. **Izquierda**: el árbol (cabecera, detalles, pantalla, WorkWith). **Centro**: el formulario o el WorkWith tal como se verán. **Derecha**: las propiedades del elemento elegido.
4. **Arrastrar y soltar**: un campo sobre otro los intercambia; sobre un hueco lo mueve; sobre "soltá acá" crea un renglón; sobre una solapa lo manda a esa solapa; a la papelera lo saca de la pantalla. También se arrastran los detalles, las solapas (para ordenarlas), los campos del árbol y las columnas del WorkWith.
   **Clases y letra (V45)**: en las propiedades del campo, "Clase" (las de `dominios.yaml` y las de la TRN) y "Letra"; debajo de la clase se ve el estilo que resulta. Negrita e itálica muestran lo que da la clase; destildarlas le saca eso a ese campo (`negrita: false`).
   **Estilo propio y clase (V48f)**: si el campo tiene `estilo:` propio, debajo de la clase se avisa "Estilo propio del campo (manda sobre la clase): …" con el botón **Sacar estilo propio**. Al cambiarle la clase a un campo se borran los `negrita: false` / `italica: false` que le quedaban de la clase anterior (si no, le sacaban la negrita a la clase nueva).
   **Crear o cambiar clases (V48e)**: botón **🎨 Clases** en la barra, o "+ Nueva clase" / "Editar …" en el Aspecto del campo. Se elige el nombre, si vale para **todas las TRN** (`dominios.yaml`, se graba en el momento) o **solo para esta TRN** (su `clases:`, se graba con Guardar), y negrita, itálica, letra, tamaño y color. Mientras se arma, la muestra "Así queda" y el formulario cambian en vivo; con "Usarla en <campo>" se ve ese campo con la clase y al crearla queda asignada. La ventana se mueve arrastrando su barra.
5. **Botón derecho** sobre un campo: visible, solo lectura, obligatorio, negrita, itálica, cuántas columnas ocupa, quitar de la pantalla. Sobre una tabla: agregar renglón/columna, meterla en un panel, poner un panel al lado. Sobre un panel: arranca abierto/cerrado, agregar tabla o panel adentro, desarmar. Sobre una solapa: nueva, mover, eliminar.
   **Paneles**: botones "+ Panel" y "+ Renglón con 2 paneles"; los paneles, tablas (desde su título ⠿), detalles y renglones se arrastran: sobre otro bloque lo pone antes; sobre "soltá acá…" de un panel lo mete adentro; sobre "+ al lado" de un renglón lo pone al lado; sobre una solapa lo manda a esa solapa.
   **Órdenes** (escritas en la barra de arriba o dictadas con 🎤): «poné AlmacenesDescrip en negrita», «ponelo en rojo con letra 18», «ocultá AlmacenesId», «sacale la negrita», «cambiá la etiqueta a Nombre del depósito», «hacelo obligatorio», «solo lectura», «mové ArticulosDescrip a la solapa Costos», «deshacer», «guardar». El campo se nombra como se llama (o por su etiqueta si es única); sin nombre, se aplica al campo elegido. El dictado usa el reconocimiento de voz de Chrome/Edge (necesita internet y permiso del micrófono).
6. **Guardar** (Ctrl+S): valida antes de grabar y reescribe **solo lo que cambió** (los comentarios y el resto del archivo quedan igual). La primera vez deja una copia `Nombre.yaml.bak`. Deshacer: Ctrl+Z.

Después de guardar, refrescá OPTIMIST para verlo y subí el cambio a GitHub como siempre.

### 11.2 Importar de GeneXus (E2, en el Designer)

Botón **⬇ Importar de GeneXus** de la barra del Designer. Hace lo mismo que `gx2yaml.py` (el mismo resultado, línea por línea),
pero sin instalar Python y eligiendo las TRN con tildes:

1. **Archivo de GeneXus…**: el export de la KB, **XML o XPZ** (Knowledge Manager → Export, con las transacciones, sus atributos,
   dominios, subtipos y las instancias de WorkWithPlus). Se lee en el navegador, por partes (sirve para KB de cientos de MB).
   Probado con GeneXus 17 + WorkWithPlus; lee también el formato de GeneXus 15.
2. **Carpeta de la aplicación…**: la del proyecto (usa `src/main/resources/trn`), una carpeta que tenga `trn` adentro, o la
   misma `trn`. Si no hay `trn`, ofrece crearla.
3. La lista muestra cada TRN con su **estado**: *nueva*, *ya migrada*, *cambió en GeneXus* (se importó y después la tocaron en
   GeneXus: lo sabe por el checksum del objeto) o *no se puede* (sin clave, o clave de un tipo que el motor no maneja).
   Las TRN del patrón WorkWithPlus (seguridad, menú) se ven tildando "Ver también las del patrón".
4. **👁 Ver**: la TRN convertida se abre en el diseñador **sin grabar nada** (formulario y WorkWith); "Volver al importador" deja
   todo como estaba. Ctrl+S no la graba.
5. **Importar**: si una TRN tildada usa otra que no está migrada (clave foránea, lupa), avisa y ofrece agregarla. Si alguna ya
   estaba migrada, pide confirmación fuerte (se pierde lo que se haya cambiado a mano) y deja `<TRN>.yaml.bak` del anterior.

Qué deja en la carpeta `trn`:

| Archivo | Qué es |
|---|---|
| `<TRN>.yaml` | La transacción para el motor. |
| `_gx/<TRN>.gx.txt` | Lo que no se tradujo solo: las reglas pendientes y los eventos completos de GeneXus, con instrucciones para pasárselo a la IA que use cada uno junto con esta guía. El importador no llama a ninguna IA. |
| `_importacion.json` | Control: KB, versión del export y, por TRN, el checksum de GeneXus y cuándo se importó. |

En GeneXus 17 las columnas, títulos y orden del WorkWith y las columnas de la lupa (`workwith.prompt`) salen de la instancia de
WorkWithPlus; los obligatorios de WorkWithPlus (`Error(Format('WWP_RequiredAttribute', …)) if X.IsEmpty()`) salen como
`obligatorio: true`, y las reglas con que WorkWithPlus recibe la clave por parámetro no quedan como pendientes.

---

## 12. Probar cambios de YAML en desarrollo

Con la aplicación levantada con `mvn spring-boot:run` desde la carpeta del proyecto, el motor lee los YAML directo de `src/main/resources` (no hace falta nada en el `pom.xml`):
**se graba el YAML y se reabre la pantalla** (o se refresca el navegador). No hace falta compilar ni reiniciar.
Si hubiera un error en el YAML, la pantalla lo dice al abrirla.

En producción (WAR) los YAML van adentro del WAR: un cambio requiere generar el WAR de nuevo.

**Al generar el WAR se validan todos los YAML** (cada TRN, `dominios.yaml` y `menu.yaml`, incluido que cada `trn:` del menú exista). Si hay errores, el WAR **no se genera** y Maven lista archivo y motivo. En un apuro se puede saltear: `mvn clean package -Pproduction -DskipTests`.

Los YAML y esta guía viven en GitHub (`diegocromberg/optimist-erp`): se editan en VS Code, se prueban y se suben con Confirmar + Sincronizar. Los zips de código no los traen, para no pisarlos.

---

## 13. Cuando algo falla

| Mensaje | Qué pasa | Qué hacer |
|---|---|---|
| `No existe trn/xxx.yaml` | La ruta del menú no coincide con ningún YAML. | Revisar `trn:` en `menu.yaml` y `transaccion:` en el YAML. |
| `No se pudo abrir la transacción. Xxx.yaml: ...` | Error de definición (con el motivo). | Corregir lo que indica (campo inexistente, dominio desconocido, falta la clave...). |
| `Unknown column ... Columnas del YAML que no existen en la base: ...` | Hay campos declarados como propios que no están en la tabla. | Marcarlos `inferido` (con su referencia) o sacarlos. |
| `no existe <Campo> <valor>` | La clave foránea no existe en la tabla referenciada. | Cargar un valor válido (usar la lupa). |
| `pantalla, línea N: ...` | Error en el bloque `pantalla`. | Corregir esa línea. |
| `X: 'renglones' ...` | `renglones` fuera de 1 a 30, o en un campo que no es de texto. | Corregir el número o sacarlo. |
| `Elegí primero ...` (al tocar la lupa) | Es una referencia de dos niveles y falta el padre. | Cargar primero el campo que indica. |

---

## 14. Formatos anteriores (siguen funcionando)

Si no hay `pantalla:`, se aceptan los formatos de versiones anteriores:
```yaml
solapas:
  - titulo: Datos Generales
    campos: [[ArticulosPK, ArticulosId], [ArticulosDescrip]]   # renglones
    tablas: [{etiquetas: izquierda, columnas: [260px, 1fr], filas: [[ArticulosPK, "ArticulosDescrip:2"]]}]
    detalles: [ListasPrecios]
filas: [[...]]      # renglones sin solapas
tablas: [...]       # tablas sin solapas
```
No se pueden combinar con `pantalla:`.

---

## 15. Todavía no soportado (próximas versiones)

- Parámetros de la instalación y del usuario (`Procesos`, `UsrProc`, `ParamGlb`): visibilidad condicional y defaults que dependen de parámetros.
- Reglas condicionales y con valores anteriores (`Old()`, `GetOldValue`), cálculos entre campos.
- Botones y eventos de usuario de la TRN (`Event 'X'`), `Refresh`, `Load`.
- Reglas sobre controles de la pantalla de GeneXus (`.Class`, `.Caption`, `.Link`, imágenes) y títulos de columna.
- En los procedimientos: métodos `Insert()` / `Update()` / `InsertOrUpdate()` de los business components (`Load`, `Save` y, desde la V50m, `Delete` sí), `FromJson` / `ToJson`, Excel real, archivos, `Submit`, SQL embebido.
- Fórmulas en las columnas del WorkWith.
- Archivos, imágenes, PDF.

---

## 16. Procedimientos (`proc/*.proc`, V34)

Un procedimiento es un archivo de texto en `src/main/resources/proc/`, en el lenguaje de GeneXus. **No se compila**: se graba y el próximo uso ya toma el cambio (en desarrollo; en el WAR, como los YAML). Viajan por GitHub igual que los YAML.

```
/* Qué hace:
   Calcula cuánto se descuenta de un importe con descuentos en cascada ("10+5").
   Devuelve en &ImporteOut el importe descontado.
*/
Parm(in: &ImporteIn, inout: &DtoStr, out: &ImporteOut);
Variables
  &ImporteIn    Numeric(14.4)
  &DtoStr       VarChar(20)
  &ImporteOut   Numeric(14.4)
  &Ctx          Sdt(WWPContext)
  &Lista        Coleccion(SDTStock)
EndVariables

LoadWWPContext.Call(&Ctx)
For each
    where PRSId = &PRSId
    &Total += PRSDetImporteIva
When none
    Msg("No hay renglones")
EndFor
```

- **"Qué hace"**: la explicación en castellano. Es documentación: lo que se ejecuta es el código de abajo.
- **Parm**: `in`, `out`, `inout`. También atributos (`Parm(in: ArticulosPK)`), que filtran los For Each como en GeneXus.
  - **`out` empieza vacío** (V66j, como GeneXus): el procedimiento no recibe el valor que tiene la variable de quien llama; si no la asigna, devuelve vacío (0, "", falso). `inout` (o sin modo) sí lo recibe. **Necesita el motor V66j.**
- **Variables**: `Numeric(l.d)`, `Character(n)`, `VarChar(n)`, `Date`, `DateTime`, `Boolean`, `Sdt(Nombre)` (estructura: `&Ctx.Empresa`), `Coleccion(Nombre)` (lista). El número se trunca a sus decimales y el texto a su largo, como en GeneXus.
- **Se puede usar**: asignaciones (`= += -=`), `If/Else/EndIf`, `Do Case`, `Do While`, `For &i = 1 To 10`, `For &x in &Lista`, `For each` (con `where`, `order`, `defined by`, `When none`; anidados), `Exit`, `Return`, `Sub`/`Do 'sub'`, `Msg()`, `Commit`/`Rollback` (V51f: `Commit` no hace nada: cada operación —confirmar una TRN, una acción del WorkWith, un botón de un web panel— es una sola transacción que se confirma al final o se deshace entera si hay un error; `Rollback` deshace todo lo hecho hasta ese momento en la operación), llamadas (`Proc.Call(...)`, `&x = Proc.Udp(...)`), `New ... When duplicate ... EndNew` (alta), asignar atributos y `Delete` dentro de un For Each (modificación y baja).
- **La tabla del For Each** se deduce como en GeneXus: por los atributos que se usan (tabla base + las que se unen por clave). Solo se consideran las tablas de la base, no las vistas; en un empate gana la tabla de una TRN (V66d).
- **Transacción**: todo el procedimiento es una unidad: si falla, no graba nada.
- **Contexto del usuario**: `LoadWWPContext.Call(&Ctx)` trae empresa, división, usuario… (`&Ctx.Empresa`). `SetWWPContext` lo cambia.
- **Duplicar con un Business Component** (V60b): `&Out = &In.Clone()` y después `&Out.Save()` graba una copia. Lo que el procedimiento **no tocó** después del `Clone()` (cabecera y renglones) queda **igual que en el original**: no lo pisan las reglas de la TRN, el cálculo comercial ni los valores por defecto (`default:`), aunque esté vacío. Lo que el procedimiento cambió o vació (`SetEmpty()`, una asignación) lo pueden completar las reglas y el cálculo como siempre (ej. `ClonarPED` vacía el IVA del renglón para que se recalcule con la tasa nueva). Las fórmulas que se graban (`guardar: true`) y la auditoría (alta: fecha y usuario) se calculan siempre.
- **Verificar una duplicación** (V60c): `CompararRegistros.Call("Tcbte", "C;FAC;0001", "C;FAC;0008", "TcbteDescripcion", &Diferencias)` compara la copia con el original **directo en la base**: la cabecera y todos sus niveles (también los ocultos), con todas las columnas de cada tabla, estén o no en el YAML. Los renglones se aparean por su clave propia. No compara la clave de la cabecera, la auditoría ni las columnas que se pasan para ignorar (lo que la duplicación cambia a propósito). `&Diferencias` queda vacío si son iguales, o con un renglón por diferencia ("Almacenes [1/1/3]: TcbteAlmTipoAlmacen original '1', copia '2'", "Impuestos: el original tiene 3 renglones y la copia 2"). Corre en la misma transacción, así que ve la copia antes del Commit. Compara como GeneXus (V65a): NULL es igual al vacío de su tipo (`''`, 0, `false`, fecha vacía). Para comparar a mano dos registros que ya existen: el web panel **Comparar Registros** (`wbp/compararregistros`).
- **Traducir de la KB**: `python3 gx2proc.py D:\ruta\OptimistU12NEW.xml Nombre1 Nombre2 --salida ..\src\main\resources\proc`. Deja el "Qué hace" para completar; lo que el motor todavía no ejecuta queda marcado `REVISAR:` en el encabezado. No pisa un `.proc` existente (salvo `--pisar`).
- **Data Providers** (V66j): un Data Provider de GeneXus que devuelve una colección de un SDT se traduce con `herramientas/gx2dp.py` a un `.proc` que la arma con For Each y la devuelve en el último parámetro; se usa igual que en GeneXus (`For &SDTCPBC in DPCPBCConta.Udp(...)`). Ya están los del Pasaje Contable: DPCPBCConta, DPCPBPContable, DPCOBRConta, DPCOBR, DPPAGO, DPMovCaja, DPMovBanco, DPMovStock.
- Ya están los 65 procedimientos que usan las fórmulas de las TRN (AplicarDescuentos, CalcularIva, GetArtCostoCPBC, GetStock…, GetParamGlb…), con su explicación.

---

## 17. Reglas e inicio (V35)

Las reglas de GeneXus van en la sección `reglas:` del YAML, **con la misma sintaxis** (cada una termina en `;` y puede tener comentarios `//` y `/* */`). El evento Start va en `inicio:` (como un procedimiento: `Variables … EndVariables` y código).

```yaml
inicio: |
  Variables
    &Decimales    Numeric(1)
    &Instalacion  VarChar(20)
  EndVariables
  GetParamGlbValorNumerico.Call(30, &Decimales)
  GetParamGlbValorAlfa.Call(1, &Instalacion)

reglas: |
  /*  Precio neto del artículo  */
  PRSDetPrecioNeto = PRSDetPrecio/((100+PRSDetTasasIvaTasa)/100) if PRSCatIvaDesglosa=false and PRSAfectaIva=true;
  PRSDetPrecioNeto = Round(PRSDetPrecioNeto, &Decimales);
  PRSDetSubtotal = DetSubtotalPRS.Udp(ArticulosTotalizadorItemizado, PRSDetSubtotal, PRSDetBultos, PRSDetCantidad, PRSDetPrecioNeto, PRSDetValorDescuento, ArticulosManejoStock) if after(PRSDetValorDescuento);
  GetVendedor.Call(PRSEntidadesPK, PRSVendedoresId, PRSVendedoresNombre) if after(PRSEntidadesPK);
  Error('Debe ingresar Razón Social.') if PRSEntidadesId = 0 and PRSRazonSocial0.IsEmpty();
  PRSDetBultos.Enabled = false if ArticulosManejoStock = 2;
  PRSComisionistaId.Visible = &Instalacion = "IEP";
  PRSVersion = PRSVersion + 1;
  &bDuplicado = VerificarPRSDetDuplicado.Udp(PRSId, ArticulosPK) on BeforeInsert;
```

| Regla | Qué hace |
|---|---|
| `Atributo = expresión [if condición]` | Asigna el valor (a un campo visible u oculto; se graba si es un campo de la tabla). |
| `&Variable = expresión …` | Variable de la TRN (se comparte con el inicio y las demás reglas). |
| `Proc.Call(A, B, &c)` / `Proc(A, B)` | Llama al procedimiento; sus parámetros `out`/`inout` vuelven a los atributos y variables. |
| `Atributo.SetEmpty()` / `.SetNull()` | Lo vacía. |
| `Error("texto") if …` | Frena el Confirmar y muestra el texto. |
| `Msg("texto") if …` | Muestra el aviso (no frena). |
| `NoAccept(A, B) if …` · `A.Enabled = …` | Habilita o deshabilita el campo (lo que ya es solo lectura sigue así). |
| `A.Visible = …` | Muestra u oculta el campo; en un detalle, la columna entera. |
| `Detalle.Visible = …` | (V65d) Con el nombre de un detalle (ej. `COBRFPago.Visible = &VerFormasDePago;`): muestra u oculta el detalle entero, y su panel si el panel no tiene otra cosa. Es el `Detalle_de_Pago.Visible` de GeneXus. |
| `A.Class = "Clase" if …` | (V49t) El campo de la cabecera toma el estilo de esa clase (`clases:` de la TRN o de dominios.yaml). Si ninguna regla `Class` del campo se cumple, vuelve a su estilo de siempre. Ej.: `PRSRentabilidad.Class = "AttributeTitleGreen" if PRSRentabilidad >= &EmpresaPorcentajeRentabilidad;` |

**Condiciones**: todo lo de las fórmulas (5.1) más `after(X)` (X cambió: lo cargó el usuario o lo asignó otra regla), `X.GetOldValue()` (el valor al abrir), `Insert`, `Update`, `Delete`, `&Mode = "INS"/"UPD"/"DLT"/"DSP"`, `Level(X)`.

**Cuándo se ejecutan** (como GeneXus web):
- **Al abrir**: el `inicio`, y las reglas de variables y de Enabled/Visible.
- **Con cada cambio**: las reglas del renglón que cambió (entero, como cuando GeneXus procesa la línea), las fórmulas y las reglas de la cabecera.
  Las reglas se ordenan solas por dependencias: primero la que asigna X, después las que usan X. Una regla con `after(X)` se dispara en el momento en que X cambia. Como en GeneXus, quien usa X se calcula con el **primer** valor que toma X (por eso el precio con descuento usa el descuento por unidad aunque después se multiplique por la cantidad).
  Para ver el orden en que corren: arrancar con `-Doptimist.reglas.depurar=true` (lo muestra en la consola).
- **Al confirmar**: la pasada completa de líneas y cabecera, los `Error` (si hay alguno, no se graba y se listan con su renglón), y las reglas `on BeforeValidate`, `AfterValidate`, `BeforeInsert`/`BeforeUpdate` y `AfterLevel`.
- **V82e — `AfterLevel` que lee el registro de la base**: si la regla `AfterLevel` llama a un procedimiento con la clave del registro (ej. `ValidarPartidas.Call("CPBC", CPBCId, &MsgErrorPartidas, &MsgWarningPartidas) on AfterLevel Level CPBCPartidasId`), corre con los renglones ya grabados, dentro de la transacción y antes de `BeforeComplete`, como en GeneXus; también los `Error`/`Msg` que usan lo que ese procedimiento devuelve. Un `Error` deshace todo. Las demás `AfterLevel` (cantidad de renglones, totales) siguen controlándose antes de grabar. No hay que cambiar nada en el YAML.
- **Dentro de la transacción**, después de grabar y antes de confirmar: `on AfterInsert`/`AfterUpdate` y `BeforeComplete` (un `Error` ahí deshace todo). **Después**: `on AfterComplete`.
- **V65k: todo el Confirmar es UNA transacción**, como la unidad de trabajo de GeneXus: las reglas de antes de grabar (sin evento, `AfterLevel`, `BeforeValidate`, `AfterValidate`, `BeforeInsert`/`BeforeUpdate`) y los procedimientos que llaman, el grabado y `AfterInsert`/`AfterUpdate`/`BeforeComplete`. Si algo falla (un `Error`, un procedimiento con `.Call()` o `.Udp()` que da error, una referencia que no existe), se deshace todo: no queda nada grabado a medias. Los `Commit` de los procedimientos no hacen nada; confirma el motor al final. `on AfterComplete` corre después de confirmar (lo que grabe ahí va aparte).
- Una regla que se asigna a sí misma sin otra que la inicialice (`PRSVersion = PRSVersion + 1`) corre una sola vez, al confirmar.
- Si un atributo no está en la TRN, el motor lo busca en las tablas que referencia el nivel (la "tabla extendida" de GeneXus).
- Si una regla falla (por ejemplo, un procedimiento que usa algo que el motor todavía no ejecuta), las demás siguen; el motivo queda en el log del servidor.

**Traducir de la KB** (`parchar_reglas.py`): pasa las reglas y el Start a los YAML, sin regenerarlos:
- Lo del patrón WorkWithPlus (`&Insert_…`, botones, celdas, clases), `Default`, `Serial`, `Prompt` y `NoAccept` fijos no se pasan (el motor ya los resuelve de otra forma). `[win]` y `[bc]` se descartan; `[web]` se desenvuelve.
- Los `Atributo.Visible/Enabled = …` del Start pasan a reglas.
- Agrega, ocultos, los atributos que usan las reglas y faltaban, y traduce a `proc/` los procedimientos que llaman (con sus dependencias).
- Lo que no se puede ejecutar queda comentado con `// REVISAR (motivo): …`.
- Una TRN que ya tiene `reglas:` no se toca (salvo `--pisar`).
```
python3 parchar_reglas.py D:\ruta\OptimistU12NEW.xml --trn ..\src\main\resources\trn --proc ..\src\main\resources\proc [--solo PRS]
```
Ya se aplicó a las 122 TRN que tienen reglas o Start en GeneXus (y a los 588 procedimientos que usan).

**Cuándo corren (V38)**: como en Excel, al cambiar un campo corren solo las reglas que lo usan (en la expresión, en el `if` o en `after()`), y en cadena las que usan lo que esas asignaron. Si una regla asigna el mismo valor que ya tenía, la cadena se corta. Al Confirmar corre la pasada completa, como GeneXus. Por eso una regla que depende de un dato tiene que nombrarlo: si usa algo que trae un procedimiento, que ese dato sea un parámetro del procedimiento.

**Reglas de un detalle con datos de la cabecera (V49c)**: en una regla de las líneas, `after(X)` con X de la cabecera vale siempre, porque cuando se procesan las líneas la cabecera ya está cargada; es igual que en GeneXus. Ejemplo: `BaseImponibleImpuestos.Call(...) If After(PRSFecha)` en los impuestos. Además, cuando cambia un total de la cabecera (un campo fórmula como `PRSSubtotal` o `PRSTotal`), se vuelven a correr en cada línea las reglas que lo usan. Por eso la base imponible de cada impuesto acompaña al subtotal mientras se carga el presupuesto. Los datos que carga el usuario en la cabecera no recalculan las líneas; eso sigue como antes.

Si al generar el WAR la validación dice `reglas, renglón N: …` o `inicio: renglón N: …`, esa regla no se puede leer: corregila o comentala con `// REVISAR`.

## 18. Cálculo comercial (`calculo:`, V36)

Las cuentas de plata de un comprobante (renglón y pie) no dependen de reglas de GeneXus: las hace el motor en Java, siempre en el mismo orden y con el mismo redondeo que OPTIMIST. Se configuran en la sección `calculo:` diciendo qué campo cumple cada papel:

```yaml
calculo:
  detalle: Det
  decimales: "&Decimales"                 # decimales de los precios (si no hay, el parámetro 30)
  ivaIncluido: "PRSCatIvaDesglosa = false"  # factura B: el precio trae el IVA
  afectaIva: "PRSAfectaIva = true"          # si no afecta, IVA 0
  linea:
    cantidad: PRSDetCantidad
    bultos: PRSDetBultos
    porBultos: "ArticulosManejoStock = 1"   # entonces multiplica por bultos
    precio: PRSDetPrecio
    descuento: PRSDetDescuento              # "20+10+5", en cascada
    tasaIva: PRSDetTasasIvaTasa
    precioNeto: PRSDetPrecioNeto            # salidas: las calcula el motor
    precioConDto: PRSDetPrecioNetoConDto
    valorDescuento: PRSDetValorDescuento
    subtotal: PRSDetSubtotal
    iva: PRSDetImporteIva
    noCalcular: "ArticulosTotalizadorItemizado = true"   # el totalizador del itemizado no se calcula como un renglón común
  itemizado:                                # V50w (opcional): comprobantes itemizados, el totalizador se recalcula en pantalla
    activo: PRSItemizado                    # expresión de cabecera: si es itemizado
    item: PRSDetItem                        # número de ítem del renglón
    totalizador: ArticulosTotalizadorItemizado   # si el renglón es el totalizador del ítem
  pie:
    base: PRSSubtotal                       # expresión: sobre qué se aplica la bonificación
    porcentajeDescuento: PRSPorBonPie
    descuento: PRSBonPie
    porcentajeRecargo: PRSPorRecPie
    recargo: PRSRecPie
```

Cómo calcula cada renglón:
1. **Precio neto** = precio / (1 + tasa/100) si el precio trae IVA; si no, el precio. Redondeado a `decimales`.
2. **Descuento** en cascada sobre el precio neto (cada porcentaje sobre lo que queda) → **precio con descuento**.
3. **Subtotal** = cantidad (o bultos) × precio con descuento, a 2 decimales.
4. **IVA** = subtotal × tasa / 100, a 2 decimales.
5. **Valor del descuento** = descuento por unidad × cantidad (o bultos).

**Estilo de toda una línea del detalle (V50x)**: en el detalle, `estiloRenglon: {si: "ArticulosTotalizadorItemizado = true", estilo: {negrita: true}}`. Las líneas que cumplen la condición (campos del renglón o de la cabecera) muestran todos sus campos con ese estilo (negrita, italica, letra, tamaño, color, como `estilo:` de un campo), en Modificar y en Visualizar; se reevalúa cuando cambia la línea (ej. al elegir el artículo). PRS y PED lo usan para los totalizadores del itemizado.

**Varias condiciones y solo algunos campos (V65c)**: `estiloRenglon` también puede ser una lista, y cada condición puede llevar `campos: [...]` para pintar solo esos campos de la línea (sin `campos`, toda la línea). Si se cumplen varias, se aplican todas en orden (la de más abajo manda). Si la condición deja de cumplirse, el campo vuelve a su estilo propio. Ej. (COBR; PAGO igual con sus campos):
```yaml
    estiloRenglon:
      - {si: "COBRDetClaseCbte = 'FAC' or COBRDetClaseCbte = 'NDB'", campos: [COBRDetImporteCbte, COBRDetImporte, COBRDetImputacion], estilo: {negrita: true, color: "#1a8f3a"}}
      - {si: "COBRDetClaseCbte = 'REC' or COBRDetClaseCbte = 'NCR'", campos: [COBRDetImporteCbte, COBRDetImporte, COBRDetImputacion], estilo: {color: "#d32f2f"}}
```
Un campo de `campos:` que no está en el detalle da error al abrir la TRN. Lo escrito antes (`estiloRenglon: {si: ..., estilo: ...}`) sigue igual.

**Estilo de todo un renglón del WorkWith (V61h)**: en `workwith:`, `estiloRenglon: {si: "ComposiDefault = true", estilo: {negrita: true}}`. Los renglones que cumplen la condición se ven enteros con ese estilo (todas las columnas, enlaces incluidos). La condición usa las columnas del WorkWith (y las `ocultas`). El estilo, como `estilo:` de un campo. Ej.: en Fórmulas (Composi), la fórmula por defecto va en negrita.

**Itemizado (V50w)**: con `itemizado:` y el comprobante itemizado, cada vez que cambia un renglón (o se borra) el renglón totalizador de cada ítem toma, como TotalizarIva al grabar: precio = suma de cantidad × precio de sus componentes; precio neto = suma de cantidad × precio neto; subtotal = cantidad del totalizador × ese precio neto; IVA = cantidad del totalizador × suma del IVA de los componentes. Lo usan PRS y PED (IEP).

Los totales **suman lo ya redondeado** de cada renglón (son fórmulas `sum()` de la TRN). En el pie, la bonificación va en cascada sobre la `base` y el recargo sobre (base − bonificación); el IVA de cada tasa se bonifica por separado con sus fórmulas (`PRSTotalIva01Bon`…). Las percepciones y el itemizado siguen como estaban (sus rutinas, al grabar).

Se recalcula al cambiar un renglón (antes y después de sus reglas, así las reglas ven los importes al día), al cambiar la cabecera (todos los renglones: por ejemplo, si el cliente pasa de A a B) y antes de grabar. Las reglas de GeneXus que hacían estas cuentas quedan comentadas con `// (lo calcula 'calculo:')`.

Si un campo no existe en el detalle o en la cabecera, o una expresión está mal escrita, la TRN no se abre y la validación dice `calculo: …`.

Hoy lo tienen **PRS** y **PED**. **CPBC** todavía no: ahí el descuento se guarda por unidad y se puede cargar como importe (con IVA incluido en factura B), así que falta definir esa variante.

---

## 19. Web panels (`wbp/*.yaml`, V48)

Un web panel de GeneXus (una pantalla que no es una TRN: pide datos, valida y hace algo) se define en `src/main/resources/wbp/<Nombre>.yaml`. Como las TRN: se graba el YAML y se reabre la pantalla, sin compilar. Lo que hace va en **código GeneXus tal cual** (los eventos), que interpreta el mismo motor de los procedimientos.

**Cómo se ve (V66n, V66q)**: cada variable lleva su etiqueta a la izquierda y el campo a la derecha, como en las TRN. Todos los renglones forman UNA tabla: las etiquetas de la primera columna quedan una debajo de la otra, los campos de la primera columna también, y lo mismo con la segunda, tercera... columna, tenga cada renglón la cantidad de campos que tenga. Cada columna tiene el ancho de su campo más ancho y ningún campo pasa a la columna de al lado (V66r). Todos los renglones van contra el margen izquierdo del formulario y el formulario queda centrado en la pantalla. Las variables `oculto: true` no cuentan; una variable que se oculta por reglas o eventos deja su lugar vacío (no corre a las de al lado) y un renglón oculto entero no deja hueco.

**Con el OPTIMIST Designer (V49h)**: con "Abrir carpeta" la lista muestra las transacciones y, aparte, los web panels de `wbp/`. Se puede:
- acomodar las variables en los renglones arrastrándolas, y sacarlas de la pantalla;
- cambiar la etiqueta, el dominio, el ancho, el valor por defecto, solo lectura, oculta y negrita;
- agregar o borrar variables, lupas (tabla, código, descripción, filtro y qué devuelve) y botones (título y evento);
- cambiar el título y los parámetros (Parm);
- escribir los eventos y las variables internas en su pestaña.

Al guardar se reescriben solo las secciones que cambiaron y la primera vez queda una copia `.bak`. Con "Abrir archivo" (V49i) también se graba en el mismo archivo que se abrió, pero sin la copia `.bak`.

```yaml
panel: PRSAltaWbp
titulo: Alta de Presupuesto a Cliente
variables:                          # las de la pantalla: como los campos de una TRN
  - {nombre: EntidadesTipoEntidad, dominio: TipoEntidad, etiqueta: Tipo Entidad, soloLectura: true}
  - {nombre: EntidadesId, dominio: Numeric(8), etiqueta: Entidad Id}
  - {nombre: EntidadesRazonSocial, dominio: Nombre, etiqueta: Razón Social, soloLectura: true}
  - {nombre: TcbteCPI, dominio: CPI, etiqueta: CPI, oculto: true}
pantalla:                           # renglones, en orden (cada uno, las variables una al lado de la otra)
  - [EntidadesTipoEntidad, EntidadesId, EntidadesRazonSocial]
prompts:                            # la lupa de una variable (la regla Prompt de GeneXus)
  - variable: EntidadesId
    tabla: Entidades
    codigo: EntidadesId
    descripcion: EntidadesRazonSocial
    donde: {EntidadesTipoEntidad: "&EntidadesTipoEntidad"}    # constante o "&Variable" (su valor en pantalla)
    devuelve: {EntidadesPK: EntidadesPK, EntidadesId: EntidadesId, EntidadesRazonSocial: EntidadesRazonSocial}
botones:
  - {titulo: Confirmar, evento: DoConfirmar}
  - {titulo: Cancelar, evento: Cancel}
internas: |                         # variables que usan los eventos y no están en la pantalla (como en un .proc)
  &WWPContext                 Sdt(WWPContext)
  &CheckRequiredFieldsResult  Boolean
eventos: |                          # código GeneXus: Event Start, Event &X.IsValid, Event 'Boton', Sub ... EndSub
  Event Start
    LoadWWPContext.Call(&WWPContext)
    &EntidadesTipoEntidad = 1
  EndEvent
  Event &EntidadesId.IsValid
    Do 'GetEntidadById'
  EndEvent
  ...
dominios:                           # los que usan las variables (como en una TRN)
  TipoEntidad: ...
```

Cómo funciona:
- **Start** corre al abrir. **Event &X.IsValid** corre cuando el usuario cambia X (o la elige con la lupa). Cada **botón** corre su evento; `Cancel` cierra.
- Los eventos ven y cambian las variables de la pantalla: lo que dejan vuelve a la pantalla. `Msg('...')` aparece arriba, en rojo (como el ErrorViewer).
- `&X.Visible = False` oculta la variable X; `&X.Enabled = False` la deja de solo lectura.
- `Trn.Call(TrnMode.Update, &Clave)` (ej. `PRS.Call(TrnMode.Update, &PRSId)`): al terminar el evento cierra el panel y abre esa TRN en ese registro (`TrnMode.Display` = Visualizar).
- **V84e — de un panel a otro**: `OtroPanel.Call(&A, &B, ...)` (ej. `CPBCWPAltaMultiremito.Call(&EntidadesPK, &TcbteCPI, &TcbteClaseCbte, &TcbteTipoCbte)` en `CPBCAltaWbp`): al terminar el evento cierra este panel y abre el otro en una ventana, con esos valores en su `parm` (en el orden del `parm`). Cuando el segundo termina (ej. `CPBC.Call(TrnMode.Update, &CPBCId)`), pasa lo mismo que si hubiera terminado el primero: desde el "+ Nuevo" del WorkWith, la lista se actualiza y se abre el registro. Se reconoce como panel si existe `wbp/OtroPanel.yaml` y no hay un procedimiento con ese nombre.
- Un error que no es un `Msg` (un procedimiento que falta, un dato mal) corta el evento y **deshace lo grabado**: un Confirmar a medias no deja registros creados.
- `&X.EnumerationDescription()` se escribe `EnumTexto.Udp(&X, "1=Activo;2=Inactivo")` (el generador lo hace solo).
- Todos los eventos se revisan al abrir el panel: un error de escritura se ve enseguida, con el renglón.
- (V65) Si un evento llama a un procedimiento que genera un PDF (una impresión con `Output_file`), el PDF se abre en la misma ventana al terminar el evento y el panel queda abierto. Ej.: Libro IVA Ventas (`WPIvaVentas`).
- (V66t) Si el procedimiento no imprimió nada (ej. el botón **Excel** de un listado que también tiene PDF), no se abre un PDF en blanco.

**Procesos largos (V66x)**: el evento de un botón corre en segundo plano; si tarda más de medio segundo aparece la ventanita "Procesando…". Un procedimiento puede ir informando qué hace con `MostrarProgreso.Call("Procesando artículo: " + Trim(&ArticulosId))` (ej. PrcActualizacionCostos, ListarStock). Fuera de un web panel no hace nada.
Para que la ventanita no cambie de ancho con cada texto (V66x3), antes del ciclo se llama una vez `AnchoProgreso.Call(<letras>)` con el largo del texto fijo más el de cada campo que se muestra (el largo declarado del dominio, ej. ArticulosId = 20): `AnchoProgreso.Call(21 + 20)` para "Procesando artículo: " + ArticulosId. Sin esa llamada queda en un ancho fijo por defecto (460 px).
**Índices en memoria (V84)**: para agrupar sin recorrer una colección por cada renglón (ej. ubicar el cliente de cada comprobante entre miles), un procedimiento guarda la posición de cada clave y la busca después:
```
IndiceLimpiar.Call('*')                              // todos (o IndiceLimpiar.Call('clientes'))
&Pos = IndiceBuscar.Udp('clientes', &Clave)          // 0 si no está
if &Pos = 0
    &Clientes.Add(&Cliente)
    &Pos = &Clientes.Count
    IndiceGuardar.Call('clientes', &Clave, &Pos)
endif
&Cliente = &Clientes.Item(&Pos)                      // es el mismo ítem: &Cliente.Total += ... cambia la colección
```
Sirve también para recordar un valor ya calculado (ej. el costo de cada artículo a una fecha). Son por usuario y por proceso; conviene limpiarlos al empezar y al terminar. Ejemplos: `EstadisticaVentas`, `EstadisticaAnualVentas`. Una colección dentro de un ítem de otra colección todavía no anda: usar una colección aparte (ej. 13 renglones por cliente, el cliente n en las posiciones (n-1)*13 + mes).

**% de avance (V66x4)**: antes del ciclo, `TotalProgreso.Call(&Total)` con la cantidad a procesar (artículos, clientes, proveedores...); desde ahí cada `MostrarProgreso.Call(...)` cuenta uno y la ventanita muestra "Avance: 37 % (123 de 344)". Conviene llamar a MostrarProgreso una vez por cada elemento contado, para que llegue al 100 %. Si el total no se sabe de antemano (ej. artículos de varios almacenes), el procedimiento calcula el % y lo manda con `PorcentajeProgreso.Call(&Porcentaje)` (0 a 100). Sin ninguna de las dos, la barra queda en movimiento sin %.

**Secciones (V66t)**: un renglón `- {seccion: Título}` en `pantalla:` pone una barra de título (como las secciones de GeneXus) que agrupa los renglones que siguen, hasta la próxima sección. Las columnas siguen alineadas en todo el panel. Si todas las variables de una sección están ocultas (`&X.Visible = False`), la sección tampoco se ve. En el OPTIMIST Designer: botón **+ Sección** debajo de los renglones; al elegirla se cambia el título, se sube, se baja o se saca.

```yaml
pantalla:
  - {seccion: Tipo de Listado}
  - [TipoListadoStock, ListarStockPor, FiltrarStockPor]
  - {seccion: Artículos}
  - [ArticulosIdDD, ArticulosDescripDesde]
```

**Combo con los registros de una tabla (V66t)**: como en las TRN (sección 6.1.2), en la variable: `combo: {tabla: ModeloCbtes, valor: ModeloCbtesId, texto: ModeloCbtesDescrip}`. Además (también en las TRN): `donde: "ModeloCbtesCPI = 'C'"` (condición SQL fija) y `vacioTexto: (Ninguno)` (lo que se ve sin elegir). `vacio: false` saca la cruz para dejarlo vacío.

**&Pgmname (V66t)**: el nombre del panel (en GeneXus, el nombre del objeto). Se usa como clave de lo que memoriza cada usuario: `PutUserCustom.Call(&Pgmname, &SDT.ToJson())` y `For each where UserCustomKey = &Pgmname ... &SDT.FromJson(UserCustomValue)` (ej. el botón Memorizar del Listado de Stock).

Dónde se usa:
- **Alta desde el WorkWith**: en la TRN, `workwith: {alta: PRSAltaWbp}` (V65c: también COBR con `COBRAltaWbp` y PAGO con `PAGOAltaWbp`; V84e: CPBC con `CPBCAltaWbp`). El "+ Nuevo" abre el panel en una ventana; cuando termina (ej. `PRS.Call(TrnMode.Update, &PRSId)`), la lista se actualiza y se abre el registro creado.
- **Desde el menú**: `- {titulo: Alta de Presupuesto, ruta: wbp/prsaltawbp}`.
- Una TRN también se abre en un registro con `trn/prs?abrir=123`.

Generarlo desde la KB (`herramientas/gx2wbp.py`):
```
python3 gx2wbp.py D:\ruta\OptimistU12NEW.xml PRSAltaWbp --salida ..\src\main\resources\wbp
```
Saca las variables del web form (con sus tipos, dominios y combos), los renglones, los Prompt (la tabla y los filtros que carga el Start), los botones y los eventos (con los enumerados reemplazados). Lo que no puede saber queda como `# REVISAR` arriba del YAML: sobre todo los filtros propios del objeto prompt (ej. *PromptTcbteFacturasClientes* trae solo comprobantes de clientes: `donde: {TcbteCPI: C, ...}`). Los procedimientos que llaman los eventos tienen que estar en `proc/` (`gx2proc.py`).

**PRSAltaWbp** (V48): el "+ Nuevo" de Presupuestos. Pide el cliente (Entidad Id con lupa de clientes; trae la razón social y el tipo de comprobante por defecto del cliente para la empresa/división) y el tipo de comprobante (lupa de facturas a clientes). Confirmar valida (obligatorios, estado del cliente, letra del comprobante según la categoría de IVA, comprobante habilitado para la empresa, vigencia de impuestos), llama a `PRSAlta` (crea el presupuesto con los datos por defecto del cliente) y abre el presupuesto en Modificar. Retoques a mano respecto de GeneXus: `PRSAlta` devuelve `&PRSId` (en GeneXus abría PRS él mismo) y la lupa de comprobantes filtra `TcbteCPI: C`.

**CambioNroFactura** (V59a): Cambio de Número de Facturas, desde el menú (`ruta: wbp/cambionrofactura`). Lee un comprobante de cliente por clase, tipo y número y cambia su número real, CAE, vencimiento del CAE y fecha (`CambiarNumeroFactura`, `CorregirFechaCPBC`). Ejemplo de dos cosas que conviene repetir en otros paneles que graban: `Procesar` no corre sin el registro leído, y antes de grabar lo vuelve a leer y compara con lo que se leyó (guardado en variables `internas`), para no grabar sobre un registro que otro cambió; y el aviso final con `&WWPContext.MensajeError` + `SetWWPContext` + `return` (aviso abajo y se cierra).

**Grillas editables (V61)**: un web panel puede tener una o más grillas para cargar varios renglones con las mismas columnas (el equivalente a un Free Style Grid editable de GeneXus). Van debajo de las variables, con un botón "Renglón" para agregar y una × en cada renglón para quitarlo:

```yaml
grillas:
  - nombre: Nuevos                  # en los eventos: la colección &Nuevos
    titulo: Artículos nuevos
    renglones: 5                    # renglones vacíos al abrir
    columnas:                       # como las variables: nombre, dominio, etiqueta, soloLectura, oculto, default
      - {nombre: Codigo, dominio: ArticuloId, etiqueta: Nuevo Código}
      - {nombre: Descrip, dominio: Descrip, etiqueta: Nueva Descripción}
internas: |
    &R   Sdt(Nuevos)                # un renglón
eventos: |
  Event 'DoProcesar'
    For &R in &Nuevos
      Msg(&R.Codigo + ' ' + &R.Descrip)
    EndFor
  EndEvent
```

- En los eventos la grilla es una colección de renglones (`For &R in &Nuevos`, `&Nuevos.Count`, `&Nuevos.Item(1).Codigo`). **Los renglones vacíos** (sin nada cargado) **no llegan**.
- Lo que el evento deja en la colección vuelve a la pantalla (se completa con renglones vacíos hasta `renglones`).
- Todavía no: lupa en una columna de la grilla, `IsValid` de una columna y edición de la grilla en el OPTIMIST Designer. **Necesita el motor V61.**
- **V65q — grilla fija** (`fija: true`): los renglones los pone el evento (ej. lo que devuelve un procedimiento en una colección); no hay "Renglón" ni ×, y llegan todos los renglones. Las columnas con `oculto: true` no se ven pero viajan con cada renglón (sirven para pasar una colección de GeneXus entera, ej. el SDTStock). `&Grilla.Visible = false` la oculta (ej. en el Start) y `true` la muestra. V66i: `&Columna.Visible = false` oculta una columna de la grilla (encabezado y celdas), ej. los bultos según VERCampo en `CPBCRemitirWbp`. Ejemplo: la grilla `Stock` de `CPBCAltaWbpConPedido`.
- **V74 — grilla de resultados** (`lista: true`): para mostrar lo que calcula un proceso (ej. la Anticuación de la Deuda, `wbp/WPAntDeuda.yaml`). Es una grilla como la del WorkWith: solo lectura, **orden con clic en el título**, columnas que se agrandan y se mueven, números en formato argentino (los enteros sin punto de miles: son códigos), combos con su texto. El evento la llena con una colección (`ProcX.Call(..., &Grilla)`, `&Grilla.Sort(...)`, `&Grilla.FromXml(...)`). Hasta 15 renglones se ve entera; con más, tiene barra.
  - `totales: [Col1, Col2]`: un renglón de totales al pie ("TOTALES (n)" en la primera columna y la suma de esas columnas).
  - `enlaces: {Col: {cns: Consulta, parametros: {Recibe: Columna | "&Variable" | constante}, si: "condición"}}`: el valor es un vínculo que abre esa consulta con lo que recibe (sin `si`, cuando el valor no es cero ni vacío). Ej.: `TotalDeuda: {cns: WWMovCtaCte, parametros: {EntidadesPK: EntidadesPK, Tramo: TO}}`.
  - **Al volver** de la consulta (Volver o Atrás del navegador) el web panel sigue como estaba: lo cargado y lo procesado. Vale para todos los web panels abiertos como página. **Necesita el motor V74.**
  - **V84 — enlace a una TRN**: `enlaces: {Col: {trn: CPBC, clave: CPBCId}}`: el valor es un vínculo que abre ese registro de la TRN en modo ver (la columna `clave` del renglón trae su clave); al cerrarlo se vuelve al web panel. Ej. el comprobante en las Estadísticas de Ventas y de Compras.
  - **V84e — también en las grillas fijas** (`fija: true`): una columna de solo lectura con `enlaces: {Col: {trn: CPBC, clave: CPBCId}}` muestra el valor como vínculo (ej. el remito y el pedido en `CPBCWPAltaMultiremito`).
  - **V84 — miles en los enteros**: los enteros de una lista van sin punto de miles (son códigos); para una cantidad, el dominio con `miles`: `Cantidad: "Numeric(13) miles"` (1.234).
  - **V84 — lupas en una columna (drill-down)**: `lupas:` abre una ventana con el detalle de ese renglón, que sale de **otra grilla del mismo panel** que el evento llenó junto con la principal (no se vuelve a procesar):
    ```yaml
    grillas:
      VtaClientes:
        lista: true
        columnas: [...]
        lupas:
          - {columna: Total, grilla: Cbtes, por: {Clave: Clave}, titulo: "Comprobantes de {Descripcion}", ayuda: Ver los comprobantes}
          - {columna: Descripcion, grilla: VendTipos, por: {Clave: Clave}, icono: tags, ayuda: Ver por Tipo de Artículo}
      Cbtes:
        lista: true
        ventana: true          # no se ve en el panel: solo en la ventana de una lupa
        columnas: [Clave, CPBCFecha, Comprobante, CPBCTotal]
        enlaces: {Comprobante: {trn: CPBC, clave: CPBCId}}
    ```
    - `columna`: al lado de qué valor va el ícono (lupa por defecto; `icono:` con el nombre de un VaadinIcon en minúscula: `tags`, `cubes`, `user`...). `ayuda`: el texto al pasar el mouse. `si:` (condición con las columnas del renglón) para mostrarla solo en algunos.
    - `grilla`: la grilla del detalle (`lista: true`); con `ventana: true` no ocupa lugar en el panel. `por: {ColumnaDetalle: ColumnaRenglón}`: los renglones del detalle que coinciden con el renglón elegido (ej. la misma `Clave`).
    - `titulo`: el de la ventana; `{Columna}` se reemplaza por el valor del renglón.
    - La ventana tiene sus totales, el orden por columna, los enlaces y sus propias lupas (se pueden encadenar: canal → artículos → comprobantes), y la X arriba a la derecha. Si desde la ventana se abre un comprobante o una consulta, al cerrarlo se vuelve a la ventana (V84c).
    - Ejemplo completo: `wbp/WPEstadisticaVentas.yaml`. **Necesita el motor V84.**
  - **V84 — gráfico de columnas al lado de la lista** (`grafico:`, el GoogleCharts de las estadísticas de GeneXus): la lista a la izquierda y el gráfico a la derecha (en una pantalla angosta, abajo).
    ```yaml
    grafico: {categoria: Descripcion, series: [Total, Cantidad, Kilos, Costo, Ganancia, Porcentaje], marcadas: [Total, Cantidad], derecha: [Cantidad, Kilos, Porcentaje], deA: 10}
    ```
    - `categoria`: la columna con el nombre de cada barra. `series`: las columnas numéricas que se pueden graficar (casillas arriba del gráfico; solo las que se ven: `&Columna.Visible = false` la saca). `marcadas`: las elegidas al abrir (si no, la primera). `derecha`: las que van con el eje de la derecha (ej. cantidades al lado de importes). `deA`: cuántos renglones por vez al abrir (10, 20 o 50; con ‹ › se recorre la lista). `titulo:` opcional.
    - Sigue el orden de la lista (también cuando el usuario la ordena), muestra el valor al pasar el mouse y usa los colores de la Apariencia. Se dibuja en el servidor (SVG), sin librerías ni Internet. Solo para grillas `lista: true` (no `ventana`).
- **V76 — el resultado en otra pantalla** (`resultado:`, como el WPShowXxx de GeneXus). Es lo que se usa para todo proceso que muestra resultados: el formulario queda con los filtros y **Procesar** (y Volver); al terminar sin errores, se pasa a otra pantalla con el resultado a todo el ancho.
  ```yaml
  resultado:
    titulo: Anticuación de la Deuda      # el título de la pantalla (si no, el del panel)
    si: Procesado                        # variable Boolean: después de un botón sin mensajes de error y con esta en true, va al resultado
    subtitulo: TextoResultado            # (opcional) variable con el texto debajo del título (ej. los filtros usados)
    variables: [OrdenAntDeuda]           # (opcional) variables que van arriba del resultado, como barra (no ponerlas en 'pantalla:')
    grillas: [Deudas, DeudasDias]        # las grillas 'lista' que van en el resultado (no se ven en el formulario)
    botones:                             # los botones del resultado; Volver se agrega solo
      - {titulo: PDF, evento: DoPDF, confirmar: false}
  ```
  - **Volver** (o el Atrás del navegador) vuelve al formulario como estaba; un vínculo de la grilla abre la consulta y al cerrarla vuelve **al resultado**. El resultado tiene su dirección (`wbp/<panel>?resultado=1`); si se recarga la página, va al formulario.
  - Conviene poner la variable de `si` en false al empezar el evento de Procesar (si no pasa los controles, no se muestra el resultado anterior). **Necesita el motor V76.**
- **V66 — por renglón** (lo que en GeneXus hacía el `Grid.Load` con `&Columna.Class` / `&Columna.Enabled` y el `IsValid` de una columna). Las condiciones y cálculos son fórmulas sobre las columnas del renglón (también las ocultas):

```yaml
grillas:
  - nombre: Stock
    fija: true
    columnas: [...]
    resaltar:                       # el renglón que cumple la condición toma el estilo de la clase (dominios.yaml o 'clases:' del panel)
      - {si: "CantidadPed > CantidadStock and ArticulosActualizaStock = true", clase: AttributeTitleRed, columnas: [ArticulosId, CantidadPed]}
      - {si: "bOkAlmacen = false", clase: AttributeTitleRed, columnas: [AlmacenesId]}   # sin 'columnas': todo el renglón
    habilitar:                      # la columna se puede tocar solo si se cumple (las de soloLectura siguen sin tocarse)
      BultosAUsar: "ArticulosManejoStock = 1 or ArticulosManejoStock = 3"
      CantidadAUsar: "ArticulosManejoStock = 2"
    calcular:                       # al cambiar algo del renglón se recalcula (si ninguna alternativa aplica, queda lo que estaba)
      CantidadAUsar: "BultosAUsar * ArticulosUnidadesPorBulto if ArticulosManejoStock = 3"
```

  Una columna que no existe o una fórmula mal escrita no deja abrir el panel y dice cuál es. **Necesita el motor V66.**

**Ir a la lista de una TRN (V66)**: en un evento, `WWCPBC.Call()` (como el botón "Ir a Comprobantes" de GeneXus) cierra el panel y abre el WorkWith de esa TRN (`WW` + el nombre de la TRN). En un procedimiento sigue sin hacer nada.

**Bajar un archivo (V66)**: `Link(&Archivo)` en un evento baja el archivo que armó un procedimiento en ese mismo evento (hoy: el Excel de un `&ExcelDocument`, ver sección 20). Ejemplo: el botón Excel de `WPIvaVentas` / `WPIvaCompras`.

**Confirmación de los botones (V66z)**: todo botón de un web panel (salvo los de `Cancel`) pregunta antes de correr: "¿Confirmás Procesar?" (el título del botón). En el botón: `confirmar: "Se va a actualizar ... ¿Confirmás?"` para una pregunta propia; `confirmar: false` para los que no procesan (buscar, ir a, ver, leer, comparar). **Necesita el motor V66z.**

**% de avance (V66z)**: la ventanita "Procesando…" muestra el % solo: el primer For Each de 2 o más filas que corre (el principal del proceso) informa sus vueltas. Si el procedimiento sabe mejor el total (o no recorre un For Each, ej. las filas de un Excel), `TotalProgreso.Call(&Total)` + un `MostrarProgreso.Call(&Texto)` por cada uno, o `PorcentajeProgreso.Call(&Porcentaje)`: desde ahí manda el suyo.

**Subir un archivo (`archivos:`, V66y)**: el *FileUpload* de GeneXus. Va como un renglón más del formulario (etiqueta a la izquierda), debajo de las variables:
```yaml
archivos:
  - {variable: UploadedFiles, etiqueta: Archivo a subir, extensiones: [xls, xlsx], maximo: 1}
internas: |
    &UploadedFiles              Coleccion(FileUploadData)
    &FileUploadData             Sdt(FileUploadData)
```
- Cada archivo elegido queda en el servidor (carpeta temporal) y se agrega a `&UploadedFiles` con `File` (la ruta completa), `Name` (el nombre con que se subió), `Extension` (sin punto, en minúscula) y `Size`. Al quitarlo de la lista, sale de la colección.
- `extensiones`: las que acepta (vacío = cualquiera). `maximo`: cuántos archivos (por defecto 1; 0 = sin límite). Hasta 30 MB por archivo.
- En el evento, como GeneXus: `For &FileUploadData in &UploadedFiles ... &FileUploadData.File ... EndFor`, `&UploadedFiles.Count`, `&UploadedFiles.Item(1).Extension`.
- Un Excel subido se lee en el procedimiento con `&ExcelDocument.Open(&Ruta)` (sección 20). Ejemplo: `WPDescargaCargaCostosPrecios` (Costos > Procesos > Actualización por Archivo). **Necesita el motor V66y.**

**Lupa con varios valores en `donde` (V66y)**: `donde: {ListasPreciosNrosStatus: [P, V]}` = cualquiera de esos valores (en preparación o vigente). También en la lupa que usa el `workwith.prompt` de la TRN de esa tabla.

**COBRAltaWbp con Pronto Pago (V66)**: con el parámetro 231 prendido aparece "Generar Pronto Pago" con el tipo de NC (por defecto el del parámetro 232). El primer Confirmar trae abajo la grilla fija `ProntoPago` (las facturas sin abonos, `COBRAltaProntoPago`); el segundo, con las tildadas, genera las NC y la cobranza (`ProcCOBRProntoPago`). Mismo esquema que la grilla de stock de `CPBCAltaWbpConPedido`: un panel en vez de dos.

**PasajeContable** (V66j): Pasaje Contable, desde el menú (`ruta: wbp/pasajecontable`, Contabilidad > Procesos). Pide el ejercicio (propone el abierto que contiene la fecha de hoy; con lupa, o tipeando el número, que trae sus fechas), Fecha Desde / Hasta (un mismo mes, dentro del ejercicio; el ejercicio no puede estar cerrado) y los módulos: Ventas, Compras, Cobranzas, Pagos, Caja, Bancos, Stock. Procesar llama a `PasarContabilidad` (todo en una transacción: si algo falla no queda nada grabado) y abre el Reporte del Pasaje Contable en la misma ventana.
- Qué genera: un asiento por módulo (fecha = Fecha Hasta, estado P, número `CONTA<empresa><ejercicio>`) con un renglón por cuenta, y el detalle por comprobante (DetAsientos); marca cada comprobante como contabilizado. Cómo se contabiliza cada comprobante se configura en **Tipos de Asientos** (cuenta, Debe/Haber, condición y fórmula de cada renglón) y en **Tipos de Asiento por Comprobante**; el tipo de comprobante tiene que ser contabilizable.
- Condición del renglón: `Variable Operador Valor`, ej. `CPBCTotal<>0`, `TipoArtsId='PT'`, `FPagoId=1`, `PAGOFPagoTipoCtaBcoCuenta='CC-123/4'` (texto entre comillas simples; operadores `= <> > < >= <=`). Las variables y fórmulas que entiende cada módulo son las de GeneXus (CumpleCondicionVentas, ...Compras, ...Cobranzas, ...Pagos, ...MovCaja, ...MovBanco, ...Stock).
- **Volver atrás** (V66j2): en Asientos Contables, "Cancelar Asiento" (solo Provisorio, con el ejercicio abierto y, V66k2, no los generados automáticamente por un comprobante) deja sin contabilizar los comprobantes de ese asiento y lo borra; después se vuelve a pasar. Como el pasaje arma un asiento por módulo y mes, cancelar uno deshace ese módulo de ese mes.
- Stock: los movimientos de la empresa / división de **stock** del usuario (EmpStk / DivStk).
- Si el asiento de un comprobante de ventas o compras no balancea por menos de 100, la diferencia va a la cuenta de redondeo de la instalación (DESANTIS 999999, IEP 426003 en ventas y 412010 en compras, TECNOCOM 41110300, el resto 9999999998).

**ReporteContable** (V66j): Reporte del Pasaje Contable desde el menú (`ruta: wbp/reportecontable`): el mismo PDF que sale al terminar el pasaje, sin grabar nada (el ejercicio puede estar cerrado). Muestra los comprobantes cuyo detalle no balancea, los asientos generados, los tipos de comprobante contabilizables sin tipo de asiento, y los comprobantes que no cumplieron ninguna condición o no tenían preasiento (estos dos, los del último pasaje de la sesión, como GeneXus).

**WSFE — Utilitarios Factura Electrónica** (V81, `ruta: wbp/wsfe`, Servicios): Último Autorizado y Consultar Comprobante en AFIP (producción). Con Tipo de Comprobante AFIP 019/020/021 va a exportación (WSFEX); si no, a local (WSFEv1). Al elegir el tipo de comprobante del sistema propone el tipo AFIP y el punto de venta de su serie, y Último Autorizado compara con el próximo número de esa serie. El resultado, dato por dato, en otra pantalla (`resultado:` con la grilla `Datos`). Lo único que graba es el ticket de acceso de AFIP (`TTiAAfip`) cuando no hay uno vigente. **Necesita el motor V81.**

**Factura electrónica en los eventos y procedimientos (V81)**: dos nativos (`OptimistAfip.java`), que no cortan el evento si algo falla: el error vuelve en `&MsgError`, y el ticket grabado se confirma igual.
- `AfipTicket.Call(&Servicio, &EmpCod, &EmpresaCuit, &ArchivoP12, &URLAfip, &GxToken, &GxSign, &MsgError)`: el ticket de acceso del WSAA (`&Servicio` = `wsfe` o `wsfex`). Usa el último de `TTiAAfip` mientras no venza; si no hay y el último que dio AFIP sigue valiendo (se borró la fila), lo vuelve a grabar; si no, lo pide a AFIP y lo graba.
- `AfipConsultar.Call(&Operacion, &EmpCod, &EmpresaCuit, &ArchivoP12, &URLAfip, &PtoVenta, &CbteTipo, &CbteNro, &CbteNroAfip, &Datos, &MsgError)`: `&Operacion` = `UltimoLocal`, `ConsultarLocal`, `UltimoExpo` o `ConsultarExpo`. Pide el ticket solo. `&CbteNroAfip` es el número que informa AFIP y `&Datos`, la respuesta en renglones `Dato` / `Valor` (una grilla `lista` con esas dos columnas).
- `&EmpresaCuit` y `&ArchivoP12` salen de `GetCUITNombreArchivoFE` (empresa, o tipo de comprobante con el parámetro 81). `&URLAfip` es el parámetro 71: vacío = WSAA de producción.
- **V82** `AfipLlamar.Call(&Metodo, &EmpCod, &EmpresaCuit, &ArchivoP12, &URLAfip, &Pedido, &Respuesta, &MsgError, &SinRespuesta)`: cualquier método de WSFEv1 / WSFEX (`FECAESolicitar`, `FECompUltimoAutorizado`, `FECompConsultar`, `FEDummy`, `FEXAuthorize`, `FEXGetLast_CMP`, `FEXGetCMP`, `FEXDummy`), con el ticket que corresponde.
  - `&Pedido` es un SDT con los nombres de AFIP. Ej.: `&Req.FeCabReq.PtoVta = 2`, `&Det.Iva = &IvaLista` (una `Coleccion(AlicIva)`), `&DetLista.Add(&Det)`, `&Req.FeDetReq = &DetLista`. Va en el orden del WSDL; lo vacío no se manda.
  - `&Respuesta` es lo que contesta AFIP, como SDT: `&Respuesta.FeCabResp.Resultado`, `&Respuesta.Errors.Item(1).Msg`, `&Respuesta.FeDetResp.Item(1).CAE`. Los valores llegan como texto (usar `Val()`).
  - `&SinRespuesta` = se cortó sin respuesta: no se sabe si AFIP lo procesó.
- **V82** `AfipBloquear.Call(&CPBCId, &SeriesNumeracionId, &MsgError)`: bloquea el comprobante y la serie hasta el fin de la transacción.
- **V82** Homologación: el parámetro 71 con `wsaahomo` manda todo a homologación (el ticket queda solo en memoria).
- El certificado es `<ArchivoP12>.p12`, con clave y alias iguales al nombre. Va en `$CATALINA_HOME/bin` o, en desarrollo, en la carpeta del proyecto. Nunca se sube a GitHub.

**Autorizar en AFIP** (V82): acción del WorkWith de Comprobantes (`proc: CPBCAutorizarAFIP`, `parametros: [CPBCId, "&Resultado", "&bOk"]`). Emite el comprobante antes de imprimirlo: número, CAE y vencimiento de AFIP (o número de la serie si no es electrónico). Si hay error lo muestra y no graba nada. Lo usa también Imprimir Comprobante.
**Imprimir Comprobante** (V84g): acción del WorkWith de Comprobantes (`proc: CPBCImprimir`, `parametros: [CPBCId, "&Resultado", "&bOk"]`) para lo que todavía no tiene número real. Remito: número de la serie de remitos (`CPBCAsignarRemito`) e impresión. Factura que genera remito: primero el remito (salvo BAIRES4, BAIRES4SRL y DESANTIS). Factura / ND / NC: corrige la fecha si quedó vieja (`VerificarCbteAImprimir`), emite con `CPBCAutorizarAFIP` (si da error no imprime ni graba) e imprime con `CPBCPrnArchivo`. Lo emitido se graba antes de imprimir.

**CPBCAltaNCWbp** (V82d): Nota de Crédito de una factura o nota de débito de cliente, desde la acción **Nota de Crédito** del WorkWith de Comprobantes (`panel: CPBCAltaNCWbp, parametros: [CPBCId]`, estado E / P / R). Se elige el tipo de NC (lupa: notas de crédito de clientes), el motivo y si es MiPyme-Anulación; en una factura de crédito MiPyme (proceso 147) propone la NC electrónica que corresponde (201→203, 206→208, 211→213). La sección **Devolución** se ve solo en TECNOCOM (lupa: devoluciones del cliente). Confirmar valida (letra según el cliente, comprobante habilitado para la empresa y el usuario, motivo, fechas de impuestos), crea la NC con `CPBCAltaNC` (copia cabecera, impuestos y renglones; estado G, sin número; vínculo con la factura en `CPBCRemitos`; FCE: ajuste −0,05 porque AFIP rechaza el mismo importe) y la abre en Modificar. Al confirmarla toma su número interno y su cuenta corriente; después, **Autorizar en AFIP** la manda con la factura como comprobante asociado. Si la factura ya tiene una NC, lo avisa al abrir.

**DupArticulos** (V61): Duplicar Artículos. Reemplaza a la TRN DupArticulos de GeneXus (que guardaba el pedido en una tabla y la dejaba con datos si algo abortaba). Artículo original con lupa y la grilla *Artículos nuevos* (código, descripción, descripción corta). Procesar valida todos los renglones antes de crear nada (código cargado, que no exista, que no esté repetido en la grilla, descripciones) y crea todos en una sola operación con `ClonarArticulo`: si uno falla o no queda igual al original (`CompararRegistros`), se deshace todo.

---

## 20. Impresiones (`impresiones/*.yaml`, V49)

Los reportes PDF de GeneXus (Imprimir Presupuesto...) se pasan en dos archivos con el mismo nombre:

- `proc/<Nombre>.proc`: la lógica, igual que en GeneXus (con `Output_file(&Archivo, 'pdf')`, que en GeneXus es una regla, para el nombre del PDF): los `For each`, las cuentas y **qué bloque se imprime y cuándo** (`print Encabezado`, `print Detalle`...). `Header ... End` se imprime al empezar cada página y `Footer ... End` al terminarla. `Eject` salta de página. `GXMLines` / `GXGetMLi` parten un texto en renglones (las observaciones). Los atributos que usa cada bloque se traen solos en el `For each` donde está el `print`, como en GeneXus.
- `impresiones/<Nombre>.yaml`: el diseño. Los bloques de GeneXus con lo que tiene cada uno, en **mm** desde la esquina de arriba a la izquierda del bloque.

```yaml
nombre: PRSPrnGenericoCantidad
hoja: {ancho: 217.47, alto: 304.25, izquierda: 7.57, arriba: 9.7, pie: 283.88}   # mm; pie: dónde empieza el Footer
letra: {familia: Helvetica, tamaño: 8}          # la de los elementos que no dicen otra
numeros: {miles: ".", decimal: ","}             # opcional; V50s: sin poner, formato argentino (1.007.937,97). Como GeneXus: {miles: ",", decimal: "."}
bloques:
  Encabezado:
    alto: 76.45                                 # lo que ocupa el bloque: el próximo va debajo
    elementos:
      - {imagen: "&LogoEmpresa", x: 2.03, y: 0, ancho: 76.2, alto: 25.4}
      - {recuadro: true, x: 135.38, y: 0, ancho: 69.85, alto: 13.97}
      - {texto: "COTIZACION NRO.:", x: 152.65, y: 0, ancho: 35.31, tamaño: 10, negrita: true}
      - {linea: true, x: 135.38, y: 6.35, ancho: 69.85}
      - {campo: "&PRSNumero", x: 158.75, y: 8.38, ancho: 23.11, alinear: derecha, formato: ZZZZZZZZ9, tamaño: 12, negrita: true}
      - {campo: PRSRazonSocial, x: 25.4, y: 33.78, tamaño: 10}
      - {campo: PRSFecha, x: 146.05, y: 33.78, formato: "99/99/99", tamaño: 10}
```

| Elemento | Qué es |
|---|---|
| `texto: "..."` | Texto fijo (la etiqueta de GeneXus). |
| `campo: X` | Un atributo (`PRSRazonSocial`, del `For each` donde está el `print`) o una variable (`&Total`). |
| `imagen: "&Var"` | Imagen de una variable: el blob de la base (logo de la empresa, foto del artículo, firma), una ruta del servidor o una URL. Se estira hasta llenar la caja, como en GeneXus; `proporcional: true` la deja sin deformar. |
| `imagen: Sello.jpg` | (V49b) Imagen fija: el archivo, al lado del YAML en `impresiones/` (las de la KB las saca `gx2prn.py`). Desde el procedimiento: `&Logo.FromImage(LogoIluminacionCenter)` (sin la extensión). |
| `linea: true` | Línea horizontal (`ancho`) o vertical (`alto`). `grosor: 3` la engrosa. |
| `recuadro: true` | Rectángulo (`ancho`, `alto`). `relleno: "#e0e0e0"` lo pinta por dentro (queda debajo de los textos del bloque). |

Propiedades: `x`, `y`, `ancho`, `alto` (mm); `alinear: derecha | centro` (los números van a la derecha); `tamaño` (pt); `negrita`, `italica`; `letra: Times` / `Courier` (Helvetica si no); `color: "#b00020"`; `ajustar: true` (el texto sigue en otro renglón dentro de la caja). `cortar: true` (V65, en un campo): si el valor no entra en el `ancho`, se corta como un texto fijo (ej. la razón social del Libro IVA Ventas). Un texto fijo con `ancho` se corta si no entra, como en GeneXus; los campos no se cortan. `hoja.pie` es dónde empieza el `Footer`: `gx2prn.py` lo calcula con el MB del reporte y el tamaño de hoja (A4), como lo ponía GeneXus. `formato` es la picture de GeneXus: `"ZZ,ZZZ,ZZZ,ZZ9.99"` (separador de miles y 2 decimales), `ZZZZZZZZ9.999`, `"99/99/99"` (fecha corta), `"99/99/9999"`.

Cómo se arma la hoja: cada `print` pone su bloque debajo del anterior; si no entra antes de `hoja.pie`, se imprime el `Footer`, se salta de página y se imprime el `Header`. Al final, el `Footer` de la última página. (V62) `Eject` cierra la página con su `Footer`; la siguiente, con su `Header`, se abre recién al imprimir el próximo bloque, como GeneXus (el encabezado sale con los valores de ese momento y un `Eject` al final no deja una hoja de más).

**Número de hoja (V65)**: un campo `&Page` en un bloque (normalmente el encabezado) muestra el número de hoja, como en GeneXus.

**Hoja (V65)**: `gx2prn.py` toma el tamaño y la orientación del reporte de GeneXus. Carta o A4 vertical: `{ancho: 217.47, alto: 304.25}`; oficio apaisada (ej. Libro IVA Ventas): `{ancho: 387.23, alto: 217.47}`. Se puede cambiar a mano en `hoja:` o en el Designer (propiedades de la hoja).

**Lineno (V63)**: `Lineno N` en el procedimiento pone el próximo bloque en el renglón N de la hoja (renglones de 15 centésimas de pulgada = 3,81 mm, contados desde `hoja.arriba`), como GeneXus. Si el `Footer` empieza con `Lineno N`, el pie va en ese renglón y el cuerpo de la hoja termina ahí (los renglones saltan de hoja antes de encimarse con el pie).

**Con el OPTIMIST Designer (V64)**: en la lista de arriba, grupo **Impresiones** (abrí la carpeta del proyecto: lee `src/main/resources/impresiones`). Cada bloque se ve dibujado a escala, uno debajo del otro, con la línea punteada del margen izquierdo.
- Arrastrá un elemento para moverlo (grilla de 0,25 mm; se cambia arriba; con **Alt** apretado, sin grilla). El cuadradito amarillo de la esquina lo estira (ancho y alto; en una línea, el largo). La franja de abajo de cada bloque cambia su alto.
- Con un elemento elegido: **flechas** lo mueven 0,25 mm (**Shift**: 1 mm), **Supr** lo borra, botón derecho: duplicar, traer al frente, mandar al fondo, borrar.
- A la derecha, las propiedades: del elemento (texto / variable / imagen, x, y, ancho, alto, letra, tamaño, negrita, itálica, color, alineación, ajustar, formato, grosor, relleno, proporcional), del bloque (nombre, alto, agregar texto / campo / imagen / línea / recuadro, subir, bajar, duplicar, borrar) y de la hoja (tamaño, márgenes, dónde empieza el pie, letra por defecto).
- **Valores de ejemplo** muestra los campos con un número o fecha según su formato (1.234.567,89; 12/10/26) en lugar de `[&Variable]`.
- Guardar reescribe solo los renglones que cambiaron (los comentarios quedan) y deja `<Nombre>.yaml.bak` la primera vez. Un bloque nuevo o renombrado hay que imprimirlo desde el procedimiento (`print Bloque`): el Designer no toca el `.proc`.

**QR de AFIP (V62)**: `GetQRArmarSDTQRImage.Call(&CPBCId, &QRImage, &QRImage64, &QRImageBlob)` arma los datos del comprobante y la imagen; en el diseño, `{imagen: "&QRImage", ...}`. Para otros datos, el nativo `GetQRImageAFIP.Call(&SDTQRImage, &QRImage, &QRImage64, &QRImageBlob)` con los campos de AFIP en el Sdt (ver, fecha, cuit, ptoVta, tipoCmp, nroCmp, importe, moneda, ctz, tipoDocRec, nroDocRec, tipoCodAut, codAut).

**Generar desde la KB**: `python3 herramientas/gx2prn.py OptimistU12NEW.xml PRSPrnGenericoCantidad --salida ../src/main/resources/prn` (el diseño) y `python3 herramientas/gx2proc.py OptimistU12NEW.xml PRSPrnGenericoCantidad --salida ../src/main/resources/proc` (la lógica; revisar los `EnumerationDescription()`, que van como `EnumTexto.Udp(Atributo, "2=Días;1=Horas")`). Las medidas salen iguales a las del PDF de GeneXus (comparado con un PDF de GeneXus: menos de medio punto de diferencia).

**En la pantalla**: la acción del WorkWith llama al procedimiento (ej. `proc: PRSPrn`) y el PDF se abre en una ventana con el visor del navegador (imprimir, descargar, abrir en otra pestaña). `VerPDF` y `WPVerPDFGenerico` de GeneXus no hacen falta (no hacen nada).

**Marca de agua de la empresa (V66)**: `GetFondoDeAguaEmpresa.Call("CPBC", &bExisteFA, &Imagen, &X, &Y)` lee la imagen y la posición de los logos de la empresa (Empresa, solapa Logos: "Fondo de agua", Fila = x, Columna = y, en puntos desde abajo a la izquierda) y `PDFAddWatermark.Call(&FileName, &FileName, &Imagen, &X, &Y)` la pone debajo de lo impreso en todas las hojas del PDF que se acaba de armar con ese nombre (con o sin `.pdf`), con su tamaño (1 píxel = 1 punto), como GeneXus. La reimpresión de comprobantes (`CPBCPrnArchivo`) ya la usa.

**Excel desde un procedimiento (V66)**: `&ExcelDocument` (`Ext(ExcelDocument)`) arma un .xlsx de verdad: `Open(&Nombre)`, `Cells(fila, col).Text / .Number / .Date / .Bold / .Color` (color = paleta de Excel: 3 rojo, 5 azul, 11 azul oscuro…), `AutoFit = 1` (ancho de columnas), `Save()`, `Close()`. Los números con decimales salen 1.234,56 (según el Excel de cada uno) y se pueden sumar; las fechas, dd/mm/aaaa. El archivo no se baja solo: lo baja el panel con `Link(&Nombre)` (sección 19). Un `Save()` sin `Open()` no arma nada. (V66y) Una columna con algún número de más de 2 decimales (costos) sale con 0,0000.

**Leer un Excel (V66y)**: `&ExcelDocument.Open(&Ruta)` con la ruta completa de un archivo que existe (el que se subió en un web panel, sección 19) lo lee (.xls o .xlsx, la primera hoja); si no se puede leer, `ErrCode` <> 0 y `ErrDescription` dice por qué. `Cells(fila, col)` devuelve: `.Text` (el número sin formato y con punto decimal, ej. `764.877`; la fecha dd/mm/aaaa), `.Number` (también de un texto `1.234,56`, `1234,56` o `1234.56`; si no es un número, 0), `.Date` (también de un texto dd/mm/aaaa; si no, vacía) y `.Type` (`N` número, `D` fecha, `C` texto, vacío).

---

## 21. Consultas (`consultas/*.yaml`, V52)

Una consulta es un WorkWith de solo lectura sobre la tabla de una TRN, con **parámetros arriba de la grilla** y **columnas que calcula el motor**. Es para las pantallas de GeneXus que recorren una tabla y por cada renglón llaman procedimientos para calcular columnas (ej. la consulta de stock). En lugar de eso, el motor calcula esas columnas en UNA consulta SQL.

- Archivo: `src/main/resources/consultas/<Nombre>.yaml`. Se abre en `/cns/<nombre en minúsculas>`. Conviene usar el **nombre del programa de GeneXus** (ej. `ExtraWWArticulosCnsStock`): así la opción del menú de la base (MenuURL) la encuentra sola. En `menu.yaml`: `- {titulo: Stock por Artículo, ruta: cns/extrawwarticuloscnsstock}`.
- La grilla es la de siempre: filtro por columna, orden, columnas por usuario (⚙), Excel, paginada en la base.

```yaml
consulta: ExtraWWArticulosCnsStock
titulo: Trabajar con Stock de Artículos
plural: artículos
trn: Articulos            # la tabla y las columnas propias (con sus filtros, combos y descripciones) salen de esta TRN
calculo: stock            # qué columnas agrega el motor (por ahora: stock)
parametros:
  - {nombre: ConsultaStock, etiqueta: Stock a consultar, valores: {1: Stock Actual, 4: Stock Disponible, 2: Stock Potencial, 3: Stock Total}, default: 1}
  - {nombre: CondicionStock, etiqueta: Condición, valores: {1: Todo, 2: Stock Positivo, 3: Stock Negativo, 4: Stock Cero, 5: Stock Distinto de Cero}, default: 1}
  - {nombre: ModeloAlmacenesId, etiqueta: Modelo de Almacenes, tabla: {tabla: ModeloAlmacenes, valor: ModeloAlmacenesId, texto: ModeloAlmacenesDescrip}, default: usuario}
columnas: [ArticulosId, ArticulosDescrip, ArticulosAlmacenesCantStk, ArticulosAlmacenesCantDisp, TipoArtsDescrip, MarcasDescrip]
titulos: {ArticulosAlmacenesCantStk: Cant. Stock}
anchos: {ArticulosDescrip: 320px}
procesos: {ArticulosAlmacenesBultStk: 156, ArticulosAlmacenesCantStk: 157, ArticulosAlmacenesBultPendEntrega: [156, 107]}
```

| Clave | Qué es |
|---|---|
| `trn` | La TRN cuya tabla se recorre. Las columnas que son campos de su cabecera (propios o inferidos) se muestran y filtran como en su WorkWith. |
| `calculo` | `stock`: agrega las columnas de stock de abajo. |
| `parametros` | Combos arriba de la grilla. `valores`: lista fija (valor: texto). `tabla`: los valores de una tabla (`valor`, `texto`); se puede dejar vacío (= todos). `default`: el valor inicial; `usuario` = el modelo de almacenes del usuario (SecUser). Lo que elige cada usuario queda guardado. |
| `columnas` | Las columnas, en orden: campos de la TRN y columnas del cálculo. El usuario después las oculta o reordena con ⚙. |
| `titulos`, `anchos` | El título y el ancho de cada columna. |
| `procesos` | Columnas que se ven solo si el usuario tiene habilitados esos procesos (GetUsrProc). Sin el proceso, la columna no existe (tampoco en ⚙ ni en el Excel). |
| `recibe` (V73) | Una consulta de stock también puede recibir valores de quien la abre (con Volver y `subtitulo`, como las consultas sin stock). Si recibe **`AlmacenesId`**, el stock actual es solo el de ese almacén (en lugar del modelo de almacenes) y el potencial sigue siendo el del artículo. Ej. `consultas/WPStockPorAlmacenArticulo.yaml`, abierta desde `consultas/WPStockPorAlmacen.yaml` con la acción `{titulo: Consultar Stock, cns: WPStockPorAlmacenArticulo, parametros: [AlmacenesEmp, AlmacenesDiv, AlmacenesId]}`. **Necesita el motor V73.** |

**Consultas sin stock (V66k)**: sin `calculo:`, una consulta es una lista de solo lectura de la tabla de la TRN con estas claves (ej. `consultas/ExtraWWDetAsientosDetalle.yaml`, el Detalle de Imputaciones de un asiento):

| Clave | Qué es |
|---|---|
| `recibe` | Lo que recibe de quien la abre (en la dirección: `cns/x?AsientosId=25`): `{nombre, sql, obligatorio}`. `sql` es la condición que agrega, con `?` en el lugar del valor; la tabla de la TRN es `t0` (ej. `t0.DetAsientosId IN (SELECT ... WHERE ... = ?)`). Sin un `obligatorio`, la consulta no se abre. Con lo recibido aparece el botón **Volver**. |
| `join` | Los `LEFT JOIN` (SQL) que usan las `columnasSql` (alias propios, que no empiecen con `t` + número). |
| `columnasSql` | Columnas calculadas en la misma consulta: `{nombre, titulo, tipo: texto/numero/fecha, formato, sql}`. Se filtran (texto: contiene; número: operadores; fecha: desde-hasta) y se ordenan como las demás, y van al Excel y al PDF. |
| `enlaces` | Vínculo de una columna al comprobante, según el renglón: `{DADNumero: {clave: DADCbteId, trn: [{si: "DADCPI = 'C' and DADClaseCbte = 'REC'", trn: COBR}, ...]}}`: el primer `si` que se cumple decide la TRN, que se abre en Visualizar. |
| `subtitulo` | Un texto al lado del Volver (y en el título del PDF): `{sql: "SELECT ... WHERE AsientosId = ?", parametros: [AsientosId]}`. |
| `ocultas` | Campos que se traen sin mostrarse (las claves de los `enlaces` ya van solas). |

Se abre desde una TRN con una lupa `ver:` (6.1.5) o una acción `cns:` (9.2). **Necesita el motor V66k.**

**V66l: variables, parámetros fecha, condición, colores, acciones, botones y cabecera** (ej. `consultas/ExtraWWEntidadesCtaCte.yaml`, Cuentas Corrientes, y `consultas/WWMovCtaCte.yaml`, sus movimientos). **Necesita el motor V66l.**

| Clave | Qué es |
|---|---|
| `&Variable` en el SQL | En `join`, `columnasSql`, `condicion`, `recibe` y `cabecera` se puede escribir `&Nombre`: un parámetro de arriba (`&Fecha`, `&CondicionCtaCte`), lo recibido (`&EntidadesPK`), el contexto del usuario (`&Empresa`, `&Division`, `&EmpStk`, `&DivStk`, `&EmpLst`, `&DivLst`, `&EmpCst`, `&DivCst`, `&SecUserId`, `&SecUserName`), `&Today` o una de `variables:`. Sin valor va NULL (`&ModeloCbtesId IS NULL`). Van como variables de la sesión de MySQL, así que se pueden usar en cualquier parte del SQL. |
| `$Columna` | En el SQL, la expresión de otra columna SQL: `{nombre: Saldo, sql: "($Debitos - $Creditos)"}`, `condicion: "... $Saldo <> 0"`. |
| `variables` | `{Sistema: {paramGlb: 150, tipo: numero}, ClaseClienteDebe: {paramGlb: 177}}`: un parámetro de ParamGlb (tipo `alfa`, `numero` o `boolean`, como GetParamGlbValor...) o `{valor: x}`. V82g: `{proceso: 74}` = si el proceso está habilitado para el usuario (GetUsrProc), verdadero o falso (ej. `UsoGlobalDePartidas` en `ExtraWWPartidasConsultaStock`). Se leen al abrir la consulta. |
| `parametros` (V66l) | `tipo: fecha` (un campo fecha; `default: hoy`; si la consulta recibe un valor con el mismo nombre, arranca en ese); `tabla:` con `donde:` (condición SQL con `&Variables`, ej. `"ModeloCbtesCPI = &ModeloCbtesCPI"`: el combo se vuelve a llenar al cambiar el otro); `vacio: true` (se puede dejar sin elegir, "Todos"); `recordar: false` (no se guarda lo que elige cada usuario; ej. la fecha, que arranca siempre en hoy). |
| `condicion` | Una condición más del WHERE (con `&Variables` y `$Columna`). Filtra en la base: el conteo y las páginas quedan bien. |
| `columnasSql` `oculta: true` | Se trae en cada renglón (para `si:`, `estilos`, vínculos y acciones) sin ser columna. |
| `estilos` | El color de una columna (o de un dato de la cabecera) según el renglón: `{Saldo: [{si: "EntidadesTipoEntidad = 1 and Saldo > 0", clase: "&ClaseClienteDebe"}, {si: "Saldo = 0", clase: AttributeBoldBlack}]}`. Va la primera regla que se cumple (sin `si`: siempre). `clase`: una clase del tema de GeneXus (`AttributeBoldRed`, `AttributeTitleGreen`...) o `&Variable` con la clase (ej. la de un parámetro de ParamGlb); el color, fondo y negrita salen de `resources/clases_gx.yaml` (lo genera `herramientas/gx2clases.py` desde la KB). `estilo`: CSS (`"color:red"`). Una regla sin clase ni estilo deja la celda sin color. |
| `enlaces` a otra consulta | `{Cheques: {cns: ChequesEntidad, parametros: [EntidadesPK, EntidadesTipoEntidad], si: "Cheques <> 0"}}`: el valor abre otra consulta con lo que recibe (sus `recibe:`, en orden). |
| `acciones` | Las del renglón, como en un WorkWith (9.2): `{titulo, icono, si, cns / panel / trn / proc: Nombre, modo (trn: Display / Update), parametros, confirmar}`. Parámetros: campos del renglón (o de la cabecera), `&Variables` o constantes. La que no corresponde va en gris; las de igual título e ícono son una con variantes (ej. "Ingresar Cobranza o Pago": `panel: COBRAltaWbp` si es cliente, `panel: PAGOAltaWbp` si es proveedor). Un `proc` muestra sus mensajes, abre el PDF que arme en la misma ventana o baja el Excel (`&ExcelDocument`). |
| `botones` | Arriba de la lista, igual que `acciones`; su `si` y sus parámetros usan los datos de la cabecera (ej. `si: "EntidadesTipoEntidad = 1"`, `si: "SecIsAuthByFunctionalityKey.Udp('WPCtasCtes')"`). |
| `cabecera` | Datos arriba de la lista: `{sql: "SELECT ... WHERE e.EntidadesPK = &EntidadesPK", campos: [{nombre, etiqueta, tipo: texto/numero/fecha, formato, trn + clave (vínculo que la abre en Visualizar), mail: true}]}`. Un renglón; se actualiza al volver de una acción o al cambiar un parámetro. |
| `orden` | El orden inicial de la lista (si no, el de la TRN): `orden: [ChequesFechaCheque, ChequesNumeroInterno]`. |

En el OPTIMIST Designer se editan las columnas, anchos, títulos, columnas SQL (con `oculta`), lo recibido (con o sin SQL), los vínculos (a una TRN o a otra consulta), los parámetros (con tipo, donde, vacío y recordar) y el subtítulo; `variables`, `condicion`, `estilos`, `acciones`, `botones`, `cabecera` y `orden` se escriben en el YAML (el Designer las deja como están).

**Anchos de columna**: `anchos: {Columna: 80px}`. Una columna con ancho queda fija en ese ancho (V66k3). Una columna sin ancho toma el ancho automático, que nunca baja de unos 190 px porque cuenta la caja "Filtrar..." del título (no los datos): para columnas angostas (CPI, clase, tipo) hay que poner el ancho. Las de stock sin ancho van en 130 px.

**Con el OPTIMIST Designer (V66k4)**: con "Abrir carpeta" (la del proyecto) la lista de arriba muestra el grupo **Consultas** (lee `src/main/resources/consultas`). También se abre una suelta con "Abrir archivo".
- **Árbol**: Columnas (en el orden en que se ven, con su título y ancho), Columnas SQL, Recibe, Vínculos, Parámetros y Subtítulo. Clic: sus propiedades a la derecha.
- **Centro**: la grilla dibujada con los anchos reales (la columna de Acciones, 90 px, incluida). **El ancho se cambia arrastrando el borde derecho del título**; doble clic en el borde la vuelve a automática. **El orden, arrastrando el título** sobre otra columna. Rayada = ancho automático. Arriba, el **ancho total** contra el **ancho útil de la pantalla** (1300 px de entrada; cambialo al de tu monitor: queda guardado) y una línea vertical que marca dónde empieza la barra horizontal. "+ Agregar columna…": los campos de la TRN, las columnas SQL y (con `calculo: stock`) las de stock que todavía no están.
- **Propiedades**: de la consulta (título, plural, TRN, join, "Todas automáticas", "+ Columna SQL / Recibe / Parámetro / Subtítulo"); de una columna (título —vacío = el de la TRN o el de la columna SQL—, ancho en px, ◀ ▶, quitar, procesos, vínculo); de una columna SQL (nombre —renombrarla la cambia en columnas, títulos, anchos y vínculos—, título, tipo, formato y SQL); de lo recibido, de cada vínculo (clave y casos si / TRN, en orden), de cada parámetro y del subtítulo.
- **Guardar** (Ctrl+S) reescribe solo lo que cambió (cambiar un ancho cambia solo ese número; los comentarios quedan) y deja `<Nombre>.yaml.bak` la primera vez. Antes de grabar valida (nombres, anchos `80px`, SQL vacíos, vínculos sin TRN) y avisa si la grilla no entra en el ancho útil. Deshacer / Rehacer como en las TRN.
- No necesita motor nuevo: graba las mismas claves que ya lee el motor V66k3. Refrescá la consulta en OPTIMIST para ver el cambio.

**Columnas del cálculo `stock`** (bultos y cantidad de cada una): `ArticulosAlmacenesBultStk` / `CantStk` (stock actual), `...BultDisp` / `CantDisp` (disponible = actual − pedidos − materia prima de OTR), `...BultPot` / `CantPot` (potencial = compras + OTR producto terminado − pedidos − materia prima de OTR), `...BultTot` / `CantTot` (total = actual + potencial), `...BultPendEntrega` / `CantPendEntrega` (facturas no entregadas de pedidos pendientes de entrega). Se ven en verde si son positivas y en rojo si son negativas; se pueden filtrar (=, <, entre...) y ordenar. Parámetros que usa: `ConsultaStock` y `CondicionStock` (la condición sobre el stock elegido, en bultos o cantidad, como GeneXus) y `ModeloAlmacenesId` (solo para el stock actual). Procesos (V55): 156 (ConsultaStockPorBultos) calcula y muestra los bultos y 157 (ConsultaStockPorCantidad) muestra la cantidad; sin ninguno de los dos, se muestra la cantidad. Se ponen en `procesos:` de cada columna (Bult.* → 156, Cant.* → 157). 107: el pendiente de entrega. Las reglas de cada fuente (estados, empresas del grupo de stock, itemizados, fórmulas, IEP, TECNOCOM) están en `OptimistStock.java` y en el CAMBIOS de la V52.

**Lupas (V54)**: con `calculo: stock`, cada columna de stock lleva una lupa al lado del número (no se configura): stock → por almacén y sus movimientos; disponible, potencial y total → los comprobantes que forman el número (con el plan de entregas); pendiente de entrega → las facturas por pedido y el resumen por canal y día. Las ventanas salen de `OptimistStock` con las mismas reglas que las columnas (su total es el número de la celda) y abren los comprobantes en modo ver. La consulta se conserva durante la sesión (`@UIScope`): al volver de un comprobante queda como estaba. Las ventanas muestran bultos y/o cantidad según el 156 / 157, y terminan con la línea TOTAL (SALDO en los movimientos) en negrita, más grande y en verde, rojo o azul según el signo.

**Cómo se pide a la base**: con la condición en "Todo" y sin filtrar ni ordenar por una columna calculada, se leen los artículos de la página y después el stock solo de esos (dos consultas chicas). Con una condición, o filtrando u ordenando por el stock, el stock de todos los artículos sale de una consulta (una pasada por cada fuente, agrupada por artículo) unida a la de artículos.

## 22. Vistas (`vistas/*.yaml`, V57)

Una vista es la lista (WorkWith) de una TRN con otra configuración, como los "ExtraWW" de GeneXus (ExtraWWPEDAutorizar, ExtraWWPEDDespacho...). El formulario que se abre desde la lista es el de la TRN. La ruta es `trn/<vista en minúscula>`: la opción del menú de GeneXus que llama a ese programa la encuentra sola (en `menu.yaml`: `{titulo: ..., trn: extrawwpedautorizar}`).

```yaml
vista: ExtraWWPEDAutorizar        # nombre (el del web panel de GeneXus)
trn: PED                          # la TRN de la lista
titulo: Pedidos Pendientes de Autorización
workwith:                         # cada clave reemplaza a la de workwith: de la TRN; las que no se ponen, se heredan
  nuevo: false                    # sin "+ Nuevo"
  columnas: [PEDId, "PEDNumero: NroPED", ...]
  filtro: {PEDEmpresa: "&Empresa", PEDDivision: "&Division", PEDStatus: "P"}
  condicion: |                    # una condición más, en la consulta a la base
    (&AutorizarPorOpciones = false or (PEDPendLM = true and PEDPendLMAutorizado = false and &SecUserAutLM = true) or ...)
    and (&ConceptosPorUsuario = false or exists (select 1 from SecUserConceptos uc where uc.SecUserId = &SecUserId and ...))
  acciones:                       # como en la TRN (9.2): inicio, modificar, visualizar, borrar, renglon
    inicio: |
      ...                         # las &Variables que usan la condicion, las acciones y los botones
  botones:                        # arriba de la lista (no son de un renglón): proc, panel o trn, con si y confirmar
    - {titulo: Reautorizar, icono: REFRESH, si: "&PuedeReautorizar", proc: PEDReautorizar, confirmar: "¿...?"}
```

**`condicion`**: se escribe como en GeneXus y se pasa a SQL: los campos guardados de la cabecera de la TRN van con su columna; las `&Variables`, del `inicio:` de las acciones; `true` / `false` como 1 / 0. El resto queda igual, así que se pueden usar `exists (select ... )` y subconsultas: un nombre después de un punto (`uc.SecUserId`) no se toma como campo de la TRN. Lo que está entre comillas simples no se toca. Como va en la consulta, la lista pagina en la base (GeneXus controlaba estas condiciones renglón por renglón, con `Load` en el `Grid.Load`). Con `condicion`, la lista siempre pide a la base solo la página visible.

**Permisos de las acciones** (el `CheckSecurityForActions` del WorkWithPlus): en el `inicio:`, `&PuedeX = SecIsAuthByFunctionalityKey.Udp("Clave")`, y la condición de la acción empieza con `&PuedeX and ...`. Una acción cuya condición pide una variable que da falso no ocupa lugar en el renglón.

**Botones** (`workwith.botones`): los botones de arriba de la lista que no son de un renglón (en GeneXus, los del Action Group de la grilla). Mismo formato que las acciones del renglón; su `si:` solo puede usar `&Variables`. Después de un procedimiento la lista se actualiza.

**Renglones tildados** (`porRenglon: true`, V59): un botón con `porRenglon: true` (solo con `proc:`) pone un tilde en cada renglón de la lista y corre el procedimiento para cada renglón tildado, con sus `parametros` de ese renglón (el For Each Line de GeneXus). Su `si:` puede usar campos del renglón: se controla en cada uno con el registro como está en la base (el que ya no corresponde se saltea y se avisa). Pregunta antes, con la cantidad, y al final muestra un solo aviso. Ej. (Comprobantes Pendientes de Autorización):

```yaml
  botones:
    - {titulo: Autorizar seleccionados, icono: CHECK, porRenglon: true, si: "CPBCStatus = 'N'", proc: CPBCAutorizar, parametros: [CPBCId], confirmar: "¿Autoriza los comprobantes tildados?"}
```

## 23. Extensiones de la aplicación (`OptimistExtension`, E3)

El motor no conoce ninguna aplicación: no nombra tablas, procedimientos, clientes ni comprobantes de nadie. Lo que una aplicación
necesita en Java (su contexto de usuario, sus procedimientos nativos, un cálculo propio de una consulta) lo engancha con una
**extensión**: una clase que implementa `ar.com.datafix.optimist.OptimistExtension` y se anota en

```
src/main/resources/META-INF/services/ar.com.datafix.optimist.OptimistExtension
```

(un renglón por clase, con el nombre completo; es el `ServiceLoader` estándar de Java). El motor las carga al arrancar y lo anota en
el log: `[OPTIMIST] Extensiones de la aplicación: [...]`. Si una extensión anotada no se puede cargar, o falla al registrar sus
procedimientos, el sistema **no arranca** (no queda andando a medias). Todo tiene valor por defecto, así que se implementa solo lo que hace falta.

| Método | Para qué | OPTIMIST ERP |
|---|---|---|
| `nombreSistema()` | nombre si el optimist.cfg no dice `sistema.nombre` | "OPTIMIST ERP" |
| `registrarProcedimientos()` | procedimientos en Java (`OptimistProcedimiento.registrarNativo`) | Logueo, AFIP, precios, stock, OPTIMIST SQL, QR |
| `alIniciarSesion(cn, usuario, ctx)` | agregar variables al contexto (el WWPContext); un `SQLException` corta el ingreso con su mensaje | empresa, división, EmpStk…, menú por permisos |
| `validarIngreso(ctx, usuario)` | mensaje si el usuario no puede entrar, o null | sin empresa asignada (salvo Administrator) |
| `variablesContexto()` | las `&Variables` de contexto que valen en los YAML | `&Empresa`, `&Division`, `&EmpStk`… (9.1) |
| `claveUsuario()` | variables que, con el usuario, forman la clave de lo que se recuerda por usuario | Empresa, Division, EmpLst, DivLst |
| `calculosConsulta()` | consultas con `calculo: nombre` (sección 21) | `stock` |
| `consultaOcultasSiempre()` | columnas que toda consulta trae aunque no se vean | ArticulosId, ArticulosDescrip |
| `procesosConsulta()` / `ajustarProcesosConsulta()` | procesos (GetUsrProc) de todas las consultas | 156, 157, 107, 78 |
| `procesoHabilitado(cn, n)` | `{proceso: N}` de las TRN y consultas: si el usuario tiene el proceso (null = la aplicación no maneja procesos) | GetUsrProc |
| `parametroGlobal(cn, n, tipo)` | variables `paramGlb: N` de las consultas | GetParamGlbValor… |
| `decimalesPrecios()` | de dónde salen los decimales de `calculo:` si el YAML no dice `decimales` | ParamGlb 30 |
| `monedas(cn)` | monedas del filtro de los totales (V77) | tabla Monedas |
| `textoSegun(col, valor)` | texto de la columna `segun` de un código repetido | Cliente / Proveedor |
| `variablesInstalacion()` / `patronesInstalacion()` | &variables "de instalación" de las acciones (V65o) | `&instalacion` |
| `objetosMensajes()` | objetos de los mensajes que no se llaman como su TRN | MSTK → MovStock |
| `precalentarTrn()` | TRN que se precalientan si el cfg no dice `application.precalentarTrn` | PRS, PED, CPBC… |

### 23.1 Una aplicación sin Java propio (ej. Factory)

Solo YAML: `trn/`, `menu.yaml`, `dominios.yaml` (y si hace falta `wbp/`, `proc/`, `consultas/`, `impresiones/`), un `pom.xml`
igual al del ERP (compila `../engine/src/main/java` y sus recursos) y su `optimist.cfg` (`sistema.nombre`, y `sistema.postits=false`
si la base no tiene la tabla PostIts). El login usa `SecUser` (SecUserName / SecUserPassword) y el menú sale completo del `menu.yaml`.
Las tablas de mensajes y PostIts son opcionales: sin ellas, la campanita queda sin número y no hay PostIts.

### Historial
- **E3**: extensiones de la aplicación (`OptimistExtension`, 23); el contexto de empresa y división pasa a ser de OPTIMIST ERP (9.1). No cambia ningún YAML.
- **V82i**: PostIts de la pantalla (botón PostIt a la derecha de los botones de cada pantalla: WorkWith, consultas, web panels; en las TRN, el del registro también va a la derecha de los botones; la principal no lleva); cada usuario ve solo los suyos. Tamaño inicial de todos los PostIts: 380 x 320. No cambia ningún YAML.
- **V69**: PostIts en el formulario de todas las TRN y su marca en el WorkWith (tabla PostIts). No cambia ningún YAML.
- **V68**: todas las lupas por páginas (como el WorkWith), orden con clic en el título y manejo con teclado. No cambia ningún YAML.
- **V66z**: confirmación de los botones de los web panels (`confirmar:`) y % de avance automático (19); Actualización de Costos por Coeficiente.
- **V66y**: subir archivos en los web panels (`archivos:`, 19); leer un Excel con `&ExcelDocument.Open` + `Cells().Text / .Number / .Date` (20); `donde` de una lupa con varios valores (19); Actualización por Archivo de costos y precios (WPDescargaCargaCostosPrecios).
- **V82g**: variables `{proceso: N}` (GetUsrProc) y `acciones` también en las consultas de stock (`calculo: stock`): Stock por Artículo → **Consultar Partidas** (`consultas/ExtraWWPartidasConsultaStock.yaml`, las partidas del artículo con su saldo).
- **V66l**: consultas con `&Variables` y `$Columna` en el SQL, `variables:` (ParamGlb), parámetros fecha y combos dependientes, `condicion`, `estilos` (clases de GeneXus), enlaces a otra consulta, `acciones`, `botones`, `cabecera`, `orden` y columnas SQL ocultas (21). Cuentas Corrientes y sus movimientos.
- **V66k4**: consultas en el OPTIMIST Designer (anchos arrastrando el borde, orden, títulos, columnas SQL, recibe, vínculos, parámetros, subtítulo) y nota de los anchos automáticos (21). Sin cambios de motor.
- **V66k**: lupas `ver:` en el formulario (6.1.5), acciones `cns:` (9.2), consultas sin stock con `recibe` / `join` / `columnasSql` / `enlaces` / `subtitulo` (21) y botón PDF en todos los WorkWith (9). Asientos: Detalle de Imputaciones.
- **V66i**: `&Columna.Visible` en las columnas de las grillas de web panel (19).
- **V66g**: Comprobantes, "Generar Remitos" (`panel: CPBCRemitirWbp`): un web panel que en el `Start` hace el trabajo y abre la TRN (si no hay nada que corregir, ni se ve), con la grilla de stock de `CPBCAltaWbpConPedido` para corregir.
- **V66e**: Comprobantes, acción "Ingresar Cobranza" (`proc: CPBCCobrar`): ejemplo de acción de renglón que crea un registro y abre su TRN en Modificar (`COBR.Call(TrnMode.Update, &COBRId)` en el procedimiento).
- **V66d**: el For Each elige su tabla solo entre las tablas de la base (no vistas) y, en un empate, la de una TRN.
- **V66**: grillas de web panel con `resaltar` / `habilitar` / `calcular` por renglón; `WWxxx.Call()` y `Link()` en los eventos (sección 19). Excel (`&ExcelDocument`) y marca de agua (`PDFAddWatermark`) en los procedimientos (sección 20). `Insert()` / `Update()` de un Business Component, `FromJson()` y `Random()`. Designer: el orden inicial del WorkWith se edita (9).
- **V65**: la lupa con procedimiento también en la cabecera; el almacén de un renglón con artículo usa `PromptArticulosAlmacenes` (6.1.1). Las fechas sin `default` arrancan en hoy también en los renglones. X para cerrar lupas, PDF y web panels.
- **V65**: `siguiente: true` (5 y 7) y `opcional: true` en las referencias (6); CondPago con lupa de forma de pago.
- **V65**: un web panel muestra el PDF que generó su evento (sección 19); `&Page` y hoja oficio / apaisada en las impresiones (sección 20); Libro IVA Ventas (WPIvaVentas).
- **V61d**: Duplicar Fórmulas como web panel (DupComposi), sin la TRN DupComposi.
- **V61e**: un For Each sin tabla (en procedimientos y web panels) elige la tabla base como GeneXus: si varias tablas tienen los atributos, la que tiene su clave entera en ellos y la más general. Para forzar una tabla igual se puede escribir `For each Articulos`.
  En procedimientos se puede asignar un campo de un renglón de una colección o de un nivel de un BC: `&Bc.Nivel.Item(&i).Campo = valor` (también `+=`, `-=` y `.SetEmpty()`).
- **V61f**: no hace falta poner en el YAML las columnas que la TRN no usa y no admiten nulo (ej. la imagen ArticulosFoto): en el alta se graban con el vacío de GeneXus.
- **V61g**: CompararRegistros no compara los numeradores `<Trn>Last*` ni `<Nivel>Last*`.
- **V65f**: `<detalle X sinTitulo>` y, en el Designer, el título del detalle y "Mostrar el título arriba de la grilla".
- **V65d**: regla `Detalle.Visible = …` (oculta un detalle entero con su panel). COBR y PAGO: lo que se ve según el Modo de Uso del recibo.
- **V65c**: `estiloRenglon` del detalle con varias condiciones y `campos:` (sección del estilo de la línea). Alta de COBR y PAGO con web panel (`COBRAltaWbp`, `PAGOAltaWbp`).
- **V65b**: el botón Atrás (mouse o navegador) cierra la ventana de arriba (lupa, PDF, web panel) sin rearmar la pantalla; no hay nada que configurar en los YAML.
- **V65a**: CompararRegistros toma NULL igual al vacío de su tipo ('', 0, false, fecha vacía): Duplicar Artículos ya no falla por los Filtro01..10 ni por Usa Partida.
- **V61h**: `workwith.estiloRenglon` (estilo de todo el renglón del WorkWith según una condición). Composi: la fórmula por defecto en negrita.
- **V62**: reimpresión de comprobantes (STONCOR / MULTICOR); QR de AFIP (`GetQRImageAFIP`, nativo); en las impresiones, la página que sigue a un `Eject` se abre al imprimir algo (como GeneXus).
- **V62a**: CPBC sin Modificar ni Eliminar; Cancelar Comprobante con `CPBCPuedeCancelar` (ejemplo de `modificar: false` / `borrar: false` + acción de renglón con un procedimiento).
- **V61c**: la auditoría en la pantalla, en dos renglones (alta / modificación), en todas las TRN (sección 3.1).
- **V61b**: fecha vacía siempre 1000-01-01; en el alta la auditoría de modificación queda vacía (sección 3.1).
- **V61**: grillas editables en los web panels (sección 19); Duplicar Artículos como web panel (DupArticulos), sin la TRN DupArticulos.
- **V60c**: `CompararRegistros` (verifica una duplicación en la base) y el web panel Comparar Registros; Duplicar Tipos de Comprobantes verifica la copia.
- **V60b**: duplicaciones con `Clone()`: lo que no se tocó queda igual que en el original (sección 16).
- **V60**: filtro de selección múltiple en las columnas con dominio enumerado (sección 9).
- **V59f**: códigos con cero adelante entre comillas en los combos (sección 4).
- **V59e**: Duplicar Tipos de Comprobantes (web panel DuplicarTcbte, procedimiento ClonarTcbte con Business Component).
- **V59d**: dominio `Password` (campo de contraseña, sección 4); Renovar Password (web panel RenovarPassword); `Ocultar:` en `menu.yaml` (sección 10), con Favoritos y Filas por Web Panel.
- **V59c**: Comprobantes Pendientes de Impresión, paso 1 (ExtraWWCPBCImpresionFAC / REM / NCR / NDB: una vista por clase, porque las vistas no reciben parámetros; acción con `Udp` en el `si:`).
- **V59b**: Remitos Pendientes de Facturación (ExtraWWCPBCRtosPendFacturar, vista sobre CPBC con `condicion` que usa subconsultas a Tcbte y CPBCRemitos).
- **V59a**: Cambio de Número de Facturas (web panel CambioNroFactura, sección 19).
- **V59**: `porRenglon` en los botones de las vistas (renglones tildados); Comprobantes Pendientes de Autorización (WPAutorizarCPBC); lupa de partidas por el artículo del renglón (`donde` con `campo`).
- **V58**: Pedidos a Despachar (ExtraWWPEDDespacho, vista). Un procedimiento que abre una TRN con `TrnMode.Display` la abre en Visualizar.
- **V57**: vistas de un WorkWith (`vistas/*.yaml`, sección 22): `workwith.condicion`, `workwith.botones`, `workwith.nuevo: false`; Pedidos Pendientes de Autorización (ExtraWWPEDAutorizar).
- **V55**: bultos y cantidad del stock según los procesos 156 / 157 en la consulta, sus lupas y las TRN (PRS, PED, CPBC, CMP, CPBP, OTR: variables `&StockPorBultos` / `&StockPorCantidad` en `inicio:` y reglas `.Visible`); `procesos:` en la lupa armada por un procedimiento (6.1.1); línea TOTAL de las ventanas del stock.
- **V54**: lupas del stock en la consulta (sección 21).
- **V52**: consultas (`consultas/*.yaml`, sección 21) con el cálculo `stock`; Trabajar con Stock de Artículos (ExtraWWArticulosCnsStock).
- **V49**: impresiones (`impresiones/*.yaml` + `print` en los procedimientos, sección 20); PDF sin librerías; `gx2prn.py`; Imprimir Presupuesto (formato genérico, PRSPrnGenericoCantidad).
- **V48f**: Designer: al cambiar la clase de un campo se borran los `negrita: false` / `italica: false` viejos de su `estilo:`; aviso del estilo propio del campo con "Sacar estilo propio" (5.2 y sección del Designer). El YAML no cambia.
- **V48** (en preparación): web panels por YAML (`wbp/*.yaml`, sección 19) con los eventos de GeneXus interpretados; `workwith.alta`; PRSAltaWbp (alta de presupuestos); `gx2wbp.py`; acciones del renglón según condiciones (`workwith.acciones`, 9.2, `acciones_gx.py`).
- **V47** (en preparación): código en lugar de la clave interna (`codigos:` en `dominios.yaml`, sección 6.2); `donde` que depende de un campo de la TRN (el CPI, 6.1); el color del estilo también en el valor de los campos de solo lectura.
- **V46**: `tamaño:` relativo a la letra del usuario (`"+4px"`, `"-2px"`, `"125%"`; `"20px"` fijo), en el motor y en el Designer; menú horizontal opcional (Apariencia → Menú).
- **V45**: clases de campo (`clases:` en `dominios.yaml` o en la TRN, `clase:` en el campo; `Cabecera` y `Detalle` se aplican solas); `letra:` en el estilo (manda sobre la del usuario); colores `"30,100,180"`; el estilo también en las líneas de los detalles; Designer con clase, letra e ícono propio.
- **V44**: las reglas `.Visible` de la cabecera ocultan también la etiqueta, y un renglón oculto entero no deja hueco; cliente ocasional en PRS, PED y CPBC.
- **V43**: filtro fijo del WorkWith con comparaciones (`">0"`), editable en el Designer; condiciones fijas de GeneXus en 21 TRN; columnas nuevas del YAML junto a su vecina aunque el usuario tenga su orden guardado.
- **V42**: fórmulas redundantes de GeneXus (`isRedundant`) grabadas como en GeneXus (136 en 14 TRN); Razón Social, CUIT, Nro. Documento, Total y Usuario ML en los WorkWith de PRS, PED y CPBC.
- **V41**: `workwith.ocultas` (campos que se traen pero no se ven), también en el Designer.
- **V40**: `insignia:` (status en pastilla de color) en el WorkWith, el formulario y el Designer; PRS, PED (también el status logístico) y CPBC con los colores de OPTIMIST.
- **V39**: `<detalle X completo>` (grilla a todo el ancho) y `alinear=` en detalles y paneles; también en el Designer.
- **V38**: reglas como Excel (solo las que dependen de lo que cambió); procedimientos de fórmulas recordados.
- **V37**: edición inmediata (lecturas recordadas mientras el formulario está abierto, una sola conexión).
- **V36**: cálculo comercial en Java (`calculo:`) para PRS y PED; densidad Supercompacta (grilla y formularios); login siempre con letra blanca.
- **V35** (en preparación): reglas de GeneXus (`reglas:`) y evento Start (`inicio:`) interpretados por el motor; 122 TRN con sus reglas; 653 procedimientos; densidad de los formularios; anchos de grilla más ajustados.
- **V34**: fórmulas visibles y en vivo (totales, `find`, procedimientos, otras fórmulas); procedimientos en `proc/*.proc` interpretados por el motor; `parchar_formulas.py` completa los YAML; `gx2proc.py` traduce procedimientos de la KB.
- **V33**: estilo por campo (`estilo:` negrita, itálica, tamaño, color); paneles que se abren y cierran (`<panel>`), renglones con bloques uno al lado del otro (`<renglon>`), y todo se muestra en el orden escrito; OPTIMIST Designer (programa aparte, con órdenes por voz).
- **V32**: anchos de columna del WorkWith según el dato (`workwith.anchos`), texto en varios renglones, renglones pares y renglón trabajado marcados, grilla con todos los renglones de la página; barra de desplazamiento en pantallas bajas; botones de los formularios siempre a la izquierda; dominios propios de GeneXus (Address, Html, Time, Geolocation) con su tipo real.
- **V31**: contexto del usuario (WWPContext): empresa/división al iniciar sesión, `default: "&Empresa"` y `workwith.filtro`.
- **V30**: TRN de dos niveles generadas; numerador compartido entre detalles; WorkWith con las columnas de GeneXus; generador: referencias encadenadas, subtipos en varios grupos, solapas anidadas.
- **V29**: WorkWith: orden sobre todas las filas, columnas por usuario (mostrar/ocultar/ordenar) y títulos propios; preferencias del usuario en la carpeta del cliente; menú de ancho ajustable; etiquetas de tabla ajustadas al texto; validación de YAML al generar el WAR.
- **V28**: pantallas responsivas (celular: tablas en una columna, menú encima); el motor guarda las TRN en memoria y solo relee un YAML cuando cambia; configuración del cliente por el nombre del WAR (`/usr/optimist/<Cliente>/optimist.cfg`, sin XML); generador en tanda (`gx2yaml_lote.py`), combos del atributo y valor inicial; menú armado desde la tabla Menu de GeneXus.
- **V27**: detalles ocultos (`oculto: true`); lectura de YAML en desarrollo sin `addResources` (que rompía los estilos: formulario sobre el menú, botones a la derecha).
- **V26**: exportación a Excel desde el WorkWith (con los filtros aplicados); referencias con condición fija (`donde`), validadas al grabar; corrección del `pom.xml` (faltaba `addResources` en la V24/V25).
- **V25**: tilde ✓ para elegir con un toque en la lupa; recuadro del menú ceñido al texto.
- **V24**: campos multirenglón (automático desde 512 caracteres, `renglones: N`); lupa en referencias de clave compuesta, filtrada por el padre; renglones con todos sus campos ocultos no ocupan lugar; menú acordeón con la pantalla actual recuadrada; en desarrollo los YAML se toman sin compilar; YAML y guía en GitHub.
- **V23 (cont.)**: fórmulas redundantes (`guardar: true`) calculadas al grabar; el menú recuerda las carpetas abiertas.
- **V23**: encabezado configurable, Primera/Última en el WorkWith, filas de detalle sin tocar se ignoran (aunque tengan valores por defecto), Monedas/Zonas/Países generadas desde la KB con el motor, menú arranca cerrado.
- **V22 (cont.)**: Monedas, Zonas Geográficas y Países/Provincias generadas desde la KB con el motor (se eliminó el Java propio de cada una). Menú arranca cerrado.
- **V22**: varios detalles, referencias con buscador, autonumerado, combos, auditoría, WorkWith con descripciones, pantalla como texto con solapas y tablas, generador desde la KB.
- **V21**: motor de TRN por YAML (1 y 2 niveles), menú por `menu.yaml`.
