# OPTIMIST Engine

## Quién soy y cómo trabajamos
- Soy Diego Cromberg (DATAFIX SRL). Desarrollador del ERP original en GeneXus 15 + WorkWithPlus (generador Java/Tomcat). Más de 45 años en sistemas.
- Hablame en castellano rioplatense, con tuteo. No uses "che". Respuestas directas y técnicas, sin preámbulos.
- Términos: decí "el servidor" (no endpoint), "el sistema en GeneXus" (no backend), "la aplicación" (no frontend), "la pantalla" (no UI), "la función" (no handler), "el archivo" (no file), "la tabla" (no schema), "el flujo" (no pipeline/workflow).
- Conozco VS Code hace poco: no des por sentado que sé usarlo. Si me pedís que haga algo en la terminal o en el editor, explicalo paso a paso.
- Para instalaciones, configuración o cualquier operación que tenga que hacer yo: UN paso por vez y esperá mi resultado antes del siguiente.
- No me gusta editar los programas yo mismo: los cambios de código los hacés vos.

## Reglas de Git y archivos
- Antes de modificar cualquier YAML de una aplicación (TRN, menú, dominios, web panels), avisame para que haga commit de mis cambios locales.
- Se trabaja directamente en `main` (Diego, 26/10/2026): nada de ramas aparte. Avisame cuando haya algo para bajar y probar.
- Si un cambio en un YAML necesita una versión nueva del motor, decímelo claramente.
- Los cambios de versión se documentan en UN solo changelog consolidado (no un archivo por versión).
- Con cada versión, mantené actualizada la documentación de cómo configurar los YAML de las TRN.

## El proyecto
- **OPTIMIST Engine**: el motor de OPTIMIST 4.0 (Java 25 + Vaadin + Spring Boot) que arma las aplicaciones a partir de YAML: TRN, WorkWith,
  lupas, web panels, procedimientos en lenguaje GeneXus (`.proc`), consultas, impresiones, PostIts, mensajes, calculadora, etc.
- Producto para vender a la comunidad GeneXus: licencia perpetua por pago único. Se licencia el **Designer por PC del desarrollador**;
  el **motor de ejecución es libre** (va con las aplicaciones que arme cada desarrollador).
- Contiene: `src/main/java` (motor), `herramientas/disenador` (OPTIMIST Designer, una página que corre en Chrome/Edge),
  `herramientas/gx2*.py` (convertidores de GeneXus a YAML), `GUIA_CONFIGURACION_TRN.md` (formato de los YAML).
- Aplicaciones hechas con el motor (cada una en su repositorio, en una carpeta AL LADO de `engine`):
  - `optimist-erp`: OPTIMIST ERP (la primera; su código Java propio: AFIP, stock, precios, comercial, geolocalización, OPTIMIST SQL).
  - `factory`: Factory, seguimiento de incidentes (KB GeneXus 17 + WorkWithPlus).
- **Etapa 1 (28/10/2026, hecha)**: mudanza pura. Las clases se movieron SIN cambios; el `pom.xml` de cada aplicación compila también
  `../engine/src/main/java` (build-helper). El WAR de cada cliente sale igual que antes. El motor todavía no compila solo.
- **Etapa 2 / E3 (28/10/2026, hecha)**: el motor no nombra nada del ERP. Lo propio de cada aplicación se engancha con
  `OptimistExtension` (ServiceLoader; guía 23): el ERP con `OptimistExtensionErp` y `OptimistExtensionStock`; Factory sin ninguna
  (solo YAML). El motor tiene su `pom.xml` (jar) y compila solo. **Regla**: nada de tablas, procedimientos ni clientes de una
  aplicación en el motor; si el motor necesita algo, se agrega un método a `OptimistExtension` con valor por defecto.
- **Importador de GeneXus (E2, hecho)**: en el Designer (`herramientas/disenador/importador.js` + pantalla en `disenador.src.html`;
  guía 11.2). `gx2yaml.py` es la REFERENCIA: todo cambio de criterio va primero ahí, después en `importador.js`, y las salidas se
  comparan (idénticas). Probado con GeneXus 17 + WWP (Factory). Falta validar con XML reales de GX15 (la KB del ERP), GX16/18 y
  Evolution, y el adaptador de K2BTools (cuando haya un XPZ que lo use).
- Versión de prueba (pendiente, con el licenciamiento): 15 días y hasta 10 TRN en el proyecto; lo hecho en prueba corre con cartel.
- Licencia: clave por PC (huella de la PC → archivo de licencia firmado con clave privada de DATAFIX).

## Cómo se compila
- Maven, Java 25. En la PC de Diego todo va en `D:\Mis Documentos\OPTIMIST` (antes "OPTIMIST ERP", renombrada el 28/10/2026):
  `engine`, `optimist-erp` y `factory`, una al lado de la otra.
- En el entorno de Claude no hay acceso a Maven Central: el chequeo es `python3 herramientas/chequear_java.py src/main/java` (motor solo) y `... src/main/java ../optimist-erp/src/main/java`
  (los avisos de Vaadin/Spring son falsos; comparar contra la corrida anterior) y `optimist-erp/herramientas/prueba_mariadb/compilar.sh`.
- Changelog del motor: `CAMBIOS.md` (uno solo, consolidado). La historia anterior a la separación está en `optimist-erp` (CAMBIOS.md y git).

## Reglas de diseño (son del motor: valen para toda aplicación)
- TRN: botones siempre a la izquierda. Web panels: contenido y botones centrados.
- **Botones de las barras (V85)**: todos con su fondo, como Excel, PDF y Apariencia (también PostIt y Σ); nada de botones "terciarios" sin fondo en una barra.
- **Un único criterio visual para todo el sistema**: web panels como las TRN: literal a la izquierda y campo a la derecha, todos los renglones en una sola tabla encolumnada (literales de una columna uno debajo del otro, campos también), cada campo en su columna sin invadir la siguiente, el formulario contra su margen izquierdo y centrado en la pantalla (V66o–V66r). Todo respeta la Apariencia (densidades, colores, letra). Las consultas mantienen sus parámetros y datos de arriba como barra (como los filtros del WW).
- **Secciones en los web panels (Diego, 25/10/2026)**: los campos agrupados por tema con títulos de sección (`{seccion: Título}`), como el Listado de Stock (Tipo de Listado, Artículos, Filtro por Modelos, Otras Opciones, Fechas...). Todos los web panels ya están así (V73g); los nuevos se hacen igual.
- **Lo que se acuerda para todos los programas se aplica en TODOS** (los que ya están y los que se hagan), sin que Diego lo vuelva a pedir. Se anota en esta lista.
- **Confirmación (todo proceso de un web panel)**: antes de correr, pregunta "¿Confirmás …?". El motor lo hace solo en todos los botones salvo Cancel (V66z); `confirmar: false` solo para los que no procesan (buscar, ir a, ver, leer, comparar); `confirmar: "texto"` para una pregunta propia.
- **% de avance (todo proceso)**: la ventanita "Procesando…" muestra el % (V66z: automático por el For Each principal; si el procedimiento sabe mejor el total, TotalProgreso / PorcentajeProgreso + MostrarProgreso). Un proceso que no recorre un For Each (ej. filas de un Excel) informa el suyo. **Además del %, siempre el texto de lo que se está procesando** (MostrarProgreso con código y nombre: "Procesando Cliente 100 - RAZÓN SOCIAL", "Pedido 1234 - …", "Procesando Vendedor: JUAN PEREZ Cliente …"; V72b / V72d). Que se actualice con cada registro que se recorre, no solo con los que pasan los filtros.
- Al confirmar o ejecutar una acción, validar que el registro de origen siga en su estado original (ej. dos pestañas con "Generar Pedido" del mismo presupuesto).
- Bloqueos: solo el registro principal, no sus renglones.
- **Confirmar (para todo el sistema)**: 1) valida todo; 2) si hay al menos un error, lo muestra y no actualiza NADA; 3) si no hay errores, actualiza todo en un solo commit. (Solo `on AfterComplete` va después del commit, como en GeneXus.)
- **Ventanas**: la X de cerrar siempre arriba a la derecha, como cualquier ventana de Windows (no preguntar).
- **Calculadora (V71)**: doble clic en un campo numérico editable (TRN, renglones, web panels) abre la calculadora; Copiar pasa el resultado al campo.
- **PostIts (V69 / V82i)**: en el registro (los ven todos) y en la pantalla (botón PostIt siempre a la derecha de los botones de la pantalla, también en las TRN; la pantalla principal no lleva; solo los ve el que los pegó). Nuevo: 380 x 320.
- **Mensajes (V72)**: campanita en la barra con los internos sin leer; no se borran; la Fecha de Visualización es la primera lectura y no cambia nunca.
- Los PDF se abren en la misma ventana desde donde se ejecuta el proceso, no en pestaña ni ventana aparte.
- La grilla del WorkWith no se desplaza bajo ninguna circunstancia (al aceptar con Enter, mover columnas, etc.).
- Al cerrar un registro, el WW mantiene la página donde estaba.
- Navegación estilo OPTIMIST: hipervínculos (ej. el pedido en el PRS y en el WW) abren la TRN destino en modo ver y al cerrar vuelven a la pantalla de origen. **Siempre: de A a B, al cerrar o volver de B se vuelve a A**, también cuando A es una ventana (lupa): se vuelve a la ventana, no a la pantalla de abajo (V84c, `OptimistVentanas`).
- Fila de TOTALES de cualquier lista: en negrita, con la letra del resto de los números (V84c).
- Filtros del WW: numéricos con operadores (= != < <= > >= entre); fechas con desde-hasta; en cualquier campo fecha, carga estilo GX (0410 → 04/10 del año actual).
- **Resultados de un proceso (V76)**: en otra pantalla, como el WPShowXxx de GeneXus (`resultado:` del web panel): el formulario solo con los filtros, Procesar y Volver; el resultado a todo el ancho con sus botones (PDF, Excel…) y Volver a los filtros como estaban.
- **Totales en las listas (V75)**: todos los WW y consultas llevan fila de TOTALES al pie con la suma de los importes de todo lo filtrado (dominios de `totalizar:` en dominios.yaml; `totales:` / `sinTotales:` por lista). Con importes en distintas monedas, un renglón por moneda ("Total en $", "Total en U$S"; V77: la columna ...MonedasId o `totalesMoneda:`). Las listas nuevas, igual. **V84i (Diego, 28/10/2026)**: apagados por defecto en todas las listas (ni se calculan); solo los usuarios de `totales.usuarios` del optimist.cfg ven el botón Σ (entre Apariencia y PostIt) para prenderlos, recordado por usuario y lista.
- Lupas (prompts): todas iguales. Por páginas como el WW (primera / anterior / siguiente / última, registros por página y el total; V68: con 40.000 artículos la barra no sirve), un filtro por columna como en los WW, orden con clic en el título, abre con el cursor en el filtro (Enter elige el primero; flecha abajo, a la grilla; Enter elige), ancho según sus columnas. La de clientes/proveedores: Razón Social, Código, Tipo Documento, CUIT y Nro. Documento.
- Donde los procedimientos de GeneXus rinden mal (consultas mal armadas, demasiadas lecturas), resolverlo de forma más eficiente, no copiarlo.

## Cuidar el consumo
- Leé solo los archivos que hacen falta para la tarea; no recorras todo el proyecto si no es necesario.
- Antes de un cambio grande, contame el plan en pocas líneas y esperá mi OK.
