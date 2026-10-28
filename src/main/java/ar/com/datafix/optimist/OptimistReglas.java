package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Nivel;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Referencia;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V35 - Reglas de GeneXus de una TRN (sección 'reglas:' del YAML) y su evento Start (sección 'inicio:').
 *
 * Reglas soportadas (cada una termina en ';', puede ocupar varios renglones):
 *   Atributo = expresión [if condición] [on Evento] [level Atributo]
 *   &Variable = expresión ...           Atributo.SetEmpty() / .SetNull() ...
 *   Proc.Call(a, B, &c) ...              (los parámetros out/inout vuelven a los atributos y &variables)
 *   Error("texto") ...    Msg("texto") ...
 *   NoAccept(A, B) if condición          A.Enabled = expresión ...     A.Visible = expresión ...
 * En las condiciones: after(X), X.GetOldValue(), Insert, Update, Delete, Level(X), &Mode = "INS"/"UPD"/"DLT".
 *
 * Cómo se aplican (como GeneXus web):
 *   - Cada regla es de un nivel (la cabecera o un detalle), según los atributos que usa.
 *   - Con cada cambio del formulario se evalúan todas las reglas del nivel, ordenadas por dependencias
 *     (primero la que asigna X, después las que usan X). after(X) vale si X cambió en esta vuelta.
 *   - Error() se controla al confirmar; Msg() se muestra.
 *   - Las reglas con evento (on BeforeValidate, AfterValidate, BeforeInsert/Update, AfterInsert/Update,
 *     BeforeComplete, AfterComplete, AfterLevel) se ejecutan al grabar, en ese orden.
 *   - Una regla que se asigna a sí misma sin otra que la inicialice (PRSVersion = PRSVersion + 1) solo corre al grabar.
 */
public final class OptimistReglas {

    /** PANTALLA (V48): una línea nueva vacía: solo Enabled / Visible / NoAccept (sin procedimientos ni asignaciones). */
    public enum Momento { CARGA, CAMBIO, GRABAR, PANTALLA }
    enum Tipo { ASIGNAR, VACIAR, LLAMAR, ERROR, MSG, NOACCEPT, ENABLED, VISIBLE, CLASE }

    static final Set<String> EVENTOS = Set.of("beforevalidate", "aftervalidate", "beforeinsert", "beforeupdate", "beforedelete",
            "afterinsert", "afterupdate", "afterdelete", "beforecomplete", "aftercomplete", "afterlevel", "beforetrn", "aftertrn");

    /** Una regla ya leída. */
    static final class Regla {
        final int indice, renglon; final String texto; final Tipo tipo;
        String destino; List<String> destinos = List.of(); boolean nulo;
        OptimistFormula expr, cond;
        String proc; List<OptimistFormula> args = List.of(); List<String> refs = List.of();
        final Set<String> eventos = new TreeSet<>();
        String nivelAtributo;
        Nivel nivel;            // null = cabecera
        final Set<String> lee = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final Set<String> escribe = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        /** after(X) de la condición: la regla se dispara apenas se asigna X. */
        final Set<String> afters = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        /**
         * V49c: after(X) de una regla de un detalle con X de la cabecera (BaseImponibleImpuestos.Call(...) If After(PRSFecha) en
         * los impuestos): como en GeneXus, la cabecera ya está aceptada cuando se procesan las líneas, así que vale siempre.
         * Se sacan de 'afters' (la regla corre como cualquier otra) y quedan acá para recalcular las líneas cuando cambian los totales.
         */
        final Set<String> aftersCabecera = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean soloAlGrabar;
        /** V50b: "Campo = &Variable" con Campo inferido de la cabecera (solo se muestra): corre también al abrir (CARGA y PANTALLA). */
        boolean dePantalla;
        Regla(int indice, int renglon, String texto, Tipo tipo) { this.indice = indice; this.renglon = renglon; this.texto = texto; this.tipo = tipo; }
        @Override public String toString() { return "renglón " + renglon + ": " + texto; }
    }

    /** Estado del formulario abierto: variables (&...), modo, valores al abrir (GetOldValue). */
    public static final class Estado {
        final Map<String, Object> vars = new HashMap<>();   // clave: nombre en minúscula, sin '&'
        public String modo;                                   // INS, UPD, DLT, DSP
        final Map<String, Object> cacheExtendida = new HashMap<>();
        Estado(String modo) { this.modo = modo; }
        public Object variable(String nombre) { return vars.get(nombre.replaceFirst("^&", "").toLowerCase(Locale.ROOT)); }
    }

    /** Lo que produjo una pasada de reglas sobre un nivel (o una línea). */
    public record Efecto(Map<String, Object> asignados, Map<String, Boolean> habilitados, Map<String, Boolean> visibles,
                         List<String> errores, List<String> mensajes, List<String> fallas, Map<String, String> clases) {
        static Efecto nuevo() {
            return new Efecto(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new LinkedHashMap<>());
        }
    }

    final Trn trn;
    final OptimistProcedimiento inicio;
    final List<Regla> reglas;
    private volatile List<Regla> ordenadas;

    private static final Map<String, OptimistReglas> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Reglas de la TRN (se leen una vez por texto). IllegalArgumentException con el renglón si algo está mal. */
    public static OptimistReglas de(Trn trn) {
        String clave = trn.nombre() + "\u0000" + trn.inicio() + "\u0000" + trn.reglas();
        OptimistReglas r = CACHE.get(clave);
        if (r == null || r.trn != trn) {
            r = new OptimistReglas(trn);
            CACHE.put(clave, r);
        }
        return r;
    }

    public boolean vacio() { return inicio == null && reglas.isEmpty(); }

    private OptimistReglas(Trn trn) {
        this.trn = trn;
        try {
            this.inicio = trn.inicio() == null ? null : OptimistProcedimiento.leer(trn.nombre() + ".inicio", trn.inicio());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("inicio: " + e.getMessage().replaceFirst("^[^,:]*\\.inicio,?\\s*", ""));
        }
        this.reglas = trn.reglas() == null ? List.of() : leer(trn.reglas());
    }

    // ------------------------------------------------------------------ lectura

    private List<Regla> leer(String texto) {
        String limpio = sinComentarios(texto);
        List<Regla> r = new ArrayList<>();
        int ini = 0, b = 0; char q = 0;
        for (int i = 0; i <= limpio.length(); i++) {
            char c = i < limpio.length() ? limpio.charAt(i) : ';';
            if (q != 0) { if (c == q || c == '\n') q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '(') b++;
            else if (c == ')') b--;
            else if (c == ';' && b <= 0) {
                String s = limpio.substring(ini, Math.min(i, limpio.length()));
                int renglon = 1 + (int) limpio.substring(0, ini).chars().filter(x -> x == '\n').count()
                        + (int) s.substring(0, s.length() - s.stripLeading().length()).chars().filter(x -> x == '\n').count();
                s = s.strip();
                if (!s.isEmpty()) r.add(regla(r.size(), renglon, s.replaceAll("\\s+", " ")));
                ini = i + 1; b = 0;
            }
        }
        for (Regla x : r) ubicar(x);
        // reglas que solo usan &variables: van al nivel de las reglas de las que reciben o a las que pasan esas variables
        // (&PRSDetPrConDtoRnd = Round(&PRSDetPrecioConDto, 2) es del renglón, no de la cabecera)
        for (int vuelta = 0; vuelta < 5; vuelta++) {
            boolean cambio = false;
            for (Regla x : r) {
                if (x.nivel != null || !soloVariables(x) || x.eventos.contains("afterlevel")) continue;
                for (Regla y : r) {
                    if (y == x || y.nivel == null) continue;
                    boolean une = x.lee.stream().anyMatch(v -> v.startsWith("&") && y.escribe.contains(v))
                            || x.escribe.stream().anyMatch(v -> y.lee.contains(v));
                    if (une) { x.nivel = y.nivel; cambio = true; break; }
                }
            }
            if (!cambio) break;
        }
        for (Regla x : r) {
            if (x.nivel == null) continue;
            for (String a : List.copyOf(x.afters))
                if (trn.cabecera().campo(a) != null && x.nivel.campo(a) == null) { x.afters.remove(a); x.aftersCabecera.add(a); }
        }
        marcarAfterLevelBase(r);
        return List.copyOf(r);
    }

    /**
     * V82e: en GeneXus, AfterLevel corre después de grabar los renglones del nivel. Las reglas AfterLevel que llaman a un
     * procedimiento con la clave del registro (ValidarPartidas.Call("CPBC", CPBCId, ...), ValidaEntidadesExterior...) leen
     * el comprobante de la base: tienen que correr con los renglones ya grabados, dentro de la transacción (un Error deshace
     * todo). Pasan al evento interno "afterlevelbd", y con ellas las AfterLevel que usan las &variables que esas devuelven
     * (Error(&MsgErrorPartidas) if ...). Las demás AfterLevel (totales, cantidad de renglones) siguen antes de grabar.
     */
    private void marcarAfterLevelBase(List<Regla> r) {
        Set<String> claves = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        trn.cabecera().claves().forEach(c -> claves.add(c.nombre()));
        Set<Regla> base = new LinkedHashSet<>();
        for (Regla x : r) {
            if (!x.eventos.contains("afterlevel")) continue;
            boolean llama = x.proc != null || todas(x).stream().anyMatch(f -> !f.procedimientos().isEmpty());
            boolean conClave = x.lee.stream().anyMatch(claves::contains)
                    || todas(x).stream().anyMatch(f -> f.atributos().stream().anyMatch(claves::contains));
            if (llama && conClave) base.add(x);
        }
        if (base.isEmpty()) return;
        boolean cambio = true;
        while (cambio) {
            cambio = false;
            Set<String> devueltas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Regla b : base) {
                b.escribe.stream().filter(v -> v.startsWith("&")).forEach(devueltas::add);
                // una llamada (Proc.Call(..., &MsgError)): sus &variables pueden ser de salida
                if (b.tipo == Tipo.LLAMAR) for (OptimistFormula f : b.args) f.atributos().stream().filter(v -> v.startsWith("&")).forEach(devueltas::add);
                if (b.tipo == Tipo.LLAMAR) b.refs.stream().filter(v -> v != null && v.startsWith("&")).forEach(devueltas::add);
            }
            for (Regla x : r)
                if (x.eventos.contains("afterlevel") && !base.contains(x) && x.lee.stream().anyMatch(devueltas::contains)) { base.add(x); cambio = true; }
        }
        for (Regla b : base) { b.eventos.remove("afterlevel"); b.eventos.add(AFTERLEVEL_BASE); }
    }

    /** V82e: evento interno de las AfterLevel que leen el registro grabado (ver marcarAfterLevelBase). */
    public static final String AFTERLEVEL_BASE = "afterlevelbd";

    private static final Pattern ERROR = Pattern.compile("(?is)^error\\s*\\((.*)\\)$");
    private static final Pattern MSG = Pattern.compile("(?is)^msg\\s*\\((.*)\\)$");
    private static final Pattern NOACCEPT = Pattern.compile("(?is)^noaccept\\s*\\((.*)\\)$");
    private static final Pattern PROPIEDAD = Pattern.compile("(?is)^([A-Za-z_]\\w*)\\s*\\.\\s*(enabled|visible|class)\\s*=\\s*(.+)$");
    private static final Pattern VACIAR = Pattern.compile("(?is)^(&?[A-Za-z_]\\w*)\\s*\\.\\s*(setempty|setnull)\\s*\\(\\s*\\)$");
    private static final Pattern LLAMADA = Pattern.compile("(?is)^(?:[A-Za-z_]\\w*\\s*\\.\\s*)*?([A-Za-z_]\\w*)\\s*\\.\\s*call\\s*\\((.*)\\)$");
    private static final Pattern CALL = Pattern.compile("(?is)^call\\s*\\(\\s*([A-Za-z_]\\w*)\\s*(?:,(.*))?\\)$");
    private static final Pattern SUELTA = Pattern.compile("(?is)^([A-Za-z_]\\w*)\\s*\\((.*)\\)$");   // Proc(args), sin .Call
    private static final Pattern ASIGNAR = Pattern.compile("(?is)^(&?[A-Za-z_]\\w*)\\s*=\\s*(.+)$");
    private static final Pattern REF = Pattern.compile("&?[A-Za-z_]\\w*");

    private Regla regla(int indice, int renglon, String t) {
        String cuerpo = t;
        String cond = null, eventos = null, nivelAt = null;
        // al final: [on Evento, Evento] [level Atributo] (en cualquier orden)
        for (int vuelta = 0; vuelta < 2; vuelta++) {
            int lv = ultimaPalabra(cuerpo, "level");
            if (lv > 0 && nivelAt == null) { nivelAt = cuerpo.substring(lv + 5).trim().split("[\\s,]+")[0]; cuerpo = cuerpo.substring(0, lv).trim(); }
            int on = ultimaPalabra(cuerpo, "on");
            if (on > 0 && eventos == null && cuerpo.substring(on + 2).trim().matches("(?i)\\w+(\\s*,\\s*\\w+)*")) {
                eventos = cuerpo.substring(on + 2).trim(); cuerpo = cuerpo.substring(0, on).trim();
            }
        }
        int si = primeraPalabra(cuerpo, "if");
        if (si > 0) { cond = cuerpo.substring(si + 2).trim(); cuerpo = cuerpo.substring(0, si).trim(); }
        Matcher m;
        Regla r;
        if ((m = ERROR.matcher(cuerpo)).matches()) {
            r = new Regla(indice, renglon, t, Tipo.ERROR);
            r.expr = formula(renglon, OptimistProcedimiento.partir(m.group(1), ',').get(0));
        } else if ((m = MSG.matcher(cuerpo)).matches()) {
            r = new Regla(indice, renglon, t, Tipo.MSG);
            r.expr = formula(renglon, OptimistProcedimiento.partir(m.group(1), ',').get(0));
        } else if ((m = NOACCEPT.matcher(cuerpo)).matches()) {
            r = new Regla(indice, renglon, t, Tipo.NOACCEPT);
            List<String> ds = new ArrayList<>();
            for (String x : OptimistProcedimiento.partir(m.group(1), ',')) {
                String a = x.trim();
                if (!a.matches("[A-Za-z_]\\w*")) throw error(renglon, "NoAccept: '" + a + "' tiene que ser un atributo");
                ds.add(a);
            }
            r.destinos = ds;
        } else if ((m = PROPIEDAD.matcher(cuerpo)).matches()) {
            // V49t: X.Class = "Clase" if ...: el campo toma esa clase (clases: del YAML o de dominios.yaml)
            r = new Regla(indice, renglon, t, m.group(2).equalsIgnoreCase("enabled") ? Tipo.ENABLED
                    : m.group(2).equalsIgnoreCase("class") ? Tipo.CLASE : Tipo.VISIBLE);
            r.destino = m.group(1);
            r.expr = formula(renglon, m.group(3));
        } else if ((m = VACIAR.matcher(cuerpo)).matches()) {
            r = new Regla(indice, renglon, t, Tipo.VACIAR);
            r.destino = m.group(1);
            r.nulo = m.group(2).equalsIgnoreCase("setnull");
        } else if ((m = LLAMADA.matcher(cuerpo)).matches() || (m = CALL.matcher(cuerpo)).matches() || (m = SUELTA.matcher(cuerpo)).matches()) {
            r = new Regla(indice, renglon, t, Tipo.LLAMAR);
            r.proc = m.group(1);
            String args = m.group(2) == null ? "" : m.group(2);
            List<OptimistFormula> fs = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            if (!args.isBlank())
                for (String x : OptimistProcedimiento.partir(args, ',')) {
                    String a = x.trim();
                    fs.add(formula(renglon, a.isEmpty() ? "null()" : a));
                    refs.add(REF.matcher(a).matches() && !a.equalsIgnoreCase("true") && !a.equalsIgnoreCase("false") ? a : null);
                }
            r.args = fs; r.refs = refs;
        } else if ((m = ASIGNAR.matcher(cuerpo)).matches()) {
            r = new Regla(indice, renglon, t, Tipo.ASIGNAR);
            r.destino = m.group(1);
            r.expr = formula(renglon, m.group(2));
        } else throw error(renglon, "no entiendo la regla '" + cuerpo + "'");
        if (cond != null) r.cond = formula(renglon, cond);
        if (eventos != null)
            for (String e : eventos.split("\\s*,\\s*")) {
                String ev = e.trim().toLowerCase(Locale.ROOT);
                if (!EVENTOS.contains(ev)) throw error(renglon, "evento '" + e + "' no soportado");
                r.eventos.add(ev);
            }
        r.nivelAtributo = nivelAt;
        // qué lee y qué escribe
        for (OptimistFormula f : todas(r))
            for (String a : f.atributos()) {
                if (a.regionMatches(true, 0, "&__after_", 0, 9)) r.afters.add(a.substring(9));
                else if (!a.startsWith("&__") && !a.contains(".")) r.lee.add(a);
            }
        if (r.tipo == Tipo.ASIGNAR || r.tipo == Tipo.VACIAR) r.escribe.add(r.destino);
        // V50b: un campo inferido de la cabecera que muestra variables (el resultado del presupuesto de IEP: TRPRSSubtotal = &TRPRSSubtotal)
        if (r.tipo == Tipo.ASIGNAR && !r.destino.startsWith("&") && r.lee.stream().allMatch(x -> x.startsWith("&"))) {
            OptimistTrnDefinicion.Campo c = trn.cabecera().campo(r.destino);
            r.dePantalla = c != null && c.inferido() && !c.persistido();
        }
        return r;
    }

    private static boolean soloVariables(Regla r) {
        if (r.destino != null && !r.destino.startsWith("&")) return false;
        if (!r.destinos.isEmpty()) return false;
        for (String x : r.refs) if (x != null && !x.startsWith("&")) return false;
        for (String x : r.lee) if (!x.startsWith("&")) return false;
        return true;
    }

    private static List<OptimistFormula> todas(Regla r) {
        List<OptimistFormula> fs = new ArrayList<>();
        if (r.expr != null) fs.add(r.expr);
        if (r.cond != null) fs.add(r.cond);
        fs.addAll(r.args);
        return fs;
    }

    /** Nivel de la regla: el detalle de alguno de sus atributos, o la cabecera. */
    private void ubicar(Regla r) {
        if (r.eventos.contains("afterlevel")) { r.nivel = null; return; }
        List<String> atts = new ArrayList<>();
        if (r.nivelAtributo != null) atts.add(r.nivelAtributo);
        if (r.destino != null && !r.destino.startsWith("&")) atts.add(r.destino);
        atts.addAll(r.destinos);
        for (String x : r.refs) if (x != null && !x.startsWith("&")) atts.add(x);
        for (String x : r.lee) if (!x.startsWith("&")) atts.add(x);
        Nivel cab = trn.cabecera();
        for (String a : atts) {
            if (cab.campo(a) != null) continue;
            for (Nivel d : trn.detalles()) if (d.campo(a) != null) { r.nivel = d; return; }
        }
        r.nivel = null;
    }

    /** V65k: la regla llama a un procedimiento (.Call( / .Udp(). */
    private static boolean llamaProcedimiento(String texto) {
        return texto != null && texto.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\.(call|udp)\\s*\\(.*");
    }

    private static OptimistFormula formula(int renglon, String texto) {
        try { return OptimistFormula.de(preparar(texto)); }
        catch (IllegalArgumentException e) { throw error(renglon, e.getMessage()); }
    }

    /** after(X), X.GetOldValue(), Insert/Update/Delete, Level(X), TrnMode.X -> lo que entiende la fórmula. */
    static String preparar(String t) {
        StringBuilder r = new StringBuilder();
        Matcher m = Pattern.compile("\"[^\"]*\"|'[^']*'").matcher(t);
        int ult = 0;
        while (m.find()) { r.append(prepararTrozo(t.substring(ult, m.start()))).append(m.group()); ult = m.end(); }
        r.append(prepararTrozo(t.substring(ult)));
        return r.toString();
    }

    private static String prepararTrozo(String s) {
        s = s.replaceAll("(?i)\\bafter\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)", "&__after_$1");
        s = s.replaceAll("(?i)\\b([A-Za-z_]\\w*)\\s*\\.\\s*getoldvalue\\s*\\(\\s*\\)", "&__old_$1");
        s = s.replaceAll("(?i)\\blevel\\s*\\(\\s*[A-Za-z_]\\w*\\s*\\)", "true");
        s = s.replaceAll("(?i)\\bTrnMode\\s*\\.\\s*Insert\\b", "\"INS\"").replaceAll("(?i)\\bTrnMode\\s*\\.\\s*Update\\b", "\"UPD\"")
             .replaceAll("(?i)\\bTrnMode\\s*\\.\\s*Delete\\b", "\"DLT\"").replaceAll("(?i)\\bTrnMode\\s*\\.\\s*Display\\b", "\"DSP\"");
        s = s.replaceAll("(?i)(?<![\\w.&])(insert|update|delete)(?![\\w(.])", "&__$1");
        return s;
    }

    /** Posición de la última palabra suelta (fuera de textos y paréntesis), o -1. */
    private static int ultimaPalabra(String s, String palabra) {
        int r = -1;
        for (int p : palabras(s, palabra)) r = p;
        return r;
    }

    private static int primeraPalabra(String s, String palabra) {
        List<Integer> ps = palabras(s, palabra);
        return ps.isEmpty() ? -1 : ps.get(0);
    }

    private static List<Integer> palabras(String s, String palabra) {
        List<Integer> r = new ArrayList<>();
        int b = 0; char q = 0; int n = palabra.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; continue; }
            if (c == '(') { b++; continue; }
            if (c == ')') { b--; continue; }
            if (b == 0 && s.regionMatches(true, i, palabra, 0, n)
                    && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)) && s.charAt(i - 1) != '_' && s.charAt(i - 1) != '.' && s.charAt(i - 1) != '&')
                    && (i + n == s.length() || !Character.isLetterOrDigit(s.charAt(i + n)) && s.charAt(i + n) != '_' && s.charAt(i + n) != '.')
                    && !(palabra.equals("level") && s.substring(i + n).stripLeading().startsWith("(")))   // Level(X) es una función
                r.add(i);
        }
        return r;
    }

    /** Saca // y /* *&#47; (fuera de los textos), dejando los saltos de renglón para numerar. */
    static String sinComentarios(String t) {
        StringBuilder r = new StringBuilder();
        char q = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (q != 0) { r.append(c); if (c == q || c == '\n') q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; r.append(c); continue; }
            if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '/') {
                while (i < t.length() && t.charAt(i) != '\n') i++;
                if (i < t.length()) r.append('\n');
                continue;
            }
            if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '*') {
                int fin = t.indexOf("*/", i + 2);
                String dentro = t.substring(i, fin < 0 ? t.length() : fin + 2);
                dentro.chars().filter(x -> x == '\n').forEach(x -> r.append('\n'));
                i = fin < 0 ? t.length() : fin + 1;
                continue;
            }
            r.append(c);
        }
        return r.toString();
    }

    private static IllegalArgumentException error(int renglon, String msg) {
        return new IllegalArgumentException("reglas, renglón " + renglon + ": " + msg);
    }

    // ------------------------------------------------------------------ orden

    /** Reglas ordenadas por dependencias (la que asigna X antes que las que usan X); empate: orden escrito. */
    List<Regla> ordenadas() {
        List<Regla> o = ordenadas;
        if (o != null) return o;
        // salidas de las llamadas: los parámetros out/inout del procedimiento
        for (Regla r : reglas) {
            if (r.tipo != Tipo.LLAMAR) continue;
            OptimistProcedimiento p = definicion(r.proc);
            for (int k = 0; k < r.refs.size(); k++) {
                String ref = r.refs.get(k);
                if (ref == null) continue;
                boolean sale = p == null ? false : k < p.parametros.size() && !p.parametros.get(k).modo().equals("in");
                if (sale) r.escribe.add(ref);
            }
        }
        for (Regla r : reglas) {
            if (r.tipo != Tipo.ASIGNAR || !r.lee.contains(r.destino) || !r.eventos.isEmpty() || !r.afters.isEmpty()) continue;
            // V48: solo si la EXPRESIÓN usa el destino (PRSVersion = PRSVersion + 1). Si lo usa nada más la condición
            // (PRSListasPreciosId = ... if PRSListasPreciosId.IsEmpty()) es un valor inicial: corre con los cambios, como en GeneXus.
            if (r.expr != null && r.expr.atributos().stream().noneMatch(x -> x.equalsIgnoreCase(r.destino))) continue;
            boolean cadena = reglas.stream().anyMatch(x -> x.indice < r.indice && x.nivel == r.nivel && x.escribe.contains(r.destino));
            r.soloAlGrabar = !cadena;   // PRSVersion = PRSVersion + 1
        }
        int n = reglas.size();
        List<Set<Integer>> antes = new ArrayList<>();
        for (int i = 0; i < n; i++) antes.add(new HashSet<>());
        for (Regla a : reglas)
            for (Regla b : reglas) {
                if (a == b) continue;
                for (String x : a.escribe) {
                    // quien asigna X va antes de quien lo usa; y varias asignaciones de X, en el orden escrito
                    // (X = f(...) antes que X = Round(X, 2))
                    if ((b.lee.contains(x) && !b.escribe.contains(x)) || (b.escribe.contains(x) && a.indice < b.indice)) {
                        antes.get(b.indice).add(a.indice);
                        break;
                    }
                }
            }
        List<Regla> r = new ArrayList<>();
        boolean[] hecho = new boolean[n];
        for (int paso = 0; paso < n; paso++) {
            int elegido = -1;
            for (int i = 0; i < n && elegido < 0; i++) {
                if (hecho[i]) continue;
                boolean libre = true;
                for (int j : antes.get(i)) if (!hecho[j]) { libre = false; break; }
                if (libre) elegido = i;
            }
            if (elegido < 0) for (int i = 0; i < n && elegido < 0; i++) if (!hecho[i]) elegido = i;   // ciclo: orden escrito
            hecho[elegido] = true;
            r.add(reglas.get(elegido));
        }
        ordenadas = List.copyOf(r);
        return ordenadas;
    }

    private static OptimistProcedimiento definicion(String nombre) {
        OptimistProcedimiento p = OptimistProcedimiento.nativo(nombre);
        if (p != null) return p;
        try { return OptimistProcedimientos.buscar(nombre); } catch (RuntimeException e) { return null; }
    }

    // ------------------------------------------------------------------ ejecución

    public Estado nuevoEstado(String modo) { return new Estado(modo); }

    /** V49o: hay una regla "Campo.Visible = ..." (la pantalla arma la columna aunque sea oculta, y la regla la muestra). */
    public boolean controlaVisible(String campo) {
        for (Regla r : reglas) if (r.tipo == Tipo.VISIBLE && r.destino != null && r.destino.equalsIgnoreCase(campo)) return true;
        return false;
    }

    /**
     * V49c: si cambiaron estos datos de la cabecera (ej. los totales), qué hay que dar por cambiado en cada línea del detalle
     * para recalcularla: esos datos más los after() de la cabecera de sus reglas (así corre de nuevo, línea por línea,
     * BaseImponibleImpuestos.Call, que deja en &ImpuestosDetBaseCalculo sobre qué total va cada impuesto).
     * Vacío si ninguna regla del detalle usa lo que cambió.
     */
    public Set<String> cambiosParaLineas(Nivel det, Set<String> cambiadosCabecera) {
        Set<String> r = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean usa = false;
        for (Regla x : reglas) {
            if (x.nivel != det) continue;
            r.addAll(x.aftersCabecera);
            for (String a : cambiadosCabecera) if (x.lee.contains(a)) { usa = true; break; }
        }
        if (!usa) return Set.of();
        r.addAll(cambiadosCabecera);
        return r;
    }

    /** Ejecuta el inicio (evento Start): &WWPContext, &Mode y lo que cargue el inicio (&Decimales, &Instalacion...). */
    public void iniciar(Estado e, Map<String, Object> cabecera, OptimistCalculo.Abridor abridor, List<String> mensajes) {
        OptimistProcedimientos.asegurarNativos();
        e.vars.put("mode", e.modo);
        // V50b: como el Parm(&Mode, &PRSId) de la TRN de GeneXus: la clave del registro llega como variable (&PRSId) al inicio.
        // Antes quedaba en 0 y GetCbtePRS / PRSoPEDResultado (&PRSId) no encontraban el presupuesto.
        if (cabecera != null)
            for (OptimistTrnDefinicion.Campo k : trn.cabecera().claves()) {
                Object v = cabecera.get(k.nombre());
                if (v != null) e.vars.put(k.nombre().toLowerCase(Locale.ROOT), v);
            }
        OptimistProcedimiento ctx = OptimistProcedimiento.nativo("LoadWWPContext");
        try (Conexion c = new Conexion(abridor, null)) {
            if (ctx != null) e.vars.put("wwpcontext", ctx.ejecutar(List.of(), null, n -> null, mensajes, 0).get("context"));
            if (inicio != null) inicio.ejecutarCon(e.vars, cabecera, c.get(), OptimistProcedimientos::buscar, mensajes);
        } catch (SQLException | RuntimeException ex) {
            mensajes.add("No se pudo ejecutar el inicio de " + trn.nombre() + ": " + ex.getMessage());
        }
    }

    /**
     * Una pasada de reglas sobre un nivel (cabecera = null) o una línea.
     *   CARGA: al abrir: variables y Enabled/Visible (no toca atributos ni muestra errores).
     *   CAMBIO: con cada cambio: asignaciones, llamadas, Msg y Enabled/Visible (los Error se juntan pero no frenan).
     *   GRABAR: eventos == null: la pasada completa antes de grabar (Error frena); eventos != null: solo las de esos eventos.
     * valores se actualiza con lo que asignan las reglas; cambiados: los atributos que cambiaron (para after()).
     */
    public Efecto aplicar(Momento momento, Set<String> eventos, Nivel nivel, Map<String, Object> valores, Map<String, Object> cabecera,
                          Map<String, Object> viejos, Set<String> cambiados, Estado est, OptimistCalculo.Abridor abridor, Connection conexion) {
        return aplicar(momento, eventos, nivel, valores, cabecera, viejos, cambiados, est, abridor, conexion, false);
    }

    /**
     * V36: soloAfectadas = como Excel: corren solo las reglas que leen algo de 'cambiados' (o tienen after() de eso),
     * y en cadena las que leen lo que esas cambiaron. Una asignación que deja el mismo valor no sigue la cadena.
     * Así, cambiar la cantidad no vuelve a buscar el precio de lista, la bonificación del cliente ni la tasa de IVA.
     */
    public Efecto aplicar(Momento momento, Set<String> eventos, Nivel nivel, Map<String, Object> valores, Map<String, Object> cabecera,
                          Map<String, Object> viejos, Set<String> cambiados, Estado est, OptimistCalculo.Abridor abridor, Connection conexion,
                          boolean soloAfectadas) {
        Efecto ef = Efecto.nuevo();
        if (reglas.isEmpty()) return ef;
        Map<String, Object> vals = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        valores.forEach((k, v) -> { if (k != null) vals.put(k, v); });
        Set<String> cambio = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (cambiados != null) cambio.addAll(cambiados);
        Nivel cab = trn.cabecera();
        try (Conexion c = new Conexion(abridor, conexion)) {
            Ctx ctx = new Ctx(this, est, nivel == null ? cab : nivel, vals, cabecera, viejos, cambio, c);
            ctx.selectivo = soloAfectadas;
            List<Regla> candidatas = new ArrayList<>();
            for (Regla r : ordenadas()) if (r.nivel == nivel && corresponde(r, momento, eventos)) candidatas.add(r);
            Set<Regla> hechas = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Regla r : candidatas) {
                // en orden (el que escribe antes que el que lee): 'cambio' crece a medida que las reglas asignan
                if (soloAfectadas && !afectada(r, cambio)) continue;
                correr(r, candidatas, hechas, momento, ctx, ef, c, 0);
            }
        }
        // lo asignado vuelve al mapa del llamador
        valores.putAll(ef.asignados());
        if (cambiados != null) cambiados.addAll(cambio);
        return ef;
    }

    /**
     * Corre una regla (si no corrió en esta pasada) y, si asignó atributos, en ese mismo momento las reglas con
     * after(de ese atributo): así GeneXus calcula el subtotal con el descuento por unidad antes de multiplicarlo.
     */
    private void correr(Regla r, List<Regla> candidatas, Set<Regla> hechas, Momento momento, Ctx ctx, Efecto ef, Conexion c, int prof) {
        if (hechas.contains(r) || prof > 30) return;
        // una regla con after(X) espera a que X cambie (lo cargó el usuario o lo asignó otra regla)
        if (!r.afters.isEmpty() && r.afters.stream().noneMatch(ctx.cambio::contains)) return;
        hechas.add(r);
        boolean dep = depurar();
        if (dep) System.out.println("  ".repeat(prof) + "regla " + r.renglon + ": " + corto(r.texto));
        int desde = ctx.historial.size();
        long t0 = System.nanoTime();
        try {
            if (r.cond != null && !OptimistFormula.verdad(r.cond.evaluar(ctx))) {
                if (r.tipo == Tipo.NOACCEPT) for (String d : r.destinos) ef.habilitados().put(d, true);
                // V49t: si ninguna regla Class del campo corre, el campo vuelve a su estilo de siempre ("")
                if (r.tipo == Tipo.CLASE) ef.clases().putIfAbsent(r.destino, "");
                if (dep) System.out.println("  ".repeat(prof) + "   -> no corre (condición falsa), " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                return;
            }
            ejecutar(r, momento, ctx, ef, c);
            if (dep) {
                StringBuilder b = new StringBuilder();
                for (String x : ctx.historial.subList(desde, ctx.historial.size())) b.append(' ').append(x).append('=').append(ef.asignados().get(x));
                System.out.println("  ".repeat(prof) + "   -> " + (System.nanoTime() - t0) / 1_000_000 + " ms" + (b.length() == 0 ? "" : ", asignó" + b));
            }
        } catch (RuntimeException | SQLException ex) {
            String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            // V48: un procedimiento que falla al grabar (on BeforeComplete, AfterInsert...) frena el grabado y se muestra, como en GeneXus
            // V65k: también una asignación con .Udp() (&Msg = Proc.Udp(...)): antes se anotaba y se grababa igual
            if (momento == Momento.GRABAR && (r.tipo == Tipo.LLAMAR || llamaProcedimiento(r.texto))) {
                String quien = r.proc;
                if (quien == null) {
                    java.util.regex.Matcher mp = java.util.regex.Pattern.compile("(?i)(\\w+)\\s*\\.\\s*(?:call|udp)\\s*\\(").matcher(r.texto);
                    quien = mp.find() ? mp.group(1) : "la regla del renglón " + r.renglon;
                }
                System.out.println("[" + quien + "] " + m);
                ef.errores().add("Error en " + quien + ": " + m);
            } else ef.fallas().add("regla del renglón " + r.renglon + " (" + corto(r.texto) + "): " + m);
            return;
        }
        if (ctx.historial.size() == desde) return;
        List<String> nuevos = new ArrayList<>(ctx.historial.subList(desde, ctx.historial.size()));
        for (String x : nuevos)
            for (Regla d : candidatas) {
                if (hechas.contains(d)) continue;
                if (ctx.selectivo && !ctx.cambio.contains(x)) continue;   // asignó lo mismo que había: no sigue la cadena
                // after(X): se dispara apenas se asigna X
                if (d.afters.contains(x)) { correr(d, candidatas, hechas, momento, ctx, ef, c, prof + 1); continue; }
                // como GeneXus: quien usa X se calcula con el PRIMER valor que toma X, si ya tiene todos sus datos
                // (PrecioConDto = PrecioNeto - ValorDescuento usa el descuento por unidad, antes de multiplicarlo por la cantidad)
                if (d.lee.contains(x) && !d.escribe.contains(x) && d.afters.isEmpty() && listo(d, candidatas, hechas, ctx))
                    correr(d, candidatas, hechas, momento, ctx, ef, c, prof + 1);
            }
    }

    private static boolean afectada(Regla r, Set<String> cambio) {
        for (String x : r.afters) if (cambio.contains(x)) return true;
        for (String x : r.lee) if (cambio.contains(x)) return true;
        return false;
    }

    /** Cada dato que usa la regla ya tomó un valor en esta pasada (o nadie lo asigna, o ya corrieron todos los que lo asignan). */
    private static boolean listo(Regla d, List<Regla> candidatas, Set<Regla> hechas, Ctx ctx) {
        for (String y : d.lee) {
            if (d.escribe.contains(y)) continue;
            boolean tieneEscritor = false, pendiente = false;
            for (Regla w : candidatas) {
                if (w == d || !w.escribe.contains(y)) continue;
                tieneEscritor = true;
                if (!hechas.contains(w)) pendiente = true;
            }
            if (tieneEscritor && pendiente && !ctx.historial.contains(y)) return false;
        }
        return true;
    }

    /**
     * -Doptimist.reglas.depurar=true o la variable de entorno OPTIMIST_DEPURAR=1: muestra en la consola qué reglas corren,
     * en qué orden, cuánto tarda cada una y qué asigna (V48: también las que no corren por su condición).
     */
    private static boolean depurar() {
        return Boolean.getBoolean("optimist.reglas.depurar") || "1".equals(System.getenv("OPTIMIST_DEPURAR"));
    }

    private static String corto(String t) { return t.length() > 70 ? t.substring(0, 67) + "..." : t; }

    private static boolean corresponde(Regla r, Momento m, Set<String> eventos) {
        switch (m) {
            case CARGA:
                if (!r.eventos.isEmpty()) return false;
                return switch (r.tipo) {
                    case ENABLED, VISIBLE, NOACCEPT, CLASE -> true;
                    case ASIGNAR -> r.destino.startsWith("&") || r.dePantalla;
                    case LLAMAR -> r.escribe.stream().allMatch(x -> x.startsWith("&"));
                    default -> false;
                };
            case PANTALLA:
                return r.eventos.isEmpty() && (r.tipo == Tipo.ENABLED || r.tipo == Tipo.VISIBLE || r.tipo == Tipo.NOACCEPT || r.tipo == Tipo.CLASE
                        || r.dePantalla);
            case CAMBIO:
                return r.eventos.isEmpty() && !r.soloAlGrabar;
            default:
                if (eventos == null) return r.eventos.isEmpty();
                for (String e : r.eventos) if (eventos.contains(e)) return true;
                return false;
        }
    }

    private void ejecutar(Regla r, Momento momento, Ctx ctx, Efecto ef, Conexion c) throws SQLException {
        switch (r.tipo) {
            case ASIGNAR -> ctx.asignar(r.destino, r.expr.evaluar(ctx), ef);
            case VACIAR -> ctx.asignar(r.destino, r.nulo ? null : vacioDe(ctx, r.destino), ef);
            case ERROR -> {
                Object m = r.expr.evaluar(ctx);
                String t = m == null ? "" : String.valueOf(m);
                if (!t.isBlank()) ef.errores().add(t); else ef.errores().add("Error (regla del renglón " + r.renglon + ")");
            }
            case MSG -> { Object m = r.expr.evaluar(ctx); if (m != null && !String.valueOf(m).isBlank()) ef.mensajes().add(String.valueOf(m)); }
            case NOACCEPT -> { for (String d : r.destinos) ef.habilitados().put(d, false); }
            case ENABLED -> ef.habilitados().put(r.destino, OptimistFormula.verdad(r.expr.evaluar(ctx)));
            case VISIBLE -> ef.visibles().put(r.destino, OptimistFormula.verdad(r.expr.evaluar(ctx)));
            case CLASE -> { Object v = r.expr.evaluar(ctx); ef.clases().put(r.destino, v == null ? "" : String.valueOf(v).trim()); }
            case LLAMAR -> {
                OptimistProcedimiento p = definicion(r.proc);
                if (p == null) throw new IllegalStateException("falta el procedimiento " + r.proc + " (proc/" + r.proc + ".proc)");
                List<Object> vs = new ArrayList<>();
                for (OptimistFormula f : r.args) vs.add(f.evaluar(ctx));
                // V48: como en GeneXus web, el Msg() de un procedimiento llamado desde una regla no aparece en pantalla: va a la consola
                List<String> msgs = new ArrayList<>();
                Map<String, Object> salida = OptimistProcedimientos.ejecutar(r.proc, vs, c.get(), msgs);
                msgs.forEach(x -> System.out.println("[" + r.proc + "] Msg: " + x));
                for (int k = 0; k < p.parametros.size() && k < r.refs.size(); k++) {
                    OptimistProcedimiento.Parametro pa = p.parametros.get(k);
                    String ref = r.refs.get(k);
                    if (ref != null && !pa.modo().equals("in")) ctx.asignar(ref, salida.get(pa.nombre().toLowerCase(Locale.ROOT)), ef);
                }
            }
        }
    }

    private Object vacioDe(Ctx ctx, String destino) {
        if (destino.startsWith("&")) {
            OptimistProcedimiento.Variable d = inicio == null ? null : inicio.variable(destino);
            return d == null ? "" : OptimistProcedimiento.vacio(d);
        }
        Campo c = ctx.campo(destino);
        if (c == null) return "";
        return c.dominio().esNumerico() ? java.math.BigDecimal.ZERO : c.dominio().esTexto() ? "" : null;
    }

    /** Conexión de una pasada: la que viene (grabado, dentro de la transacción) o una que se abre si hace falta. */
    private static final class Conexion implements AutoCloseable {
        final OptimistCalculo.Abridor abridor; Connection c; final boolean propia;
        Conexion(OptimistCalculo.Abridor abridor, Connection dada) { this.abridor = abridor; this.c = dada; this.propia = dada == null; }
        Connection get() {
            try {
                if (c == null) {
                    if (abridor == null) throw new IllegalStateException("no hay conexión a la base");
                    c = abridor.abrir();
                }
                return c;
            } catch (SQLException e) { throw new IllegalStateException(e.getMessage(), e); }
        }
        @Override public void close() { if (propia && c != null) try { c.close(); } catch (SQLException ignorado) { } }
    }

    /** Contexto de las fórmulas de las reglas: atributos del nivel, cabecera, variables, tabla extendida. */
    private static final class Ctx implements OptimistFormula.Contexto {
        final OptimistReglas rs; final Estado est; final Nivel nivel;
        final Map<String, Object> vals, cab, viejos; final Set<String> cambio; final Conexion con;
        /** Atributos y variables asignados en esta pasada, en orden (para disparar los after()). */
        final List<String> historial = new ArrayList<>();
        /** V36: solo las reglas afectadas por lo que cambió (ver aplicar). */
        boolean selectivo;

        Ctx(OptimistReglas rs, Estado est, Nivel nivel, Map<String, Object> vals, Map<String, Object> cab, Map<String, Object> viejos,
            Set<String> cambio, Conexion con) {
            this.rs = rs; this.est = est; this.nivel = nivel; this.vals = vals; this.cambio = cambio; this.con = con;
            this.cab = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (cab != null) cab.forEach((k, v) -> { if (k != null) this.cab.put(k, v); });
            this.viejos = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (viejos != null) viejos.forEach((k, v) -> { if (k != null) this.viejos.put(k, v); });
        }

        Campo campo(String a) {
            Campo c = nivel.campo(a);
            return c != null ? c : rs.trn.cabecera().campo(a);
        }

        @Override public Object valor(String a) {
            if (a.startsWith("&")) {
                String n = a.substring(1);
                String low = n.toLowerCase(Locale.ROOT);
                if (low.startsWith("__after_")) {
                    String x = n.substring(8);
                    // V49c: en una regla de un detalle, after(atributo de la cabecera) vale siempre (ver Regla.aftersCabecera)
                    if (nivel != rs.trn.cabecera() && nivel.campo(x) == null && rs.trn.cabecera().campo(x) != null) return true;
                    return cambio.contains(x);
                }
                if (low.startsWith("__old_")) return viejos.get(n.substring(6));
                switch (low) {
                    case "__insert": return "INS".equals(est.modo);
                    case "__update": return "UPD".equals(est.modo);
                    case "__delete": return "DLT".equals(est.modo);
                    case "mode": if (!est.vars.containsKey("mode")) return est.modo;
                    default:
                }
                String[] partes = low.split("\\.");
                Object v;
                if (est.vars.containsKey(partes[0])) v = est.vars.get(partes[0]);
                else {
                    OptimistProcedimiento.Variable d = rs.inicio == null ? null : rs.inicio.variable(partes[0]);
                    v = d == null ? null : OptimistProcedimiento.vacio(d);
                }
                for (int i = 1; i < partes.length; i++) v = OptimistFormula.campo(v, n.split("\\.")[i]);
                return v;
            }
            if (vals.containsKey(a)) return vals.get(a);
            if (cab.containsKey(a)) return cab.get(a);
            return extendida(a);
        }

        /** Atributo que no está en la TRN: se busca en las tablas que referencia el nivel (tabla extendida). */
        private Object extendida(String a) {
            if (rs.trn.cabecera().campo(a) != null) return null;
            for (Nivel d : rs.trn.detalles()) if (d.campo(a) != null) return null;
            List<Nivel> niveles = nivel == rs.trn.cabecera() ? List.of(nivel) : List.of(nivel, rs.trn.cabecera());
            try {
                Map<String, OptimistProcedimiento.TablaBD> esq = OptimistProcedimiento.esquema(con.get());
                for (Nivel n : niveles)
                    for (Referencia r : n.referencias()) {
                        OptimistProcedimiento.TablaBD t = esq.get(r.tabla().toLowerCase(Locale.ROOT));
                        if (t == null || !t.columnas().containsKey(a.toLowerCase(Locale.ROOT))) continue;
                        Map<String, Object> iguales = new LinkedHashMap<>();
                        boolean completo = true;
                        for (var e : r.campos().entrySet()) {
                            Object v = vals.containsKey(e.getKey()) ? vals.get(e.getKey()) : cab.get(e.getKey());
                            if (OptimistTrnDatos.vacio(v)) { completo = false; break; }
                            iguales.put(e.getValue(), v);
                        }
                        if (!completo) continue;
                        String clave = r.tabla() + "|" + a + "|" + iguales;
                        if (est.cacheExtendida.containsKey(clave)) return est.cacheExtendida.get(clave);
                        Object v = OptimistCalculo.buscar(con.get(), r.tabla(), a, iguales);
                        est.cacheExtendida.put(clave, v);
                        return v;
                    }
            } catch (SQLException | RuntimeException e) { return null; }
            return null;
        }

        void asignar(String destino, Object v, Efecto ef) {
            if (destino.startsWith("&")) {
                String[] partes = destino.substring(1).split("\\.");
                String k = partes[0].toLowerCase(Locale.ROOT);
                boolean igual = false;
                if (partes.length == 1) {
                    OptimistProcedimiento.Variable d = rs.inicio == null ? null : rs.inicio.variable(k);
                    Object nuevo = OptimistProcedimiento.tipar(d, v);
                    igual = mismoValor(est.vars.get(k), nuevo);
                    est.vars.put(k, nuevo);
                }
                if (selectivo && igual) { marcar(destino); return; }
                cambio.add(destino);
                marcar(destino);
                return;
            }
            Campo c = campo(destino);
            Object x = c == null ? v : OptimistCalculo.redondear(c, v);
            String nombre = c == null ? destino : c.nombre();
            boolean igual = mismoValor(vals.get(nombre), x);
            vals.put(nombre, x);
            ef.asignados().put(nombre, x);
            if (selectivo && igual) { marcar(nombre); return; }
            cambio.add(nombre);
            marcar(nombre);
        }

        private void marcar(String nombre) { historial.add(nombre); }

        private static boolean mismoValor(Object a, Object b) {
            if (Objects.equals(a, b)) return true;
            if (a == null || b == null) return false;
            if (a instanceof Number || b instanceof Number) {
                try { return OptimistFormula.num(a).compareTo(OptimistFormula.num(b)) == 0; } catch (RuntimeException e) { return false; }
            }
            return String.valueOf(a).equals(String.valueOf(b));
        }

        @Override public Object procedimiento(String nombre, List<Object> args) {
            Object b = OptimistProcedimiento.Funciones.llamar(nombre, args);
            if (b != OptimistProcedimiento.Funciones.NO_ES) return b;
            if (nombre.startsWith(".")) throw new IllegalStateException("el método " + nombre.substring(1) + "() todavía no está soportado");
            if (!OptimistProcedimientos.existe(nombre)) throw new IllegalStateException("falta el procedimiento " + nombre + " (proc/" + nombre + ".proc)");
            try { return OptimistProcedimientos.udp(nombre, args, con.get()); }
            catch (SQLException e) { throw new IllegalStateException(nombre + ": " + e.getMessage(), e); }
        }

        @Override public Object buscar(String tabla, String columna, Map<String, Object> igualdades) {
            try { return OptimistCalculo.buscar(con.get(), tabla, columna, igualdades); }
            catch (SQLException e) { throw new IllegalStateException("find: " + e.getMessage(), e); }
        }
    }
}
