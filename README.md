# OPTIMIST Engine

Motor de aplicaciones por YAML de DATAFIX SRL: transacciones (TRN), WorkWith, lupas, web panels, procedimientos en lenguaje
GeneXus, consultas e impresiones, sobre Java 25 + Vaadin + Spring Boot. Incluye **OPTIMIST Designer** (diseñador visual) y los
convertidores de GeneXus a YAML.

| Carpeta / archivo | Qué es |
|---|---|
| `src/main/java` | El motor. |
| `herramientas/disenador/OPTIMIST-Designer.html` | OPTIMIST Designer (se abre en Chrome o Edge). |
| `herramientas/gx2*.py` | Convertidores de la KB GeneXus a los YAML del motor (ver `herramientas/LEEME.md`). |
| `GUIA_CONFIGURACION_TRN.md` | Cómo se escriben los YAML. |
| `CAMBIOS.md` | Cambios del motor. |

## Cómo se usa (etapa 1)

Cada aplicación (por ejemplo `optimist-erp`) vive en su propia carpeta **al lado** de `engine` y su `pom.xml` compila también
`../engine/src/main/java`:

```
OPTIMIST\
  engine\          <- este repositorio
  optimist-erp\    <- OPTIMIST ERP
  factory\         <- Factory
```
