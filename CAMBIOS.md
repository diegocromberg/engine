# OPTIMIST Engine — Cambios

Changelog consolidado del motor. Lo anterior a la separación (V1 a V84) está en `optimist-erp/CAMBIOS.md`.

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
