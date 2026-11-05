package ar.com.datafix.optimist;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** E6x: 'porRenglon:' de las consultas (consultas/*.yaml). */
public final class OptimistPorRenglon {
    private OptimistPorRenglon() { }

    /**
     * E6x: un procedimiento que se corre por cada renglón de la página que se muestra, con parámetros del renglón (o constantes,
     * o &Variables de lo recibido / de 'variables:'), y cuyos out van a columnas (declaradas en columnasSql con sql: "NULL").
     * Como el Grid.Load de GeneXus que llama a un procedimiento. Esas columnas no se filtran ni se ordenan (son de la página).
     */
    public record PorRenglon(String proc, List<String> parametros, Map<String, String> devuelve) {}

    @SuppressWarnings("unchecked")
    public static List<PorRenglon> leer(Object def) {
        List<PorRenglon> r = new ArrayList<>();
        if (def == null) return r;
        if (!(def instanceof List<?> l)) throw new IllegalArgumentException("porRenglon: debe ser una lista de {proc, parametros, devuelve}");
        for (Object o : l) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.get("proc") == null) throw new IllegalArgumentException("porRenglon: falta 'proc'");
            List<String> ps = m.get("parametros") instanceof List<?> lp ? lp.stream().map(x -> String.valueOf(x).trim()).toList() : List.of();
            Map<String, String> dev = new LinkedHashMap<>();
            if (m.get("devuelve") instanceof Map<?, ?> dm) dm.forEach((k, v) -> dev.put(String.valueOf(k).trim(), String.valueOf(v).trim().replaceFirst("^&", "").toLowerCase(Locale.ROOT)));
            if (dev.isEmpty()) throw new IllegalArgumentException("porRenglon " + m.get("proc") + ": falta 'devuelve' ({Columna: &ParametroOut})");
            r.add(new PorRenglon(String.valueOf(m.get("proc")).trim(), ps, dev));
        }
        return r;
    }

    /** E6x: corre los porRenglon sobre las filas (la página). vars: lo recibido y las variables de la consulta. */
    public static void calcular(List<PorRenglon> prs, List<Map<String, Object>> filas, Map<String, Object> vars, Connection c) throws SQLException {
        if (prs == null || prs.isEmpty() || filas == null) return;
        for (Map<String, Object> f : filas) {
            Map<String, Object> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            f.forEach((k, v) -> { if (k != null) ci.put(k, v); });
            for (PorRenglon pr : prs) {
                List<Object> args = new ArrayList<>();
                for (String p : pr.parametros()) {
                    if (p.matches("-?\\d+(\\.\\d+)?")) args.add(new java.math.BigDecimal(p));
                    else if (p.length() >= 2 && (p.startsWith("'") && p.endsWith("'") || p.startsWith("\"") && p.endsWith("\""))) args.add(p.substring(1, p.length() - 1));
                    else if (p.startsWith("&")) { Object v = null; if (vars != null) for (var e : vars.entrySet()) if (e.getKey().equalsIgnoreCase(p.substring(1))) v = e.getValue(); args.add(v); }
                    else args.add(ci.get(p));
                }
                Map<String, Object> out;
                try { out = OptimistProcedimientos.ejecutar(pr.proc(), args, c, new ArrayList<>()); }
                catch (RuntimeException ex) { throw new IllegalStateException(pr.proc() + ": " + ex.getMessage(), ex); }
                pr.devuelve().forEach((col, par) -> { Object v = out.get(par); f.put(col, v); ci.put(col, v); });
            }
        }
    }

}
