# Herramientas de OPTIMIST Engine

Convertidores de GeneXus a los YAML del motor, OPTIMIST Designer (`disenador/`) y el chequeo de compilación sin Maven.

**Dónde escriben (desde la separación de OPTIMIST Engine, 28/10/2026):** las herramientas viven en `engine/herramientas`, y los
YAML que generan son de cada aplicación. En los ejemplos de abajo, `..\src\main\resources\...` es la carpeta de recursos de la
aplicación: para OPTIMIST ERP, desde `engine\herramientas`, es `..\..\optimist-erp\src\main\resources\...`.

Las pruebas con datos del ERP (`prueba_mariadb`, `prueba_stock`, `prueba_afip`, `barrido`) quedaron en `optimist-erp/herramientas`.

# gx2yaml.py — de la KB GeneXus al YAML del motor

Requisito: Python 3.9+ (sin librerías extra).

```
python3 gx2yaml.py D:\ruta\OptimistU12NEW.xml Articulos --solapas 1,2,4,6,7,10 --salida ..\src\main\resources\trn
```

- `xml`: export completo de la KB (el de 200 MB). Se lee en streaming: tarda ~6 segundos.
- `--solapas`: números de solapa del web form a incluir (1 = la primera). Sin este parámetro, todas.
- `--workwith ArticulosId,ArticulosDescrip,TipoArtsDescrip`: columnas del WorkWith (propias o inferidas). Si no se pasa, se conservan las que ya tenía el YAML.
- `--cache archivo.json`: guarda la KB procesada para que la siguiente corrida de la MISMA TRN sea instantánea.

Qué saca de la KB:
- Tipos reales de cada atributo (dominio, subtipo, longitud, decimales) y valores de los dominios enumerados (combos).
- Qué atributos se guardan en la tabla y cuáles se infieren de otra: arma las `referencias` (FK) incluso con subtipos.
- Tabla física de cada nivel (por su clave), claves, nulables y autonumerados.
- Solapas y qué campos / grillas van en cada una (web form); títulos de columnas de las grillas.
- **Renglones** de cada solapa copiados del web form: cada fila de las tablas de GeneXus es un renglón, y las tablas puestas lado a lado se combinan renglón por renglón (como se ven en GeneXus).
- Reglas: NoAccept → `soloLectura`, Default con constante → `default`, `Error(...) if X.IsEmpty()` → `obligatorio` + `mensaje`, Serial → `numerador`, auditoría Create/LastModified.
- Todo lo que no sabe traducir queda al final del YAML como comentario `PENDIENTE`.

Los campos propios que no están en las solapas elegidas se incluyen `oculto: true`: se preservan al modificar y se graban vacíos (o con su default) en el alta.

## V28: varias TRN de una vez (gx2yaml_lote.py)

Lee la KB UNA sola vez y genera muchas TRN:

```
python3 gx2yaml_lote.py D:\ruta\OptimistU12NEW.xml --un-nivel --salida ..\src\main\resources\trn --cache kb_todas.json --saltear Articulos,Monedas,Pais,ZonasGeograficas
python3 gx2yaml_lote.py D:\ruta\OptimistU12NEW.xml --trns Vendedores,Bancos --salida ..\src\main\resources\trn --cache kb_todas.json
```

- `--un-nivel`: todas las TRN de un solo nivel. `--trns A,B,C`: las que se indiquen.
- `--saltear A,B`: no las toca. `--no-pisar`: no regenera las que ya tienen YAML.
- Deja `_informe_lote.txt` en la carpeta de salida (generadas, salteadas, con error).
- El caché (`kb_todas.json`) es distinto del de `gx2yaml.py` (versión 4, guarda todas las TRN).


## V48: web panels (gx2wbp.py)

V49h: los web panels también se editan en el OPTIMIST Designer (`disenador/OPTIMIST-Designer.html`): aparecen en la lista, aparte de las TRN.
V66k4: las consultas (`consultas/`) también: anchos arrastrando el borde de cada columna, orden, títulos, columnas SQL y vínculos (guía, sección 21).


```
python3 gx2wbp.py D:\ruta\OptimistU12NEW.xml PRSAltaWbp [OtroWbp ...] --salida ..\src\main\resources\wbp
```

Escribe `wbp/<Nombre>.yaml` (ver la guía, sección 19): variables del web form con sus tipos y combos, renglones, Prompt (lupa),
botones y los eventos GeneXus con los enumerados reemplazados. Lo que no puede deducir queda como `# REVISAR` arriba del YAML.

## gx2prn.py (V49): impresiones
Saca el diseño de un reporte de GeneXus (los print blocks del Layout) a `impresiones/<Nombre>.yaml`, en mm:
`python3 gx2prn.py OptimistU12NEW.xml PRSPrnGenerico --salida ../src/main/resources/prn`.
La lógica del mismo reporte sale con `gx2proc.py` a `proc/<Nombre>.proc`. Ver la guía, sección 20.
V65: la hoja sale con el tamaño y la orientación del reporte de GeneXus (carta / A4 / oficio, vertical o apaisada).

## gx2dp.py (V66j): Data Providers
Traduce un Data Provider de GeneXus que devuelve una colección de un SDT a `proc/<Nombre>.proc`: un For Each por grupo
(los grupos de adentro son For Each anidados, filtrados por el de afuera) que arma la colección, devuelta en el último
parámetro (`out: &DPLista`). Se usa igual que en GeneXus: `For &SDTCPBC in DPCPBCConta.Udp(...)`.
`python3 gx2dp.py OptimistU12NEW.xml DPCPBCConta DPMovStock --salida ../src/main/resources/proc`.
Los enumerados se reemplazan por su valor. Un `where A where B` en el mismo renglón son dos condiciones (como GeneXus).

## gx2clases.py (V66l): clases del tema de GeneXus

    python3 gx2clases.py OptimistU12NEW.xml --salida ../src/main/resources/clases_gx.yaml

Saca del tema WorkWithPlusTheme el color, fondo, negrita, itálica y subrayado de cada clase (AttributeBoldRed, AttributeTitleGreen...).
El motor los usa para pintar las celdas cuya clase viene de un parámetro (ParamGlb 177 a 180) o de la consulta (`estilos:`).

## prueba_mariadb (V66j)
Correr un procedimiento contra una base MariaDB local con la estructura de las TRN. Ver `prueba_mariadb/LEEME.md`.


## V83: KB de GeneXus 17 (WorkWithPlus nuevo)
`gx2yaml.py` lee también el formulario "layout" de GeneXus 17 (filas y celdas responsive, grillas `simplegrid`). Las claves foráneas
que en GX17 se ven con un combo (ExtendedCombo) salen con su descripción al lado, así el motor arma la referencia y la lupa.
Los Blob de la cabecera salen en `archivos:` (con sus campos de nombre y extensión) y los GUID como `dominio: GUID` con
`default: "&NewGuid"`. Ver la guía, sección 5.4.
