package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Nivel;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * V52 - Consultas (resources/consultas/<Nombre>.yaml): un WorkWith de solo lectura sobre la tabla de una TRN, con
 * parámetros arriba de la grilla y columnas que calcula la aplicación (calculo: stock -> en OPTIMIST ERP, OptimistConsultaStock). La grilla se pagina en
 * la base: con la condición en "Todo" y sin filtrar ni ordenar por una columna calculada, se calcula solo la página que se
 * ve; si no, la condición, el filtro y el orden van al SQL (una consulta, no una por renglón como en GeneXus).
 *
 * Ruta: cns/<nombre en minúscula>, el mismo nombre que el programa de GeneXus (cns/extrawwarticuloscnsstock), así la
 * opción del menú de la base (MenuURL) la encuentra sola.
 */
@Route("cns/:nombre")
// V54: la misma consulta durante toda la sesión de esa pestaña: al volver de un comprobante abierto desde una lupa, queda
// como estaba (página, filtros, orden) y con la ventana de detalle abierta
@com.vaadin.flow.spring.annotation.SpringComponent
@com.vaadin.flow.spring.annotation.UIScope
public class OptimistConsultaView extends VerticalLayout implements BeforeEnterObserver {

    final OptimistDatabase db;
    final OptimistTrnDatos datos;

    public OptimistConsultaView(@Autowired OptimistDatabase db, @Autowired OptimistTrnDatos datos) {
        this.db = db;
        this.datos = datos;
    }

    // ------------------------------------------------------------------ la definición (YAML)

    /**
     * Un parámetro de arriba de la grilla: un combo con valores fijos o de una tabla. V66l: tipo "fecha" (un campo fecha,
     * default "hoy"); la tabla puede tener "donde" (condición SQL con &Variables, ej. el modelo según el CPI elegido);
     * vacio: el combo se puede dejar sin elegir; recordar: false = no se guarda lo que eligió el usuario.
     */
    public record Parametro(String nombre, String etiqueta, Map<Object, String> valores, Map<String, String> tabla, Object defecto,
                            String tipo, boolean vacio, boolean recordar) {
        public Parametro(String nombre, String etiqueta, Map<Object, String> valores, Map<String, String> tabla, Object defecto) {
            this(nombre, etiqueta, valores, tabla, defecto, "combo", !tabla.isEmpty(), true);
        }
    }

    /** V66l: una variable de la consulta (&Nombre en el SQL): un parámetro de ParamGlb (tipo alfa, numero o boolean) o un valor fijo. */
    public record Variable(String nombre, Integer paramGlb, String tipo, Object valor, Integer proceso) {}   // V82g: proceso = GetUsrProc (true / false)

    /** V66l: una regla de estilo de una columna: si se cumple 'si' (null = siempre), la clase de GeneXus (o &Variable) o el CSS. */
    public record Regla(String si, String clase, String estilo) {}

    /**
     * V66l: una acción del renglón o un botón de arriba: tipo cns / panel / trn / proc, con sus parámetros (campos del
     * renglón o de la cabecera, &Variables, constantes) y la condición 'si'. modo: el de la TRN (Display / Update).
     */
    public record AccionCns(String titulo, String icono, String si, String tipo, String objeto, String modo, List<String> parametros,
                            String confirmar) {}

    /** V66l: un dato de la cabecera: tipo texto / numero / fecha; trn + clave = vínculo que la abre en Visualizar; mail = mailto. */
    public record CampoCab(String nombre, String etiqueta, String tipo, String formato, String trn, String clave, boolean mail) {}

    /** V66l: los datos de arriba de la grilla (ej. la entidad y su saldo): un SELECT de un renglón, con &Variables. */
    public record Cabecera(String sql, List<CampoCab> campos) {}

    /**
     * V66k: lo que recibe la consulta de quien la abre (en la dirección: cns/nombre?AsientosId=25): cada uno filtra con su
     * condición SQL ('?' = el valor; la tabla de la TRN es t0). obligatorio: sin ese valor la consulta no se abre.
     */
    public record Recibe(String nombre, boolean obligatorio, String sql) {}   // V66l: sql puede faltar (se usa como &Nombre)

    /** V66k: columna calculada en la misma consulta SQL (con el 'join' de la consulta). tipo: texto, numero o fecha. */
    public record ColSql(String nombre, String titulo, String tipo, String formato, String sql, boolean oculta) {}   // V66l: oculta

    /** V66k: vínculo de una columna a la TRN que corresponda según el renglón (casos: {si, trn}, el primero que se cumple). */
    public record EnlaceCns(String clave, List<String[]> casos, String cns, List<String> parametros, String si) {}   // V66l: a otra consulta

    /** V66k: un texto debajo del título (ej. el asiento), con un SELECT que usa lo recibido. */
    public record Subtitulo(String sql, List<String> parametros) {}

    /** consultas/<Nombre>.yaml. procesos: columna -> procesos (GetUsrProc) que tienen que estar habilitados para verla. */
    public record Consulta(String nombre, String titulo, String plural, String trn, String calculo, List<Parametro> parametros,
                           List<String> columnas, Map<String, String> titulos, Map<String, String> anchos,
                           Map<String, List<Integer>> procesos, List<Recibe> recibe, String join, List<ColSql> columnasSql,
                           Map<String, EnlaceCns> enlaces, Subtitulo subtitulo, List<String> ocultas,
                           List<Variable> variables, String condicion, Map<String, List<Regla>> estilos, List<AccionCns> acciones,
                           List<AccionCns> botones, Cabecera cabecera, List<String> orden, Map<String, Boolean> totales,
                           String totalesMoneda) {
        /** V75: ¿la columna va con total al pie? 'totales:' / 'sinTotales:' de la consulta; si no, los campos con dominio de importe. */
        boolean totaliza(String col, Campo c) {
            for (Map.Entry<String, Boolean> e : totales.entrySet()) if (e.getKey().equalsIgnoreCase(col)) return e.getValue();
            return c != null && OptimistTrnDefinicion.dominioDeTotal(c);
        }
    }

    @SuppressWarnings("unchecked")
    static Consulta cargar(String nombre) {
        if (!nombre.matches("[A-Za-z0-9_]{1,80}")) throw new IllegalArgumentException("nombre inválido");
        Map<String, Object> y = null;
        String archivo = nombre;
        try {
            for (org.springframework.core.io.Resource r : OptimistRecursos.listar("consultas", ".yaml")) {
                String f = r.getFilename();
                if (f != null && f.equalsIgnoreCase(nombre + ".yaml")) {
                    try (InputStream in = r.getInputStream()) { y = new Yaml().load(in); }
                    archivo = f.substring(0, f.length() - 5);
                    break;
                }
            }
        } catch (Exception e) { throw new IllegalArgumentException("no se pudo leer consultas/" + nombre + ".yaml: " + e.getMessage()); }
        if (y == null) throw new IllegalArgumentException("no existe consultas/" + nombre + ".yaml");
        List<Parametro> ps = new ArrayList<>();
        for (Object o : (List<Object>) y.getOrDefault("parametros", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            Map<Object, String> valores = new LinkedHashMap<>();
            if (m.get("valores") instanceof Map<?, ?> vs) vs.forEach((k, v) -> valores.put(k, String.valueOf(v)));
            Map<String, String> tabla = new LinkedHashMap<>();
            if (m.get("tabla") instanceof Map<?, ?> t) t.forEach((k, v) -> tabla.put(String.valueOf(k), String.valueOf(v)));
            String n = String.valueOf(m.get("nombre"));
            String tipo = String.valueOf(m.getOrDefault("tipo", "combo")).trim().toLowerCase(Locale.ROOT);
            if (!Set.of("combo", "fecha").contains(tipo)) throw new IllegalArgumentException("parámetro " + n + ": tipo combo o fecha");
            ps.add(new Parametro(n, String.valueOf(m.getOrDefault("etiqueta", n)), valores, tabla, m.get("default"), tipo,
                    m.get("vacio") instanceof Boolean b ? b : !tabla.isEmpty(), !Boolean.FALSE.equals(m.get("recordar"))));
        }
        Map<String, List<Integer>> procesos = new LinkedHashMap<>();
        if (y.get("procesos") instanceof Map<?, ?> pm)
            pm.forEach((k, v) -> {
                List<Integer> l = new ArrayList<>();
                if (v instanceof List<?> lv) for (Object x : lv) l.add(Integer.parseInt(String.valueOf(x).trim()));
                else l.add(Integer.parseInt(String.valueOf(v).trim()));
                procesos.put(String.valueOf(k), l);
            });
        // V66k: recibe, join, columnasSql, enlaces, subtitulo, ocultas
        List<Recibe> recibe = new ArrayList<>();
        for (Object o : (List<Object>) y.getOrDefault("recibe", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            String n = String.valueOf(m.get("nombre")).trim();
            if (!n.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("recibe: nombre inválido " + n);
            recibe.add(new Recibe(n, Boolean.TRUE.equals(m.get("obligatorio")), m.get("sql") == null ? null : String.valueOf(m.get("sql")).trim()));
        }
        List<ColSql> colsSql = new ArrayList<>();
        for (Object o : (List<Object>) y.getOrDefault("columnasSql", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            String n = String.valueOf(m.get("nombre")).trim();
            if (!n.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("columnasSql: nombre inválido " + n);
            if (m.get("sql") == null) throw new IllegalArgumentException("columnasSql " + n + ": falta 'sql'");
            String tipo = String.valueOf(m.getOrDefault("tipo", "texto")).trim().toLowerCase(Locale.ROOT);
            if (!Set.of("texto", "numero", "fecha").contains(tipo)) throw new IllegalArgumentException("columnasSql " + n + ": tipo texto, numero o fecha");
            colsSql.add(new ColSql(n, String.valueOf(m.getOrDefault("titulo", n)), tipo, m.get("formato") == null ? null : String.valueOf(m.get("formato")),
                    String.valueOf(m.get("sql")).trim(), Boolean.TRUE.equals(m.get("oculta"))));
        }
        Map<String, EnlaceCns> enlaces = new LinkedHashMap<>();
        if (y.get("enlaces") instanceof Map<?, ?> em)
            em.forEach((k, v) -> {
                Map<String, Object> m = (Map<String, Object>) v;
                List<String[]> casos = new ArrayList<>();
                for (Object c : (List<Object>) m.getOrDefault("trn", List.of())) {
                    Map<String, Object> cm = (Map<String, Object>) c;
                    String si = cm.get("si") == null ? null : String.valueOf(cm.get("si"));
                    if (si != null) OptimistFormula.de(si);   // que esté bien escrita
                    casos.add(new String[]{si, String.valueOf(cm.get("trn")).trim()});
                }
                String si = m.get("si") == null ? null : String.valueOf(m.get("si"));
                if (si != null) OptimistFormula.de(si);
                List<String> pars = m.get("parametros") instanceof List<?> lp ? lp.stream().map(x -> String.valueOf(x).trim()).toList() : List.of();
                if (m.get("cns") == null && m.get("clave") == null) throw new IllegalArgumentException("enlace de " + k + ": falta 'clave' (o 'cns')");
                enlaces.put(String.valueOf(k), new EnlaceCns(m.get("clave") == null ? null : String.valueOf(m.get("clave")).trim(), casos,
                        m.get("cns") == null ? null : String.valueOf(m.get("cns")).trim(), pars, si));
            });
        Subtitulo sub = null;
        if (y.get("subtitulo") instanceof Map<?, ?> sm && sm.get("sql") != null)
            sub = new Subtitulo(String.valueOf(sm.get("sql")), sm.get("parametros") instanceof List<?> l ? l.stream().map(x -> String.valueOf(x).trim()).toList() : List.of());
        // V66l: variables, condicion, estilos, acciones, botones, cabecera
        List<Variable> variables = new ArrayList<>();
        if (y.get("variables") instanceof Map<?, ?> vm)
            vm.forEach((k, v) -> {
                String n = String.valueOf(k).trim();
                if (!n.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("variables: nombre inválido " + n);
                if (v instanceof Map<?, ?> mv) {
                    Integer pg = mv.get("paramGlb") == null ? null : Integer.parseInt(String.valueOf(mv.get("paramGlb")).trim());
                    String tipo = (mv.get("tipo") == null ? "alfa" : String.valueOf(mv.get("tipo"))).trim().toLowerCase(Locale.ROOT);
                    if (!Set.of("alfa", "numero", "boolean").contains(tipo)) throw new IllegalArgumentException("variable " + n + ": tipo alfa, numero o boolean");
                    Integer pr = mv.get("proceso") == null ? null : Integer.parseInt(String.valueOf(mv.get("proceso")).trim());
                    variables.add(new Variable(n, pg, pr != null ? "boolean" : tipo, mv.get("valor"), pr));
                } else variables.add(new Variable(n, null, "alfa", v, null));
            });
        Map<String, List<Regla>> estilos = new LinkedHashMap<>();
        if (y.get("estilos") instanceof Map<?, ?> em2)
            em2.forEach((k, v) -> {
                List<Regla> rs = new ArrayList<>();
                for (Object o : v instanceof List<?> l ? l : List.of(v)) {
                    Map<String, Object> m = (Map<String, Object>) o;
                    String si = m.get("si") == null ? null : String.valueOf(m.get("si"));
                    if (si != null) OptimistFormula.de(si);
                    rs.add(new Regla(si, m.get("clase") == null ? null : String.valueOf(m.get("clase")).trim(),
                            m.get("estilo") == null ? null : String.valueOf(m.get("estilo")).trim()));
                }
                estilos.put(String.valueOf(k), rs);
            });
        Cabecera cabecera = null;
        if (y.get("cabecera") instanceof Map<?, ?> cm && cm.get("sql") != null) {
            List<CampoCab> cs = new ArrayList<>();
            for (Object o : cm.get("campos") instanceof List<?> l ? l : List.of()) {
                Map<String, Object> m = (Map<String, Object>) o;
                String n = String.valueOf(m.get("nombre")).trim();
                cs.add(new CampoCab(n, String.valueOf(m.getOrDefault("etiqueta", n)), String.valueOf(m.getOrDefault("tipo", "texto")).trim().toLowerCase(Locale.ROOT),
                        m.get("formato") == null ? null : String.valueOf(m.get("formato")), m.get("trn") == null ? null : String.valueOf(m.get("trn")).trim(),
                        m.get("clave") == null ? null : String.valueOf(m.get("clave")).trim(), Boolean.TRUE.equals(m.get("mail"))));
            }
            cabecera = new Cabecera(String.valueOf(cm.get("sql")), List.copyOf(cs));
        }
        return new Consulta(archivo, String.valueOf(y.getOrDefault("titulo", archivo)), String.valueOf(y.getOrDefault("plural", "registros")),
                String.valueOf(y.get("trn")), y.get("calculo") == null ? "" : String.valueOf(y.get("calculo")), ps,
                ((List<Object>) y.getOrDefault("columnas", List.of())).stream().map(String::valueOf).toList(),
                texto((Map<Object, Object>) y.getOrDefault("titulos", Map.of())), texto((Map<Object, Object>) y.getOrDefault("anchos", Map.of())),
                procesos, List.copyOf(recibe), y.get("join") == null ? null : String.valueOf(y.get("join")).trim(), List.copyOf(colsSql),
                enlaces, sub, ((List<Object>) y.getOrDefault("ocultas", List.of())).stream().map(x -> String.valueOf(x).trim()).toList(),
                List.copyOf(variables), y.get("condicion") == null ? null : String.valueOf(y.get("condicion")).trim(), estilos,
                acciones((List<Object>) y.getOrDefault("acciones", List.of()), "acciones"),
                acciones((List<Object>) y.getOrDefault("botones", List.of()), "botones"), cabecera,
                ((List<Object>) y.getOrDefault("orden", List.of())).stream().map(x -> String.valueOf(x).trim()).toList(),
                OptimistTrnDefinicion.ajustesTotales(y.get("totales"), y.get("sinTotales")),
                y.get("totalesMoneda") == null ? null : String.valueOf(y.get("totalesMoneda")).trim());
    }

    /** V66l: acciones / botones del YAML: {titulo, icono, si, cns|panel|trn|proc: Nombre, modo, parametros, confirmar}. */
    @SuppressWarnings("unchecked")
    private static List<AccionCns> acciones(List<Object> l, String donde) {
        List<AccionCns> r = new ArrayList<>();
        for (Object o : l) {
            Map<String, Object> m = (Map<String, Object>) o;
            String tipo = null, objeto = null;
            for (String k : List.of("cns", "panel", "trn", "proc"))
                if (m.get(k) != null) { tipo = k; objeto = String.valueOf(m.get(k)).trim(); break; }
            String titulo = String.valueOf(m.getOrDefault("titulo", objeto));
            if (tipo == null) throw new IllegalArgumentException(donde + " '" + titulo + "': falta cns, panel, trn o proc");
            String si = m.get("si") == null ? null : String.valueOf(m.get("si"));
            if (si != null) OptimistFormula.de(si);
            r.add(new AccionCns(titulo, m.get("icono") == null ? null : String.valueOf(m.get("icono")).trim(), si, tipo, objeto,
                    m.get("modo") == null ? null : String.valueOf(m.get("modo")).trim(),
                    m.get("parametros") instanceof List<?> lp ? lp.stream().map(x -> String.valueOf(x).trim()).toList() : List.of(),
                    m.get("confirmar") == null ? null : String.valueOf(m.get("confirmar"))));
        }
        return List.copyOf(r);
    }

    private static Map<String, String> texto(Map<Object, Object> m) {
        Map<String, String> r = new LinkedHashMap<>();
        m.forEach((k, v) -> r.put(String.valueOf(k), String.valueOf(v)));
        return r;
    }

    /** Los nombres de las consultas que hay (en minúscula), para el menú. */
    static Set<String> nombres() {
        Set<String> r = new HashSet<>();
        try {
            for (org.springframework.core.io.Resource x : OptimistRecursos.listar("consultas", ".yaml")) {
                String f = x.getFilename();
                if (f != null && f.toLowerCase(Locale.ROOT).endsWith(".yaml")) r.add(f.substring(0, f.length() - 5).toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignorado) { }
        return r;
    }

    // ------------------------------------------------------------------ la pantalla

    /**
     * E3: una consulta con 'calculo: nombre' (OPTIMIST ERP: 'stock'): la arma la aplicación (OptimistExtension.calculosConsulta).
     * Trabaja sobre esta vista: def, trn, pantalla, combos, procesos, recibidos... (son del paquete) y puede guardar lo suyo en
     * estadoCalculo.
     */
    public interface Calculo {
        /** Las columnas que calcula (no son de la tabla de la TRN). */
        Set<String> columnas();
        /** Arma la pantalla de la consulta. */
        void construir(OptimistConsultaView vista) throws SQLException;
    }

    /** El cálculo de ese nombre que trae la aplicación, o null. */
    static Calculo calculo(String nombre) {
        String n = nombre == null ? "" : nombre.trim().toLowerCase(Locale.ROOT);
        for (OptimistExtension e : OptimistExtension.todas()) {
            for (Map.Entry<String, Calculo> x : e.calculosConsulta().entrySet())
                if (x.getKey().equalsIgnoreCase(n)) return x.getValue();
        }
        return null;
    }

    record Opcion(Object valor, String texto) {
        @Override public String toString() { return texto; }
    }

    // E3: lo que sigue es del paquete (no private): lo usan las consultas con 'calculo:' de la aplicación (ej. el stock del ERP)
    Consulta def;
    Trn trn;
    OptimistWorkWith.Pantalla<Map<String, Object>> pantalla;
    final Map<String, ComboBox<Opcion>> combos = new LinkedHashMap<>();
    final Set<String> calculadas = new LinkedHashSet<>();
    final Map<Integer, Boolean> procesos = new HashMap<>();
    /** E3: lo propio del 'calculo:' de la consulta (lo arma y lo usa la aplicación); se guarda con la consulta para volver a ella. */
    Object estadoCalculo;
    private final OptimistUserConfig preferencias = new OptimistUserConfig();
    private String usuario;
    /** V54: usuario, cliente y consulta con que se armó: si se vuelve con los mismos, la consulta queda como estaba. */
    private String firma;

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        if (OptimistShell.usuario() == null) { OptimistShell.alLogin(e); return; }
        if (OptimistVisorPdf.volviendoDePdf() && pantalla != null) return;   // V84h: el Atrás cerró una ventana: todo queda como estaba
        usuario = OptimistShell.usuario();
        String nombre = e.getRouteParameters().get("nombre").orElse("");
        String ruta = "cns/" + nombre.toLowerCase(Locale.ROOT);
        OptimistShell.rutaActual(ruta);
        if (OptimistShell.sinPermiso(this, e, ruta)) return;
        OptimistDatabase.Cliente cl = OptimistDatabase.clienteActual();
        // V66k: lo que recibe de quien la abrió (cns/x?AsientosId=25): otra dirección = otra consulta
        Map<String, List<String>> qp = new java.util.LinkedHashMap<>(e.getLocation().getQueryParameters().getParameters());
        qp.remove(OptimistVisorPdf.PARAM_PDF);   // V84h: el paso del PDF en el historial
        String f = usuario + "|" + (cl == null ? "" : cl.uuid()) + "|" + nombre.toLowerCase(Locale.ROOT) + "|" + new TreeMap<>(qp);
        // V54: la misma consulta: los datos al día, en la misma página. V55a: salvo que hayan cambiado los procesos del
        // usuario (ej. le habilitaron el 156): entonces se rearma con sus columnas.
        if (f.equals(firma) && pantalla != null && mismosProcesos()) {
            if (cajaCabecera != null || !botonesArriba.isEmpty()) refrescarCabecera();   // V66l: ej. el saldo después de una cobranza
            pantalla.cargar(List.of());
            return;
        }
        // V66l: volver a una consulta de antes (ej. de los movimientos a Cuentas Corrientes, con Volver): queda como estaba
        // (parámetros, fecha, filtros, página), con los datos al día. La vista es una sola para todas las consultas de la
        // pestaña: se guarda cómo quedó la que se deja.
        if (firma != null && pantalla != null) {
            estados.put(firma, guardarEstado());
            while (estados.size() > 6) estados.remove(estados.keySet().iterator().next());
        }
        Estado previo = estados.remove(f);
        if (previo != null) {
            restaurar(previo);
            if (mismosProcesos()) {
                firma = f;
                removeAll();
                OptimistShell.montar(this, pantalla.contenido, def.titulo());
                if (cajaCabecera != null || !botonesArriba.isEmpty()) refrescarCabecera();
                pantalla.cargar(List.of());
                return;
            }
        }
        firma = null;
        try {
            def = cargar(nombre);
            trn = OptimistPanelView.trn(def.trn());
            if (trn == null) throw new IllegalArgumentException("la TRN " + def.trn() + " no existe");
            recibidos.clear();
            for (Recibe r : def.recibe())
                for (Map.Entry<String, List<String>> q : qp.entrySet())
                    if (q.getKey().equalsIgnoreCase(r.nombre()) && !q.getValue().isEmpty() && !q.getValue().get(0).isBlank())
                        recibidos.put(r.nombre(), q.getValue().get(0).trim());
            if (def.calculo().isBlank()) construirGeneral();   // V66k: sin cálculo
            else {   // E3: 'calculo: X' lo arma la aplicación (OPTIMIST ERP: stock)
                Calculo k = calculo(def.calculo());
                if (k == null) throw new IllegalArgumentException("cálculo desconocido: " + def.calculo() + " (la aplicación no tiene ese cálculo)");
                k.construir(this);
            }
            firma = f;
        } catch (RuntimeException | SQLException ex) {
            Span aviso = new Span("No se pudo abrir la consulta " + nombre + ": " + ex.getMessage());
            aviso.getStyle().set("padding", "28px").set("color", "#ff8585");
            OptimistShell.montar(this, aviso, "Consulta");
        }
    }

    /**
     * Los procesos (GetUsrProc) que deciden las columnas: los de 'procesos:' del YAML y los que la aplicación lee en todas sus
     * consultas, con sus reglas (E3; OPTIMIST ERP: 156 / 157 bultos y cantidad, 107 pendiente de entrega, 78 movimientos de
     * la empresa; sin el 156 ni el 157, la cantidad).
     */
    Map<Integer, Boolean> leerProcesos(Connection c) {
        Map<Integer, Boolean> r = new HashMap<>();
        Set<Integer> pedir = new TreeSet<>();
        for (OptimistExtension e : OptimistExtension.todas()) pedir.addAll(e.procesosConsulta());
        def.procesos().values().forEach(pedir::addAll);
        for (int p : pedir) r.put(p, OptimistProcedimientos.procesoHabilitado(c, p));
        for (OptimistExtension e : OptimistExtension.todas()) e.ajustarProcesosConsulta(r);
        return r;
    }

    /** V55a: si los procesos del usuario siguen como cuando se armó la pantalla (si cambiaron, hay que rearmarla). */
    private boolean mismosProcesos() {
        try (Connection c = db.openConnection()) {
            return leerProcesos(c).equals(procesos);
        } catch (SQLException | RuntimeException e) {
            return true;   // si no se pudo leer, se deja la pantalla como estaba
        }
    }

    /** La TRN base con las columnas de artículos que pide la consulta (las calculadas no son de la tabla). */
    Trn trnConColumnas() {
        OptimistTrnDefinicion.WorkWith w = trn.workwith();
        List<String> propias = new ArrayList<>();
        Calculo k = def.calculo().isBlank() ? null : calculo(def.calculo());   // E3: las columnas que calcula no son de la tabla
        Set<String> calculables = k == null ? Set.of() : k.columnas();
        for (String c : def.columnas()) if (!calculables.contains(c) && trn.cabecera().campo(c) != null) propias.add(c);
        Map<String, String> titulos = new LinkedHashMap<>(w.titulos());
        titulos.putAll(def.titulos());
        // V54: lo que se trae siempre aunque no sea columna (E3: lo dice la aplicación; OPTIMIST ERP: el código y la descripción
        // del artículo, para el título de las ventanas de las lupas del stock)
        List<String> ocultas = new ArrayList<>();
        for (OptimistExtension e : OptimistExtension.todas())
            for (String c : e.consultaOcultasSiempre()) if (!propias.contains(c) && !ocultas.contains(c) && trn.cabecera().campo(c) != null) ocultas.add(c);
        // V66k: la clave de cada vínculo y las 'ocultas:' de la consulta también se traen
        List<String> mas = new ArrayList<>(def.ocultas());
        def.enlaces().values().forEach(en -> mas.add(en.clave()));
        for (String c : mas) {
            Campo cc = trn.cabecera().campo(c);
            if (cc != null && !propias.contains(cc.nombre()) && !ocultas.contains(cc.nombre())) ocultas.add(cc.nombre());
        }
        OptimistTrnDefinicion.WorkWith ww = new OptimistTrnDefinicion.WorkWith(propias, w.filasPorPagina(), titulos, w.filtro(),
                def.anchos(), ocultas, null, OptimistTrnDefinicion.Acciones.TODAS, w.prompt(), w.promptTitulos(), w.estilos(), true);
        Nivel cab = trn.cabecera();   // V66l: 'orden:' de la consulta (si no, el de la TRN)
        if (!def.orden().isEmpty())
            cab = new Nivel(cab.nombre(), cab.titulo(), cab.tabla(), cab.campos(), def.orden(), cab.numerador(), cab.referencias(), cab.oculto());
        return new Trn(trn.nombre(), trn.ruta(), trn.titulo(), trn.singular(), trn.plural(), trn.femenino(), cab,
                trn.detalles(), trn.solapas(), trn.auditoria(), ww, trn.filas(), trn.tablas(), trn.encabezado(), trn.bloques(),
                trn.inicio(), trn.reglas(), trn.calculo(), trn.archivos());
    }

    void columnaTrn(Campo c, Trn base) {
        if (c == null) return;
        EnlaceCns en = def.enlaces().get(c.nombre());   // V66k: el vínculo va antes de armar la columna
        if (en != null) pantalla.enlaceColumna(c.nombre(), f -> hrefEnlace(en, f));
        String titulo = base.workwith().titulo(c);
        java.util.function.Function<Map<String, Object>, Object> excel = c.dominio().esCombo()
                ? f -> OptimistTrnCampos.mostrar(c, f.get(c.nombre())) : f -> f.get(c.nombre());
        String formato = c.dominio().esNumerico() && !c.dominio().esCombo()
                ? (c.dominio().decimales() > 0 ? "#,##0." + "0".repeat(c.dominio().decimales()) : "0") : null;
        if (def.estilos().containsKey(c.nombre())) pantalla.estiloPorFila(c.nombre(), f -> estiloDe(c.nombre(), f));   // V66l
        if (c.dominio().esCombo()) pantalla.filtroValores(c.nombre(), c.dominio().valores());   // V60: selección múltiple
        else if (c.dominio().esNumerico()) pantalla.tipoFiltro(c.nombre(), "numero");
        else if (c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATE || c.dominio().tipo() == OptimistTrnDefinicion.Tipo.DATETIME)
            pantalla.tipoFiltro(c.nombre(), "fecha");
        else if (c.nombre().equalsIgnoreCase(OptimistTrnDefinicion.monedaTotales(def.totalesMoneda(), def.columnas(), trn.cabecera())))
            pantalla.filtroValores(c.nombre(), datos.monedas());   // V77: la moneda, con combo (igual, no "contiene")
        else {   // E4f: la descripción de una referencia chica (hasta 50 registros): combo de selección múltiple
            List<OptimistTrnDefinicion.Valor> vr = datos.valoresReferencia(base.cabecera(), c);
            if (vr != null) pantalla.filtroValores(c.nombre(), vr);
        }
        Grid.Column<Map<String, Object>> col = pantalla.columna(c.nombre(), titulo, f -> OptimistTrnCampos.mostrar(c, f.get(c.nombre())),
                excel, formato, null, null);
        String ancho = def.anchos().get(c.nombre());
        // V66k3: sin apagar el ancho automático, Vaadin lo recalcula y no respeta el de 'anchos:'
        if (ancho != null) col.setAutoWidth(false).setWidth(ancho).setFlexGrow(OptimistWorkWith.crecimiento(ancho));   // V66l: reparte lo que sobra
        if (!c.dominio().esCombo() && c.dominio().esNumerico()) col.setTextAlign(ColumnTextAlign.END);
        if (def.totaliza(c.nombre(), c)) pantalla.totalizar(c.nombre(), formato);   // V75: total al pie
    }

    // ------------------------------------------------------------------ V66k: consultas sin cálculo de stock

    /** Lo que recibió de quien la abrió (nombre de 'recibe' -> valor). */
    final Map<String, String> recibidos = new LinkedHashMap<>();
    private final Map<String, OptimistFormula> formulasEnlace = new HashMap<>();

    /**
     * Una consulta común: la tabla de la TRN con sus columnas, las columnasSql (calculadas en la misma consulta, con el
     * join), filtrada por lo que recibe (ej. las imputaciones de un asiento). Con lo recibido, un botón Volver y el subtítulo.
     */
    private void construirGeneral() throws SQLException {
        removeAll();
        pantalla = null;
        estadoCalculo = null;
        calculadas.clear();
        procesos.clear();
        combos.clear();
        fechas.clear();
        botonesArriba.clear();
        cabeceraFila = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Recibe r : def.recibe())
            if (r.obligatorio() && !recibidos.containsKey(r.nombre()))
                throw new IllegalArgumentException("esta consulta se abre desde otra pantalla (falta " + r.nombre() + ")");
        // V66l: las variables fijas (ParamGlb) y los parámetros de arriba (combos y fechas)
        try (Connection c = db.openConnection()) {
            leerVariablesFijas(c);
            armarCombos(c, null);
        }
        referenciadas = referencias();
        String sub = subtitulo();
        pantalla = new OptimistWorkWith.Pantalla<>(this, new Grid<>(), def.plural(), "+ Nuevo", () -> { }, f -> def.plural(),
                f -> { }, f -> { }, f -> { }, new H1(def.titulo()), new Span(OptimistDatabase.nombreSistema()));
        pantalla.nuevo.setVisible(false);
        if (def.acciones().isEmpty()) pantalla.sinAcciones();
        else pantalla.acciones(this::accionesDe);   // V66l: acciones del renglón (como el WorkWithPlus de GeneXus)
        HorizontalLayout barra = new HorizontalLayout();
        barra.setAlignItems(FlexComponent.Alignment.END);
        barra.getStyle().set("flex-wrap", "wrap").set("gap", "var(--lumo-space-m)");
        barra.addClassName("optimist-formulario");   // V66p: los parámetros respetan la densidad de los formularios
        if (!recibidos.isEmpty()) {
            // como el "Volver" de GeneXus: a la pantalla que la abrió (el asiento, la lista de asientos)
            com.vaadin.flow.component.button.Button volver = new com.vaadin.flow.component.button.Button("Volver",
                    com.vaadin.flow.component.icon.VaadinIcon.ARROW_LEFT.create(),
                    ev -> com.vaadin.flow.component.UI.getCurrent().getPage().getHistory().back());
            barra.add(volver);
        }
        if (sub != null && !sub.isBlank()) {
            Span s = new Span(sub);
            s.getStyle().set("font-weight", "600").set("color", "var(--optimist-texto)").set("align-self", "center");
            barra.add(s);
            pantalla.subtituloPdf(sub);
        }
        for (Parametro p : def.parametros()) {   // en el orden del YAML
            if (combos.containsKey(p.nombre())) barra.add(combos.get(p.nombre()));
            if (fechas.containsKey(p.nombre())) barra.add(fechas.get(p.nombre()));
        }
        int lugar = 1;
        if (barra.getComponentCount() > 0) pantalla.tarjeta.addComponentAtIndex(lugar++, barra);
        if (def.cabecera() != null) {   // V66l: los datos de arriba (ej. la entidad, su teléfono y su saldo)
            cajaCabecera = new com.vaadin.flow.component.orderedlayout.FlexLayout();
            cajaCabecera.addClassName("optimist-formulario");   // V66p
            cajaCabecera.getStyle().set("flex-wrap", "wrap").set("gap", "var(--lumo-space-xs) var(--lumo-space-l)").set("padding", "var(--lumo-space-s) var(--lumo-space-m)")
                    .set("border", "1px solid var(--optimist-borde, #d7dde3)").set("border-radius", "8px")
                    .set("background", "var(--optimist-fondo-suave, rgba(0,0,0,0.02))");
            pantalla.tarjeta.addComponentAtIndex(lugar++, cajaCabecera);
        } else cajaCabecera = null;
        // V66l: botones de arriba (imprimir, Excel, otra pantalla...), al lado de los de la lista
        int lb = 1;
        for (AccionCns b : def.botones()) {
            com.vaadin.flow.component.button.Button bt = new com.vaadin.flow.component.button.Button(b.titulo(),
                    OptimistWorkWith.iconoAccion(icono(b.icono())).create(), e -> boton(b));
            bt.getElement().setAttribute("title", b.titulo());
            pantalla.barraAcciones.addComponentAtIndex(Math.min(lb++, pantalla.barraAcciones.getComponentCount()), bt);
            botonesArriba.put(b, bt);
        }
        refrescarCabecera();
        Trn base = trnConColumnas();
        Nivel cab = base.cabecera();
        for (String col : def.columnas()) {
            ColSql cs = colSql(col);
            if (cs != null) columnaSql(cs);
            else if (cab.campo(col) != null) columnaTrn(cab.campo(col), base);
            else throw new IllegalArgumentException("la columna " + col + " no es de " + trn.nombre() + " ni de columnasSql");
        }
        pantalla.archivoExcel(def.nombre());
        String monedaTot = OptimistTrnDefinicion.monedaTotales(def.totalesMoneda(), def.columnas(), trn.cabecera());   // V77
        if (monedaTot != null) pantalla.totalesMoneda(monedaTot, f -> f.get(monedaTot));
        pantalla.identificador(f -> {
            StringBuilder k = new StringBuilder();
            for (Campo c : cab.claves()) k.append(f.get(c.nombre())).append('|');
            return k.toString();
        });
        // V66l: el extra se arma en cada pedido (los parámetros y la fecha cambian)
        pantalla.fuente(new OptimistWorkWith.Pantalla.Fuente<>() {
            @Override public int contar(Map<String, String> filtros) throws Exception {
                OptimistTrnDatos.Extra ex = extraGeneral();
                return ex == null ? datos.contar(base, filtros) : datos.contar(base, filtros, ex);
            }
            @Override public OptimistTrnDatos.Conteo contarYSumar(Map<String, String> filtros, java.util.Set<String> sumar, String moneda) throws Exception {
                return datos.contarYSumar(base, filtros, extraGeneral(), sumar, moneda);   // V75 / V77
            }
            @Override public List<Map<String, Object>> pagina(Map<String, String> filtros, String orden, boolean desc, int desde, int cantidad)
                    throws Exception {
                OptimistTrnDatos.Extra ex = extraGeneral();
                return ex == null ? datos.listarPagina(base, filtros, orden, desc, desde, cantidad)
                        : datos.listarPagina(base, filtros, orden, desc, desde, cantidad, ex);
            }
        });
        pantalla.cargar(List.of());
        OptimistShell.montar(this, pantalla.contenido, def.titulo());
    }

    // ------------------------------------------------------------------ V66l: variables, estilos, acciones, cabecera

    /** V66l: cómo quedó una consulta (para volver a ella como estaba). */
    private record Estado(Consulta def, Trn trn, OptimistWorkWith.Pantalla<Map<String, Object>> pantalla, Map<String, ComboBox<Opcion>> combos,
                          Set<String> calculadas, Object estadoCalculo, Map<Integer, Boolean> procesos, Map<String, String> recibidos,
                          Map<String, com.vaadin.flow.component.datepicker.DatePicker> fechas, Map<String, Object> varsFijas, Set<String> referenciadas,
                          Map<String, Object> cabeceraFila, com.vaadin.flow.component.orderedlayout.FlexLayout cajaCabecera,
                          Map<AccionCns, com.vaadin.flow.component.button.Button> botonesArriba) {}
    private final LinkedHashMap<String, Estado> estados = new LinkedHashMap<>();

    private Estado guardarEstado() {
        return new Estado(def, trn, pantalla, new LinkedHashMap<>(combos), new LinkedHashSet<>(calculadas), estadoCalculo, new HashMap<>(procesos),
                new LinkedHashMap<>(recibidos), new LinkedHashMap<>(fechas), new TreeMap<>(varsFijas), referenciadas, cabeceraFila,
                cajaCabecera, new LinkedHashMap<>(botonesArriba));
    }

    private void restaurar(Estado s) {
        def = s.def(); trn = s.trn(); pantalla = s.pantalla(); estadoCalculo = s.estadoCalculo();
        combos.clear(); combos.putAll(s.combos());
        calculadas.clear(); calculadas.addAll(s.calculadas());
        procesos.clear(); procesos.putAll(s.procesos());
        recibidos.clear(); recibidos.putAll(s.recibidos());
        fechas.clear(); fechas.putAll(s.fechas());
        varsFijas.clear(); varsFijas.putAll(s.varsFijas());
        referenciadas = s.referenciadas(); cabeceraFila = s.cabeceraFila(); cajaCabecera = s.cajaCabecera();
        botonesArriba.clear(); botonesArriba.putAll(s.botonesArriba());
    }

    private final Map<String, com.vaadin.flow.component.datepicker.DatePicker> fechas = new LinkedHashMap<>();
    /** Las variables de ParamGlb y fijas del YAML (se leen al armar la pantalla). */
    final Map<String, Object> varsFijas = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    /** Las &Variables que usa el SQL de la consulta (van como @cns_Nombre a la sesión de MySQL antes de cada consulta). */
    Set<String> referenciadas = Set.of();
    Map<String, Object> cabeceraFila = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    com.vaadin.flow.component.orderedlayout.FlexLayout cajaCabecera;
    final Map<AccionCns, com.vaadin.flow.component.button.Button> botonesArriba = new LinkedHashMap<>();
    private final Map<String, OptimistFormula> formulas = new HashMap<>();
    private static final java.util.regex.Pattern VAR = java.util.regex.Pattern.compile("&([A-Za-z_][A-Za-z0-9_]*)");
    private static final java.util.regex.Pattern COL = java.util.regex.Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)");

    private ColSql colSql(String nombre) {
        return def.columnasSql().stream().filter(x -> x.nombre().equalsIgnoreCase(nombre)).findFirst().orElse(null);
    }

    /** Las variables del YAML: un parámetro global de la aplicación (alfa, numero o boolean), un proceso o un valor fijo. */
    private void leerVariablesFijas(Connection c) {
        varsFijas.clear();
        for (Variable v : def.variables()) {
            Object x = v.valor();
            if (v.proceso() != null) x = OptimistProcedimientos.procesoHabilitado(c, v.proceso());   // V82g
            else if (v.paramGlb() != null) {   // E3: el parámetro global lo resuelve la aplicación (OPTIMIST ERP: GetParamGlbValor...)
                x = null;
                try {
                    for (OptimistExtension e : OptimistExtension.todas()) { x = e.parametroGlobal(c, v.paramGlb(), v.tipo()); if (x != null) break; }
                }
                catch (SQLException | RuntimeException ex) { System.out.println("[" + def.nombre() + "] variable " + v.nombre() + ": " + ex.getMessage()); x = null; }
                if (x instanceof String s) x = s.strip();
            }
            varsFijas.put(v.nombre(), x);
        }
    }

    /** Todas las variables: el contexto del usuario (&Empresa, &EmpStk...), &Today, las del YAML, lo recibido y los parámetros. */
    private Map<String, Object> variables() {
        Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx != null) for (String k : OptimistTrnDefinicion.variablesContexto()) v.put(k, ctx.valor(k));   // E3: las del motor y las de la aplicación
        v.put("Today", java.time.LocalDate.now());
        v.putAll(varsFijas);
        recibidos.forEach((k, x) -> v.put(k, valorRecibido(x)));
        combos.forEach((k, cb) -> v.put(k, cb.getValue() == null ? null : cb.getValue().valor()));
        fechas.forEach((k, dp) -> v.put(k, dp.getValue()));
        return v;
    }

    private static Object valorRecibido(String x) {
        if (x == null) return null;
        String s = x.trim();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) try { return java.time.LocalDate.parse(s); } catch (RuntimeException ignorado) { }
        return valor(s);
    }

    /** El SQL con $Columna = la expresión de esa columnasSql y &Variable = @cns_Variable (ver OptimistTrnDatos.ponerVariables). */
    private String sqlVars(String s) {
        if (s == null) return null;
        String r = s;
        for (int vuelta = 0; vuelta < 6 && r.contains("$"); vuelta++) {   // una columna puede usar otra ($Saldo = $Debitos - $Creditos)
            java.util.regex.Matcher m = COL.matcher(r);
            StringBuffer sb = new StringBuffer();
            boolean cambio = false;
            while (m.find()) {
                ColSql cs = colSql(m.group(1));
                if (cs != null) cambio = true;
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(cs == null ? m.group(0) : "(" + cs.sql() + ")"));
            }
            m.appendTail(sb);
            r = sb.toString();
            if (!cambio) break;
        }
        return VAR.matcher(r).replaceAll("@cns_$1");
    }

    /** Las &Variables que aparecen en el SQL de la consulta (con las de las columnas que se usan con $Columna). */
    private Set<String> referencias() {
        List<String> textos = new ArrayList<>();
        textos.add(def.join());
        textos.add(def.condicion());
        def.columnasSql().forEach(c -> textos.add(c.sql()));
        def.recibe().forEach(r -> textos.add(r.sql()));
        if (def.cabecera() != null) textos.add(def.cabecera().sql());
        Set<String> r = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String s : textos) {
            if (s == null) continue;
            java.util.regex.Matcher m = VAR.matcher(s);
            while (m.find()) r.add(m.group(1));
        }
        return r;
    }

    /** Las variables que usa el SQL, con su valor de ahora (las que no tienen valor van en NULL: no queda una vieja en la conexión). */
    private Map<String, Object> varsSql() {
        Map<String, Object> todas = variables(), r = new LinkedHashMap<>();
        for (String n : referenciadas) r.put(n, todas.get(n));
        return r;
    }

    /** Si se cumple una condición (con los campos del renglón y &Variables). Sin condición, sí. */
    private boolean cumple(String si, Map<String, Object> fila) {
        if (si == null || si.isBlank()) return true;
        try {
            OptimistFormula fm = formulas.computeIfAbsent(si, OptimistFormula::de);
            Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            cabeceraFila.forEach((k, x) -> { if (k != null) v.put(k, x); });
            if (fila != null) fila.forEach((k, x) -> { if (k != null) v.put(k, x); });
            Map<String, Object> vars = si.contains("&") ? variables() : Map.of();
            return Boolean.TRUE.equals(fm.evaluar(new OptimistFormula.Contexto() {
                @Override public Object valor(String n) { return n.startsWith("&") ? vars.get(n.substring(1)) : v.get(n); }
                @Override public Object procedimiento(String nombre, List<Object> args) {
                    Object r = OptimistProcedimiento.Funciones.llamar(nombre, args);
                    if (r != OptimistProcedimiento.Funciones.NO_ES) return r;
                    if (!OptimistProcedimientos.existe(nombre)) throw new IllegalStateException("falta el procedimiento " + nombre);
                    try (Connection c = db.openConnection()) { return OptimistProcedimientos.udp(nombre, args, c); }   // ej. un permiso
                    catch (SQLException e) { throw new IllegalStateException(nombre + ": " + e.getMessage(), e); }
                }
            }));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** El estilo de una celda según las reglas de 'estilos:' (la primera que se cumple): la clase de GeneXus y/o el CSS. */
    private String estiloDe(String col, Map<String, Object> fila) {
        List<Regla> rs = def.estilos().get(col);
        if (rs == null) return null;
        for (Regla r : rs) {
            if (!cumple(r.si(), fila)) continue;
            String css = "";
            if (r.clase() != null) {
                String cl = r.clase();
                if (cl.startsWith("&")) { Object x = varsFijas.get(cl.substring(1)); if (x == null) x = variables().get(cl.substring(1)); cl = x == null ? null : String.valueOf(x); }
                String c2 = OptimistClasesGx.css(cl);
                if (c2 != null) css += c2;
            }
            if (r.estilo() != null) css += r.estilo().endsWith(";") ? r.estilo() : r.estilo() + ";";
            return css.isEmpty() ? null : css;
        }
        return null;
    }

    /** El valor de un parámetro de una acción: un campo del renglón (o de la cabecera), una constante o &Variable. */
    private Object parametro(String p, Map<String, Object> fila) {
        String t = p.trim();
        if (t.startsWith("&")) return variables().get(t.substring(1));
        if ((t.startsWith("'") && t.endsWith("'") || t.startsWith("\"") && t.endsWith("\"")) && t.length() >= 2) return t.substring(1, t.length() - 1);
        if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) return Boolean.parseBoolean(t);
        if (t.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(t);
        if (fila != null) for (Map.Entry<String, Object> e : fila.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(t)) return e.getValue();
        Object c = cabeceraFila.get(t);
        return c != null || cabeceraFila.containsKey(t) ? c : t;
    }

    private static com.vaadin.flow.component.icon.VaadinIcon icono(String nombre) {
        if (nombre == null || nombre.isBlank()) return com.vaadin.flow.component.icon.VaadinIcon.CARET_RIGHT;
        try { return com.vaadin.flow.component.icon.VaadinIcon.valueOf(nombre.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException ex) { return com.vaadin.flow.component.icon.VaadinIcon.CARET_RIGHT; }
    }

    /**
     * Las acciones de un renglón. Como en GeneXus, la que no corresponde va en gris; las que tienen el mismo título e ícono
     * son UNA con variantes (ej. "Ingresar Cobranza o Pago": cobranza para un cliente, pago para un proveedor).
     */
    List<OptimistWorkWith.Accion> accionesDe(Map<String, Object> f) {
        List<OptimistWorkWith.Accion> r = new ArrayList<>();
        Map<String, Integer> lugar = new HashMap<>();
        for (AccionCns a : def.acciones()) {
            boolean ok = cumple(a.si(), f);
            OptimistWorkWith.Accion ac = ok ? new OptimistWorkWith.Accion(icono(a.icono()), a.titulo(), () -> accion(a, f))
                    : new OptimistWorkWith.Accion(icono(a.icono()), a.titulo(), null, "No corresponde a este renglón.");
            String k = a.titulo().trim().toLowerCase(Locale.ROOT) + "|" + a.icono();
            Integer i = lugar.get(k);
            if (i == null) { lugar.put(k, r.size()); r.add(ac); }
            else if (r.get(i).hacer() == null && ok) r.set(i, ac);
        }
        return r;
    }

    private void accion(AccionCns a, Map<String, Object> fila) {
        Runnable correr = () -> ejecutar(a, fila);
        if (a.confirmar() == null || a.confirmar().isBlank()) { correr.run(); return; }
        new com.vaadin.flow.component.confirmdialog.ConfirmDialog(a.titulo(), a.confirmar(), "Sí", e -> correr.run(), "No", e -> { }).open();
    }

    private void boton(AccionCns b) {
        if (!cumple(b.si(), null)) {
            com.vaadin.flow.component.notification.Notification.show("Ya no corresponde \"" + b.titulo() + "\".", 6000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
            return;
        }
        accion(b, null);
    }

    private void ejecutar(AccionCns a, Map<String, Object> fila) {
        List<Object> args = new ArrayList<>();
        for (String p : a.parametros()) args.add(parametro(p, fila));
        try {
            switch (a.tipo()) {
                case "cns" -> navegar(a.objeto(), args);
                case "panel" -> OptimistPanelView.abrirDialogo(a.objeto(), db, args, ab -> abrirTrn(ab.trn(), ab.modo(), ab.clave()), this::refrescar);
                case "proc" -> correrProcedimiento(a, args);
                default -> abrirTrn(a.objeto(), a.modo(), args.isEmpty() ? null : args.get(0));
            }
        } catch (RuntimeException ex) {
            com.vaadin.flow.component.notification.Notification.show(a.titulo() + ": " + ex.getMessage(), 8000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
        }
    }

    /** Abre una TRN en el modo pedido (Display / DSP = Visualizar; si no, Modificar) con su clave. */
    private static void abrirTrn(String nombre, String modo, Object clave) {
        Trn otra = OptimistPanelView.trn(nombre);
        if (otra == null) throw new IllegalArgumentException("la TRN " + nombre + " no existe");
        boolean ver = modo == null || List.of("display", "dsp", "ver", "visualizar").contains(modo.trim().toLowerCase(Locale.ROOT));
        String k = clave instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : clave == null ? null : String.valueOf(clave).strip();
        if (k == null || k.isBlank()) com.vaadin.flow.component.UI.getCurrent().navigate("trn/" + otra.ruta());
        else com.vaadin.flow.component.UI.getCurrent().navigate("trn/" + otra.ruta(), com.vaadin.flow.router.QueryParameters.of(ver ? "ver" : "abrir", k));
    }

    /** La lista y la cabecera al día, en la misma página (ej. después de cargar una observación o una cobranza). */
    private void refrescar() {
        refrescarCabecera();
        if (pantalla != null) pantalla.cargar(List.of());
    }

    /**
     * Un procedimiento de una acción o un botón: sus Msg() y su &WWPContext.MensajeError; el PDF que armó se muestra en la
     * misma ventana y el Excel (&ExcelDocument) se baja; si pidió abrir una TRN, se abre.
     */
    private void correrProcedimiento(AccionCns a, List<Object> args) {
        List<String> mensajes = new ArrayList<>();
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx != null) ctx.poner("MensajeError", "");
        OptimistProcedimiento.tomarTrnAAbrir();
        OptimistProcedimiento.tomarPdfs();
        Object[] abrir = null;
        List<Object[]> pdfs = List.of();
        Map<String, byte[]> archivos = Map.of();
        try (Connection c = db.openConnection()) {
            OptimistProcedimientos.ejecutar(a.objeto(), args, c, mensajes);
            abrir = OptimistProcedimiento.tomarTrnAAbrir();
            archivos = new LinkedHashMap<>(OptimistProcedimiento.ARCHIVOS.get());
            pdfs = OptimistProcedimiento.tomarPdfs();
            Object me = ctx == null ? null : ctx.valores().entrySet().stream().filter(x -> "MensajeError".equalsIgnoreCase(x.getKey()))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (me instanceof String sm && !sm.isBlank()) { mensajes.add(sm.strip()); ctx.poner("MensajeError", ""); }
        } catch (SQLException | RuntimeException ex) {
            mensajes.add(a.titulo() + ": " + ex.getMessage());
            OptimistProcedimiento.tomarPdfs();
        }
        Object[] pdf = null, excel = null;
        for (Object[] x : pdfs) {
            String n = String.valueOf(x[0]).toLowerCase(Locale.ROOT);
            if (n.endsWith(".xlsx") || n.endsWith(".xls")) excel = x; else pdf = x;
        }
        if (excel == null && !archivos.isEmpty()) {   // el Excel que armó (&ExcelDocument.Save()), aunque no haya un Link
            Map.Entry<String, byte[]> ult = null;
            for (Map.Entry<String, byte[]> e : archivos.entrySet()) ult = e;
            excel = new Object[]{OptimistProcedimiento.nombreDescarga(ult.getKey()), ult.getValue()};
        }
        // el Excel se baja; si armó un Excel, el PDF del reporte (sin renglones, el modo Excel no imprime) no se muestra
        if (excel != null) OptimistVisorPdf.bajar(String.valueOf(excel[0]), (byte[]) excel[1]);
        else if (pdf != null) OptimistVisorPdf.abrir(a.titulo(), String.valueOf(pdf[0]), (byte[]) pdf[1]);
        if (pdf != null || excel != null) {
            if (!mensajes.isEmpty()) com.vaadin.flow.component.notification.Notification.show(String.join("\n", mensajes), 8000,
                    com.vaadin.flow.component.notification.Notification.Position.BOTTOM_START);
            return;
        }
        refrescar();
        if (abrir != null && mensajes.isEmpty()) {
            boolean ver = abrir.length > 2 && abrir[2] != null && List.of("dsp", "display").contains(String.valueOf(abrir[2]).trim().toLowerCase(Locale.ROOT));
            abrirTrn(String.valueOf(abrir[0]), ver ? "Display" : "Update", abrir[1]);
            return;
        }
        com.vaadin.flow.component.notification.Notification.show(mensajes.isEmpty() ? a.titulo() + ": listo." : String.join("\n", mensajes),
                mensajes.isEmpty() ? 3000 : 10000, com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
    }

    /** La cabecera (un SELECT de un renglón con las &Variables) y los botones que corresponden según sus datos. */
    private void refrescarCabecera() {
        if (def.cabecera() != null && cajaCabecera != null) {
            Map<String, Object> fila = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            String falla = null;
            try (Connection c = db.openConnection()) {
                OptimistTrnDatos.ponerVariables(c, new OptimistTrnDatos.Extra(null, null, null, null, null, varsSql()));
                try (PreparedStatement p = c.prepareStatement(sqlVars(def.cabecera().sql())); ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        java.sql.ResultSetMetaData md = r.getMetaData();
                        for (int i = 1; i <= md.getColumnCount(); i++) fila.put(md.getColumnLabel(i), r.getObject(i));
                    }
                }
                if (fila.isEmpty()) falla = "No se encontraron los datos de arriba (" + recibidos + ").";
            } catch (SQLException | RuntimeException ex) {
                System.out.println("[" + def.nombre() + "] cabecera: " + ex.getMessage());
                falla = "No se pudieron leer los datos de arriba: " + ex.getMessage();
            }
            cabeceraFila = fila;
            cajaCabecera.removeAll();
            if (falla != null) {   // V66l: el error a la vista (antes quedaban los títulos sin datos)
                Span err = new Span(falla);
                err.getStyle().set("color", "#c62828").set("font-size", "var(--lumo-font-size-s)").set("flex-basis", "100%");
                cajaCabecera.add(err);
            }
            for (CampoCab cc : def.cabecera().campos()) {
                Object v = fila.get(cc.nombre());
                String txt = OptimistPdfLista.texto(v, "numero".equals(cc.tipo()) ? (cc.formato() == null ? "#,##0.00" : cc.formato()) : cc.formato());
                Span etq = new Span(cc.etiqueta());
                etq.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
                com.vaadin.flow.component.Component val;
                if (cc.trn() != null && cc.clave() != null && fila.get(cc.clave()) != null && OptimistPanelView.trn(cc.trn()) != null) {
                    Object k = fila.get(cc.clave());
                    String ks = k instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(k).strip();
                    val = new com.vaadin.flow.component.html.Anchor("trn/" + OptimistPanelView.trn(cc.trn()).ruta() + "?ver="
                            + java.net.URLEncoder.encode(ks, java.nio.charset.StandardCharsets.UTF_8), txt);
                    val.getElement().setAttribute("router-link", true);
                } else if (cc.mail() && !txt.isBlank()) val = new com.vaadin.flow.component.html.Anchor("mailto:" + txt, txt);
                else val = new Span(txt);
                val.getElement().getStyle().set("font-weight", "600");
                String est = estiloDe(cc.nombre(), fila);
                if (est != null) for (String par : est.split(";")) {
                    int dp = par.indexOf(':');
                    if (dp > 0) val.getElement().getStyle().set(par.substring(0, dp).trim(), par.substring(dp + 1).trim());
                }
                VerticalLayout caja = new VerticalLayout(etq, val);
                caja.setPadding(false);
                caja.setSpacing(false);
                caja.setWidth(null);
                cajaCabecera.add(caja);
            }
        }
        botonesArriba.forEach((b, bt) -> bt.setVisible(cumple(b.si(), null)));
    }

    /** Una columna de columnasSql: números con el formato argentino (a la derecha), fechas dd/mm/aaaa, textos. */
    private void columnaSql(ColSql cs) {
        String n = cs.nombre();
        calculadas.add(n);
        EnlaceCns en = def.enlaces().get(n);
        if (en != null) pantalla.enlaceColumna(n, f -> hrefEnlace(en, f));
        if (def.estilos().containsKey(n)) pantalla.estiloPorFila(n, f -> estiloDe(n, f));   // V66l
        java.util.function.Function<Map<String, Object>, Object> visto, real = f -> f.get(n);
        String formato = null;
        switch (cs.tipo()) {
            case "numero" -> {
                pantalla.tipoFiltro(n, "numero");
                formato = cs.formato() == null ? "#,##0.00" : cs.formato();
                String fm = formato;
                visto = f -> OptimistPdfLista.texto(f.get(n), fm);
            }
            case "fecha" -> {
                pantalla.tipoFiltro(n, "fecha");
                visto = f -> OptimistPdfLista.texto(f.get(n), null);
                real = f -> {
                    Object v = f.get(n);
                    if (v instanceof java.sql.Date d) v = d.toLocalDate();
                    if (v instanceof java.sql.Timestamp t) v = t.toLocalDateTime().toLocalDate();
                    if (v instanceof java.time.LocalDateTime t) v = t.toLocalDate();
                    return v instanceof java.time.LocalDate d && d.getYear() <= 1000 ? null : v;
                };
            }
            default -> visto = f -> f.get(n) == null ? "" : String.valueOf(f.get(n)).strip();
        }
        Grid.Column<Map<String, Object>> col = pantalla.columna(n, def.titulos().getOrDefault(n, cs.titulo()), visto, real, formato, null, null);
        String ancho = def.anchos().get(n);
        if (ancho != null) col.setAutoWidth(false).setWidth(ancho).setFlexGrow(OptimistWorkWith.crecimiento(ancho));   // V66k3 / V66l: ver columnaTrn
        if ("numero".equals(cs.tipo())) col.setTextAlign(ColumnTextAlign.END);
        if ("numero".equals(cs.tipo()) && def.totaliza(n, null)) pantalla.totalizar(n, formato);   // V75: solo las de 'totales:'
    }

    /**
     * Las columnasSql, la condición de lo recibido y la 'condicion:' de la consulta, para la consulta a la base (null si no
     * hay nada). V66l: con las &Variables (van a la sesión de MySQL) y $Columna en la condición.
     */
    private OptimistTrnDatos.Extra extraGeneral() {
        Map<String, String> cols = new LinkedHashMap<>();
        for (ColSql c : def.columnasSql())
            if (c.oculta() || def.columnas().stream().anyMatch(x -> x.equalsIgnoreCase(c.nombre()))) cols.put(c.nombre(), sqlVars(c.sql()));
        List<String> conds = new ArrayList<>();
        List<Object> ps = new ArrayList<>();
        for (Recibe r : def.recibe()) {
            String v = recibidos.get(r.nombre());
            if (v == null || r.sql() == null) continue;
            conds.add("(" + sqlVars(r.sql()) + ")");
            long marcas = r.sql().chars().filter(ch -> ch == '?').count();
            for (long i = 0; i < marcas; i++) ps.add(valor(v));
        }
        if (def.condicion() != null && !def.condicion().isBlank()) conds.add("(" + sqlVars(def.condicion()) + ")");
        Map<String, Object> vars = varsSql();
        if (cols.isEmpty() && conds.isEmpty() && vars.isEmpty()) return null;
        return new OptimistTrnDatos.Extra(sqlVars(def.join()), List.of(), cols, conds.isEmpty() ? null : String.join(" AND ", conds), ps, vars);
    }

    private static Object valor(String v) {
        return v.matches("-?\\d+(\\.\\d+)?") ? new BigDecimal(v) : v;
    }

    /** El subtítulo (ej. "Asiento 25 del 30/09/2026 - Asiento de Ventas"), o null si falta algo de lo que usa. */
    String subtitulo() {
        Subtitulo st = def.subtitulo();
        if (st == null) return null;
        List<Object> ps = new ArrayList<>();
        for (String p : st.parametros()) {
            String v = recibidos.get(p);
            if (v == null) return null;
            ps.add(valor(v));
        }
        try (Connection c = db.openConnection(); PreparedStatement p = c.prepareStatement(st.sql())) {
            for (int i = 0; i < ps.size(); i++) p.setObject(i + 1, ps.get(i));
            try (ResultSet r = p.executeQuery()) { return r.next() && r.getObject(1) != null ? String.valueOf(r.getObject(1)).strip() : null; }
        } catch (SQLException | RuntimeException ex) {
            System.out.println("[" + def.nombre() + "] subtítulo: " + ex.getMessage());
            return null;
        }
    }

    /** La dirección que abre en Visualizar el comprobante del renglón (la TRN del primer caso que se cumple), o null. */
    private String hrefEnlace(EnlaceCns en, Map<String, Object> f) {
        Map<String, Object> v = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        f.forEach((k, x) -> { if (k != null) v.put(k, x); });
        if (en.si() != null && !cumple(en.si(), f)) return null;
        if (en.cns() != null) {   // V66l: abre otra consulta con lo que recibe (ej. los cheques en cartera del cliente)
            List<Object> args = new ArrayList<>();
            for (String p : en.parametros()) args.add(parametro(p, f));
            return hrefConsulta(en.cns(), args);
        }
        Object clave = v.get(en.clave());
        if (clave == null || String.valueOf(clave).isBlank() || (clave instanceof Number nn && new BigDecimal(nn.toString()).signum() == 0)) return null;
        for (String[] caso : en.casos()) {
            boolean cumple = true;
            if (caso[0] != null) {
                try { cumple = Boolean.TRUE.equals(formulasEnlace.computeIfAbsent(caso[0], OptimistFormula::de).evaluar(v::get)); }
                catch (RuntimeException ex) { cumple = false; }
            }
            if (!cumple) continue;
            Trn otra = OptimistPanelView.trn(caso[1]);
            if (otra == null) return null;
            return "trn/" + otra.ruta() + "?ver=" + java.net.URLEncoder.encode(String.valueOf(clave).strip(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    private static final Map<String, Consulta> CONSULTAS_ENLACE = new java.util.concurrent.ConcurrentHashMap<>();

    /** V66l: la dirección de una consulta con lo que recibe (sus 'recibe:', en orden): cns/nombre?EntidadesPK=12&Fecha=2026-10-21. */
    static String hrefConsulta(String nombre, List<Object> valores) {
        Consulta d = CONSULTAS_ENLACE.computeIfAbsent(nombre.toLowerCase(Locale.ROOT), k -> cargar(nombre));
        StringBuilder sb = new StringBuilder("cns/" + d.nombre().toLowerCase(Locale.ROOT));
        char sep = '?';
        for (int i = 0; i < d.recibe().size() && i < valores.size(); i++) {
            String t = textoParametro(valores.get(i));
            if (t == null) continue;
            sb.append(sep).append(d.recibe().get(i).nombre()).append('=').append(java.net.URLEncoder.encode(t, java.nio.charset.StandardCharsets.UTF_8));
            sep = '&';
        }
        return sb.toString();
    }

    private static String textoParametro(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.time.LocalDateTime dt) return dt.toLocalDate().toString();
        return String.valueOf(v).strip();
    }

    /** Para abrir una consulta con lo que recibe (sus 'recibe:', en orden): cns/nombre?AsientosId=25&AsientosDetId=2. */
    static void navegar(String nombre, List<Object> valores) {
        Consulta d = cargar(nombre);
        Map<String, String> q = new LinkedHashMap<>();
        for (int i = 0; i < d.recibe().size() && i < valores.size(); i++) {
            String t = textoParametro(valores.get(i));   // V66l: también fechas (2026-10-21)
            if (t == null) continue;
            q.put(d.recibe().get(i).nombre(), t);
        }
        com.vaadin.flow.component.UI.getCurrent().navigate("cns/" + d.nombre().toLowerCase(Locale.ROOT), com.vaadin.flow.router.QueryParameters.simple(q));
    }

    static BigDecimal numero(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v).trim()); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    /**
     * Los combos de los parámetros, con el valor guardado del usuario o el default del YAML. V66l: también los de tipo fecha
     * (default "hoy", o lo recibido con el mismo nombre) y los combos de una tabla con "donde" (se vuelven a llenar cuando
     * cambia el parámetro del que dependen, ej. el modelo de comprobantes según el CPI).
     */
    void armarCombos(Connection c, Object modeloUsuario) throws SQLException {
        combos.clear();
        fechas.clear();
        for (Parametro p : def.parametros()) {
            String guardado = p.recordar() ? preferencias.cargar(usuario).getProperty(clavePref(p.nombre())) : null;
            if ("fecha".equals(p.tipo())) {
                com.vaadin.flow.component.datepicker.DatePicker dp = new com.vaadin.flow.component.datepicker.DatePicker(p.etiqueta());
                dp.setLocale(Locale.of("es", "AR"));
                dp.setI18n(OptimistTrnCampos.i18nFecha());
                OptimistTrnCampos.fechaRapida(dp);
                dp.setWidth("170px");
                java.time.LocalDate ini = fechaDe(guardado != null && !guardado.isBlank() ? guardado : p.defecto());
                String rec = recibidos.get(p.nombre());
                if (rec != null) { java.time.LocalDate r = fechaDe(rec); if (r != null) ini = r; }
                dp.setValue(ini);
                dp.addValueChangeListener(ev -> {
                    if (ev.isFromClient() && p.recordar())
                        preferencias.guardarClave(usuario, clavePref(p.nombre()), ev.getValue() == null ? "" : ev.getValue().toString());
                    alCambiarParametro(p.nombre());
                });
                fechas.put(p.nombre(), dp);
                continue;
            }
            List<Opcion> ops = opciones(c, p);
            ComboBox<Opcion> cb = new ComboBox<>(p.etiqueta());
            cb.setItems(ops);
            cb.setClearButtonVisible(p.vacio());   // un modelo de almacenes se puede sacar (= todos)
            cb.setPlaceholder(p.vacio() ? "Todos" : null);
            cb.setWidth(p.tabla().isEmpty() ? "200px" : "260px");
            String rec = recibidos.get(p.nombre());
            Object inicial = rec != null ? rec : guardado != null ? guardado : "usuario".equals(String.valueOf(p.defecto())) ? modeloUsuario : p.defecto();
            if (inicial != null && !String.valueOf(inicial).isBlank())
                ops.stream().filter(o -> mismo(o.valor(), inicial)).findFirst().ifPresent(cb::setValue);
            cb.addValueChangeListener(ev -> {
                if (ev.isFromClient() && p.recordar())
                    preferencias.guardarClave(usuario, clavePref(p.nombre()), ev.getValue() == null ? "" : String.valueOf(ev.getValue().valor()));
                alCambiarParametro(p.nombre());
            });
            combos.put(p.nombre(), cb);
        }
    }

    private static java.time.LocalDate fechaDe(Object d) {
        if (d == null) return null;
        String s = String.valueOf(d).trim();
        if (s.equalsIgnoreCase("hoy") || s.equalsIgnoreCase("today")) return java.time.LocalDate.now();
        try { return java.time.LocalDate.parse(s); } catch (RuntimeException e) { return null; }
    }

    /** Las opciones de un combo: los valores fijos y los de su tabla (con su "donde", que puede usar &Variables). */
    private List<Opcion> opciones(Connection c, Parametro p) throws SQLException {
        List<Opcion> ops = new ArrayList<>();
        p.valores().forEach((k, v) -> ops.add(new Opcion(k, v)));
        if (!p.tabla().isEmpty()) {
            String t = p.tabla().get("tabla"), v = p.tabla().get("valor"), x = p.tabla().get("texto");
            for (String id : new String[]{t, v, x})
                if (id == null || !id.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("tabla inválida en el parámetro " + p.nombre());
            String donde = p.tabla().get("donde");
            List<Object> args = new ArrayList<>();
            String w = "";
            if (donde != null && !donde.isBlank()) {
                Map<String, Object> vars = variables();
                java.util.regex.Matcher m = VAR.matcher(donde);
                StringBuffer sb = new StringBuffer();
                while (m.find()) { args.add(vars.get(m.group(1))); m.appendReplacement(sb, "?"); }
                m.appendTail(sb);
                w = " WHERE " + sb;
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT " + v + ", " + x + " FROM " + t + w + " ORDER BY " + x)) {
                for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i) instanceof java.time.LocalDate d ? java.sql.Date.valueOf(d) : args.get(i));
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) ops.add(new Opcion(r.getObject(1), String.valueOf(r.getObject(2)).trim()));
                }
            }
        }
        return ops;
    }

    /** Cambió un parámetro: los combos que dependen de él se vuelven a llenar, la cabecera y la lista desde la primera página. */
    private void alCambiarParametro(String nombre) {
        if (def == null) return;
        String marca = "&" + nombre.toLowerCase(Locale.ROOT);
        for (Parametro p : def.parametros()) {
            String donde = p.tabla().get("donde");
            ComboBox<Opcion> cb = combos.get(p.nombre());
            if (donde == null || cb == null || !donde.toLowerCase(Locale.ROOT).matches(".*" + java.util.regex.Pattern.quote(marca) + "\\b.*")) continue;
            Opcion antes = cb.getValue();
            try (Connection c = db.openConnection()) {
                List<Opcion> ops = opciones(c, p);
                cb.setItems(ops);
                Opcion sigue = antes == null ? null : ops.stream().filter(o -> mismo(o.valor(), antes.valor())).findFirst().orElse(null);
                cb.setValue(sigue);
            } catch (SQLException | RuntimeException ex) {
                System.out.println("[" + def.nombre() + "] parámetro " + p.nombre() + ": " + ex.getMessage());
            }
        }
        if (cajaCabecera != null || !botonesArriba.isEmpty()) refrescarCabecera();
        if (pantalla != null) pantalla.inicio();
    }

    private String clavePref(String parametro) { return "cns." + def.nombre() + "." + parametro; }

    private static boolean mismo(Object a, Object b) {
        if (a == null || b == null) return false;
        return clave(a).equals(clave(b instanceof String s && s.trim().matches("-?\\d+(\\.\\d+)?") ? new BigDecimal(s.trim()) : b));
    }

    /** Un valor para comparar: los números sin ceros de más (1 = 1.00), los textos sin espacios. */
    private static Object clave(Object v) {
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v == null ? null : String.valueOf(v).trim();
    }

    int entero(String parametro, int defecto) {
        ComboBox<Opcion> cb = combos.get(parametro);
        Object v = cb == null || cb.getValue() == null ? null : cb.getValue().valor();
        try { return v == null ? defecto : Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { return defecto; }
    }
}
