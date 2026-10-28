package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Dominio;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Nivel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V48 - WEB PANEL de GeneXus por YAML (wbp/*.yaml), como las TRN: se graba el YAML y se reabre la pantalla.
 *
 *   panel: PRSAltaWbp
 *   titulo: Alta de Presupuesto a Cliente
 *   variables:                       # las de la pantalla, como los campos de una TRN (dominio, etiqueta, soloLectura, oculto...)
 *     - {nombre: EntidadesId, dominio: Numeric(8), etiqueta: Entidad Id}
 *   pantalla:                        # renglones, en orden ({seccion: Título} = título de sección, V66t)
 *     - [EntidadesTipoEntidad, EntidadesId, EntidadesRazonSocial]
 *   prompts:                         # la lupa de una variable (el Prompt de las reglas de GeneXus)
 *     - {variable: EntidadesId, tabla: Entidades, codigo: EntidadesId, descripcion: EntidadesRazonSocial,
 *        donde: {EntidadesTipoEntidad: 1}, devuelve: {EntidadesPK: EntidadesPK, EntidadesId: EntidadesId}}
 *   parm: [PRSId, Objeto]           # (opcional) lo que recibe al abrirse, como el Parm(in: ...) de GeneXus
 *   botones:
 *     - {titulo: Confirmar, evento: DoConfirmar}
 *     - {titulo: Cancelar, evento: Cancel}
 *   internas: |                      # variables que usan los eventos y no están en la pantalla (como en un .proc)
 *     &WWPContext  Sdt(WWPContext)
 *   eventos: |                       # el código de GeneXus TAL CUAL: Event Start, Event &X.IsValid, Event 'Boton', Sub
 *     Event Start ... EndEvent
 *
 * Los eventos los ejecuta el intérprete de procedimientos (OptimistProcedimiento) con las variables de la pantalla:
 * entran con lo que hay en pantalla y lo que dejan vuelve a la pantalla. Msg() se muestra arriba (el ErrorViewer).
 * Además de GeneXus puro:
 *   &X.Visible = ... / &X.Enabled = ...   muestran u ocultan / habilitan la variable X.
 *   Trn.Call(TrnMode.Update, &Clave)       al terminar el evento, cierra el panel y abre esa TRN en ese registro.
 * Esta clase no usa Vaadin (la pantalla es OptimistPanelView).
 */
public final class OptimistPanel {

    /** La lupa de una variable. donde: columna -> constante o "&Variable" (el valor actual en pantalla). */
    public record Prompt(String variable, String tabla, LinkedHashMap<String, Object> donde, LinkedHashMap<String, String> devuelve,
                         String codigo, String descripcion) {}

    /**
     * V66z: confirmar = lo que pregunta antes de correr el evento (CLAUDE.md: todo proceso de un web panel pide confirmación).
     * null = la pregunta de siempre ("¿Confirmás Procesar?"); "" = no pregunta (confirmar: false: buscar, ir a, ver...).
     * Los botones de Cancel nunca preguntan.
     */
    public record Boton(String titulo, String evento, String confirmar) {
        public Boton(String titulo, String evento) { this(titulo, evento, null); }
        /** La pregunta antes de correrlo, o null si no pregunta. */
        public String pregunta() {
            if (normalizar(evento).equals("cancel") || "".equals(confirmar)) return null;
            return confirmar != null ? confirmar : "¿Confirmás " + titulo.strip() + "?";
        }
    }

    /**
     * V66y: un control para subir archivos (el FileUpload de GeneXus): los archivos que elige el usuario llegan a la colección
     * &Variable (Coleccion(FileUploadData) en 'internas'), cada uno con File (la ruta en el servidor), Name, Extension y Size.
     * extensiones: las que acepta (sin punto; vacío = cualquiera). maximo: cuántos archivos (0 = sin límite).
     */
    public record Archivo(String variable, String etiqueta, List<String> extensiones, int maximo) {}

    /**
     * V61: una grilla editable del panel (varios renglones con las mismas columnas). En los eventos es una colección
     * (&Nombre) de renglones: For &R in &Nuevos ... &R.Codigo ... EndFor. Los renglones vacíos (sin nada cargado) no llegan.
     * renglones: cuántos renglones vacíos muestra al abrir (se pueden agregar y quitar).
     */
    /** V61: grilla editable. V65q: fija = los renglones los pone el evento (sin agregar ni quitar); las columnas ocultas viajan igual. */
    public record Grilla(String nombre, String titulo, Nivel columnas, int renglones, boolean fija,
                         List<Resaltado> resaltar, Map<String, OptimistFormula> habilitar, Map<String, OptimistFormula> calcular,
                         boolean lista, Set<String> totales, Map<String, Enlace> enlaces, boolean ventana, List<Lupa> lupas,
                         Grafico grafico) {
        /** V84b: sin gráfico. */
        public Grilla(String nombre, String titulo, Nivel columnas, int renglones, boolean fija,
                      List<Resaltado> resaltar, Map<String, OptimistFormula> habilitar, Map<String, OptimistFormula> calcular,
                      boolean lista, Set<String> totales, Map<String, Enlace> enlaces, boolean ventana, List<Lupa> lupas) {
            this(nombre, titulo, columnas, renglones, fija, resaltar, habilitar, calcular, lista, totales, enlaces, ventana, lupas, null);
        }
        public Grilla(String nombre, String titulo, Nivel columnas, int renglones, boolean fija) {
            this(nombre, titulo, columnas, renglones, fija, List.of(), Map.of(), Map.of(), false, Set.of(), Map.of(), false, List.of());
        }
        public Grilla(String nombre, String titulo, Nivel columnas, int renglones, boolean fija,
                      List<Resaltado> resaltar, Map<String, OptimistFormula> habilitar, Map<String, OptimistFormula> calcular) {
            this(nombre, titulo, columnas, renglones, fija, resaltar, habilitar, calcular, false, Set.of(), Map.of(), false, List.of());
        }
        public Grilla(String nombre, String titulo, Nivel columnas, int renglones, boolean fija,
                      List<Resaltado> resaltar, Map<String, OptimistFormula> habilitar, Map<String, OptimistFormula> calcular,
                      boolean lista, Set<String> totales, Map<String, Enlace> enlaces) {
            this(nombre, titulo, columnas, renglones, fija, resaltar, habilitar, calcular, lista, totales, enlaces, false, List.of());
        }
        /** V84: las lupas de una columna (en el orden del YAML). */
        public List<Lupa> lupasDe(String columna) {
            List<Lupa> r = new ArrayList<>();
            for (Lupa l : lupas) if (l.columna().equalsIgnoreCase(columna)) r.add(l);
            return r;
        }
    }

    /**
     * V84: una lupa al lado del valor de una columna de una grilla 'lista' (el drill-down de OPTIMIST): abre una ventana con
     * los renglones de otra grilla del panel (la de 'grilla', en general una 'ventana: true') que coinciden con este renglón
     * según 'por' ({ColumnaDeLaOtra: ColumnaDeEsta}). titulo: el de la ventana, con {Columna} = el valor de este renglón.
     * icono: el de Vaadin (search, cubes, tags, user...; por defecto search). ayuda: el texto al pasar el mouse.
     * si: cuándo hay lupa (sin 'si': siempre).
     */
    public record Lupa(String columna, String grilla, Map<String, String> por, String titulo, String icono, String ayuda, OptimistFormula si) {}

    /**
     * V84b: el gráfico de columnas al lado de una grilla 'lista' (el GoogleCharts de las estadísticas de GeneXus): una
     * columna por renglón (en el orden de la lista, de a 'deA' con ‹ ›), con el texto de 'categoria' y una barra por cada
     * serie elegida. series: las columnas numéricas que se pueden graficar (solo las que se ven: &Columna.Visible);
     * marcadas: las que aparecen elegidas al abrir; derecha: las que van con el eje de la derecha (ej. cantidades, al lado de
     * importes, para que no se aplasten).
     */
    public record Grafico(String categoria, List<String> series, List<String> marcadas, Set<String> derecha, int deA, String titulo) {}

    /**
     * V74: el vínculo de una columna de una grilla 'lista': abre una consulta (cns) con lo que recibe, en el orden que diga
     * 'parametros' ({NombreQueRecibe: Columna | "&Variable" | constante}); 'si': cuándo hay vínculo (sin 'si': siempre que el
     * valor no sea cero ni vacío).
     */
    public record Enlace(String cns, Map<String, String> parametros, OptimistFormula si, String trn, String clave) {
        public Enlace(String cns, Map<String, String> parametros, OptimistFormula si) { this(cns, parametros, si, null, null); }
    }

    /**
     * V66: estilo por renglón de una grilla (como el &Columna.Class del Grid.Load de GeneXus): si la condición se cumple con
     * los valores del renglón, las columnas indicadas (o todas) toman el estilo de la clase (de dominios.yaml o del panel).
     */
    public record Resaltado(OptimistFormula si, Map<String, String> estilo, Set<String> columnas) {}

    /** Al terminar un evento: abrir esta TRN (ruta) en este modo con esta clave. V66: modo "Lista" = su WorkWith. */
    public record Abrir(String trn, String modo, Object clave) {
        public boolean lista() { return "Lista".equalsIgnoreCase(modo); }
        /** V84e: OtroPanel.Call(...): trn = el panel; clave = lo que recibe (lista). */
        public boolean panel() { return "Panel".equalsIgnoreCase(modo); }
        @SuppressWarnings("unchecked")
        public List<Object> parametros() { return clave instanceof List<?> l ? (List<Object>) l : List.of(); }
    }

    /**
     * Lo que dejó un evento. V49f: cerrar = el evento terminó con Return (en GeneXus vuelve a la pantalla que llamó al panel);
     * aviso = el &WWPContext.MensajeError que dejaron los procedimientos ("Mail enviado correctamente").
     */
    public record Resultado(List<String> mensajes, boolean error, Abrir abrir, boolean cerrar, String aviso, List<Object[]> pdfs) {
        public Resultado(List<String> mensajes, boolean error, Abrir abrir) { this(mensajes, error, abrir, false, "", List.of()); }
        public Resultado(List<String> mensajes, boolean error, Abrir abrir, boolean cerrar, String aviso) { this(mensajes, error, abrir, cerrar, aviso, List.of()); }
    }

    /**
     * V76: el resultado en otra pantalla (como WPShowAntDeuda de GeneXus): después de un botón del formulario sin mensajes
     * de error y con la variable 'si' en true, se pasa a una pantalla con el título, el subtítulo (una variable), las
     * variables y las grillas del resultado a todo el ancho y sus botones (más Volver, que vuelve al formulario como estaba).
     */
    public record Aparte(String titulo, String si, String subtitulo, List<String> variables, List<String> grillas, List<Boton> botones) {}
    /** V76: 'resultado:' del YAML, o null (el resultado queda en la misma pantalla). */
    public final Aparte resultado;

    @SuppressWarnings("unchecked")
    private static List<Boton> leerBotones(Object v) {
        List<Boton> bs = new ArrayList<>();
        if (v instanceof List<?> lb)
            for (Object o : lb) {
                if (!(o instanceof Map<?, ?> m)) throw error("botón inválido: " + o);
                Object cf = m.get("confirmar");   // V66z: true / ausente = la pregunta de siempre; false = no pregunta; "texto" = esa pregunta
                String conf = cf == null || Boolean.TRUE.equals(cf) || "true".equalsIgnoreCase(String.valueOf(cf).trim()) ? null
                        : Boolean.FALSE.equals(cf) || "false".equalsIgnoreCase(String.valueOf(cf).trim()) ? "" : String.valueOf(cf).strip();
                bs.add(new Boton(String.valueOf(((Map<String, Object>) m).getOrDefault("titulo", "?")),
                        String.valueOf(((Map<String, Object>) m).getOrDefault("evento", "Cancel")).replace("'", "").trim(), conf));
            }
        return bs;
    }

    private static List<String> nombres(Object v) {
        List<String> r = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) r.add(String.valueOf(o).replace("&", "").trim());
        else if (v != null) for (String x : String.valueOf(v).split(",")) if (!x.isBlank()) r.add(x.replace("&", "").trim());
        return List.copyOf(r);
    }

    public final String nombre;
    public final String titulo;
    public final Nivel variables;
    public final List<List<String>> renglones;
    /** V66t: secciones de la pantalla ({seccion: Título} en 'pantalla'): el título va antes del renglón número 'antesDe'. */
    public record Seccion(int antesDe, String titulo) {}
    public final List<Seccion> secciones;
    public final List<Prompt> prompts;
    /** V66t: variables con 'combo: {tabla, valor, texto, donde, vacioTexto}' (el Dynamic Combo Box de GeneXus), por nombre. */
    public final Map<String, OptimistTrnDefinicion.ComboTabla> combos;
    public final List<Boton> botones;
    /** V66y: los controles para subir archivos (van debajo de las variables). */
    public final List<Archivo> archivos;
    /** V61: las grillas editables (en el orden del YAML; van debajo de las variables). */
    public final List<Grilla> grillas;
    /** V48: variables que recibe al abrirse (Parm de GeneXus), en orden. Pueden ser de la pantalla o internas. */
    public final List<String> parm;
    /** nombre normalizado del evento ("start", "'doconfirmar'", "&entidadesid.isvalid") -> código. */
    final Map<String, String> eventos;
    final String subs;
    final String internas;
    private final Function<String, String> tipoClaveTrn;
    private final Map<String, OptimistProcedimiento> compilados = new HashMap<>();

    private static final Pattern EVENTO = Pattern.compile("(?im)^\\s*event\\s+(.+?)\\s*$");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * y: el YAML ya leído; dominiosGlobales: dominios.yaml.
     * tipoClaveTrn: nombre de una TRN -> tipo GeneXus de su clave ("Numeric(10)"), o null si no es una TRN (para Trn.Call).
     */
    @SuppressWarnings("unchecked")
    public OptimistPanel(Map<String, Object> y, Map<String, Object> dominiosGlobales, Function<String, String> tipoClaveTrn) {
        this.nombre = ident(String.valueOf(y.getOrDefault("panel", "")), "panel");
        this.titulo = String.valueOf(y.getOrDefault("titulo", nombre));
        this.variables = OptimistTrnDefinicion.variablesDePanel(nombre, y, dominiosGlobales);
        this.tipoClaveTrn = tipoClaveTrn == null ? n -> null : tipoClaveTrn;

        List<List<String>> rs = new ArrayList<>();
        List<Seccion> secs = new ArrayList<>();
        if (y.get("pantalla") instanceof List<?> lp) {
            for (Object o : lp) {
                if (o instanceof Map<?, ?> m) {   // V66t: - {seccion: Título}
                    Object t = m.get("seccion");
                    if (t == null || String.valueOf(t).isBlank()) throw error("'pantalla': un renglón entre llaves tiene que ser {seccion: Título}");
                    secs.add(new Seccion(rs.size(), String.valueOf(t).trim()));
                    continue;
                }
                List<String> fila = new ArrayList<>();
                for (Object x : o instanceof List<?> l ? l : List.of(o)) fila.add(variableExistente(String.valueOf(x).trim(), "pantalla"));
                rs.add(List.copyOf(fila));
            }
        } else if (y.get("pantalla") != null) throw error("'pantalla' debe ser una lista de renglones: - [VarA, VarB]");
        // las variables visibles que no están en ningún renglón van al final, una por renglón
        Set<String> usadas = new HashSet<>();
        rs.forEach(f -> f.forEach(v -> usadas.add(v.toLowerCase(Locale.ROOT))));
        for (Campo c : variables.campos())
            if (!c.oculto() && !usadas.contains(c.nombre().toLowerCase(Locale.ROOT))) rs.add(List.of(c.nombre()));
        this.renglones = List.copyOf(rs);
        this.secciones = List.copyOf(secs);

        Map<String, OptimistTrnDefinicion.ComboTabla> combosVar = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (y.get("variables") instanceof List<?> lv)
            for (Object o : lv)
                if (o instanceof Map<?, ?> m && m.get("combo") != null && m.get("nombre") != null)
                    combosVar.put(String.valueOf(m.get("nombre")), OptimistTrnDefinicion.comboTabla(m.get("combo"), "panel " + nombre + "." + m.get("nombre") + ".combo"));
        this.combos = combosVar;
        List<Prompt> ps = new ArrayList<>();
        if (y.get("prompts") instanceof List<?> lp) {
            for (Object o : lp) {
                if (!(o instanceof Map<?, ?> m)) throw error("prompt inválido: " + o);
                Map<String, Object> p = (Map<String, Object>) m;
                String var = variableExistente(String.valueOf(p.get("variable")), "prompt.variable");
                String tabla = ident(String.valueOf(p.get("tabla")), "prompt " + var + ".tabla");
                LinkedHashMap<String, String> dev = new LinkedHashMap<>();
                if (p.get("devuelve") instanceof Map<?, ?> dm)
                    for (Map.Entry<?, ?> e : dm.entrySet())
                        dev.put(variableExistente(String.valueOf(e.getKey()), "prompt " + var + ".devuelve"), ident(String.valueOf(e.getValue()), "prompt " + var + ".devuelve"));
                if (dev.isEmpty()) throw error("prompt " + var + ": falta 'devuelve' ({Variable: Columna, ...})");
                LinkedHashMap<String, Object> donde = new LinkedHashMap<>();
                if (p.get("donde") instanceof Map<?, ?> wm)
                    for (Map.Entry<?, ?> e : wm.entrySet()) {
                        Object v = e.getValue();
                        if (v instanceof String s && s.startsWith("&")) variableExistente(s.substring(1), "prompt " + var + ".donde");
                        donde.put(ident(String.valueOf(e.getKey()), "prompt " + var + ".donde"), v);
                    }
                String cod = p.get("codigo") == null ? dev.get(var) : ident(String.valueOf(p.get("codigo")), "prompt " + var + ".codigo");
                String desc = p.get("descripcion") == null ? null : ident(String.valueOf(p.get("descripcion")), "prompt " + var + ".descripcion");
                ps.add(new Prompt(var, tabla, donde, dev, cod, desc));
            }
        }
        this.prompts = List.copyOf(ps);

        List<Boton> bs = leerBotones(y.get("botones"));
        if (bs.isEmpty()) bs.add(new Boton("Cerrar", "Cancel"));
        this.botones = List.copyOf(bs);

        // V61: grillas editables
        List<Grilla> gs = new ArrayList<>();
        if (y.get("grillas") instanceof List<?> lg) {
            for (Object o : lg) {
                if (!(o instanceof Map<?, ?> m0)) throw error("grilla inválida: " + o);
                Map<String, Object> g = (Map<String, Object>) m0;
                String gn = ident(String.valueOf(g.get("nombre")), "grillas.nombre");
                if (variables.campo(gn) != null) throw error("grilla " + gn + ": ya hay una variable con ese nombre");
                if (!(g.get("columnas") instanceof List<?>)) throw error("grilla " + gn + ": faltan 'columnas' (como las variables)");
                Map<String, Object> yg = new LinkedHashMap<>();
                yg.put("variables", g.get("columnas"));
                yg.put("dominios", y.get("dominios"));
                Nivel cols = OptimistTrnDefinicion.variablesDePanel(nombre + "." + gn, yg, dominiosGlobales);
                boolean fija = Boolean.parseBoolean(String.valueOf(g.getOrDefault("fija", "false")).trim());
                int n = fija ? 0 : 5;
                if (g.get("renglones") != null) {
                    try { n = Math.max(fija ? 0 : 1, Math.min(200, Integer.parseInt(String.valueOf(g.get("renglones")).trim()))); }
                    catch (NumberFormatException e) { throw error("grilla " + gn + ": 'renglones' tiene que ser un número"); }
                }
                // V66: resaltar / habilitar / calcular por renglón
                Set<String> nombresCols = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (Campo c : cols.campos()) nombresCols.add(c.nombre());
                List<Resaltado> res = new ArrayList<>();
                if (g.get("resaltar") instanceof List<?> lr) {
                    Map<String, Map<String, String>> clases = OptimistTrnDefinicion.clasesDePanel(y, dominiosGlobales, nombre);
                    for (Object o2 : lr) {
                        if (!(o2 instanceof Map<?, ?> mr)) throw error("grilla " + gn + ": resaltar: - {si: \"condición\", clase: AttributeTitleRed, columnas: [...]}");
                        Map<String, Object> r = (Map<String, Object>) mr;
                        String clase = String.valueOf(r.getOrDefault("clase", "AttributeTitleRed")).trim();
                        Map<String, String> est = clases.get(clase);
                        if (est == null) throw error("grilla " + gn + ": resaltar: la clase '" + clase + "' no está en 'clases:' (ni en dominios.yaml)");
                        Set<String> cs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                        if (r.get("columnas") instanceof List<?> lc) for (Object c : lc) {
                            if (!nombresCols.contains(String.valueOf(c).trim())) throw error("grilla " + gn + ": resaltar: no existe la columna " + c);
                            cs.add(String.valueOf(c).trim());
                        }
                        res.add(new Resaltado(formulaGrilla(gn, "resaltar", r.get("si"), nombresCols), est, cs));
                    }
                } else if (g.get("resaltar") != null) throw error("grilla " + gn + ": 'resaltar' debe ser una lista");
                Map<String, OptimistFormula> hab = condicionesGrilla(gn, "habilitar", g.get("habilitar"), nombresCols);
                Map<String, OptimistFormula> cal = condicionesGrilla(gn, "calcular", g.get("calcular"), nombresCols);
                // V74: lista: grilla de resultados (solo lectura, orden con clic en el título, totales al pie y vínculos)
                boolean lista = Boolean.parseBoolean(String.valueOf(g.getOrDefault("lista", "false")).trim());
                Set<String> tot = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                if (g.get("totales") instanceof List<?> lt) for (Object c : lt) {
                    String cn = String.valueOf(c).trim();
                    if (!nombresCols.contains(cn)) throw error("grilla " + gn + ": totales: no existe la columna " + cn);
                    tot.add(cn);
                } else if (g.get("totales") != null) throw error("grilla " + gn + ": 'totales' debe ser una lista de columnas");
                Map<String, Enlace> enl = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                if (g.get("enlaces") instanceof Map<?, ?> me) {
                    for (Map.Entry<?, ?> e : me.entrySet()) {
                        String cn = String.valueOf(e.getKey()).trim();
                        if (!nombresCols.contains(cn)) throw error("grilla " + gn + ": enlaces: no existe la columna " + cn);
                        // V84: {trn: CPBC, clave: CPBCId} = abre ese registro de la TRN en modo ver
                        if (e.getValue() instanceof Map<?, ?> dt && dt.get("trn") != null) {
                            String cl = String.valueOf(dt.get("clave") == null ? "" : dt.get("clave")).trim();
                            if (!nombresCols.contains(cl)) throw error("grilla " + gn + ": enlaces: " + cn + ": {trn: TRN, clave: Columna}: no existe la columna '" + cl + "'");
                            OptimistFormula si = dt.get("si") == null ? null : formulaGrilla(gn, "enlaces", dt.get("si"), nombresCols);
                            enl.put(cn, new Enlace(null, Map.of(), si, String.valueOf(dt.get("trn")).trim(), cl));
                            continue;
                        }
                        if (!(e.getValue() instanceof Map<?, ?> d) || d.get("cns") == null)
                            throw error("grilla " + gn + ": enlaces: " + cn + ": {cns: Consulta, parametros: {Recibe: Columna}, si: \"condición\"} o {trn: TRN, clave: Columna}");
                        Map<String, String> pe = new LinkedHashMap<>();
                        if (d.get("parametros") instanceof Map<?, ?> mp) mp.forEach((k, v) -> pe.put(String.valueOf(k).trim(), String.valueOf(v).trim()));
                        else if (d.get("parametros") != null) throw error("grilla " + gn + ": enlaces: " + cn + ": 'parametros' debe ser {Recibe: Columna}");
                        OptimistFormula si = d.get("si") == null ? null : formulaGrilla(gn, "enlaces", d.get("si"), nombresCols);
                        enl.put(cn, new Enlace(String.valueOf(d.get("cns")).trim(), Collections.unmodifiableMap(pe), si));
                    }
                } else if (g.get("enlaces") != null) throw error("grilla " + gn + ": 'enlaces' debe ser {Columna: {cns: ..., parametros: {...}}}");
                // V84: ventana: la grilla no va en la pantalla: se ve en la ventana de una lupa de otra grilla
                boolean ventana = Boolean.parseBoolean(String.valueOf(g.getOrDefault("ventana", "false")).trim());
                if (ventana) lista = true;
                List<Lupa> lps = new ArrayList<>();
                if (g.get("lupas") instanceof List<?> ll) {
                    for (Object o2 : ll) {
                        if (!(o2 instanceof Map<?, ?> ml)) throw error("grilla " + gn + ": lupas: - {columna: Total, grilla: Otra, por: {ColumnaDeLaOtra: ColumnaDeEsta}, titulo: \"...\"}");
                        Map<String, Object> l = (Map<String, Object>) ml;
                        String col = String.valueOf(l.getOrDefault("columna", "")).trim();
                        if (!nombresCols.contains(col)) throw error("grilla " + gn + ": lupas: no existe la columna '" + col + "'");
                        if (l.get("grilla") == null) throw error("grilla " + gn + ": lupas: " + col + ": falta 'grilla'");
                        Map<String, String> por = new LinkedHashMap<>();
                        if (l.get("por") instanceof Map<?, ?> mp) mp.forEach((k, v) -> por.put(String.valueOf(k).trim(), String.valueOf(v).trim()));
                        else throw error("grilla " + gn + ": lupas: " + col + ": 'por' debe ser {ColumnaDeLaOtra: ColumnaDeEsta}");
                        for (String v : por.values()) if (!nombresCols.contains(v)) throw error("grilla " + gn + ": lupas: " + col + ": por: no existe la columna '" + v + "'");
                        OptimistFormula si = l.get("si") == null ? null : formulaGrilla(gn, "lupas", l.get("si"), nombresCols);
                        lps.add(new Lupa(col, String.valueOf(l.get("grilla")).trim(), Collections.unmodifiableMap(por),
                                l.get("titulo") == null ? null : String.valueOf(l.get("titulo")),
                                l.get("icono") == null ? "search" : String.valueOf(l.get("icono")).trim(),
                                l.get("ayuda") == null ? null : String.valueOf(l.get("ayuda")), si));
                    }
                } else if (g.get("lupas") != null) throw error("grilla " + gn + ": 'lupas' debe ser una lista");
                if (!lps.isEmpty() && !lista) throw error("grilla " + gn + ": 'lupas' es solo para las grillas 'lista: true'");
                // V84b: grafico: {categoria: Descripcion, series: [Total, Cantidad], marcadas: [Total], derecha: [Cantidad], deA: 10}
                Grafico graf = null;
                if (g.get("grafico") instanceof Map<?, ?> mg) {
                    if (!lista || ventana) throw error("grilla " + gn + ": 'grafico' es solo para las grillas 'lista: true' (no las 'ventana')");
                    String cat = String.valueOf(mg.get("categoria") == null ? "" : mg.get("categoria")).trim();
                    if (cols.campo(cat) == null) throw error("grilla " + gn + ": grafico: categoria: no existe la columna '" + cat + "'");
                    List<String> ser = new ArrayList<>(), mar = new ArrayList<>();
                    Set<String> der = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    for (String sn : nombres(mg.get("series"))) {
                        Campo cs = cols.campo(sn);
                        if (cs == null) throw error("grilla " + gn + ": grafico: series: no existe la columna '" + sn + "'");
                        if (!cs.dominio().esNumerico() || cs.dominio().esCombo()) throw error("grilla " + gn + ": grafico: series: '" + sn + "' no es numérica");
                        ser.add(cs.nombre());
                    }
                    if (ser.isEmpty()) throw error("grilla " + gn + ": grafico: falta 'series' (las columnas numéricas a graficar)");
                    for (String sn : nombres(mg.get("marcadas"))) {
                        String real = ser.stream().filter(x -> x.equalsIgnoreCase(sn)).findFirst().orElse(null);
                        if (real == null) throw error("grilla " + gn + ": grafico: marcadas: '" + sn + "' no está en series");
                        mar.add(real);
                    }
                    if (mar.isEmpty()) mar.add(ser.get(0));
                    for (String sn : nombres(mg.get("derecha"))) {
                        String real = ser.stream().filter(x -> x.equalsIgnoreCase(sn)).findFirst().orElse(null);
                        if (real == null) throw error("grilla " + gn + ": grafico: derecha: '" + sn + "' no está en series");
                        der.add(real);
                    }
                    int deA = 10;
                    if (mg.get("deA") != null) {
                        try { deA = Integer.parseInt(String.valueOf(mg.get("deA")).trim()); }
                        catch (NumberFormatException e) { throw error("grilla " + gn + ": grafico: deA: tiene que ser un número (10, 20, 50)"); }
                        if (deA < 3 || deA > 100) throw error("grilla " + gn + ": grafico: deA: entre 3 y 100");
                    }
                    graf = new Grafico(cols.campo(cat).nombre(), List.copyOf(ser), List.copyOf(mar), Collections.unmodifiableSet(der), deA,
                            mg.get("titulo") == null ? null : String.valueOf(mg.get("titulo")));
                } else if (g.get("grafico") != null) throw error("grilla " + gn + ": 'grafico' debe ser {categoria: Columna, series: [Col1, Col2]}");
                if (lista) { fija = true; n = 0; }
                gs.add(new Grilla(gn, String.valueOf(g.getOrDefault("titulo", gn)), cols, n, fija, List.copyOf(res), hab, cal,
                        lista, Collections.unmodifiableSet(tot), Collections.unmodifiableMap(enl), ventana, List.copyOf(lps), graf));
            }
        } else if (y.get("grillas") != null) throw error("'grillas' debe ser una lista: - {nombre: Nuevos, columnas: [...]}");
        this.grillas = List.copyOf(gs);
        // V84: la grilla de cada lupa existe y tiene las columnas de 'por'
        for (Grilla g : grillas)
            for (Lupa l : g.lupas()) {
                Grilla o = grillas.stream().filter(x -> x.nombre().equalsIgnoreCase(l.grilla())).findFirst().orElse(null);
                if (o == null) throw error("grilla " + g.nombre() + ": lupas: " + l.columna() + ": no existe la grilla '" + l.grilla() + "'");
                if (!o.lista()) throw error("grilla " + g.nombre() + ": lupas: " + l.columna() + ": la grilla '" + l.grilla() + "' tiene que ser 'lista' o 'ventana'");
                for (String k : l.por().keySet())
                    if (o.columnas().campo(k) == null) throw error("grilla " + g.nombre() + ": lupas: " + l.columna() + ": la grilla '" + l.grilla() + "' no tiene la columna '" + k + "'");
            }

        // V76: resultado: {titulo, si, subtitulo, variables, grillas, botones}
        if (y.get("resultado") instanceof Map<?, ?> rm) {
            Map<String, Object> r = (Map<String, Object>) rm;
            String si = r.get("si") == null ? null : String.valueOf(r.get("si")).replace("&", "").trim();
            if (si == null || variables.campo(si) == null) throw error("resultado.si: tiene que ser una variable del panel (Boolean)");
            String sub = r.get("subtitulo") == null ? null : String.valueOf(r.get("subtitulo")).replace("&", "").trim();
            if (sub != null && variables.campo(sub) == null) throw error("resultado.subtitulo: variable inexistente " + sub);
            List<String> vs = nombres(r.get("variables"));
            for (String v : vs) if (variables.campo(v) == null) throw error("resultado.variables: variable inexistente " + v);
            List<String> gr = nombres(r.get("grillas"));
            for (String g : gr) if (grillas.stream().noneMatch(x -> x.nombre().equalsIgnoreCase(g))) throw error("resultado.grillas: grilla inexistente " + g);
            this.resultado = new Aparte(String.valueOf(r.getOrDefault("titulo", titulo)), si, sub, vs, gr, List.copyOf(leerBotones(r.get("botones"))));
        } else this.resultado = null;

        this.internas = y.get("internas") == null ? "" : String.valueOf(y.get("internas"));
        // V66y: archivos: - {variable: UploadedFiles, etiqueta: Archivo, extensiones: [xls, xlsx], maximo: 1}
        List<Archivo> ars = new ArrayList<>();
        if (y.get("archivos") instanceof List<?> la) {
            Map<String, String> tipos = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String[] d : declaraciones()) tipos.put(d[0], d[1]);
            for (Object o : la) {
                if (!(o instanceof Map<?, ?> m)) throw error("archivos: - {variable: UploadedFiles, etiqueta: Archivo, extensiones: [xls, xlsx], maximo: 1}");
                String var = String.valueOf(m.get("variable")).trim().replaceFirst("^&", "");
                String tipo = tipos.get(var);
                if (tipo == null || !tipo.replace(" ", "").toLowerCase(Locale.ROOT).startsWith("coleccion("))
                    throw error("archivos: &" + var + " tiene que estar en 'internas' como Coleccion(FileUploadData)");
                List<String> ext = new ArrayList<>();
                if (m.get("extensiones") instanceof List<?> le)
                    for (Object x : le) ext.add(String.valueOf(x).trim().replaceFirst("^\\.", "").toLowerCase(Locale.ROOT));
                int max;
                try { max = m.get("maximo") == null ? 1 : Math.max(0, Integer.parseInt(String.valueOf(m.get("maximo")).trim())); }
                catch (NumberFormatException e) { throw error("archivos: 'maximo' tiene que ser un número"); }
                String nom = tipos.keySet().stream().filter(var::equalsIgnoreCase).findFirst().orElse(var);
                Object et = m.get("etiqueta");
                ars.add(new Archivo(nom, et == null ? "Archivo" : String.valueOf(et), List.copyOf(ext), max));
            }
        } else if (y.get("archivos") != null) throw error("'archivos' debe ser una lista: - {variable: UploadedFiles, ...}");
        this.archivos = List.copyOf(ars);
        List<String> pm = new ArrayList<>();
        if (y.get("parm") instanceof List<?> lp)
            for (Object o : lp) pm.add(String.valueOf(o).trim().replaceFirst("^(?i)(in|out|inout)\\s*:\\s*", "").replaceFirst("^&", ""));
        else if (y.get("parm") != null) throw error("'parm' debe ser una lista: [Variable1, Variable2]");
        Set<String> declaradas = new HashSet<>();
        for (String[] d : declaraciones()) declaradas.add(d[0].toLowerCase(Locale.ROOT));
        for (String v : pm) if (!declaradas.contains(v.toLowerCase(Locale.ROOT))) throw error("parm: &" + v + " no es una variable del panel (ni de internas)");
        this.parm = List.copyOf(pm);
        String ev = y.get("eventos") == null ? "" : String.valueOf(y.get("eventos"));
        this.eventos = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        separar(ev, eventos, sb);
        this.subs = sb.toString();
        // se compilan todos al leer: un error de sintaxis se ve al abrir el panel (con el renglón), no al tocar el botón
        for (String e : eventos.keySet()) compilado(e);
    }

    // ------------------------------------------------------------------ eventos

    /** Separa "Event X ... EndEvent" (por nombre) y deja el resto (los Sub) en subs. */
    private void separar(String texto, Map<String, String> destino, StringBuilder resto) {
        String[] ls = texto.replace("\r\n", "\n").split("\n", -1);
        String actual = null;
        StringBuilder cuerpo = new StringBuilder();
        for (String l : ls) {
            String t = l.trim().toLowerCase(Locale.ROOT);
            Matcher m = EVENTO.matcher(l);
            if (actual == null && m.matches()) { actual = normalizar(m.group(1)); cuerpo.setLength(0); continue; }
            if (actual != null && (t.equals("endevent") || t.equals("end event"))) {
                if (destino.containsKey(actual)) throw error("evento repetido: " + actual);
                destino.put(actual, cuerpo.toString());
                actual = null;
                continue;
            }
            (actual != null ? cuerpo : resto).append(l).append('\n');
        }
        if (actual != null) throw error("falta EndEvent en el evento " + actual);
    }

    /** "Start", "'DoConfirmar'", "DoConfirmar", "&EntidadesId.IsValid" -> "start", "doconfirmar", "&entidadesid.isvalid". */
    public static String normalizar(String evento) {
        String e = evento.trim().replaceAll("//.*$", "").trim().replace("'", "").replace("\"", "").toLowerCase(Locale.ROOT);
        return e.replaceAll("\\s+", "");
    }

    public boolean tieneEvento(String evento) { return eventos.containsKey(normalizar(evento)); }

    /** El evento que corre cuando cambia una variable en pantalla (Event &X.IsValid), o null. */
    public String eventoAlCambiar(String variable) {
        String k = "&" + variable.toLowerCase(Locale.ROOT) + ".isvalid";
        return eventos.containsKey(k) ? k : null;
    }

    private OptimistProcedimiento compilado(String evento) {
        return compilados.computeIfAbsent(evento, e -> {
            String texto = programa(e);
            try { return OptimistProcedimiento.leer(nombre + "." + e, texto); }
            catch (IllegalArgumentException ex) { throw error("evento " + e + ": " + ex.getMessage()); }
        });
    }

    /** Las variables propias del panel y de los eventos: pantalla, internas y las de Visible / Enabled / Trn.Call. */
    private List<String[]> declaraciones() {
        List<String[]> r = new ArrayList<>();
        for (Campo c : variables.campos()) {
            r.add(new String[]{c.nombre(), tipoGx(c.dominio())});
            r.add(new String[]{"WbpVisible_" + c.nombre(), "Boolean"});
            r.add(new String[]{"WbpEnabled_" + c.nombre(), "Boolean"});
        }
        for (String l : internas.replace("\r\n", "\n").split("\n")) {
            String t = l.replaceAll("//.*", "").trim();
            if (t.isEmpty() || t.equalsIgnoreCase("variables") || t.equalsIgnoreCase("endvariables")) continue;
            String[] p = t.split("\\s+", 2);
            if (!p[0].startsWith("&") || p.length < 2) throw error("internas: '" + t + "' (ej. &Importe Numeric(14.2))");
            r.add(new String[]{p[0].substring(1), p[1].trim()});
        }
        for (Grilla g : grillas) {
            r.add(new String[]{g.nombre(), "Coleccion(" + g.nombre() + ")"});   // V61
            r.add(new String[]{"WbpVisible_" + g.nombre(), "Boolean"});         // V65q: &Grilla.Visible
        }
        // V66i: &Columna.Visible de una columna de grilla (oculta la columna entera, como en GeneXus), si no hay ya una
        // variable del panel con ese nombre
        Set<String> ya = new HashSet<>();
        for (String[] d : r) ya.add(d[0].toLowerCase(Locale.ROOT));
        for (Grilla g : grillas)
            for (Campo c : g.columnas().campos())
                if (!c.oculto() && ya.add(("WbpVisible_" + c.nombre()).toLowerCase(Locale.ROOT))) r.add(new String[]{"WbpVisible_" + c.nombre(), "Boolean"});
        r.add(new String[]{"WbpAbrir", "VarChar(60)"});
        r.add(new String[]{"WbpModo", "VarChar(20)"});
        r.add(new String[]{"WbpParametros", "Coleccion(VarChar)"});   // V84e: lo que recibe el panel de OtroPanel.Call(...)
        // V66t: &Pgmname de GeneXus: el nombre del panel (ej. la clave de lo que memoriza el usuario en UserCustom)
        if (ya.add("pgmname")) r.add(new String[]{"Pgmname", "VarChar(128)"});
        return r;
    }

    /** Arma el "procedimiento" de un evento: Parm con todas las variables, sus tipos, el código del evento y los Sub. */
    String programa(String evento) {
        String cuerpo = eventos.get(evento);
        if (cuerpo == null) throw error("no existe el evento " + evento);
        List<String[]> decl = new ArrayList<>(declaraciones());
        StringBuilder clave = new StringBuilder();
        String fuente = preprocesar(cuerpo + "\n" + subs, decl, clave);
        Set<String> vistos = new HashSet<>();
        StringBuilder sb = new StringBuilder("/* Qué hace:\n   Evento ").append(evento).append(" del web panel ").append(nombre).append("\n*/\n");
        StringBuilder parm = new StringBuilder();
        StringBuilder vars = new StringBuilder();
        for (String[] d : decl) {
            if (!vistos.add(d[0].toLowerCase(Locale.ROOT))) continue;
            parm.append(parm.isEmpty() ? "" : ", ").append("inout: &").append(d[0]);
            vars.append("  &").append(d[0]).append(' ').append(d[1]).append('\n');
        }
        sb.append("Parm(").append(parm).append(");\nVariables\n").append(vars).append("EndVariables\n").append(fuente);
        return sb.toString();
    }

    /** Propiedades de controles de la pantalla de GeneXus que el panel todavía no maneja (Caption, Class, Tooltiptext...). */
    private static final Pattern PROPIEDAD_CONTROL = Pattern.compile("(?i)^\\s*&?[A-Za-z_][A-Za-z0-9_]*\\.(?!visible\\b|enabled\\b)(caption|class|tooltiptext|title|link|linktarget|jsonclickevent|fontbold|forecolor|backcolor|width|height|columns|rows|format|picture|invitemessage|tooltip|icon|iconclass|columnheaderclass|columnclass|gridcolumnclass)\\s*=.*$");
    private static final Pattern PROPIEDAD = Pattern.compile("(?i)&([A-Za-z_][A-Za-z0-9_]*)\\.(Visible|Enabled)\\b");
    /** V66: WWCPBC.Call() en un web panel (el botón "Ir a Comprobantes" de GeneXus): abre la lista de esa TRN. */
    private static final Pattern LLAMADA_WW = Pattern.compile("(?i)^(\\s*)WW([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*call\\s*\\(\\s*\\)\\s*;?\\s*(//.*)?$");
    /** V84e: OtroPanel.Call(&A, &B): al terminar el evento, cierra este panel y abre ese (como CPBCProcAltaMultiremito de GeneXus). */
    private static final Pattern LLAMADA_PANEL = Pattern.compile("(?i)^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*call\\s*\\((.*)\\)\\s*;?\\s*(//.*)?$");
    private static final Pattern LLAMADA_TRN = Pattern.compile("(?i)^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*call\\s*\\(\\s*TrnMode\\s*\\.\\s*([A-Za-z]+)\\s*,\\s*(.+?)\\s*\\)\\s*;?\\s*$");

    /** &X.Visible -> &WbpVisible_X; Trn.Call(TrnMode.Update, &Clave) -> &WbpAbrir / &WbpModo / &WbpClave; Refresh -> nada. */
    private String preprocesar(String fuente, List<String[]> decl, StringBuilder clave) {
        StringBuilder r = new StringBuilder();
        for (String l : fuente.split("\n", -1)) {
            String t = l.trim();
            if (t.equalsIgnoreCase("refresh") || t.equalsIgnoreCase("refresh;")) { r.append('\n'); continue; }
            if (PROPIEDAD_CONTROL.matcher(l).matches()) { r.append("// (pantalla, todavía no) ").append(t).append('\n'); continue; }
            Matcher ww = LLAMADA_WW.matcher(l);
            if (ww.matches() && tipoClaveTrn.apply(ww.group(2)) != null) {   // V66: la lista (WorkWith) de la TRN
                r.append(ww.group(1)).append("&WbpAbrir = \"").append(ww.group(2)).append("\"\n")
                 .append(ww.group(1)).append("&WbpModo = \"Lista\"\n");
                continue;
            }
            Matcher mp = LLAMADA_PANEL.matcher(l);
            if (mp.matches() && !mp.group(3).trim().toLowerCase(Locale.ROOT).startsWith("trnmode") && esPanel(mp.group(2))) {
                String in = mp.group(1);
                r.append(in).append("&WbpParametros = New()\n");
                for (String arg : argumentos(mp.group(3))) if (!arg.isBlank()) r.append(in).append("&WbpParametros.Add(").append(arg.trim()).append(")\n");
                r.append(in).append("&WbpAbrir = \"").append(mp.group(2)).append("\"\n")
                 .append(in).append("&WbpModo = \"Panel\"\n");
                continue;
            }
            Matcher m = LLAMADA_TRN.matcher(l);
            if (m.matches()) {
                String tipo = tipoClaveTrn.apply(m.group(2));
                if (tipo == null) throw error(m.group(2) + ".Call(TrnMode..." + "): " + m.group(2) + " no es una TRN del motor");
                decl.add(new String[]{"WbpClave", tipo});
                String in = m.group(1);
                r.append(in).append("&WbpAbrir = \"").append(m.group(2)).append("\"\n")
                 .append(in).append("&WbpModo = \"").append(m.group(3)).append("\"\n")
                 .append(in).append("&WbpClave = ").append(m.group(4)).append('\n');
                continue;
            }
            r.append(PROPIEDAD.matcher(l).replaceAll(x -> "&Wbp" + (x.group(2).equalsIgnoreCase("visible") ? "Visible_" : "Enabled_") + x.group(1))).append('\n');
        }
        return r.toString();
    }

    /** V84e: ¿es un web panel (wbp/Nombre.yaml) y no un procedimiento? */
    static boolean esPanel(String nombre) {
        if (nombre == null || !nombre.matches("[A-Za-z0-9_]{1,80}")) return false;
        try (java.io.InputStream pr = OptimistRecursos.abrir("proc/" + nombre + ".proc")) { if (pr != null) return false; } catch (Exception ignorado) { }
        try (java.io.InputStream in = OptimistRecursos.abrir("wbp/" + nombre + ".yaml")) { return in != null; } catch (Exception e) { return false; }
    }

    /** V84e: los argumentos de una llamada, separados por las comas de afuera (no las de adentro de paréntesis o comillas). */
    static List<String> argumentos(String s) {
        List<String> r = new ArrayList<>();
        int nivel = 0; boolean comilla = false; char q = 0; StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (comilla) { b.append(ch); if (ch == q) comilla = false; continue; }
            if (ch == '"' || ch == '\'') { comilla = true; q = ch; b.append(ch); continue; }
            if (ch == '(') nivel++;
            if (ch == ')') nivel--;
            if (ch == ',' && nivel == 0) { r.add(b.toString()); b.setLength(0); continue; }
            b.append(ch);
        }
        if (b.length() > 0) r.add(b.toString());
        return r;
    }

    /** Tipo GeneXus del dominio de una variable ("Numeric(8)", "VarChar(40)", "Date"...). */
    static String tipoGx(Dominio d) {
        return switch (d.tipo()) {
            case NUMERIC -> d.decimales() > 0 ? "Numeric(" + d.longitud() + "." + d.decimales() + ")" : "Numeric(" + Math.max(1, d.longitud()) + ")";
            case CHAR -> "Character(" + Math.max(1, d.longitud()) + ")";
            case VARCHAR -> "VarChar(" + Math.max(1, d.longitud()) + ")";
            case DATE -> "Date";
            case DATETIME -> "DateTime";
            case BOOLEAN -> "Boolean";
        };
    }

    // ------------------------------------------------------------------ ejecución

    /** Estado inicial: cada variable con su valor vacío (0, "", false) y todas visibles y habilitadas. */
    public Map<String, Object> estadoInicial() {
        Map<String, Object> estado = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        OptimistProcedimiento p = eventos.isEmpty() ? null : compilado(eventos.keySet().iterator().next());
        for (String[] d : declaraciones()) {
            OptimistProcedimiento.Variable v = p == null ? null : p.variable(d[0]);
            estado.put(d[0], d[0].startsWith("WbpVisible_") || d[0].startsWith("WbpEnabled_") ? Boolean.TRUE : OptimistProcedimiento.vacio(v));
        }
        for (Campo c : variables.campos()) if (c.defectoActual() != null) estado.put(c.nombre(), c.defectoActual());
        for (Grilla g : grillas) estado.put(g.nombre(), new ArrayList<>());   // V61: la pantalla pone los renglones vacíos
        estado.put("Pgmname", nombre);   // V66t
        return estado;
    }

    /** V66: una condición o cálculo de una grilla sobre las columnas del renglón. */
    private OptimistFormula formulaGrilla(String gn, String prop, Object texto, Set<String> columnas) {
        if (texto == null || String.valueOf(texto).isBlank()) throw error("grilla " + gn + ": " + prop + ": falta la condición");
        OptimistFormula f;
        try { f = OptimistFormula.de(String.valueOf(texto)); }
        catch (IllegalArgumentException e) { throw error("grilla " + gn + ": " + prop + " '" + texto + "': " + e.getMessage()); }
        for (String a : f.atributos())
            if (!columnas.contains(a)) throw error("grilla " + gn + ": " + prop + " '" + texto + "': no existe la columna " + a);
        return f;
    }

    /** V66: {Columna: "condición o cálculo", ...} de una grilla. */
    @SuppressWarnings("unchecked")
    private Map<String, OptimistFormula> condicionesGrilla(String gn, String prop, Object o, Set<String> columnas) {
        if (o == null) return Map.of();
        if (!(o instanceof Map<?, ?> m)) throw error("grilla " + gn + ": '" + prop + "' debe ser {Columna: \"expresión\", ...}");
        Map<String, OptimistFormula> r = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
            String c = String.valueOf(e.getKey()).trim();
            if (!columnas.contains(c)) throw error("grilla " + gn + ": " + prop + ": no existe la columna " + c);
            r.put(c, formulaGrilla(gn, prop, e.getValue(), columnas));
        }
        return Collections.unmodifiableMap(r);
    }

    /** V66: evalúa una condición / cálculo de la grilla con los valores de un renglón. */
    public static Object evaluarRenglon(OptimistFormula f, Map<String, ?> renglon) {
        OptimistProcedimiento.Sdt r = new OptimistProcedimiento.Sdt(renglon);
        try { return f.evaluar(a -> r.get(a)); }
        catch (RuntimeException e) { return null; }
    }

    /** V66: la condición se cumple con los valores del renglón (si no se puede evaluar, no). */
    public static boolean cumple(OptimistFormula f, Map<String, ?> renglon) {
        Object v = evaluarRenglon(f, renglon);
        return v != null && OptimistFormula.verdad(v);
    }

    /** V61: un renglón nuevo de la grilla: cada columna con su valor vacío (o su default). */
    public static OptimistProcedimiento.Sdt renglonVacio(Grilla g) {
        OptimistProcedimiento.Sdt r = new OptimistProcedimiento.Sdt();
        for (Campo c : g.columnas().campos()) {
            Object v = c.defectoActual();
            if (v == null) v = switch (c.dominio().tipo()) {
                case NUMERIC -> java.math.BigDecimal.ZERO;
                case BOOLEAN -> Boolean.FALSE;
                case CHAR, VARCHAR -> "";
                default -> null;
            };
            r.put(c.nombre(), v);
        }
        return r;
    }

    /** V48: los valores con que se abre el panel (Parm), en orden; los que sobran o faltan se ignoran. */
    public void ponerParametros(Map<String, Object> estado, List<Object> valores) {
        if (valores == null) return;
        // V49h: cada valor llevado al tipo de su variable (de la base viene Integer / Short: la pantalla no lo mostraba y quedaba en 0)
        OptimistProcedimiento p = eventos.isEmpty() ? null : compilado(eventos.keySet().iterator().next());
        for (int i = 0; i < parm.size() && i < valores.size(); i++) {
            String n = parm.get(i);
            Object v = valores.get(i);
            Campo c = variables.campo(n);
            try {
                if (c != null && v != null) v = OptimistTrnDefinicion.normalizar(c.dominio(), v);
                else if (p != null && p.variable(n) != null) v = OptimistProcedimiento.tipar(p.variable(n), v instanceof Number x && !(x instanceof java.math.BigDecimal) ? new java.math.BigDecimal(x.toString()) : v);
            } catch (RuntimeException ignorado) { }
            estado.put(n, v);
        }
    }

    /**
     * Ejecuta un evento con el estado de la pantalla (entra y sale por 'estado'). Un error que no es Msg() corta el evento
     * y deshace lo que haya grabado (así un 'Confirmar' a medias no deja un registro creado).
     */
    public Resultado ejecutar(String evento, Map<String, Object> estado, Connection c) {
        String e = normalizar(evento);
        List<String> mensajes = new ArrayList<>();
        if (!eventos.containsKey(e)) return new Resultado(mensajes, false, null);
        OptimistProcedimientos.asegurarNativos();   // registra LoadWWPContext y los demás nativos antes del primer evento
        OptimistProcedimiento p = compilado(e);
        estado.put("WbpAbrir", "");
        estado.put("WbpModo", "");
        estado.put("WbpParametros", new ArrayList<>());
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx != null) ctx.poner("MensajeError", "");
        OptimistProcedimiento.tomarRetorno();
        OptimistProcedimiento.tomarPdfs();   // V49f: nada pendiente de antes (los PDF que se mandan por mail se arman en el evento)
        List<Object> args = new ArrayList<>();
        for (OptimistProcedimiento.Parametro x : p.parametros) args.add(estado.get(x.nombre()));
        boolean auto = true;
        List<Object[]> pdfs = List.of();
        try {
            if (c != null) { auto = c.getAutoCommit(); if (auto) c.setAutoCommit(false); }
            Map<String, Object> salida = p.ejecutar(args, c, OptimistProcedimientos::buscar, mensajes, 0);
            if (c != null) c.commit();
            for (OptimistProcedimiento.Parametro x : p.parametros) estado.put(x.nombre(), salida.get(x.nombre().toLowerCase(Locale.ROOT)));
            pdfs = OptimistProcedimiento.tomarPdfs();   // V65: los PDF que generó el evento (ej. Libro IVA Ventas) se muestran
        } catch (SQLException | RuntimeException ex) {
            OptimistProcedimiento.tomarPdfs();
            try { if (c != null) c.rollback(); } catch (SQLException ignorado) { }
            mensajes.add("Error en " + evento.trim() + ": " + ex.getMessage());
            return new Resultado(mensajes, true, null);
        } finally {
            try { if (c != null && auto) c.setAutoCommit(true); } catch (SQLException ignorado) { }
        }
        String abrir = String.valueOf(Objects.requireNonNullElse(estado.get("WbpAbrir"), "")).trim();
        boolean cerrar = OptimistProcedimiento.tomarRetorno();
        String aviso = "";
        if (ctx != null) {
            Object me = ctx.valores().entrySet().stream().filter(x -> "MensajeError".equalsIgnoreCase(x.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (me instanceof String s && !s.isBlank()) { aviso = s.strip(); ctx.poner("MensajeError", ""); }
        }
        String modoAbrir = String.valueOf(Objects.requireNonNullElse(estado.get("WbpModo"), "Update")).trim();
        return new Resultado(mensajes, false, abrir.isEmpty() ? null
                : new Abrir(abrir, modoAbrir, "Panel".equalsIgnoreCase(modoAbrir) ? estado.get("WbpParametros") : estado.get("WbpClave")),
                cerrar, aviso, pdfs);
    }

    // ------------------------------------------------------------------ lupa

    /** Filas para la lupa de una variable (hasta 300), filtrando por código o descripción. Cada fila: columna -> valor. */
    public List<Map<String, Object>> buscar(Prompt p, String texto, Map<String, Object> estado, Connection c) throws SQLException {
        return buscar(p, texto, null, estado, c, 300, 0);
    }

    /** V50k: los valores de los 'donde' de la lupa con las variables del panel resueltas (la variable vacía no filtra). */
    public Map<String, Object> dondeResuelto(Prompt p, Map<String, Object> estado) {
        Map<String, Object> r = new LinkedHashMap<>();
        p.donde().forEach((col, v) -> {
            Object x = v instanceof String s && s.startsWith("&") ? estado.get(s.substring(1)) : v;
            if (x == null || (x instanceof String s2 && s2.isBlank())) return;
            r.put(col, x);
        });
        return r;
    }

    /** V50k: con un filtro por columna (código y descripción, como el WorkWith) y por páginas (antes, hasta 300 y nada más). */
    public List<Map<String, Object>> buscar(Prompt p, String texto, Map<String, String> filtros, Map<String, Object> estado,
                                            Connection c, int limite, int desde) throws SQLException {
        LinkedHashSet<String> cols = new LinkedHashSet<>(p.devuelve().values());
        cols.add(p.codigo());
        if (p.descripcion() != null) cols.add(p.descripcion());
        List<String> cond = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        p.donde().forEach((col, v) -> {
            Object x = v instanceof String s && s.startsWith("&") ? estado.get(s.substring(1)) : v;
            if (x == null || (x instanceof String s2 && s2.isBlank())) return;   // la variable vacía no filtra
            igualOEn(col, x, cond, params);   // V66y: una lista de valores = cualquiera de ellos
        });
        if (texto != null && !texto.isBlank()) {
            String desc = p.descripcion() == null ? p.codigo() : p.descripcion();
            cond.add("(CAST(" + p.codigo() + " AS CHAR) LIKE ? OR CAST(" + desc + " AS CHAR) LIKE ?)");
            params.add("%" + texto.trim() + "%");
            params.add("%" + texto.trim() + "%");
        }
        if (filtros != null) {
            String c1 = OptimistTrnDatos.condicionFiltro(filtros.get(p.codigo()), p.codigo(), null, params);
            if (c1 != null) cond.add(c1);
            if (p.descripcion() != null) {
                String c2 = OptimistTrnDatos.condicionFiltro(filtros.get(p.descripcion()), p.descripcion(), null, params);
                if (c2 != null) cond.add(c2);
            }
        }
        String desdeWhere = p.tabla() + (cond.isEmpty() ? "" : " WHERE " + String.join(" AND ", cond));
        if (limite < 0) {   // V68: cuántas filas cumplen (la lupa por páginas)
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + desdeWhere)) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Object> f = new HashMap<>();
                    f.put(OptimistTrnDatos.TOTAL, rs.next() ? rs.getLong(1) : 0L);
                    return new ArrayList<>(List.of(f));
                }
            }
        }
        Map<String, String> exprs = new LinkedHashMap<>();
        exprs.put(p.codigo(), p.codigo());
        if (p.descripcion() != null) exprs.put(p.descripcion(), p.descripcion());
        String sql = "SELECT " + String.join(",", cols) + " FROM " + desdeWhere
                + " ORDER BY " + OptimistTrnDatos.ordenLupa(filtros, exprs, p.codigo()) + " LIMIT " + Math.max(1, Math.min(limite, 1000)) + (desde > 0 ? " OFFSET " + desde : "");
        List<Map<String, Object>> r = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> lc = new ArrayList<>(cols);
                while (rs.next()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    for (int i = 0; i < lc.size(); i++) {
                        Object o = rs.getObject(i + 1);
                        f.put(lc.get(i), o instanceof String s ? s.stripTrailing() : o);
                    }
                    r.add(f);
                }
            }
        }
        return r;
    }

    /** V65: ¿existe en la tabla de la lupa un registro con ese código (y los 'donde' de la lupa)? Para avisar al salir del campo. */
    public boolean existe(Prompt p, Object valor, Map<String, Object> estado, Connection c) throws SQLException {
        List<String> cond = new ArrayList<>(List.of(p.codigo() + " = ?"));
        List<Object> params = new ArrayList<>(List.of(valor));
        dondeResuelto(p, estado).forEach((col, x) -> igualOEn(col, x, cond, params));
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + p.tabla() + " WHERE " + String.join(" AND ", cond) + " LIMIT 1")) {
            for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** V66y: "col = ?" o, con una lista de valores en el 'donde' (ej. {Status: [P, V]}), "col IN (?, ?)". */
    static void igualOEn(String col, Object x, List<String> cond, List<Object> params) {
        if (x instanceof Collection<?> l && !l.isEmpty()) {
            cond.add(col + " IN (" + String.join(",", Collections.nCopies(l.size(), "?")) + ")");
            params.addAll(l);
        } else {
            cond.add(col + " = ?");
            params.add(x);
        }
    }

    public Prompt promptDe(String variable) {
        return prompts.stream().filter(p -> p.variable().equalsIgnoreCase(variable)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ utilidades

    private String variableExistente(String n, String donde) {
        Campo c = variables.campo(n.trim());
        if (c == null) throw error(donde + ": " + n + " no es una variable del panel");
        return c.nombre();
    }

    private static String ident(String s, String donde) {
        if (s == null || !IDENT.matcher(s.trim()).matches()) throw error(donde + ": nombre inválido '" + s + "'");
        return s.trim();
    }

    private static IllegalArgumentException error(String m) { return new IllegalArgumentException(m); }
}
