# OPTIMIST Engine — Cambios

Changelog consolidado del motor. Lo anterior a la separación (V1 a V84) está en `optimist-erp/CAMBIOS.md`.

## E1 — Separación de OPTIMIST Engine, etapa 1 (28/10/2026)

- El motor sale de `optimist-erp` a su propio repositorio: 48 clases Java, OPTIMIST Designer, los convertidores de GeneXus
  (`gx2yaml`, `gx2yaml_lote`, `gx2wbp`, `gx2prn`, `gx2proc`, `gx2dp`, `gx2clases`, `subtipos_gx`, `acciones_gx`, `paneles_gx`,
  `parchar_*`), `chequear_java.py` y la guía `GUIA_CONFIGURACION_TRN.md`.
- **Mudanza pura**: las clases son exactamente las mismas (mismo contenido, mismo paquete `ar.com.datafix.optimist`). El WAR de
  OPTIMIST ERP se arma igual que antes, compilando `engine/src/main/java` junto con las clases propias del ERP.
- En OPTIMIST ERP quedan: AFIP, Comercial, Precios, Stock, StockDetalle, ListarStock, Geolocalización (GeoDatos / GeoView) y
  OPTIMIST SQL, más todos sus YAML, procedimientos, impresiones, ayudas y el manual.
- `chequear_java.py` acepta varias carpetas (motor + aplicación).
