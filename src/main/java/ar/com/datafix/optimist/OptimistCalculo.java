package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Nivel;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Trn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * V34 - Calcula las fórmulas de una TRN (cabecera y líneas de los detalles) con todo el contexto:
 * sum/count/... sobre las líneas, otras fórmulas (en el orden que haga falta), procedimientos (proc/*.proc)
 * y find() en la base. Lo usa el formulario (en vivo, cada vez que cambia algo) y el grabado (fórmulas guardar: true).
 *
 * Si una fórmula falla (por ejemplo, llama a un procedimiento que todavía no está), queda vacía y el motivo
 * va a errores(): el resto se calcula igual.
 */
public final class OptimistCalculo {

    /** Abre una conexión cuando hace falta (solo si alguna fórmula llama a un procedimiento o usa find). */
    @FunctionalInterface
    public interface Abridor { Connection abrir() throws SQLException; }

    /** Valores calculados: cabecera (campo -> valor) y, por detalle, uno por línea (en el mismo orden). */
    public record Resultado(Map<String, Object> cabecera, Map<String, List<Map<String, Object>>> lineas, Map<String, String> errores) {
        /**
         * Pone en los valores solo las fórmulas que se graban (guardar: true).
         * V41: una fórmula que no se pudo calcular NO se graba (queda lo que había; no se pisa con vacío).
         */
        public void aplicarGuardadas(Trn trn, Map<String, Object> cab, Map<String, List<Map<String, Object>>> detalles) {
            for (Campo c : trn.cabecera().campos())
                if (c.formula() != null && c.guardar() && cabecera.containsKey(c.nombre()) && !errores.containsKey(c.nombre()))
                    cab.put(c.nombre(), cabecera.get(c.nombre()));
            for (Nivel det : trn.detalles()) {
                List<Map<String, Object>> calc = lineas.get(det.nombre()), vals = detalles.get(det.nombre());
                if (calc == null || vals == null) continue;
                for (int i = 0; i < Math.min(calc.size(), vals.size()); i++)
                    for (Campo c : det.campos())
                        if (c.formula() != null && c.guardar() && calc.get(i).containsKey(c.nombre()) && !errores.containsKey(c.nombre()))
                            vals.get(i).put(c.nombre(), calc.get(i).get(c.nombre()));
            }
        }
    }

    private static final Map<String, Object> FORMULAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Fórmula ya leída (o el error de lectura, como String). */
    private static Object formula(String texto) {
        return FORMULAS.computeIfAbsent(texto, t -> {
            try { return OptimistFormula.de(t); } catch (IllegalArgumentException e) { return e.getMessage(); }
        });
    }

    /** true si la TRN tiene alguna fórmula (si no, no hace falta recalcular nada). */
    public static boolean tieneFormulas(Trn trn) {
        if (trn.cabecera().campos().stream().anyMatch(c -> c.formula() != null)) return true;
        return trn.detalles().stream().anyMatch(d -> d.campos().stream().anyMatch(c -> c.formula() != null));
    }

    private static final boolean DEPURAR = Boolean.getBoolean("optimist.reglas.depurar") || "1".equals(System.getenv("OPTIMIST_DEPURAR"));

    public static Resultado calcular(Trn trn, Map<String, Object> cabecera, Map<String, List<Map<String, Object>>> detalles, Abridor abridor) {
        Calculo k = new Calculo(trn, detalles, abridor);
        try {
            Ctx cab = new Ctx(k, trn.cabecera(), cabecera, null);
            Map<String, Object> rc = new LinkedHashMap<>();
            for (Campo c : trn.cabecera().campos()) if (c.formula() != null) rc.put(c.nombre(), cab.valor(c.nombre()));
            Map<String, List<Map<String, Object>>> rl = new LinkedHashMap<>();
            for (Nivel det : trn.detalles()) {
                if (det.campos().stream().noneMatch(c -> c.formula() != null)) continue;
                List<Map<String, Object>> ls = new ArrayList<>();
                for (Ctx l : k.lineas(det, cab)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (Campo c : det.campos()) if (c.formula() != null) m.put(c.nombre(), l.valor(c.nombre()));
                    ls.add(m);
                }
                rl.put(det.nombre(), ls);
            }
            long total = k.tiempos.values().stream().mapToLong(t -> t[0]).max().orElse(0) / 1_000_000;
            // V48: qué fórmulas tardan (tiempo incluido el de las que usan). V49w: también sin depurar, si alguna pasa de 1 segundo
            if (!k.tiempos.isEmpty() && (DEPURAR || total >= 1000)) {
                StringBuilder b = new StringBuilder("[" + trn.nombre() + "] fórmulas más lentas:");
                k.tiempos.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0])).limit(12)
                        .forEach(e -> b.append("\n   ").append(e.getKey()).append(": ").append(e.getValue()[0] / 1_000_000).append(" ms en ")
                                .append(e.getValue()[1]).append(" cálculos"));
                System.out.println(b);
            }
            return new Resultado(rc, rl, k.errores);
        } finally {
            k.cerrar();
        }
    }

    /** Estado de un cálculo: conexión (si hizo falta), cachés de procedimientos y find, errores. */
    private static final class Calculo {
        final Trn trn; final Map<String, List<Map<String, Object>>> detalles; final Abridor abridor;
        final Map<String, String> errores = new LinkedHashMap<>();
        final Map<List<Object>, Object> llamadas = new HashMap<>();
        final Map<String, long[]> tiempos = new HashMap<>();   // V48: depuración
        final Map<Nivel, List<Ctx>> lineas = new IdentityHashMap<>();
        Connection conexion;

        Calculo(Trn trn, Map<String, List<Map<String, Object>>> detalles, Abridor abridor) {
            this.trn = trn; this.detalles = detalles; this.abridor = abridor;
        }

        Connection conexion() {
            try {
                if (conexion == null) {
                    if (abridor == null) throw new IllegalStateException("no hay conexión a la base");
                    conexion = abridor.abrir();
                }
                return conexion;
            } catch (SQLException e) { throw new IllegalStateException(e.getMessage(), e); }
        }

        void cerrar() {
            if (conexion != null) try { conexion.close(); } catch (SQLException ignorado) { }
        }

        List<Ctx> lineas(Nivel det, Ctx cab) {
            return lineas.computeIfAbsent(det, d -> {
                List<Ctx> r = new ArrayList<>();
                for (Map<String, Object> v : detalles.getOrDefault(d.nombre(), List.of())) r.add(new Ctx(this, d, v, cab));
                return r;
            });
        }
    }

    /** Contexto de un nivel (cabecera o una línea): valores, fórmulas calculadas, cabecera para las líneas. */
    private static final class Ctx implements OptimistFormula.Contexto {
        final Calculo k; final Nivel nivel; final Ctx padre;
        final Map<String, Object> valores = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Map<String, Object> calculados = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final Set<String> enCurso = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        Ctx(Calculo k, Nivel nivel, Map<String, Object> valores, Ctx padre) {
            this.k = k; this.nivel = nivel; this.padre = padre;
            if (valores != null) valores.forEach((a, v) -> { if (a != null) this.valores.put(a, v); });
        }

        @Override public Object valor(String atributo) {
            Campo c = nivel.campo(atributo);
            if (c != null && c.formula() != null) return formula(c);
            if (valores.containsKey(atributo)) return valores.get(atributo);
            if (c == null && padre != null) return padre.valor(atributo);
            return null;
        }

        private Object formula(Campo c) {
            if (calculados.containsKey(c.nombre())) return calculados.get(c.nombre());
            if (!enCurso.add(c.nombre())) return null;   // se usa a sí misma (directa o indirectamente)
            Object r = null;
            long t0 = System.nanoTime();   // V49w: siempre (es barato): las lentas se avisan solas
            try {
                Object f = OptimistCalculo.formula(c.formula());
                if (f instanceof String error) throw new IllegalArgumentException(error);
                r = redondear(c, ((OptimistFormula) f).evaluar(this));
            } catch (RuntimeException e) {
                k.errores.putIfAbsent(c.nombre(), e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                enCurso.remove(c.nombre());
                { long[] t = k.tiempos.computeIfAbsent(c.nombre(), x -> new long[2]); t[0] += System.nanoTime() - t0; t[1]++; }
            }
            calculados.put(c.nombre(), r);
            return r;
        }

        @Override public List<OptimistFormula.Contexto> lineas(String atributo) {
            if (padre != null) return padre.lineas(atributo);
            for (Nivel det : k.trn.detalles())
                if (det.campo(atributo) != null) return new ArrayList<>(k.lineas(det, this));
            return null;
        }

        @Override public Object procedimiento(String nombre, List<Object> args) {
            Object b = OptimistProcedimiento.Funciones.llamar(nombre, args);
            if (b != OptimistProcedimiento.Funciones.NO_ES) return b;
            if (nombre.startsWith(".")) throw new IllegalStateException("el método " + nombre.substring(1) + "() todavía no está soportado");
            List<Object> clave = new ArrayList<>();
            clave.add(nombre.toLowerCase(Locale.ROOT));
            clave.addAll(args);
            if (k.llamadas.containsKey(clave)) return k.llamadas.get(clave);
            if (!OptimistProcedimientos.existe(nombre))
                throw new IllegalStateException("falta el procedimiento " + nombre + " (proc/" + nombre + ".proc)");
            try {
                Object r = OptimistLecturas.llamada(clave, () -> OptimistProcedimientos.udp(nombre, args, k.conexion()));
                k.llamadas.put(clave, r);
                return r;
            } catch (SQLException e) { throw new IllegalStateException(nombre + ": " + e.getMessage(), e); }
        }

        @Override public Object buscar(String tabla, String columna, Map<String, Object> igualdades) {
            List<Object> clave = new ArrayList<>(List.of("find", tabla.toLowerCase(Locale.ROOT), columna.toLowerCase(Locale.ROOT)));
            igualdades.forEach((a, v) -> { clave.add(a.toLowerCase(Locale.ROOT)); clave.add(v); });
            if (k.llamadas.containsKey(clave)) return k.llamadas.get(clave);
            try {
                Object r = OptimistCalculo.buscar(k.conexion(), tabla, columna, igualdades);
                k.llamadas.put(clave, r);
                return r;
            } catch (SQLException e) { throw new IllegalStateException("find: " + e.getMessage(), e); }
        }
    }

    /** find(): primera fila de 'tabla' con esas igualdades; valor de 'columna' (null si no hay). */
    static Object buscar(Connection c, String tabla, String columna, Map<String, Object> igualdades) throws SQLException {
        Map<String, OptimistProcedimiento.TablaBD> esq = OptimistProcedimiento.esquema(c);
        OptimistProcedimiento.TablaBD t = esq.get(tabla.toLowerCase(Locale.ROOT));
        if (t == null) throw new IllegalStateException("find: no existe la tabla " + tabla);
        String col = t.columnas().get(columna.toLowerCase(Locale.ROOT));
        if (col == null) throw new IllegalStateException("find: la tabla " + tabla + " no tiene " + columna);
        List<String> w = new ArrayList<>();
        List<Object> vs = new ArrayList<>();
        for (var e : igualdades.entrySet()) {
            String cw = t.columnas().get(e.getKey().toLowerCase(Locale.ROOT));
            if (cw == null) throw new IllegalStateException("find: la tabla " + tabla + " no tiene " + e.getKey());
            w.add("`" + cw + "` = ?");
            vs.add(e.getValue() instanceof Boolean b ? (b ? 1 : 0) : e.getValue());
        }
        String sql = "SELECT `" + col + "` FROM `" + t.nombre() + "`" + (w.isEmpty() ? "" : " WHERE " + String.join(" AND ", w)) + " LIMIT 1";
        List<Object[]> fs = OptimistLecturas.leer(c, sql, vs, 1);   // V36: de memoria si ya se leyó
        Object r = fs.isEmpty() ? null : fs.get(0)[0];
        if (r instanceof Number n && !(r instanceof BigDecimal)) r = new BigDecimal(n.toString());
        if (r instanceof java.sql.Date d) r = d.toLocalDate();
        if (r instanceof java.sql.Timestamp ts) r = ts.toLocalDateTime();
        return r;
    }

    /** Como se graba: del tipo del campo (fecha, número con los decimales del dominio, texto, sí/no). */
    static Object redondear(Campo c, Object r) {
        if (r == null) return null;
        var d = c.dominio();
        switch (d.tipo()) {
            case DATE:
                return r instanceof java.time.LocalDate ? r : r instanceof java.time.LocalDateTime t ? t.toLocalDate() : null;
            case DATETIME:
                return r instanceof java.time.LocalDateTime ? r : r instanceof java.time.LocalDate f ? f.atStartOfDay() : null;
            case BOOLEAN:
                return OptimistFormula.verdad(r);
            case NUMERIC: {
                BigDecimal b;
                if (r instanceof BigDecimal x) b = x;
                else if (r instanceof Number n) b = new BigDecimal(n.toString());
                else if (r instanceof Boolean x) b = x ? BigDecimal.ONE : BigDecimal.ZERO;
                else try { b = new BigDecimal(String.valueOf(r).trim()); } catch (NumberFormatException e) { return null; }
                return d.esEntero() ? (Object) b.setScale(0, RoundingMode.HALF_UP).longValue() : b.setScale(d.decimales(), RoundingMode.HALF_UP);
            }
            default: {
                String t = r instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(r);
                return d.longitud() > 0 && t.length() > d.longitud() ? t.substring(0, d.longitud()) : t;
            }
        }
    }
}
