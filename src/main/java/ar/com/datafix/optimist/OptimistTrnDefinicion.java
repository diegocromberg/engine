package ar.com.datafix.optimist;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V21 - Definición de una TRN leída desde YAML (trn/*.yaml).
 * Solo estructura y validación: no conoce Vaadin ni JDBC.
 * Todos los nombres de tabla y columna se validan como identificadores
 * porque el SQL genérico los concatena (los VALORES siempre van como parámetros ?).
 *
 * V21b: varios detalles, solapas, referencias a otras tablas (atributos inferidos),
 * autonumerado, solo lectura, ocultos, valores por defecto, combos (dominios enumerados)
 * y auditoría de alta/modificación.
 */
public final class OptimistTrnDefinicion {
    private OptimistTrnDefinicion() {}

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern TIPO_GX = Pattern.compile("\\s*([A-Za-z]+)\\s*(?:\\(\\s*(\\d+)\\s*(?:[.,]\\s*(\\d+))?\\s*\\))?\\s*");

    public enum Tipo { CHAR, VARCHAR, NUMERIC, DATE, DATETIME, BOOLEAN }

    // ------------------------------------------------------------------ V30: contexto de sesión (WWPContext)

    /**
     * Variables del contexto que se pueden usar en el YAML como "&SecUserId" (default: y workwith.filtro:): las del motor y
     * las que agrega la aplicación (E3, OptimistExtension.variablesContexto; en OPTIMIST ERP: &Empresa, &Division, &EmpStk...).
     */
    public static List<String> variablesContexto() {
        List<String> r = new ArrayList<>(List.of("SecUserId", "SecUserName"));
        for (OptimistExtension e : OptimistExtension.todas())
            for (String v : e.variablesContexto()) if (r.stream().noneMatch(v::equalsIgnoreCase)) r.add(v);
        return r;
    }

    /** Lo instala OptimistContexto al iniciar sesión: nombre de variable -> valor del usuario actual. */
    private static volatile java.util.function.Function<String, Object> contexto = v -> null;
    static void instalarContexto(java.util.function.Function<String, Object> f) { contexto = f; }

    /** "&SecUserId" -> "SecUserId" (nombre canónico); null si no es una variable de contexto. */
    static String variableContexto(Object v) {
        if (!(v instanceof String s) || !s.startsWith("&")) return null;
        String n = s.substring(1).trim().replaceFirst("(?i)^WWPContext\\.", "");
        if (n.equalsIgnoreCase("Today") || n.equalsIgnoreCase("Now")) return n.equalsIgnoreCase("Today") ? "Today" : "Now";   // V48c: fecha del día
        if (n.equalsIgnoreCase("NewGuid")) return "NewGuid";   // V83: un UUID nuevo (el GUID.NewGuid() de GeneXus)
        List<String> vars = variablesContexto();
        return vars.stream().filter(x -> x.equalsIgnoreCase(n)).findFirst()
                .orElseThrow(() -> error("variable de contexto desconocida '" + s + "' (se puede usar: &"
                        + String.join(", &", vars) + ")"));
    }

    /** Valor de un literal del YAML o de una variable de contexto, llevado al dominio (null si el contexto no la tiene). */
    public static Object resolver(Dominio d, Object v) {
        String var = variableContexto(v);
        if (var == null) return v;
        // V48c: default "&Today" / "&Now": la fecha (y hora) del momento del alta
        if (var.equals("Today") || var.equals("Now")) {
            Object hoy = d.tipo() == Tipo.DATE ? java.time.LocalDate.now() : java.time.LocalDateTime.now().withNano(0);
            try { return normalizar(d, hoy); } catch (IllegalArgumentException e) { return hoy; }
        }
        if (var.equals("NewGuid")) return java.util.UUID.randomUUID().toString();   // V83
        Object x = contexto.apply(var);
        if (x == null) return null;
        try { return normalizar(d, x); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * V43: condición del filtro fijo del WorkWith que no es igualdad: {PRSNumero: ">0"}, {PEDStatus: "<>X"}.
     * valor: constante normalizada o "&Variable" del contexto del usuario.
     */
    public record Condicion(String operador, Object valor) {
        @Override public String toString() { return operador + valor; }
    }
    private static final Pattern OPERADOR = Pattern.compile("^\\s*(<>|!=|>=|<=|>|<|=)\\s*(.*)$");

    /** Valor de un dominio enumerado (combo): lo que se guarda y lo que se muestra. */
    public record Valor(Object valor, String texto) {}

    /**
     * V83: campo de tipo archivo (el Blob de GeneXus) de la cabecera: la columna guarda el contenido; nombreArchivo y
     * tipoArchivo (optativos) son los campos con el nombre y la extensión, como AttFileNameAtt / AttFileTypeAtt de GeneXus.
     * No va en 'campos': se sube y se baja desde su panel del formulario y se graba aparte, en la misma transacción.
     */
    public record Archivo(String nombre, String etiqueta, String nombreArchivo, String tipoArchivo) {}

    /** miles (V33): los números se muestran con punto de miles (1.234.567,89), en la pantalla y en el WorkWith. */
    public record Dominio(String nombre, Tipo tipo, int longitud, int decimales, List<Valor> valores, boolean miles) {
        public Dominio(String nombre, Tipo tipo, int longitud, int decimales, List<Valor> valores) { this(nombre, tipo, longitud, decimales, valores, false); }
        public Dominio conMiles() { return new Dominio(nombre, tipo, longitud, decimales, valores, true); }
        public boolean esTexto() { return tipo == Tipo.CHAR || tipo == Tipo.VARCHAR; }
        public boolean esNumerico() { return tipo == Tipo.NUMERIC; }
        public boolean esEntero() { return tipo == Tipo.NUMERIC && decimales == 0; }
        public boolean esCombo() { return valores != null && !valores.isEmpty(); }
    }

    /**
     * formula != null: atributo calculado (como en GeneXus); no se guarda ni se edita.
     * inferido: viene de otra tabla por una referencia; no se guarda, se muestra.
     * oculto: se guarda pero no se muestra (se preserva al modificar, se graba vacío/defecto en el alta).
     */
    public record Campo(String nombre, String etiqueta, Dominio dominio, boolean clave, boolean obligatorio,
                        boolean editableEnModificacion, String visibleSi, boolean nulo, String formula,
                        boolean autonumerico, boolean inferido, boolean soloLectura, boolean oculto,
                        Object defecto, String mensaje, String ancho, boolean guardar, int renglonesDefinidos,
                        Map<String, String> estilo, Insignia insignia) {
        /** Compatibilidad V38: campo sin insignia. */
        public Campo(String nombre, String etiqueta, Dominio dominio, boolean clave, boolean obligatorio,
                     boolean editableEnModificacion, String visibleSi, boolean nulo, String formula,
                     boolean autonumerico, boolean inferido, boolean soloLectura, boolean oculto,
                     Object defecto, String mensaje, String ancho, boolean guardar, int renglonesDefinidos,
                     Map<String, String> estilo) {
            this(nombre, etiqueta, dominio, clave, obligatorio, editableEnModificacion, visibleSi, nulo, formula, autonumerico,
                 inferido, soloLectura, oculto, defecto, mensaje, ancho, guardar, renglonesDefinidos, estilo, null);
        }
        /** Texto largo desde este tamaño: se dibuja multirenglón. */
        public static final int LARGO_MULTIRENGLON = 512;
        public static final int RENGLONES_AUTOMATICOS = 3;
        /**
         * V24: renglones del control. 1 = una sola línea. Automático: VarChar/LongVarChar de 512 o más
         * va con 3 renglones; con 'renglones: N' en el YAML se fuerza N (1 = una sola línea).
         */
        public int renglones() {
            if (renglonesDefinidos > 0) return renglonesDefinidos;
            return dominio.esTexto() && !dominio.esCombo() && dominio.longitud() >= LARGO_MULTIRENGLON ? RENGLONES_AUTOMATICOS : 1;
        }
        /** V30: el default ya resuelto: si es "&Empresa", el valor del contexto del usuario. */
        public Object defectoActual() { return resolver(dominio, defecto); }
        /** Se guarda: campo común, o fórmula REDUNDANTE (guardar: true, se calcula al grabar). */
        public boolean persistido() { return (formula == null || guardar) && !inferido; }
        public boolean editable() { return formula == null && !inferido && !soloLectura && !autonumerico; }
    }

    /**
     * Referencia a otra tabla (clave foránea). campos: local -> columna remota; trae: local -> columna remota.
     * V26 donde: condiciones fijas sobre la tabla remota (columna remota -> constante), por ejemplo
     * {EntidadesTipoEntidad: 2} para "solo proveedores". Se aplican al traer datos, en la lupa y al validar.
     */
    public record Referencia(String tabla, LinkedHashMap<String, String> campos, LinkedHashMap<String, String> trae,
                             String descripcion, LinkedHashMap<String, Object> donde, Codigo codigo) {
        /** Condiciones fijas como SQL, con el alias dado ("t3." o ""). Vacío si no hay. Las que dependen de un campo no van. */
        public List<String> condicionesFijas(String alias) {
            List<String> r = new ArrayList<>();
            donde.forEach((col, v) -> {
                if (v instanceof DondeCampo) return;
                Object x = deContexto(v);
                if (x != null) r.add(alias + col + " = " + literal(x));
            });
            return r;
        }
        /**
         * V47: condiciones fijas + las que dependen de un campo de la TRN (ej. el tipo de entidad según el CPI):
         * con el valor actual de ese campo. Si el campo está vacío o su valor no está en la tabla, esa condición no va.
         */
        public List<String> condiciones(String alias, java.util.function.Function<String, Object> valorDe) {
            List<String> r = new ArrayList<>();
            donde.forEach((col, v) -> {
                Object x = v instanceof DondeCampo dc ? dc.valor(valorDe.apply(dc.campo())) : deContexto(v);
                if (x != null) r.add(alias + col + " = " + literal(x));
            });
            return r;
        }
        /** V47: la clave es un solo campo y la tabla tiene código: se ve y se escribe el código, se guarda la clave. */
        public boolean conCodigo() { return codigo != null && campos.size() == 1; }
        /** V49u: "&EmpLst", "&Empresa"...: el valor del contexto del usuario (sin valor, la condición no va). */
        private static Object deContexto(Object v) {
            String var = variableContexto(v);
            if (var == null) return v;
            Object x = contexto.apply(var);
            return OptimistTrnDatos.vacio(x) ? null : x;
        }
        private static String literal(Object v) {
            if (v instanceof Boolean b) return b ? "1" : "0";
            if (v instanceof Number n) return new java.math.BigDecimal(n.toString()).toPlainString();
            return "'" + String.valueOf(v).replace("\\", "\\\\").replace("'", "''") + "'";
        }
    }

    /**
     * V47: código que ve y escribe el usuario en lugar de la clave interna de una tabla (como el "Input Type =
     * Descriptions" de GeneXus): en la base se guarda ArticulosPK, el usuario trabaja con ArticulosId.
     * columna: la columna del código; segun: (opcional) la columna que distingue registros con el mismo código
     * (EntidadesTipoEntidad: un cliente y un proveedor pueden tener el mismo EntidadesId). Se define en dominios.yaml:
     *   codigos:
     *     Articulos: {codigo: ArticulosId}
     *     Entidades: {codigo: EntidadesId, segun: EntidadesTipoEntidad}
     */
    public record Codigo(String columna, String segun) {}

    /**
     * V47: condición de un 'donde' que depende de un campo de la TRN: {campo: PRSCPI, C: 1, P: 2} = si PRSCPI es "C",
     * la columna vale 1; si es "P", 2. Sin valores ({campo: X}) se compara con el valor del campo tal cual.
     */
    public record DondeCampo(String campo, Map<String, Object> valores) {
        public Object valor(Object deCampo) {
            if (OptimistTrnDatos.vacio(deCampo)) return null;
            if (valores.isEmpty()) return deCampo;
            String k = String.valueOf(deCampo).trim();
            for (Map.Entry<String, Object> e : valores.entrySet()) if (e.getKey().equalsIgnoreCase(k)) return e.getValue();
            return null;
        }
    }

    /** oculto (V27, solo detalles): no se muestra, no se lee ni se graba; sus filas quedan como están. */
    public record Nivel(String nombre, String titulo, String tabla, List<Campo> campos,
                        List<String> orden, String numerador, List<Referencia> referencias, boolean oculto) {
        public List<Campo> claves() { return campos.stream().filter(Campo::clave).toList(); }
        public List<Campo> persistidos() { return campos.stream().filter(Campo::persistido).toList(); }
        public List<Campo> noClaves() { return campos.stream().filter(c -> c.persistido() && !c.clave()).toList(); }
        public Campo campo(String nombre) {
            return campos.stream().filter(c -> c.nombre().equalsIgnoreCase(nombre)).findFirst().orElse(null);
        }
        public Campo autonumerico() { return campos.stream().filter(Campo::autonumerico).findFirst().orElse(null); }
    }

    /** titulos (V29): título propio de una columna del WorkWith (y del Excel), sin cambiar la etiqueta del formulario. */
    /** filtro (V30): condiciones fijas del WorkWith, campo -> valor o "&Empresa" (como las Conditions del WW de GeneXus). */
    /** anchos (V32): ancho fijo de una columna del WorkWith ("100px"); el texto que no entra sigue en otro renglón. */
    /** ocultas (V41): campos que el WorkWith trae en la consulta pero no muestra (para acciones y procedimientos). */
    /**
     * V48: acciones del renglón del WorkWith (las del WorkWithPlus de GeneXus). modificar / visualizar / borrar:
     * null = siempre; "false" = nunca; otro texto = condición sobre el renglón (fórmula: PRSStatus = 'C' or PRSStatus = 'T').
     * renglon: las acciones propias (procedimiento, web panel u otra TRN), con su condición.
     */
    public record Acciones(String modificar, String visualizar, String borrar, List<AccionRenglon> renglon, String inicio) {
        public static final Acciones TODAS = new Acciones(null, null, null, List.of(), null);
        public Acciones(String modificar, String visualizar, String borrar, List<AccionRenglon> renglon) {
            this(modificar, visualizar, borrar, renglon, null);
        }
    }

    /**
     * V48: una acción propia del renglón. tipo: proc (procedimiento), panel (web panel, wbp/), trn (otra TRN en un modo).
     * parametros: atributos del renglón (PRSId), constantes (1, "C") o "&Variable" (de salida: si trae un texto, se muestra).
     * icono: nombre de VaadinIcon (CLOCK, BAN, COPY, ENVELOPE...). si: condición (null = siempre). confirmar: pregunta antes.
     */
    public record AccionRenglon(String titulo, String icono, String si, String tipo, String objeto, String modo,
                                List<String> parametros, String confirmar, boolean porRenglon) {
        public AccionRenglon(String titulo, String icono, String si, String tipo, String objeto, String modo,
                             List<String> parametros, String confirmar) {
            this(titulo, icono, si, tipo, objeto, modo, parametros, confirmar, false);
        }
    }

    /** V48 alta: web panel que reemplaza al formulario vacío del "+ Nuevo" (ej. PRSAltaWbp: pide cliente y comprobante). */
    public record WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                           Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones, List<String> prompt,
                           Map<String, String> promptTitulos, Map<String, Map<String, String>> estilos, boolean paginarEnServidor,
                           String condicion, List<AccionRenglon> botones, boolean nuevo, Map<String, Boolean> totales,
                           String totalesMoneda, Map<String, String> columnasSi) {
        /** V84: columnasSi: columna -> condición (con las &variables del inicio de las acciones); si da falso, la columna no va. */
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones, List<String> prompt,
                        Map<String, String> promptTitulos, Map<String, Map<String, String>> estilos, boolean paginarEnServidor,
                        String condicion, List<AccionRenglon> botones, boolean nuevo, Map<String, Boolean> totales,
                        String totalesMoneda) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, alta, acciones, prompt, promptTitulos, estilos, paginarEnServidor,
                 condicion, botones, nuevo, totales, totalesMoneda, Map.of());
        }
        /** V84: la condición de la columna (columnasSi:) o null si va siempre. */
        public String condicionColumna(String columna) {
            if (columnasSi == null) return null;
            for (Map.Entry<String, String> e : columnasSi.entrySet()) if (e.getKey().equalsIgnoreCase(columna)) return e.getValue();
            return null;
        }
        /** V77: totalesMoneda: la columna con la moneda del renglón para los totales por moneda; "no" = sin separar; null = sola. */
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones, List<String> prompt,
                        Map<String, String> promptTitulos, Map<String, Map<String, String>> estilos, boolean paginarEnServidor,
                        String condicion, List<AccionRenglon> botones, boolean nuevo, Map<String, Boolean> totales) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, alta, acciones, prompt, promptTitulos, estilos, paginarEnServidor,
                 condicion, botones, nuevo, totales, null);
        }
        /** V77: la columna de la moneda para los totales (o null): la de totalesMoneda, o la primera columna ...MonedasId. */
        public String monedaTotales(Nivel cab) {
            return OptimistTrnDefinicion.monedaTotales(totalesMoneda, columnas, cab);
        }
        /** V75: totales: columnas que se agregan (true, 'totales:') o se sacan (false, 'sinTotales:') de los totales al pie. */
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones, List<String> prompt,
                        Map<String, String> promptTitulos, Map<String, Map<String, String>> estilos, boolean paginarEnServidor,
                        String condicion, List<AccionRenglon> botones, boolean nuevo) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, alta, acciones, prompt, promptTitulos, estilos, paginarEnServidor,
                 condicion, botones, nuevo, Map.of());
        }
        /** V75: ¿la columna va con total al pie? Los dominios de importes (dominios.yaml 'totalizar:') más / menos los ajustes. */
        public boolean totaliza(Campo c) {
            if (c == null) return false;
            for (Map.Entry<String, Boolean> e : totales.entrySet()) if (e.getKey().equalsIgnoreCase(c.nombre())) return e.getValue();
            return OptimistTrnDefinicion.dominioDeTotal(c);
        }
        /**
         * V57: condicion: una condición más de la lista, en el WHERE (campos de la cabecera, &Variables del inicio de las
         * acciones, true/false y SQL: exists(...)). botones: acciones de arriba de la lista (no son de un renglón, ej.
         * Reautorizar). nuevo: false = sin "+ Nuevo" (las vistas que solo trabajan sobre lo que ya existe).
         */
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones, List<String> prompt,
                        Map<String, String> promptTitulos, Map<String, Map<String, String>> estilos, boolean paginarEnServidor) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, alta, acciones, prompt, promptTitulos, estilos, paginarEnServidor,
                 null, List.of(), true);
        }
        /** V50o: paginarEnServidor: la lista pide a la base solo la página que muestra (filtros y orden en el SQL). */
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones, List<String> prompt,
                        Map<String, String> promptTitulos, Map<String, Map<String, String>> estilos) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, alta, acciones, prompt, promptTitulos, estilos, false);
        }
        /** V48e: estilos: el estilo (CSS) de las celdas de cada columna, de workwith.clase / workwith.clases (sin estilo, no está). */
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones, List<String> prompt,
                        Map<String, String> promptTitulos) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, alta, acciones, prompt, promptTitulos, Map.of());
        }
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas, String alta, Acciones acciones) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, alta, acciones, List.of(), Map.of());
        }
        public WorkWith(List<String> columnas, int filasPorPagina) { this(columnas, filasPorPagina, Map.of(), Map.of(), Map.of(), List.of(), null, Acciones.TODAS, List.of(), Map.of()); }
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos) { this(columnas, filasPorPagina, titulos, Map.of(), Map.of(), List.of(), null, Acciones.TODAS, List.of(), Map.of()); }
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro, Map<String, String> anchos) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, List.of(), null, Acciones.TODAS, List.of(), Map.of());
        }
        public WorkWith(List<String> columnas, int filasPorPagina, Map<String, String> titulos, Map<String, Object> filtro,
                        Map<String, String> anchos, List<String> ocultas) {
            this(columnas, filasPorPagina, titulos, filtro, anchos, ocultas, null, Acciones.TODAS, List.of(), Map.of());
        }
        public String titulo(Campo c) { return titulos.getOrDefault(c.nombre(), c.etiqueta()); }
        /** V48: título de una columna de la lupa ("MarcasDescrip: Marca"); si no, el del WorkWith o la etiqueta. */
        public String tituloPrompt(Campo c) { return promptTitulos.getOrDefault(c.nombre(), titulo(c)); }
    }

    /**
     * Solapa del formulario: campos de la cabecera y detalles (por nombre) que muestra.
     * filas: si en el YAML 'campos' es una lista de listas, cada lista es un renglón de la pantalla.
     */
    /** bloques (V33): el contenido de la solapa EN ORDEN (tablas, detalles, paneles, renglones). tablas/detalles: todos, aplanados. */
    public record Solapa(String titulo, List<String> campos, List<String> detalles, List<List<String>> filas,
                         List<Tabla> tablas, List<Bloque> bloques) {
        public Solapa(String titulo, List<String> campos, List<String> detalles, List<List<String>> filas, List<Tabla> tablas) {
            this(titulo, campos, detalles, filas, tablas, List.of());
        }
    }

    /**
     * V33: bloques de la pantalla. Un panel (como el de GeneXus) se abre y se cierra con su ícono; 'abierto' es como
     * arranca. Un renglón pone sus bloques uno al lado del otro (ej. dos paneles). Paneles y renglones se anidan.
     */
    public sealed interface Bloque permits BloqueTabla, BloqueDetalle, Panel, Renglon {}
    public record BloqueTabla(Tabla tabla) implements Bloque {}
    /** V38: completo = la grilla ocupa todo el ancho (columnas estiradas en proporción); alinear = izquierda/centro/derecha.
     *  V65f: sinTitulo = no muestra el título del detalle (lo dice el panel); el título sigue en los mensajes. */
    public record BloqueDetalle(String detalle, boolean completo, String alinear, boolean sinTitulo) implements Bloque {
        public BloqueDetalle(String detalle) { this(detalle, false, null, false); }
    }
    /** V38: alinear = el panel toma el ancho de su contenido y va a la izquierda, al centro o a la derecha. */
    public record Panel(String titulo, boolean abierto, String ancho, List<Bloque> bloques, String alinear, boolean ocultarVacio) implements Bloque {
        public Panel(String titulo, boolean abierto, String ancho, List<Bloque> bloques) { this(titulo, abierto, ancho, bloques, null, false); }
        public Panel(String titulo, boolean abierto, String ancho, List<Bloque> bloques, String alinear) { this(titulo, abierto, ancho, bloques, alinear, false); }
    }
    public record Renglon(List<Bloque> bloques) implements Bloque {}

    /** Celda de una tabla de pantalla: un campo (o vacía si campo == null) que ocupa 'span' columnas. */
    public record Celda(String campo, int span) {}

    /**
     * Tabla de pantalla estilo GeneXus: columnas con ancho, etiqueta a la izquierda (o arriba) y
     * un campo por celda. columnas: anchos (px, %, fr, ch, auto); vacía = columnas iguales.
     */
    public record Tabla(String titulo, List<String> columnas, boolean etiquetaIzquierda, String anchoEtiquetas,
                        List<List<Celda>> filas, String alinear) {
        /** alinear (V33): izquierda, centro o derecha (null = ocupa todo el ancho). */
        public Tabla(String titulo, List<String> columnas, boolean etiquetaIzquierda, String anchoEtiquetas, List<List<Celda>> filas) {
            this(titulo, columnas, etiquetaIzquierda, anchoEtiquetas, filas, null);
        }
        public int cantidadColumnas() {
            int n = columnas.size();
            for (List<Celda> f : filas) n = Math.max(n, f.stream().mapToInt(Celda::span).sum());
            return Math.max(1, n);
        }
        public List<String> campos() {
            return filas.stream().flatMap(List::stream).map(Celda::campo).filter(Objects::nonNull).toList();
        }
    }

    /**
     * ruta: la parte variable de la URL (trn/{ruta}). La ubicación en el menú se define en menu.yaml.
     * auditoria: altaFecha, altaUsuario, modificacionFecha, modificacionUsuario -> nombre de campo.
     */
    /**
     * V40: el valor se muestra como una "pastilla" (recuadro redondeado de color), en el WorkWith y en el formulario.
     * colores: valor o texto del combo -> color de la paleta (lo que no figura va en gris).
     * colorDe: el color sale de otro campo de la fila (ej. StatusLogisticoColor, cuyo texto es "Azul", "Rojo"...).
     */
    public record Insignia(Map<String, String> colores, String colorDe) {
        public static final List<String> PALETA = List.of("azul", "verde", "verde-oscuro", "rojo", "marron", "magenta", "celeste",
                "turquesa", "violeta", "naranja", "amarillo", "gris", "gris-oscuro");

        /** "Marrón" -> "marron", "Verde Oscuro" -> "verde-oscuro"; null si no es un color de la paleta. */
        public static String color(Object texto) {
            if (texto == null) return null;
            String t = java.text.Normalizer.normalize(String.valueOf(texto).trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "").replaceAll("[\\s_]+", "-");
            return PALETA.contains(t) ? t : null;
        }

        /** El color para este valor (buscando por el valor y por su texto en el combo). */
        public String colorDeValor(Dominio d, Object valor) {
            if (valor == null || colores.isEmpty()) return null;
            String v = clave(valor);
            String r = colores.get(v);
            if (r == null && d != null && d.esCombo())
                for (Valor x : d.valores()) if (clave(x.valor()).equals(v)) { r = colores.get(clave(x.texto())); break; }
            return r;
        }

        static String clave(Object o) {
            String s = o instanceof java.math.BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(o);
            return java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        }
    }

    public record Trn(String nombre, String ruta, String titulo, String singular, String plural,
                      boolean femenino, Nivel cabecera, List<Nivel> detalles, List<Solapa> solapas,
                      Map<String, String> auditoria, WorkWith workwith, List<List<String>> filas, List<Tabla> tablas,
                      String encabezado, List<Bloque> bloques, String inicio, String reglas,
                      Map<String, Object> calculo, List<Archivo> archivos) {
        /** Compatibilidad V83: TRN sin archivos. */
        public Trn(String nombre, String ruta, String titulo, String singular, String plural,
                   boolean femenino, Nivel cabecera, List<Nivel> detalles, List<Solapa> solapas,
                   Map<String, String> auditoria, WorkWith workwith, List<List<String>> filas, List<Tabla> tablas,
                   String encabezado, List<Bloque> bloques, String inicio, String reglas, Map<String, Object> calculo) {
            this(nombre, ruta, titulo, singular, plural, femenino, cabecera, detalles, solapas, auditoria, workwith, filas, tablas,
                 encabezado, bloques, inicio, reglas, calculo, List.of());
        }
        /** Compatibilidad V35: TRN sin cálculo comercial. */
        public Trn(String nombre, String ruta, String titulo, String singular, String plural,
                   boolean femenino, Nivel cabecera, List<Nivel> detalles, List<Solapa> solapas,
                   Map<String, String> auditoria, WorkWith workwith, List<List<String>> filas, List<Tabla> tablas,
                   String encabezado, List<Bloque> bloques, String inicio, String reglas) {
            this(nombre, ruta, titulo, singular, plural, femenino, cabecera, detalles, solapas, auditoria, workwith, filas, tablas,
                 encabezado, bloques, inicio, reglas, null);
        }
        /** Compatibilidad: TRN sin inicio ni reglas. */
        public Trn(String nombre, String ruta, String titulo, String singular, String plural,
                   boolean femenino, Nivel cabecera, List<Nivel> detalles, List<Solapa> solapas,
                   Map<String, String> auditoria, WorkWith workwith, List<List<String>> filas, List<Tabla> tablas,
                   String encabezado, List<Bloque> bloques) {
            this(nombre, ruta, titulo, singular, plural, femenino, cabecera, detalles, solapas, auditoria, workwith, filas, tablas,
                 encabezado, bloques, null, null, null);
        }
        /** Campos que usa el encabezado ("Pedido {PedidosNumero} de {ClientesRazonSocial}"). */
        public List<String> camposEncabezado() {
            if (encabezado == null) return List.of();
            List<String> r = new ArrayList<>();
            Matcher m = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(encabezado);
            while (m.find()) r.add(m.group(1));
            return r;
        }
        public boolean dosNiveles() { return !detalles.isEmpty(); }
        /** Compatibilidad V21: el primer (o único) detalle. */
        public Nivel detalle() { return detalles.isEmpty() ? null : detalles.get(0); }
        /** V27: los detalles que se muestran y se graban (sin los 'oculto: true'). */
        public List<Nivel> detallesVisibles() { return detalles.stream().filter(d -> !d.oculto()).toList(); }
        public Nivel detalle(String nombre) {
            return detalles.stream().filter(d -> d.nombre().equalsIgnoreCase(nombre)).findFirst().orElse(null);
        }
        /** "o"/"a" para armar textos: Nueva moneda, Moneda guardada. */
        public String genero() { return femenino ? "a" : "o"; }
    }

    // ------------------------------------------------------------------ V75: totales al pie del WorkWith

    /** Los dominios de importes que se totalizan solos (dominios.yaml 'totalizar:'); sin la lista, estos. */
    private static volatile Set<String> dominiosTotal = Set.of("total", "total14", "totalconsigno", "total2decz", "importe2dec",
            "importe2decconmiles", "importe2decsignedmiles");

    /** ¿El campo es un importe (numérico, no combo, con un dominio de 'totalizar:')? */
    public static boolean dominioDeTotal(Campo c) {
        Dominio d = c.dominio();
        return d != null && d.esNumerico() && !d.esCombo() && d.nombre() != null
                && dominiosTotal.contains(d.nombre().trim().toLowerCase(Locale.ROOT));
    }

    static void leerDominiosTotal(Map<String, Object> dominiosGlobales) {
        if (dominiosGlobales == null || dominiosGlobales.get("totalizar") == null) return;
        Set<String> s = new HashSet<>();
        for (String n : lista(dominiosGlobales.get("totalizar"))) s.add(n.trim().toLowerCase(Locale.ROOT));
        dominiosTotal = Set.copyOf(s);
    }

    /**
     * V84: workwith.columnasSi: {Columna: condición}. La columna va en la lista solo si la condición (con las &variables del
     * inicio de las acciones, ej. &VerTotales) da verdadero. Ej. el Saldo de los Comprobantes según VERCampos.
     */
    private static Map<String, String> columnasSi(Object o, List<String> columnas, Nivel cabecera) {
        if (o == null) return Map.of();
        if (!(o instanceof Map<?, ?> m)) throw error("workwith.columnasSi: va {Columna: condición}");
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String col = String.valueOf(e.getKey()).trim(), cond = String.valueOf(e.getValue()).trim();
            String real = columnas.stream().filter(x -> x.equalsIgnoreCase(col)).findFirst().orElse(null);
            // V84d: una vista (vistas/*.yaml) hereda el columnasSi de su TRN con otras columnas: la que no está en la lista
            // se saltea; solo es error un campo que no existe
            if (real == null) {
                if (cabecera.campo(col) == null) throw error("workwith.columnasSi: '" + col + "' no es un campo de la cabecera");
                continue;
            }
            if (cond.isEmpty()) throw error("workwith.columnasSi: falta la condición de " + col);
            try { OptimistFormula.de(cond); }
            catch (RuntimeException ex) { throw error("workwith.columnasSi: la condición de " + col + " está mal escrita: " + ex.getMessage()); }
            r.put(real, cond);
        }
        return Collections.unmodifiableMap(r);
    }

    /**
     * V77: la columna con la moneda del renglón para los totales por moneda. indicada: 'totalesMoneda:' del YAML ("no" = no
     * se separa; false de YAML también); sin indicar, la primera columna de la lista cuyo nombre termina en MonedasId.
     */
    public static String monedaTotales(String indicada, List<String> columnas, Nivel cab) {
        if (indicada != null) {
            if (indicada.equalsIgnoreCase("no") || indicada.equalsIgnoreCase("false")) return null;
            Campo c = cab == null ? null : cab.campo(indicada);
            return c != null ? c.nombre() : indicada;   // también una columnaSql de una consulta
        }
        for (String n : columnas) {
            String k = n.contains(":") ? n.substring(0, n.indexOf(':')).trim() : n.trim();
            if (k.toLowerCase(Locale.ROOT).endsWith("monedasid")) {
                Campo c = cab == null ? null : cab.campo(k);
                return c != null ? c.nombre() : k;
            }
        }
        return null;
    }

    /** 'totales: [Col, ...]' (se agregan) y 'sinTotales: [Col, ...]' (se sacan) de un WorkWith o una consulta. */
    public static Map<String, Boolean> ajustesTotales(Object totales, Object sinTotales) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String n : lista(totales)) if (!n.isBlank()) m.put(n.trim(), true);
        for (String n : lista(sinTotales)) if (!n.isBlank()) m.put(n.trim(), false);
        return Map.copyOf(m);
    }

    // ------------------------------------------------------------------ lectura

    /** Arma una TRN desde el Map que devuelve SnakeYAML. dominiosGlobales: dominios.yaml. */
    @SuppressWarnings("unchecked")
    /** V82p: las columnas de la lista con su expresión SQL (tabla|campo, en minúscula). */
    static final Map<String, String> SQL_LISTA = new java.util.concurrent.ConcurrentHashMap<>();

    /** V82p: la expresión SQL de una columna fórmula de la lista (workwith.columnasSql), o null. */
    public static String sqlLista(String tabla, String campo) {
        return tabla == null || campo == null ? null : SQL_LISTA.get((tabla + "|" + campo).toLowerCase(Locale.ROOT));
    }

    public static Trn desde(Map<String, Object> y, Map<String, Object> dominiosGlobales) {
        String nombre = ident(texto(y, "transaccion", null), "transaccion");
        Map<String, Dominio> dominios = new LinkedHashMap<>();
        leerDominios(dominiosGlobales, dominios);
        leerDominios((Map<String, Object>) y.get("dominios"), dominios);
        leerDominiosTotal(dominiosGlobales);   // V75
        // V33: dominios.yaml -> conMiles: [Precio, Total, ...] vale para todas las TRN (aunque la TRN defina el dominio)
        if (dominiosGlobales != null)
            for (String n : lista(dominiosGlobales.get("conMiles"))) {
                Dominio x = dominios.get(n.trim().toLowerCase(Locale.ROOT));
                if (x != null && x.esNumerico()) dominios.put(n.trim().toLowerCase(Locale.ROOT), x.conMiles());
            }

        // V45: clases de campo (dominios.yaml y la TRN; la de la TRN manda)
        Map<String, Map<String, String>> clases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        leerClases(dominiosGlobales, clases, "dominios.yaml");
        leerClases(y, clases, nombre);
        CLASES.put(nombre.toLowerCase(Locale.ROOT), clases);
        // V47: código en lugar de la clave interna (dominios.yaml y la TRN; la de la TRN manda)
        Map<String, Codigo> codigos = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        leerCodigos(dominiosGlobales, codigos, "dominios.yaml");
        leerCodigos(y, codigos, nombre);

        List<String> clavePrimaria = lista(y.get("clavePrimaria"));
        Nivel cabecera = nivel(nombre, texto(y, "titulo", nombre), texto(y, "tabla", nombre),
                (List<Map<String, Object>>) y.get("campos"), clavePrimaria, lista(y.get("orden")), null,
                y.get("referencias"), dominios, "campos", clases, claseNivel(y, "Cabecera", clases, nombre), codigos);
        validarReferencias(cabecera, null);
        leerVer(nombre, cabecera, null, y.get("ver"), "ver");   // V66k
        long autos = cabecera.campos().stream().filter(Campo::autonumerico).count();
        if (autos > 1 || (autos == 1 && (cabecera.claves().size() != 1 || !cabecera.autonumerico().dominio().esEntero())))
            throw error("autonumerico: solo para una clave única numérica entera de la cabecera");

        List<Object> bloques = new ArrayList<>();
        if (y.get("detalle") instanceof Map<?, ?> d1) bloques.add(d1);
        else if (y.get("detalle") != null) throw error("'detalle' debe ser un bloque con nombre, tabla y campos");
        if (y.get("detalles") instanceof List<?> ds) bloques.addAll(ds);
        else if (y.get("detalles") != null) throw error("'detalles' debe ser una lista");
        List<Nivel> detalles = new ArrayList<>();
        Set<String> nombresDet = new HashSet<>();
        for (Object o : bloques) {
            if (!(o instanceof Map<?, ?> dm)) throw error("detalle inválido");
            Map<String, Object> det = (Map<String, Object>) dm;
            String nomDet = ident(texto(det, "nombre", "Detalle"), "detalle.nombre");
            if (!nombresDet.add(nomDet.toLowerCase(Locale.ROOT))) throw error("detalle repetido: " + nomDet);
            Nivel nd = nivel(nomDet, texto(det, "titulo", nomDet), texto(det, "tabla", nombre + nomDet),
                    (List<Map<String, Object>>) det.get("campos"), lista(det.get("clavePrimaria")),
                    lista(det.get("orden")), opcional(det, "numerador"), det.get("referencias"), dominios,
                    "detalle " + nomDet + ".campos", clases, claseNivel(det, "Detalle", clases, "detalle " + nomDet), codigos);
            if (bool(det.get("oculto"), false))   // V27: detalle oculto
                nd = new Nivel(nd.nombre(), nd.titulo(), nd.tabla(), nd.campos(), nd.orden(), nd.numerador(), nd.referencias(), true);
            // V50x: estiloRenglon: {si: "ArticulosTotalizadorItemizado = true", estilo: {negrita: true}}: estilo de toda la línea según una condición
            // V65c: también una lista, y cada una con 'campos: [...]' para pintar solo esos campos de la línea (ej. COBR: importes de
            // los débitos en verde y negrita, de los créditos en rojo)
            ESTILOS_DETALLE.remove(nd.tabla().toLowerCase(Locale.ROOT));
            Object erDef = det.get("estiloRenglon");
            if (erDef != null) {
                List<?> defs = erDef instanceof List<?> l ? l : List.of(erDef);
                List<EstiloRenglon> estilos = new ArrayList<>();
                for (Object defEr : defs) {
                    if (!(defEr instanceof Map<?, ?> er)) throw error("detalle " + nomDet + ".estiloRenglon debe ser {si: ..., estilo: {...}} o una lista de esos");
                    String si = er.get("si") == null ? null : String.valueOf(er.get("si")).trim();
                    if (si == null || si.isEmpty()) throw error("detalle " + nomDet + ".estiloRenglon: falta 'si' (la condición)");
                    try { OptimistFormula.de(si); } catch (IllegalArgumentException ex) { throw error("detalle " + nomDet + ".estiloRenglon.si: " + ex.getMessage()); }
                    Map<String, String> est = estilo(er.get("estilo"), "detalle " + nomDet + ".estiloRenglon");
                    if (est.isEmpty()) throw error("detalle " + nomDet + ".estiloRenglon: falta 'estilo'");
                    Set<String> campos = null;
                    if (er.get("campos") != null) {
                        campos = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                        for (String c : lista(er.get("campos"))) {
                            if (nd.campo(c) == null) throw error("detalle " + nomDet + ".estiloRenglon.campos: el campo " + c + " no está en el detalle");
                            campos.add(c);
                        }
                        if (campos.isEmpty()) campos = null;
                    }
                    estilos.add(new EstiloRenglon(si, est, campos));
                }
                if (!estilos.isEmpty()) ESTILOS_DETALLE.put(nd.tabla().toLowerCase(Locale.ROOT), List.copyOf(estilos));
            }
            validarDetalle(cabecera, nd);
            validarReferencias(nd, cabecera);
            leerVer(nombre, nd, cabecera, det.get("ver"), "detalle " + nomDet + ".ver");   // V66k
            validarFormulas(nd, cabecera, List.of());
            // V30: dos detalles pueden compartir numerador (en GeneXus: Serial(A, LastX) y Serial(B, LastX))
            detalles.add(nd);
        }
        // V41: las fórmulas de la cabecera que se graban pueden sumar campos de los detalles (PRSSubtotal = sum(PRSDetSubtotal))
        validarFormulas(cabecera, null, detalles);

        Map<String, Object> ww = y.get("workwith") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        // V82p: workwith.columnasSql: {Campo: expresión SQL} para una columna fórmula de la lista (ej. el saldo del comprobante),
        // así la lista paginada la trae, la filtra, la ordena y la suma en la base (t0 = la tabla de la TRN)
        if (ww.get("columnasSql") instanceof Map<?, ?> cs)
            for (Map.Entry<?, ?> e : cs.entrySet()) {
                String campo = String.valueOf(e.getKey()).trim();
                if (cabecera.campo(campo) == null) throw error("workwith.columnasSql: " + campo + " no es un campo de la cabecera");
                SQL_LISTA.put((cabecera.tabla() + "|" + campo).toLowerCase(Locale.ROOT), String.valueOf(e.getValue()).trim());
            }
        // V29: cada columna puede traer su título: [ArticulosId, "MarcasDescrip: Marca"] o [ArticulosId, MarcasDescrip: Marca]
        List<String> columnas = new ArrayList<>();
        Map<String, String> titulosWw = new LinkedHashMap<>();
        Set<String> vistasWw = new HashSet<>();
        if (ww.get("columnas") instanceof List<?> crudas) {
            for (Object o : crudas) {
                String nom, tit = null;
                if (o instanceof Map<?, ?> mm && mm.size() == 1) {
                    Map.Entry<?, ?> e1 = mm.entrySet().iterator().next();
                    nom = String.valueOf(e1.getKey()).trim();
                    tit = e1.getValue() == null ? null : String.valueOf(e1.getValue()).trim();
                } else {
                    String t = String.valueOf(o).trim();
                    int i = t.indexOf(':');
                    nom = i < 0 ? t : t.substring(0, i).trim();
                    if (i >= 0) tit = t.substring(i + 1).trim();
                }
                if (nom.isEmpty()) continue;
                Campo cc = cabecera.campo(nom);
                String nomReal = cc == null ? nom : cc.nombre();
                if (!vistasWw.add(nomReal.toLowerCase(Locale.ROOT))) throw error("workwith.columnas: '" + nomReal + "' está repetida");
                columnas.add(nomReal);
                if (tit != null && !tit.isEmpty()) titulosWw.put(nomReal, tit);
            }
        } else columnas = lista(ww.get("columnas"));
        if (columnas.isEmpty()) columnas = cabecera.persistidos().stream().filter(c -> !c.oculto()).limit(6).map(Campo::nombre).toList();
        for (String c : columnas) {
            Campo campo = cabecera.campo(c);
            if (campo == null) throw error("workwith.columnas: '" + c + "' no es un campo de la cabecera");
            // V41: una fórmula redundante (guardar: true) está grabada en la tabla: se puede mostrar
            if (campo.formula() != null && !campo.guardar() && sqlLista(cabecera.tabla(), campo.nombre()) == null)   // V82p: o con columnasSql
                throw error("workwith.columnas: '" + c + "' es fórmula que no se graba; el WorkWith muestra campos propios, inferidos, fórmulas redundantes o las de workwith.columnasSql");
        }
        int filas = entero(ww.get("filasPorPagina"), 10);
        // V30: filtro fijo del WorkWith (ej. {PRSEmpresa: "&Empresa", PRSDivision: "&Division"})
        Map<String, Object> filtroWw = new LinkedHashMap<>();
        if (ww.get("filtro") instanceof Map<?, ?> fm) {
            for (Map.Entry<?, ?> e : fm.entrySet()) {
                Campo fc = cabecera.campo(String.valueOf(e.getKey()));
                if (fc == null || !fc.persistido())
                    throw error("workwith.filtro: '" + e.getKey() + "' no es un campo guardado de la cabecera");
                if (e.getValue() == null) throw error("workwith.filtro: falta el valor de " + fc.nombre());
                // V43: también comparaciones: {PRSNumero: ">0"} (<>, >, <, >=, <=)
                Object val = e.getValue();
                String op = "=";
                if (val instanceof String sv) {
                    Matcher om = OPERADOR.matcher(sv);
                    if (om.matches() && !om.group(2).isBlank()) { op = om.group(1).equals("!=") ? "<>" : om.group(1); val = om.group(2).trim(); }
                }
                String var = variableContexto(val);
                Object v = var != null ? "&" + var : normalizar(fc.dominio(), val);
                filtroWw.put(fc.nombre(), op.equals("=") ? v : new Condicion(op, v));
            }
        } else if (ww.get("filtro") != null) throw error("workwith.filtro debe ser {Campo: valor, ...}");
        // V32: anchos de columna del WorkWith (ej. {PRSEntidadesRazonSocial: 200px}); un número solo = píxeles
        Map<String, String> anchosWw = new LinkedHashMap<>();
        if (ww.get("anchos") instanceof Map<?, ?> am) {
            for (Map.Entry<?, ?> e : am.entrySet()) {
                String col = columnas.stream().filter(x -> x.equalsIgnoreCase(String.valueOf(e.getKey()))).findFirst()
                        .orElseThrow(() -> error("workwith.anchos: '" + e.getKey() + "' no es una columna del WorkWith"));
                String a = String.valueOf(e.getValue()).trim();
                if (a.matches("\\d{1,4}")) a = a + "px";
                if (!a.matches("\\d{1,4}(px|em|rem|ch|%)")) throw error("workwith.anchos: ancho inválido '" + a + "' (ej. 120px)");
                anchosWw.put(col, a);
            }
        } else if (ww.get("anchos") != null) throw error("workwith.anchos debe ser {Columna: ancho, ...}");
        // V48e: clases del WorkWith, propias (no las del campo en el formulario): workwith.clase para todas las celdas
        // (o la clase "WorkWith" si existe; "ninguna" = sin clase) y workwith.clases {Columna: Clase | [Clase, Otra]} por columna
        String claseWw = claseNivel(ww, "WorkWith", clases, "workwith");
        Map<String, Object> clasesCol = new HashMap<>();
        if (ww.get("clases") instanceof Map<?, ?> cm) {
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                String col = columnas.stream().filter(x -> x.equalsIgnoreCase(String.valueOf(e.getKey()).trim())).findFirst()
                        .orElseThrow(() -> error("workwith.clases: '" + e.getKey() + "' no es una columna del WorkWith"));
                clasesCol.put(col, e.getValue());
            }
        } else if (ww.get("clases") != null) throw error("workwith.clases debe ser {Columna: Clase, ...}");
        // V61h: workwith.estiloRenglon: {si: "ComposiDefault = true", estilo: {negrita: true}}: estilo de todo el renglón del
        // WorkWith según una condición sobre sus columnas (las del WorkWith y las 'ocultas')
        ESTILOS_RENGLON.remove("ww:" + cabecera.tabla().toLowerCase(Locale.ROOT));
        if (ww.get("estiloRenglon") instanceof Map<?, ?> erw) {
            String si = erw.get("si") == null ? null : String.valueOf(erw.get("si")).trim();
            if (si == null || si.isEmpty()) throw error("workwith.estiloRenglon: falta 'si' (la condición)");
            try { OptimistFormula.de(si); } catch (IllegalArgumentException ex) { throw error("workwith.estiloRenglon.si: " + ex.getMessage()); }
            Map<String, String> est = estilo(erw.get("estilo"), "workwith.estiloRenglon");
            if (est.isEmpty()) throw error("workwith.estiloRenglon: falta 'estilo'");
            ESTILOS_RENGLON.put("ww:" + cabecera.tabla().toLowerCase(Locale.ROOT), new EstiloRenglon(si, est, null));
        } else if (ww.get("estiloRenglon") != null) throw error("workwith.estiloRenglon debe ser {si: ..., estilo: {...}}");
        Map<String, Map<String, String>> estilosWw = new LinkedHashMap<>();
        for (String col : columnas) {
            Map<String, Object> cc = new HashMap<>();
            if (clasesCol.get(col) != null) cc.put("clase", clasesCol.get(col));
            Map<String, String> est = estiloDeCampo(cc, "workwith.clases." + col, clases, claseWw);
            if (!est.isEmpty()) estilosWw.put(col, est);
        }
        // V41: campos que se traen pero no se muestran (ej. para llamar a un procedimiento desde una acción del renglón)
        List<String> ocultasWw = new ArrayList<>();
        for (String o : lista(ww.get("ocultas"))) {
            Campo oc = cabecera.campo(o);
            if (oc == null) throw error("workwith.ocultas: '" + o + "' no es un campo de la cabecera");
            if (oc.formula() != null && !oc.guardar()) throw error("workwith.ocultas: '" + o + "' es fórmula que no se graba; el WorkWith trae campos propios, inferidos o fórmulas redundantes");
            if (columnas.stream().anyMatch(x -> x.equalsIgnoreCase(oc.nombre())))
                throw error("workwith.ocultas: '" + oc.nombre() + "' ya está en columnas (va en una lista o en la otra)");
            if (ocultasWw.stream().noneMatch(x -> x.equalsIgnoreCase(oc.nombre()))) ocultasWw.add(oc.nombre());
        }
        // V77: la moneda de los totales (totalesMoneda:) se trae aunque no sea columna
        if (ww.get("totalesMoneda") != null && !List.of("no", "false").contains(String.valueOf(ww.get("totalesMoneda")).trim().toLowerCase(Locale.ROOT))) {
            Campo cm = cabecera.campo(String.valueOf(ww.get("totalesMoneda")).trim());
            if (cm == null) throw error("workwith.totalesMoneda: '" + ww.get("totalesMoneda") + "' no es un campo de la cabecera");
            if (columnas.stream().noneMatch(x -> x.equalsIgnoreCase(cm.nombre())) && ocultasWw.stream().noneMatch(x -> x.equalsIgnoreCase(cm.nombre())))
                ocultasWw.add(cm.nombre());
        }
        // V48: acciones del renglón; los campos que usan sus condiciones y parámetros se traen (como ocultas)
        Acciones accionesWw = acciones(ww.get("acciones"), cabecera, columnas, ocultasWw);
        // V48: workwith.prompt: columnas (con filtro cada una) de la lupa cuando otra TRN busca en esta (ej. el artículo de un renglón)
        List<String> promptWw = new ArrayList<>();
        Map<String, String> promptTit = new HashMap<>();
        List<Object> crudosPrompt = ww.get("prompt") instanceof List<?> lp ? new ArrayList<>(lp) : ww.get("prompt") == null ? List.of() : List.of(ww.get("prompt"));
        for (Object cr : crudosPrompt) {
            // "Campo" o "Campo: Título" (en YAML [A, B: Título] el segundo llega como {B: Título})
            String o, tit = null;
            if (cr instanceof Map<?, ?> m1 && m1.size() == 1) {
                var e1 = m1.entrySet().iterator().next();
                o = String.valueOf(e1.getKey()).trim();
                tit = e1.getValue() == null ? null : String.valueOf(e1.getValue()).trim();
            } else {
                String t = String.valueOf(cr).trim();
                int i = t.indexOf(':');
                o = i < 0 ? t : t.substring(0, i).trim();
                if (i >= 0) tit = t.substring(i + 1).trim();
            }
            Campo pc = cabecera.campo(o);
            if (pc == null) throw error("workwith.prompt: '" + o + "' no es un campo de la cabecera");
            if (pc.formula() != null && !pc.guardar()) throw error("workwith.prompt: '" + o + "' es fórmula que no se graba; van campos propios o inferidos");
            if (!pc.persistido() && cabecera.referencias().stream().noneMatch(r -> r.trae().containsKey(pc.nombre())))
                throw error("workwith.prompt: '" + o + "' es inferido pero ninguna referencia lo trae");
            promptWw.add(pc.nombre());
            if (tit != null && !tit.isEmpty()) promptTit.put(pc.nombre(), tit);
        }

        List<Solapa> solapas = new ArrayList<>();
        if (y.get("solapas") instanceof List<?> ls) {
            for (Object o : ls) {
                if (!(o instanceof Map<?, ?> sm)) throw error("solapa inválida");
                Map<String, Object> s = (Map<String, Object>) sm;
                String tit = texto(s, "titulo", "Solapa " + (solapas.size() + 1));
                List<List<String>> fs = renglones(s.get("campos"), cabecera, "solapa '" + tit + "'");
                List<String> cs = fs.stream().flatMap(List::stream).toList(), ds = lista(s.get("detalles"));
                for (String d : ds) if (detalles.stream().noneMatch(x -> x.nombre().equalsIgnoreCase(d)))
                    throw error("solapa '" + tit + "': detalle inexistente " + d);
                boolean conFilas = s.get("campos") instanceof List<?> l0 && l0.stream().anyMatch(x -> x instanceof List<?>);
                List<Tabla> ts = tablas(s.get("tablas"), cabecera, "solapa '" + tit + "'");
                List<String> todos = new ArrayList<>(cs);
                ts.forEach(t -> todos.addAll(t.campos()));
                if (new HashSet<>(todos.stream().map(x -> x.toLowerCase(Locale.ROOT)).toList()).size() != todos.size())
                    throw error("solapa '" + tit + "': un campo figura dos veces (en 'campos' y en 'tablas')");
                solapas.add(new Solapa(tit, List.copyOf(todos), ds, conFilas ? fs : List.of(), ts));
            }
        }

        Map<String, String> auditoria = new LinkedHashMap<>();
        if (y.get("auditoria") instanceof Map<?, ?> am) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) am).entrySet()) {
                if (!List.of("altaFecha", "altaUsuario", "modificacionFecha", "modificacionUsuario").contains(e.getKey()))
                    throw error("auditoria: clave desconocida " + e.getKey());
                Campo c = cabecera.campo(String.valueOf(e.getValue()));
                if (c == null || !c.persistido()) throw error("auditoria." + e.getKey() + ": campo inexistente " + e.getValue());
                auditoria.put(e.getKey(), c.nombre());
            }
        }

        // 'pantalla:' (bloque de texto con <solapa>, <tabla>, <detalle>) reemplaza a solapas/tablas/filas
        List<Tabla> tablasPantalla;
        List<Bloque> bloquesRaiz = List.of();
        if (y.get("pantalla") != null) {
            if (y.get("solapas") != null || y.get("tablas") != null || y.get("filas") != null)
                throw error("usar 'pantalla' o 'solapas'/'tablas'/'filas', no los dos");
            Pantalla p = pantalla(String.valueOf(y.get("pantalla")), cabecera, detalles);
            solapas = new ArrayList<>(p.solapas());
            tablasPantalla = p.tablas();
            bloquesRaiz = p.bloques();
        } else {
            tablasPantalla = tablas(y.get("tablas"), cabecera, "tablas");
        }

        String ruta = texto(y, "ruta", nombre.toLowerCase(Locale.ROOT));
        if (!ruta.matches("[a-z0-9_-]{1,64}")) throw error("ruta inválida: " + ruta);
        Trn trn = new Trn(nombre, ruta, cabecera.titulo(),
                texto(y, "singular", "registro"), texto(y, "plural", "registros"),
                texto(y, "genero", "m").toLowerCase(Locale.ROOT).startsWith("f"),
                cabecera, List.copyOf(detalles), List.copyOf(solapas), Map.copyOf(auditoria), new WorkWith(List.copyOf(columnas), filas, Map.copyOf(titulosWw), Collections.unmodifiableMap(filtroWw), Map.copyOf(anchosWw), List.copyOf(ocultasWw),
                        ww.get("alta") == null ? null : ident(String.valueOf(ww.get("alta")).trim(), "workwith.alta"), accionesWw, List.copyOf(promptWw), Map.copyOf(promptTit),
                        Map.copyOf(estilosWw), !Boolean.FALSE.equals(ww.get("paginarEnServidor")) || ww.get("condicion") != null,   // V82o: por defecto, en la base
                        condicionWw(ww.get("condicion"), cabecera, columnas, ocultasWw),
                        ww.get("botones") == null ? List.of() : List.copyOf(acciones(Map.of("renglon", ww.get("botones")), cabecera, columnas, ocultasWw).renglon()),
                        !Boolean.FALSE.equals(ww.get("nuevo")), ajustesTotales(ww.get("totales"), ww.get("sinTotales")),
                        ww.get("totalesMoneda") == null ? null : String.valueOf(ww.get("totalesMoneda")).trim(),
                        columnasSi(ww.get("columnasSi"), columnas, cabecera)),
                y.get("filas") == null ? List.of() : renglones(y.get("filas"), cabecera, "filas"),
                tablasPantalla, encabezado(opcional(y, "encabezado"), cabecera), bloquesRaiz,
                opcional(y, "inicio"), opcional(y, "reglas"), calculo(y.get("calculo")), archivos(y.get("archivos"), cabecera));
        // V36: el cálculo comercial se valida al leer la TRN (campos que no existen, expresiones mal escritas)
        if (trn.calculo() != null) OptimistComercial.de(trn);
        // V35: el inicio (evento Start) y las reglas se validan al leer la TRN (renglón y motivo si algo está mal)
        if (trn.inicio() != null || trn.reglas() != null) OptimistReglas.de(trn);
        return trn;
    }

    /** V83: archivos: [{nombre: Col, etiqueta: Texto, nombreArchivo: Campo, tipoArchivo: Campo}, ...] */
    @SuppressWarnings("unchecked")
    private static List<Archivo> archivos(Object o, Nivel cabecera) {
        if (o == null) return List.of();
        if (!(o instanceof List<?> l)) throw error("archivos: tiene que ser una lista");
        List<Archivo> r = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (Object x : l) {
            if (!(x instanceof Map<?, ?> mm)) throw error("archivos: cada archivo es {nombre: ..., etiqueta: ...}");
            Map<String, Object> m = (Map<String, Object>) mm;
            for (String k : m.keySet())
                if (!List.of("nombre", "etiqueta", "nombreArchivo", "tipoArchivo").contains(k))
                    throw error("archivos: clave desconocida '" + k + "' (nombre, etiqueta, nombreArchivo, tipoArchivo)");
            String n = ident(String.valueOf(m.get("nombre")).trim(), "archivos.nombre");
            if (!vistos.add(n.toLowerCase(Locale.ROOT))) throw error("archivos: " + n + " figura dos veces");
            if (cabecera.campo(n) != null) throw error("archivos: " + n + " también está en 'campos' (va en un solo lado)");
            String na = null, ta = null;
            for (String k : List.of("nombreArchivo", "tipoArchivo")) {
                if (m.get(k) == null) continue;
                Campo c = cabecera.campo(String.valueOf(m.get(k)).trim());
                if (c == null || !c.persistido()) throw error("archivos." + n + "." + k + ": campo inexistente " + m.get(k));
                if (k.equals("nombreArchivo")) na = c.nombre(); else ta = c.nombre();
            }
            r.add(new Archivo(n, m.get("etiqueta") == null ? n : String.valueOf(m.get("etiqueta")), na, ta));
        }
        return List.copyOf(r);
    }

    /** V40: insignia: {Aprobado: verde, ...} o {colorDe: OtroCampo}. */
    @SuppressWarnings("unchecked")
    private static Insignia insignia(Object o, String campo, Dominio dom) {
        if (o == null) return null;
        if (!(o instanceof Map<?, ?> m)) throw error(campo + ": insignia se escribe {valor: color, ...} o {colorDe: Campo}");
        Map<String, String> colores = new LinkedHashMap<>();
        String colorDe = null;
        for (var e : ((Map<Object, Object>) m).entrySet()) {
            String k = String.valueOf(e.getKey());
            if (k.equalsIgnoreCase("colorDe")) { colorDe = String.valueOf(e.getValue()).trim(); continue; }
            String color = Insignia.color(e.getValue());
            if (color == null) throw error(campo + ": insignia, '" + e.getValue() + "' no es un color (" + String.join(", ", Insignia.PALETA) + ")");
            colores.put(Insignia.clave(k), color);
        }
        if (dom != null && dom.esCombo())   // cada valor tiene que existir en el combo (por su código o su texto)
            for (String k : colores.keySet()) {
                boolean existe = dom.valores().stream().anyMatch(x -> Insignia.clave(x.valor()).equals(k) || Insignia.clave(x.texto()).equals(k));
                if (!existe) throw error(campo + ": insignia, el valor '" + k + "' no está en el combo");
            }
        return new Insignia(Collections.unmodifiableMap(colores), colorDe);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> calculo(Object o) {
        if (o == null) return null;
        if (!(o instanceof Map<?, ?> m)) throw error("calculo: se esperaba una sección con linea: y pie:");
        return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) m));
    }

    @SuppressWarnings("unchecked")
    private static Nivel nivel(String nombre, String titulo, String tabla, List<Map<String, Object>> campos,
                               List<String> clavePrimaria, List<String> orden, String numerador, Object referencias,
                               Map<String, Dominio> dominios, String donde,
                               Map<String, Map<String, String>> clases, String claseNivel, Map<String, Codigo> codigos) {
        ident(tabla, "tabla");
        if (campos == null || campos.isEmpty()) throw error(donde + ": no hay campos definidos");
        List<Campo> lista = new ArrayList<>();
        Set<String> vistos = new HashSet<>();
        for (Map<String, Object> c : campos) {
            String n = ident(texto(c, "nombre", null), donde + ".nombre");
            if (!vistos.add(n.toLowerCase(Locale.ROOT))) throw error(donde + ": campo repetido " + n);
            Dominio dom = dominioDeCampo(c, n, dominios);
            String formula = c.get("formula") == null ? null : String.valueOf(c.get("formula"));
            boolean clave = bool(c.get("clave"), false) || clavePrimaria.stream().anyMatch(k -> k.equalsIgnoreCase(n));
            boolean inferido = bool(c.get("inferido"), false);
            // V50b: pantalla: true = solo se muestra y lo llenan las reglas (Campo = &Variable): como inferido, sin referencia
            if (bool(c.get("pantalla"), false)) { inferido = true; SOLO_PANTALLA.add(tabla.toLowerCase(Locale.ROOT) + "." + n.toLowerCase(Locale.ROOT)); }
            if (clave && (formula != null || inferido)) throw error(n + ": una clave no puede ser fórmula ni inferida");
            boolean auto = bool(c.get("autonumerico"), false);
            if (auto && !clave) throw error(n + ": autonumerico solo en la clave");
            String visibleSi = c.get("visibleSi") == null ? null : String.valueOf(c.get("visibleSi"));
            Object defecto = c.get("default");
            lista.add(new Campo(n, texto(c, "etiqueta", n), dom, clave,
                    (clave && !auto) || bool(c.get("obligatorio"), false),
                    !clave && bool(c.get("editableEnModificacion"), true),
                    visibleSi, bool(c.get("nulo"), false), formula, auto, inferido,
                    bool(c.get("soloLectura"), false), bool(c.get("oculto"), false),
                    defecto == null ? null : variableContexto(defecto) != null ? "&" + variableContexto(defecto) : normalizar(dom, defecto),
                    opcional(c, "mensaje"), ancho(c.get("ancho"), n), bool(c.get("guardar"), false),
                    renglones(c.get("renglones"), n, dom), estiloDeCampo(c, n, clases, claseNivel), insignia(c.get("insignia"), n, dom)));
            if (bool(c.get("guardar"), false) && formula == null) throw error(n + ": 'guardar' es solo para fórmulas");
            if (c.get("combo") != null) COMBOS.put(tabla.toLowerCase(Locale.ROOT) + "." + n.toLowerCase(Locale.ROOT), comboTabla(c.get("combo"), donde + "." + n + ".combo"));
            if (c.get("enlace") != null) ENLACES.put(tabla.toLowerCase(Locale.ROOT) + "." + n.toLowerCase(Locale.ROOT), enlaceDe(c.get("enlace"), donde + "." + n));
            // V65: siguiente: true en una clave numérica: en el alta se propone el último + 1 (se puede cambiar)
            if (bool(c.get("siguiente"), false)) {
                if (!clave || auto || !dom.esNumerico() || dom.decimales() > 0)
                    throw error(n + ": 'siguiente' es solo para una clave numérica entera (no autonumérica)");
                SIGUIENTE.add(tabla.toLowerCase(Locale.ROOT) + "." + n.toLowerCase(Locale.ROOT));
            }
            if (c.get("lupa") != null) LUPAS.put(tabla.toLowerCase(Locale.ROOT) + "." + n.toLowerCase(Locale.ROOT), lupaProc(c.get("lupa"), donde + "." + n + ".lupa"));
        }
        // V40: el campo del que sale el color de la insignia tiene que estar en el mismo nivel
        for (Campo x : lista)
            if (x.insignia() != null && x.insignia().colorDe() != null
                    && lista.stream().noneMatch(y -> y.nombre().equalsIgnoreCase(x.insignia().colorDe())))
                throw error(x.nombre() + ": insignia, colorDe '" + x.insignia().colorDe() + "' no es un campo de " + nombre);
        for (String k : clavePrimaria)
            if (lista.stream().noneMatch(c -> c.nombre().equalsIgnoreCase(k))) throw error("clavePrimaria: " + k + " no está en los campos");
        List<String> ordenOk = new ArrayList<>();
        for (String o : orden) {
            String[] p = o.trim().split("\\s+");
            if (p.length > 2 || (p.length == 2 && !p[1].equalsIgnoreCase("ASC") && !p[1].equalsIgnoreCase("DESC")))
                throw error("orden inválido: " + o);
            // V41: también un campo inferido (la descripción de otra tabla, ej. la razón social del cliente)
            Campo campo = lista.stream().filter(c -> c.nombre().equalsIgnoreCase(p[0]) && (c.persistido() || c.inferido())).findFirst()
                    .orElseThrow(() -> error("orden: " + p[0] + " no es un campo guardado ni inferido"));
            ordenOk.add(campo.nombre() + (p.length == 2 ? " " + p[1].toUpperCase(Locale.ROOT) : ""));
        }
        if (numerador != null) ident(numerador, "numerador");

        List<Referencia> refs = new ArrayList<>();
        if (referencias instanceof List<?> lr) {
            for (Object o : lr) {
                if (!(o instanceof Map<?, ?> rm)) throw error(donde + ": referencia inválida");
                Map<String, Object> r = (Map<String, Object>) rm;
                String t = ident(texto(r, "tabla", null), "referencia.tabla");
                LinkedHashMap<String, String> cs = mapaIdent(r.get("campos"), "referencia " + t + ".campos");
                LinkedHashMap<String, String> tr = mapaIdent(r.get("trae"), "referencia " + t + ".trae");
                if (cs.isEmpty()) throw error("referencia " + t + ": faltan campos");
                String desc = opcional(r, "descripcion");
                if (desc != null) ident(desc, "referencia.descripcion");
                // V47: el código de la tabla (codigos: de dominios.yaml); la referencia puede cambiarlo o sacarlo con 'codigo: ninguno'
                Codigo cod = codigos.get(t);
                if (r.get("codigo") != null) {
                    String k = String.valueOf(r.get("codigo")).trim();
                    cod = k.equalsIgnoreCase("ninguno") ? null : new Codigo(ident(k, "referencia " + t + ".codigo"), cod == null ? null : cod.segun());
                }
                if (cod != null && cs.size() != 1) cod = null;   // clave compuesta: se sigue escribiendo la clave
                refs.add(new Referencia(t, cs, tr, desc, donde(r.get("donde"), t), cod));
                // V65: opcional: true = con 0 (o vacío) no se valida (un atributo de GeneXus que no es clave foránea, ej. CondPagoFPagoId)
                if (bool(r.get("opcional"), false)) REF_OPCIONAL.add(claveOpcional(tabla, t, cs.keySet()));
                // V65: dondeLupa = condiciones que filtran SOLO la lupa (ej. ArticulosMostrarEnVentas: true); escrito a mano se acepta
                if (r.get("dondeLupa") != null) {
                    LinkedHashMap<String, Object> dl = donde(r.get("dondeLupa"), t);
                    if (dl.values().stream().anyMatch(v -> v instanceof DondeCampo)) throw error("referencia " + t + ".dondeLupa: solo constantes o &Variables del usuario");
                    REF_DONDE_LUPA.put(claveOpcional(tabla, t, cs.keySet()), dl);
                }
                // V65: mensaje = lo que se avisa cuando el registro existe pero no cumple el 'donde' (ej. "no está activo")
                if (r.get("mensaje") != null) REF_MENSAJE.put(claveOpcional(tabla, t, cs.keySet()), String.valueOf(r.get("mensaje")).trim());
            }
        } else if (referencias != null) throw error(donde + ": 'referencias' debe ser una lista");

        Nivel nivel = new Nivel(nombre, titulo, tabla, List.copyOf(lista), List.copyOf(ordenOk), numerador, List.copyOf(refs), false);
        if (nivel.claves().isEmpty() && !donde.startsWith("panel ")) throw error(donde + ": falta la clave (clave: true o clavePrimaria)");
        return nivel;
    }

    /**
     * V48: las variables de un web panel (wbp/*.yaml) se leen como los campos de una TRN: dominio (con los de dominios.yaml
     * y los 'dominios:' del panel), etiqueta, soloLectura, oculto, estilo, clase... Devuelve un nivel sin tabla ni clave.
     */
    @SuppressWarnings("unchecked")
    public static Nivel variablesDePanel(String nombre, Map<String, Object> y, Map<String, Object> dominiosGlobales) {
        Map<String, Dominio> dominios = new LinkedHashMap<>();
        leerDominios(dominiosGlobales, dominios);
        leerDominios((Map<String, Object>) y.get("dominios"), dominios);
        if (dominiosGlobales != null)
            for (String n : lista(dominiosGlobales.get("conMiles"))) {
                Dominio x = dominios.get(n.trim().toLowerCase(Locale.ROOT));
                if (x != null && x.esNumerico()) dominios.put(n.trim().toLowerCase(Locale.ROOT), x.conMiles());
            }
        Map<String, Map<String, String>> clases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        leerClases(dominiosGlobales, clases, "dominios.yaml");
        leerClases(y, clases, nombre);
        if (!(y.get("variables") instanceof List<?>)) throw error("panel " + nombre + ": faltan 'variables'");
        return nivel(nombre, texto(y, "titulo", nombre), "panel", (List<Map<String, Object>>) y.get("variables"), List.of(), List.of(),
                null, null, dominios, "panel " + nombre + ".variables", clases, claseNivel(y, null, clases, nombre), Map.of());
    }

    /**
     * V48: workwith.acciones:
     *   acciones:
     *     modificar: "PRSStatus = 'C' or PRSStatus = 'T'"     # condición (o false = nunca)
     *     borrar: false
     *     renglon:
     *       - {titulo: Pasar a En Trámite, icono: CLOCK, si: "PRSStatus = 'C'", proc: PRSPasarAEnTramite, parametros: [PRSId],
     *          confirmar: "¿Pasar el presupuesto a En Trámite?"}
     *       - {titulo: Transferir, panel: WPTransferirPRSPED, parametros: [PRSId, PRSEmpresa]}
     *       - {titulo: Ver pedido, trn: PED, modo: Display, parametros: [PRSPEDId]}
     */
    @SuppressWarnings("unchecked")
    /**
     * V57: workwith.condicion: los campos de la cabecera que usa se traen en la consulta. Se controla que los paréntesis y
     * las comillas estén bien; el resto lo dice la base al abrir la lista.
     */
    private static String condicionWw(Object o, Nivel cab, List<String> columnas, List<String> ocultas) {
        if (o == null) return null;
        String c = String.valueOf(o).trim();
        if (c.isEmpty()) return null;
        int nivel = 0;
        boolean comilla = false;
        for (char ch : c.toCharArray()) {
            if (ch == '\'') comilla = !comilla;
            else if (!comilla && ch == '(') nivel++;
            else if (!comilla && ch == ')' && --nivel < 0) break;
        }
        if (nivel != 0 || comilla) throw error("workwith.condicion: paréntesis o comillas sin cerrar");
        return c;
    }

    private static Acciones acciones(Object o, Nivel cab, List<String> columnas, List<String> ocultas) {
        if (o == null) return Acciones.TODAS;
        if (!(o instanceof Map<?, ?> m0)) throw error("workwith.acciones debe ser {modificar: ..., borrar: ..., renglon: [...]}");
        Map<String, Object> m = (Map<String, Object>) m0;
        for (String k : m.keySet())
            if (!List.of("modificar", "visualizar", "borrar", "renglon", "inicio").contains(k))
                throw error("workwith.acciones." + k + " desconocido (modificar, visualizar, borrar, renglon, inicio)");
        java.util.function.BiFunction<Object, String, String> cond = (v, donde) -> {
            if (v == null || Boolean.TRUE.equals(v)) return null;
            if (Boolean.FALSE.equals(v)) return "false";
            String c = String.valueOf(v).trim();
            if (c.equalsIgnoreCase("true") || c.isEmpty()) return null;
            if (c.equalsIgnoreCase("false")) return "false";
            OptimistFormula f;
            try { f = OptimistFormula.de(c); }
            catch (IllegalArgumentException e) { throw error(donde + ": condición mal escrita '" + c + "': " + e.getMessage()); }
            // V50l: &Variable = del inicio del WorkWith (workwith.acciones.inicio); TrnMode.Update y parecidos: constantes
            for (String a : f.atributos()) if (!a.startsWith("&") && !a.contains(".")) traer(a, cab, columnas, ocultas, donde);
            return c;
        };
        List<AccionRenglon> rs = new ArrayList<>();
        if (m.get("renglon") instanceof List<?> l) {
            for (Object x : l) {
                if (!(x instanceof Map<?, ?> am)) throw error("workwith.acciones.renglon: acción inválida " + x);
                Map<String, Object> a = (Map<String, Object>) am;
                String titulo = texto(a, "titulo", null);
                if (titulo == null) throw error("workwith.acciones.renglon: falta 'titulo'");
                String donde = "workwith.acciones '" + titulo + "'";
                String tipo = null, obj = null;
                for (String t : List.of("proc", "panel", "trn", "cns"))   // V66k: cns = una consulta (consultas/), con lo que recibe
                    if (a.get(t) != null) {
                        if (tipo != null) throw error(donde + ": va 'proc', 'panel', 'trn' o 'cns', uno solo");
                        tipo = t; obj = ident(String.valueOf(a.get(t)).trim(), donde + "." + t);
                    }
                if (tipo == null) throw error(donde + ": falta qué hace ('proc: Procedimiento', 'panel: WebPanel', 'trn: Trn' o 'cns: Consulta')");
                List<String> ps = new ArrayList<>();
                for (String p : lista(a.get("parametros"))) {
                    String t = p.trim();
                    if (t.matches("[A-Za-z_][A-Za-z0-9_]*") && !t.equalsIgnoreCase("true") && !t.equalsIgnoreCase("false"))
                        t = traer(t, cab, columnas, ocultas, donde + ".parametros");
                    ps.add(t);
                }
                String modo = a.get("modo") == null ? (tipo.equals("trn") ? "Display" : null) : String.valueOf(a.get("modo")).trim();
                // V59: porRenglon (solo en workwith.botones): el botón corre para cada renglón tildado de la lista
                boolean porRenglon = Boolean.TRUE.equals(a.get("porRenglon"));
                if (porRenglon && !tipo.equals("proc")) throw error(donde + ": porRenglon va con 'proc'");
                rs.add(new AccionRenglon(titulo, opcional(a, "icono"), cond.apply(a.get("si"), donde + ".si"), tipo, obj, modo,
                        List.copyOf(ps), opcional(a, "confirmar"), porRenglon));
            }
        } else if (m.get("renglon") != null) throw error("workwith.acciones.renglon debe ser una lista");
        return new Acciones(cond.apply(m.get("modificar"), "workwith.acciones.modificar"), cond.apply(m.get("visualizar"), "workwith.acciones.visualizar"),
                cond.apply(m.get("borrar"), "workwith.acciones.borrar"), List.copyOf(rs),
                m.get("inicio") == null ? null : String.valueOf(m.get("inicio")));
    }

    /** Un campo que usa una acción: tiene que estar en la cabecera y se trae en la consulta del WorkWith. Devuelve su nombre. */
    private static String traer(String a, Nivel cab, List<String> columnas, List<String> ocultas, String donde) {
        Campo c = cab.campo(a);
        if (c == null) throw error(donde + ": '" + a + "' no es un campo de la cabecera");
        if (c.formula() != null && !c.guardar()) throw error(donde + ": '" + a + "' es fórmula que no se graba (el WorkWith no la trae)");
        if (columnas.stream().noneMatch(x -> x.equalsIgnoreCase(c.nombre())) && ocultas.stream().noneMatch(x -> x.equalsIgnoreCase(c.nombre())))
            ocultas.add(c.nombre());
        return c.nombre();
    }

    /** [[A, B], [C]] -> renglones; [A, B, C] -> un campo por elemento (sin renglones fijos). */
    private static List<List<String>> renglones(Object v, Nivel cabecera, String donde) {
        List<List<String>> r = new ArrayList<>();
        if (v == null) return r;
        if (!(v instanceof List<?> l)) throw error(donde + ": 'campos' debe ser una lista");
        Set<String> vistos = new HashSet<>();
        for (Object o : l) {
            List<String> fila = o instanceof List<?> sub ? sub.stream().map(String::valueOf).map(String::trim).toList() : List.of(String.valueOf(o).trim());
            for (String c : fila) {
                if (cabecera.campo(c) == null) throw error(donde + ": campo inexistente " + c);
                if (!vistos.add(c.toLowerCase(Locale.ROOT))) throw error(donde + ": campo repetido " + c);
            }
            r.add(fila);
        }
        return r;
    }

    /**
     * tablas:
     *   - titulo: Identificación          (opcional)
     *     columnas: [220px, 1fr]          (opcional)
     *     etiquetas: izquierda | arriba   (por defecto izquierda)
     *     anchoEtiquetas: 150px           (por defecto 150px)
     *     filas:
     *       - [ArticulosPK, ArticulosDescrip]
     *       - [-, ArticulosEstado]          "-" = celda vacía
     *       - [ArticulosBusqueda:2]         ":2" = ocupa dos columnas
     */
    @SuppressWarnings("unchecked")
    private static List<Tabla> tablas(Object v, Nivel cabecera, String donde) {
        List<Tabla> r = new ArrayList<>();
        if (v == null) return r;
        if (!(v instanceof List<?> l)) throw error(donde + ": 'tablas' debe ser una lista");
        Set<String> vistos = new HashSet<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> tm)) throw error(donde + ": tabla inválida");
            Map<String, Object> t = (Map<String, Object>) tm;
            List<String> cols = new ArrayList<>();
            for (String c : lista(t.get("columnas"))) {
                String a = c.trim();
                if (a.matches("\\d+")) a += "px";
                if (!a.matches("\\d+(\\.\\d+)?(px|%|fr|ch|em|rem)|auto")) throw error(donde + ": ancho de columna inválido '" + c + "'");
                cols.add(a);
            }
            String et = opcional(t, "etiquetas");
            boolean izq = et == null || et.equalsIgnoreCase("izquierda");
            if (et != null && !izq && !et.equalsIgnoreCase("arriba")) throw error(donde + ": etiquetas debe ser izquierda o arriba");
            String anchoEt = ancho(t.getOrDefault("anchoEtiquetas", "150px"), donde + ".anchoEtiquetas");
            List<List<Celda>> filas = new ArrayList<>();
            if (!(t.get("filas") instanceof List<?> lf)) throw error(donde + ": la tabla necesita 'filas'");
            for (Object fo : lf) {
                List<?> fl = fo instanceof List<?> x ? x : List.of(fo);
                List<Celda> fila = new ArrayList<>();
                for (Object co : fl) {
                    String txt = String.valueOf(co).trim();
                    int span = 1;
                    int dp = txt.lastIndexOf(':');
                    if (dp > 0) {
                        try { span = Integer.parseInt(txt.substring(dp + 1).trim()); }
                        catch (NumberFormatException e) { throw error(donde + ": celda inválida '" + txt + "'"); }
                        if (span < 1 || span > 12) throw error(donde + ": celda inválida '" + txt + "'");
                        txt = txt.substring(0, dp).trim();
                    }
                    if (txt.equals("-") || txt.isEmpty()) { fila.add(new Celda(null, span)); continue; }
                    Campo c = cabecera.campo(txt);
                    if (c == null) throw error(donde + ": campo inexistente " + txt);
                    if (!vistos.add(c.nombre().toLowerCase(Locale.ROOT))) throw error(donde + ": campo repetido " + txt);
                    fila.add(new Celda(c.nombre(), span));
                }
                filas.add(fila);
            }
            r.add(new Tabla(opcional(t, "titulo"), List.copyOf(cols), izq, anchoEt, List.copyOf(filas)));
        }
        return r;
    }

    private static String encabezado(String e, Nivel cabecera) {
        if (e == null) return null;
        Matcher m = Pattern.compile("\\{([^}]*)}").matcher(e);
        while (m.find()) {
            Campo c = cabecera.campo(m.group(1).trim());
            if (c == null) throw error("encabezado: el campo {" + m.group(1) + "} no existe en la cabecera");
        }
        return e;
    }

    record Pantalla(List<Solapa> solapas, List<Tabla> tablas, List<Bloque> bloques) {}

    /**
     * Diseño de pantalla como texto, una línea por renglón:
     *   <solapa Datos Generales>
     *     <tabla Identificación anchos=260px,1fr,1fr etiquetas=izquierda anchoEtiquetas=150px>
     *       ArticulosPK    ArticulosDescrip:2
     *       -              ArticulosEstado
     *     <endtable>
     *     <detalle ListasPrecios>
     *   <endsolapa>
     * Los campos se separan con espacios (o tabs). "-" = celda vacía, "Campo:2" = ocupa 2 columnas.
     * Renglones fuera de una <tabla> forman una tabla implícita. # = comentario.
     */
    static Pantalla pantalla(String texto, Nivel cabecera, List<Nivel> detalles) {
        // V33: contenedores anidados: raíz > solapa > panel/renglón > panel/renglón ...
        List<Solapa> solapas = new ArrayList<>();
        Deque<ContPantalla> pila = new ArrayDeque<>();
        ContPantalla raiz = new ContPantalla("raiz", null, true, null);
        pila.push(raiz);
        Set<String> vistos = new HashSet<>();
        Set<String> detVistos = new HashSet<>();
        Map<String, Object> tabla = null;          // tabla abierta (opciones + filas)
        List<List<Celda>> filas = null;
        boolean implicita = false;
        String[] lineas = texto.split("\\r?\\n");
        for (int i = 0; i <= lineas.length; i++) {
            String l = i < lineas.length ? lineas[i].trim() : "<fin>";
            String donde = "pantalla, línea " + (i + 1);
            if (l.isEmpty() || l.startsWith("#")) continue;
            boolean esMarca = l.startsWith("<") && l.endsWith(">");
            // cierre de tabla implícita cuando aparece una marca
            if (esMarca && implicita && tabla != null) {
                agregarTabla(pila, armarTabla(tabla, filas, donde));
                tabla = null; filas = null; implicita = false;
            }
            if (!esMarca) {
                if (tabla == null) { tabla = new LinkedHashMap<>(); filas = new ArrayList<>(); implicita = true; }
                List<Celda> fila = new ArrayList<>();
                for (String tok : l.split("\\s+")) {
                    int span = 1;
                    String t = tok;
                    int dp = t.lastIndexOf(':');
                    if (dp > 0) {
                        try { span = Integer.parseInt(t.substring(dp + 1)); } catch (NumberFormatException e) { throw error(donde + ": '" + tok + "' inválido"); }
                        if (span < 1 || span > 12) throw error(donde + ": '" + tok + "' inválido");
                        t = t.substring(0, dp);
                    }
                    if (t.equals("-")) { fila.add(new Celda(null, span)); continue; }
                    Campo c = cabecera.campo(t);
                    if (c == null) throw error(donde + ": el campo " + t + " no existe en la cabecera");
                    if (!vistos.add(c.nombre().toLowerCase(Locale.ROOT))) throw error(donde + ": " + t + " ya está en la pantalla");
                    fila.add(new Celda(c.nombre(), span));
                }
                filas.add(fila);
                continue;
            }
            String cuerpo = l.substring(1, l.length() - 1).trim();
            String marca0 = cuerpo.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            String resto0 = cuerpo.length() > marca0.length() ? cuerpo.substring(marca0.length()).trim() : "";
            // <fin panel> = <endpanel>, </panel> = <endpanel> (igual para tabla, solapa y renglon)
            if (marca0.equals("fin") && !resto0.isEmpty()) { marca0 = "end" + resto0.toLowerCase(Locale.ROOT).replace(" ", ""); resto0 = ""; }
            if (marca0.startsWith("/")) marca0 = "end" + marca0.substring(1);
            final String marca = marca0, resto = resto0;
            switch (marca) {
                case "fin" -> {
                    if (tabla != null) throw error("pantalla: falta <endtable>");
                    if (pila.size() > 1) throw error("pantalla: falta cerrar <" + pila.peek().tipo + (pila.peek().titulo == null ? "" : " " + pila.peek().titulo) + ">");
                }
                case "solapa" -> {
                    if (tabla != null) throw error(donde + ": falta <endtable>");
                    if (pila.size() > 1) throw error(donde + (pila.peek().tipo.equals("solapa") ? ": falta <endsolapa> de '" + pila.peek().titulo + "'"
                            : ": una <solapa> no puede ir dentro de un " + pila.peek().tipo));
                    if (resto.isEmpty()) throw error(donde + ": <solapa> necesita un título");
                    pila.push(new ContPantalla("solapa", resto, true, null));
                }
                case "endsolapa" -> {
                    if (tabla != null) throw error(donde + ": falta <endtable>");
                    ContPantalla so = pila.peek();
                    if (!so.tipo.equals("solapa")) throw error(donde + ": <endsolapa> sin <solapa>" + (so.tipo.equals("raiz") ? "" : " (falta cerrar el " + so.tipo + ")"));
                    pila.pop();
                    List<String> cs = new ArrayList<>();
                    for (Tabla t : so.tablas) cs.addAll(t.campos());
                    solapas.add(new Solapa(so.titulo, List.copyOf(cs), List.copyOf(so.detalles), List.of(), List.copyOf(so.tablas), List.copyOf(so.bloques)));
                }
                case "panel" -> {
                    if (tabla != null) throw error(donde + ": <panel> dentro de una tabla (falta <endtable>)");
                    boolean abierto = true, ocultarVacio = false; String ancho = null, alinear = null;
                    List<String> tit = new ArrayList<>();
                    for (String tok : resto.isEmpty() ? new String[0] : resto.split("\\s+")) {
                        String tl = tok.toLowerCase(Locale.ROOT);
                        if (tl.equals("cerrado")) abierto = false;
                        else if (tl.equals("abierto")) abierto = true;
                        else if (tl.equals("ocultarvacio")) ocultarVacio = true;   // V49t: no se ve si sus detalles no tienen renglones
                        else if (tl.startsWith("ancho=")) ancho = ancho(tok.substring(6), donde + ": ancho del panel");
                        else if (tl.startsWith("alinear=")) alinear = alineacion(tok.substring(8), donde);
                        else tit.add(tok);
                    }
                    ContPantalla nuevo = new ContPantalla("panel", tit.isEmpty() ? null : String.join(" ", tit), abierto, ancho);
                    nuevo.alinear = alinear;
                    nuevo.ocultarVacio = ocultarVacio;
                    pila.push(nuevo);
                }
                case "renglon", "renglón" -> {
                    if (tabla != null) throw error(donde + ": <renglon> dentro de una tabla (falta <endtable>)");
                    pila.push(new ContPantalla("renglon", null, true, null));
                }
                case "endpanel", "endrenglon", "endrenglón" -> {
                    if (tabla != null) throw error(donde + ": falta <endtable>");
                    String tipo = marca.equals("endpanel") ? "panel" : "renglon";
                    ContPantalla c = pila.peek();
                    if (!c.tipo.equals(tipo)) throw error(donde + ": <" + marca + "> sin <" + tipo + ">" + (c.tipo.equals("raiz") ? "" : " (falta cerrar el " + c.tipo + ")"));
                    pila.pop();
                    pila.peek().bloques.add(tipo.equals("panel") ? new Panel(c.titulo, c.abierto, c.ancho, List.copyOf(c.bloques), c.alinear, c.ocultarVacio) : new Renglon(List.copyOf(c.bloques)));
                }
                case "tabla", "table" -> {
                    if (tabla != null) throw error(donde + ": <tabla> dentro de otra tabla (falta <endtable>)");
                    tabla = new LinkedHashMap<>(); filas = new ArrayList<>();
                    List<String> titulo = new ArrayList<>();
                    for (String tok : resto.isEmpty() ? new String[0] : resto.split("\\s+")) {
                        int eq = tok.indexOf('=');
                        if (eq > 0) tabla.put(tok.substring(0, eq).toLowerCase(Locale.ROOT), tok.substring(eq + 1));
                        else titulo.add(tok);
                    }
                    if (!titulo.isEmpty()) tabla.put("titulo", String.join(" ", titulo));
                }
                case "endtable", "endtabla" -> {
                    if (tabla == null) throw error(donde + ": <endtable> sin <tabla>");
                    agregarTabla(pila, armarTabla(tabla, filas, donde));
                    tabla = null; filas = null;
                }
                case "detalle" -> {
                    if (tabla != null) throw error(donde + ": <detalle> dentro de una tabla (falta <endtable>)");
                    // V38: <detalle Det completo alinear=derecha>
                    String[] toks = resto.split("\\s+");
                    String nomDet = toks[0];
                    boolean completo = false, sinTitulo = false; String alinear = null;
                    for (int k = 1; k < toks.length; k++) {
                        String tl = toks[k].toLowerCase(Locale.ROOT);
                        if (tl.equals("completo")) completo = true;
                        else if (tl.equals("sintitulo")) sinTitulo = true;   // V65f: no muestra el título (ya lo dice el panel)
                        else if (tl.startsWith("alinear=")) alinear = alineacion(toks[k].substring(8), donde);
                        else throw error(donde + ": opción desconocida '" + toks[k] + "' en <detalle> (usar completo, sinTitulo o alinear=)");
                    }
                    Nivel d = detalles.stream().filter(x -> x.nombre().equalsIgnoreCase(nomDet)).findFirst()
                            .orElseThrow(() -> error(donde + ": no existe el detalle '" + nomDet + "'"));
                    if (!detVistos.add(d.nombre())) throw error(donde + ": el detalle " + d.nombre() + " ya está en la pantalla");
                    pila.peek().bloques.add(new BloqueDetalle(d.nombre(), completo, alinear, sinTitulo));
                    for (ContPantalla c : pila) if (c.tipo.equals("solapa") || c.tipo.equals("raiz")) { c.detalles.add(d.nombre()); break; }
                }
                default -> throw error(donde + ": marca desconocida <" + marca + "> (usar solapa, tabla, detalle, panel, renglon y sus cierres)");
            }
            if (marca.equals("fin")) break;
        }
        return new Pantalla(List.copyOf(solapas), List.copyOf(raiz.tablas), List.copyOf(raiz.bloques));
    }

    /** V33: contenedor abierto mientras se lee la pantalla: raíz, solapa, panel o renglón. */
    private static final class ContPantalla {
        final String tipo, titulo, ancho; final boolean abierto;
        String alinear;
        boolean ocultarVacio;
        final List<Bloque> bloques = new ArrayList<>();
        final List<Tabla> tablas = new ArrayList<>();        // solapa y raíz: todas sus tablas, aplanadas
        final List<String> detalles = new ArrayList<>();
        ContPantalla(String tipo, String titulo, boolean abierto, String ancho) { this.tipo = tipo; this.titulo = titulo; this.abierto = abierto; this.ancho = ancho; }
    }

    /** V33: la tabla va al contenedor abierto (en orden) y a la lista aplanada de su solapa (o de la raíz). */
    private static void agregarTabla(Deque<ContPantalla> pila, Tabla t) {
        pila.peek().bloques.add(new BloqueTabla(t));
        for (ContPantalla c : pila) if (c.tipo.equals("solapa") || c.tipo.equals("raiz")) { c.tablas.add(t); return; }
    }

    /** izquierda, centro o derecha (acepta left/center/right). */
    private static String alineacion(String v, String donde) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "izquierda", "left" -> "izquierda";
            case "centro", "center", "centrada" -> "centro";
            case "derecha", "right" -> "derecha";
            default -> throw error(donde + ": alinear debe ser izquierda, centro o derecha");
        };
    }

    private static Tabla armarTabla(Map<String, Object> op, List<List<Celda>> filas, String donde) {
        List<String> cols = new ArrayList<>();
        for (String c : lista(op.get("anchos"))) {
            String a = c.trim();
            if (a.matches("\\d+")) a += "px";
            if (!a.matches("\\d+(\\.\\d+)?(px|%|fr|ch|em|rem)|auto")) throw error(donde + ": ancho de columna inválido '" + c + "'");
            cols.add(a);
        }
        String et = op.get("etiquetas") == null ? null : String.valueOf(op.get("etiquetas"));
        boolean izq = et == null || et.equalsIgnoreCase("izquierda");
        if (et != null && !izq && !et.equalsIgnoreCase("arriba")) throw error(donde + ": etiquetas debe ser izquierda o arriba");
        String anchoEt = ancho(op.getOrDefault("anchoetiquetas", "150px"), donde + ": anchoEtiquetas");
        String al = op.get("alinear") == null ? null : alineacion(String.valueOf(op.get("alinear")), donde);
        return new Tabla(op.get("titulo") == null ? null : String.valueOf(op.get("titulo")), List.copyOf(cols), izq, anchoEt, List.copyOf(filas), al);
    }

    /** V24: 'renglones: N' (1 a 30), solo para campos de texto. 0 = automático. */
    /**
     * V33: estilo del campo (etiqueta y contenido): {negrita: true, italica: true, tamaño: 16px, color: "#b00020"}.
     * Tiene prioridad sobre Apariencia. Se normaliza a propiedades CSS.
     */
    private static Map<String, String> estilo(Object v, String campo) {
        if (v == null) return Map.of();
        if (!(v instanceof Map<?, ?> m)) throw error(campo + ": 'estilo' debe ser {negrita: true, italica: true, letra: Arial, tamaño: \"+2px\", color: \"30,100,180\"}");
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT).replace('ñ', 'n').replace('á', 'a');
            String val = String.valueOf(e.getValue()).trim();
            switch (k) {
                // V45: 'negrita: false' / 'italica: false' sirven para sacarle a un campo lo que le pone su clase
                case "negrita" -> r.put("font-weight", bool(e.getValue(), false) ? "700" : "400");
                case "italica" -> r.put("font-style", bool(e.getValue(), false) ? "italic" : "normal");
                case "tamano" -> r.put("font-size", tamanoCss(e.getValue(), campo + ": estilo.tamaño"));
                case "color" -> r.put("color", colorCss(val, campo + ": estilo.color"));
                case "letra" -> r.put("font-family", letraCss(val, campo + ": estilo.letra"));
                default -> throw error(campo + ": estilo." + e.getKey() + " desconocido (negrita, italica, letra, tamaño, color)");
            }
        }
        return Map.copyOf(r);
    }

    /** V46: letra que eligió el usuario en Apariencia (la publica OptimistVisuals.aplicarPaletaGlobal). */
    static final String LETRA_USUARIO = "var(--optimist-letra, 15px)";

    /**
     * V46: tamaño de letra de un campo, RELATIVO a la letra del usuario (así un campo destacado sigue más grande
     * aunque el usuario agrande su letra en Apariencia):
     *   "+4px" / "-2px"  suma o resta a la letra del usuario
     *   "125%"           proporcional a la letra del usuario (destaca igual con cualquier tamaño)
     *   "20px"           fijo (no cambia con la letra del usuario); también em / rem
     * Un número suelto de 9 en adelante es fijo en px (como antes); negativo, relativo. "+4" sin comillas YAML lo
     * lee como 4: por eso del 0 al 8 se pide escribirlo como "+4px".
     */
    static String tamanoCss(Object v, String donde) {
        String ej = " (ej. \"+4px\", \"-2px\", \"125%\" o \"20px\" fijo)";
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d < 0) return relativo(d, donde, ej);
            if (d >= 9 && d <= 200) return numero(d) + "px";
            throw error(donde + " " + numero(d) + " no se entiende: para agrandar respecto de la letra del usuario va con signo y comillas, \"+"
                    + numero(d) + "px\"" + ej);
        }
        String s = String.valueOf(v).trim().replace(" ", "").replace(',', '.').toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m;
        if ((m = java.util.regex.Pattern.compile("([+-]\\d{1,2}(?:\\.\\d+)?)(px)?").matcher(s)).matches())
            return relativo(Double.parseDouble(m.group(1)), donde, ej);
        if ((m = java.util.regex.Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)%").matcher(s)).matches()) {
            double p = Double.parseDouble(m.group(1));
            if (p < 50 || p > 300) throw error(donde + " '" + v + "' fuera de rango (de 50% a 300%)");
            return p == 100 ? LETRA_USUARIO : "calc(" + LETRA_USUARIO + " * " + numero(p / 100) + ")";
        }
        if (s.matches("\\d{1,3}(\\.\\d+)?")) return tamanoCss(Double.parseDouble(s), donde);
        if (s.matches("\\d{1,3}(\\.\\d+)?(px|em|rem)")) return s;
        throw error(donde + " inválido '" + v + "'" + ej);
    }

    private static String relativo(double d, String donde, String ej) {
        if (Math.abs(d) > 30) throw error(donde + " '" + (d > 0 ? "+" : "") + numero(d) + "px' fuera de rango (de -30px a +30px)" + ej);
        if (d == 0) return LETRA_USUARIO;
        return "calc(" + LETRA_USUARIO + (d > 0 ? " + " : " - ") + numero(Math.abs(d)) + "px)";
    }

    private static String numero(double d) {
        return new java.math.BigDecimal(String.valueOf(d)).stripTrailingZeros().toPlainString();
    }

    /** V45: color como "#1e64b4", "30,100,180", "rgb(30,100,180)" o un nombre (red, navy...). */
    static String colorCss(String val, String donde) {
        String s = val.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)(?:rgb\\s*\\(\\s*)?(\\d{1,3})\\s*[,; ]\\s*(\\d{1,3})\\s*[,; ]\\s*(\\d{1,3})\\s*\\)?").matcher(s);
        if (m.matches()) {
            int r = Integer.parseInt(m.group(1)), g = Integer.parseInt(m.group(2)), b = Integer.parseInt(m.group(3));
            if (r > 255 || g > 255 || b > 255) throw error(donde + " inválido '" + val + "' (cada número va de 0 a 255)");
            return String.format("#%02x%02x%02x", r, g, b);
        }
        if (s.matches("#[0-9A-Fa-f]{3,8}|[A-Za-z]{3,30}")) return s;
        throw error(donde + " inválido '" + val + "' (ej. \"30,100,180\", \"#b00020\" o red)");
    }

    /** V45: letra (familia): "Arial", "Courier New", o varias separadas por coma (la primera que tenga la PC). */
    static String letraCss(String val, String donde) {
        List<String> r = new ArrayList<>();
        for (String p : val.split(",")) {
            String n = p.trim().replaceAll("^['\"]|['\"]$", "");
            if (n.isEmpty()) continue;
            if (!n.matches("[\\p{L}0-9 _.-]{1,60}")) throw error(donde + " inválida '" + val + "' (ej. Arial, \"Courier New\")");
            r.add(List.of("serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui").contains(n.toLowerCase(Locale.ROOT))
                    ? n.toLowerCase(Locale.ROOT) : "'" + n + "'");
        }
        if (r.isEmpty()) throw error(donde + " vacía");
        return String.join(", ", r);
    }

    /**
     * V45: clases de campo. 'clases:' en dominios.yaml (para todas las TRN) o en la TRN (manda sobre la global):
     *   clases:
     *     Cabecera: {color: "30,100,180"}       # se aplica sola a todos los campos de la cabecera
     *     Detalle: {tamaño: 13}                 # se aplica sola a todos los campos de los detalles
     *     Importe: {negrita: true, letra: Consolas}
     * Un campo usa 'clase: Importe' (o varias: [Importe, Rojo]); la TRN o un detalle pueden cambiar la clase que
     * se aplica sola con 'clase: OtraClase' (o 'clase: ninguna'). Orden: clase del nivel < clase del campo < estilo.
     */
    @SuppressWarnings("unchecked")
    /**
     * V49u: lupa propia de un campo, armada por un procedimiento (como un prompt de GeneXus con grilla calculada, ej.
     * PromptArticulosListasPreciosModificado): el procedimiento recibe 'parametros' (campos de la línea o de la cabecera,
     * o &variables de las reglas) y devuelve una colección; la lupa muestra 'columnas' y al elegir copia 'devuelve'.
     */
    /** V55: procesos: columna -> proceso (GetUsrProc) que tiene que estar habilitado para verla (ej. BultosStock: 156). */
    public record LupaProc(String proc, List<String> parametros, String titulo, LinkedHashMap<String, String> columnas,
                           LinkedHashMap<String, String> devuelve, Map<String, Integer> procesos) {}

    /** V49u: combo con los registros de una tabla (el Dynamic Combo Box de GeneXus): se guarda 'valor', se ve 'texto'. */
    public record ComboTabla(String tabla, String valor, String texto, boolean vacio, String donde, String vacioTexto) {
        /** V66t: el SELECT de los registros del combo ('donde': condición SQL fija, ej. "ModeloCbtesCPI = 'C'"). */
        public String sql() {
            return "SELECT `" + valor + "`, `" + texto + "` FROM `" + tabla + "`" + (donde == null || donde.isBlank() ? "" : " WHERE " + donde)
                    + " ORDER BY `" + texto + "`";
        }
    }

    private static final Map<String, ComboTabla> COMBOS = new java.util.concurrent.ConcurrentHashMap<>();

    /** V49u: el combo de tabla del campo de esa tabla (la del nivel), o null. */
    public static ComboTabla comboTabla(String tabla, String campo) {
        return COMBOS.get(tabla.toLowerCase(Locale.ROOT) + "." + campo.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    static ComboTabla comboTabla(Object v, String donde) {
        if (!(v instanceof Map<?, ?> m0)) throw error(donde + ": va {tabla: ..., valor: ..., texto: ...}");
        Map<String, Object> m = (Map<String, Object>) m0;
        // V66t: 'donde' (condición fija) y 'vacioTexto' (lo que se ve sin elegir, ej. "(Ninguno)")
        String cond = m.get("donde") == null ? null : String.valueOf(m.get("donde"));
        if (cond != null && (cond.contains(";") || cond.contains("--") || cond.contains("/*")))
            throw error(donde + ".donde: una condición simple (sin ; ni comentarios)");
        return new ComboTabla(ident(texto(m, "tabla", null), donde + ".tabla"), ident(texto(m, "valor", null), donde + ".valor"),
                ident(texto(m, "texto", null), donde + ".texto"), bool(m.get("vacio"), true), cond,
                m.get("vacioTexto") == null ? null : String.valueOf(m.get("vacioTexto")));
    }

    /** V50b: campos 'pantalla: true' (tabla.campo): inferidos que llenan las reglas, sin referencia. */
    private static final Set<String> SOLO_PANTALLA = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> SIGUIENTE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> REF_OPCIONAL = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<String, LinkedHashMap<String, Object>> REF_DONDE_LUPA = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> REF_MENSAJE = new java.util.concurrent.ConcurrentHashMap<>();
    /** V65: las condiciones de 'dondeLupa' de la referencia como SQL (con el alias dado). Vacío si no tiene. */
    public static List<String> condicionesLupa(String tablaNivel, Referencia r, String alias) {
        LinkedHashMap<String, Object> dl = REF_DONDE_LUPA.get(claveOpcional(tablaNivel, r.tabla(), r.campos().keySet()));
        if (dl == null || dl.isEmpty()) return List.of();
        return new Referencia(r.tabla(), r.campos(), r.trae(), r.descripcion(), dl, r.codigo()).condicionesFijas(alias);
    }
    /** V65: el 'mensaje' de la referencia (cuando existe pero no cumple el 'donde'), o null. */
    public static String mensajeReferencia(String tablaNivel, Referencia r) {
        return REF_MENSAJE.get(claveOpcional(tablaNivel, r.tabla(), r.campos().keySet()));
    }
    private static String claveOpcional(String tablaNivel, String tablaRef, Collection<String> locales) {
        return (tablaNivel + ">" + tablaRef + ":" + String.join(",", locales)).toLowerCase(Locale.ROOT);
    }
    /** V65: la referencia tiene 'opcional: true' (0 o vacío = sin valor, no se valida). */
    public static boolean referenciaOpcional(String tablaNivel, Referencia r) {
        return REF_OPCIONAL.contains(claveOpcional(tablaNivel, r.tabla(), r.campos().keySet()));
    }

    /** V65: la clave numérica con 'siguiente: true' (en el alta se propone el último + 1). */
    public static boolean siguiente(String tabla, String campo) {
        return SIGUIENTE.contains(tabla.toLowerCase(Locale.ROOT) + "." + campo.toLowerCase(Locale.ROOT));
    }
    /** V65: ídem por el nombre del campo solo (los nombres de atributo de GeneXus no se repiten entre tablas). */
    public static boolean siguienteCampo(Campo c) {
        String fin = "." + c.nombre().toLowerCase(Locale.ROOT);
        return SIGUIENTE.stream().anyMatch(x -> x.endsWith(fin));
    }

    /** V50c: enlace: TRN = el valor del campo es la clave de esa TRN; se ve como vínculo y al tocarlo la abre (PRSPEDId -> PED). */
    /** V50e: trn a abrir y el campo con su clave (null = el valor del mismo campo): enlace: PED o {trn: PED, clave: PRSPEDId}. */
    public record Enlace(String trn, String clave) {}
    /**
     * V66k: una lupa del formulario que abre una consulta (consultas/) con datos del registro. En la cabecera ('ver:' de la
     * TRN) va arriba del formulario; en un detalle ('ver:' del detalle), en cada renglón. parametros: los campos (del nivel o
     * de la cabecera) que recibe la consulta, en el orden de su 'recibe:'. si: condición opcional (con los valores del
     * registro; en un renglón, los de la cabecera y los del renglón). Ej. Asientos: "Ver imputaciones del asiento".
     */
    public record Ver(String titulo, String consulta, List<String> parametros, String si) {}

    private static final Map<String, List<Ver>> VER = new java.util.concurrent.ConcurrentHashMap<>();

    /** V66k: las lupas 'ver:' de un nivel (la cabecera: su nombre es el de la TRN). Lista vacía si no tiene. */
    public static List<Ver> ver(String trn, String nivel) {
        return VER.getOrDefault((trn + "." + nivel).toLowerCase(Locale.ROOT), List.of());
    }

    @SuppressWarnings("unchecked")
    private static void leerVer(String trn, Nivel n, Nivel cab, Object def, String donde) {
        String k = (trn + "." + n.nombre()).toLowerCase(Locale.ROOT);
        VER.remove(k);
        if (def == null) return;
        if (!(def instanceof List<?> l)) throw error(donde + " debe ser una lista: - {titulo: ..., cns: Consulta, parametros: [Campo]}");
        List<Ver> r = new ArrayList<>();
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m0)) throw error(donde + ": inválido " + o);
            Map<String, Object> m = (Map<String, Object>) m0;
            String titulo = texto(m, "titulo", null);
            if (titulo == null) throw error(donde + ": falta 'titulo'");
            String cns = m.get("cns") == null ? null : ident(String.valueOf(m.get("cns")).trim(), donde + ".cns");
            if (cns == null) throw error(donde + " '" + titulo + "': falta 'cns' (la consulta que abre)");
            List<String> ps = new ArrayList<>();
            for (String p : lista(m.get("parametros"))) {
                Campo c = n.campo(p.trim());
                if (c == null && cab != null) c = cab.campo(p.trim());
                if (c == null) throw error(donde + " '" + titulo + "'.parametros: '" + p.trim() + "' no es un campo");
                ps.add(c.nombre());
            }
            String si = m.get("si") == null ? null : String.valueOf(m.get("si")).trim();
            if (si != null) try { OptimistFormula.de(si); } catch (IllegalArgumentException ex) { throw error(donde + " '" + titulo + "'.si: " + ex.getMessage()); }
            r.add(new Ver(titulo, cns, List.copyOf(ps), si));
        }
        if (!r.isEmpty()) VER.put(k, List.copyOf(r));
    }

    private static final Map<String, Enlace> ENLACES = new java.util.concurrent.ConcurrentHashMap<>();
    public static Enlace enlace(String tabla, String campo) {
        return ENLACES.get(tabla.toLowerCase(Locale.ROOT) + "." + campo.toLowerCase(Locale.ROOT));
    }
    @SuppressWarnings("unchecked")
    private static Enlace enlaceDe(Object v, String donde) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> x = (Map<String, Object>) m;
            return new Enlace(ident(texto(x, "trn", null), donde + ".enlace.trn"),
                    x.get("clave") == null ? null : ident(String.valueOf(x.get("clave")).trim(), donde + ".enlace.clave"));
        }
        return new Enlace(ident(String.valueOf(v).trim(), donde + ".enlace"), null);
    }

    private static final Map<String, LupaProc> LUPAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** V50x: estilo de las líneas de un detalle que cumplen una condición (ej. los totalizadores del itemizado en negrita). */
    /** campos: null = toda la línea (V65c: solo esos campos). */
    public record EstiloRenglon(String si, Map<String, String> estilo, Set<String> campos) {
        public boolean aplica(String campo) { return campos == null || (campo != null && campos.contains(campo)); }
    }
    private static final Map<String, EstiloRenglon> ESTILOS_RENGLON = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, List<EstiloRenglon>> ESTILOS_DETALLE = new java.util.concurrent.ConcurrentHashMap<>();
    /** V50x: los estiloRenglon del detalle de esa tabla (V65c: puede haber varios), o lista vacía. */
    public static List<EstiloRenglon> estilosRenglon(String tabla) { return tabla == null ? List.of() : ESTILOS_DETALLE.getOrDefault(tabla.toLowerCase(Locale.ROOT), List.of()); }
    /** V61h: el estiloRenglon del WorkWith de la TRN de esa tabla, o null. */
    public static EstiloRenglon estiloRenglonWorkWith(String tabla) { return tabla == null ? null : ESTILOS_RENGLON.get("ww:" + tabla.toLowerCase(Locale.ROOT)); }

    /** V49u: la lupa propia del campo de esa tabla (la del nivel), o null. */
    public static LupaProc lupaProc(String tabla, String campo) {
        return LUPAS.get(tabla.toLowerCase(Locale.ROOT) + "." + campo.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static LupaProc lupaProc(Object v, String donde) {
        if (!(v instanceof Map<?, ?> m0)) throw error(donde + ": va {proc: ..., parametros: [...], columnas: {...}, devuelve: {...}}");
        Map<String, Object> m = (Map<String, Object>) m0;
        String proc = ident(texto(m, "proc", null), donde + ".proc");
        List<String> ps = new ArrayList<>();
        for (String x : lista(m.get("parametros"))) {
            String t = x.trim();
            if (!t.matches("&?[A-Za-z_]\\w*")) throw error(donde + ".parametros: '" + t + "' tiene que ser un campo o una &variable");
            ps.add(t);
        }
        LinkedHashMap<String, String> cols = new LinkedHashMap<>();
        if (!(m.get("columnas") instanceof Map<?, ?> cm)) throw error(donde + ": faltan las columnas {Campo: Título, ...}");
        cm.forEach((k, t) -> cols.put(ident(String.valueOf(k), donde + ".columnas"), String.valueOf(t)));
        LinkedHashMap<String, String> dev = mapaIdent(m.get("devuelve"), donde + ".devuelve");
        if (dev.isEmpty()) throw error(donde + ": falta 'devuelve' {CampoDeLaTrn: CampoDeLaLupa, ...}");
        Map<String, Integer> procs = new LinkedHashMap<>();
        if (m.get("procesos") instanceof Map<?, ?> pm)
            pm.forEach((k, x) -> procs.put(ident(String.valueOf(k), donde + ".procesos"), Integer.parseInt(String.valueOf(x).trim())));
        return new LupaProc(proc, List.copyOf(ps), opcional(m, "titulo"), cols, dev, procs);
    }

    /** V49t: las clases de cada TRN (dominios.yaml + las suyas), para las reglas X.Class = "Clase". */
    private static final Map<String, Map<String, Map<String, String>>> CLASES = new java.util.concurrent.ConcurrentHashMap<>();

    /** V49t: el estilo de una clase en una TRN, o null si no existe. */
    public static Map<String, String> estiloDeClase(String trn, String clase) {
        Map<String, Map<String, String>> cs = CLASES.get(trn.toLowerCase(Locale.ROOT));
        return cs == null ? null : cs.get(clase.trim());
    }

    /** V66: las clases que ve un web panel (dominios.yaml + las suyas). */
    public static Map<String, Map<String, String>> clasesDePanel(Map<String, Object> y, Map<String, Object> dominiosGlobales, String nombre) {
        Map<String, Map<String, String>> clases = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        leerClases(dominiosGlobales, clases, "dominios.yaml");
        leerClases(y, clases, nombre);
        return clases;
    }

    private static void leerClases(Map<String, Object> origen, Map<String, Map<String, String>> destino, String donde) {
        if (origen == null || origen.get("clases") == null) return;
        if (!(origen.get("clases") instanceof Map<?, ?> m)) throw error(donde + ": 'clases' debe ser {Nombre: {negrita: true, color: ...}, ...}");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            String n = String.valueOf(e.getKey()).trim();
            if (!n.matches("[A-Za-z_][A-Za-z0-9_]{0,40}")) throw error(donde + ": nombre de clase inválido '" + n + "'");
            destino.remove(n); destino.put(n, estilo(e.getValue(), "clase " + n));
        }
    }

    private static Map<String, String> estiloDeCampo(Map<String, Object> c, String n, Map<String, Map<String, String>> clases, String claseNivel) {
        Map<String, String> r = new LinkedHashMap<>();
        if (claseNivel != null && clases.containsKey(claseNivel)) r.putAll(clases.get(claseNivel));
        for (String k : lista(c.get("clase"))) {
            Map<String, String> cl = clases.get(k.trim());
            if (cl == null) throw error(n + ": clase desconocida '" + k.trim() + "'"
                    + (clases.isEmpty() ? " (no hay 'clases:' definidas)" : " (hay: " + String.join(", ", clases.keySet()) + ")"));
            r.putAll(cl);
        }
        r.putAll(estilo(c.get("estilo"), n));
        return Map.copyOf(r);
    }

    /** Clase que se aplica sola a un nivel: la que diga 'clase:' (ninguna = sin clase) o la de nombre 'porDefecto'. */
    private static String claseNivel(Map<String, Object> y, String porDefecto, Map<String, Map<String, String>> clases, String donde) {
        if (y.get("clase") == null) return porDefecto;
        String k = String.valueOf(y.get("clase")).trim();
        if (k.equalsIgnoreCase("ninguna")) return null;
        if (!clases.containsKey(k)) throw error(donde + ": clase desconocida '" + k + "'");
        return k;
    }

    private static int renglones(Object v, String campo, Dominio dom) {
        if (v == null) return 0;
        int r;
        try { r = Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { throw error(campo + ": 'renglones' tiene que ser un número (1 a 30)"); }
        if (r < 1 || r > 30) throw error(campo + ": 'renglones' tiene que ser de 1 a 30");
        if (!dom.esTexto() || dom.esCombo()) throw error(campo + ": 'renglones' es solo para campos de texto");
        return r;
    }

    private static String ancho(Object v, String campo) {
        if (v == null) return null;
        String a = String.valueOf(v).trim();
        if (a.matches("\\d+")) a = a + "px";
        if (!a.matches("\\d+(\\.\\d+)?(px|%|em|rem|ch)")) throw error(campo + ": ancho inválido '" + v + "' (ej: 250px, 30%, 20ch)");
        return a;
    }

    private static void validarFormulas(Nivel n, Nivel cabecera, List<Nivel> detalles) {
        for (Campo c : n.campos()) {
            if (c.formula() == null || !c.guardar()) continue;
            OptimistFormula f;
            try { f = OptimistFormula.de(c.formula()); }
            catch (IllegalArgumentException e) { throw error(c.nombre() + ": " + e.getMessage()); }
            for (String a : f.atributos())
                if (n.campo(a) == null && (cabecera == null || cabecera.campo(a) == null)
                        && detalles.stream().noneMatch(d -> d.campo(a) != null))
                    throw error(c.nombre() + ": la fórmula usa " + a + ", que no es un campo de la TRN");
        }
    }

    /**
     * V47: 'codigos:' (en dominios.yaml o en la TRN): tabla -> {codigo: Columna, segun: Columna}. También 'Articulos: ArticulosId'.
     */
    @SuppressWarnings("unchecked")
    private static void leerCodigos(Map<String, Object> origen, Map<String, Codigo> destino, String donde) {
        if (origen == null || origen.get("codigos") == null) return;
        if (!(origen.get("codigos") instanceof Map<?, ?> m)) throw error(donde + ": 'codigos' debe ser {Tabla: {codigo: Columna}, ...}");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            String t = ident(String.valueOf(e.getKey()).trim(), donde + ".codigos");
            Object v = e.getValue();
            if (v instanceof String s0) { destino.put(t, new Codigo(ident(s0.trim(), donde + ".codigos." + t), null)); continue; }
            if (!(v instanceof Map<?, ?> cm)) throw error(donde + ".codigos." + t + ": debe ser {codigo: Columna} o {codigo: Columna, segun: Columna}");
            Object col = cm.get("codigo");
            if (col == null) throw error(donde + ".codigos." + t + ": falta 'codigo'");
            Object seg = cm.get("segun");
            for (Object k : cm.keySet())
                if (!String.valueOf(k).equals("codigo") && !String.valueOf(k).equals("segun"))
                    throw error(donde + ".codigos." + t + "." + k + " desconocido (codigo, segun)");
            destino.put(t, new Codigo(ident(String.valueOf(col).trim(), donde + ".codigos." + t + ".codigo"),
                    seg == null ? null : ident(String.valueOf(seg).trim(), donde + ".codigos." + t + ".segun")));
        }
    }

    /** V26: 'donde: {Columna: constante, ...}' de una referencia. V47: o {campo: CampoDeLaTrn, C: 1, P: 2}. */
    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> donde(Object o, String tabla) {
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        if (o == null) return r;
        if (!(o instanceof Map<?, ?> m)) throw error("referencia " + tabla + ": 'donde' tiene que ser {Columna: valor}");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            String col = ident(String.valueOf(e.getKey()), "referencia " + tabla + ".donde");
            Object v = e.getValue();
            if (v instanceof Map<?, ?> dm) {   // V47: {campo: PRSCPI, C: 1, P: 2}
                Map<String, Object> vals = new LinkedHashMap<>();
                String campo = null;
                for (Map.Entry<?, ?> x : dm.entrySet()) {
                    String k = String.valueOf(x.getKey()).trim();
                    if (k.equalsIgnoreCase("campo")) campo = ident(String.valueOf(x.getValue()).trim(), "referencia " + tabla + ".donde." + col + ".campo");
                    else if (x.getValue() instanceof Number || x.getValue() instanceof String || x.getValue() instanceof Boolean) vals.put(k, x.getValue());
                    else throw error("referencia " + tabla + ".donde." + col + "." + k + ": tiene que ser un número, un texto o true/false");
                }
                if (campo == null) throw error("referencia " + tabla + ".donde." + col + ": falta 'campo' (ej. {campo: TipoComprobante, C: 1, P: 2})");
                r.put(col, new DondeCampo(campo, Map.copyOf(vals)));
                continue;
            }
            if (!(v instanceof Number || v instanceof String || v instanceof Boolean))
                throw error("referencia " + tabla + ".donde." + col + ": tiene que ser un número, un texto o true/false");
            r.put(col, v);
        }
        return r;
    }

    private static void validarReferencias(Nivel n, Nivel cabecera) {
        Set<String> inferidosCubiertos = new HashSet<>();
        for (Referencia r : n.referencias()) {
            for (String local : r.campos().keySet())
                if (n.campo(local) == null && (cabecera == null || cabecera.campo(local) == null))
                    throw error("referencia " + r.tabla() + ": el campo " + local + " no existe en " + n.nombre());
            for (Object v : r.donde().values())   // V47
                if (v instanceof DondeCampo dc && n.campo(dc.campo()) == null && (cabecera == null || cabecera.campo(dc.campo()) == null))
                    throw error("referencia " + r.tabla() + ": donde usa el campo " + dc.campo() + ", que no existe en " + n.nombre());
            for (String local : r.trae().keySet()) {
                Campo c = n.campo(local);
                if (c == null) throw error("referencia " + r.tabla() + ": trae " + local + ", que no es campo de " + n.nombre());
                if (c.inferido()) inferidosCubiertos.add(c.nombre());
            }
        }
        for (Campo c : n.campos())
            if (c.inferido() && !inferidosCubiertos.contains(c.nombre())
                    && !SOLO_PANTALLA.contains(n.tabla().toLowerCase(Locale.ROOT) + "." + c.nombre().toLowerCase(Locale.ROOT)))
                throw error(c.nombre() + " es inferido pero ninguna referencia lo trae");
    }

    private static void validarDetalle(Nivel cab, Nivel det) {
        for (Campo c : det.campos())
            if (cab.campo(c.nombre()) != null)
                throw error("detalle " + det.nombre() + ": " + c.nombre() + " ya es campo de la cabecera (la clave de la cabecera se agrega sola)");
        if (det.numerador() != null) {
            if (det.claves().size() != 1 || !det.claves().get(0).dominio().esEntero())
                throw error("detalle " + det.nombre() + ".numerador exige una única clave numérica entera en el detalle");
            if (cab.campo(det.numerador()) != null)
                throw error("detalle.numerador: " + det.numerador() + " es una columna interna, no la declares como campo");
        }
    }

    // ------------------------------------------------------------------ dominios

    @SuppressWarnings("unchecked")
    private static void leerDominios(Map<String, Object> origen, Map<String, Dominio> destino) {
        if (origen == null) return;
        Object bloque = origen.containsKey("dominios") ? origen.get("dominios") : origen;
        if (!(bloque instanceof Map<?, ?> m)) return;
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet())
            destino.put(e.getKey().toLowerCase(Locale.ROOT), dominio(e.getKey(), e.getValue()));
    }

    /** Acepta "Numeric(15.4)", "Character(3)", "Date"... o un bloque {tipo, longitud, decimales, valores}. */
    @SuppressWarnings("unchecked")
    static Dominio dominio(String nombre, Object def) {
        if (def instanceof Map<?, ?> m) {
            Map<String, Object> d = (Map<String, Object>) m;
            Object[] base = tipoCrudo(nombre, texto(d, "tipo", null));
            int lon = entero(d.get("longitud"), (Integer) base[1]);
            int dec = entero(d.get("decimales"), (Integer) base[2]);
            boolean miles = bool(d.get("miles"), (Boolean) base[3]);
            Dominio sin = armar(nombre, (Tipo) base[0], lon, dec, null);
            List<Valor> valores = null;
            if (d.get("valores") instanceof List<?> lv) {
                valores = new ArrayList<>();
                for (Object o : lv) {
                    if (!(o instanceof Map<?, ?> vm)) throw error("dominio " + nombre + ": valor inválido " + o);
                    Map<String, Object> v = (Map<String, Object>) vm;
                    if (v.get("valor") == null) throw error("dominio " + nombre + ": falta 'valor'");
                    valores.add(new Valor(normalizar(sin, v.get("valor")), String.valueOf(v.getOrDefault("texto", v.get("valor")))));
                }
            }
            Dominio r = armar(nombre, sin.tipo(), sin.longitud(), sin.decimales(), valores == null ? null : List.copyOf(valores));
            return miles && r.esNumerico() ? r.conMiles() : r;
        }
        Object[] t = tipoCrudo(nombre, def);
        Dominio r = armar(nombre, (Tipo) t[0], (Integer) t[1], (Integer) t[2], null);
        return (Boolean) t[3] && r.esNumerico() ? r.conMiles() : r;
    }

    private static Object[] tipoCrudo(String nombre, Object def) {
        if (def == null) throw error("dominio " + nombre + ": falta el tipo");
        // V33: "Numeric(14.2) miles" = con punto de miles
        String txt = String.valueOf(def).trim();
        boolean miles = txt.toLowerCase(Locale.ROOT).endsWith(" miles");
        if (miles) txt = txt.substring(0, txt.length() - 6).trim();
        Matcher mt = TIPO_GX.matcher(txt);
        if (!mt.matches()) throw error("dominio " + nombre + ": tipo no reconocido '" + def + "'");
        Tipo tipo = switch (mt.group(1).toLowerCase(Locale.ROOT)) {
            case "char", "character", "c" -> Tipo.CHAR;
            case "varchar", "longvarchar", "string", "text" -> Tipo.VARCHAR;
            case "numeric", "number", "decimal", "n", "int", "integer" -> Tipo.NUMERIC;
            case "date", "fecha" -> Tipo.DATE;
            case "datetime", "timestamp" -> Tipo.DATETIME;
            case "boolean", "bool" -> Tipo.BOOLEAN;
            case "guid", "uuid" -> Tipo.CHAR;   // V83: el GUID de GeneXus: texto de 36 ("xxxxxxxx-xxxx-...")
            default -> throw error("dominio " + nombre + ": tipo no soportado '" + mt.group(1) + "'");
        };
        int lon = mt.group(2) == null ? 0 : Integer.parseInt(mt.group(2));
        if (mt.group(1).equalsIgnoreCase("guid") || mt.group(1).equalsIgnoreCase("uuid")) lon = 36;
        int dec = mt.group(3) == null ? 0 : Integer.parseInt(mt.group(3));
        return new Object[]{tipo, lon, dec, miles};
    }

    private static Dominio armar(String nombre, Tipo tipo, int lon, int dec, List<Valor> valores) {
        if ((tipo == Tipo.CHAR || tipo == Tipo.VARCHAR) && lon <= 0) throw error("dominio " + nombre + ": falta longitud");
        if (tipo == Tipo.NUMERIC && lon <= 0) lon = 18;
        if (dec < 0 || (tipo == Tipo.NUMERIC && dec >= lon)) throw error("dominio " + nombre + ": decimales inválidos");
        return new Dominio(nombre, tipo, lon, dec, valores);
    }

    private static Dominio dominioDeCampo(Map<String, Object> c, String campo, Map<String, Dominio> dominios) {
        Object nom = c.get("dominio");
        if (nom != null) {
            Dominio d = dominios.get(String.valueOf(nom).toLowerCase(Locale.ROOT));
            if (d != null) return d;
            // Permite escribir el tipo GeneXus directo en 'dominio', ej: Character(3)
            try { return dominio(String.valueOf(nom), nom); }
            catch (IllegalArgumentException e) { throw error(campo + ": dominio desconocido '" + nom + "'"); }
        }
        if (c.get("tipo") != null) return dominio(campo, c);
        throw error(campo + ": falta 'dominio' o 'tipo'");
    }

    /** Lleva un valor del YAML al tipo Java del dominio (Long, BigDecimal, String, Boolean, LocalDate). */
    static Object normalizar(Dominio d, Object v) {
        if (v == null) return null;
        try {
            return switch (d.tipo()) {
                case NUMERIC -> d.esEntero() ? (Object) new java.math.BigDecimal(String.valueOf(v).trim()).longValueExact()
                        : new java.math.BigDecimal(String.valueOf(v).trim());
                case BOOLEAN -> v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v).trim());
                case DATE -> java.time.LocalDate.parse(String.valueOf(v).trim());
                case DATETIME -> java.time.LocalDateTime.parse(String.valueOf(v).trim());
                case CHAR, VARCHAR -> String.valueOf(v);
            };
        } catch (RuntimeException e) {
            throw error("valor '" + v + "' inválido para el dominio " + d.nombre());
        }
    }

    // ------------------------------------------------------------------ utilitarios

    static String ident(String s, String donde) {
        if (s == null || !IDENT.matcher(s).matches()) throw error(donde + ": nombre inválido '" + s + "'");
        return s;
    }
    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, String> mapaIdent(Object o, String donde) {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        if (o == null) return r;
        if (!(o instanceof Map<?, ?> m)) throw error(donde + ": debe ser un bloque local: remoto");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet())
            r.put(ident(e.getKey(), donde), ident(String.valueOf(e.getValue()), donde));
        return r;
    }
    private static String texto(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        if (v == null || String.valueOf(v).isBlank()) {
            if (def == null) throw error("falta '" + k + "'");
            return def;
        }
        return String.valueOf(v).trim();
    }
    private static String opcional(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }
    private static List<String> lista(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return l.stream().map(String::valueOf).map(String::trim).toList();
        return Arrays.stream(String.valueOf(v).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    private static boolean bool(Object v, boolean def) {
        return v == null ? def : v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v).trim());
    }
    private static int entero(Object v, int def) {
        if (v == null) return def;
        try { return v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).trim()); }
        catch (NumberFormatException e) { throw error("número inválido: " + v); }
    }
    private static IllegalArgumentException error(String msg) { return new IllegalArgumentException(msg); }
}
