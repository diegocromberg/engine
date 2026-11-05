# OPTIMIST Engine — Cambios

Changelog consolidado del motor. Lo anterior a la separación (V1 a V84) está en `optimist-erp/CAMBIOS.md`.

## E6w — Lo que faltaba para dejar las TRN como GeneXus (05/11/2026)

- `X.InviteMessage = "texto"` en las reglas: el texto de ayuda dentro del campo vacío (Entidades: "Personas Físicas: Apellido, Nombre…").
- `X.Visible = …` sobre el **archivo** (Blob) de un detalle: oculta esa columna (COBR: el archivo de la forma de pago según el proceso).
- La lista de la TRN misma (no una vista) vacía las claves de la sesión que ponen sus vistas (`sesion:`, E6u), como el WWCSEventuales de
  GeneXus que hace `&WebSession.Remove(...)`.
- Procedimientos y reglas: `&Texto.ReplaceRegEx(patrón, reemplazo)` (QuitarTagsHTML de Eventos fallaba), `Dominio.Convert(&x)` de un
  enumerado (el inicio del CPBC en Modificar se cortaba ahí) y `YMDtoD` / `YmdHmsToT` con una fecha inválida devuelven la fecha vacía
  como GeneXus (antes, un error: Días de Producción no abría).
- `gx2yaml.py` y el importador del Designer: las grillas que están **debajo** del control de solapas (ej. Apertura de Caja: los niveles
  Cbte y Det) van en la primera solapa; antes el nivel entero se perdía. Las salidas de los dos siguen idénticas.
- Todo opcional / compatible: un YAML que no usa nada de esto anda igual. **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0).

## E6v — Regla Default() de GeneXus (05/11/2026)

- `Default(A, expresión)` en las reglas: el valor inicial de A en el alta (cabecera nueva o renglón nuevo), cuando sale de una
  variable del inicio o de otro campo (el código nuevo del cliente, el almacén de compras del artículo, el año de IVA según la fecha).
  Se aplica al abrir, de nuevo si cambia lo que usa la expresión y, en un renglón, al empezar a cargarlo; nunca al grabar ni al
  modificar. Un valor vacío no se asigna. Antes, el convertidor los dejaba de lado.
- `parchar_reglas.py` (y por lo tanto el importador del Designer) ya pasa esos Default a las reglas; los constantes y los del contexto
  siguen yendo a `default:` del campo.
- Opcional: una TRN sin `Default(` en sus reglas, como antes. **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0).

## E6u — Valores de la sesión desde la vista (05/11/2026)

- `sesion: {CLAVE: valor}` en una vista (`vistas/*.yaml`): al abrirla, deja esos valores en la sesión del usuario y la TRN los lee con
  `&WebSession.Get('CLAVE')`, como el programa del menú de GeneXus que los seteaba antes de abrir la lista (ej. Clientes / Proveedores
  con `TIPOENTIDAD`). Opcional: sin `sesion:`, como antes. **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0).

## E6t — Vínculo con condición en un renglón (05/11/2026)

- `enlace: [{si: "condición", trn: X, clave: Y}, ...]` en un campo de un detalle: abre la TRN de la primera opción cuya condición se
  cumple con los valores del renglón (ej. en Partidas, el comprobante es un CPBC, un CPBP, una OTR o un MovStock según su CPI y clase).
  El `enlace:` de siempre sigue igual. **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0).

## E6s — Vínculo en un renglón, renglones de la grilla, Agregar Filas y título del formulario desde las reglas (05/11/2026)

- `enlace:` en un campo de un **detalle** (de solo lectura): se ve como vínculo y abre esa TRN en Visualizar con la clave del renglón
  (la lupa "ver cheque" / "ver comprobante" de GeneXus). Antes, solo en la cabecera y en las columnas del WorkWith.
- `Detalle.Rows = n` (el `GridLevel_X.Rows` de GeneXus): los renglones vacíos del detalle al abrir y los que agrega "Agregar Filas";
  `Rows = 0`: sin renglones vacíos (el botón igual agrega 5).
- `AgregarFilas<Detalle>.Visible = false`: oculta el botón "Agregar Filas" de ese detalle (el BotonFilas de GeneXus).
- `Form.Caption = "..."` (o `<Trn>Title.Caption`): el título del formulario (ej. "Hoja de Ruta", "Clientes" / "Proveedores").
- Todo opcional: sin esas reglas, como antes. **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0).

## E6r — Títulos, anchos, solapas y paneles desde las reglas (05/11/2026)

- Para que las TRN queden como GeneXus, que en el Start cambia títulos, anchos y solapas según la instalación o VERCampo:
  - `X.Caption = "texto"` (o `X.Title`): el título del campo de la cabecera o de la columna del detalle. Vacío: queda el de siempre.
  - `X.Width = n`: el ancho (px) de la columna de un detalle, también en las filas que se agregan. 0 o vacío: el de siempre.
  - `SolapaMercadoLibre.Visible = ...` / `PanelDatosDelMovimiento.Visible = ...`: la solapa o el panel con ese título (Solapa /
    Panel + el título sin espacios, signos ni acentos).
- Todas son opcionales: sin esas reglas, todo como antes. Se usan como las demás reglas de Visible / Class (al abrir y con cada cambio).
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6q — El Save() de un Business Component corre las reglas de sus eventos, como GeneXus (04/11/2026)

- Diego: la cobranza generaba el movimiento de caja con los importes con signo en 0. En GeneXus los pone
  `CorregirSignoMovCaja.Call(MovCajaId) on BeforeComplete;`, una regla de MovCaja que **no** está en un bloque `[web]`: GeneXus la
  corre también cuando un procedimiento graba con el Business Component (`&MovCaja.Save()`). El motor no corría en el BC las reglas
  con evento (V48c), así que se perdía todo lo que hacen: números (`LlamarGetNumeroId`), estados históricos, cuenta corriente y
  stock de un comprobante grabado por BC (`CPBCActualizarTablas`, `ActualizarMovimientosDeStock`...).
- Clave nueva y opcional de la TRN: **`reglasBc: |`**, las reglas que el Save() / Insert() / Update() del Business Component corre en
  sus eventos (las de GeneXus fuera de `[web]`, más las de `[bc]` y `[web][bc]`). Antes de grabar: BeforeValidate, AfterValidate y
  BeforeInsert / BeforeUpdate (cabecera y cada renglón) y las AfterLevel; ya grabado, en la misma transacción: AfterInsert /
  AfterUpdate y BeforeComplete, y al final AfterComplete. Un `Error()` hace que el Save no grabe nada y `Success()` dé falso (en
  AfterComplete solo se informa). Usan las mismas variables que dejaron las reglas de la TRN. Sin `reglasBc:`, todo como antes.
- `parchar_reglas.py --bc` arma `reglasBc:` desde la KB en los YAML que ya existen. Corregido también en el convertidor: las reglas de
  un bloque `[web][bc]` se perdían (valen para la pantalla y para el BC); en MovCaja era `GrabarEgresoDeCaja` (cheques, banco y
  transferencia de un movimiento cargado a mano). El importador del Designer todavía no traduce reglas (las deja para la IA): cuando
  las traduzca, va con esto.
- Probado en el banco de pruebas: la cobranza 301 genera su movimiento de caja con el signo, los importes con signo y el estado
  histórico. Batería de regresión igual. Las 293 TRN del ERP cargan (58 con `reglasBc:`).
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6p — La lupa se deshabilita con su campo bloqueado (04/11/2026)

- Diego: en COBR la lupa de la Cuenta tiene que andar solo si la forma de pago afecta banco. La regla de GeneXus ya bloqueaba el
  campo (`NoAccept(...) if FPagoAfectaBanco = false`), pero la lupa seguía activa. Ahora toda lupa (de referencia o de
  procedimiento, en la cabecera o en los renglones) se ve deshabilitada y no busca mientras su campo esté bloqueado; cuando la regla
  lo libera, vuelve a andar. Como el Prompt de GeneXus.
- Probado con los componentes de Vaadin 24.9.3 en el navegador (campo editable, bloqueado y liberado).
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6o — Fechas: 0411 de nuevo y el calendario solo con el ícono (04/11/2026)

- Diego: escribir 0411 en una fecha ya no la completaba (con Vaadin 24.9 el control dejó de leer la conversión de la V50), y el
  calendario se abría al hacer clic en el campo. Ahora lo escrito se completa en el mismo campo al salir (con TAB, Enter o el mouse):
  0411 -> 04/11/(año actual), 041126 -> 04/11/2026, 04112026, 4/11, 4/11/26. El calendario se abre solo con el ícono. En todos los
  campos fecha: TRN, renglones, web panels, filtros de los WorkWith y parámetros de las consultas.
- Probado con el date-picker de Vaadin 24.9.3 en el navegador: 0411+TAB, 041126+Enter, 5/3+clic afuera, fecha completa, el ícono.
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6n — Versión de la aplicación y color del nombre del sistema (04/11/2026)

- Diego: debajo del nombre del sistema (arriba a la izquierda, en el menú horizontal y en el vertical) se ve la versión de la
  APLICACIÓN, chiquita, centrada y del mismo color. Sale de `version.properties` (clave `version`) de los recursos de la aplicación,
  que su `pom.xml` completa al compilar; sin ese archivo no se ve nada (como antes). El nombre sigue saliendo de la aplicación
  (`sistema.nombre` del cfg o `OptimistExtension.nombreSistema()`), nunca del motor.
- Apariencia: un color nuevo con el nombre del sistema (ej. "OPTIMIST ERP"): el color del nombre y de la versión en la barra (para
  distinguir en qué empresa se trabaja). Solo en la barra, no en el login. Va primero en el renglón de los colores. Sin elegir (y al
  Restablecer), el color de la aplicación (`sistema.color` del cfg o `OptimistExtension.colorSistema()`); si no tiene, el de las letras.
- La versión va con letra de 13px (Diego: un poco más grande que los 11px del principio).
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6m — `mostrarClave: true` en una referencia (03/11/2026)

- Diego: en los comprobantes "Entidad PK" mostraba el código de la entidad (1546) en vez de su clave (40369), y al lado "Entidades
  Id" con el mismo 1546. Nuevo (opcional): `mostrarClave: true` en la referencia: en el formulario el campo de la clave muestra la
  clave interna; la lupa (columnas, filtro por código), el WorkWith y lo demás siguen igual. Sin la clave, como antes.
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6l — El Load de un BC calcula las fórmulas, como GeneXus (03/11/2026)

- Diego: una orden de pago efectivizada en 4.0 dejaba el recibo con saldo igual a su importe ("A CUENTA"), aunque imputaba toda la
  factura; en GeneXus queda en 0. GenerarMovimientosDeCuentaCorriente (igual a la KB) recalcula en un For each PAGOCreditos,
  PAGODebitos y PAGOPagoACuenta con el signo al revés de las fórmulas de la TRN y después hace `&PAGO.Load()`: en GeneXus el Load
  vuelve a calcular las fórmulas (PAGOPagoACuenta = A Pagar + créditos − débitos = 0); el motor devolvía lo grabado por el For each.
- Ahora `&BC.Load()` calcula todas las fórmulas de la TRN (cabecera y renglones, también las que se graban) con el registro leído.
  Si no se pueden calcular, queda lo grabado, como antes.
- Probado: la batería del banco (21 web panels y 4 procedimientos) da exactamente igual.
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6k — Las variables numéricas no se truncan a sus decimales (03/11/2026)

- Diego: la retención de IIBB del PAGO daba 94.329,31 y en GeneXus 95.071,29. En CalcularRetenciones, `&Porcentaje Numeric(6.2) =
  &BaseDeCalculo / &CPBPTotal` el motor lo truncaba a 0,82; GeneXus guarda la cuenta completa (0,8264…) y la retención sale con eso.
- Ahora, como GeneXus: una variable numérica (procedimientos, reglas `&var = …`, web panels) guarda el valor completo. Si los
  decimales de más son ceros, queda con los de la declaración, como antes (así lo que se muestra o se pasa a texto no cambia).
  Se redondea a los del atributo al grabar y a los del dominio al mostrar.
- Al grabar una TRN o un BC con un atributo con más decimales que su dominio, se redondea (antes daba "máximo N decimales"; desde la
  pantalla no puede pasar, el campo ya redondea). Los New y For each de los procedimientos ya lo dejaban redondear a la base.
- Probado: la batería de los web panels y procedimientos del banco (21 paneles y 4 procedimientos) da exactamente igual que antes.
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6j — La pregunta "¿Confirmás …?" de los web panels, opcional (03/11/2026)

- Diego: con `panel.confirmar=false` en el `optimist.cfg` del cliente, los botones de los web panels ya no preguntan "¿Confirmás
  …?" antes de correr. La pregunta propia de un botón (`confirmar: "texto"` en el YAML, ej. la de Imprimir Comprobante) sigue
  saliendo. Sin la clave, como antes (pregunta). Se lee al día: no hace falta reiniciar.
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6i — `url:` en las opciones de menu.yaml (02/11/2026)

- Lo de la E6h no alcanzaba: en la base de Diego las dos opciones se llaman igual ("Entidades", con `wwentidades?1` y
  `wwentidades?2`), así que por el título no se distinguen y Clientes y Proveedores seguían abriendo la lista entera.
- Nuevo (opcional): `url:` en una opción de `menu.yaml`, con la dirección del menú de GeneXus que la abre:
  `{titulo: Proveedores, trn: entidadesproveedores, url: "wwentidades?2"}`. Si la dirección de la opción de la base es esa
  (sin importar mayúsculas, `.aspx` ni el paquete), va esa ruta. Es lo primero que se mira; sin `url:`, todo como antes.
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6h — Opciones del menú de la base con parámetros (02/11/2026)

- Aquiles: Clientes y Proveedores (en el menú de la base, `wwentidades?1` y `wwentidades?2`) abrían la misma lista, con todas las
  entidades: el motor tiraba lo que viene después del `?`. Ahora, si la dirección trae parámetros y `menu.yaml` tiene una opción
  con ese mismo título (ej. `{titulo: Clientes, trn: entidadesclientes}`, una vista con su filtro), va esa. Si no, como antes.
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6g — Variables editables en los renglones de una TRN (`variable: true`, 02/11/2026)

- Diego: en GeneXus la grilla de comprobantes de COBR y PAGO tenía el tilde `&bImputar` (imputar todo el saldo) y en 4.0 no
  aparecía: el motor no sabía mostrar una &variable editable en un renglón.
- Nuevo (opcional, guía 6.1.3b): `variable: true` en un campo de la cabecera o de un detalle. Se ve y se edita como un campo,
  **no se graba** y las reglas del nivel lo usan como `&Nombre` con el valor de **cada renglón** (leer, asignar, condiciones;
  cambiarlo dispara las reglas que lo leen, como cualquier campo).
- Compatible: sin la clave, todo igual.
- Pendiente: que el importador (gx2yaml / importador.js) traiga solas las variables de las grillas de GeneXus como `variable: true`
  (hoy las saltea; en el ERP se agregaron a mano en COBR y PAGO).
- **Hay que hacer `mvn install` en `engine`** (sigue la versión 0.6.0) y reiniciar las aplicaciones.

## E6f — La calculadora se abre con el clic derecho (02/11/2026)

- Diego: el doble clic en un campo numérico abría la calculadora y no dejaba seleccionar el contenido, como en cualquier campo.
  Ahora la calculadora se abre con el **clic derecho** en un campo numérico editable (formulario y renglones de las TRN, web panels);
  el **doble clic vuelve a seleccionar**. En esos campos el clic derecho ya no muestra el menú del navegador (Copiar, Pegar…):
  Ctrl+C / Ctrl+V siguen andando. En los campos de solo lectura y en los demás, el clic derecho es el de siempre.
- Sin cambios en los YAML. **Hay que hacer `mvn install` en `engine`** y reiniciar las aplicaciones.

## E6e — Impresiones: la picture sin 9 deja el cero en blanco (01/11/2026)

- `formato:` de las impresiones (`OptimistImpresion.formatear`): una picture numérica que no tiene ningún `9` (ej.
  `ZZZ,ZZZ,ZZZ.ZZ`) imprime en blanco el valor 0, como GeneXus. Diego: en la factura genérica del ERP salían cuatro "0,00" sin
  título (las percepciones de Ingresos Brutos en 0) que en OPTIMIST no se ven. Con un `9` en la picture (`ZZ9.99`) el cero se
  sigue imprimiendo ("0,00"). Sin cambios en los YAML.

## E6d — Barras de desplazamiento de más en las grillas (01/11/2026)

Diego: una lista con 3 o 4 columnas mostraba barra horizontal; con 20 registros por página aparecía la barra vertical adentro de
la grilla y el último renglón quedaba cortado, y al volver a 10 seguía igual. Es de todas las grillas (WorkWith, consultas,
web panels).

- Causa: el manejador para arrastrar el ancho de las columnas (V82n, `optimist-grid.css`) sobresalía 5 px de la última
  columna (`right: -5px`); esos 5 px disparaban la barra horizontal, y con ella la grilla de Vaadin (`setAllRowsVisible`) no
  volvía a calcular su alto al cambiar la cantidad de registros: barra vertical y último renglón cortado.
- Arreglo: en la última columna el manejador queda adentro de la celda, con la línea pegada al borde. Reproducido y verificado
  con la grilla de Vaadin 24.9.3 en Chromium (sin el sobresaliente, 10 → 20 → 10 → 3 registros sin ninguna barra).
- Solo CSS del motor (no cambia ningún YAML ni la API).

## E6 — Grilla tipo WorkWith en los web panels: filtros y tildes; bajada de archivos (01/11/2026), versión 0.6.0

Diego: cualquier grilla tipo WW tiene que poder agrandar las columnas, filtrar y ordenar por columna (la Facturación Masiva del ERP).

- Grilla `lista: true` de los web panels (guía 19): **filtros por columna** como los del WorkWith (texto, número con operadores,
  fecha desde-hasta, combo múltiple; `OptimistWorkWith.filtrar`, en memoria) y **columnas de tildes** (una columna `Boolean` sin
  `soloLectura`): tilde por renglón, que el evento ve en la colección, y un tilde en el título que tilda o destilda los que se ven.
  El orden por título y el ancho de las columnas ya estaban (V74).
- Un archivo que deja un evento o un procedimiento y no es un PDF (ZIP, CSV, Excel…) se baja al navegador, con su tipo
  (`OptimistVisorPdf.abrir` / `bajar`). Lo usa la Facturación Masiva del ERP para el ZIP de los comprobantes.
- **Consultas con tildes y lotes** (guía 21): `tildes:` (columnas de tildes, que se recuerdan entre páginas y filtros, con el tilde del
  título para la página) y `botones` con `tilde: Columna` + `proc:` (corre por cada tildado) + `despues:` (una vez al final); lo que
  dejan (PDF / archivo) se muestra o se baja al final. Así una consulta es un WorkWith completo (Excel, PDF, columnas, Apariencia, Σ,
  PostIt, filtros, orden, páginas) que además procesa los tildados: la Facturación Masiva del ERP (`consultas/WPMultiML.yaml`).
- Las consultas muestran la **insignia** (pastilla de color) de los campos que la tienen en su TRN, como el WorkWith (ej. el Status
  del pedido en la Facturación Masiva), y la clase del WorkWith (`estilos`).
- Corregido: en una consulta con columnas de tildes, al refrescar (F5) se perdían las columnas elegidas y los anchos (el orden guardado
  no incluía las columnas de tildes y Vaadin lo rechazaba entero).
- **E6c**: el lote de una consulta (botones con `tilde:`) corre en segundo plano con la ventanita "Procesando…" y el % de avance
  (renglones hechos / total, y el texto que informa el procedimiento con `MostrarProgreso`), igual que los web panels: la pantalla
  no queda congelada. `OptimistProgreso`, la ventanita de V66x hecha reusable. Los mensajes iguales de varios renglones se juntan
  en uno ("Todos (10): …", "3 renglones (…): …") en vez de repetirse.
- Versión 0.6.0.

## E5c — `color:` en las acciones y versión 0.5.0 (01/11/2026)

Diego: el motor da por cada TRN Insert, Update, Delete y View y los mecanismos genéricos; todo lo demás es de cada aplicación, y nada
del ERP (nombres, colores, reglas) entra al motor. Y preocupación por tener que bajar el motor a cada rato: reglas en `CLAUDE.md`
(compatibilidad hacia atrás, versiones numeradas, cadencia para los clientes).

- **`color:`** en las acciones del renglón y los botones del WorkWith, de las vistas y de las consultas: el color del ícono
  (`"#1e7fd8"`, `"30,100,180"` o `red`, como los colores de V45). Sin él, el color por defecto del ícono (la tabla de V56d, que ya no
  nombra funciones del ERP). El Designer lo deja como está.
- Versión del motor **0.5.0** (antes 0.1.0-SNAPSHOT); el ERP y la plantilla del Designer la declaran en su `pom.xml`.

## E5b — Permisos en el ERP: la entrada la decide el menú (01/11/2026)

Al prender `permisos:` en OPTIMIST ERP (GeneXus 15 + WorkWithPlus) se vio, con la KB a la vista, que OPTIMIST no pide la clave
`WW<Trn>` para entrar a un WorkWith: entrar lo decide el menú (`MenuSecUser`, que `GrabarMenuSecUserByRole` rearma desde los roles);
`WW<Trn>` la preguntan *otros* programas para habilitar el hipervínculo a esa TRN. Y `PXToolsMenuDataDP` filtra **cada nivel** del
menú por el usuario: un padre que el usuario no tiene apaga todo lo de abajo.

- **`permisos.entrada: menu`** (guía 24.3): con `tipo: wwp`, entrar a una TRN, un web panel o una consulta (desde el menú, por
  dirección o como ventana) no pide clave: lo controla el menú del usuario, como antes (V48e). Las claves `WW<Trn>`,
  `WW<Trn>_Insert/_Update/_Delete`, `<Campo>_Attribute`, `permiso:` y las propias siguen valiendo para botones, acciones,
  hipervínculos y `SecIsAuthByFunctionalityKey`. `entrada: clave` (por defecto) es lo de la E5a (la clave del programa, como
  WorkWithPlus). `OptimistPermisos.puedeEntrar`.
- **Menú de la base**: un nodo padre también tiene que estar en `MenuSecUser` para que se vean sus hijos (antes el motor miraba solo
  las hojas). Las rutas de lo que cuelga de un padre apagado se siguen controlando al entrar por dirección.
- **`herramientas/permisos_gx.py`** (y el importador del Designer, paso 5): la clave de permiso de cada acción del WorkWithPlus
  (`SecFuntionKey` de cada `userAction`: PEDAutorizar, PEDCancelar, PrintPedido…) pasa a `permiso:` de la acción del YAML. El ERP
  se había migrado sin esto (acciones_gx no lo traía), así que un usuario sin PEDAutorizar veía Autorizar Pedido. Aparea por título y,
  si no, por el objeto que llama y sus parámetros constantes; lo que no aparea queda informado.
- Las **acciones y botones de las consultas** (`consultas/*.yaml`) aceptan `permiso:` como los del WorkWith (sin la clave no se ven) y
  entran en "Actualizar permisos" debajo de su consulta. Ícono FOLDER_OPEN / FOLDER en azul (la carpetita de "ver comprobantes").
- Claves de "Actualizar permisos": confirmado contra `SecGetAdvancedSecurityWWPFunctionalities` de la KB del ERP que el motor usa los
  mismos tipos y padres que WorkWithPlus (`WW<Trn>` Object sin padre; `_Insert/_Update/_Delete` Mode y las acciones Action debajo
  de `WW<Trn>`; `<Campo>_Attribute` Attribute debajo de `WW<Trn>`). Las claves que ya están en la base no se tocan.

## E5a — Permisos y panel de Seguridad (01/11/2026)

Diego: "el manejo de permisos que hoy no lo tenemos y que es vital para las trn y los ww"; que más adelante se puedan agregar
permisos de TRN y de funcionalidades nuevas; un panel para la seguridad con un botón para actualizar, como el de WorkWithPlus.

- **`permisos:` en `seguridad.yaml`** (guía 24.3): `tipo: wwp` (las tablas de la seguridad avanzada de WorkWithPlus, con la lógica de
  `SecIsAuthByFunctionalityKey`) o `extension` (la aplicación da las claves del usuario: `OptimistExtension.permisos`); `gam`, más
  adelante. Sin `permisos:`, todos pueden todo (las aplicaciones que ya andan no cambian; el ERP sigue con sus procesos).
- El motor aplica las claves de WorkWithPlus: `WW<Trn>` (WorkWith y menú), `_Insert` / `_Update` / `_Delete` (+ Nuevo, Modificar
  —sin permiso abre para ver—, Eliminar), `<Campo>_Attribute` (`permisos.campos` en la TRN: campo y columna), el nombre del web panel o
  la consulta, y `permiso:` en acciones y botones del WorkWith y en botones de los web panels. Las carpetas del menú que quedan vacías
  no se muestran. `SecIsAuthByFunctionalityKey` es nativo (reglas y procedimientos) **solo con `permisos:`**; sin `permisos:` sigue el
  `.proc` de la aplicación (corregido el mismo día: en el ERP el nativo autorizaba a todos, ej. PEDCancelar, CAMBIOVENDEDOR).
- **Panel de Seguridad** (`ruta: seguridad`): roles y su árbol de permisos con tildes, roles de cada usuario y **Actualizar
  permisos**: da de alta las claves que falten (TRN, campos, acciones, web panels, consultas, `permisos.claves`) y se las asigna al rol
  administrador. Así, lo que se agregue más adelante se carga solo.
- Importador: los campos con permiso de WorkWithPlus van a `permisos.campos`; los permisos ya no figuran como "sin migrar". Si la KB
  usa la seguridad de WorkWithPlus y la aplicación no tiene `permisos:`, el informe dice qué agregar.
- Seguridad (Diego): **duplicar un rol** (con sus permisos), **dar de alta un usuario** y **duplicar un usuario** (con sus roles). Las
  columnas obligatorias de la tabla de usuarios que no se piden quedan vacías, como un alta de GeneXus.
- `administradores: [Usuario]` en `permisos:`: esos usuarios tienen todo aunque les falte el rol (para no quedar afuera de Seguridad).
- Plantilla de aplicación: `seguridad.yaml` con `permisos:` comentado y el ítem de Seguridad en el menú, explicado.
- Probado contra MariaDB con las TRN de Factory: Actualizar permisos (35 claves nuevas; reusa las que ya estaban, ej. `WWIncidentes`;
  la segunda vez, nada), un administrador con todo y un usuario con un rol de solo consulta (entra a Incidentes, sin agregar, sin ver
  Facturar, sin Clientes en el menú).

## E4h — Teclado en los formularios: cursor en el primer campo y Enter como TAB (31/10/2026)

- Diego: al abrir **cualquier TRN o web panel** el cursor queda en el **primer campo editable** (alta y modificación de las TRN;
  web panels en ventana o como página, después de su Start). En Visualizar no. Si mientras se arma se abrió una lupa o la
  calculadora, o el usuario ya está en un campo, no le saca el foco.
- **Enter pasa al campo siguiente**, como TAB, salteando los de solo lectura, deshabilitados y ocultos (otras solapas); en una
  grilla de renglones va al de la derecha y después al renglón de abajo. En el último campo no hace nada.
- **Enter nunca confirma ni toca un botón** (Confirmar, Procesar, Cancelar...): siempre con el mouse.
- Excepciones: texto de varios renglones (Enter = renglón nuevo), combo o fecha con la lista abierta (elige), lupas (Enter
  elige), calculadora, PostIts y confirmaciones (como siempre).
- Nueva clase `OptimistTeclado` (un script en el navegador); la usan `OptimistTrnView` y `OptimistPanelView`. Vale para todas las
  aplicaciones; no hay nada que configurar en los YAML.
- **Hay que hacer `mvn install` en `engine`** y reiniciar las aplicaciones.

## E4g — Las ventanas se pueden mover (29/10/2026)

- Diego: los web panels en ventana y las lupas quedaban fijos. **Todas las ventanas** (lupas, web panels, formularios de las TRN,
  PDF, calculadora, ayuda…) se mueven arrastrando el título (el cursor lo indica) o un espacio libre si no tienen título.
- **Hay que hacer `mvn install` en `engine`** y reiniciar las aplicaciones.

## E4f — Combo en el filtro de las descripciones de referencias (29/10/2026)

- Diego: en Factory el filtro de Estado de Incidentes era "contiene" y en GeneXus se elegía de una lista. La columna que muestra la
  descripción de una referencia (Estado, Tipo, Usuario…) tiene ahora el **combo de selección múltiple** (el de V60) con los
  registros de esa tabla, si tiene **hasta 50** (Diego). Con más, "contiene". WorkWith, vistas y consultas; sin configurar nada;
  caché de 10 minutos por base. Guía, sección 9.
- **Hay que hacer `mvn install` en `engine`** y reiniciar las aplicaciones.

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
