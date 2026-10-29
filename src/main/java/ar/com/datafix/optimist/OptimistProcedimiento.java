package ar.com.datafix.optimist;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V34 - Procedimiento en el lenguaje de GeneXus (archivos proc/*.proc), interpretado por el motor.
 * No se compila: se graba el .proc y el próximo uso ya toma el cambio (igual que los YAML).
 *
 * Formato del archivo:
 *   /* Qué hace:
 *      Descripción en castellano (documentación; lo que se ejecuta es lo de abajo).
 *   *&#47;
 *   Parm(in: &ImporteIn, inout: &DtoStr, out: &ImporteOut);      (también atributos: Parm(in: ArticulosPK))
 *   Variables
 *     &ImporteIn   Numeric(14.4)
 *     &Contexto    Sdt(WWPContext)          (estructura: &Contexto.Empresa)
 *     &Lista       Coleccion(SDTStock)      (lista: For &x in &Lista, &Lista.Add(&x), &Lista.Count)
 *   EndVariables
 *   ... código GeneXus ...
 *   Sub 'Nombre' ... EndSub
 *
 * Soporta: asignaciones (= += -=), If/Else/EndIf, Do Case/Case/Otherwise/EndCase, Do While/EndDo,
 * For &i = a To b [Step s]/EndFor, For &x in &coleccion/EndFor,
 * For Each [Tabla] + where/order/defined by + When None + EndFor (lee, actualiza atributos y Delete),
 * New ... [When Duplicate ...] EndNew (inserta), Commit / Rollback, Exit, Return, Sub/Do 'sub', Msg(),
 * llamadas a otros procedimientos (Proc.Call(...), Call(Proc, ...), &x = Proc.Udp(...)), funciones de GeneXus
 * y procedimientos "nativos" escritos en Java (registrarNativo), por ejemplo LoadWWPContext.
 */
public final class OptimistProcedimiento {

    public record Parametro(String modo, String nombre, boolean atributo) {
        public Parametro(String modo, String nombre) { this(modo, nombre, false); }
    }
    public record Variable(String nombre, String tipo, int longitud, int decimales, String estructura) {}

    /** Procedimiento escrito en Java: recibe los parámetros por nombre (minúscula) y devuelve los out/inout. */
    @FunctionalInterface
    public interface Nativo { Map<String, Object> ejecutar(Map<String, Object> entrada, Connection c) throws SQLException; }

    public final String nombre;
    public final String descripcion;
    public final List<Parametro> parametros;
    final Map<String, Variable> variables;
    final List<Sentencia> cuerpo;
    final Map<String, List<Sentencia>> subs;
    final Map<String, Set<String>> atributosSubs;
    final Nativo nativo;

    // ------------------------------------------------------------------ nativos

    private static final Map<String, OptimistProcedimiento> NATIVOS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Registra un procedimiento Java. parm como en GeneXus: "out: &Context". Tiene prioridad sobre un .proc del mismo nombre. */
    public static void registrarNativo(String nombre, String parm, Nativo impl) {
        List<Parametro> ps = parm == null || parm.isBlank() ? List.of() : leerParm(nombre, null, parm);
        NATIVOS.put(nombre.toLowerCase(Locale.ROOT), new OptimistProcedimiento(nombre, "(nativo, Java)", ps, Map.of(), List.of(), Map.of(), Map.of(), impl));
    }

    public static OptimistProcedimiento nativo(String nombre) { return NATIVOS.get(nombre.toLowerCase(Locale.ROOT)); }

    // ------------------------------------------------------------------ lectura del archivo

    private static final Pattern PARM = Pattern.compile("(?is)^\\s*parm\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern TIPO = Pattern.compile("(?i)^(numeric|character|char|varchar|longvarchar|date|datetime|boolean)\\s*(?:\\(\\s*(\\d+)\\s*(?:[.,]\\s*(\\d+))?\\s*\\))?$");
    private static final Pattern TIPO_OBJETO = Pattern.compile("(?i)^(sdt|coleccion|bc|ext)\\s*\\(\\s*([A-Za-z_][\\w.]*)\\s*\\)$");

    private OptimistProcedimiento(String nombre, String descripcion, List<Parametro> parm, Map<String, Variable> vars,
                                  List<Sentencia> cuerpo, Map<String, List<Sentencia>> subs, Map<String, Set<String>> atributosSubs,
                                  Nativo nativo) {
        this.nombre = nombre; this.descripcion = descripcion; this.parametros = parm;
        this.variables = vars; this.cuerpo = cuerpo; this.subs = subs; this.atributosSubs = atributosSubs; this.nativo = nativo;
    }

    /** Lee y valida un .proc. IllegalArgumentException con el renglón si algo está mal. */
    public static OptimistProcedimiento leer(String nombre, String texto) {
        String descripcion = "";
        Matcher d = Pattern.compile("(?s)^\\s*/\\*(.*?)\\*/").matcher(texto);
        if (d.find()) descripcion = d.group(1).replaceFirst("(?i)^\\s*qu[eé] hace:?", "").strip();
        List<Linea> lineas = lineas(texto);
        List<Parametro> parm = new ArrayList<>();
        Map<String, Variable> vars = new LinkedHashMap<>();
        List<Linea> resto = new ArrayList<>();
        boolean enVariables = false;
        for (Linea l : lineas) {
            String low = l.texto.toLowerCase(Locale.ROOT);
            if (enVariables) {
                if (low.equals("endvariables") || low.equals("end variables")) { enVariables = false; continue; }
                String[] p = l.texto.split("\\s+", 2);
                if (!p[0].startsWith("&") || p.length < 2) throw error(nombre, l, "variable inválida (ej: &Importe Numeric(14.2))");
                String tipo = p[1].replaceAll("//.*", "").trim();
                Matcher m = TIPO.matcher(tipo);
                Matcher o = TIPO_OBJETO.matcher(tipo);
                if (m.matches()) {
                    String t = m.group(1).toLowerCase(Locale.ROOT);
                    if (t.equals("char") || t.equals("character")) t = "character";
                    if (t.equals("longvarchar")) t = "varchar";
                    vars.put(clave(p[0]), new Variable(p[0].substring(1), t, m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                            m.group(3) == null ? 0 : Integer.parseInt(m.group(3)), null));
                } else if (o.matches()) {
                    vars.put(clave(p[0]), new Variable(p[0].substring(1), o.group(1).toLowerCase(Locale.ROOT), 0, 0, o.group(2)));
                } else throw error(nombre, l, "tipo inválido '" + p[1] + "' (Numeric(10.2), VarChar(40), Date, Boolean, Sdt(Nombre), Coleccion(Nombre)...)");
                continue;
            }
            if (low.equals("variables")) { enVariables = true; continue; }
            Matcher pm = PARM.matcher(l.texto);
            if (pm.matches()) { parm.addAll(leerParm(nombre, l, pm.group(1))); continue; }
            resto.add(l);
        }
        if (enVariables) throw error(nombre, lineas.isEmpty() ? null : lineas.get(lineas.size() - 1), "falta EndVariables");
        colecciones(vars, resto);   // V48c
        Lector lec = new Lector(nombre, resto);
        Map<String, List<Sentencia>> subs = new LinkedHashMap<>();
        List<Sentencia> cuerpo = lec.bloque(Set.of("<fin>"), subs);
        return new OptimistProcedimiento(nombre, descripcion, List.copyOf(parm), vars, cuerpo, subs, lec.atributosSubs, null);
    }

    /**
     * V48c: en GeneXus un SDT puede ser una colección: Sdt(SDTEmpresas) con sus ítems Sdt(SDTEmpresas.SDTEmpresasItem).
     * Una variable Sdt(X) es colección si hay una variable del ítem (Sdt(X.Algo)) y el procedimiento la usa como colección
     * (For &i in &X, &X.Add(), .Count, .Item(), .Sort(), .Clear(), .Remove()).
     */
    private static void colecciones(Map<String, Variable> vars, List<Linea> lineas) {
        Set<String> conItem = new HashSet<>();
        for (Variable v : vars.values())
            if ("sdt".equals(v.tipo()) && v.estructura() != null && v.estructura().contains("."))
                conItem.add(v.estructura().substring(0, v.estructura().indexOf('.')).trim().toLowerCase(Locale.ROOT));
        if (conItem.isEmpty()) return;
        StringBuilder texto = new StringBuilder();
        for (Linea l : lineas) texto.append(l.texto).append('\n');
        String t = texto.toString().toLowerCase(Locale.ROOT);
        for (var en : vars.entrySet()) {
            Variable v = en.getValue();
            if (!"sdt".equals(v.tipo()) || v.estructura() == null || v.estructura().contains(".")) continue;
            if (!conItem.contains(v.estructura().trim().toLowerCase(Locale.ROOT))) continue;
            String n = Pattern.quote("&" + v.nombre().toLowerCase(Locale.ROOT));
            if (Pattern.compile("\\bin\\s+" + n + "\\b|" + n + "\\s*\\.\\s*(add|count|item|sort|clear|remove)\\b").matcher(t).find())
                en.setValue(new Variable(v.nombre(), "coleccion", 0, 0, v.estructura()));
        }
    }

    private static List<Parametro> leerParm(String nombre, Linea l, String texto) {
        List<Parametro> r = new ArrayList<>();
        for (String x : partir(texto, ',')) {
            if (x.isBlank()) continue;
            String[] mp = x.trim().split("\\s*:\\s*", 2);
            String var = (mp.length == 2 ? mp[1] : mp[0]).trim();
            // V48c: una &variable sin modo, GeneXus la toma como inout (antes acá era in: PEDGetEntDir no devolvía &existe).
            // Un atributo sin modo sigue como in (filtra los For Each; no se devuelve a quien llama).
            String modo = mp.length == 2 ? mp[0].toLowerCase(Locale.ROOT) : var.startsWith("&") ? "inout" : "in";
            if (!var.matches("&?[A-Za-z_][A-Za-z0-9_]*")) throw error(nombre, l, "Parm: '" + var + "' tiene que ser una &variable o un atributo");
            if (!Set.of("in", "out", "inout").contains(modo)) throw error(nombre, l, "Parm: modo '" + modo + "' (in, out, inout)");
            r.add(var.startsWith("&") ? new Parametro(modo, var.substring(1), false) : new Parametro(modo, var, true));
        }
        return r;
    }

    private record Linea(int numero, String texto) {}

    /** Saca comentarios (fuera de los textos), junta renglones con paréntesis sin cerrar y separa por ';'. */
    private static List<Linea> lineas(String texto) {
        StringBuilder limpio = new StringBuilder();
        boolean enBloque = false;
        char comilla = 0;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (enBloque) {
                if (c == '*' && i + 1 < texto.length() && texto.charAt(i + 1) == '/') { enBloque = false; i++; }
                else if (c == '\n') limpio.append('\n');
                continue;
            }
            if (comilla != 0) { limpio.append(c); if (c == comilla || c == '\n') comilla = 0; continue; }
            if (c == '"' || c == '\'') { comilla = c; limpio.append(c); continue; }
            if (c == '/' && i + 1 < texto.length() && texto.charAt(i + 1) == '*') { enBloque = true; i++; continue; }
            if (c == '/' && i + 1 < texto.length() && texto.charAt(i + 1) == '/') {
                while (i < texto.length() && texto.charAt(i) != '\n') i++;
                limpio.append('\n');
                continue;
            }
            limpio.append(c);
        }
        List<Linea> r = new ArrayList<>();
        // "EndIf  EndFor" en el mismo renglón (GeneXus lo acepta): un cierre por renglón
        String[] ls = limpio.toString().replaceAll("(?im)\\b(endif|endfor|enddo|endcase|endsub)[ \\t]+(?=(endif|endfor|enddo|endcase|endsub|else)\\b)", "$1\n").split("\n", -1);
        StringBuilder pend = null; int inicio = 0;
        for (int i = 0; i < ls.length; i++) {
            String t = ls[i].strip();
            if (pend == null) { if (t.isEmpty()) continue; pend = new StringBuilder(t); inicio = i + 1; }
            else pend.append(' ').append(t);
            // un paréntesis sin cerrar, o un renglón que termina en operador (and, or, +, =...) o un renglón de abajo
            // que empieza con and/or, junta con el renglón de abajo (hasta 30 renglones: si no, era un error)
            boolean sigue = balance(pend) > 0 || CONTINUA.matcher(pend).find() || siguienteSigue(ls, i);
            if (!sigue || i - inicio >= 30) {
                String s = pend.toString().trim();
                if (s.endsWith(";") && !PARM.matcher(s).matches()) s = s.substring(0, s.length() - 1).trim();
                if (!s.isEmpty()) r.add(new Linea(inicio, s));
                pend = null;
            }
        }
        if (pend != null) r.add(new Linea(inicio, pend.toString().trim()));
        return r;
    }

    private static final Pattern CONTINUA = Pattern.compile("(?i)(?:\\band|\\bor|[+\\-*/=<>,])\\s*$");
    private static final Pattern EMPIEZA_Y_O = Pattern.compile("(?i)^(and|or|when(?!\\s+(none|duplicate)\\b))\\b");

    private static boolean siguienteSigue(String[] ls, int i) {
        for (int k = i + 1; k < ls.length; k++) {
            String t = ls[k].strip();
            if (!t.isEmpty()) return EMPIEZA_Y_O.matcher(t).find();
        }
        return false;
    }

    private static int balance(CharSequence s) {
        int b = 0; char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') q = c;
            else if (c == '(') b++;
            else if (c == ')') b--;
        }
        return b;
    }

    /** Parte por un separador que no esté dentro de paréntesis ni de textos. */
    static List<String> partir(String s, char sep) {
        List<String> r = new ArrayList<>();
        int b = 0, ini = 0; char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') q = c;
            else if (c == '(') b++;
            else if (c == ')') b--;
            else if (c == sep && b == 0) { r.add(s.substring(ini, i)); ini = i + 1; }
        }
        r.add(s.substring(ini));
        return r;
    }

    private static String clave(String var) { return (var.startsWith("&") ? var.substring(1) : var).toLowerCase(Locale.ROOT); }

    private static IllegalArgumentException error(String proc, Linea l, String msg) {
        return new IllegalArgumentException(proc + (l == null ? "" : ", renglón " + l.numero) + ": " + msg);
    }

    // ------------------------------------------------------------------ sentencias

    interface Sentencia { void ejecutar(Ejecucion e) throws SQLException; }

    private static final class Salir extends RuntimeException { Salir() { super(null, null, false, false); } }
    private static final class Retornar extends RuntimeException { Retornar() { super(null, null, false, false); } }

    private static OptimistFormula formula(String proc, Linea l, String texto) {
        try { return OptimistFormula.de(texto); }
        catch (IllegalArgumentException ex) { throw error(proc, l, ex.getMessage()); }
    }

    private static final Pattern ASIG = Pattern.compile("^(&?[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*(\\+=|-=|\\*=|/=|=)\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern LLAMADA = Pattern.compile("(?i)^(?:[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)*?([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*(call|udp)\\s*\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern CALL = Pattern.compile("(?i)^call\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?:,(.*))?\\)$", Pattern.DOTALL);
    private static final Pattern VACIAR = Pattern.compile("(?i)^(&?[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\s*\\.\\s*(setempty|setnull)\\s*\\(\\s*\\)$");
    private static final Pattern METODO = Pattern.compile("^&[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*\\s*\\.\\s*[A-Za-z_]\\w*\\s*\\(.*\\)$", Pattern.DOTALL);
    private static final Pattern EXTERNO = Pattern.compile("^(&[A-Za-z_]\\w*(?:\\.\\w+)*\\s*\\(.*?\\)(?:\\.\\w+)+)\\s*(?:\\+=|-=|=)\\s*(.+)$", Pattern.DOTALL);
    /** V61e: &Bc.Nivel.Item(&i).Campo = valor / += / -=  y  &Bc.Nivel.Item(&i).Campo.SetEmpty() (escribir en un ítem de una colección). */
    private static final Pattern ITEM_VACIAR = Pattern.compile("(?is)^(&[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*)\\s*\\.\\s*item\\s*\\((.+?)\\)((?:\\s*\\.\\s*[A-Za-z_]\\w*)+?)\\s*\\.\\s*(setempty|setnull)\\s*\\(\\s*\\)$");
    private static final Pattern ITEM_ASIG = Pattern.compile("(?is)^(&[A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*)\\s*\\.\\s*item\\s*\\((.+?)\\)((?:\\s*\\.\\s*[A-Za-z_]\\w*)+)\\s*(\\+=|-=|=)\\s*(.+)$");
    private static final String SUB = "\u0000sub:";
    /** V63: "Lineno 55" (constante; una expresión se ignora, como antes). */
    private static final Pattern LINENO = Pattern.compile("(?i)^lineno\\s+(\\d+)\\s*;?$");
    private static final Pattern CLAUSULA = Pattern.compile("(?i)where|order|defined|unique|using|blocking");

    /** Lee sentencias hasta una de las palabras de fin. */
    private static final class Lector {
        final String proc; final List<Linea> ls; int i = 0;
        Lector(String proc, List<Linea> ls) { this.proc = proc; this.ls = ls; }

        /** Atributos que usa cada For Each (o Sub) abierto: el de más arriba es el que se está leyendo. */
        final Deque<Set<String>> recolectores = new ArrayDeque<>();
        /** A dónde van las asignaciones de atributos: el For Each o el New más de adentro. */
        final Deque<Set<String>> destinos = new ArrayDeque<>();
        final Map<String, Set<String>> atributosSubs = new HashMap<>();
        /** V51e: recolectores en los que ya hubo un New: lo que se lee después puede ser del registro nuevo (MovStockId). */
        final Set<Set<String>> conNewPrevio = Collections.newSetFromMap(new IdentityHashMap<>());

        OptimistFormula f(Linea l, String texto) {
            OptimistFormula r = formula(proc, l, texto);
            Set<String> top = recolectores.peek();
            boolean opcional = top != null && conNewPrevio.contains(top);
            if (top != null) for (String a : r.atributos()) if (!a.startsWith("&") && !a.contains(".")) top.add(opcional ? "?" + a : a);
            return r;
        }

        String low() { return i < ls.size() ? ls.get(i).texto.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") : "<fin>"; }
        boolean empieza(String... ps) { String l = low(); for (String p : ps) if (l.equals(p) || l.startsWith(p + " ") || l.startsWith(p + "(")) return true; return false; }

        List<Sentencia> bloque(Set<String> fines, Map<String, List<Sentencia>> subs) {
            List<Sentencia> r = new ArrayList<>();
            while (true) {
                String l = low();
                for (String f : fines) if (l.equals(f) || (l.startsWith(f + " ") && !f.equals("end"))) return r;
                if (l.equals("<fin>")) throw error(proc, ls.isEmpty() ? null : ls.get(ls.size() - 1), "falta " + String.join(" / ", fines));
                Linea lin = ls.get(i);
                if (empieza("sub")) {
                    if (subs == null) throw error(proc, lin, "Sub dentro de otro bloque");
                    String n = lin.texto.substring(3).trim().replaceAll("^['\"]|['\"]$", "").toLowerCase(Locale.ROOT);
                    i++;
                    Set<String> atts = new LinkedHashSet<>();
                    recolectores.push(atts);
                    destinos.push(atts);   // lo que asigna la Sub se graba en el For Each desde el que se la llama
                    try { subs.put(n, bloque(Set.of("endsub", "end sub"), null)); } finally { recolectores.pop(); destinos.pop(); }
                    atributosSubs.put(n, atts);
                    i++;
                    continue;
                }
                r.add(sentencia(subs));
            }
        }

        Sentencia sentencia(Map<String, List<Sentencia>> subs) {
            Linea lin = ls.get(i);
            String t = lin.texto, l = low();
            i++;
            if (l.startsWith("if ") || l.startsWith("if(")) {
                OptimistFormula cond = f(lin, t.substring(2).trim());
                List<Sentencia> si = bloque(Set.of("else", "endif", "end if"), subs);
                List<Sentencia> no = List.of();
                if (low().equals("else")) { i++; no = bloque(Set.of("endif", "end if"), subs); }
                i++;
                List<Sentencia> fsi = si, fno = no;
                return e -> e.ejecutar(OptimistFormula.verdad(e.evaluar(cond)) ? fsi : fno);
            }
            if (l.equals("do case")) {
                List<OptimistFormula> conds = new ArrayList<>();
                List<List<Sentencia>> cuerpos = new ArrayList<>();
                List<Sentencia> otro = List.of();
                while (true) {
                    Linea c = i < ls.size() ? ls.get(i) : null;
                    String cl = low();
                    if (cl.equals("endcase") || cl.equals("end case")) { i++; break; }
                    if (cl.startsWith("case ")) {
                        i++;
                        conds.add(f(c, c.texto.substring(4).trim()));
                        cuerpos.add(bloque(Set.of("case", "otherwise", "endcase", "end case"), subs));
                    } else if (cl.equals("otherwise")) {
                        i++;
                        otro = bloque(Set.of("endcase", "end case"), subs);
                    } else if (cl.equals("<fin>")) throw error(proc, lin, "falta EndCase");
                    else throw error(proc, c, "se esperaba Case, Otherwise o EndCase");
                }
                List<Sentencia> fo = otro;
                return e -> {
                    for (int k = 0; k < conds.size(); k++)
                        if (OptimistFormula.verdad(e.evaluar(conds.get(k)))) { e.ejecutar(cuerpos.get(k)); return; }
                    e.ejecutar(fo);
                };
            }
            if (l.startsWith("do while ") || l.startsWith("do while(")) {
                OptimistFormula cond = f(lin, t.substring(8).trim());
                List<Sentencia> c = bloque(Set.of("enddo", "end do"), subs);
                i++;
                return e -> {
                    int vueltas = 0;
                    try {
                        while (OptimistFormula.verdad(e.evaluar(cond))) {
                            if (++vueltas > 1_000_000) throw new IllegalStateException(proc + ": Do While sin fin (más de un millón de vueltas)");
                            e.ejecutar(c);
                        }
                    } catch (Salir s) { /* Exit */ }
                };
            }
            if (l.startsWith("do ")) {
                String n = t.substring(2).trim().replaceAll("^['\"]|['\"]$", "").toLowerCase(Locale.ROOT);
                Set<String> top = recolectores.peek();
                if (top != null) top.add(SUB + n);
                return e -> {
                    List<Sentencia> s = e.proc.subs.get(n);
                    if (s == null) throw new IllegalStateException(proc + ": no existe la Sub '" + n + "'");
                    // V66x7: como en GeneXus, un For Each de la Sub NO queda anidado al For Each desde donde se la llama (el
                    // anidamiento es por cómo está escrito). Los atributos de afuera se siguen pudiendo leer.
                    if (e.filas.isEmpty()) { e.ejecutar(s); return; }
                    e.filas.push(new FronteraSub());
                    try { e.ejecutar(s); } finally { e.filas.pop(); }
                };
            }
            if (l.startsWith("for each")) return paraCada(lin, t.substring(8).trim(), subs);
            if (l.startsWith("for ")) {
                Matcher en = Pattern.compile("(?i)^for\\s+(&\\w+)\\s+in\\s+(.+)$").matcher(t);
                if (en.matches()) {
                    String var = en.group(1);
                    OptimistFormula col = f(lin, en.group(2));
                    List<Sentencia> c = bloque(Set.of("endfor", "end for"), subs);
                    i++;
                    return e -> {
                        Object v = e.evaluar(col);
                        if (v == null) return;
                        if (!(v instanceof List<?> lista)) throw new IllegalStateException(proc + ", renglón " + lin.numero + ": " + en.group(2) + " no es una colección");
                        try { for (Object x : new ArrayList<>(lista)) { e.asignar(var, x); e.ejecutar(c); } }
                        catch (Salir s) { /* Exit */ }
                    };
                }
                Matcher m = Pattern.compile("(?i)^for\\s+(&\\w+)\\s*=\\s*(.+?)\\s+to\\s+(.+?)(?:\\s+step\\s+(.+))?$").matcher(t);
                if (!m.matches()) throw error(proc, lin, "For inválido (ej: For &i = 1 To 10, o For &x in &Lista)");
                String var = m.group(1);
                OptimistFormula desde = f(lin, m.group(2)), hasta = f(lin, m.group(3));
                OptimistFormula paso = m.group(4) == null ? null : f(lin, m.group(4));
                List<Sentencia> c = bloque(Set.of("endfor", "end for"), subs);
                i++;
                return e -> {
                    // V86: For &Fecha = &FechaDesde To &FechaHasta [Step n]: recorre los días, como GeneXus (antes daba una sola vuelta con 0)
                    Object d0 = e.evaluar(desde);
                    if (d0 instanceof java.time.LocalDate || d0 instanceof java.time.LocalDateTime) {
                        Object h0 = e.evaluar(hasta);
                        java.time.LocalDate dh = h0 instanceof java.time.LocalDateTime hd ? hd.toLocalDate() : h0 instanceof java.time.LocalDate hl ? hl : null;
                        if (dh == null) return;
                        long dias = paso == null ? 1 : OptimistFormula.num(e.evaluar(paso)).longValue();
                        if (dias == 0) return;
                        java.time.LocalDate dia = d0 instanceof java.time.LocalDateTime dt ? dt.toLocalDate() : (java.time.LocalDate) d0;
                        e.asignar(var, dia);
                        try {
                            while (true) {
                                Object v = e.valorVariable(var);
                                java.time.LocalDate actual = v instanceof java.time.LocalDateTime vt ? vt.toLocalDate() : v instanceof java.time.LocalDate vd ? vd : null;
                                if (actual == null || (dias > 0 ? actual.isAfter(dh) : actual.isBefore(dh))) break;
                                e.ejecutar(c);
                                e.asignar(var, actual.plusDays(dias));
                            }
                        } catch (Salir s) { /* Exit */ }
                        return;
                    }
                    BigDecimal h = OptimistFormula.num(e.evaluar(hasta));
                    BigDecimal p = paso == null ? BigDecimal.ONE : OptimistFormula.num(e.evaluar(paso));
                    e.asignar(var, OptimistFormula.num(d0));
                    try {
                        while (p.signum() >= 0 ? OptimistFormula.num(e.valorVariable(var)).compareTo(h) <= 0
                                               : OptimistFormula.num(e.valorVariable(var)).compareTo(h) >= 0) {
                            e.ejecutar(c);
                            e.asignar(var, OptimistFormula.num(e.valorVariable(var)).add(p));
                        }
                    } catch (Salir s) { /* Exit */ }
                };
            }
            if (l.equals("new") || l.startsWith("new ")) return nuevo(lin, subs);
            if (l.equals("delete")) {
                if (destinos.isEmpty()) throw error(proc, lin, "Delete va dentro de un For Each");
                destinos.peek().add("!delete");   // V48c: el For Each borra: bloquea las filas que lee
                return e -> e.borrarFilaActual(proc + ", renglón " + lin.numero);
            }
            if (l.equals("exit")) return e -> { throw new Salir(); };
            if (l.equals("return")) return e -> { throw new Retornar(); };
            // V51f: Commit no hace nada. Cada operación (confirmar una TRN, una acción del WorkWith, un botón de un web panel)
            // es UNA transacción: se confirma al final si no hubo error, y si algo falla se deshace todo. Los Commit intermedios
            // de GeneXus dejaban cosas a medias (un comprobante numerado sin haber actualizado el pedido).
            if (l.equals("commit")) return e -> { };
            if (l.equals("rollback")) return e -> { if (!e.conexion.getAutoCommit()) e.conexion.rollback(); };
            if (l.startsWith("msg(") || l.startsWith("msg ")) {
                String a = t.substring(3).trim();
                if (a.startsWith("(") && a.endsWith(")")) a = a.substring(1, a.length() - 1);
                List<String> partes = partir(a, ',');
                OptimistFormula m = f(lin, partes.get(0));
                boolean status = partes.size() > 1 && partes.get(1).trim().equalsIgnoreCase("status");   // Msg(x, status): barra de estado
                // V48: como en GeneXus web, el Msg() de un procedimiento LLAMADO no aparece en pantalla (suelen ser de depuración):
                // va a la consola del servidor. Solo se muestran los del objeto que se ejecuta (el evento del panel, la acción).
                return e -> {
                    String txt = String.valueOf(e.evaluar(m));
                    if (e.profundidad > 0 || status) System.out.println("[" + e.proc.nombre + "] Msg: " + txt);
                    else e.mensajes.add(txt);
                };
            }
            if (l.equals("header") || l.equals("footer")) {
                // V49: lo que se imprime al empezar (Header) y al terminar (Footer) cada página
                boolean esPie = l.equals("footer");
                // V63: el "Lineno N" del Footer dice dónde empieza el pie: el cuerpo de la hoja no lo pasa
                Integer lineaPie = null;
                // V84h: desde el renglón que sigue al Footer (i ya lo apunta); antes empezaba uno después y un Footer que arranca
                // con Lineno (De las Misiones) no lo veía: el cuerpo se encimaba con el pie
                if (esPie) for (int k = i; k < ls.size(); k++) {
                    String tk = ls.get(k).texto.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                    if (tk.equals("end")) break;
                    Matcher ln = LINENO.matcher(tk);
                    if (ln.matches()) { lineaPie = Integer.valueOf(ln.group(1)); break; }
                }
                final Integer lineaDelPie = lineaPie;
                List<Sentencia> c = bloque(Set.of("end"), subs);
                i++;
                return e -> {
                    if (e.impresion == null) return;
                    Runnable r = () -> {
                        try { e.ejecutar(c); } catch (SQLException ex) { throw new IllegalStateException(ex.getMessage(), ex); }
                    };
                    if (esPie) e.impresion.pie(r, lineaDelPie); else e.impresion.encabezado(r);
                };
            }
            if (l.startsWith("print ")) {
                // V49: print Bloque: el bloque del diseño (impresiones/<proc>.yaml) con los valores de ahora (variables y atributos
                // del For Each). Sus atributos se agregan a los que trae el For Each, como en GeneXus.
                String bn = t.substring(5).trim();
                OptimistImpresion.Diseno dis = disenoDe.apply(proc);
                if (dis == null) return e -> { };
                OptimistImpresion.Bloque b = dis.bloque(bn);
                if (b == null) throw error(proc, lin, "no existe el bloque '" + bn + "' en impresiones/" + dis.nombre() + ".yaml");
                List<String> exs = new ArrayList<>(b.expresiones());
                exs.removeIf(x -> x.trim().equalsIgnoreCase("&Page"));   // V65: el número de hoja lo pone la impresión
                List<OptimistFormula> fs = new ArrayList<>();
                for (String x : exs) {
                    try { fs.add(f(lin, x)); }
                    catch (RuntimeException ex) { throw error(proc, lin, "bloque " + bn + ": '" + x + "' no se entiende (" + ex.getMessage() + ")"); }
                }
                String nombreBloque = b.nombre();
                return e -> {
                    if (e.impresion == null) return;
                    Map<String, Object> vals = new HashMap<>();
                    for (int k = 0; k < exs.size(); k++) {
                        try { vals.put(exs.get(k), e.evaluar(fs.get(k))); }
                        catch (RuntimeException ex) { vals.put(exs.get(k), null); }
                    }
                    e.impresion.imprimir(nombreBloque, vals);
                };
            }
            if (l.equals("eject")) return e -> { if (e.impresion != null) e.impresion.saltarPagina(); };
            if (l.startsWith("output_file")) {   // V49: Output_file(&Archivo, 'pdf') (en GeneXus es una regla): el nombre del PDF
                Matcher of = Pattern.compile("(?i)^output_file\\s*\\((.*)\\)\\s*;?$", Pattern.DOTALL).matcher(t);
                if (!of.matches()) throw error(proc, lin, "Output_file inválido (ej: Output_file(&Archivo, 'pdf'))");
                OptimistFormula fo = f(lin, partir(of.group(1), ',').get(0).trim());
                return e -> { if (e.impresion != null) e.impresion.archivo(String.valueOf(e.evaluar(fo)).trim()); };
            }
            Matcher lnm = LINENO.matcher(l.replaceAll("\\s+", " "));
            if (lnm.matches()) {
                // V63: Lineno N de GeneXus: el próximo bloque va en el renglón N de la hoja (renglones de 15 centésimas de pulgada)
                int n = Integer.parseInt(lnm.group(1));
                return e -> { if (e.impresion != null) e.impresion.renglon(n); };
            }
            if (Set.of("load", "print", "eject", "lineno", "mb", "mt", "cp", "noskip").contains(l.split("[ (\t]")[0]))
                return e -> { };
            if (l.startsWith("submit")) return noSoportado(lin, "Submit todavía no está soportado");
            if (l.startsWith("sql ") || l.startsWith("sql(") || l.startsWith("csharp ")) return noSoportado(lin, "SQL/C# embebido: pasarlo a un procedimiento nativo (registrarNativo)");
            if (l.startsWith("java ") || l.equals("java")) return noSoportado(lin, "código Java embebido: pasarlo a un procedimiento nativo (registrarNativo)");
            // V61e: escribir en un ítem de una colección (renglón de un nivel de un BC o de un Sdt colección).
            // Antes daba "propiedades de objetos externos no soportadas" y el .SetEmpty() se ignoraba sin avisar.
            Matcher iv = ITEM_VACIAR.matcher(t);
            Matcher ia = iv.matches() ? null : ITEM_ASIG.matcher(t);
            if (iv.matches() || (ia.matches() && !ia.group(5).trim().startsWith("="))) {
                Matcher m = iv.matches() ? iv : ia;
                String coleccion = m.group(1).replaceAll("\\s+", "");
                OptimistFormula indice = f(lin, m.group(2).trim());
                String camino = m.group(3).replaceAll("\\s+", "").substring(1);
                String op = iv.matches() ? null : ia.group(4);
                OptimistFormula valor = iv.matches() ? null : f(lin, ia.group(5).trim());
                String texto = t;
                return e -> {
                    Object v = valor == null ? null : e.evaluar(valor);
                    e.asignarItem(coleccion, OptimistFormula.num(e.evaluar(indice)).intValue(), camino, v, op,
                            proc + ", renglón " + lin.numero + ": " + texto);
                };
            }
            Matcher va = VACIAR.matcher(t);
            if (va.matches()) {
                String dest = va.group(1);
                boolean nulo = va.group(2).equalsIgnoreCase("setnull");
                if (dest.startsWith("&")) return e -> e.asignar(dest, null);
                return asignarAtributo(lin, dest, e -> nulo ? null : "");
            }
            Matcher cl = LLAMADA.matcher(t);
            if (cl.matches()) return llamada(lin, cl.group(1), cl.group(3));
            Matcher cc = CALL.matcher(t);
            if (cc.matches()) return llamada(lin, cc.group(1), cc.group(2) == null ? "" : cc.group(2));
            Matcher a = ASIG.matcher(t);
            if (a.matches() && !a.group(3).startsWith("=")) {
                String dest = a.group(1), op = a.group(2);
                String expr = a.group(3).trim();
                OptimistFormula fo = f(lin, expr);
                Valor v = e -> {
                    Object x = e.evaluar(fo);
                    if (op.equals("+=")) {
                        // V48c: &Texto += "..." concatena (antes sumaba como número: &MsgError += "Error" daba "0")
                        Object actual = e.valor(dest);
                        Variable dv = dest.contains(".") ? null : e.proc.variables.get(clave(dest));
                        boolean texto = dv != null ? dv.tipo().equals("character") || dv.tipo().equals("varchar")
                                : actual instanceof String || (x instanceof String sx && !sx.isBlank() && !sx.trim().matches("-?\\d+(\\.\\d+)?"));
                        if (texto) return (actual == null ? "" : String.valueOf(actual)) + (x == null ? "" : String.valueOf(x));
                        return OptimistFormula.num(actual).add(OptimistFormula.num(x));
                    }
                    if (op.equals("-=")) return OptimistFormula.num(e.valor(dest)).subtract(OptimistFormula.num(x));
                    if (op.equals("*=")) return OptimistFormula.num(e.valor(dest)).multiply(OptimistFormula.num(x));
                    if (op.equals("/=")) {
                        java.math.BigDecimal d = OptimistFormula.num(x);
                        return d.signum() == 0 ? java.math.BigDecimal.ZERO : OptimistFormula.num(e.valor(dest)).divide(d, java.math.MathContext.DECIMAL64);
                    }
                    return x;
                };
                if (dest.startsWith("&")) return e -> e.asignar(dest, v.de(e));
                if (dest.contains(".")) throw error(proc, lin, "no se puede asignar " + dest);
                return asignarAtributo(lin, dest, v);
            }
            Matcher ext = EXTERNO.matcher(t);
            if (ext.matches()) {
                OptimistFormula valor = f(lin, ext.group(2));   // igual se valida la expresión
                String destino = ext.group(1).trim();
                Sentencia no = noSoportado(lin, destino + ": propiedades de objetos externos (Excel, archivos...) todavía no están soportadas");
                Matcher var = Pattern.compile("^(&[A-Za-z_]\\w*)").matcher(destino);
                if (!var.find()) return no;
                String nombreVar = var.group(1);
                // V66: las celdas de un Excel: &Excel.Cells(fila, columna).Text / .Number / .Date / .Bold / .Color = valor
                Matcher cel = Pattern.compile("(?is)^&[A-Za-z_]\\w*\\s*\\.\\s*cells\\s*\\((.*)\\)\\s*\\.\\s*([A-Za-z_]\\w*)$").matcher(destino);
                List<String> fc = cel.matches() ? partir(cel.group(1), ',') : List.of();
                OptimistFormula ff = fc.size() == 2 ? f(lin, fc.get(0)) : null, fco = fc.size() == 2 ? f(lin, fc.get(1)) : null;
                String prop = cel.matches() ? cel.group(2) : null;
                return e -> {
                    if (e.valorVariable(nombreVar) instanceof PlanillaExcel px) {
                        Object v = e.evaluar(valor);
                        if (ff != null) px.planilla.poner(OptimistFormula.num(e.evaluar(ff)).intValue(), OptimistFormula.num(e.evaluar(fco)).intValue(), prop, v);
                        return;
                    }
                    no.ejecutar(e);
                };
            }
            Matcher desde = Pattern.compile("(?i)^(&[A-Za-z_]\\w*)\\s*\\.\\s*(fromurl|fromstring)\\s*\\((.*)\\)$", Pattern.DOTALL).matcher(t);
            Matcher img = Pattern.compile("(?i)^(&[A-Za-z_]\\w*)\\s*\\.\\s*fromimage\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)$").matcher(t);
            if (img.matches()) {   // V49: &Logo.FromImage(LogoIluminacionCenter): una imagen fija de la KB (impresiones/<Nombre>.jpg|png)
                String dest = img.group(1), nombreImg = img.group(2);
                return e -> e.asignar(dest, nombreImg);
            }
            if (desde.matches()) {   // V49: &Imagen.FromURL(&Archivo), &Texto.FromString(&x): le asigna el valor
                OptimistFormula fo = f(lin, desde.group(3));
                String dest = desde.group(1);
                return e -> e.asignar(dest, e.evaluar(fo));
            }
            if (METODO.matcher(t).matches()) {   // &Lista.Add(&x), &Sdt.Clear(), ...
                OptimistFormula fo = f(lin, t);
                return e -> e.evaluar(fo);
            }
            // Proc(args) suelto
            Matcher suelto = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)$", Pattern.DOTALL).matcher(t);
            if (suelto.matches()) return llamada(lin, suelto.group(1), suelto.group(2));
            throw error(proc, lin, "no entiendo '" + t + "'");
        }

        Sentencia asignarAtributo(Linea lin, String atributo, Valor v) {
            if (destinos.isEmpty()) {
                // V48c: GeneXus lo acepta (con un aviso de especificación) y no graba nada: acá tampoco (CPBCHeredarPED99)
                String donde0 = proc + ", renglón " + lin.numero;
                return e -> { if (DEPURAR) System.out.println("[" + donde0 + "] " + atributo + " = ... fuera de un For Each o New: no se graba"); };
            }
            destinos.peek().add("=" + atributo);   // "=X": X se graba
            String donde = proc + ", renglón " + lin.numero;
            return e -> e.asignarAtributo(atributo, v.de(e), donde);
        }

        Sentencia noSoportado(Linea lin, String msg) {
            String m = proc + ", renglón " + lin.numero + ": " + msg;
            return e -> { throw new UnsupportedOperationException(m); };
        }

        /** Proc.Call(a, &b): los &variables pasan por referencia (reciben los out/inout). */
        Sentencia llamada(Linea lin, String nombreProc, String args) {
            List<String> as = args.isBlank() ? List.of() : partir(args, ',');
            List<OptimistFormula> fs = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            for (String x : as) {
                String s = x.trim();
                fs.add(f(lin, s));
                refs.add(s.matches("&[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*") ? s : null);
            }
            return e -> {
                List<Object> vs = new ArrayList<>();
                for (OptimistFormula fo : fs) vs.add(e.evaluar(fo));
                OptimistProcedimiento otro = e.buscar(nombreProc);
                Map<String, Object> salida = e.llamar(nombreProc, vs);
                for (int k = 0; k < otro.parametros.size() && k < refs.size(); k++) {
                    Parametro p = otro.parametros.get(k);
                    if (!p.modo().equals("in") && refs.get(k) != null) e.asignar(refs.get(k), salida.get(p.nombre().toLowerCase(Locale.ROOT)));
                }
            };
        }

        /** New + asignaciones de atributos + [When Duplicate + ...] + EndNew. */
        Sentencia nuevo(Linea lin, Map<String, List<Sentencia>> subs) {
            while (empieza("defined by", "blocking")) i++;
            Set<String> asignados = new LinkedHashSet<>();
            destinos.push(asignados);
            // V51d: lo que se lee dentro del New y es del registro nuevo (CPBCDetPrecioNeto después de asignarlo) no es del
            // For Each de afuera: antes se le sumaba y el For Each no encontraba una tabla con todo ("no encuentro una tabla con...")
            Set<String> leidos = new LinkedHashSet<>();
            recolectores.push(leidos);
            List<Sentencia> c, dup = null;
            try {
                c = bloque(Set.of("when duplicate", "endnew", "end new"), subs);
                if (low().equals("when duplicate")) { i++; dup = bloque(Set.of("endnew", "end new"), subs); }
            } finally { destinos.pop(); recolectores.pop(); }
            Set<String> propios = new HashSet<>();
            for (String x : asignados) propios.add((x.startsWith("=") ? x.substring(1) : x).toLowerCase(Locale.ROOT));
            Set<String> afuera = recolectores.peek();
            if (afuera != null) {
                for (String x : leidos) if (!propios.contains(x.toLowerCase(Locale.ROOT).replaceFirst("^\\?", ""))) afuera.add(x);
                conNewPrevio.add(afuera);
            }
            i++;
            if (asignados.isEmpty()) throw error(proc, lin, "New sin atributos");
            return new Nuevo(proc, lin.numero, c, dup, asignados);
        }

        /** Una cláusula de For Each (where / order / defined by / unique / using / blocking). false si no es una. */
        boolean clausula(Linea x, String texto, List<OptimistFormula> wheres, List<String> textosWhere, List<String> orden, Set<String> atributos) {
            String l = texto.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (l.startsWith("where ") || l.startsWith("where(")) {
                String w = texto.substring(5).trim();
                Matcher cuando = Pattern.compile("(?is)^(.*?)\\s+when\\s+(.+)$").matcher(w);
                if (cuando.matches()) {   // where X when C
                    String combinada = "(" + cuando.group(1) + ") or not (" + cuando.group(2) + ")";
                    wheres.add(f(x, combinada));
                    textosWhere.add("\u0001" + cuando.group(2).trim() + "\u0001" + cuando.group(1).trim());   // V49w: C y X por separado
                    return true;
                }
                wheres.add(f(x, w)); textosWhere.add(w);
                return true;
            }
            if (l.startsWith("order ") || l.startsWith("order(")) {
                String o = texto.substring(5).trim().replaceAll("(?is)\\s+when\\s+.*$", "");
                // "order (A)" es descendente: se guarda como "(A"
                for (String s : o.replaceAll("\\(\\s*", " (").replaceAll("\\)", " ").trim().split("[\\s,]+"))
                    if (!s.isBlank() && !s.equalsIgnoreCase("none") && !s.equals("(")) orden.add(s);
                return true;
            }
            if (l.startsWith("defined by")) {
                for (String s : texto.substring(10).trim().split("[\\s,]+")) if (!s.isBlank()) atributos.add(s);
                return true;
            }
            return l.startsWith("unique") || l.startsWith("using") || l.startsWith("blocking");
        }

        /** For Each [Tabla] + where / order / defined by + cuerpo + When None + EndFor. */
        Sentencia paraCada(Linea lin, String tablaExplicita, Map<String, List<Sentencia>> subs) {
            Set<String> atributos = new LinkedHashSet<>();
            recolectores.push(atributos);
            destinos.push(atributos);
            try {
                List<OptimistFormula> wheres = new ArrayList<>();
                List<String> textosWhere = new ArrayList<>();
                List<String> orden = new ArrayList<>();
                // "For each [Tabla] [order X] [where Y]" también en el mismo renglón
                Matcher enLinea = Pattern.compile("(?is)^([A-Za-z_]\\w*)?\\s*((?:where|order|defined\\s+by|unique|using|blocking)\\b.*)?$").matcher(tablaExplicita);
                if (enLinea.matches() && enLinea.group(1) != null && CLAUSULA.matcher(enLinea.group(1)).matches()) enLinea = null;
                if (enLinea != null && enLinea.matches()) {
                    String resto = enLinea.group(2);
                    tablaExplicita = enLinea.group(1) == null ? "" : enLinea.group(1);
                    if (resto != null && !clausula(lin, resto.trim(), wheres, textosWhere, orden, atributos))
                        throw error(proc, lin, "no entiendo '" + resto + "'");
                } else if (!tablaExplicita.isBlank()) {
                    Matcher k = Pattern.compile("(?is)^(.*?)\\s+((?:where|order|defined\\s+by)\\b.*)$").matcher(tablaExplicita);
                    if (k.matches()) { tablaExplicita = k.group(1).trim(); clausula(lin, k.group(2).trim(), wheres, textosWhere, orden, atributos); }
                }
                while (i < ls.size() && clausula(ls.get(i), ls.get(i).texto, wheres, textosWhere, orden, atributos)) i++;
                int desdeCuerpo = i;
                List<Sentencia> c = bloque(Set.of("when none", "endfor", "end for"), subs);
                // V48: si el cuerpo solo copia atributos a &variables (&X = Atributo), lo que queda es la ÚLTIMA fila: se lee una sola
                int finCuerpo = i;
                boolean soloUltima = i > desdeCuerpo;
                for (int k = desdeCuerpo; k < i && soloUltima; k++) {
                    String t = ls.get(k).texto.trim();
                    if (t.isEmpty()) continue;
                    soloUltima = t.matches("(?s)&[A-Za-z_]\\w*\\s*=\\s*([A-Za-z_]\\w*|-?\\d+(\\.\\d+)?|\"[^\"]*\"|'[^']*')");
                }
                List<Sentencia> ninguno = List.of();
                if (low().equals("when none")) {
                    i++;
                    // lo de When None no es de esta tabla (no hay registro)
                    recolectores.push(new LinkedHashSet<>());
                    try { ninguno = bloque(Set.of("endfor", "end for"), subs); } finally { recolectores.pop(); }
                }
                i++;
                for (String o : orden) atributos.add(o.startsWith("(") ? o.substring(1) : o);
                // V48: si el cuerpo termina en Exit (sin condición), se usa solo la PRIMERA fila: se pide una sola (LIMIT 1)
                String ultimo = "";
                for (int k = desdeCuerpo; k < finCuerpo; k++) if (!ls.get(k).texto.isBlank()) ultimo = ls.get(k).texto.trim().toLowerCase(Locale.ROOT);
                boolean soloPrimera = ultimo.equals("exit");
                ParaCada pc = new ParaCada(proc, lin.numero, tablaExplicita.isBlank() ? null : tablaExplicita.trim(), wheres, textosWhere, orden, c, ninguno, atributos);
                pc.soloUltima = soloUltima;
                pc.soloPrimera = soloPrimera;
                return pc;
            } finally { recolectores.pop(); destinos.pop(); }
        }
    }

    @FunctionalInterface private interface Valor { Object de(Ejecucion e) throws SQLException; }

    // ------------------------------------------------------------------ base de datos

    /** Tablas y columnas de la base (information_schema), para deducir la tabla base como GeneXus. */
    /** vacios (V48): columna (minúscula) -> valor vacío de GeneXus (0 o "") para las NOT NULL sin valor por defecto. */
    record TablaBD(String nombre, Map<String, String> columnas, List<String> clave, Set<String> autonumericas, Map<String, Object> vacios,
                   Map<String, String> tipos, Set<String> nulables, Map<String, Integer> largos) {
        TablaBD(String nombre, Map<String, String> columnas, List<String> clave, Set<String> autonumericas) { this(nombre, columnas, clave, autonumericas, Map.of(), Map.of(), Set.of(), Map.of()); }
    }
    private static final Map<String, Map<String, TablaBD>> ESQUEMAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Olvida la estructura leída (por ejemplo, después de crear una tabla). */
    public static void olvidarEsquema() { ESQUEMAS.clear(); }

    /**
     * V71b: clave de todo lo que se recuerda por base de datos: el UUID del cliente (instalación única) más el nombre de
     * la base. Antes era solo el nombre de la base: dos clientes con bases del mismo nombre en distintos servidores MySQL
     * habrían compartido la estructura y lo recordado. Sin instalación única el UUID queda vacío.
     */
    static String claveBase(Connection c) throws SQLException { return prefijoCliente() + c.getCatalog(); }

    /** V71b: "uuid|" del cliente actual ("|" sin instalación única). */
    static String prefijoCliente() {
        OptimistDatabase.Cliente cl;
        try { cl = OptimistDatabase.clienteActual(); } catch (RuntimeException | LinkageError e) { cl = null; }   // pruebas sin el driver de MySQL
        return (cl == null || cl.uuid() == null ? "" : cl.uuid()) + "|";
    }

    /** V48: valor vacío de GeneXus para un tipo de MySQL (null = no se completa: fechas, binarios...). */
    static Object vacioSql(String tipo) {
        String t = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
        if (t.matches("tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|float|double|real|bit|year")) return 0;
        if (t.matches("char|varchar|tinytext|text|mediumtext|longtext|enum|set")) return "";
        if (t.equals("date")) return java.time.LocalDate.of(1000, 1, 1);          // fecha vacía de GeneXus en MySQL
        if (t.equals("datetime") || t.equals("timestamp")) return java.time.LocalDateTime.of(1000, 1, 1, 0, 0);
        // V61f: imágenes y archivos de GeneXus (ej. ArticulosFoto): vacío = sin bytes
        if (t.matches("tinyblob|blob|mediumblob|longblob|binary|varbinary")) return new byte[0];
        return null;
    }

    static Map<String, TablaBD> esquema(Connection c) throws SQLException {
        String cat = claveBase(c);   // V71b: cliente + base (instalación única: dos clientes nunca comparten la estructura)
        Map<String, TablaBD> r = ESQUEMAS.get(cat);
        if (r != null) return r;
        Map<String, Map<String, String>> cols = new HashMap<>();
        Map<String, Set<String>> autos = new HashMap<>();
        Map<String, String> nombres = new HashMap<>();
        Map<String, Map<String, Object>> vacios = new HashMap<>();
        Map<String, Map<String, String>> tipos = new HashMap<>();
        Map<String, Set<String>> nulables = new HashMap<>();
        Map<String, Map<String, Integer>> largos = new HashMap<>();
        // V66d: solo las tablas (no las vistas). Una vista de la base (las de OPTIMIST SQL, con los renglones y el artículo juntos)
        // tiene más columnas de las que usa un For Each que la tabla verdadera y ganaba como tabla base: sin clave, el For Each
        // anidado no se filtraba por el de afuera y leía toda la vista (el remito de De las Misiones salía con 115 hojas).
        try (PreparedStatement p = c.prepareStatement("SELECT c.TABLE_NAME, c.COLUMN_NAME, c.EXTRA, c.DATA_TYPE, c.IS_NULLABLE, c.COLUMN_DEFAULT, c.CHARACTER_MAXIMUM_LENGTH "
                + "FROM information_schema.COLUMNS c JOIN information_schema.TABLES t ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME "
                + "WHERE c.TABLE_SCHEMA = DATABASE() AND t.TABLE_TYPE = 'BASE TABLE'");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) {
                String t = rs.getString(1), col = rs.getString(2), extra = rs.getString(3);
                String tl = t.toLowerCase(Locale.ROOT);
                nombres.put(tl, t);
                cols.computeIfAbsent(tl, k -> new LinkedHashMap<>()).put(col.toLowerCase(Locale.ROOT), col);
                boolean auto = extra != null && extra.toLowerCase(Locale.ROOT).contains("auto_increment");
                if (auto) autos.computeIfAbsent(tl, k -> new HashSet<>()).add(col.toLowerCase(Locale.ROOT));
                // V48: como GeneXus en un New: lo que no se asigna va con el valor vacío de su tipo (0, "")
                Object v = auto || !"NO".equalsIgnoreCase(rs.getString(5)) || rs.getString(6) != null ? null : vacioSql(rs.getString(4));
                if (v != null) vacios.computeIfAbsent(tl, k -> new HashMap<>()).put(col.toLowerCase(Locale.ROOT), v);
                tipos.computeIfAbsent(tl, k -> new HashMap<>()).put(col.toLowerCase(Locale.ROOT), rs.getString(4));
                // V51a: largo de las CHAR / VARCHAR (GeneXus recorta el texto al largo del atributo al asignarlo)
                String dt = rs.getString(4) == null ? "" : rs.getString(4).toLowerCase(Locale.ROOT);
                long largo = rs.getLong(7);
                if ((dt.equals("char") || dt.equals("varchar")) && largo > 0 && largo < Integer.MAX_VALUE)
                    largos.computeIfAbsent(tl, k -> new HashMap<>()).put(col.toLowerCase(Locale.ROOT), (int) largo);
                if (!"NO".equalsIgnoreCase(rs.getString(5))) nulables.computeIfAbsent(tl, k -> new HashSet<>()).add(col.toLowerCase(Locale.ROOT));
            }
        }
        Map<String, List<String>> pks = new HashMap<>();
        try (PreparedStatement p = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND INDEX_NAME = 'PRIMARY' ORDER BY TABLE_NAME, SEQ_IN_INDEX");
             ResultSet rs = p.executeQuery()) {
            while (rs.next()) pks.computeIfAbsent(rs.getString(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(rs.getString(2).toLowerCase(Locale.ROOT));
        }
        // V48c: GeneXus recorta los nombres de columna a 40 caracteres: el atributo con su nombre completo (de las TRN) también
        // encuentra su columna (TransportesTarifasPorImportePorPorcentaje -> TransportesTarifasPorImportePorPorcentaj)
        try {
            for (OptimistTrnDefinicion.Trn t : trns.get()) {
                List<OptimistTrnDefinicion.Nivel> ns = new ArrayList<>(t.detalles()); ns.add(0, t.cabecera());
                for (OptimistTrnDefinicion.Nivel n : ns)
                    for (OptimistTrnDefinicion.Campo cp : n.campos()) {
                        if (cp.nombre().length() <= 40) continue;
                        Map<String, String> m = cols.get(n.tabla().toLowerCase(Locale.ROOT));
                        String corto = cp.nombre().substring(0, 40).toLowerCase(Locale.ROOT);
                        if (m != null && m.containsKey(corto)) m.putIfAbsent(cp.nombre().toLowerCase(Locale.ROOT), m.get(corto));
                    }
            }
        } catch (RuntimeException ignorado) { }
        r = new HashMap<>();
        for (var e : cols.entrySet())
            r.put(e.getKey(), new TablaBD(nombres.get(e.getKey()), e.getValue(), pks.getOrDefault(e.getKey(), List.of()), autos.getOrDefault(e.getKey(), Set.of()),
                    vacios.getOrDefault(e.getKey(), Map.of()), tipos.getOrDefault(e.getKey(), Map.of()), nulables.getOrDefault(e.getKey(), Set.of()),
                    largos.getOrDefault(e.getKey(), Map.of())));
        ESQUEMAS.put(cat, r);
        return r;
    }

    /** Tabla base + tablas unidas por clave (tabla extendida de GeneXus). */
    /** extendidos (V48): atributo (subtipo, minúscula) -> "tN.`Columna`" de la tabla extendida (ej. prsentidadesrazonsocial -> t1.`EntidadesRazonSocial`). */
    record Plan(List<TablaBD> tablas, List<String> uniones, Map<String, String> extendidos) {
        Plan(List<TablaBD> tablas, List<String> uniones) { this(tablas, uniones, Map.of()); }
        /** alias.columna de un atributo (primero la base). */
        String columna(String atributo) {
            String k = atributo.toLowerCase(Locale.ROOT);
            for (int i = 0; i < tablas.size(); i++) if (tablas.get(i).columnas().containsKey(k)) return "t" + i + ".`" + tablas.get(i).columnas().get(k) + "`";
            return extendidos.get(k);
        }
    }

    /**
     * V48: referencias de las TRN cuya tabla es esa (para la tabla extendida con SUBTIPOS: en PRS, PRSEntidadesPK apunta a
     * Entidades.EntidadesPK y PRSEntidadesRazonSocial es Entidades.EntidadesRazonSocial). La carga OptimistProcedimientos
     * desde el catálogo de TRN; sin catálogo (pruebas), ninguna.
     */
    static java.util.function.Function<String, List<OptimistTrnDefinicion.Referencia>> referenciasDeTabla = t -> List.of();

    /**
     * V48: grupos de subtipos de GeneXus (subtipos.yaml, generado desde la KB): grupo -> {subtipo: supertipo}, en minúscula.
     * Ej. STCOBR_Tcbte: COBRCPI -> TcbteCPI, COBRCOBRPAGOImputacion -> TcbteCOBRPAGOModoUso. Los carga OptimistProcedimientos.
     */
    static volatile Map<String, Map<String, String>> gruposSubtipos = Map.of();
    /** subtipo (minúscula) -> grupos donde está. */
    static volatile Map<String, List<String>> gruposDe = Map.of();

    public static void subtipos(Map<String, Map<String, String>> grupos) {
        Map<String, Map<String, String>> g = new HashMap<>();
        Map<String, List<String>> idx = new HashMap<>();
        grupos.forEach((n, m) -> {
            Map<String, String> mm = new HashMap<>();
            m.forEach((sub, sup) -> { mm.put(sub.toLowerCase(Locale.ROOT), sup.toLowerCase(Locale.ROOT)); idx.computeIfAbsent(sub.toLowerCase(Locale.ROOT), x -> new ArrayList<>()).add(n); });
            g.put(n, mm);
        });
        gruposSubtipos = g;
        gruposDe = idx;
    }

    /**
     * V48: tabla extendida por GRUPO DE SUBTIPOS (como GeneXus): 'a' es subtipo de S en el grupo G; la tabla U que tiene la
     * columna S se une con una de las tablas ya unidas que tiene, para cada columna de la clave de U, el subtipo de G que
     * apunta a esa columna (o la misma columna). Devuelve "tN.`S`" o null.
     */
    private static String porSubtipo(Map<String, TablaBD> esq, List<TablaBD> ts, List<String> uniones,
                                      Map<String, Integer> unidas, String a) {
        for (String g : gruposDe.getOrDefault(a, List.of())) {
            Map<String, String> miembros = gruposSubtipos.get(g);
            String sup = miembros.get(a);
            if (sup == null) continue;
            for (TablaBD u : esq.values()) {
                if (!u.columnas().containsKey(sup) || u.clave().isEmpty()) continue;
                for (int j = 0; j < ts.size(); j++) {
                    TablaBD desde = ts.get(j);
                    if (desde == u) continue;
                    List<String> on = new ArrayList<>();
                    int n = ts.size();
                    String clave = j + "|" + g + "|" + u.nombre();
                    Integer ya = unidas.get(clave);
                    if (ya != null) return "t" + ya + ".`" + u.columnas().get(sup) + "`";
                    for (String k : u.clave()) {
                        String local = null;
                        for (Map.Entry<String, String> m : miembros.entrySet())
                            if (m.getValue().equals(k) && desde.columnas().containsKey(m.getKey())) { local = m.getKey(); break; }
                        if (local == null && desde.columnas().containsKey(k)) local = k;
                        if (local == null) { on = null; break; }
                        on.add("t" + n + ".`" + u.columnas().get(k) + "` = t" + j + ".`" + desde.columnas().get(local) + "`");
                    }
                    if (on == null) continue;
                    ts.add(u);
                    uniones.add(" LEFT JOIN `" + u.nombre() + "` t" + n + " ON " + String.join(" AND ", on));
                    unidas.put(clave, n);
                    return "t" + n + ".`" + u.columnas().get(sup) + "`";
                }
            }
        }
        return null;
    }

    /**
     * V48: busca el atributo 'a' en el 'trae' de las referencias de las tablas ya unidas; si las columnas locales de la
     * referencia están en esa tabla, agrega el LEFT JOIN (una vez por referencia) y devuelve "tN.`ColumnaRemota`".
     */
    private static String porReferencia(Map<String, TablaBD> esq, List<TablaBD> ts, List<String> uniones,
                                        Map<String, Integer> unidasPorRef, String a) {
        for (int j = 0; j < ts.size(); j++) {
            TablaBD desde = ts.get(j);
            for (OptimistTrnDefinicion.Referencia r : referenciasDeTabla.apply(desde.nombre())) {
                String remota = null;
                for (Map.Entry<String, String> e : r.trae().entrySet()) if (e.getKey().equalsIgnoreCase(a)) remota = e.getValue();
                // V48c: como la tabla extendida de GeneXus, por la referencia también se llega a cualquier atributo de la tabla
                // referenciada con su propio nombre (MovStock -> Tcbte por los subtipos MovStockCPI...: TcbteTxAlmacenes)
                if (remota == null) {
                    TablaBD u0 = esq.get(r.tabla().toLowerCase(Locale.ROOT));
                    if (u0 != null && u0.columnas().containsKey(a.toLowerCase(Locale.ROOT))) remota = a;
                }
                if (remota == null) continue;
                TablaBD u = esq.get(r.tabla().toLowerCase(Locale.ROOT));
                if (u == null || !u.columnas().containsKey(remota.toLowerCase(Locale.ROOT))) continue;
                boolean locales = true;
                for (String l : r.campos().keySet()) if (!desde.columnas().containsKey(l.toLowerCase(Locale.ROOT))) locales = false;
                if (!locales) continue;
                String clave = j + "|" + r.tabla() + "|" + r.campos();
                Integer n = unidasPorRef.get(clave);
                if (n == null) {
                    n = ts.size();
                    List<String> on = new ArrayList<>();
                    for (Map.Entry<String, String> k : r.campos().entrySet()) {
                        String rc = u.columnas().get(k.getValue().toLowerCase(Locale.ROOT));
                        if (rc == null) { on = null; break; }
                        on.add("t" + n + ".`" + rc + "` = t" + j + ".`" + desde.columnas().get(k.getKey().toLowerCase(Locale.ROOT)) + "`");
                    }
                    if (on == null) continue;
                    ts.add(u);
                    uniones.add(" LEFT JOIN `" + u.nombre() + "` t" + n + " ON " + String.join(" AND ", on));
                    unidasPorRef.put(clave, n);
                }
                return "t" + n + ".`" + u.columnas().get(remota.toLowerCase(Locale.ROOT)) + "`";
            }
        }
        return null;
    }

    /** V66d: las tablas de las TRN (cabecera y niveles), en minúscula; se arma una vez. */
    private static volatile Set<String> TABLAS_TRN;
    static Set<String> tablasDeTrn() {
        Set<String> r = TABLAS_TRN;
        if (r != null) return r;
        r = new HashSet<>();
        try {
            for (OptimistTrnDefinicion.Trn t : trns.get()) {
                r.add(t.cabecera().tabla().toLowerCase(Locale.ROOT));
                for (OptimistTrnDefinicion.Nivel n : t.detalles()) r.add(n.tabla().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException sinCatalogo) { return Set.of(); }
        if (!r.isEmpty()) TABLAS_TRN = r;
        return r;
    }

    static Plan planificar(Map<String, TablaBD> esq, String explicita, Set<String> atributos, Set<String> asignados, String proc) {
        Plan p = planificar0(esq, explicita, atributos, asignados, proc, false);
        // V48c: como GeneXus, lo que se asigna puede ser de una tabla de la extendida (en un For Each de PEDDet se actualizan
        // atributos de PED): primero se busca una base que tenga todo; si no hay, se acepta la extendida
        if (p == null && !asignados.isEmpty()) p = planificar0(esq, explicita, atributos, asignados, proc, true);
        if (p == null) throw new IllegalStateException(proc + ": For Each: no encuentro una tabla con " + atributos
                + (asignados.isEmpty() ? "" : " donde se puedan actualizar " + asignados));
        return p;
    }

    /** V48c: escrito -> índice de la tabla del plan donde se actualiza (0 = la base). */
    static Map<String, Integer> dondeSeEscribe(Plan plan, Set<String> asignados) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (String a : asignados) {
            String k = a.toLowerCase(Locale.ROOT);
            for (int i = 0; i < plan.tablas().size(); i++) if (plan.tablas().get(i).columnas().containsKey(k)) { r.put(k, i); break; }
        }
        return r;
    }

    private static Plan planificar0(Map<String, TablaBD> esq, String explicita, Set<String> atributos, Set<String> asignados, String proc,
                                    boolean extendida) {
        Set<String> atts = new LinkedHashSet<>();
        for (String a : atributos) atts.add(a.toLowerCase(Locale.ROOT));
        Set<String> escritos = new HashSet<>();
        for (String a : asignados) escritos.add(a.toLowerCase(Locale.ROOT));
        List<TablaBD> candidatas = new ArrayList<>();
        if (explicita != null) {
            TablaBD t = esq.get(explicita.toLowerCase(Locale.ROOT));
            if (t == null) throw new IllegalStateException(proc + ": For Each " + explicita + ": no existe esa tabla");
            candidatas.add(t);
        } else {
            for (TablaBD t : esq.values()) for (String a : atts) if (t.columnas().containsKey(a)) { candidatas.add(t); break; }
        }
        Plan mejor = null; int mejorDirectos = -1, mejorTam = Integer.MAX_VALUE, mejorClaveEn = -1, mejorLargoClave = Integer.MAX_VALUE, mejorEnKB = -1, mejorPrefijo = -1;
        for (TablaBD base : candidatas) {
            // lo que se actualiza tiene que ser de la tabla base (V48c: o, en la segunda pasada, de una tabla unida)
            if (!extendida && !base.columnas().keySet().containsAll(escritos)) continue;
            List<TablaBD> ts = new ArrayList<>(List.of(base));
            List<String> uniones = new ArrayList<>();
            Set<String> cubiertos = new HashSet<>(base.columnas().keySet());
            Map<String, String> extendidos = new HashMap<>();
            Map<String, Integer> unidasPorRef = new HashMap<>();
            boolean cambio = true;
            while (cambio) {
                cambio = false;
                for (String a : atts) {
                    if (cubiertos.contains(a)) continue;
                    // V48: subtipo que trae una referencia de la TRN de alguna de las tablas ya unidas (tabla extendida de GeneXus)
                    String col = porReferencia(esq, ts, uniones, unidasPorRef, a);
                    if (col == null) col = porSubtipo(esq, ts, uniones, unidasPorRef, a);   // V48: grupos de subtipos de la KB
                    if (col != null) { extendidos.put(a, col); cubiertos.add(a); cambio = true; continue; }
                    for (TablaBD u : esq.values()) {
                        if (ts.contains(u) || !u.columnas().containsKey(a) || u.clave().isEmpty()) continue;
                        if (!cubiertos.containsAll(u.clave())) continue;
                        int n = ts.size();
                        List<String> on = new ArrayList<>();
                        for (String k : u.clave()) {
                            String desde = null;
                            for (int j = 0; j < ts.size() && desde == null; j++)
                                if (ts.get(j).columnas().containsKey(k)) desde = "t" + j + ".`" + ts.get(j).columnas().get(k) + "`";
                            on.add("t" + n + ".`" + u.columnas().get(k) + "` = " + desde);
                        }
                        ts.add(u);
                        uniones.add(" LEFT JOIN `" + u.nombre() + "` t" + n + " ON " + String.join(" AND ", on));
                        cubiertos.addAll(u.columnas().keySet());
                        cambio = true;
                        break;
                    }
                }
                // V48: dos saltos, como la tabla extendida de GeneXus: en un For Each de PRSDet, PRSCatIvaDesglosa no está en
                // PRS pero sí en CatIva, a la que se llega desde PRS (el nivel padre: su clave es parte de la de PRSDet) por el
                // subtipo PRSCatIvaId. Se une el padre solo si desde él se resuelve algún atributo que falta.
                if (!cambio) {
                    for (String a : atts) {
                        if (cubiertos.contains(a)) continue;
                        for (TablaBD u : esq.values()) {
                            if (ts.contains(u) || u.clave().isEmpty() || !base.clave().containsAll(u.clave()) || u.clave().size() >= base.clave().size()) continue;
                            List<TablaBD> ts2 = new ArrayList<>(ts);
                            List<String> uniones2 = new ArrayList<>(uniones);
                            Map<String, Integer> unidas2 = new HashMap<>(unidasPorRef);
                            int n = ts2.size();
                            List<String> on = new ArrayList<>();
                            for (String k : u.clave()) on.add("t" + n + ".`" + u.columnas().get(k) + "` = t0.`" + base.columnas().get(k) + "`");
                            ts2.add(u);
                            uniones2.add(" LEFT JOIN `" + u.nombre() + "` t" + n + " ON " + String.join(" AND ", on));
                            String col = porReferencia(esq, ts2, uniones2, unidas2, a);
                            if (col == null) col = porSubtipo(esq, ts2, uniones2, unidas2, a);
                            if (col == null) continue;
                            ts.clear(); ts.addAll(ts2); uniones.clear(); uniones.addAll(uniones2); unidasPorRef.clear(); unidasPorRef.putAll(unidas2);
                            for (String c : u.columnas().keySet()) if (!cubiertos.contains(c)) { extendidos.put(c, "t" + n + ".`" + u.columnas().get(c) + "`"); cubiertos.add(c); }
                            extendidos.put(a, col); cubiertos.add(a);
                            cambio = true;
                            break;
                        }
                        if (cambio) break;
                    }
                }
            }
            if (!cubiertos.containsAll(atts)) continue;
            if (extendida) {   // cada escrito, en alguna tabla unida (con su nombre de columna, no por subtipo)
                boolean todos = true;
                for (String a : escritos) if (ts.stream().noneMatch(t -> t.columnas().containsKey(a))) { todos = false; break; }
                if (!todos) continue;
            }
            int directos = 0;
            for (String a : atts) if (base.columnas().containsKey(a)) directos++;
            // V61e: en un empate gana, como en GeneXus, la tabla cuya clave está entera en los atributos del For Each y,
            // entre esas, la de clave más corta (la más general): "where ArticulosPK = &X" es Articulos, no cualquier
            // tabla que tenga ArticulosPK (ArticulosArchivos, ArtiEnti...), que daba "no existe" si no tenía renglones.
            int claveEn = !base.clave().isEmpty() && atts.containsAll(base.clave()) ? 1 : 0;
            // V66d: en un empate gana la tabla de una TRN (la de la KB) sobre otra de la base con las mismas columnas (una copia
            // de resguardo, CPBCDet_bak)
            int enKB = tablasDeTrn().contains(base.nombre().toLowerCase(Locale.ROOT)) ? 1 : 0;
            int largoClave = base.clave().isEmpty() ? Integer.MAX_VALUE : base.clave().size();
            // V66l: en un empate gana la tabla de la que son los atributos por su nombre (como en GeneXus, donde EntidadesId y
            // EntidadesRazonSocial son de Entidades): "where EntidadesId = &X" leía Carpetas o Contactos, que repiten esas columnas
            String bn = base.nombre().toLowerCase(Locale.ROOT);
            int prefijo = 0;
            for (String a : atts) if (a.startsWith(bn)) prefijo++;
            boolean gana = directos > mejorDirectos
                    || (directos == mejorDirectos && enKB > mejorEnKB)
                    || (directos == mejorDirectos && enKB == mejorEnKB && claveEn > mejorClaveEn)
                    || (directos == mejorDirectos && enKB == mejorEnKB && claveEn == mejorClaveEn && ts.size() < mejorTam)
                    || (directos == mejorDirectos && enKB == mejorEnKB && claveEn == mejorClaveEn && ts.size() == mejorTam && prefijo > mejorPrefijo)
                    || (directos == mejorDirectos && enKB == mejorEnKB && claveEn == mejorClaveEn && ts.size() == mejorTam && prefijo == mejorPrefijo && largoClave < mejorLargoClave);
            if (gana) {
                mejor = new Plan(ts, uniones, Map.copyOf(extendidos)); mejorDirectos = directos; mejorTam = ts.size();
                mejorClaveEn = claveEn; mejorLargoClave = largoClave; mejorEnKB = enKB; mejorPrefijo = prefijo;
            }
        }
        return mejor;
    }

    /** V49w: "Atributo in (a, b, c)". */
    private static final Pattern EN_LISTA = Pattern.compile("(?is)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s*\\((.*)\\)\\s*$");
    private static final Map<String, Object> FORMULAS_WHERE = new java.util.concurrent.ConcurrentHashMap<>();

    /** V49w: la fórmula de un pedazo de where (se arma una vez), o null si no se puede. */
    static OptimistFormula formulaWhere(String t) {
        Object r = FORMULAS_WHERE.computeIfAbsent(t, x -> { try { return OptimistFormula.de(x); } catch (RuntimeException ex) { return Boolean.FALSE; } });
        return r instanceof OptimistFormula f ? f : null;
    }

    private static final Pattern FILTRO = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(=|<>|<=|>=|<|>)\\s*(.+?)\\s*$", Pattern.DOTALL);

    /** V48: un valor nulo en una columna NOT NULL se graba con el vacío de GeneXus (0, "", 1000-01-01). */
    static Object conVacio(TablaBD t, String att, Object v) {
        String tipo = t.tipos().get(att);
        boolean texto = tipo != null && vacioSql(tipo) instanceof String;
        // SetEmpty() / "" en una fecha o un número: es el vacío de GeneXus, no un texto
        if (v == null || (!texto && tipo != null && v instanceof String s && s.isBlank())) {
            // V61b: SetEmpty() de una fecha deja la fecha vacía de GeneXus (1000-01-01) aunque la columna admita nulo
            // (SetNull() sigue dejando NULL). Números y textos, como antes (una clave foránea vacía tiene que quedar NULL).
            String tl = tipo == null ? "" : tipo.toLowerCase(Locale.ROOT);
            if (v != null && (tl.equals("date") || tl.equals("datetime") || tl.equals("timestamp"))) return vacioSql(tipo);
            return t.nulables().contains(att) ? null : t.vacios().containsKey(att) ? t.vacios().get(att) : vacioSql(tipo);
        }
        // V51a: como GeneXus, un texto más largo que la columna se recorta (antes: "Data too long for column ...")
        Integer largo = t.largos().get(att);
        if (largo != null && v instanceof String s && s.length() > largo) return s.substring(0, largo);
        return v;
    }

    /** Registro de un For Each: sus valores + lo que se le cambia (se graba al terminar cada vuelta). */
    /**
     * V49w: las filas que trajo un For Each de solo lectura. Un For Each anidado que se filtra por la clave de esta tabla
     * (los renglones de cada pedido) se lee UNA vez para todas las filas (WHERE PEDId IN (...)) y se reparte de memoria:
     * antes era una consulta por fila de afuera (GetPendienteArticuloTerminadoCantidadPED: cientos por artículo).
     */
    static final class Lote {
        final List<FilaBD> filas; final boolean lectura;
        final Map<String, Map<String, List<Object[]>>> leido = new HashMap<>();
        Lote(List<FilaBD> filas, boolean lectura) { this.filas = filas; this.lectura = lectura; }
    }

    /** V49w: clave de comparación de un valor (10 = 10.00 = 10L). */
    static String claveLote(Object v) {
        if (v == null) return "\u0000";
        if (v instanceof Number) try { return OptimistFormula.num(v).stripTrailingZeros().toPlainString(); } catch (RuntimeException ex) { return String.valueOf(v); }
        return String.valueOf(v).strip();
    }

    static final class FilaBD extends HashMap<String, Object> {
        final TablaBD tabla; final Map<String, Object> clave = new LinkedHashMap<>();
        final Map<String, Object> cambios = new LinkedHashMap<>();
        boolean borrar;
        boolean bloqueada;   // V48c: este registro está bloqueado (FOR UPDATE) hasta el Commit / Rollback
        final List<FilaBD> extendidas = new ArrayList<>();   // V48c: registros de la extendida que se actualizan (PED desde PEDDet)
        Set<String> sinColumna = Set.of();                   // V48c: atributos que la base no tiene (se asignan solo en memoria)
        Lote lote;                                           // V49w: las filas del mismo For Each (para leer en lote los anidados)
        FilaBD(TablaBD tabla) { this.tabla = tabla; }

        void grabar(Connection c) throws SQLException {
            if (clave.isEmpty() && (borrar || !cambios.isEmpty()))
                throw new IllegalStateException("la tabla " + tabla.nombre() + " no tiene clave primaria: no se puede actualizar");
            List<Object> vs = new ArrayList<>();
            String sql;
            if (borrar) sql = "DELETE FROM `" + tabla.nombre() + "`";
            else if (!cambios.isEmpty()) {
                List<String> set = new ArrayList<>();
                for (var e : cambios.entrySet()) { set.add("`" + tabla.columnas().get(e.getKey()) + "` = ?"); vs.add(conVacio(tabla, e.getKey(), sqlValor(e.getValue()))); }
                sql = "UPDATE `" + tabla.nombre() + "` SET " + String.join(", ", set);
            } else return;
            List<String> w = new ArrayList<>();
            for (var e : clave.entrySet()) { w.add("`" + tabla.columnas().get(e.getKey()) + "` = ?"); vs.add(sqlValor(e.getValue())); }
            try (PreparedStatement p = c.prepareStatement(sql + " WHERE " + String.join(" AND ", w))) {
                for (int k = 0; k < vs.size(); k++) p.setObject(k + 1, vs.get(k));
                p.executeUpdate();
            }
            OptimistLecturas.escribio();
            cambios.clear();
        }

        /** V48c: graba esta fila y los registros de la extendida que cambiaron. */
        void grabarTodo(Connection c) throws SQLException {
            grabar(c);
            for (FilaBD x : extendidas) x.grabar(c);
        }
    }

    /** Registro que se está armando en un New. */
    static final class FilaNueva extends LinkedHashMap<String, Object> {}
    /** Atributos recibidos en el Parm: filtran los For Each (como en GeneXus). */
    static final class FilaParm extends HashMap<String, Object> {}
    /** V66x7: marca en la pila de filas el comienzo de una Sub (corta el anidamiento de los For Each). */
    static final class FronteraSub extends HashMap<String, Object> {}

    private static final class ParaCada implements Sentencia {
        final String proc, tabla; final int renglon;
        final List<OptimistFormula> wheres; final List<String> textos; final List<String> orden;
        final List<Sentencia> cuerpo, ninguno;
        final Set<String> usados;
        final Map<List<Object>, Object[]> planes = new java.util.concurrent.ConcurrentHashMap<>();   // V48c: plan ya armado
        boolean soloUltima;   // V48: el cuerpo solo copia atributos a &variables
        boolean soloPrimera;  // V48: el cuerpo termina en Exit
        ParaCada(String proc, int renglon, String tabla, List<OptimistFormula> wheres, List<String> textos, List<String> orden,
                 List<Sentencia> cuerpo, List<Sentencia> ninguno, Set<String> usados) {
            this.proc = proc; this.renglon = renglon; this.tabla = tabla; this.wheres = wheres; this.textos = textos;
            this.orden = orden; this.cuerpo = cuerpo; this.ninguno = ninguno; this.usados = usados;
        }

        @SuppressWarnings("unchecked")
        public void ejecutar(Ejecucion e) throws SQLException {
            Set<String> atributos = new LinkedHashSet<>();
            Set<String> us = new LinkedHashSet<>(usados);
            boolean borra = us.remove("!delete");
            expandir(us, e.proc, atributos, new HashSet<>());
            Set<String> asignados = new LinkedHashSet<>();
            for (String a : atributos) if (a.startsWith("=")) asignados.add(a.substring(1));
            atributos.removeIf(a -> a.startsWith("="));
            atributos.addAll(asignados);
            // V51e: "?X" = leído después de un New (puede ser del registro nuevo: &MovStockId = MovStockId): es de este For Each
            // solo si la tabla lo tiene; si no, se lee del último New
            Set<String> opcionales = new LinkedHashSet<>();
            for (String a : atributos) if (a.startsWith("?")) opcionales.add(a.substring(1));
            atributos.removeIf(a -> a.startsWith("?"));
            Set<String> yaEstan = atributos;
            opcionales.removeIf(a -> contiene(yaEstan, a));
            Map<String, TablaBD> esq = esquema(e.conexion);
            // V48c: el plan (tabla base, uniones, fórmulas, atributos sin columna) depende solo de la estructura de la base y de
            // qué atributos trae un For Each de afuera: se arma una vez y se reusa. Antes se rearmaba en cada ejecución y era
            // casi todo el tiempo de un procedimiento (GetStockArticuloAlmacen...: 170 ms sin contar la base).
            List<String> externos = new ArrayList<>();
            for (String a : atributos) if (e.enFilaAnidada(a)) externos.add(a.toLowerCase(Locale.ROOT));
            List<Object> claveCache = List.of(System.identityHashCode(esq), externos);
            Object[] enCache = planes.get(claveCache);
            Map<String, Object[]> formulas;
            Set<String> sinColumna;
            Plan plan;
            if (enCache != null && enCache[0] == esq) {
                atributos = new LinkedHashSet<>((Set<String>) enCache[1]);
                asignados = new LinkedHashSet<>((Set<String>) enCache[2]);
                formulas = (Map<String, Object[]>) enCache[3];
                sinColumna = (Set<String>) enCache[4];
                plan = (Plan) enCache[5];
            } else {
                // V48c: atributos fórmula de GeneXus (no son columnas: PEDRentabilidad, PEDDetCantidadStk, AsientosTotalDebe...):
                // no van a la consulta; se trae la clave de su TRN y se calculan por fila con las fórmulas de la TRN
                Map<String, Object[]> fs = new LinkedHashMap<>();
                for (String a : atributos) {
                    String k = a.toLowerCase(Locale.ROOT);
                    if (e.enFilaAnidada(a) || esq.values().stream().anyMatch(t -> t.columnas().containsKey(k))) continue;
                    Object[] fo = formulaDe(a);
                    if (fo != null) fs.put(k, fo);
                }
                formulas = fs;
                if (!fs.isEmpty()) {
                    atributos.removeIf(x -> fs.containsKey(x.toLowerCase(Locale.ROOT)));
                    for (Object[] fo : fs.values()) {
                        OptimistTrnDefinicion.Trn t = (OptimistTrnDefinicion.Trn) fo[0];
                        OptimistTrnDefinicion.Nivel nv = (OptimistTrnDefinicion.Nivel) fo[1];
                        for (OptimistTrnDefinicion.Campo k : t.cabecera().claves()) if (!contiene(atributos, k.nombre())) atributos.add(k.nombre());
                        if (nv != null) for (OptimistTrnDefinicion.Campo k : nv.claves()) if (!contiene(atributos, k.nombre())) atributos.add(k.nombre());
                    }
                }
                // los atributos que ya trae un For Each de afuera no obligan a esta tabla (V66x7: de afuera como está escrito; en
                // una Sub, todos sus atributos son de su For Each, como en GeneXus)
                Set<String> propios = new LinkedHashSet<>();
                // V49b: salvo los del where, que como en GeneXus son de este For Each (en una Sub llamada desde un For Each de
                // PRSDet, "where ArticulosPK = &ArticulosPK" navega Articulos aunque el de afuera ya traiga ArticulosPK)
                Set<String> delWhere = new HashSet<>();
                for (OptimistFormula w : wheres) for (String a : w.atributos()) if (!a.startsWith("&") && !a.contains(".")) delWhere.add(a.toLowerCase(Locale.ROOT));
                for (String a : atributos) if (!e.enFilaAnidada(a) || asignados.contains(a) || delWhere.contains(a.toLowerCase(Locale.ROOT))) propios.add(a);
                if (propios.isEmpty()) propios.addAll(atributos);
                Plan plan0 = null;
                sinColumna = new LinkedHashSet<>();
                if (!opcionales.isEmpty()) {
                    Set<String> conOpc = new LinkedHashSet<>(propios);
                    for (String a : opcionales) if (!e.enFilaAnidada(a)) conOpc.add(a);
                    try { plan0 = planificar(esq, tablaDe(esq, tabla), conOpc, asignados, proc + ", renglón " + renglon); atributos.addAll(conOpc); }
                    catch (IllegalStateException sinOpcionales) { plan0 = null; }
                }
                if (plan0 == null)
                try { plan0 = planificar(esq, tablaDe(esq, tabla), propios, asignados, proc + ", renglón " + renglon); }
                catch (IllegalStateException sinPlan) {
                    // V48c: atributos que no son columnas de la base, ni llegan por una referencia, ni son fórmulas de un YAML (ej.
                    // PEDNetoGravado01 en TotalizarIva: fórmula de PED que gx2yaml no pasó porque la pantalla no la usa). Se dejan de lado
                    // (lo que se les asigna no se graba; si se leen, valen lo último asignado o vacío) y se avisa en la consola.
                    for (String a : propios) if (!existeEnLaBase(esq, a)) sinColumna.add(a);
                    if (sinColumna.isEmpty()) throw sinPlan;
                    Set<String> resto = new LinkedHashSet<>(propios); resto.removeAll(sinColumna);
                    Set<String> asig = new LinkedHashSet<>(asignados); asig.removeAll(sinColumna);
                    if (resto.isEmpty()) throw sinPlan;
                    plan0 = planificar(esq, tablaDe(esq, tabla), resto, asig, proc + ", renglón " + renglon);
                    atributos.removeAll(sinColumna);
                    asignados.removeAll(sinColumna);
                    if (AVISADOS.add(proc + ":" + renglon))
                        System.out.println("[" + proc + ", renglón " + renglon + "] " + sinColumna
                                + " no son columnas de la base ni fórmulas de una TRN (¿fórmula que falta en el YAML?): se ignoran");
                }
                plan = plan0;
                planes.put(claveCache, new Object[]{esq, new LinkedHashSet<>(atributos), new LinkedHashSet<>(asignados), formulas, sinColumna, plan});
            }
            TablaBD base = plan.tablas().get(0);
            // columnas que se leen (+ la clave de la tabla base, para poder actualizar)
            List<String> select = new ArrayList<>(), nombres = new ArrayList<>();
            for (String a : atributos) { String col = plan.columna(a); if (col != null) { select.add(col); nombres.add(a.toLowerCase(Locale.ROOT)); } }
            for (String k : base.clave()) if (!nombres.contains(k)) { select.add("t0.`" + base.columnas().get(k) + "`"); nombres.add(k); }
            // V48c: tablas de la extendida donde se asigna algo (PED desde un For Each de PEDDet): se trae su clave
            Set<Integer> extUsadas = new TreeSet<>(dondeSeEscribe(plan, asignados).values());
            extUsadas.remove(0);
            for (int j : extUsadas)
                for (String k : plan.tablas().get(j).clave()) { select.add("t" + j + ".`" + plan.tablas().get(j).columnas().get(k) + "`"); nombres.add(EXT + j + ":" + k); }
            // where: "Atributo op expresión-sin-atributos" va a SQL; lo demás se filtra acá
            List<String> sql = new ArrayList<>();
            List<Object> valores = new ArrayList<>();
            List<OptimistFormula> enJava = new ArrayList<>();
            for (int k = 0; k < wheres.size(); k++) {
                OptimistFormula f = wheres.get(k);
                String tx = textos.get(k);
                // V49w: "where X when C": si C solo usa variables, se decide acá; con C falsa la condición no va, con C verdadera va X
                // (y X puede ir a SQL). Antes se filtraba todo en Java: se traían todos los registros.
                if (tx.startsWith("\u0001")) {
                    String[] pz = tx.substring(1).split("\u0001", 2);
                    OptimistFormula cf = formulaWhere(pz[0]), xf = formulaWhere(pz[1]);
                    if (cf != null && xf != null && cf.atributos().stream().allMatch(x -> x.startsWith("&") || x.contains(".") || e.enFilaExterna(x))) {
                        if (!OptimistFormula.verdad(e.evaluar(cf))) continue;
                        tx = pz[1]; f = xf;
                    } else { enJava.add(f); continue; }
                }
                // V49w: "Atributo in (constantes o &variables)" va a SQL
                Matcher en = EN_LISTA.matcher(tx);
                if (en.matches() && plan.columna(en.group(1)) != null) {
                    List<Object> vs = new ArrayList<>();
                    boolean ok = true;
                    for (String it : partir(en.group(2), ',')) {
                        OptimistFormula fi = formulaWhere(it.trim());
                        if (fi == null || !fi.atributos().stream().allMatch(x -> x.startsWith("&") || x.contains(".") || e.enFilaExterna(x))) { ok = false; break; }
                        vs.add(sqlValor(e.evaluar(fi)));
                    }
                    if (ok && !vs.isEmpty()) {
                        sql.add(plan.columna(en.group(1)) + " IN (" + String.join(",", Collections.nCopies(vs.size(), "?")) + ")");
                        valores.addAll(vs);
                        continue;
                    }
                }
                Matcher m = FILTRO.matcher(tx);
                boolean hecho = false;
                if (m.matches() && !tx.toLowerCase(Locale.ROOT).matches("(?s).*\\b(and|or|not|in|like)\\b.*")) {
                    String col = plan.columna(m.group(1));
                    OptimistFormula der;
                    try { der = OptimistFormula.de(m.group(3)); } catch (IllegalArgumentException ex) { der = null; }
                    if (col != null && der != null && der.atributos().stream().allMatch(x -> x.startsWith("&") || x.contains(".") || e.enFilaExterna(x))) {
                        sql.add(col + " " + m.group(2) + " ?");
                        valores.add(sqlValor(e.evaluar(der)));
                        hecho = true;
                    }
                }
                if (!hecho) enJava.add(f);
            }
            // For Each anidado: se filtra por la clave de la tabla de afuera; los atributos del Parm filtran siempre
            int condPadre = -1, valPadre = -1; String clavePadre = null; Object valorPadre = null;
            for (String k : base.clave()) {
                Object v = e.valorFilaExterna(k);
                if (e.enFilaAnidada(k) && plan.columna(k) != null && sql.stream().noneMatch(s -> s.startsWith(plan.columna(k) + " "))) {
                    if (v != null) { condPadre = condPadre == -1 ? sql.size() : -2; valPadre = valores.size(); clavePadre = k; valorPadre = v; }
                    sql.add(plan.columna(k) + (v == null ? " IS NULL" : " = ?")); if (v != null) valores.add(sqlValor(v));
                }
            }
            for (Map<String, Object> fp : e.filas) if (fp instanceof FilaParm)
                for (var p : fp.entrySet()) {
                    String col = plan.columna(p.getKey());
                    if (col != null && sql.stream().noneMatch(s -> s.startsWith(col + " "))) { sql.add(col + " = ?"); valores.add(sqlValor(p.getValue())); }
                }
            List<String> ord = new ArrayList<>();
            for (String o : orden) {
                boolean desc = o.startsWith("(");
                String col = plan.columna(desc ? o.substring(1) : o);
                if (col != null) ord.add(col + (desc ? " DESC" : ""));
            }
            String limite = "";
            if (soloUltima && enJava.isEmpty()) {
                // V48: el For Each de GeneXus recorre todo y se queda con la última fila; acá se pide solo esa (orden al revés, LIMIT 1)
                List<String> inv = new ArrayList<>();
                for (String o : ord) inv.add(o.endsWith(" DESC") ? o.substring(0, o.length() - 5) : o + " DESC");
                for (String k : base.clave()) inv.add("t0.`" + base.columnas().get(k) + "` DESC");
                ord = inv;
                limite = " LIMIT 1";
            } else if (soloPrimera && enJava.isEmpty()) limite = " LIMIT 1";
            String q = "SELECT " + String.join(", ", select) + " FROM `" + base.nombre() + "` t0"
                    + String.join("", plan.uniones()) + (sql.isEmpty() ? "" : " WHERE " + String.join(" AND ", sql))
                    + (ord.isEmpty() ? "" : " ORDER BY " + String.join(", ", ord)) + limite;
            // V48c: un For Each que actualiza o borra bloquea, fila por fila, SOLO el registro de su tabla base, por su clave
            // (SELECT ... WHERE clave = ? FOR UPDATE), hasta el Commit / Rollback. No se bloquean las tablas de la extendida
            // (artículo, cliente) ni toda la tabla. Si ya hay un registro bloqueado más afuera (For Each de PED con uno de
            // PEDDet adentro: la clave de PEDDet empieza con la de PED), los renglones no se bloquean: con el principal
            // bloqueado alcanza. Otra tabla adentro (la cuenta corriente, el saldo del cliente) sí se bloquea. Así dos usuarios
            // (o dos pantallas) que corren a la vez un proceso sobre el mismo registro van uno detrás del otro, y el segundo
            // lee lo que grabó el primero en lugar de pisarlo.
            boolean bloquea = (borra || !asignados.isEmpty()) && !e.conexion.getAutoCommit() && !base.clave().isEmpty()
                    && e.filas.stream().noneMatch(x -> x instanceof FilaBD b && b.bloqueada && base.clave().containsAll(b.tabla.clave()));
            List<FilaBD> filas = new ArrayList<>();
            Map<List<Object>, FilaBD> extendidas = new HashMap<>();
            Map<List<Object>, Object[]> calculadas = new HashMap<>();   // V48c: registro + fórmulas calculadas, por clave
            // V36: la misma consulta con los mismos valores sale de memoria mientras se edita el formulario
            long tq = System.nanoTime();
            List<Object[]> leidas = null;
            String origenAntes = OptimistLecturas.ORIGEN.get();
            OptimistLecturas.ORIGEN.set(proc + ":" + renglon);   // V49y: para saber qué For Each consulta
            try {
            // V49w: For Each anidado de solo lectura filtrado por la clave del de afuera (también de solo lectura): en lote
            if (condPadre >= 0 && !bloquea && !borra && asignados.isEmpty() && limite.isEmpty()) {
                final String kp = clavePadre;
                Lote lote = null;
                for (Map<String, Object> fx : e.filas)
                    if (fx instanceof FilaBD fb && fb.lote != null && fb.containsKey(kp)) { lote = fb.lote; break; }
                int idx = nombres.indexOf(kp);
                if (lote != null && lote.lectura && lote.filas.size() > 1 && lote.filas.size() <= 2000 && idx >= 0) {
                    List<String> sqlOtras = new ArrayList<>(sql); String colPadre = plan.columna(kp); sqlOtras.remove(condPadre);
                    List<Object> valOtros = new ArrayList<>(valores); valOtros.remove(valPadre);
                    String firma = String.join(",", select) + "|" + base.nombre() + String.join("", plan.uniones()) + "|" + sqlOtras + "|" + valOtros + "|" + ord;
                    Map<String, List<Object[]>> porPadre = lote.leido.get(firma);
                    if (porPadre == null) {
                        LinkedHashSet<String> vistos = new LinkedHashSet<>(); List<Object> padres = new ArrayList<>();
                        for (FilaBD fp : lote.filas) { Object pv = fp.get(kp); if (pv != null && vistos.add(claveLote(pv))) padres.add(sqlValor(pv)); }
                        List<String> sqlLote = new ArrayList<>(sqlOtras);
                        sqlLote.add(colPadre + " IN (" + String.join(",", Collections.nCopies(padres.size(), "?")) + ")");
                        List<Object> valLote = new ArrayList<>(valOtros); valLote.addAll(padres);
                        String qLote = "SELECT " + String.join(", ", select) + " FROM `" + base.nombre() + "` t0" + String.join("", plan.uniones())
                                + " WHERE " + String.join(" AND ", sqlLote) + (ord.isEmpty() ? "" : " ORDER BY " + String.join(", ", ord));
                        porPadre = new HashMap<>();
                        for (Object[] fila : OptimistLecturas.leer(e.conexion, qLote, valLote, nombres.size()))
                            porPadre.computeIfAbsent(claveLote(normalizar(fila[idx])), x -> new ArrayList<>()).add(fila);
                        lote.leido.put(firma, porPadre);
                    }
                    leidas = porPadre.getOrDefault(claveLote(valorPadre), List.of());
                }
            }
            if (leidas == null) leidas = bloquea ? OptimistLecturas.leerSinMemoria(e.conexion, q, valores, nombres.size())
                    : OptimistLecturas.leer(e.conexion, q, valores, nombres.size());
            } finally { OptimistLecturas.ORIGEN.set(origenAntes); }
            long msq = (System.nanoTime() - tq) / 1_000_000;
            if (DEPURAR && msq >= 30)   // V48: las consultas lentas, con su SQL
                System.out.println("  ".repeat(e.profundidad + 1) + "SQL " + msq + " ms, " + leidas.size() + " filas (" + proc + ", renglón " + renglon + "): " + q + " " + valores);
            for (Object[] fila : leidas) {
                FilaBD f = new FilaBD(base);
                if (!sinColumna.isEmpty()) { Set<String> sc = new HashSet<>(); for (String x : sinColumna) sc.add(x.toLowerCase(Locale.ROOT)); f.sinColumna = sc; }
                for (int k = 0; k < nombres.size(); k++) f.put(nombres.get(k), normalizar(fila[k]));
                for (String k : base.clave()) f.clave.put(k, f.get(k));
                for (int j : extUsadas) {   // V48c: un registro por clave, compartido entre las filas (varios PEDDet, un PED)
                    TablaBD u = plan.tablas().get(j);
                    List<Object> kv = new ArrayList<>();
                    for (String k : u.clave()) kv.add(f.get(EXT + j + ":" + k));
                    if (kv.contains(null)) continue;   // el LEFT JOIN no encontró el registro
                    List<Object> id = new ArrayList<>(kv); id.add(0, j);
                    FilaBD x = extendidas.get(id);
                    if (x == null) {
                        x = new FilaBD(u);
                        for (int qq = 0; qq < u.clave().size(); qq++) x.clave.put(u.clave().get(qq), kv.get(qq));
                        for (String n2 : nombres) if (u.columnas().containsKey(n2)) x.put(n2, f.get(n2));
                        if (!e.conexion.getAutoCommit()) { bloquearRegistro(e.conexion, u, x.clave); x.bloqueada = true; }
                        extendidas.put(id, x);
                    } else for (var v : x.entrySet()) f.put(v.getKey(), v.getValue());   // lo que ya cambió otra fila
                    f.extendidas.add(x);
                }
                filas.add(f);
            }
            Lote loteEste = new Lote(filas, !borra && asignados.isEmpty() && !bloquea);   // V49w
            for (FilaBD f : filas) f.lote = loteEste;
            int n = 0;
            java.util.function.DoubleConsumer avance = PROGRESO_PORCENTAJE.get();   // V66z: % de avance automático
            boolean guia = avance != null && filas.size() >= 2 && !Boolean.TRUE.equals(PROGRESO_MANUAL.get())
                    && !Boolean.TRUE.equals(PROGRESO_GUIA.get());
            if (guia) PROGRESO_GUIA.set(true);
            int vuelta = 0;
            try {
                for (FilaBD f : filas) {
                    if (guia && !Boolean.TRUE.equals(PROGRESO_MANUAL.get())) avance.accept(vuelta * 100.0 / filas.size());
                    vuelta++;
                    e.filas.push(f);
                    try {
                        boolean ok = true;
                        if (bloquea && f.extendidas.stream().noneMatch(x -> x.bloqueada && base.clave().containsAll(x.tabla.clave()))) {
                            // bloquea el registro y trae cómo está ahora (pudo cambiar entre la lectura y el bloqueo)
                            Set<String> cambiaron = bloquear(e.conexion, base, select, nombres, f);
                            if (cambiaron == null) continue;   // lo borraron en el medio
                            if (!cambiaron.isEmpty())        // cambió: se controlan de nuevo todas las condiciones
                                for (OptimistFormula w : wheres) if (!OptimistFormula.verdad(e.evaluar(w))) { ok = false; break; }
                            if (!ok) continue;
                        }
                        if (!formulas.isEmpty()) calcularFormulas(e.conexion, f, formulas, calculadas);
                        for (OptimistFormula w : enJava) if (!OptimistFormula.verdad(e.evaluar(w))) { ok = false; break; }
                        if (!ok) continue;
                        n++;
                        try { e.ejecutar(cuerpo); }
                        catch (Salir | Retornar s) { f.grabarTodo(e.conexion); throw s; }
                        f.grabarTodo(e.conexion);
                    } finally { e.filas.pop(); }
                }
            } catch (Salir s) { n = Math.max(n, 1); }
            finally { if (guia) PROGRESO_GUIA.set(false); }
            if (n == 0) e.ejecutar(ninguno);
        }

        static final String EXT = "\u0001ext";
        static final Set<String> AVISADOS = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** V48c: el atributo está en la base: columna de alguna tabla, 'trae' de una referencia de las TRN o subtipo de la KB. */
        static boolean existeEnLaBase(Map<String, TablaBD> esq, String a) {
            String k = a.toLowerCase(Locale.ROOT);
            if (esq.values().stream().anyMatch(t -> t.columnas().containsKey(k))) return true;
            for (OptimistTrnDefinicion.Trn t : trns.get()) {
                for (OptimistTrnDefinicion.Referencia r : t.cabecera().referencias()) if (r.trae().keySet().stream().anyMatch(a::equalsIgnoreCase)) return true;
                for (OptimistTrnDefinicion.Nivel d : t.detalles())
                    for (OptimistTrnDefinicion.Referencia r : d.referencias()) if (r.trae().keySet().stream().anyMatch(a::equalsIgnoreCase)) return true;
            }
            // V49b: un subtipo existe si su supertipo es una columna (PRSTransportesDirGoogle es subtipo de TransportesDirGoogle,
            // que en GeneXus es una fórmula: no está en la base)
            for (Map<String, String> g : gruposSubtipos.values())
                if (g.containsKey(k) && esq.values().stream().anyMatch(t -> t.columnas().containsKey(g.get(k)))) return true;
            return false;
        }

        private static boolean contiene(Collection<String> c, String a) { return c.stream().anyMatch(x -> x.equalsIgnoreCase(a)); }

        /** V48c: {TRN, nivel (null = cabecera)} donde el atributo es una fórmula que no se graba, o null. */
        static Object[] formulaDe(String atributo) {
            for (OptimistTrnDefinicion.Trn t : trns.get()) {
                OptimistTrnDefinicion.Campo c = t.cabecera().campo(atributo);
                if (c != null && c.formula() != null) return new Object[]{t, null};
                for (OptimistTrnDefinicion.Nivel d : t.detalles()) {
                    OptimistTrnDefinicion.Campo x = d.campo(atributo);
                    if (x != null && x.formula() != null) return new Object[]{t, d};
                }
            }
            return null;
        }

        /** V48c: calcula las fórmulas de la fila: carga el registro de su TRN (una vez por clave) y corre las fórmulas. */
        private static void calcularFormulas(Connection c, FilaBD f, Map<String, Object[]> formulas, Map<List<Object>, Object[]> cache) {
            for (var fe : formulas.entrySet()) {
                OptimistTrnDefinicion.Trn t = (OptimistTrnDefinicion.Trn) fe.getValue()[0];
                OptimistTrnDefinicion.Nivel nv = (OptimistTrnDefinicion.Nivel) fe.getValue()[1];
                Map<String, Object> clave = new LinkedHashMap<>();
                List<Object> id = new ArrayList<>(List.of(t.nombre()));
                for (OptimistTrnDefinicion.Campo k : t.cabecera().claves()) { Object v = f.get(k.nombre().toLowerCase(Locale.ROOT)); clave.put(k.nombre(), v); id.add(String.valueOf(v)); }
                if (clave.containsValue(null)) continue;
                Object[] hecho = cache.computeIfAbsent(id, x -> {
                    try {
                        OptimistTrnDatos datos = new OptimistTrnDatos(c);
                        OptimistTrnDatos.Registro r = datos.cargarBc(t, clave);
                        Map<String, List<Map<String, Object>>> lineas = new LinkedHashMap<>();
                        for (var d : r.detalles().entrySet()) {
                            List<Map<String, Object>> ls = new ArrayList<>();
                            for (OptimistTrnDatos.Linea l : d.getValue()) ls.add(l.valores());
                            lineas.put(d.getKey(), ls);
                        }
                        return new Object[]{r, OptimistCalculo.calcular(t, r.cabecera(), lineas, datos.abridor())};
                    } catch (SQLException | RuntimeException ex) {
                        if (DEPURAR) System.out.println("  fórmulas de " + t.nombre() + " " + clave + ": " + ex.getMessage());
                        return new Object[]{null, null};
                    }
                });
                if (hecho[1] == null) continue;
                OptimistCalculo.Resultado res = (OptimistCalculo.Resultado) hecho[1];
                String nombre = nv == null ? t.cabecera().campo(fe.getKey()).nombre() : nv.campo(fe.getKey()).nombre();
                if (nv == null) { f.put(fe.getKey(), res.cabecera().get(nombre)); continue; }
                // fórmula del renglón: el renglón de la TRN con la misma clave que esta fila
                List<OptimistTrnDatos.Linea> ls = ((OptimistTrnDatos.Registro) hecho[0]).lineas(nv.nombre());
                List<Map<String, Object>> calc = res.lineas().getOrDefault(nv.nombre(), List.of());
                for (int i = 0; i < ls.size() && i < calc.size(); i++) {
                    boolean misma = true;
                    for (OptimistTrnDefinicion.Campo k : nv.claves())
                        if (!OptimistTrnDatos.igual(ls.get(i).valores().get(k.nombre()), f.get(k.nombre().toLowerCase(Locale.ROOT)))) { misma = false; break; }
                    if (misma) { f.put(fe.getKey(), calc.get(i).get(nombre)); break; }
                }
            }
        }

        /**
         * V48c: For Each PED.Det (un nivel de una transacción, como GeneXus) -> la tabla de ese nivel (PEDDet); For Each PED
         * -> la tabla de la TRN si no hay una tabla con ese nombre. Sin catálogo o si no lo encuentra, queda como está.
         */
        static String tablaDe(Map<String, TablaBD> esq, String explicita) {
            if (explicita == null || explicita.isBlank()) return explicita;
            String[] p = explicita.trim().split("\\s*\\.\\s*");
            if (p.length == 1 && esq.containsKey(p[0].toLowerCase(Locale.ROOT))) return explicita;
            OptimistTrnDefinicion.Trn t = trnDe.apply(p[0]);
            if (t != null && p.length == 1) return t.cabecera().tabla();
            if (t != null)
                for (OptimistTrnDefinicion.Nivel d : t.detalles())
                    if (d.nombre().equalsIgnoreCase(p[1]) || d.tabla().equalsIgnoreCase(p[1])) return d.tabla();
            // nivel que no está en el YAML (Partidas.PEDDet): la tabla se llama como la TRN + el nivel (PartidasPEDDet)
            if (p.length == 2) {
                for (String n : t == null ? List.of(p[0] + p[1]) : List.of(t.cabecera().tabla() + p[1], p[0] + p[1]))
                    if (esq.containsKey(n.toLowerCase(Locale.ROOT))) return esq.get(n.toLowerCase(Locale.ROOT)).nombre();
                if (esq.containsKey(p[1].toLowerCase(Locale.ROOT))) return esq.get(p[1].toLowerCase(Locale.ROOT)).nombre();
            }
            return explicita;
        }

        /** V48c: bloquea un registro de la extendida que se va a actualizar (por su clave). */
        private static void bloquearRegistro(Connection c, TablaBD t, Map<String, Object> clave) throws SQLException {
            List<String> w = new ArrayList<>();
            List<Object> vs = new ArrayList<>();
            for (var k : clave.entrySet()) { w.add("`" + t.columnas().get(k.getKey()) + "` = ?"); vs.add(sqlValor(k.getValue())); }
            OptimistLecturas.leerSinMemoria(c, "SELECT 1 FROM `" + t.nombre() + "` WHERE " + String.join(" AND ", w) + " FOR UPDATE", vs, 1);
        }

        /**
         * V48c: SELECT de las columnas de la tabla base de esta fila, por su clave, FOR UPDATE (bloquea solo ese registro).
         * Actualiza la fila con lo que hay ahora en la base. Devuelve los atributos que cambiaron desde la primera
         * lectura (vacío = nada), o null si el registro ya no está.
         */
        private static Set<String> bloquear(Connection c, TablaBD base, List<String> select, List<String> nombres, FilaBD f) throws SQLException {
            List<String> cols = new ArrayList<>(), ns = new ArrayList<>();
            for (int k = 0; k < select.size(); k++) if (select.get(k).startsWith("t0.")) { cols.add(select.get(k)); ns.add(nombres.get(k)); }
            List<String> w = new ArrayList<>();
            List<Object> vs = new ArrayList<>();
            for (var k : f.clave.entrySet()) { w.add("t0.`" + base.columnas().get(k.getKey()) + "` = ?"); vs.add(sqlValor(k.getValue())); }
            String q = "SELECT " + (cols.isEmpty() ? "1" : String.join(", ", cols)) + " FROM `" + base.nombre() + "` t0 WHERE "
                    + String.join(" AND ", w) + " FOR UPDATE";
            List<Object[]> r = OptimistLecturas.leerSinMemoria(c, q, vs, Math.max(1, cols.size()));
            if (r.isEmpty()) return null;
            f.bloqueada = true;
            Set<String> cambiaron = new LinkedHashSet<>();
            for (int k = 0; k < ns.size(); k++) {
                Object nuevo = normalizar(r.get(0)[k]);
                if (!Objects.equals(String.valueOf(f.get(ns.get(k))), String.valueOf(nuevo))) cambiaron.add(ns.get(k));
                f.put(ns.get(k), nuevo);
            }
            return cambiaron;
        }

        /** Atributos del For Each + los de las Subs que llama (y las que llaman esas). "=X": X se actualiza. */
        private static void expandir(Set<String> usados, OptimistProcedimiento p, Set<String> r, Set<String> vistas) {
            for (String a : usados) {
                if (a.startsWith(SUB)) {
                    String s = a.substring(SUB.length());
                    if (vistas.add(s) && p.atributosSubs.containsKey(s)) expandir(p.atributosSubs.get(s), p, r, vistas);
                } else r.add(a);
            }
        }
    }

    private static final class Nuevo implements Sentencia {
        final String proc; final int renglon; final List<Sentencia> cuerpo, duplicado; final Set<String> asignados;
        Nuevo(String proc, int renglon, List<Sentencia> cuerpo, List<Sentencia> duplicado, Set<String> asignados) {
            this.proc = proc; this.renglon = renglon; this.cuerpo = cuerpo; this.duplicado = duplicado; this.asignados = asignados;
        }

        public void ejecutar(Ejecucion e) throws SQLException {
            FilaNueva f = new FilaNueva();
            e.filas.push(f);
            try { e.ejecutar(cuerpo); } finally { e.filas.pop(); }
            TablaBD t = tabla(esquema(e.conexion), f.keySet());
            List<String> cols = new ArrayList<>(), marcas = new ArrayList<>();
            List<Object> vs = new ArrayList<>();
            for (var x : f.entrySet()) { cols.add("`" + t.columnas().get(x.getKey()) + "`"); marcas.add("?"); vs.add(conVacio(t, x.getKey(), sqlValor(x.getValue()))); }
            // V48: como GeneXus, las columnas NOT NULL que no se asignaron van con su valor vacío (0, "")
            for (var x : t.vacios().entrySet())
                if (!f.containsKey(x.getKey())) { cols.add("`" + t.columnas().get(x.getKey()) + "`"); marcas.add("?"); vs.add(x.getValue()); }
            String sql = "INSERT INTO `" + t.nombre() + "` (" + String.join(", ", cols) + ") VALUES (" + String.join(", ", marcas) + ")";
            OptimistLecturas.escribio();
            try (PreparedStatement p = e.conexion.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                for (int k = 0; k < vs.size(); k++) p.setObject(k + 1, vs.get(k));
                p.executeUpdate();
                // V48: como GeneXus, después del EndNew los atributos (y la clave autonumerada) quedan con los valores grabados
                if (t.clave().size() == 1 && !f.containsKey(t.clave().get(0)))
                    try (ResultSet g = p.getGeneratedKeys()) { if (g.next()) f.put(t.clave().get(0), normalizar(g.getObject(1))); }
                e.ultimoNew = new HashMap<>(f);
            } catch (SQLException ex) {
                if (ex.getSQLState() == null || !ex.getSQLState().startsWith("23")) throw ex;
                // V65m: solo la clave DUPLICADA se ignora (como GeneXus). Antes cualquier violación (ej. un almacén que no existe:
                // clave foránea) se tomaba como duplicada y se seguía: quedaba la cabecera del movimiento de stock sin renglones.
                if (!OptimistTrnDatos.esDuplicado(ex)) {
                    Map<String, Object> porColumna = new HashMap<>();
                    for (int k = 0; k < cols.size(); k++) porColumna.put(cols.get(k).replace("`", ""), vs.get(k));
                    throw OptimistTrnDatos.errorIntegridad(ex, t.nombre(), porColumna);
                }
                // clave duplicada: When Duplicate (se actualiza el registro que ya estaba); sin When Duplicate, no hace nada
                if (duplicado == null) return;
                FilaBD existente = new FilaBD(t);
                for (String k : t.clave()) { existente.clave.put(k, f.get(k)); existente.put(k, f.get(k)); }
                e.filas.push(existente);
                try { e.ejecutar(duplicado); existente.grabar(e.conexion); } finally { e.filas.pop(); }
            }
        }

        /** La tabla que tiene todos los atributos asignados y cuya clave está completa (o es autonumerada). */
        private TablaBD tabla(Map<String, TablaBD> esq, Set<String> asignadosAhora) {
            Set<String> atts = new HashSet<>();
            for (String a : asignados) if (a.startsWith("=")) atts.add(a.substring(1).toLowerCase(Locale.ROOT));
            TablaBD mejor = null; int mejorPuntaje = -1;
            for (TablaBD t : esq.values()) {
                if (!t.columnas().keySet().containsAll(atts) || t.clave().isEmpty()) continue;
                boolean claveCompleta = atts.containsAll(t.clave()) || (t.clave().size() == 1 && t.autonumericas().contains(t.clave().get(0)));
                // V48c: como GeneXus, la parte de la clave que no se asigna va con su valor vacío (0): MensajesEnviosUsuariosId
                boolean claveConVacio = !claveCompleta && atts.stream().anyMatch(t.clave()::contains)
                        && t.clave().stream().allMatch(k -> atts.contains(k) || t.vacios().containsKey(k));
                if (!claveCompleta && !claveConVacio) continue;
                int puntaje = 1000 - t.columnas().size() - (claveConVacio ? 500 : 0);   // la más chica que alcance
                if (atts.containsAll(t.clave())) puntaje += 1000;  // clave asignada a mano, mejor todavía
                if (puntaje > mejorPuntaje) { mejor = t; mejorPuntaje = puntaje; }
            }
            if (mejor == null) throw new IllegalStateException(proc + ", renglón " + renglon + ": New: no encuentro una tabla con " + asignados + " y su clave completa");
            return mejor;
        }
    }

    private static Object sqlValor(Object v) {
        if (v instanceof Boolean b) return b ? 1 : 0;
        return v;
    }

    private static Object normalizar(Object v) {
        if (v == null) return null;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (v instanceof Number n && !(v instanceof BigDecimal)) return new BigDecimal(n.toString());
        if (v instanceof byte[] b && b.length == 1) return b[0] != 0;
        return v;
    }

    // ------------------------------------------------------------------ ejecución

    /** Estructura (SDT): los nombres de los campos no distinguen mayúsculas. */
    public static class Sdt extends TreeMap<String, Object> {
        public Sdt() { super(String.CASE_INSENSITIVE_ORDER); }
        public Sdt(Map<String, ?> m) { this(); if (m != null) putAll(m); }
    }

    /**
     * V48: un procedimiento que termina abriendo una TRN (PED.Call(TrnMode.Update, &PEDId), como hace GeneXus al generar el
     * pedido): queda anotado acá y la pantalla que corrió el procedimiento la abre al terminar.
     */
    static final ThreadLocal<Object[]> TRN_A_ABRIR = new ThreadLocal<>();

    /** V66x: a quién avisar lo que va haciendo un proceso largo (MostrarProgreso.Call(&Texto)): la ventanita "Procesando…". */
    public static final ThreadLocal<java.util.function.Consumer<String>> PROGRESO = new ThreadLocal<>();
    /** V66x3: el ancho fijo de la ventanita, en letras (AnchoProgreso.Call(&Letras)): así no cambia de tamaño con cada texto. */
    public static final ThreadLocal<java.util.function.IntConsumer> PROGRESO_ANCHO = new ThreadLocal<>();
    /** V66x4: % de avance: TotalProgreso.Call(&Total) (cada MostrarProgreso cuenta uno) o PorcentajeProgreso.Call(&Porcentaje). */
    public static final ThreadLocal<java.util.function.IntConsumer> PROGRESO_TOTAL = new ThreadLocal<>();
    public static final ThreadLocal<java.util.function.DoubleConsumer> PROGRESO_PORCENTAJE = new ThreadLocal<>();
    /**
     * V66z: % de avance automático (CLAUDE.md: todo proceso de un web panel muestra el % de avance). El primer For Each de 2 o
     * más filas que corre sin otro "guía" abierto informa el % de sus vueltas (las filas ya están leídas: se sabe el total);
     * los de adentro (y los de los procedimientos que llama) no. Si el procedimiento informa su propio avance (TotalProgreso
     * / PorcentajeProgreso), manda el suyo.
     */
    public static final ThreadLocal<Boolean> PROGRESO_MANUAL = new ThreadLocal<>();
    static final ThreadLocal<Boolean> PROGRESO_GUIA = new ThreadLocal<>();

    /**
     * V49: el diseño de impresión (impresiones/<Nombre>.yaml) de un procedimiento que imprime (reporte de GeneXus), o null.
     * Lo carga OptimistProcedimientos. Al leer el .proc se usa para saber qué atributos trae cada "print Bloque".
     */
    public static volatile java.util.function.Function<String, OptimistImpresion.Diseno> disenoDe = n -> null;
    /** V49: de lo que tiene una variable imagen (bytes de la base, ruta o URL) a los bytes de la imagen. */
    public static volatile java.util.function.Function<Object, byte[]> cargarImagen = OptimistProcedimiento::imagen;
    /** V49: los PDF que generaron los reportes de esta ejecución: {nombre, bytes}. La pantalla los muestra al terminar. */
    static final ThreadLocal<List<Object[]>> PDFS = ThreadLocal.withInitial(ArrayList::new);

    /** Los PDF generados desde la última vez (y los olvida). */
    public static List<Object[]> tomarPdfs() { List<Object[]> r = new ArrayList<>(PDFS.get()); PDFS.get().clear(); ARCHIVOS.get().clear(); return r; }

    /** V49f: el evento de un web panel terminó con Return (en GeneXus vuelve a la pantalla que lo llamó: el panel se cierra). */
    static final ThreadLocal<Boolean> RETORNO = ThreadLocal.withInitial(() -> false);
    public static boolean tomarRetorno() { boolean r = RETORNO.get(); RETORNO.set(false); return r; }

    /**
     * V50b: &Excel (Ext(ExcelDocument)) de GeneXus. Por ahora no se arma el archivo: Open / Clear / Save / Close no hacen nada y
     * las celdas se aceptan y se descartan, con ErrCode 0. Alcanza para los procedimientos que arman un Excel de detalle al
     * costado de su cálculo (PRSoPEDResultado: el resultado del presupuesto de IEP), que así corren hasta el final.
     */
    static final class PlanillaExcel extends Sdt {
        /** V66: las celdas y el nombre con que se abrió (Open). Save() arma el .xlsx (OptimistPlanilla) y lo deja en ARCHIVOS. */
        transient final OptimistPlanilla planilla = new OptimistPlanilla();
        transient String archivo = "";
        PlanillaExcel() { put("ErrCode", BigDecimal.ZERO); put("ErrDescription", ""); put("AutoFit", BigDecimal.ZERO); }
    }

    /**
     * V66: los archivos que armaron los procedimientos de esta ejecución (el .xlsx de un &ExcelDocument.Save()), por el
     * nombre con que se abrieron. Link(&Archivo) en un web panel (como GeneXus) lo baja: pasa a la lista de PDFS con su nombre.
     */
    static final ThreadLocal<Map<String, byte[]>> ARCHIVOS = ThreadLocal.withInitial(LinkedHashMap::new);

    /** V66: el nombre con que se baja un archivo armado (el Excel de GeneXus era .xls; el nuevo es .xlsx). */
    static String nombreDescarga(String archivo) {
        String n = archivo == null ? "" : archivo.trim();
        int barra = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (barra >= 0) n = n.substring(barra + 1);
        if (n.toLowerCase(Locale.ROOT).endsWith(".xls")) n = n + "x";
        else if (!n.toLowerCase(Locale.ROOT).endsWith(".xlsx")) n = n + ".xlsx";
        return n;
    }

    /**
     * V66: PDFAddWatermark(&Origen, &Destino, &Imagen, &X, &Y) de GeneXus: la marca de agua en el PDF que armó un reporte
     * en esta ejecución (el de nombre &Origen, con o sin ".pdf"). Queda en el mismo PDF (en GeneXus iba a &Destino y
     * después se copiaba sobre el original). Devuelve el error o "".
     */
    static String marcaDeAgua(String archivo, Object imagen, double x, double y) {
        String a = archivo == null ? "" : archivo.trim();
        String sin = a.toLowerCase(Locale.ROOT).endsWith(".pdf") ? a.substring(0, a.length() - 4) : a;
        byte[] img = cargarImagen.apply(imagen);
        if (img == null) return "La marca de agua de la empresa no es una imagen que se pueda leer.";
        List<Object[]> ps = PDFS.get();
        for (int i = ps.size() - 1; i >= 0; i--) {
            String n = String.valueOf(ps.get(i)[0]).trim();
            String ns = n.toLowerCase(Locale.ROOT).endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
            if (!ns.equalsIgnoreCase(sin) || !(ps.get(i)[1] instanceof byte[] pdf)) continue;
            byte[] r = OptimistPdf.conFondo(pdf, img, x, y);
            if (r == null) return "No se pudo poner la marca de agua en " + a + ".";
            ps.set(i, new Object[]{ps.get(i)[0], r});
            return "";
        }
        return "No se encontró el PDF " + a + " para ponerle la marca de agua.";
    }

    /** V66: Link(&Archivo): si es un archivo armado en esta ejecución, la pantalla lo baja. Devuelve si lo encontró. */
    static boolean bajar(String archivo) {
        byte[] b = ARCHIVOS.get().get(archivo == null ? "" : archivo.trim());
        if (b == null) return false;
        PDFS.get().add(new Object[]{nombreDescarga(archivo), b});
        return true;
    }

    // ------------------------------------------------------------------ V49f: mails (Ext SMTPSession, MailMessage, MailRecipient)

    /** &SMTP (Ext(SMTPSession)): Host, Port, UserName, Password, Authentication, Secure, Sender.Name / .Address; ErrCode, ErrDescription. */
    static final class SmtpSesion extends Sdt {
        transient OptimistMail conexion;
        SmtpSesion() {
            put("Host", ""); put("Port", BigDecimal.valueOf(25)); put("UserName", ""); put("Password", "");
            put("Authentication", BigDecimal.ZERO); put("Secure", BigDecimal.ZERO);
            Sdt s = new Sdt(); s.put("Name", ""); s.put("Address", ""); put("Sender", s);
            error(0, "");
            // E4d: los datos del servidor de correo salen del optimist.cfg del cliente (fuera del código y de GitHub); lo que
            // asigne el procedimiento manda igual. mail.host, mail.puerto, mail.usuario, mail.clave, mail.seguro,
            // mail.remitente.nombre, mail.remitente.direccion
            try {
                OptimistDatabase db = OptimistDatabase.instancia();
                if (db != null) {
                    String h = db.propiedad("mail.host", ""), u = db.propiedad("mail.usuario", ""), c = db.propiedad("mail.clave", "");
                    String pu = db.propiedad("mail.puerto", ""), se = db.propiedad("mail.seguro", "");
                    if (!h.isBlank()) put("Host", h.trim());
                    if (!u.isBlank()) { put("UserName", u.trim()); put("Authentication", BigDecimal.ONE); }
                    if (!c.isEmpty()) put("Password", c);
                    if (pu.trim().matches("\\d{1,5}")) put("Port", new BigDecimal(pu.trim()));
                    if (se.equalsIgnoreCase("true") || (se.isBlank() && pu.trim().equals("465"))) put("Secure", BigDecimal.ONE);
                    s.put("Name", db.propiedad("mail.remitente.nombre", "").trim());
                    s.put("Address", db.propiedad("mail.remitente.direccion", u).trim());
                }
            } catch (RuntimeException ignorado) { }
        }
        void error(int codigo, String texto) { put("ErrCode", BigDecimal.valueOf(codigo)); put("ErrDescription", texto == null ? "" : texto); }
        String texto(String k) { Object v = get(k); return v == null ? "" : String.valueOf(v).trim(); }
        int numero(String k) { try { return OptimistFormula.num(get(k)).intValue(); } catch (RuntimeException ex) { return 0; } }
    }

    /** &MailMessage (Ext(MailMessage)): To, CC, BCC (de MailRecipient), Subject, Text, HTMLText, Attachments (nombres de archivo). */
    static final class MailMensaje extends Sdt {
        MailMensaje() {
            put("To", new ArrayList<>()); put("CC", new ArrayList<>()); put("BCC", new ArrayList<>());
            put("Subject", ""); put("Text", ""); put("HTMLText", ""); put("Attachments", new ArrayList<>());
        }
    }

    /** &MailRecipient (Ext(MailRecipient)): Address, Name. Al agregarlo a To / CC / BCC se copia (como GeneXus). */
    static final class MailDestinatario extends Sdt {
        MailDestinatario() { put("Address", ""); put("Name", ""); }
        MailDestinatario(Map<String, ?> m) { this(); putAll(m); }
    }

    private static Object smtp(SmtpSesion s, String metodo, List<Object> a) {
        try {
            switch (metodo) {
                case ".login" -> {
                    if (s.conexion != null) s.conexion.close();
                    s.conexion = new OptimistMail(s.texto("Host"), s.numero("Port"), s.numero("Secure") != 0);
                    s.conexion.login(s.numero("Authentication") != 0, s.texto("UserName"), s.texto("Password"));
                }
                case ".send" -> {
                    if (!(a.size() > 1 && a.get(1) instanceof Map<?, ?> m)) throw new IOException("Send: falta el mensaje (MailMessage)");
                    enviarMail(s, castear(m));
                }
                case ".logout" -> { if (s.conexion != null) s.conexion.logout(); s.conexion = null; }
                default -> { return Funciones.NO_ES; }
            }
            s.error(0, "");
            return BigDecimal.ZERO;
        } catch (IOException | RuntimeException ex) {
            if (s.conexion != null && !metodo.equals(".send")) { s.conexion.close(); s.conexion = null; }
            String m = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            s.error(metodo.equals(".login") ? 3 : metodo.equals(".send") ? 4 : 1, m);
            System.out.println("[Mail] " + metodo.substring(1) + ": " + m);
            return BigDecimal.ONE;
        }
    }

    private static void enviarMail(SmtpSesion s, Map<String, ?> msg) throws IOException {
        if (s.conexion == null) throw new IOException("no hay sesión de mail (falta el Login)");
        Sdt m = new Sdt(msg);
        Object snd = s.get("Sender");
        Sdt sender = snd instanceof Map<?, ?> x ? new Sdt(castear(x)) : new Sdt();
        OptimistMail.Destinatario de = new OptimistMail.Destinatario(texto(sender.get("Address")), texto(sender.get("Name")));
        String html = texto(m.get("HTMLText"));
        if (html.isEmpty()) html = texto(m.get("Text"));
        List<OptimistMail.Adjunto> adjuntos = new ArrayList<>();
        Object as = m.get("Attachments");
        if (as instanceof List<?> l) for (Object x : l) adjuntos.add(adjunto(texto(x)));
        s.conexion.enviar(de, destinatarios(m.get("To")), destinatarios(m.get("CC")), destinatarios(m.get("BCC")), texto(m.get("Subject")), html, adjuntos);
        // los PDF que se mandaron no se muestran en pantalla al terminar
        PDFS.get().removeIf(p -> adjuntos.stream().anyMatch(ad -> ad.datos() == p[1]));
    }

    private static List<OptimistMail.Destinatario> destinatarios(Object v) {
        List<OptimistMail.Destinatario> r = new ArrayList<>();
        if (v instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> mm) { Sdt d = new Sdt(castear(mm)); r.add(new OptimistMail.Destinatario(texto(d.get("Address")), texto(d.get("Name")))); }
                else if (o != null && !texto(o).isEmpty()) r.add(new OptimistMail.Destinatario(texto(o), ""));
        return r;
    }

    /**
     * Un adjunto por su nombre: primero un PDF que generó un reporte en esta ejecución (Output_file "PRS-X-00001-..."; con o sin
     * ".pdf", sin importar la carpeta); si no, un archivo del servidor con esa ruta.
     */
    static OptimistMail.Adjunto adjunto(String nombre) throws IOException {
        String n = nombre.replace('\\', '/');
        String base = n.substring(n.lastIndexOf('/') + 1);
        String sinPdf = base.toLowerCase(Locale.ROOT).endsWith(".pdf") ? base.substring(0, base.length() - 4) : base;
        List<Object[]> ps = PDFS.get();
        for (int i = ps.size() - 1; i >= 0; i--) {
            String pn = String.valueOf(ps.get(i)[0]).replace('\\', '/');
            pn = pn.substring(pn.lastIndexOf('/') + 1);
            if (pn.toLowerCase(Locale.ROOT).endsWith(".pdf")) pn = pn.substring(0, pn.length() - 4);
            if (pn.equalsIgnoreCase(sinPdf)) return new OptimistMail.Adjunto(sinPdf + ".pdf", (byte[]) ps.get(i)[1]);
        }
        java.nio.file.Path p = java.nio.file.Path.of(n);
        if (java.nio.file.Files.isRegularFile(p)) return new OptimistMail.Adjunto(base, java.nio.file.Files.readAllBytes(p));
        throw new IOException("no encuentro el archivo para adjuntar: " + nombre);
    }

    private static String texto(Object v) { return v == null ? "" : String.valueOf(v).trim(); }

    /** Imagen: bytes tal cual; texto: URL (http/https) o ruta de un archivo del servidor. null si no hay o no se pudo leer. */
    static byte[] imagen(Object v) {
        if (v instanceof byte[] b) return b.length == 0 ? null : b;
        if (v instanceof java.sql.Blob bl) { try { return bl.getBytes(1, (int) bl.length()); } catch (SQLException e) { return null; } }
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            if (s.matches("(?i)https?://.*")) {
                var con = (java.net.HttpURLConnection) java.net.URI.create(s).toURL().openConnection();
                con.setConnectTimeout(4000); con.setReadTimeout(8000);
                try (var in = con.getInputStream()) { return in.readAllBytes(); }
            }
            java.nio.file.Path p = java.nio.file.Path.of(s.replaceFirst("(?i)^file:/*", "/").replace('\\', '/'));
            if (java.nio.file.Files.isRegularFile(p)) return java.nio.file.Files.readAllBytes(p);
        } catch (Exception e) {
            System.out.println("Imagen '" + s + "': " + e.getMessage());
        }
        return null;
    }

    /** {nombre de la TRN, clave} que pidió abrir el último procedimiento (y lo olvida), o null. */
    public static Object[] tomarTrnAAbrir() { Object[] r = TRN_A_ABRIR.get(); TRN_A_ABRIR.remove(); return r; }

    /** V48: la TRN de un Business Component por su nombre (la carga OptimistProcedimientos desde el catálogo). */
    static java.util.function.Function<String, OptimistTrnDefinicion.Trn> trnDe = n -> null;
    /** V48c: todas las TRN (para encontrar dónde es fórmula un atributo). La carga OptimistProcedimientos desde el catálogo. */
    static java.util.function.Supplier<Collection<OptimistTrnDefinicion.Trn>> trns = List::of;

    /** V82k: atributo -> {largo, decimales} de su dominio numérico (de las TRN), para X.ToString(). */
    private static volatile Map<String, int[]> tamanios;
    private static volatile Object tamaniosDe;

    static int[] tamanioAtributo(String nombre) {
        Collection<OptimistTrnDefinicion.Trn> ts = trns.get();
        Map<String, int[]> m = tamanios;
        if (m == null || tamaniosDe != ts) {
            m = new HashMap<>();
            for (OptimistTrnDefinicion.Trn t : ts) {
                List<OptimistTrnDefinicion.Nivel> ns = new ArrayList<>();
                ns.add(t.cabecera());
                ns.addAll(t.detalles());
                for (OptimistTrnDefinicion.Nivel n : ns)
                    for (OptimistTrnDefinicion.Campo c : n.campos())
                        if (c.dominio() != null && c.dominio().tipo() == OptimistTrnDefinicion.Tipo.NUMERIC && !c.dominio().esCombo())
                            m.putIfAbsent(c.nombre().toLowerCase(Locale.ROOT), new int[]{c.dominio().longitud(), c.dominio().decimales()});
            }
            tamanios = m;
            tamaniosDe = ts;
        }
        return m.get(nombre.toLowerCase(Locale.ROOT));
    }

    /**
     * V48 - Business Component de GeneXus. Bc(PED): el registro (sus campos) y cada nivel como lista (&PED.Det); Bc(PED.Det):
     * un renglón. New() / Load(clave) / Save() / Success() / Fail() / GetMessages(). Graba con el motor de las TRN
     * (OptimistTrnDatos: valores por defecto, numeradores, fórmulas que se graban, validaciones, referencias) dentro de la
     * transacción del procedimiento: el Commit / Rollback lo decide el procedimiento, como en GeneXus.
     * Delete() (V50m) borra el registro cargado con sus niveles. Todavía no: las reglas de la TRN (las corre la pantalla al confirmar).
     */
    public static final class Bc extends Sdt {
        final String trn, nivel;
        boolean cargado, exito = true;
        final List<String> mensajes = new ArrayList<>();
        Map<String, Object> claveOriginal;   // renglón leído con Load (para actualizarlo)
        /**
         * V60b: hecho con Clone() (una duplicación): los valores del original al clonar. Al grabar, lo que el procedimiento
         * NO tocó después del Clone() (sigue igual a esto) queda como en el original: no lo pisan las reglas de la TRN, el
         * cálculo ni los valores por defecto. Lo que tocó (lo cambió o lo vació) lo pueden completar, como siempre (ej.
         * ClonarPED vacía el IVA del renglón para que se recalcule con la tasa nueva).
         */
        Map<String, Object> original;
        /** V60b: mientras se graba: lo que se protege (cabecera y cada renglón), con el valor del original. */
        private Map<String, Object> protegidosCab;
        private Map<OptimistTrnDatos.Linea, Map<String, Object>> protegidosLinea;

        Bc(String estructura) {
            String e = estructura == null ? "" : estructura.trim();
            int p = e.indexOf('.');
            trn = p < 0 ? e : e.substring(0, p);
            nivel = p < 0 ? null : e.substring(p + 1);
            if (nivel == null) {
                OptimistTrnDefinicion.Trn t = trnDe.apply(trn);
                if (t != null) for (OptimistTrnDefinicion.Nivel d : t.detalles()) put(d.nombre(), new ArrayList<>());
            }
        }

        OptimistTrnDefinicion.Trn definicion() {
            OptimistTrnDefinicion.Trn t = trnDe.apply(trn);
            if (t == null) throw new IllegalStateException("Business Component " + trn + ": no existe la TRN (trn/" + trn + ".yaml)");
            return t;
        }

        void load(Connection c, List<Object> claveArgs) {
            OptimistTrnDefinicion.Trn t = definicion();
            mensajes.clear();
            List<OptimistTrnDefinicion.Campo> claves = t.cabecera().claves();
            Map<String, Object> clave = new LinkedHashMap<>();
            for (int i = 0; i < claves.size(); i++) clave.put(claves.get(i).nombre(), i < claveArgs.size() ? claveArgs.get(i) : null);
            clear();
            for (OptimistTrnDefinicion.Nivel d : t.detalles()) put(d.nombre(), new ArrayList<>());
            try {
                if (clave.values().stream().anyMatch(v -> v == null || (v instanceof Number n && new BigDecimal(n.toString()).signum() == 0)))
                    throw new SQLException(t.singular() + " no encontrad" + t.genero());
                OptimistTrnDatos.Registro r = new OptimistTrnDatos(c).cargarBc(t, clave);
                putAll(r.cabecera());
                for (var e : r.detalles().entrySet()) {
                    List<Object> lineas = new ArrayList<>();
                    for (OptimistTrnDatos.Linea l : e.getValue()) {
                        Bc b = new Bc(trn + "." + e.getKey());
                        b.putAll(l.valores());
                        b.claveOriginal = l.claveOriginal();
                        b.cargado = true;
                        lineas.add(b);
                    }
                    put(e.getKey(), lineas);
                }
                cargado = true; exito = true;
            } catch (SQLException | RuntimeException ex) {
                cargado = false; exito = false;
                mensajes.add(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        }

        /** V50m: Delete() de GeneXus: borra el registro leído con Load (cabecera y niveles), sin Commit. */
        void delete(Connection c) {
            OptimistTrnDefinicion.Trn t = definicion();
            mensajes.clear();
            try {
                if (!cargado) throw new SQLException(t.singular() + " no encontrad" + t.genero() + ": falta el Load()");
                Map<String, Object> clave = new LinkedHashMap<>();
                for (OptimistTrnDefinicion.Campo k : t.cabecera().claves()) clave.put(k.nombre(), get(k.nombre()));
                new OptimistTrnDatos(c).borrar(c, t, clave);
                cargado = false; exito = true;
            } catch (SQLException | RuntimeException ex) {
                exito = false;
                mensajes.add(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                System.out.println("Business Component " + trn + ".Delete(): *** ERROR: " + mensajes.get(mensajes.size() - 1));
            }
        }

        @SuppressWarnings("unchecked")
        void save(Connection c) {
            OptimistTrnDefinicion.Trn t = definicion();
            mensajes.clear();
            protegidosCab = new LinkedHashMap<>();
            protegidosLinea = new java.util.IdentityHashMap<>();
            try {
                Map<String, Object> cab = new LinkedHashMap<>();
                for (OptimistTrnDefinicion.Campo k : t.cabecera().campos()) if (containsKey(k.nombre())) cab.put(k.nombre(), get(k.nombre()));
                Map<String, List<OptimistTrnDatos.Linea>> dets = new LinkedHashMap<>();
                for (OptimistTrnDefinicion.Nivel d : t.detallesVisibles()) {
                    List<OptimistTrnDatos.Linea> ls = new ArrayList<>();
                    if (get(d.nombre()) instanceof List<?> l)
                        for (Object o : l) {
                            if (!(o instanceof Map<?, ?> m)) continue;
                            Map<String, Object> v = new LinkedHashMap<>();
                            Map<String, Object> mm = new Sdt((Map<String, ?>) m);
                            for (OptimistTrnDefinicion.Campo k : d.campos()) if (mm.containsKey(k.nombre())) v.put(k.nombre(), mm.get(k.nombre()));
                            OptimistTrnDatos.Linea lin = new OptimistTrnDatos.Linea(v, o instanceof Bc b ? b.claveOriginal : null);
                            ls.add(lin);
                            // V60b: renglón clonado: lo que no se tocó después del Clone()
                            if (!cargado && o instanceof Bc b && b.original != null) {
                                Map<String, Object> pr = sinTocar(b.original, v);
                                if (!pr.isEmpty()) protegidosLinea.put(lin, pr);
                            }
                        }
                    dets.put(d.nombre(), ls);
                }
                if (!cargado && original != null) protegidosCab.putAll(sinTocar(original, cab));   // V60b
                reglasYCalculo(c, t, cab, dets);   // V48c
                OptimistContexto ctx = OptimistContexto.actual();
                String usuario = ctx == null || ctx.valor("SecUserName") == null ? "" : String.valueOf(ctx.valor("SecUserName"));
                // V60b: los valores por defecto no pisan lo que se protege
                Map<String, List<java.util.Set<String>>> salvoLineas = new LinkedHashMap<>();
                for (var d : dets.entrySet()) {
                    List<java.util.Set<String>> l2 = new ArrayList<>();
                    for (OptimistTrnDatos.Linea lin : d.getValue()) {
                        Map<String, Object> pr = protegidosLinea.get(lin);
                        l2.add(pr == null ? java.util.Set.of() : pr.keySet());
                    }
                    salvoLineas.put(d.getKey(), l2);
                }
                Map<String, Object> clave;
                OptimistTrnDatos.protegerDefectos(protegidosCab.keySet(), salvoLineas);
                try { clave = new OptimistTrnDatos(c).guardar(t, new OptimistTrnDatos.Registro(cab, dets), !cargado, usuario, null); }
                finally { OptimistTrnDatos.protegerDefectos(null, null); }
                if (clave != null) putAll(clave);
                cargado = true; exito = true;
            } catch (SQLException | RuntimeException ex) {
                exito = false;
                mensajes.add(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                System.out.println("Business Component " + trn + ".Save(): *** ERROR: " + mensajes.get(mensajes.size() - 1));   // V48c
            }
        }

        /**
         * V48c: como el Save de un Business Component de GeneXus, antes de grabar corren las reglas de la TRN
         * (asignaciones y llamadas, como si se hubiera cargado cada renglón en pantalla) y el cálculo comercial ('calculo:'):
         * así el pedido generado desde un presupuesto trae la tasa de IVA de cada artículo, el IVA de cada renglón y los totales.
         * No corren las reglas con evento (on BeforeInsert, BeforeComplete...): esas corren al confirmar en pantalla
         * (ej. el número del pedido). Los Error() de las reglas no frenan acá: se controlan al confirmar en pantalla
         * (van a la consola del servidor).
         */
        private void reglasYCalculo(Connection c, OptimistTrnDefinicion.Trn t, Map<String, Object> cab,
                                    Map<String, List<OptimistTrnDatos.Linea>> dets) throws SQLException {
            OptimistTrnDatos datos = new OptimistTrnDatos(c);
            OptimistCalculo.Abridor ab = datos.abridor();
            OptimistTrnDefinicion.Nivel cabN = t.cabecera();
            OptimistReglas rs = OptimistReglas.de(t);
            OptimistReglas.Estado est = rs.nuevoEstado(cargado ? "UPD" : "INS");
            List<String> avisos = new ArrayList<>();
            List<String> errores = new ArrayList<>();

            // cabecera: con sus inferidos (descripciones, CatIvaDesglosa...), como la tiene la pantalla
            Map<String, Object> cabV = new LinkedHashMap<>(cab);
            datos.completarReferencias(cabN, null, cabV, Map.of());
            if (!rs.vacio()) {
                rs.iniciar(est, cabV, ab, avisos);
                // V50y: en un alta, lo que el BC asignó con valor (&PED.PEDTransportesId = &PRS.PRSTransportesId) no lo pisa una
                // regla: en GeneXus la regla corre al asignar el atributo que la dispara (PEDEntidadesPK) y la asignación
                // siguiente del BC queda. Acá las reglas corren todas juntas al final (GetEntidadesTransporte dejaba el transporte en 0).
                Map<String, Object> delBc = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                if (!cargado)
                    for (var x : cab.entrySet()) {
                        OptimistTrnDefinicion.Campo k = cabN.campo(x.getKey());
                        if (k != null && !k.inferido() && !Funciones.vacioGx(x.getValue())) delBc.put(k.nombre(), x.getValue());
                    }
                OptimistReglas.Efecto e = rs.aplicar(OptimistReglas.Momento.CAMBIO, null, null, cabV, null, null,
                        editables(cabN), est, ab, c);
                cabV.putAll(e.asignados());
                for (var x : delBc.entrySet())
                    for (String n : new ArrayList<>(cabV.keySet())) if (n.equalsIgnoreCase(x.getKey())) cabV.put(n, x.getValue());
                errores.addAll(e.errores());
            }

            OptimistComercial com = OptimistComercial.de(t);
            java.util.function.Function<String, Object> vars = est::variable;
            int dec = com == null ? 2 : com.decimales(cabV, vars, ab);
            Map<String, List<Map<String, Object>>> lineasV = new LinkedHashMap<>();
            for (var d : dets.entrySet()) {
                OptimistTrnDefinicion.Nivel det = t.detalles().stream().filter(x -> x.nombre().equalsIgnoreCase(d.getKey())).findFirst().orElse(null);
                List<Map<String, Object>> ls = new ArrayList<>();
                lineasV.put(d.getKey(), ls);
                if (det == null) continue;
                for (OptimistTrnDatos.Linea l : d.getValue()) {
                    Map<String, Object> lv = new LinkedHashMap<>(l.valores());
                    datos.completarReferencias(det, cabN, lv, cabV);
                    if (!rs.vacio()) {
                        OptimistReglas.Efecto e = rs.aplicar(OptimistReglas.Momento.CAMBIO, null, det, lv, cabV, null,
                                editables(det), est, ab, c);
                        for (var a : e.asignados().entrySet()) {
                            if (det.campo(a.getKey()) != null) lv.put(a.getKey(), a.getValue());
                            else if (cabN.campo(a.getKey()) != null) cabV.put(a.getKey(), a.getValue());   // regla de línea que asigna la cabecera
                        }
                        errores.addAll(e.errores());
                    }
                    if (com != null && com.detalle().nombre().equalsIgnoreCase(det.nombre())) lv.putAll(com.linea(lv, cabV, dec, vars));
                    ls.add(lv);
                    // vuelve al renglón lo que se graba (no los inferidos)
                    for (var x : lv.entrySet()) {
                        OptimistTrnDefinicion.Campo k = det.campo(x.getKey());
                        if (k != null && !k.inferido()) l.valores().put(k.nombre(), x.getValue());
                    }
                }
            }
            // bonificación / recargo de pie, sobre los totales de los renglones
            if (com != null) {
                OptimistCalculo.Resultado r = OptimistCalculo.calcular(t, cabV, lineasV, ab);
                Map<String, Object> conTotales = new LinkedHashMap<>(cabV);
                conTotales.putAll(r.cabecera());
                cabV.putAll(com.pie(conTotales, dec, vars));
            }
            // V49d: las reglas de los detalles que usan totales de la cabecera (la base imponible de cada impuesto:
            // PEDImpuestosBaseImponible = PEDSubTotal If ...) corren de nuevo con los totales ya calculados. En la pasada de
            // arriba los totales todavía no estaban (el pedido generado desde el presupuesto quedaba con los impuestos en 0).
            if (!rs.vacio()) {
                Set<String> totales = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (OptimistTrnDefinicion.Campo k : cabN.campos()) if (k.formula() != null) totales.add(k.nombre());
                Map<String, Object> antes = null;
                for (int vuelta = 0; vuelta < 2; vuelta++) {
                    Map<String, Object> calc = OptimistCalculo.calcular(t, cabV, lineasV, ab).cabecera();
                    if (antes != null && sinCambios(antes, calc)) break;
                    antes = calc;
                    cabV.putAll(calc);
                    boolean corrio = false;
                    for (var d : dets.entrySet()) {
                        OptimistTrnDefinicion.Nivel det = t.detalles().stream().filter(x -> x.nombre().equalsIgnoreCase(d.getKey())).findFirst().orElse(null);
                        if (det == null) continue;
                        Set<String> cambios = rs.cambiosParaLineas(det, totales);
                        if (cambios.isEmpty()) continue;
                        List<Map<String, Object>> ls = lineasV.get(d.getKey());
                        for (int i = 0; i < ls.size() && i < d.getValue().size(); i++) {
                            Map<String, Object> lv = ls.get(i);
                            Set<String> cs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                            cs.addAll(cambios);
                            OptimistReglas.Efecto e = rs.aplicar(OptimistReglas.Momento.CAMBIO, null, det, lv, cabV, null, cs, est, ab, c, true);
                            for (var a : e.asignados().entrySet()) if (det.campo(a.getKey()) != null) lv.put(a.getKey(), a.getValue());
                            for (var x : lv.entrySet()) {
                                OptimistTrnDefinicion.Campo k = det.campo(x.getKey());
                                if (k != null && !k.inferido()) d.getValue().get(i).valores().put(k.nombre(), x.getValue());
                            }
                            corrio = true;
                        }
                    }
                    if (!corrio) break;
                }
            }
            for (var x : cabV.entrySet()) {
                OptimistTrnDefinicion.Campo k = cabN.campo(x.getKey());
                if (k != null && !k.inferido()) { cab.put(k.nombre(), x.getValue()); put(k.nombre(), x.getValue()); }
            }
            // V60b: duplicación (Clone): lo que el procedimiento no tocó vuelve al valor del original (reglas, cálculo)
            if (protegidosCab != null)
                for (var x : protegidosCab.entrySet()) { cab.put(x.getKey(), x.getValue()); put(x.getKey(), x.getValue()); }
            if (protegidosLinea != null)
                for (var x : protegidosLinea.entrySet()) x.getKey().valores().putAll(x.getValue());
            for (String a : avisos) System.out.println("Business Component " + trn + ": " + a);
            for (String x : errores) System.out.println("Business Component " + trn + " (se controla al confirmar): " + x);
        }

        private static boolean sinCambios(Map<String, Object> a, Map<String, Object> b) {
            for (var e : b.entrySet()) {
                Object x = a.get(e.getKey()), y = e.getValue();
                if (java.util.Objects.equals(x, y)) continue;
                try { if (x != null && y != null && OptimistFormula.num(x).compareTo(OptimistFormula.num(y)) == 0) continue; }
                catch (RuntimeException ignorado) { }
                return false;
            }
            return true;
        }

        private static Set<String> editables(OptimistTrnDefinicion.Nivel n) {
            Set<String> r = new HashSet<>();
            for (OptimistTrnDefinicion.Campo k : n.campos()) if (k.editable()) r.add(k.nombre());
            return r;
        }

        /**
         * V48g: Clone() de un Business Component (ClonarPRS: Duplicar Presupuesto). Copia la cabecera y cada renglón de
         * cada nivel; la copia es NUEVA (Save() inserta, los renglones también): no queda atada al registro leído con Load.
         * Antes Clone() devolvía una estructura común: al asignarla a una variable Bc se perdían los renglones.
         */
        Bc clonar() {
            Bc n = new Bc(nivel == null ? trn : trn + "." + nivel);
            for (Map.Entry<String, Object> e : entrySet()) {
                Object v = e.getValue();
                if (v instanceof List<?> l) {
                    List<Object> copia = new ArrayList<>();
                    for (Object o : l) copia.add(o instanceof Bc b ? b.clonar() : o instanceof Map<?, ?> m ? new Sdt(castear(m)) : o);
                    n.put(e.getKey(), copia);
                } else n.put(e.getKey(), v);
            }
            n.cargado = false; n.claveOriginal = null; n.exito = true;
            n.original = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);   // V60b
            for (Map.Entry<String, Object> e : n.entrySet()) if (!(e.getValue() instanceof List<?>)) n.original.put(e.getKey(), e.getValue());
            return n;
        }

        /** V60b: los campos de 'actual' que siguen igual que en el original (con el valor del original). */
        private static Map<String, Object> sinTocar(Map<String, Object> original, Map<String, Object> actual) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : actual.entrySet()) {
                if (!original.containsKey(e.getKey())) continue;
                Object o = original.get(e.getKey()), a = e.getValue();
                boolean igual = (o == null && a == null) || (o != null && a != null && OptimistTrnDatos.igual(o, a));
                if (igual) r.put(e.getKey(), o);
            }
            return r;
        }

        List<Object> mensajesGx() {
            List<Object> r = new ArrayList<>();
            for (String m : mensajes) {
                Sdt x = new Sdt();
                x.put("Id", "Error"); x.put("Type", BigDecimal.ONE); x.put("Description", m);
                r.add(x);
            }
            return r;
        }
    }

    /** Estado de una ejecución: variables, filas de los For Each abiertos, mensajes. */
    static final class Ejecucion implements OptimistFormula.Contexto {
        final OptimistProcedimiento proc;
        final Connection conexion;
        final java.util.function.Function<String, OptimistProcedimiento> catalogo;
        final Map<String, Object> vars = new HashMap<>();
        final Deque<Map<String, Object>> filas = new ArrayDeque<>();
        Map<String, Object> ultimoNew = Map.of();   // V48: valores del último New, para leerlos después del EndNew
        OptimistImpresion impresion;                // V49: la hoja, si el procedimiento imprime (tiene impresiones/<Nombre>.yaml)
        final List<String> mensajes;
        final int profundidad;

        Ejecucion(OptimistProcedimiento proc, Connection c, java.util.function.Function<String, OptimistProcedimiento> catalogo,
                  List<String> mensajes, int profundidad) {
            this.proc = proc; this.conexion = c; this.catalogo = catalogo; this.mensajes = mensajes; this.profundidad = profundidad;
        }

        void ejecutar(List<Sentencia> ss) throws SQLException { for (Sentencia s : ss) s.ejecutar(this); }

        Object evaluar(OptimistFormula f) { return f.evaluar(this); }

        /** V82k: el largo declarado de una &variable numérica (o el del atributo), para X.ToString(). */
        @Override public int[] tamanio(String nombre) {
            if (nombre != null && nombre.toLowerCase(Locale.ROOT).matches("&wwpcontext\\.(empresa|division|empstk|divstk|emplst|divlst|empcst|divcst)"))
                return new int[]{2, 0};   // el WWPContext de GeneXus: empresa y división son Numeric(2)
            if (nombre != null && nombre.startsWith("&")) {
                Variable v = proc.variables.get(clave(nombre));
                return v != null && "numeric".equals(v.tipo()) ? new int[]{v.longitud(), v.decimales()} : null;
            }
            return OptimistFormula.Contexto.super.tamanio(nombre);
        }

        Object valorVariable(String var) {
            String[] partes = var.split("\\.");
            String k = clave(partes[0]);
            Object v;
            if (vars.containsKey(k)) v = vars.get(k);
            else if (proc.variables.get(k) == null && k.equals("pgmname")) v = proc.nombre;   // V66x8: &Pgmname = el procedimiento (ej. CostoArtProgramName)
            else if (proc.variables.get(k) == null && sistema(k) != null) v = sistema(k);   // V50r: &Today, &Time, &Now
            else { v = vacio(proc.variables.get(k)); if (v instanceof Sdt || v instanceof List) vars.put(k, v); }
            for (int i = 1; i < partes.length; i++) v = campo(v, partes[i]);
            return v;
        }

        /** V50r: variables de sistema de GeneXus que no se declaran: &Today (fecha), &Time ("hh:mm:ss"), &Now (fecha y hora). */
        private static Object sistema(String k) {
            return switch (k) {
                case "today" -> LocalDate.now();
                case "time" -> java.time.LocalTime.now().withNano(0).toString();
                case "now" -> LocalDateTime.now().withNano(0);
                default -> null;
            };
        }

        private static Object campo(Object v, String nombre) {
            if (v instanceof List<?> l && nombre.equalsIgnoreCase("count")) return BigDecimal.valueOf(l.size());
            if (v instanceof Map<?, ?> m) {
                for (var e : m.entrySet()) if (String.valueOf(e.getKey()).equalsIgnoreCase(nombre)) return e.getValue();
                return null;
            }
            return null;
        }

        private static final Set<String> PROPIEDADES_PANTALLA = Set.of("visible", "enabled", "class", "caption", "tooltiptext",
                "title", "forecolor", "backcolor", "fontbold", "readonly");

        void asignar(String var, Object v) {
            String[] partes = var.split("\\.");
            String k = clave(partes[0]);
            if (partes.length == 1) { vars.put(k, tipar(proc.variables.get(k), v)); return; }
            Object obj = valorVariable(partes[0]);
            // V50y: &Variable.Visible / .Enabled / .Caption... (propiedades de pantalla en el inicio de una TRN) no son de un Sdt:
            // se ignoran. Antes cortaban el inicio (ej. &AlmacenesDescrip.Visible = false en el PED) y el Save del BC fallaba.
            if (partes.length == 2 && !(obj instanceof Map) && PROPIEDADES_PANTALLA.contains(partes[1].toLowerCase(Locale.ROOT))) return;
            if (obj == null) { obj = new Sdt(); vars.put(k, obj); }
            for (int i = 1; i < partes.length - 1; i++) {
                Object hijo = campo(obj, partes[i]);
                if (hijo == null && obj instanceof Map) { hijo = new Sdt(); poner(obj, partes[i], hijo); }
                obj = hijo;
            }
            if (!(obj instanceof Map)) throw new IllegalStateException(proc.nombre + ": " + var + ": " + partes[0] + " no es una estructura (Sdt)");
            poner(obj, partes[partes.length - 1], v);
        }

        /** V61e: &Coleccion.Item(n).Campo[.Sub...] = v (op null = SetEmpty, "=", "+=" o "-="). n empieza en 1, como en GeneXus. */
        void asignarItem(String coleccion, int n, String camino, Object v, String op, String donde) {
            String[] partes = coleccion.split("\\.");
            Object obj = valorVariable(partes[0]);
            for (int i = 1; i < partes.length && obj != null; i++) obj = campo(obj, partes[i]);
            if (!(obj instanceof List<?> lista)) throw new IllegalStateException(donde + ": " + coleccion + " no es una colección");
            if (n < 1 || n > lista.size()) throw new IllegalStateException(donde + ": Item(" + n + ") fuera de rango (hay " + lista.size() + ")");
            Object item = lista.get(n - 1);
            String[] cs = camino.split("\\.");
            for (int i = 0; i < cs.length - 1; i++) {
                Object hijo = campo(item, cs[i]);
                if (hijo == null && item instanceof Map) { hijo = new Sdt(); poner(item, cs[i], hijo); }
                item = hijo;
            }
            if (!(item instanceof Map)) throw new IllegalStateException(donde + ": el ítem no es una estructura");
            String ultimo = cs[cs.length - 1];
            Object nuevo = v;
            if ("+=".equals(op) || "-=".equals(op)) {
                Object actual = campo(item, ultimo);
                if ("+=".equals(op) && (actual instanceof String || (v instanceof String sv && !sv.trim().matches("-?\\d+(\\.\\d+)?"))))
                    nuevo = (actual == null ? "" : String.valueOf(actual)) + (v == null ? "" : String.valueOf(v));
                else nuevo = "+=".equals(op) ? OptimistFormula.num(actual).add(OptimistFormula.num(v)) : OptimistFormula.num(actual).subtract(OptimistFormula.num(v));
            } else if (op == null) {
                // SetEmpty: el vacío según lo que tenga hoy el campo (0 en números, "" en textos, null en fechas, false en lógicos)
                Object actual = campo(item, ultimo);
                nuevo = actual instanceof BigDecimal || actual instanceof Number ? BigDecimal.ZERO
                        : actual instanceof String ? "" : actual instanceof Boolean ? Boolean.FALSE : null;
            }
            poner(item, ultimo, nuevo);
        }

        @SuppressWarnings("unchecked")
        private static void poner(Object mapa, String campo, Object v) {
            Map<String, Object> m = (Map<String, Object>) mapa;
            String existente = null;
            for (String x : m.keySet()) if (x.equalsIgnoreCase(campo)) existente = x;
            m.put(existente == null ? campo : existente, v);
        }

        void asignarAtributo(String atributo, Object v, String donde) {
            String k = atributo.toLowerCase(Locale.ROOT);
            for (Map<String, Object> f : filas) {
                if (f instanceof FilaNueva n) { n.put(k, v); return; }
                if (f instanceof FilaBD b && b.tabla.columnas().containsKey(k)) { b.cambios.put(k, v); b.put(k, v); return; }
                if (f instanceof FilaBD b && b.sinColumna.contains(k)) { b.put(k, v); return; }   // V48c: la base no lo tiene
                if (f instanceof FilaBD b)   // V48c: atributo de una tabla de la extendida
                    for (FilaBD x : b.extendidas) if (x.tabla.columnas().containsKey(k)) { x.cambios.put(k, v); x.put(k, v); b.put(k, v); return; }
            }
            throw new IllegalStateException(donde + ": no se puede asignar " + atributo + " (no es de la tabla del For Each)");
        }

        void borrarFilaActual(String donde) {
            for (Map<String, Object> f : filas) if (f instanceof FilaBD b) { b.borrar = true; return; }
            throw new IllegalStateException(donde + ": Delete fuera de un For Each");
        }

        boolean enFilaExterna(String atributo) {
            String k = atributo.toLowerCase(Locale.ROOT);
            for (Map<String, Object> f : filas) if (f.containsKey(k)) return true;
            return false;
        }

        /** V66x7: el atributo está en un For Each que contiene a este como está escrito (no en el que llamó a la Sub). */
        boolean enFilaAnidada(String atributo) {
            String k = atributo.toLowerCase(Locale.ROOT);
            for (Map<String, Object> f : filas) {
                if (f instanceof FronteraSub) return false;
                if (f.containsKey(k)) return true;
            }
            return false;
        }

        Object valorFilaExterna(String atributo) {
            String k = atributo.toLowerCase(Locale.ROOT);
            for (Map<String, Object> f : filas) if (f.containsKey(k)) return f.get(k);
            return ultimoNew.get(k);
        }

        @Override public Object valor(String atributo) {
            if (atributo.startsWith("&")) return valorVariable(atributo);
            Object modo = trnMode(atributo);   // V50m
            if (modo != null) return modo;
            return valorFilaExterna(atributo);
        }

        @Override public Object procedimiento(String nombre, List<Object> args) {
            if (!args.isEmpty() && args.get(0) instanceof Bc bc) {   // V48: métodos del Business Component
                switch (nombre.toLowerCase(Locale.ROOT)) {
                    case ".load": bc.load(conexion, args.subList(1, args.size())); return null;
                    case ".save": bc.save(conexion); return null;
                    // V66: Insert() / Update() / InsertOrUpdate(), como en GeneXus: graban y devuelven si anduvo
                    case ".insert":
                        if (bc.cargado) { bc.exito = false; bc.mensajes.clear(); bc.mensajes.add("Insert(): el registro ya existe (se cargó con Load)"); return false; }
                        bc.save(conexion); return bc.exito;
                    case ".update":
                        if (!bc.cargado) { bc.exito = false; bc.mensajes.clear(); bc.mensajes.add("Update(): primero hay que cargar el registro con Load"); return false; }
                        bc.save(conexion); return bc.exito;
                    case ".insertorupdate": bc.save(conexion); return bc.exito;
                    case ".success": return bc.exito;
                    case ".fail": return !bc.exito;
                    case ".getmessages": return bc.mensajesGx();
                    case ".check": return true;
                    case ".clone": return bc.clonar();   // V48g
                    case ".delete": bc.delete(conexion); return null;   // V50m
                    default: break;
                }
            }
            Object b = Funciones.llamar(nombre, args);
            if (b != Funciones.NO_ES) return b;
            if (nombre.startsWith(".")) throw new IllegalStateException(proc.nombre + ": el método " + nombre.substring(1) + "() todavía no está soportado");
            try {
                Map<String, Object> salida = llamar(nombre, args);
                OptimistProcedimiento otro = buscar(nombre);
                for (int k = otro.parametros.size() - 1; k >= 0; k--)
                    if (!otro.parametros.get(k).modo().equals("in")) return salida.get(otro.parametros.get(k).nombre().toLowerCase(Locale.ROOT));
                return null;
            } catch (SQLException ex) { throw new IllegalStateException(ex.getMessage(), ex); }
        }

        OptimistProcedimiento buscar(String nombre) {
            OptimistProcedimiento n = nativo(nombre);
            OptimistProcedimiento otro = n != null ? n : catalogo.apply(nombre);
            if (otro == null) throw new IllegalStateException(proc.nombre + ": no existe el procedimiento " + nombre + " (falta proc/" + nombre + ".proc)");
            return otro;
        }

        Map<String, Object> llamar(String nombre, List<Object> args) throws SQLException {
            OptimistProcedimiento otro = buscar(nombre);
            if (profundidad > 40) throw new IllegalStateException(proc.nombre + ": demasiadas llamadas anidadas (¿se llama a sí mismo?)");
            return otro.ejecutar(args, conexion, catalogo, mensajes, profundidad + 1);
        }
    }

    /**
     * V48: procedimientos de solo lectura de configuración (parámetros globales, tipos de comprobante, tasas de IVA, tipo de
     * cambio): el resultado se recuerda MEMORIA_MS por base y argumentos. GeneXus los relee en cada llamada; acá, con la base
     * en la nube, eran decenas de viajes por cada cambio de un renglón.
     */
    static volatile java.util.function.Predicate<String> memorizable = n -> {
        String x = n.toLowerCase(Locale.ROOT);
        return x.startsWith("getparamglb") || x.equals("gettipocambio") || x.startsWith("gettcbte") || x.startsWith("checktcbte")
                || x.equals("getartivaid") || x.equals("getartivatasa")
                // V48: lecturas del artículo y del cliente que se repiten en cada renglón y en cada recálculo de las fórmulas
                // (GetArtCostoCPBC tardaba ~450 ms por artículo y se calculaba 4 veces por cambio)
                || x.startsWith("getartcosto") || x.equals("getartrenta") || x.equals("getartid") || x.equals("getartprecioxcantidad")
                || x.equals("getentiidpk") || x.equals("getarticulousarpreciode") || x.equals("getarticulosformadecomercializacion")
                || x.equals("getarticulohabilitadoventa") || x.equals("getentilistaprecioscondpago") || x.equals("getentitipoentidadstock")
                || x.equals("getentiporbonporrec") || x.startsWith("getstockarticulo")
                || OptimistProcedimiento.porUsuario.test(x);
    };
    /**
     * V49y: configuración que depende del usuario o de su empresa (procesos habilitados, VERCampo, ver la renta, la
     * rentabilidad de la empresa): se recuerda por usuario + empresa + división. Con los parámetros globales y los tipos de
     * comprobante se recuerdan CONFIG_MS (10 minutos) o hasta que se graba una de sus tablas: abrir un presupuesto hacía
     * 45 llamadas de configuración.
     */
    static volatile java.util.function.Predicate<String> porUsuario = x ->
            x.equals("getusrproc") || x.equals("vercampo") || x.equals("getsecuserverrenta") || x.equals("getempresarentabilidad");
    /** V49y: usuario, empresa y división de la sesión (lo pone OptimistProcedimientos; sin sesión, vacío). */
    static volatile java.util.function.Supplier<List<Object>> claveUsuario = List::of;
    static boolean deConfiguracion(String nombre) {
        String x = nombre.toLowerCase(Locale.ROOT);
        return porUsuario.test(x) || x.startsWith("getparamglb") || x.startsWith("gettcbte") || x.startsWith("checktcbte");
    }
    static final long CONFIG_MS = 600_000;
    /** Cuánto se recuerda: el stock, poco (10 s); el resto, MEMORIA_MS. */
    static long memoriaMs(String nombre) {
        return nombre.toLowerCase(Locale.ROOT).startsWith("getstock") ? 10_000 : deConfiguracion(nombre) ? CONFIG_MS : MEMORIA_MS;
    }
    static final long MEMORIA_MS = 60_000;
    private record Recordado(long vence, Map<String, Object> salida) {}
    private static final Map<List<Object>, Recordado> MEMORIA = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * V66z: lo recordado de los procedimientos que leen DATOS (costos, artículos, entidades, stock), aparte de la configuración:
     * se olvida apenas algo graba en la base. Antes se recordaba 60 s aunque se grabara: después de PutArtCosto, GetArtCosto
     * seguía dando el costo viejo (ej. Actualización de Costos por Coeficiente dos veces seguidas no acumulaba; una fórmula que
     * usa un semielaborado recién recalculado podía tomar su costo anterior).
     */
    private static final Map<List<Object>, Recordado> MEMORIA_DATOS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Olvida lo recordado (por ejemplo, después de cambiar un parámetro). */
    public static void olvidarMemoria() { olvidarDelCliente(MEMORIA); olvidarDelCliente(MEMORIA_DATOS); }   // V71b: solo el cliente actual

    /** V66z: algo grabó en la base: lo recordado de los procedimientos de datos ya no vale (la configuración sí). */
    static void olvidarDatos() { if (!MEMORIA_DATOS.isEmpty()) olvidarDelCliente(MEMORIA_DATOS); }

    /** V71b: borra lo recordado del cliente actual (sin instalación única, todo: el prefijo es el mismo para todas las claves). */
    private static void olvidarDelCliente(Map<List<Object>, Recordado> mapa) {
        String p = prefijoCliente();
        if ("|".equals(p)) { mapa.clear(); return; }
        mapa.keySet().removeIf(k -> !k.isEmpty() && k.get(0) instanceof String s && s.startsWith(p));
    }

    private static Object claveMemoria(Object v) {
        if (v instanceof BigDecimal b) return b.signum() == 0 ? BigDecimal.ZERO : b.stripTrailingZeros();
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros();
        return v;
    }

    /** Ejecuta con los valores de los parámetros (en orden). Devuelve los out/inout (nombre en minúscula). */
    public Map<String, Object> ejecutar(List<Object> args, Connection c,
                                        java.util.function.Function<String, OptimistProcedimiento> catalogo,
                                        List<String> mensajes, int profundidad) throws SQLException {
        if (!DEPURAR) return ejecutar1(args, c, catalogo, mensajes, profundidad);
        // V48: OPTIMIST_DEPURAR=1: cada procedimiento que corre, con su tiempo (los anidados, con sangría)
        long t0 = System.nanoTime();
        boolean[] mem = {false};
        String falla = null;
        try { return ejecutar1(args, c, catalogo, mensajes, profundidad, mem); }
        catch (SQLException | RuntimeException ex) { falla = ex.getMessage() == null ? ex.toString() : ex.getMessage(); throw ex; }
        finally {
            StringBuilder a = new StringBuilder();
            for (Object x : args) { if (a.length() > 0) a.append(", "); String t = x instanceof Map || x instanceof List ? "{..}" : String.valueOf(x); a.append(t.length() > 20 ? t.substring(0, 20) + "…" : t); }
            System.out.println("  ".repeat(profundidad) + "proc " + nombre + "(" + a + ") " + (System.nanoTime() - t0) / 1_000_000 + " ms"
                    + (mem[0] ? " [memoria]" : nativo != null ? " [Java]" : "") + (falla == null ? "" : "  *** ERROR: " + falla));
        }
    }

    static final boolean DEPURAR = Boolean.getBoolean("optimist.reglas.depurar") || "1".equals(System.getenv("OPTIMIST_DEPURAR"));

    private Map<String, Object> ejecutar1(List<Object> args, Connection c,
                                          java.util.function.Function<String, OptimistProcedimiento> catalogo,
                                          List<String> mensajes, int profundidad) throws SQLException {
        return ejecutar1(args, c, catalogo, mensajes, profundidad, new boolean[1]);
    }

    private Map<String, Object> ejecutar1(List<Object> args, Connection c,
                                          java.util.function.Function<String, OptimistProcedimiento> catalogo,
                                          List<String> mensajes, int profundidad, boolean[] mem) throws SQLException {
        if (memorizable.test(nombre) && args.stream().noneMatch(x -> x instanceof Map || x instanceof List)) {
            List<Object> k = new ArrayList<>();
            k.add(claveBase(c)); k.add(nombre.toLowerCase(Locale.ROOT));   // V71b: cliente + base
            if (porUsuario.test(nombre.toLowerCase(Locale.ROOT))) k.add(claveUsuario.get());   // V49y
            for (Object x : args) k.add(claveMemoria(x));
            Map<List<Object>, Recordado> mapa = deConfiguracion(nombre) ? MEMORIA : MEMORIA_DATOS;   // V66z
            Recordado r = mapa.get(k);
            long ahora = System.currentTimeMillis();
            if (r != null && r.vence() > ahora) { mem[0] = true; return new LinkedHashMap<>(r.salida()); }
            Map<String, Object> salida = ejecutar0(args, c, catalogo, mensajes, profundidad);
            if (mapa.size() > 5000) mapa.clear();
            mapa.put(k, new Recordado(ahora + memoriaMs(nombre), new LinkedHashMap<>(salida)));
            return salida;
        }
        return ejecutar0(args, c, catalogo, mensajes, profundidad);
    }

    private Map<String, Object> ejecutar0(List<Object> args, Connection c,
                                          java.util.function.Function<String, OptimistProcedimiento> catalogo,
                                          List<String> mensajes, int profundidad) throws SQLException {
        if (nativo != null) {
            Map<String, Object> entrada = new LinkedHashMap<>();
            for (int k = 0; k < parametros.size(); k++) entrada.put(parametros.get(k).nombre().toLowerCase(Locale.ROOT), k < args.size() ? args.get(k) : null);
            Map<String, Object> r = nativo.ejecutar(entrada, c);
            return r == null ? Map.of() : r;
        }
        Ejecucion e = new Ejecucion(this, c, catalogo, mensajes, profundidad);
        FilaParm fp = new FilaParm();
        for (int k = 0; k < parametros.size(); k++) {
            Parametro p = parametros.get(k);
            Object v = k < args.size() ? args.get(k) : null;
            if (p.atributo()) { if (!p.modo().equals("out")) fp.put(p.nombre().toLowerCase(Locale.ROOT), v); }
            // V66j: como en GeneXus, un parámetro out NO recibe el valor de quien llama: empieza vacío. Antes lo recibía, y un
            // procedimiento que no lo asignaba devolvía lo que ya tenía la variable de afuera (en el Pasaje Contable,
            // CumpleCondicionVentas repetía el importe de la cabecera en cada renglón y GetTipoAsientoTcbte devolvía el tipo de
            // asiento del comprobante anterior); los que acumulan (&Total += ...) arrancaban del valor de afuera.
            else if (!p.modo().equals("out")) e.asignar("&" + p.nombre(), v);
        }
        if (!fp.isEmpty()) e.filas.push(fp);
        OptimistImpresion.Diseno dis = disenoDe.apply(nombre);
        if (dis != null) e.impresion = new OptimistImpresion(dis, cargarImagen);   // V49: es un reporte
        try { e.ejecutar(cuerpo); }
        catch (Retornar r) { if (profundidad == 0) RETORNO.set(true); /* Return; V49f: el panel se cierra */ }
        catch (Salir s) { /* Exit fuera de un ciclo = termina */ }
        if (e.impresion != null) {
            byte[] pdf = e.impresion.terminar();
            // V66t: un reporte que no imprimió nada (ej. el Excel de ListarStock / ProcSaldosCtasCtes) no deja un PDF en blanco
            if (e.impresion.imprimio())
                PDFS.get().add(new Object[]{e.impresion.archivo() == null || e.impresion.archivo().isBlank() ? nombre : e.impresion.archivo(), pdf});
            for (String a : e.impresion.avisos()) System.out.println("[" + nombre + "] " + a);
        }
        Map<String, Object> salida = new LinkedHashMap<>();
        for (Parametro p : parametros) {
            if (p.modo().equals("in")) continue;
            salida.put(p.nombre().toLowerCase(Locale.ROOT), p.atributo() ? e.valorFilaExterna(p.nombre()) : e.valorVariable("&" + p.nombre()));
        }
        return salida;
    }

    /** Valores del registro abierto en el formulario (se leen como atributos; no filtran los For Each por sí mismos). */
    static final class FilaLectura extends HashMap<String, Object> {}

    /**
     * V35: ejecuta el cuerpo (el 'inicio' de una TRN) con las variables del formulario, que se leen antes y se
     * actualizan después, y los valores del registro a la vista como atributos.
     */
    public void ejecutarCon(Map<String, Object> variables, Map<String, Object> registro, Connection c,
                            java.util.function.Function<String, OptimistProcedimiento> catalogo, List<String> mensajes) throws SQLException {
        Ejecucion e = new Ejecucion(this, c, catalogo, mensajes, 0);
        variables.forEach((k, v) -> e.vars.put(clave(k), v));
        if (registro != null && !registro.isEmpty()) {
            FilaLectura f = new FilaLectura();
            registro.forEach((k, v) -> { if (k != null) f.put(k.toLowerCase(Locale.ROOT), v); });
            e.filas.push(f);
        }
        // cada sentencia por separado: si una falla (ej. falta un procedimiento), las demás igual se ejecutan
        try {
            for (Sentencia s : cuerpo) {
                try { s.ejecutar(e); }
                catch (Retornar | Salir r) { throw r; }
                catch (SQLException | RuntimeException ex) { mensajes.add(nombre + ": " + ex.getMessage()); }
            }
        } catch (Retornar | Salir r) { /* Return / Exit */ }
        variables.putAll(e.vars);
    }

    /** Definición de una &variable (del bloque Variables), o null. */
    public Variable variable(String nombre) { return variables.get(clave(nombre)); }

    // ------------------------------------------------------------------ tipos de las variables

    static Object vacio(Variable d) {
        if (d == null) return null;
        return switch (d.tipo()) {
            case "numeric" -> BigDecimal.ZERO;
            case "boolean" -> Boolean.FALSE;
            case "character", "varchar" -> "";
            case "sdt" -> new Sdt();
            case "coleccion" -> new ArrayList<>();
            case "ext" -> switch (d.estructura() == null ? "" : d.estructura().toLowerCase(Locale.ROOT)) {
                case "websession" -> new SesionWeb();
                case "smtpsession" -> new SmtpSesion();       // V49f
                case "mailmessage" -> new MailMensaje();
                case "mailrecipient" -> new MailDestinatario();
                case "exceldocument" -> new PlanillaExcel();   // V50b
                default -> null;
            };
            case "bc" -> new Bc(d.estructura());
            default -> null;
        };
    }

    /**
     * V48: &WebSession (Ext(WebSession) de GeneXus): Set / Get / Remove / Clear en la sesión del usuario. Sin sesión (una
     * tarea programada, las pruebas), en memoria de la ejecución. GetArtCostoCPBC guarda ahí el origen del costo.
     */
    static final class SesionWeb {
        private static final Map<String, Object> local = new java.util.concurrent.ConcurrentHashMap<>();
        private static final String PREFIJO = "websession.";
        Object get(String k) {
            OptimistContexto c = OptimistContexto.actual();
            Object v = c != null ? c.valor(PREFIJO + k) : local.get(k);
            return v == null ? "" : v;
        }
        void set(String k, Object v) {
            OptimistContexto c = OptimistContexto.actual();
            if (c != null) c.poner(PREFIJO + k, v == null ? "" : String.valueOf(v)); else local.put(k, v == null ? "" : String.valueOf(v));
        }
    }

    /**
     * V50m: TrnMode.Insert / Update / Delete / Display valen "INS" / "UPD" / "DLT" / "DSP", como &Mode en las reglas de una TRN
     * y en las condiciones de las acciones del WorkWith (ej. PEDVerificarBajaCambio: Case &TrnMode = TrnMode.Update).
     * Antes no se reconocían dentro de un procedimiento: la comparación nunca daba verdadero. null si no es TrnMode.
     */
    static String trnMode(String nombre) {
        if (nombre == null || !nombre.regionMatches(true, 0, "trnmode.", 0, 8)) return null;
        return switch (nombre.substring(8).toLowerCase(Locale.ROOT)) {
            case "insert" -> "INS"; case "update" -> "UPD"; case "delete" -> "DLT"; case "display" -> "DSP"; default -> null;
        };
    }

    /** Como GeneXus: el número se trunca a los decimales de la variable; el texto, a su largo. */
    static Object tipar(Variable d, Object v) {
        if (d == null) return v;
        switch (d.tipo()) {
            case "numeric": {
                BigDecimal b = OptimistFormula.num(v);
                return b.setScale(d.decimales(), RoundingMode.DOWN);
            }
            case "boolean": return OptimistFormula.verdad(v);
            case "character": case "varchar": {
                String s = v == null ? "" : v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
                return d.longitud() > 0 && s.length() > d.longitud() ? s.substring(0, d.longitud()) : s;
            }
            // V84: una fecha que llega como texto (ej. de un FromJson: "2026-10-01") se pasa a fecha; antes quedaba texto
            // (el ToFormattedString daba 2026-10-01 en vez de 01/10/2026)
            case "date": {
                if (v instanceof LocalDateTime t) return t.toLocalDate();
                if (v instanceof String s) {
                    String x = s.trim();
                    if (x.isEmpty()) return null;
                    if (x.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                        try { return LocalDate.parse(x.substring(0, 10)); } catch (RuntimeException ex) { return v; }
                    }
                }
                return v;
            }
            case "sdt": return v instanceof Sdt ? v : v instanceof Map<?, ?> m ? new Sdt(castear(m)) : v == null ? new Sdt() : v;
            // V48g: &Coleccion = New() daba una estructura vacía y no una lista ("no es una colección" en el For &x in)
            case "coleccion": return v == null || (v instanceof Map<?, ?> m && m.isEmpty()) ? new ArrayList<>() : v;
            case "bc": {   // V48: &PED = New() -> Business Component vacío (con sus niveles)
                if (v instanceof Bc) return v;
                Bc b = new Bc(d.estructura());
                if (v instanceof Map<?, ?> m) for (var e : m.entrySet()) if (!(e.getValue() instanceof List) || !b.containsKey(String.valueOf(e.getKey()))) b.put(String.valueOf(e.getKey()), e.getValue());
                return b;
            }
            default: return v;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> castear(Map<?, ?> m) { return (Map<String, ?>) m; }

    // ------------------------------------------------------------------ funciones de GeneXus

    static final class Funciones {
        /** V49: como GXMLines / GXGetMLi: el texto en renglones de hasta 'ancho' caracteres (corta en los espacios y en los Enter). */
        static List<String> renglones(String texto, int ancho) {
            List<String> r = new ArrayList<>();
            if (texto == null || texto.isEmpty()) return r;
            int w = Math.max(1, ancho);
            for (String parrafo : texto.replace("\r", "").split("\n", -1)) {
                String p = parrafo.stripTrailing();
                if (p.isEmpty()) { r.add(""); continue; }
                while (p.length() > w) {
                    int corte = p.lastIndexOf(' ', w);
                    if (corte <= 0) corte = w;
                    r.add(p.substring(0, corte).stripTrailing());
                    p = p.substring(corte).stripLeading();
                }
                r.add(p);
            }
            while (!r.isEmpty() && r.get(r.size() - 1).isEmpty()) r.remove(r.size() - 1);
            return r;
        }

        static final Object NO_ES = new Object();

        /** V49f: ToJson() de una estructura, colección o valor (las contraseñas, si las hay, se tapan). */
        static String json(Object v) {
            if (v == null) return "null";
            if (v instanceof Map<?, ?> m) {
                StringJoiner j = new StringJoiner(",", "{", "}");
                for (var e : m.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    Object x = k.toLowerCase(Locale.ROOT).contains("passw") || k.equalsIgnoreCase("password") ? "***" : e.getValue();
                    j.add(json(k) + ":" + json(x));
                }
                return j.toString();
            }
            if (v instanceof Collection<?> c) { StringJoiner j = new StringJoiner(",", "[", "]"); for (Object x : c) j.add(json(x)); return j.toString(); }
            if (v instanceof Boolean || v instanceof Number) return v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
            StringBuilder b = new StringBuilder("\"");
            for (char ch : String.valueOf(v).toCharArray()) {
                switch (ch) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> { if (ch < 32) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
                }
            }
            return b.append('"').toString();
        }

        /** V49s: una estructura o colección a XML; cada valor lleva su tipo (t=) para que FromXml lo devuelva igual. */
        static String xml(Object v) {
            StringBuilder b = new StringBuilder();
            xml(b, v instanceof List<?> ? "Coleccion" : "Sdt", v);
            return b.toString();
        }

        private static void xml(StringBuilder b, String etiqueta, Object v) {
            String e = etiqueta.replaceAll("[^A-Za-z0-9_]", "_");
            if (v instanceof Map<?, ?> m) {
                b.append('<').append(e).append(" t=\"r\">");
                for (Map.Entry<?, ?> x : m.entrySet()) xml(b, String.valueOf(x.getKey()), x.getValue());
                b.append("</").append(e).append('>');
                return;
            }
            if (v instanceof Collection<?> c) {
                b.append('<').append(e).append(" t=\"c\">");
                for (Object x : c) xml(b, "Item", x);
                b.append("</").append(e).append('>');
                return;
            }
            String t = v == null ? "z" : v instanceof Number ? "n" : v instanceof LocalDateTime ? "h" : v instanceof LocalDate ? "d" : v instanceof Boolean ? "b" : "s";
            String texto = v == null ? "" : v instanceof BigDecimal d ? d.toPlainString() : String.valueOf(v);
            b.append('<').append(e).append(" t=\"").append(t).append("\">")
             .append(texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
             .append("</").append(e).append('>');
        }

        /** V49s: &Var.FromXml(&Texto): llena la colección (o la estructura) con lo que dejó ToXml. Texto vacío = la deja vacía. */
        /**
         * V66: &Sdt.FromJson(&Texto) / &Coleccion.FromJson(&Texto): carga lo que dice el JSON (objetos -> Sdt, listas ->
         * colección, números -> BigDecimal). Como GeneXus: lo que había se reemplaza; un texto vacío deja todo vacío.
         */
        @SuppressWarnings("unchecked")
        static void desdeJson(Object destino, String texto) {
            if (destino instanceof List<?> l) l.clear(); else if (destino instanceof Map<?, ?> m) m.clear(); else return;
            if (texto == null || texto.isBlank()) return;
            Object v;
            try { v = new LectorJson(texto).todo(); }
            catch (RuntimeException ex) { throw new IllegalStateException("FromJson: el texto no es un JSON válido (" + ex.getMessage() + ")"); }
            if (destino instanceof List<?> l) {
                if (v instanceof List<?> lv) ((List<Object>) l).addAll(lv); else if (v != null) ((List<Object>) l).add(v);
            } else if (v instanceof Map<?, ?> mv) ((Map<String, Object>) destino).putAll((Map<String, Object>) mv);
        }

        /** V66: lector de JSON (sin librerías). */
        static final class LectorJson {
            private final String t; private int i;
            LectorJson(String t) { this.t = t; }
            Object todo() { Object v = valor(); blancos(); if (i < t.length()) throw new IllegalArgumentException("sobra texto en la posición " + i); return v; }
            private void blancos() { while (i < t.length() && Character.isWhitespace(t.charAt(i))) i++; }
            private Object valor() {
                blancos();
                if (i >= t.length()) throw new IllegalArgumentException("termina antes de tiempo");
                char c = t.charAt(i);
                if (c == '{') {
                    i++; Sdt m = new Sdt(); blancos();
                    if (t.charAt(i) == '}') { i++; return m; }
                    while (true) {
                        blancos(); String k = texto(); blancos(); esperar(':');
                        m.put(k, valor()); blancos();
                        if (t.charAt(i) == ',') { i++; continue; }
                        esperar('}'); return m;
                    }
                }
                if (c == '[') {
                    i++; List<Object> l = new ArrayList<>(); blancos();
                    if (t.charAt(i) == ']') { i++; return l; }
                    while (true) {
                        l.add(valor()); blancos();
                        if (t.charAt(i) == ',') { i++; continue; }
                        esperar(']'); return l;
                    }
                }
                if (c == '"') return texto();
                if (t.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
                if (t.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
                if (t.startsWith("null", i)) { i += 4; return null; }
                int ini = i;
                while (i < t.length() && "+-0123456789.eE".indexOf(t.charAt(i)) >= 0) i++;
                if (ini == i) throw new IllegalArgumentException("no se esperaba '" + c + "' en la posición " + i);
                return new BigDecimal(t.substring(ini, i));
            }
            private void esperar(char c) { blancos(); if (i >= t.length() || t.charAt(i) != c) throw new IllegalArgumentException("se esperaba '" + c + "' en la posición " + i); i++; }
            private String texto() {
                esperar('"');
                StringBuilder b = new StringBuilder();
                while (true) {
                    char c = t.charAt(i++);
                    if (c == '"') return b.toString();
                    if (c != '\\') { b.append(c); continue; }
                    char e = t.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        static void desdeXml(Object destino, String texto) {
            if (destino instanceof List<?> l) l.clear(); else if (destino instanceof Map<?, ?> m) m.clear(); else return;
            if (texto == null || texto.isBlank()) return;
            org.w3c.dom.Element raiz;
            try {
                javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                raiz = f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(texto))).getDocumentElement();
            } catch (Exception ex) {
                throw new IllegalStateException("FromXml: el texto no es un XML válido (" + ex.getMessage() + ")");
            }
            Object v = desdeXml(raiz);
            if (destino instanceof List<?> l) {
                if (v instanceof List<?> lv) ((List<Object>) l).addAll(lv); else if (v != null) ((List<Object>) l).add(v);
            } else if (v instanceof Map<?, ?> mv) ((Map<String, Object>) destino).putAll((Map<String, Object>) mv);
        }

        private static Object desdeXml(org.w3c.dom.Element e) {
            String t = e.getAttribute("t");
            List<org.w3c.dom.Element> hijos = new ArrayList<>();
            for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof org.w3c.dom.Element h) hijos.add(h);
            if (t.equals("c") || (t.isEmpty() && !hijos.isEmpty() && hijos.stream().allMatch(h -> h.getTagName().equals(hijos.get(0).getTagName())) && hijos.size() > 1)) {
                List<Object> l = new ArrayList<>();
                for (org.w3c.dom.Element h : hijos) l.add(desdeXml(h));
                return l;
            }
            if (t.equals("r") || (t.isEmpty() && !hijos.isEmpty())) {
                Sdt m = new Sdt();
                for (org.w3c.dom.Element h : hijos) m.put(h.getTagName(), desdeXml(h));
                return m;
            }
            String x = e.getTextContent();
            try {
                return switch (t) {
                    case "z" -> null;
                    case "n" -> new BigDecimal(x.trim());
                    case "d" -> LocalDate.parse(x.trim());
                    case "h" -> LocalDateTime.parse(x.trim());
                    case "b" -> Boolean.parseBoolean(x.trim());
                    default -> x;
                };
            } catch (RuntimeException ex) { return x; }
        }

        @SuppressWarnings("unchecked")
        static Object llamar(String nombre, List<Object> a) {
            String n = nombre.toLowerCase(Locale.ROOT);
            if (!a.isEmpty() && a.get(0) instanceof SesionWeb w) {   // V48: &WebSession
                switch (n) {
                    case ".set": w.set(s(a, 1), a.size() > 2 ? a.get(2) : ""); return null;
                    case ".get": return w.get(s(a, 1));
                    case ".remove": w.set(s(a, 1), ""); return null;
                    case ".clear", ".destroy": return null;
                    default: break;
                }
            }
            if (!a.isEmpty() && a.get(0) instanceof PlanillaExcel px) {   // V50b / V66: el Excel (Ext(ExcelDocument))
                switch (n) {
                    case ".open": {
                        px.archivo = s(a, 1).trim(); px.planilla.limpiar(); px.put("ErrCode", BigDecimal.ZERO); px.put("ErrDescription", "");
                        // V66y: un Excel que existe (el que se subió en un web panel: ruta completa) se lee para Cells(f, c).Text...
                        java.io.File f = new java.io.File(px.archivo);
                        if (f.isAbsolute() && f.isFile()) {
                            try { px.planilla.leer(f); }
                            catch (java.io.IOException ex) {
                                px.put("ErrCode", BigDecimal.ONE);
                                px.put("ErrDescription", "No se pudo leer el Excel: " + ex.getMessage());
                            }
                        }
                        return BigDecimal.ZERO;
                    }
                    case ".clear": px.planilla.limpiar(); return BigDecimal.ZERO;
                    case ".save":
                        // V66: arma el .xlsx; queda para bajarlo con Link(&Archivo). Sin Open (Excel de detalle) no se guarda nada.
                        if (!px.archivo.isEmpty()) {
                            try {
                                boolean ajuste = OptimistFormula.num(px.get("AutoFit")).signum() != 0;
                                String hoja = px.archivo.replaceAll("(?i)\\.xlsx?$", "").replaceAll(".*[/\\\\]", "");
                                Map<String, byte[]> ar = ARCHIVOS.get();
                                ar.remove(px.archivo);
                                ar.put(px.archivo, px.planilla.xlsx(hoja, ajuste));
                                // los que nadie baja (el Excel de detalle de PRSoPEDResultado) no se acumulan en el hilo
                                while (ar.size() > 5) ar.remove(ar.keySet().iterator().next());
                            } catch (java.io.IOException | RuntimeException ex) {
                                px.put("ErrCode", BigDecimal.ONE); px.put("ErrDescription", "No se pudo armar el Excel: " + ex.getMessage());
                            }
                        }
                        return BigDecimal.ZERO;
                    case ".close", ".show", ".unbind", ".selectsheet", ".renamesheet": return BigDecimal.ZERO;
                    case ".cells":   // V66y: leer una celda: .Text / .Number / .Date / .Type
                        return a.size() < 3 ? new Sdt() : new Sdt(px.planilla.leerCelda(OptimistFormula.num(a.get(1)).intValue(), OptimistFormula.num(a.get(2)).intValue()));
                    default: break;
                }
            }
            if (!a.isEmpty() && a.get(0) instanceof SmtpSesion smtp) {   // V49f: &SMTP.Login() / .Send(&MailMessage) / .Logout()
                Object r = smtp(smtp, n, a);
                if (r != NO_ES) return r;
            }
            return switch (n) {
                // E4d: archivos de texto delimitados (DFWOpen / DFWPTxt / DFWPNum / DFWPDate / DFWNext / DFWClose de GeneXus)
                case "dfwopen" -> Dfw.abrir(s(a, 0), a.size() > 1 ? s(a, 1) : ",", a.size() > 2 ? s(a, 2) : "\"",
                        a.size() > 3 && a.get(3) != null && OptimistFormula.num(a.get(3)).signum() != 0, a.size() > 4 ? s(a, 4) : "");
                case "dfwptxt" -> Dfw.campo(a.isEmpty() ? "" : s(a, 0), true);
                case "dfwpnum" -> Dfw.campo(a.isEmpty() || a.get(0) == null ? "" : (a.size() > 1
                        ? OptimistFormula.num(a.get(0)).setScale(OptimistFormula.num(a.get(1)).intValue(), RoundingMode.HALF_UP)
                        : OptimistFormula.num(a.get(0))).toPlainString(), false);
                case "dfwpdate" -> Dfw.campo(a.isEmpty() || a.get(0) == null ? "" : String.valueOf(a.get(0)), false);
                case "dfwnext" -> Dfw.siguiente();
                case "dfwclose" -> Dfw.cerrar();
                case "random" -> BigDecimal.valueOf(Math.random());   // V66: Random() de GeneXus (entre 0 y 1)
                case "strreplace" -> s(a, 0).replace(s(a, 1), s(a, 2));
                case "strsearch" -> {
                    int desde = a.size() > 2 ? Math.max(1, OptimistFormula.num(a.get(2)).intValue()) : 1;
                    String t = s(a, 0);
                    int p = desde - 1 > t.length() ? -1 : t.indexOf(s(a, 1), desde - 1);
                    yield BigDecimal.valueOf(p + 1);
                }
                case "strsearchrev" -> BigDecimal.valueOf(s(a, 0).lastIndexOf(s(a, 1)) + 1);
                case "substr", ".substring" -> {
                    String t = s(a, 0);
                    int ini = Math.max(1, OptimistFormula.num(a.get(1)).intValue()) - 1;
                    if (ini >= t.length()) yield "";
                    int largo = a.size() > 2 ? OptimistFormula.num(a.get(2)).intValue() : t.length();
                    yield t.substring(ini, Math.min(t.length(), ini + Math.max(0, largo)));
                }
                case "val" -> {
                    String t = s(a, 0).trim().replace(",", ".");
                    Matcher m = Pattern.compile("^[-+]?\\d*\\.?\\d+").matcher(t);
                    yield m.find() ? new BigDecimal(m.group()) : BigDecimal.ZERO;
                }
                case "str" -> {
                    BigDecimal b = OptimistFormula.num(a.get(0));
                    int largo = a.size() > 1 ? OptimistFormula.num(a.get(1)).intValue() : 10;
                    int dec = a.size() > 2 ? OptimistFormula.num(a.get(2)).intValue() : 0;
                    String t = b.setScale(dec, RoundingMode.HALF_UP).toPlainString();
                    yield t.length() >= largo ? t : " ".repeat(largo - t.length()) + t;
                }
                case "trim", ".trim" -> s(a, 0).trim();
                case "ltrim", ".trimstart" -> s(a, 0).stripLeading();
                case "rtrim", ".trimend" -> s(a, 0).stripTrailing();
                case "upper", ".toupper" -> s(a, 0).toUpperCase(Locale.ROOT);
                case "lower", ".tolower" -> s(a, 0).toLowerCase(Locale.ROOT);
                case "len", ".length" -> BigDecimal.valueOf(s(a, 0).length());
                case "now", "servernow" -> LocalDateTime.now().withNano(0);
                case "servertoday" -> LocalDate.now();
                case "concat" -> s(a, 0) + (a.size() > 2 ? s(a, 2) : "") + s(a, 1);
                case "padl" -> pad(s(a, 0), OptimistFormula.num(a.get(1)).intValue(), a.size() > 2 ? s(a, 2) : " ", true);
                case "padr" -> pad(s(a, 0), OptimistFormula.num(a.get(1)).intValue(), a.size() > 2 ? s(a, 2) : " ", false);
                // V48c: Space(n), NewLine(), YMDHMStoT(a, m, d, h, mi, s), TAdd(fechaHora, segundos)
                case "space" -> " ".repeat(Math.max(0, OptimistFormula.num(a.isEmpty() ? null : a.get(0)).intValue()));
                case "newline" -> "\n";
                case "serverdate" -> LocalDate.now();
                case "dow" -> fecha(a.get(0)) == null ? BigDecimal.ZERO : BigDecimal.valueOf(fecha(a.get(0)).getDayOfWeek().getValue() % 7 + 1);   // 1 = domingo
                case "cdow" -> fecha(a.get(0)) == null ? "" : List.of("Domingo", "Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado").get(fecha(a.get(0)).getDayOfWeek().getValue() % 7);
                case "hour" -> a.get(0) instanceof LocalDateTime t ? BigDecimal.valueOf(t.getHour()) : BigDecimal.ZERO;
                case "minute" -> a.get(0) instanceof LocalDateTime t ? BigDecimal.valueOf(t.getMinute()) : BigDecimal.ZERO;
                case "second" -> a.get(0) instanceof LocalDateTime t ? BigDecimal.valueOf(t.getSecond()) : BigDecimal.ZERO;
                // V48c: métodos de GeneXus que aparecen al confirmar un pedido
                case ".todate" -> fecha(a.get(0));
                case ".tonumeric" -> OptimistFormula.num(a.get(0) instanceof String t ? (t.isBlank() ? "0" : t.trim()) : a.get(0));
                case ".truncate" -> OptimistFormula.num(a.get(0)).setScale(a.size() > 1 ? OptimistFormula.num(a.get(1)).intValue() : 0, RoundingMode.DOWN);
                case ".getoldvalue" -> a.get(0);   // en un procedimiento: el valor como se leyó (no hay "valor anterior" de pantalla)
                case "eom" -> fecha(a.get(0)) == null ? null : fecha(a.get(0)).withDayOfMonth(fecha(a.get(0)).lengthOfMonth());
                case "trunc" -> OptimistFormula.num(a.get(0)).setScale(a.size() > 1 ? OptimistFormula.num(a.get(1)).intValue() : 0, RoundingMode.DOWN);
                case "ymdhmstot" -> LocalDateTime.of(OptimistFormula.num(a.get(0)).intValue(), OptimistFormula.num(a.get(1)).intValue(),
                        OptimistFormula.num(a.get(2)).intValue(), a.size() > 3 ? OptimistFormula.num(a.get(3)).intValue() : 0,
                        a.size() > 4 ? OptimistFormula.num(a.get(4)).intValue() : 0, a.size() > 5 ? OptimistFormula.num(a.get(5)).intValue() : 0);
                case "tadd", ".addseconds" -> a.get(0) instanceof LocalDateTime t ? t.plusSeconds(OptimistFormula.num(a.get(1)).longValue())
                        : fecha(a.get(0)) == null ? null : fecha(a.get(0)).atStartOfDay().plusSeconds(OptimistFormula.num(a.get(1)).longValue());
                case "ymdtod" -> LocalDate.of(OptimistFormula.num(a.get(0)).intValue(), OptimistFormula.num(a.get(1)).intValue(), OptimistFormula.num(a.get(2)).intValue());
                case "addmth", ".addmonths" -> fecha(a.get(0)) == null ? null : fecha(a.get(0)).plusMonths(OptimistFormula.num(a.get(1)).longValue());
                case "adddays", ".adddays" -> fecha(a.get(0)) == null ? null : fecha(a.get(0)).plusDays(OptimistFormula.num(a.get(1)).longValue());
                case "ctod" -> ctod(s(a, 0));
                case ".year" -> fecha(a.get(0)) == null ? BigDecimal.ZERO : BigDecimal.valueOf(fecha(a.get(0)).getYear());
                case ".month" -> fecha(a.get(0)) == null ? BigDecimal.ZERO : BigDecimal.valueOf(fecha(a.get(0)).getMonthValue());
                case ".day" -> fecha(a.get(0)) == null ? BigDecimal.ZERO : BigDecimal.valueOf(fecha(a.get(0)).getDayOfMonth());
                case ".hour" -> a.get(0) instanceof LocalDateTime t ? BigDecimal.valueOf(t.getHour()) : BigDecimal.ZERO;
                case ".minute" -> a.get(0) instanceof LocalDateTime t ? BigDecimal.valueOf(t.getMinute()) : BigDecimal.ZERO;
                case ".second" -> a.get(0) instanceof LocalDateTime t ? BigDecimal.valueOf(t.getSecond()) : BigDecimal.ZERO;
                case ".isempty" -> vacioGx(a.get(0));
                case ".isnull" -> a.get(0) == null;
                // V48: X.ToString() de GeneXus deja el lugar del signo: 1 -> " 1", -1 -> "-1" (NumeroId: "PRESUPUESTOS 1 1").
                // ToFormattedString() no lo deja.
                // V48: sin el dominio a mano, la descripción de un enumerado es su valor (gx2proc usa EnumTexto.Udp cuando lo sabe)
                case ".enumerationdescription" -> s(a, 0).strip();
                // V49g: &Texto.IsMatch(&Patron) (ValidarMail): como GeneXus, la expresión regular aparece en alguna parte del texto
                case ".ismatch" -> {
                    try { yield Pattern.compile(s(a, 1)).matcher(s(a, 0)).find(); }
                    catch (java.util.regex.PatternSyntaxException ex) { throw new IllegalStateException("IsMatch: expresión regular inválida " + s(a, 1)); }
                }
                // V49s: &Coleccion.ToXml() / .FromXml(&Texto) (ActualizarSDTStockPotencial guarda el stock potencial en la WebSession)
                case ".toxml" -> xml(a.get(0));
                case ".fromxml" -> { desdeXml(a.get(0), s(a, 1)); yield null; }
                case ".fromjson" -> { desdeJson(a.get(0), s(a, 1)); yield Boolean.TRUE; }   // V66: &Sdt.FromJson(&Texto)
                case ".tojson" -> json(a.get(0));   // V49f: &Sdt.ToJson() (MailPdfGenerico lo deja en su log)
                case ".tostring" -> a.get(0) instanceof BigDecimal b ? (b.signum() < 0 ? "" : " ") + b.stripTrailingZeros().toPlainString() : s(a, 0);
                // V63: una fecha sale como en GeneXus, dd/mm/aaaa (antes salía 2004-02-23 en "Inicio Actividades" de las facturas);
                // la fecha vacía (1000-01-01 en MySQL), vacía
                case ".toformattedstring" -> a.get(0) instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString()
                        : a.get(0) instanceof LocalDateTime t ? (t.getYear() <= 1000 ? "" : t.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")))
                        : a.get(0) instanceof LocalDate d ? (d.getYear() <= 1000 ? "" : d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                        : a.get(0) instanceof java.sql.Date d ? (d.toLocalDate().getYear() <= 1000 ? "" : d.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                        : s(a, 0);
                case ".setempty", ".setnull" -> null;
                case ".contains" -> s(a, 0).contains(s(a, 1));
                case ".startswith" -> s(a, 0).startsWith(s(a, 1));
                case ".endswith" -> s(a, 0).endsWith(s(a, 1));
                case ".indexof" -> BigDecimal.valueOf(s(a, 0).indexOf(s(a, 1)) + 1);
                case ".replace" -> s(a, 0).replace(s(a, 1), s(a, 2));
                case "new" -> new Sdt();
                case "mod" -> {
                    BigDecimal d = OptimistFormula.num(a.size() > 1 ? a.get(1) : null);
                    yield d.signum() == 0 ? BigDecimal.ZERO : OptimistFormula.num(a.get(0)).remainder(d);
                }
                case "int" -> OptimistFormula.num(a.get(0)).setScale(0, RoundingMode.DOWN);
                case "ctot" -> {
                    String t = s(a, 0).trim();
                    try {
                        String[] hm = t.split(":");
                        yield LocalDate.of(1, 1, 1).atTime(Integer.parseInt(hm[0].trim()), hm.length > 1 ? Integer.parseInt(hm[1].trim()) : 0);
                    } catch (RuntimeException ex) { yield null; }
                }
                // colecciones y estructuras
                case ".add" -> {
                    // V49f: un MailRecipient se copia al agregarlo (MailPdfGenerico usa la misma variable para To y los BCC)
                    if (a.get(0) instanceof List<?> l) ((List<Object>) l).add(a.get(1) instanceof MailDestinatario d ? new MailDestinatario(d) : a.get(1));
                    yield null;
                }
                case ".clear" -> { if (a.get(0) instanceof List<?> l) l.clear(); else if (a.get(0) instanceof Map<?, ?> m) m.clear(); yield null; }
                case ".count" -> a.get(0) instanceof List<?> l ? BigDecimal.valueOf(l.size()) : BigDecimal.ZERO;
                case ".item" -> a.get(0) instanceof List<?> l ? l.get(OptimistFormula.num(a.get(1)).intValue() - 1) : null;
                case ".remove" -> { if (a.get(0) instanceof List<?> l) l.remove(OptimistFormula.num(a.get(1)).intValue() - 1); yield null; }
                case ".clone" -> a.get(0) instanceof Map<?, ?> m ? new Sdt(castear(m)) : a.get(0) instanceof List<?> l ? new ArrayList<>(l) : a.get(0);
                // V49: texto en renglones de N caracteres (las observaciones de una impresión)
                case "gxmlines" -> BigDecimal.valueOf(renglones(s(a, 0), a.size() > 1 ? OptimistFormula.num(a.get(1)).intValue() : 80).size());
                case "gxgetmli" -> {
                    List<String> rs = renglones(s(a, 0), a.size() > 2 ? OptimistFormula.num(a.get(2)).intValue() : 80);
                    int k = a.size() > 1 ? OptimistFormula.num(a.get(1)).intValue() : 1;
                    yield k >= 1 && k <= rs.size() ? rs.get(k - 1) : "";
                }
                // V49: &Archivo.Exists() (Ext(File) con .Source): si el archivo está en el servidor
                case ".exists" -> {
                    Object src = a.get(0) instanceof Map<?, ?> m ? m.entrySet().stream().filter(x -> "source".equalsIgnoreCase(String.valueOf(x.getKey())))
                            .map(Map.Entry::getValue).findFirst().orElse(null) : a.get(0);
                    String ruta = src == null ? "" : String.valueOf(src).trim();
                    yield !ruta.isEmpty() && java.nio.file.Files.isRegularFile(java.nio.file.Path.of(ruta.replace('\\', '/')));
                }
                case ".sort" -> { if (a.get(0) instanceof List<?> l) ordenar((List<Object>) l, s(a, 1)); yield null; }
                default -> NO_ES;
            };
        }

        static LocalDate fecha(Object v) {
            if (v instanceof LocalDate d) return d;
            if (v instanceof LocalDateTime t) return t.toLocalDate();
            return null;
        }

        static LocalDate ctod(String s) {
            String[] p = s.trim().split("[/\\-.]");
            if (p.length != 3) return null;
            try {
                int d = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                if (y < 100) y += y < 50 ? 2000 : 1900;
                return LocalDate.of(y, m, d);
            } catch (RuntimeException ex) { return null; }
        }

        /** &Lista.Sort('Fecha,Objeto') / '[Fecha]' descendente. */
        private static void ordenar(List<Object> l, String campos) {
            Comparator<Object> cmp = null;
            for (String c : campos.split(",")) {
                String t = c.trim();
                if (t.isEmpty()) continue;
                boolean desc = t.startsWith("[") || t.startsWith("(");
                String campo = t.replaceAll("[\\[\\]()]", "");
                Comparator<Object> uno = (x, y) -> {
                    Object vx = Ejecucion.campo(x, campo), vy = Ejecucion.campo(y, campo);
                    if (vx == null || vy == null) return vx == null ? (vy == null ? 0 : -1) : 1;
                    if (vx instanceof Number || vy instanceof Number) return OptimistFormula.num(vx).compareTo(OptimistFormula.num(vy));
                    @SuppressWarnings("unchecked") int r = vx instanceof Comparable cx && vx.getClass() == vy.getClass() ? cx.compareTo(vy) : String.valueOf(vx).compareTo(String.valueOf(vy));
                    return r;
                };
                if (desc) uno = uno.reversed();
                cmp = cmp == null ? uno : cmp.thenComparing(uno);
            }
            if (cmp != null) l.sort(cmp);
        }

        static boolean vacioGx(Object v) {
            if (v == null) return true;
            if (v instanceof byte[] b) return b.length == 0;   // V49: imagen / blob
            if (v instanceof String t) return t.isBlank();
            if (v instanceof Number) return OptimistFormula.num(v).signum() == 0;
            if (v instanceof Boolean b) return !b;
            if (OptimistTrnDatos.fechaVacia(v)) return true;   // V48: 1000-01-01 es la fecha vacía de GeneXus
            if (v instanceof List<?> l) return l.isEmpty();
            if (v instanceof Map<?, ?> m) return m.values().stream().allMatch(Funciones::vacioGx);
            return false;
        }

        /**
         * E4d: el archivo de texto que escribe el procedimiento (DFW de GeneXus), uno por hilo. Una ruta relativa va a la
         * carpeta del cliente (la del optimist.cfg). Devuelven 0 si anduvo y -1 si no (como GeneXus).
         */
        static final class Dfw {
            private static final ThreadLocal<Dfw> ACTUAL = new ThreadLocal<>();
            private final java.io.Writer w;
            private final String sepCampo, sepTexto;
            private boolean primero = true;
            private Dfw(java.io.Writer w, String sepCampo, String sepTexto) { this.w = w; this.sepCampo = sepCampo; this.sepTexto = sepTexto; }

            static BigDecimal abrir(String archivo, String sepCampo, String sepTexto, boolean agregar, String codificacion) {
                cerrar();
                try {
                    java.nio.file.Path p = java.nio.file.Path.of(archivo.trim());
                    if (!p.isAbsolute()) {
                        java.nio.file.Path base = OptimistDatabase.carpetaCliente();
                        if (base != null) p = base.resolve(p);
                    }
                    if (p.getParent() != null) java.nio.file.Files.createDirectories(p.getParent());
                    java.nio.charset.Charset cs = java.nio.charset.StandardCharsets.UTF_8;
                    String c = codificacion == null ? "" : codificacion.replace('_', '-').trim();
                    if (!c.isEmpty()) try { cs = java.nio.charset.Charset.forName(c.replaceAll("(?i)^encoding\\.", "")); } catch (RuntimeException ignorado) { }
                    java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(p.toFile(), agregar), cs));
                    ACTUAL.set(new Dfw(w, sepCampo == null ? "," : sepCampo, sepTexto == null ? "" : sepTexto));
                    return BigDecimal.ZERO;
                } catch (java.io.IOException | RuntimeException ex) {
                    System.out.println("[DFWOpen] " + archivo + ": " + ex.getMessage());
                    return BigDecimal.ONE.negate();
                }
            }
            static BigDecimal campo(String texto, boolean esTexto) {
                Dfw d = ACTUAL.get();
                if (d == null) return BigDecimal.ONE.negate();
                try {
                    if (!d.primero) d.w.write(d.sepCampo);
                    d.w.write(esTexto ? d.sepTexto + texto + d.sepTexto : texto);
                    d.primero = false;
                    return BigDecimal.ZERO;
                } catch (java.io.IOException ex) { return BigDecimal.ONE.negate(); }
            }
            static BigDecimal siguiente() {
                Dfw d = ACTUAL.get();
                if (d == null) return BigDecimal.ONE.negate();
                try { d.w.write(System.lineSeparator()); d.primero = true; return BigDecimal.ZERO; }
                catch (java.io.IOException ex) { return BigDecimal.ONE.negate(); }
            }
            static BigDecimal cerrar() {
                Dfw d = ACTUAL.get();
                ACTUAL.remove();
                if (d == null) return BigDecimal.ZERO;
                try { d.w.close(); return BigDecimal.ZERO; } catch (java.io.IOException ex) { return BigDecimal.ONE.negate(); }
            }
        }

        private static String s(List<Object> a, int i) {
            if (i >= a.size() || a.get(i) == null) return "";
            Object v = a.get(i);
            return v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
        }

        private static String pad(String t, int n, String c, boolean izq) {
            if (t.length() >= n || c.isEmpty()) return t;
            String r = c.repeat(n - t.length()).substring(0, n - t.length());
            return izq ? r + t : t + r;
        }
    }
}
