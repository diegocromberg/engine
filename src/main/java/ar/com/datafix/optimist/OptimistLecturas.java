package ar.com.datafix.optimist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

/**
 * V36 - Lecturas recordadas mientras se edita un formulario.
 *
 * Al cambiar un campo, las reglas, los procedimientos y las fórmulas leen muchas veces lo mismo (parámetros
 * globales, el artículo, la tasa de IVA...): en un presupuesto eran ~140 consultas por cambio (12 segundos).
 * Con esto, cada consulta distinta va a la base una sola vez; las siguientes salen de memoria.
 *
 * - Solo está activo dentro de {@link #con} / {@link #hacer} (la pantalla lo usa al editar; al grabar, uno nuevo y vacío).
 * - Cualquier escritura (INSERT/UPDATE/DELETE de un procedimiento) lo vacía entero.
 * - Vence a los {@value #VIGENCIA_MS} ms, para no mostrar un precio viejo si el formulario queda abierto.
 */
public final class OptimistLecturas {

    static final long VIGENCIA_MS = 120_000;
    private static final ThreadLocal<OptimistLecturas> ACTUAL = new ThreadLocal<>();

    private final Map<String, List<Object[]>> filas = new HashMap<>();
    /** Una sola conexión por cambio (se abre recién si hace falta ir a la base) y se cierra al terminar. */
    private Connection conexion;
    private boolean autoCommit = true;   // mientras la conexión no se abrió, las transacciones son "de palabra"
    /** V65k: Confirmar de una TRN: todo en esta conexión y una sola transacción; commit / setAutoCommit de adentro no hacen nada. */
    private boolean transaccion;
    private String catalogo;             // el nombre de la base no cambia: no hace falta abrir para saberlo
    /** V37: resultados de procedimientos de las fórmulas (nombre + argumentos), si no escribieron en la base. */
    private final Map<List<Object>, Object> llamadas = new HashMap<>();
    private int escrituras;
    private long desde = System.currentTimeMillis();
    private int consultas, recordadas;
    /** V49y: quién hizo cada consulta a la base (procedimiento:renglón, o la tabla) -> {cantidad, nanos}. */
    private final Map<String, long[]> porOrigen = new HashMap<>();
    /** V49y: el For Each que está consultando ("GetStockArticulo...:57"); sin él, se anota la tabla del SELECT. */
    static final ThreadLocal<String> ORIGEN = new ThreadLocal<>();

    /** Ejecuta 'accion' con este caché activo (si ya había uno activo, sigue el de afuera). */
    public void hacer(Runnable accion) {
        con(() -> { accion.run(); return null; });
    }

    public <T> T con(Supplier<T> accion) {
        if (ACTUAL.get() != null) return accion.get();
        ACTUAL.set(this);
        try { return accion.get(); }
        finally {
            ACTUAL.remove();
            if (conexion != null) { try { conexion.close(); } catch (SQLException ignorado) { } conexion = null; }
            autoCommit = true;
        }
    }

    public interface Trabajo<T> { T hacer() throws SQLException; }

    /**
     * V65k: corre 'trabajo' como UNA transacción, como la unidad de trabajo de GeneXus: las reglas, los procedimientos que
     * llaman y el grabado de la TRN comparten la conexión (con el caché de lecturas). Si termina bien, commit; si algo falla
     * (una excepción, o un Error de las reglas que el que llama convierte en excepción), se deshace TODO. Los Commit /
     * setAutoCommit de adentro no hacen nada (antes cada procedimiento de una regla confirmaba lo suyo y, si después el
     * grabado fallaba, quedaba grabado a medias: un pedido marcado como facturado sin su comprobante).
     */
    public static <T> T enTransaccion(Trabajo<T> trabajo) throws SQLException {
        OptimistLecturas afuera = ACTUAL.get();   // (no debería haber uno activo; si lo hay, se deja como estaba al terminar)
        OptimistLecturas l = new OptimistLecturas();
        l.transaccion = true;
        l.autoCommit = false;
        ACTUAL.set(l);
        try {
            T r = trabajo.hacer();
            if (l.conexion != null) l.conexion.commit();
            return r;
        } catch (SQLException | RuntimeException e) {
            if (l.conexion != null) { try { l.conexion.rollback(); } catch (SQLException ignorado) { } }
            throw e;
        } finally {
            if (afuera == null) ACTUAL.remove(); else ACTUAL.set(afuera);
            if (l.conexion != null) {
                try { l.conexion.setAutoCommit(true); } catch (SQLException ignorado) { }
                try { l.conexion.close(); } catch (SQLException ignorado) { }
                l.conexion = null;
            }
        }
    }

    /** V65k: hay una transacción de Confirmar en curso (el grabado de la TRN usa su conexión). */
    static boolean enTransaccion() {
        OptimistLecturas l = ACTUAL.get();
        return l != null && l.transaccion;
    }

    /**
     * Abridor para las reglas y las fórmulas: si hay un caché activo, todas comparten una conexión que se abre
     * la primera vez que se usa de verdad (si todo sale de memoria, no se abre ninguna). Si no, el real.
     */
    public static OptimistCalculo.Abridor compartido(OptimistCalculo.Abridor real) {
        return () -> {
            OptimistLecturas l = ACTUAL.get();
            if (l == null) return real.abrir();
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, m, args) -> {
                        switch (m.getName()) {
                            case "close": return null;                        // la cierra con() al terminar
                            case "isClosed": return false;
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            case "toString": return "conexión compartida";
                            case "getAutoCommit": if (l.transaccion) return false; if (l.conexion == null) return l.autoCommit; break;
                            case "setAutoCommit": if (l.transaccion) return null; if (l.conexion == null) { l.autoCommit = (Boolean) args[0]; return null; } break;
                            case "commit": if (l.transaccion || l.conexion == null) return null; break;   // V65k: confirma enTransaccion al final
                            case "rollback": if (l.conexion == null && (args == null || args.length == 0)) return null; break;   // nada que deshacer
                            case "getCatalog": if (l.catalogo != null) return l.catalogo; break;
                            default: break;
                        }
                        if (l.conexion == null) {
                            l.conexion = real.abrir();
                            l.conexion.setAutoCommit(l.autoCommit);
                        }
                        try {
                            Object r = m.invoke(l.conexion, args);
                            if (m.getName().equals("getCatalog")) l.catalogo = (String) r;
                            return r;
                        } catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                    });
        };
    }

    /** SELECT con parámetros; devuelve las filas (cada una con 'columnas' valores). Usa el caché si está activo. */
    static List<Object[]> leer(Connection c, String sql, List<?> parametros, int columnas) throws SQLException {
        OptimistLecturas l = ACTUAL.get();
        if (l == null) return consultar(c, sql, parametros, columnas);
        l.vencer();
        String clave = sql + "\u0000" + parametros;
        List<Object[]> r = l.filas.get(clave);
        if (r != null) { l.recordadas++; return r; }
        l.consultas++;
        long t0 = System.nanoTime();
        r = consultar(c, sql, parametros, columnas);
        String o = ORIGEN.get();
        if (o == null) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\bFROM\\s+`?(\\w+)").matcher(sql); o = m.find() ? "tabla " + m.group(1) : "?"; }
        long[] t = l.porOrigen.computeIfAbsent(o, x -> new long[2]); t[0]++; t[1] += System.nanoTime() - t0;
        l.filas.put(clave, r);
        return r;
    }

    /** V48c: lectura que bloquea (FOR UPDATE): siempre de la base, nunca de memoria. */
    static List<Object[]> leerSinMemoria(Connection c, String sql, List<?> parametros, int columnas) throws SQLException {
        return consultar(c, sql, parametros, columnas);
    }

    private void vencer() {
        if (System.currentTimeMillis() - desde > VIGENCIA_MS) { filas.clear(); llamadas.clear(); desde = System.currentTimeMillis(); }
    }

    /** Algo escribió en la base: lo leído puede haber cambiado. */
    static void escribio() {
        OptimistProcedimiento.olvidarDatos();   // V66z: lo recordado de GetArtCosto, GetStockArticulo... tampoco vale
        OptimistLecturas l = ACTUAL.get();
        if (l != null) { l.filas.clear(); l.llamadas.clear(); l.escrituras++; }
    }

    public interface Llamada { Object hacer() throws SQLException; }

    /**
     * V37: un procedimiento llamado desde una fórmula con los mismos argumentos devuelve lo mismo mientras se edita
     * (el costo o el stock del artículo no dependen de la cantidad que se está cargando). Si al correr escribió
     * en la base, no se recuerda.
     */
    static Object llamada(List<Object> clave, Llamada hacer) throws SQLException {
        OptimistLecturas l = ACTUAL.get();
        if (l == null) return hacer.hacer();
        l.vencer();
        if (l.llamadas.containsKey(clave)) { l.recordadas++; return l.llamadas.get(clave); }
        int antes = l.escrituras;
        Object r = hacer.hacer();
        if (l.escrituras == antes) l.llamadas.put(new ArrayList<>(clave), r);   // (los argumentos pueden ser null)
        return r;
    }

    private static List<Object[]> consultar(Connection c, String sql, List<?> parametros, int columnas) throws SQLException {
        List<Object[]> r = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.size(); i++) p.setObject(i + 1, parametros.get(i));
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Object[] f = new Object[columnas];
                    for (int i = 0; i < columnas; i++) f[i] = rs.getObject(i + 1);
                    r.add(f);
                }
            }
        }
        return Collections.unmodifiableList(r);
    }

    /** Para el registro: cuántas fueron a la base y cuántas salieron de memoria. */
    /** V49y: las n que más tardaron, para la consola: "GetX:12 40 consultas 800 ms; tabla Articulos 12 consultas 90 ms". */
    public String detalle(int n) {
        StringBuilder b = new StringBuilder();
        porOrigen.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(n)
                .forEach(e -> b.append("\n   ").append(e.getKey()).append(": ").append(e.getValue()[0]).append(" consultas, ")
                        .append(e.getValue()[1] / 1_000_000).append(" ms"));
        return b.toString();
    }

    public String resumen() { return consultas + " a la base, " + recordadas + " de memoria"; }
}
