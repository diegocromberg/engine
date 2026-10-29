# OPTIMIST Engine — Cambios

Changelog consolidado del motor. Lo anterior a la separación (V1 a V84) está en `optimist-erp/CAMBIOS.md`.

## E4e — Importador: todo el flujo de Python e informe de lo que no se migra (29/10/2026)

Diego: "el usuario tiene que enterarse de todas las cosas que no se pudieron importar… esto no puede pasar bajo ninguna
circunstancia". El importador del Designer convertía solo la TRN: no traía los procedimientos que llaman las reglas (ej. los mails
de Factory al cambiar el estado de un incidente) ni avisaba lo que quedaba afuera.

- **`herramientas/importar_kb.py`** (nuevo): todo el flujo con que se migró el ERP para las TRN pedidas (gx2yaml, fórmulas,
  paneles, reglas e inicio, acciones del WorkWith y lo que llaman: procedimientos, impresiones, Data Providers, web panels, hasta
  el final; subtipos y clases). No pisa procedimientos, web panels ni impresiones que la aplicación ya tiene.
- **El Designer lo corre con Python adentro** (Pyodide 0.27.7, en un Worker; anda sin internet y abriendo el HTML como archivo):
  importar y 👁 Ver dan exactamente lo mismo que la herramienta de Python. Pyodide va en `optimist-python.js` (18 MB, **tiene
  que estar al lado de `OPTIMIST-Designer.html`**); las herramientas, adentro del HTML (`armar.py`).
- **Informe obligatorio** al terminar (Copiar / Guardar), también en `trn/_gx/_informe_importacion.txt` y `.json`: eventos no
  migrados, permisos de WorkWithPlus que el motor todavía no aplica (por modo y por campo), reglas sin traducir, tipos no
  soportados, los `REVISAR` de los procedimientos traídos y los objetos que faltan en el export.
- **⚠ N en la lista** del importador: lo que ya se sabe que no se migra (antes de importar) o lo que quedó (después).
  Clic en el ⚠: la lista de esa TRN; botón **⚠ Ver lo que no se migra**: la de las tildadas (o todas), con Copiar.
- **Franja amarilla en el Designer** ("⚠ No hace todo lo de GeneXus: N sin migrar") en las TRN con el bloque `# ---- SIN MIGRAR`
  que el importador deja al final del YAML; clic: la lista. Se conserva al guardar desde el Designer.
- El informe lista también **lo que la aplicación ya tenía y usan las TRN** ("ya estaba en la aplicación, no se tocó"), con lo
  que tenga para revisar y el recordatorio del `optimist.cfg` si manda mails; y sigue lo que esos llaman.
- El bloque `# ---- PENDIENTE` de la TRN solo sale si hay algo (antes quedaba el encabezado vacío).
- Carpeta de la aplicación: hace falta la del proyecto (o `src/main/resources`), no solo la `trn`.
- El importador **recuerda el último archivo de GeneXus y la última carpeta de la aplicación**: al abrirlo los usa solos si el
  navegador ya dio permiso; si no, un clic en **↻ Factory.xml** / **↻ factory** (el navegador pregunta una vez).
- `gx2proc.py`: saca del código las credenciales del `&SMTP` (van al `optimist.cfg`, `mail.*`) y avisa que hay que configurarlo;
  ignora las reglas de salida de reportes. `gx2yaml.py`: `Default(X, Today())` no hace falta (la fecha ya arranca en hoy).
- Regla nueva en el `CLAUDE.md`: una herramienta de Python que migre algo de la KB va también al importador, en la misma versión.
- Probado con Factory (Incidentes, Versiones, Sistemas): el mismo resultado en Python y en el Designer (Chromium, como archivo y
  por http); el bloque SIN MIGRAR sobrevive a guardar desde el Designer.

## E4d — Mails, archivos de texto y WWPContext (29/10/2026)

- **Mails**: el `&SMTP` de los procedimientos toma servidor, puerto, usuario, clave y remitente del `optimist.cfg` (`mail.*`,
  guía 16.1). La clave sale del código fuente (en GeneXus suele estar escrita en el procedimiento).
- **Archivos de texto** (`DFWOpen`, `DFWPTxt`, `DFWPNum`, `DFWPDate`, `DFWNext`, `DFWClose`): antes daban error; una ruta sin
  carpeta va a la carpeta del cliente.
- `&WWPContext.UserId` y `.UserName` (los de WorkWithPlus) = el usuario conectado.
- `gx2proc.py` ya no marca para revisar `&SMTP`, `&MailMessage`, `&MailRecipient` ni `&ExcelDocument` (el motor los ejecuta).
- Probado con Factory contra MariaDB y un servidor de correo de prueba: al pasar un incidente a Finalizado, IncidentesUpdateLastStatus
  graba la Historia de Estados y Emailing manda el mail a los usuarios con email ("Incidente: 710 - … - Finalizado").

## E4c — Archivos en los detalles (29/10/2026)

- Los archivos (el Blob de GeneXus) también en un **detalle** (`archivos:` dentro del detalle; guía 5.4): en cada renglón,
  **👁 Ver** (en una ventana si el navegador lo muestra: PDF, imágenes, texto; Excel, Word… se bajan), **⬇ Bajar**, **⬆ Subir**
  (el nombre y la extensión se completan solos) y **✕ Quitar**. Se graba al Confirmar, en la misma transacción, también en un
  renglón nuevo con clave numerada. El contenido se lee de la base recién al pedirlo (la lista no se hace más lenta).
- El importador (y `gx2yaml.py`) arma el `archivos:` de los detalles desde la KB (ej. Incidentes de Factory: IncidentesArchivosFile).
  Antes quedaba como "tipo no soportado".
- Probado contra MariaDB: alta con archivo en un renglón numerado, cambio de archivo, quitar y leer.
- **Bajar un archivo ya no cierra el formulario** (Diego: al bajar un Excel desde un incidente, volvía al WorkWith). La descarga
  se hacía con un clic en un enlace armado al vuelo, y ese clic contaba como "clic afuera" de la ventana del formulario, que se
  cerraba. Ahora se baja con un marco oculto, sin ningún clic. Vale para todas las descargas: archivos, Excel del WorkWith (también
  desde una lupa) y el botón Descargar de los PDF.

## E4b — Importador: solapas de GeneXus 17 y encabezado elegible (29/10/2026)

- **Solapas de GeneXus 17**: el importador (y `gx2yaml.py`) solo veía la primera; ahora arma todas, como en GeneXus (ej. Incidentes
  de Factory: Datos Generales, Archivos, Historia Estados, Facturación, cada detalle en su solapa). Las KB de GeneXus 15 no cambian.
- **Encabezado del formulario**: si la descripción de la TRN es un texto largo (ej. la descripción completa de un incidente), el
  importador usa una corta (Descort, Titulo, Nombre…). En el Designer, propiedades de la transacción: además de escribirlo,
  **"＋ Agregar un campo al encabezado"** elige los campos de una lista.
- `gx2yaml.py` e `importador.js` siguen dando exactamente el mismo YAML (comparadas las TRN de Factory).
- Nueva aplicación: cada aplicación con su propia cookie de sesión (`server.servlet.session.cookie.name`), para que dos
  aplicaciones abiertas en la misma PC (ej. ERP en 18080 y Factory en 18081) no se pisen la sesión.

## E4a — Ayuda en el Designer (29/10/2026)

- Botón **❓ Ayuda** (y F1) en OPTIMIST Designer: el manual del desarrollador y la guía de los YAML en una ventana con índice y
  buscador (sin tildes ni mayúsculas; Enter, la siguiente). Abre en el tema de lo que se está haciendo: TRN (pantalla o WorkWith),
  web panel, impresión, consulta, importador ("❓ Ayuda" en su ventana) o Nueva aplicación.
- Funciona sin internet: `armar.py` mete los dos `.md` adentro del Designer (con marked, licencia MIT, para mostrarlos), así es
  siempre la documentación de esa versión del motor.

## E4 — El motor como producto: librería, sin tablas propias, Nueva aplicación (29/10/2026)

- **El motor es una librería** (`optimist-engine.jar`): `mvn install` en `engine` y las aplicaciones lo usan como dependencia
  (antes compilaban las fuentes de `../engine`). Sus estilos y el logo van dentro del jar (el build de producción los encuentra
  solo). El `pom.xml` de cada aplicación indica la clase que arranca (`OptimistApplication`, del motor).
- **Sin tablas propias**:
  - Usuarios: `src/main/resources/seguridad.yaml` de cada aplicación (tabla, columnas, clave en texto o hash md5 / sha1 / sha256;
    guía 24.1), o una extensión (`autenticar`, ej. GAM). Antes, `SecUser` fijo.
  - PostIts y mensajes: **módulos opcionales**, apagados salvo que la aplicación los pida (`OptimistExtension.modulos`) o el
    `optimist.cfg` los prenda (`sistema.postits`, `sistema.mensajes`; guía 24.2).
  - `visibleSi: X` de los YAML: `application.X=true` en el `optimist.cfg` (antes solo existía ModuloMutual, fijo en el motor).
  - El menú de emergencia (si `menu.yaml` falla) ya no trae las categorías del ERP.
  - Dónde están los YAML en desarrollo: se busca a partir de los recursos de la aplicación (antes, de las clases, que ahora vienen
    en el jar) y ya no se busca una carpeta `optimist-erp`.
- **OPTIMIST Designer: ✨ Nueva aplicación** (guía 25): arma la aplicación completa (pom, menú, dominios, usuarios, configuración de
  ejemplo, prueba de los YAML, README) desde `plantilla-aplicacion/` y ofrece importar las TRN de GeneXus ahí. Arreglo: abrir el
  importador con la carpeta `trn` ya elegida repintaba el diseñador de impresiones.
- **`MANUAL_DESARROLLADOR.md`**: el camino de punta a punta (instalar el motor, crear la aplicación, importar, arrancar, WAR,
  extensiones). Se actualiza con cada versión.
- **Necesita**: `mvn install` en `engine`; bajar también `optimist-erp` (trae `seguridad.yaml`, prende PostIts y mensajes y usa el
  jar) y `factory`.

## V86 — 29/10/2026: For con fechas
- **`For &Fecha = &FechaDesde To &FechaHasta`** recorre los días, como GeneXus (con `Step n`, de a n días; negativo, hacia atrás).
  Antes las fechas se tomaban como números y daba una sola vuelta con 0 (ej. Rendimiento de Centros Operativos, que recorre
  los días del período). Guía 16.

## V85 — 28/10/2026: botones PostIt y Σ con fondo
- El botón **PostIt** (de la pantalla y del formulario de un registro) y el **Σ** de los totales pasan a ser botones comunes, con su
  fondo, como Excel, PDF y Apariencia (antes eran "terciarios": sin fondo, parecían un texto).


## E3 — Separación, etapa 2: el motor sin nada del ERP (28/10/2026)

- **Extensiones de la aplicación** (`OptimistExtension`, guía 23): lo propio de cada aplicación se engancha por `ServiceLoader`
  (`META-INF/services/ar.com.datafix.optimist.OptimistExtension`). El motor ya no nombra tablas, procedimientos, clientes ni
  comprobantes del ERP. Una aplicación sin Java propio (Factory) no declara ninguna y anda solo con sus YAML.
- Salió del motor hacia OPTIMIST ERP: el contexto de empresa / división / stock / costos / listas y el menú por permisos
  (`OptimistContexto` ahora solo lee `SecUser`), el control "usuario sin empresa" del login, los procedimientos nativos del ERP
  (Logueo, LogueoFile, QR de AFIP) y el registro de Precios, AFIP, ListarStock y OPTIMIST SQL, el cálculo de la consulta de stock
  (`calculo: stock`), los procesos fijos de las consultas, ParamGlb, la tabla Monedas, EntidadesTipoEntidad, `&instalacion`,
  los objetos de los mensajes y la lista de TRN a precalentar.
- Entró al motor: `OptimistComercial` (el cálculo comercial `calculo:` de las TRN es del motor; los decimales por defecto los da
  la extensión), los estilos de la pantalla y el logo (`src/main/resources/META-INF/resources`), y
  `OptimistVentanas.abrirRegistro(trn, id)`.
- Si una extensión anotada no se puede cargar o falla al registrar sus procedimientos, el sistema no arranca (como antes).
- `{proceso: N}` (GetUsrProc del ERP) también va por la extensión (`procesoHabilitado`). La empresa de la barra sigue a la
  variable de contexto que indica la aplicación (`captionDe`): si un procedimiento la cambia con SetWWPContext, se ve al día.
- Build de producción de las aplicaciones (`-Pproduction`): el `pom.xml` le indica a Vaadin que los estilos están en el motor.
- `OptimistConsultaView.Calculo`: interfaz para las consultas con un cálculo propio (`calculo: nombre`).
- El nombre del sistema: `sistema.nombre` del optimist.cfg, si no el de la extensión, si no "OPTIMIST".
- **`pom.xml` propio** (jar `optimist-engine`): el motor compila solo, sin ninguna aplicación.
- Probado: el motor solo y motor + ERP compilan igual que antes; contra MariaDB, Logueo, PasarContabilidad, CtaCteImprimir,
  QR y NumeroArgentino dan lo mismo que el código anterior. Las 8 TRN de Factory se leen sin errores.
- Designer: **arreglo** del diseñador de impresiones, roto desde la E2 (el importador tenía una función con el mismo nombre
  que la que dibuja la impresión y la pisaba). Y el importador, si la aplicación tiene `src/main/resources` sin carpeta `trn`, la
  crea ahí (donde la lee el motor) y no en la raíz.
- **Necesita** bajar también `optimist-erp` (su extensión). Ningún YAML cambia.

## E2b — Importador: ventanita "Procesando…" (28/10/2026)

- Como todo proceso del motor: al leer la KB, convertir e importar se ve el % y lo que está haciendo ("Leyendo Factory.xml
  (2 de 12 MB)…", "Importando Incidentes (4 de 8)…"). Antes tardaba sin avisar nada.
- Si hay TRN tildadas y falta la carpeta de la aplicación, el pie lo dice en rojo. Si el navegador no da la carpeta elegida,
  avisa el motivo (antes no decía nada).

## E2a — Designer: formulario encolumnado (28/10/2026)

- En el diseño del formulario de las TRN (también en la vista previa del importador) y de los web panels, los literales y los campos
  quedan **encolumnados como en el motor** (V66o): en cada columna los literales tienen el ancho del más largo, así todos los campos de
  esa columna arrancan a la misma altura. Antes cada campo arrancaba donde terminaba su literal. Solo cambia cómo se ve el Designer;
  no cambia ningún YAML.

## E2 — Importador de GeneXus en el Designer (28/10/2026)

- **OPTIMIST Designer: botón "⬇ Importar de GeneXus"** (guía, 11.2). Abre el export de una KB (**XML o XPZ**, leído por partes en el
  navegador), lista sus TRN con tildes y estado (nueva / ya migrada / cambió en GeneXus / no se puede), **vista previa** del
  formulario y del WorkWith sin grabar, aviso de **dependencias** (TRN que usa y no están migradas, con opción de agregarlas),
  **confirmación fuerte** antes de pisar una ya migrada (con `.bak`), y deja `trn/_gx/<TRN>.gx.txt` (lo no traducido, para la IA
  de cada desarrollador) y `trn/_importacion.json` (checksum de GeneXus por TRN).
- `herramientas/disenador/importador.js`: el convertidor `gx2yaml.py` pasado a JavaScript. Da **exactamente el mismo YAML** que el
  Python (comparado archivo por archivo con las 15 TRN de la KB Factory, GeneXus 17). `armar.py` lo mete en el Designer.
- `gx2yaml.py` (y el importador): **GeneXus 17**
  - columnas, títulos y orden del WorkWith y columnas de la lupa (`workwith.prompt`) desde la instancia de WorkWithPlus
    (en GX17 el web panel del WW se llama `<Trn>WW`, no `WW<Trn>`, y antes salían columnas por defecto);
  - `Error(Format('WWP_RequiredAttribute','Etiqueta')) if X.IsEmpty()` → `obligatorio: true` con mensaje "Ingrese Etiqueta";
  - `X = &X if not &X.IsEmpty()` y `noaccept(X) if not &X.IsEmpty()` (WorkWithPlus recibe la clave por parámetro) ya no quedan
    como PENDIENTE.
  - Caché de la KB: versión 8 (los `--cache` viejos se vuelven a leer solos).
- Los YAML que ya están en OPTIMIST ERP no cambian (vienen de GeneXus 15: su WW sigue saliendo del web panel `WW<Trn>`).

## E1 — Separación de OPTIMIST Engine, etapa 1 (28/10/2026)

- El motor sale de `optimist-erp` a su propio repositorio: 48 clases Java, OPTIMIST Designer, los convertidores de GeneXus
  (`gx2yaml`, `gx2yaml_lote`, `gx2wbp`, `gx2prn`, `gx2proc`, `gx2dp`, `gx2clases`, `subtipos_gx`, `acciones_gx`, `paneles_gx`,
  `parchar_*`), `chequear_java.py` y la guía `GUIA_CONFIGURACION_TRN.md`.
- **Mudanza pura**: las clases son exactamente las mismas (mismo contenido, mismo paquete `ar.com.datafix.optimist`). El WAR de
  OPTIMIST ERP se arma igual que antes, compilando `engine/src/main/java` junto con las clases propias del ERP.
- En OPTIMIST ERP quedan: AFIP, Comercial, Precios, Stock, StockDetalle, ListarStock, Geolocalización (GeoDatos / GeoView) y
  OPTIMIST SQL, más todos sus YAML, procedimientos, impresiones, ayudas y el manual.
- `chequear_java.py` acepta varias carpetas (motor + aplicación).
