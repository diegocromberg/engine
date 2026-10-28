package ar.com.datafix.optimist;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Evaluador de fórmulas estilo GeneXus. Todo con BigDecimal: nada de double.
 *
 * V23: números, atributos, + - * /, paréntesis, menos unario; year(), month(), day(), today(), round(x, n), abs(x), int(x).
 * V34 (fórmulas visibles):
 *   - comparaciones = <> < > <= >=, and / or / not, true / false, textos 'así' o "así"
 *   - condicional de GeneXus:  expr1 IF cond1; expr2 IF cond2; expr3 OTHERWISE
 *   - agregados sobre un detalle: sum(expr [, cond [, defecto]]), count(...), max(...), min(...), average(...)
 *     (el detalle se deduce de los atributos que usa)
 *   - find(Tabla.Columna, Col1 = expr and Col2 = expr [, defecto]): lee de la base
 *   - llamadas a procedimientos: Nombre.udp(args) o Nombre(args) -> las resuelve el contexto
 *   - fecha + número = fecha + días; fecha - fecha = días
 *   - comentarios // y /* ... *&#47;
 */
public final class OptimistFormula {

    /** De dónde saca los valores la fórmula. */
    public interface Contexto {
        /** Valor de un atributo del registro (o de la línea, y si no de la cabecera). */
        Object valor(String atributo);
        /** Líneas del detalle al que pertenece el atributo (null si el atributo no es de un detalle). */
        default List<Contexto> lineas(String atributo) { return null; }
        /**
         * Llamada a un procedimiento (Nombre.udp(...)). null si no existe. V82b: los métodos de los valores (.IsEmpty(), .Trim(),
         * .ToUpper()...) se resuelven igual que en los procedimientos; antes, en las condiciones simples de las acciones del
         * WorkWith (sin procedimientos ni &variables) daban null: "CPBCRealFct.IsEmpty()" era siempre falso.
         */
        /**
         * V82k: largo y decimales declarados de una &variable o un atributo ({largo, decimales}), o null si no se sabe. Lo usa
         * X.ToString() para ocupar el largo, como GeneXus. Los atributos, por su dominio en las TRN.
         */
        default int[] tamanio(String nombre) {
            if (nombre == null || nombre.startsWith("&")) return null;
            return OptimistProcedimiento.tamanioAtributo(nombre);
        }

        default Object procedimiento(String nombre, List<Object> args) {
            if (nombre == null || !nombre.startsWith(".")) return null;
            try {
                Object r = OptimistProcedimiento.Funciones.llamar(nombre, args);
                return r == OptimistProcedimiento.Funciones.NO_ES ? null : r;
            } catch (RuntimeException e) { return null; }
        }
        /** find(): primera fila de 'tabla' que cumple las igualdades; valor de 'columna'. */
        default Object buscar(String tabla, String columna, Map<String, Object> igualdades) { return null; }
    }

    /** Nodo del árbol de la fórmula. */
    private interface Nodo { Object valor(Contexto c); }

    /** Comparación: se guarda aparte para que find() pueda leer sus igualdades. */
    private record Comparacion(String op, Nodo izq, Nodo der, String atributoIzq) implements Nodo {
        public Object valor(Contexto c) { return comparar(op, izq.valor(c), der.valor(c)); }
    }
    private record Y(Nodo a, Nodo b) implements Nodo {
        public Object valor(Contexto c) { return verdad(a.valor(c)) && verdad(b.valor(c)); }
    }

    private final String texto;
    private final String fuente;
    private final List<Nodo[]> alternativas = new ArrayList<>();     // {expresión, condición|null}
    private final Set<String> atributos = new LinkedHashSet<>();
    private final Set<String> procedimientos = new LinkedHashSet<>();
    private Set<String> ambito = null;                                // atributos del agregado que se está leyendo

    private static final Set<String> FUNCIONES = Set.of("year", "month", "day", "today", "round", "abs", "int",
            "sum", "count", "max", "min", "average", "find", "trim", "len", "iif", "null", "nullvalue");

    private OptimistFormula(String texto) {
        this.fuente = texto;
        this.texto = sinComentarios(texto);
        this.pos = 0;
        do {
            espacios();
            if (pos >= this.texto.length()) break;
            Nodo e = logico();
            espacios();
            Nodo cond = null;
            if (palabra("if")) cond = logico();
            else palabra("otherwise");
            alternativas.add(new Nodo[]{e, cond});
            espacios();
        } while (acepta(';'));
        espacios();
        if (pos < this.texto.length()) throw error("sobra '" + this.texto.substring(pos) + "'");
        if (alternativas.isEmpty()) throw error("fórmula vacía");
    }

    /** Parsea la fórmula. Lanza IllegalArgumentException si usa algo que todavía no se soporta. */
    public static OptimistFormula de(String texto) { return new OptimistFormula(texto); }

    /** Atributos de la TRN que usa la fórmula (para validar que existan). No incluye las columnas de find(). */
    public Set<String> atributos() { return Collections.unmodifiableSet(atributos); }

    /** Procedimientos que llama (Nombre.udp). */
    public Set<String> procedimientos() { return Collections.unmodifiableSet(procedimientos); }

    public String texto() { return fuente; }

    /** Evalúa con un contexto (cabecera, líneas de los detalles, procedimientos, base). null si ninguna alternativa aplica. */
    public Object evaluar(Contexto c) {
        for (Nodo[] a : alternativas)
            if (a[1] == null || verdad(a[1].valor(c))) return a[0].valor(c);
        return null;
    }

    // ------------------------------------------------------------------ parser (descenso recursivo)

    private int pos;

    private Nodo logico() {
        Nodo n = conjuncion();
        while (true) {
            espacios();
            if (palabra("or")) { Nodo a = n, b = conjuncion(); n = c -> verdad(a.valor(c)) || verdad(b.valor(c)); }
            else return n;
        }
    }

    private Nodo conjuncion() {
        Nodo n = negacion();
        while (true) {
            espacios();
            if (palabra("and")) { Nodo a = n, b = negacion(); n = new Y(a, b); }
            else return n;
        }
    }

    private Nodo negacion() {
        espacios();
        if (palabra("not")) { Nodo a = negacion(); return c -> !verdad(a.valor(c)); }
        return comparacion();
    }

    private Nodo comparacion() {
        espacios();
        int antes = pos;
        Nodo a = expresion();
        String entre = texto.substring(antes, pos).trim();
        String atr = entre.matches("[A-Za-z_][A-Za-z0-9_]*") ? entre : null;
        espacios();
        if (palabra("in")) {
            // Atributo in ("A", "B", ...)
            espacios();
            if (!acepta('(')) {
                // Atributo in &Coleccion
                Nodo col = expresion();
                return c -> {
                    Object v = a.valor(c), l = col.valor(c);
                    if (l instanceof Collection<?> xs) for (Object x : xs) if (comparar("=", v, x)) return true;
                    return false;
                };
            }
            List<Nodo> lista = argumentos("in");
            return c -> { Object v = a.valor(c); for (Nodo x : lista) if (comparar("=", v, x.valor(c))) return true; return false; };
        }
        if (palabra("like")) {
            Nodo b = expresion();
            return c -> {
                Object v = a.valor(c), patron = b.valor(c);
                if (v == null || patron == null) return false;
                StringBuilder re = new StringBuilder();
                for (char ch : String.valueOf(patron).stripTrailing().toCharArray())
                    re.append(ch == '%' ? ".*" : ch == '_' ? "." : java.util.regex.Pattern.quote(String.valueOf(ch)));
                return String.valueOf(v).stripTrailing().matches("(?is)" + re);
            };
        }
        for (String op : new String[]{"<>", "<=", ">=", "=", "<", ">"}) {
            if (texto.startsWith(op, pos)) {
                pos += op.length();
                Nodo b = expresion();
                return new Comparacion(op, a, b, atr);
            }
        }
        return a;
    }

    private Nodo expresion() {
        Nodo n = termino();
        while (true) {
            espacios();
            if (acepta('+')) { Nodo a = n, b = termino(); n = c -> sumar(a.valor(c), b.valor(c)); }
            else if (siguienteEs('-')) { pos++; Nodo a = n, b = termino(); n = c -> restar(a.valor(c), b.valor(c)); }
            else return n;
        }
    }

    private Nodo termino() {
        Nodo n = factor();
        while (true) {
            espacios();
            if (acepta('*')) { Nodo a = n, b = factor(); n = c -> num(a.valor(c)).multiply(num(b.valor(c))); }
            else if (acepta('^')) { Nodo a = n, b = factor(); n = c -> BigDecimal.valueOf(Math.pow(num(a.valor(c)).doubleValue(), num(b.valor(c)).doubleValue())); }
            else if (acepta('/')) {
                Nodo a = n, b = factor();
                n = c -> {
                    BigDecimal d = num(b.valor(c));
                    return d.signum() == 0 ? BigDecimal.ZERO : num(a.valor(c)).divide(d, MathContext.DECIMAL64);
                };
            }
            else return n;
        }
    }

    private Nodo factor() {
        Nodo n = factorBase();
        // encadenados: X.ToString().Trim(), &Col.Item(&i).Campo
        while (siguienteEs('.') && pos + 1 < texto.length() && Character.isLetter(texto.charAt(pos + 1))) {
            pos++;
            String seg = identificador();
            espacios();
            Nodo base = n;
            if (acepta('(')) {
                List<Nodo> args = argumentos(seg);
                String metodo = "." + seg.toLowerCase(Locale.ROOT);
                n = c -> {
                    List<Object> vs = new ArrayList<>();
                    vs.add(base.valor(c));
                    for (Nodo x : args) vs.add(x.valor(c));
                    return c.procedimiento(metodo, vs);
                };
            } else n = c -> campo(base.valor(c), seg);
        }
        return n;
    }

    /** Campo de una estructura (Sdt) o Count de una colección. */
    static Object campo(Object v, String nombre) {
        if (v instanceof List<?> l && nombre.equalsIgnoreCase("count")) return BigDecimal.valueOf(l.size());
        if (v instanceof Map<?, ?> m) for (var e : m.entrySet()) if (String.valueOf(e.getKey()).equalsIgnoreCase(nombre)) return e.getValue();
        return null;
    }

    private Nodo factorBase() {
        espacios();
        if (acepta('-')) { Nodo a = factor(); return c -> num(a.valor(c)).negate(); }
        if (acepta('+')) return factor();
        if (palabra("new")) {
            // new Estructura() -> estructura vacía
            espacios();
            while (pos < texto.length() && (Character.isLetterOrDigit(texto.charAt(pos)) || texto.charAt(pos) == '_' || texto.charAt(pos) == '.')) pos++;
            espacios();
            if (acepta('(')) { espacios(); if (!acepta(')')) throw error("new X() no lleva parámetros"); }
            return c -> c.procedimiento("new", List.of());
        }
        if (acepta('(')) { Nodo a = logico(); espacios(); if (!acepta(')')) throw error("falta ')'"); return a; }
        if (pos + 1 < texto.length() && texto.charAt(pos) == '!' && (texto.charAt(pos + 1) == '\'' || texto.charAt(pos + 1) == '"')) pos++;   // !"texto" (no traducible)
        if (pos < texto.length() && (texto.charAt(pos) == '\'' || texto.charAt(pos) == '"')) {
            char q = texto.charAt(pos++);
            int ini = pos;
            while (pos < texto.length() && texto.charAt(pos) != q) pos++;
            if (pos >= texto.length()) throw error("falta cerrar el texto");
            String s = texto.substring(ini, pos++);
            return c -> s;
        }
        if (pos < texto.length() && (Character.isDigit(texto.charAt(pos)) || texto.charAt(pos) == '.')) {
            int ini = pos;
            while (pos < texto.length() && (Character.isDigit(texto.charAt(pos)) || texto.charAt(pos) == '.')) pos++;
            if (texto.substring(ini, pos).replace(".", "").isEmpty()) throw error("número inválido");
            BigDecimal n = new BigDecimal(texto.substring(ini, pos));
            return c -> n;
        }
        if (pos < texto.length() && (Character.isLetter(texto.charAt(pos)) || texto.charAt(pos) == '_' || texto.charAt(pos) == '&')) {
            String nombre = identificador();
            String baja = nombre.toLowerCase(Locale.ROOT);
            if (baja.equals("true")) return c -> Boolean.TRUE;
            if (baja.equals("false")) return c -> Boolean.FALSE;
            // Nombre.udp(...) / Nombre.call(...) = procedimiento;  Tabla.Columna / &Sdt.Campo.Campo = nombre calificado;
            // &x.Metodo(...) / &Sdt.Campo.Metodo(...) = método
            if (siguienteEs('.') && pos + 1 < texto.length() && Character.isLetter(texto.charAt(pos + 1))) {
                List<String> segs = new ArrayList<>();
                while (siguienteEs('.') && pos + 1 < texto.length() && Character.isLetter(texto.charAt(pos + 1))) { pos++; segs.add(identificador()); }
                espacios();
                String segundo = segs.get(segs.size() - 1);
                if ((segundo.equalsIgnoreCase("udp") || segundo.equalsIgnoreCase("call")) && !nombre.startsWith("&") && acepta('('))
                    return procedimiento(segs.size() == 1 ? nombre : segs.get(segs.size() - 2));   // [Módulo.]Proc.udp(...)
                String objeto = nombre + (segs.size() > 1 ? "." + String.join(".", segs.subList(0, segs.size() - 1)) : "");
                if (acepta('(')) {
                    // método: &Texto.IsEmpty(), &Texto.ToUpper()... -> procedimiento(".isempty", [valor, args...])
                    if (!objeto.startsWith("&") && !objeto.contains(".")) { atributos.add(objeto); if (ambito != null) ambito.add(objeto); }
                    List<Nodo> args = argumentos(segundo);
                    String metodo = "." + segundo.toLowerCase(Locale.ROOT);
                    if (metodo.equals(".tostring") && args.isEmpty() && (!objeto.contains(".") || objeto.toLowerCase(Locale.ROOT).startsWith("&wwpcontext.")))
                        // V82k: como GeneXus, X.ToString() de un número entero ocupa el largo declarado (Numeric(2): 1 -> " 1",
                        // 99 -> "99"). Antes siempre " " + número: con la empresa 99 la numeración "PEDIDOS9999" pasaba a ser
                        // "PEDIDOS 99 99" y arrancaba de nuevo.
                        return c -> {
                            Object v = c.valor(objeto);
                            int[] t = c.tamanio(objeto);
                            if (v instanceof BigDecimal b && t != null && t[0] > 0 && t[1] == 0 && b.stripTrailingZeros().scale() <= 0) {
                                String x = b.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
                                return x.length() >= t[0] ? x : " ".repeat(t[0] - x.length()) + x;
                            }
                            List<Object> vs = new ArrayList<>();
                            vs.add(v);
                            return c.procedimiento(metodo, vs);
                        };
                    return c -> {
                        List<Object> vs = new ArrayList<>();
                        vs.add(c.valor(objeto));
                        for (Nodo x : args) vs.add(x.valor(c));
                        return c.procedimiento(metodo, vs);
                    };
                }
                String calificado = nombre + "." + String.join(".", segs);
                return c -> c.valor(calificado);
            }
            espacios();
            if (acepta('(')) {
                if (FUNCIONES.contains(baja)) return funcion(baja);
                return procedimiento(nombre);   // GetPRSRazonSocial(...) sin .udp
            }
            atributos.add(nombre);
            if (ambito != null) ambito.add(nombre);
            return c -> c.valor(nombre);
        }
        throw error("se esperaba un número, un atributo o '('");
    }

    private String identificador() {
        int ini = pos;
        if (texto.charAt(pos) == '&') pos++;
        while (pos < texto.length() && (Character.isLetterOrDigit(texto.charAt(pos)) || texto.charAt(pos) == '_')) pos++;
        return texto.substring(ini, pos);
    }

    private List<Nodo> argumentos(String f) {
        List<Nodo> args = new ArrayList<>();
        espacios();
        if (!acepta(')')) {
            do { args.add(logico()); espacios(); } while (acepta(','));
            if (!acepta(')')) throw error("falta ')' en " + f + "()");
        }
        return args;
    }

    private Nodo procedimiento(String nombre) {
        procedimientos.add(nombre);
        List<Nodo> args = argumentos(nombre);
        return c -> {
            List<Object> vs = new ArrayList<>();
            for (Nodo a : args) vs.add(a.valor(c));
            return c.procedimiento(nombre, vs);
        };
    }

    private Nodo funcion(String f) {
        if (Set.of("sum", "count", "max", "min", "average").contains(f)) return agregado(f);
        if (f.equals("find")) return find();
        List<Nodo> args = argumentos(f);
        return switch (f) {
            case "today" -> { aridad(f, args, 0); yield c -> LocalDate.now(); }
            case "year" -> { aridad(f, args, 1); Nodo a = args.get(0); yield c -> fecha(a.valor(c)) == null ? BigDecimal.ZERO : BigDecimal.valueOf(fecha(a.valor(c)).getYear()); }
            case "month" -> { aridad(f, args, 1); Nodo a = args.get(0); yield c -> fecha(a.valor(c)) == null ? BigDecimal.ZERO : BigDecimal.valueOf(fecha(a.valor(c)).getMonthValue()); }
            case "day" -> { aridad(f, args, 1); Nodo a = args.get(0); yield c -> fecha(a.valor(c)) == null ? BigDecimal.ZERO : BigDecimal.valueOf(fecha(a.valor(c)).getDayOfMonth()); }
            case "abs" -> { aridad(f, args, 1); Nodo a = args.get(0); yield c -> num(a.valor(c)).abs(); }
            case "int" -> { aridad(f, args, 1); Nodo a = args.get(0); yield c -> num(a.valor(c)).setScale(0, RoundingMode.DOWN); }
            case "round" -> {
                aridad(f, args, 2);
                Nodo a = args.get(0), b = args.get(1);
                yield c -> num(a.valor(c)).setScale(num(b.valor(c)).intValue(), RoundingMode.HALF_UP);
            }
            case "trim" -> { aridad(f, args, 1); Nodo a = args.get(0); yield c -> a.valor(c) == null ? "" : String.valueOf(a.valor(c)).trim(); }
            case "len" -> { aridad(f, args, 1); Nodo a = args.get(0); yield c -> BigDecimal.valueOf(a.valor(c) == null ? 0 : String.valueOf(a.valor(c)).length()); }
            case "iif" -> { aridad(f, args, 3); Nodo a = args.get(0), b = args.get(1), d = args.get(2); yield c -> verdad(a.valor(c)) ? b.valor(c) : d.valor(c); }
            case "null", "nullvalue" -> c -> null;
            default -> throw error("función " + f);
        };
    }

    /** sum/count/max/min/average(expr [, cond [, defecto]]) sobre las líneas del detalle que usan sus atributos. */
    private Nodo agregado(String f) {
        Set<String> previo = ambito;
        ambito = new LinkedHashSet<>();
        List<Nodo> args = argumentos(f);
        List<String> usados = new ArrayList<>(ambito);
        ambito = previo;
        if (previo != null) previo.addAll(usados);
        if (args.isEmpty() || args.size() > 3) throw error(f + "() lleva de 1 a 3 parámetros");
        Nodo expr = args.get(0), cond = args.size() > 1 ? args.get(1) : null, defecto = args.size() > 2 ? args.get(2) : null;
        return c -> {
            List<Contexto> lineas = null;
            for (String a : usados) { lineas = c.lineas(a); if (lineas != null) break; }
            if (lineas == null) lineas = List.of();
            BigDecimal suma = BigDecimal.ZERO, mejor = null;
            int n = 0;
            for (Contexto l : lineas) {
                if (cond != null && !verdad(cond.valor(l))) continue;
                BigDecimal v = num(expr.valor(l));
                n++;
                suma = suma.add(v);
                if (mejor == null || (f.equals("max") ? v.compareTo(mejor) > 0 : f.equals("min") && v.compareTo(mejor) < 0)) mejor = v;
            }
            if (n == 0) return defecto != null ? defecto.valor(c) : BigDecimal.ZERO;
            return switch (f) {
                case "sum" -> suma;
                case "count" -> BigDecimal.valueOf(n);
                case "average" -> suma.divide(BigDecimal.valueOf(n), MathContext.DECIMAL64);
                default -> mejor;
            };
        };
    }

    /** find(Tabla.Columna, Col = expr and ... [, defecto]) */
    private Nodo find() {
        espacios();
        String calif = identificador();
        String tabla = null, columna = calif;
        if (acepta('.')) { tabla = calif; columna = identificador(); }
        espacios();
        List<Comparacion> iguales = new ArrayList<>();
        Nodo defecto = null;
        if (acepta(',')) {
            Set<String> antes = new LinkedHashSet<>(atributos);
            Nodo cond = logico();
            juntarIgualdades(cond, iguales);
            // las columnas de la otra tabla (lado izquierdo de cada igualdad) no son atributos de la TRN
            for (Comparacion q : iguales) if (!antes.contains(q.atributoIzq())) atributos.remove(q.atributoIzq());
            espacios();
            if (acepta(',')) defecto = logico();
        }
        espacios();
        if (!acepta(')')) throw error("falta ')' en find()");
        final String t = tabla, col = columna;
        final Nodo def = defecto;
        return c -> {
            Map<String, Object> igual = new LinkedHashMap<>();
            for (Comparacion q : iguales) igual.put(q.atributoIzq(), q.der().valor(c));
            Object r = t == null ? null : c.buscar(t, col, igual);
            return r != null ? r : def == null ? null : def.valor(c);
        };
    }

    private void juntarIgualdades(Nodo n, List<Comparacion> r) {
        if (n instanceof Y y) { juntarIgualdades(y.a(), r); juntarIgualdades(y.b(), r); }
        else if (n instanceof Comparacion q && q.op().equals("=") && q.atributoIzq() != null) r.add(q);
        else throw error("find(): la condición tiene que ser Columna = valor (and ...)");
    }

    private void aridad(String f, List<Nodo> args, int n) {
        if (args.size() != n) throw error(f + "() lleva " + n + " parámetro(s)");
    }

    private void espacios() { while (pos < texto.length() && Character.isWhitespace(texto.charAt(pos))) pos++; }
    private boolean acepta(char c) {
        if (pos < texto.length() && texto.charAt(pos) == c) { pos++; return true; }
        return false;
    }
    private boolean siguienteEs(char c) { return pos < texto.length() && texto.charAt(pos) == c; }
    /** palabra clave (if, and, or, not, otherwise) seguida de algo que no es letra */
    private boolean palabra(String p) {
        int n = p.length();
        if (pos + n <= texto.length() && texto.regionMatches(true, pos, p, 0, n)
                && (pos + n == texto.length() || !Character.isLetterOrDigit(texto.charAt(pos + n)))) {
            pos += n; return true;
        }
        return false;
    }
    private IllegalArgumentException error(String m) {
        return new IllegalArgumentException("fórmula '" + fuente + "': " + m);
    }

    /** Saca los comentarios // y /* *&#47; que no estén dentro de un texto ("http://..." queda). */
    static String sinComentarios(String s) {
        StringBuilder r = new StringBuilder();
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { r.append(c); if (c == q) q = 0; continue; }
            if (c == '"' || c == '\'') { q = c; r.append(c); continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; r.append(' '); continue; }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int fin = s.indexOf("*/", i + 2);
                i = fin < 0 ? s.length() : fin + 1;
                r.append(' ');
                continue;
            }
            r.append(c);
        }
        return r.toString().trim();
    }

    // ------------------------------------------------------------------ valores

    static BigDecimal num(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        if (o instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    static boolean verdad(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number) return num(o).signum() != 0;
        String s = String.valueOf(o).trim();
        return s.equalsIgnoreCase("true") || s.equalsIgnoreCase("s") || s.equals("1");
    }

    private static boolean esNumero(Object o) {
        if (o instanceof Number) return true;
        if (o instanceof String s) { try { new BigDecimal(s.trim()); return true; } catch (NumberFormatException e) { return false; } }
        return false;
    }

    private static Object sumar(Object a, Object b) {
        LocalDate f = fecha(a);
        if (f != null && (b == null || esNumero(b))) {
            long dias = num(b).longValue();
            return a instanceof LocalDateTime t ? t.plusDays(dias) : f.plusDays(dias);
        }
        // V82: dos textos se concatenan siempre, como en GeneXus ("2026" + "10" + "25" = "20261025"; antes sumaba: 2061)
        if ((a instanceof String && b instanceof String) || (a instanceof String && !esNumero(a)) || (b instanceof String && !esNumero(b)))
            return (a == null ? "" : String.valueOf(a)) + (b == null ? "" : String.valueOf(b));
        return num(a).add(num(b));
    }

    private static Object restar(Object a, Object b) {
        LocalDate fa = fecha(a), fb = fecha(b);
        if (fa != null && fb != null) return BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(fb, fa));
        if (fa != null) return a instanceof LocalDateTime t ? t.minusDays(num(b).longValue()) : fa.minusDays(num(b).longValue());
        return num(a).subtract(num(b));
    }

    /** Compara como GeneXus: vacío = 0 / "" / falso. */
    static boolean comparar(String op, Object a, Object b) {
        int r;
        if (a instanceof Boolean || b instanceof Boolean) {
            r = Boolean.compare(verdad(a), verdad(b));
        } else if (fecha(a) != null || fecha(b) != null) {
            LocalDate x = fecha(a), y = fecha(b);
            if (x == null || y == null) r = x == y ? 0 : (x == null ? -1 : 1);
            else r = x.compareTo(y);
        } else if ((a == null || esNumero(a)) && (b == null || esNumero(b))) {
            r = num(a).compareTo(num(b));
        } else {
            String x = a == null ? "" : String.valueOf(a).trim(), y = b == null ? "" : String.valueOf(b).trim();
            r = x.compareTo(y);
        }
        return switch (op) {
            case "=" -> r == 0;
            case "<>" -> r != 0;
            case "<" -> r < 0;
            case ">" -> r > 0;
            case "<=" -> r <= 0;
            default -> r >= 0;
        };
    }

    private static LocalDate fecha(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof LocalDateTime t) return t.toLocalDate();
        return null;
    }
}
