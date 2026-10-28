# OPTIMIST Engine — Cambios

Changelog consolidado del motor. Lo anterior a la separación (V1 a V84) está en `optimist-erp/CAMBIOS.md`.

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
