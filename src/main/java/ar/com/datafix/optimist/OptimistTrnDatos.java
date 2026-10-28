package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * V21 - Acceso JDBC genérico para cualquier TRN definida en YAML (cabecera + N detalles).
 * Tablas y columnas salen de la definición (validadas como identificadores);
 * los valores van siempre como parámetros. Cabecera + detalles se guardan en UNA transacción.
 *
 * V21b: varios detalles, referencias (trae descripciones de otras tablas y valida que la clave exista),
 * clave autonumerada, valores por defecto y auditoría.
 */
@Component
public class OptimistTrnDatos {

    /** Una línea de un detalle. claveOriginal == null: línea nueva. */
    public record Linea(Map<String, Object> valores, Map<String, Object> claveOriginal) {
        public boolean nueva() { return claveOriginal == null; }
    }
    /** detalles: nombre del detalle -> líneas. */
    public record Registro(Map<String, Object> cabecera, Map<String, List<Linea>> detalles) {
        public List<Linea> lineas(String detalle) { return detalles.getOrDefault(detalle, List.of()); }
    }

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final OptimistDatabase database;
    /** V48: Business Components: todo con esta conexión, dentro de la transacción del procedimiento (sin commit propio). */
    private final Connection fija;

    @org.springframework.beans.factory.annotation.Autowired
    public OptimistTrnDatos(OptimistDatabase database) { this.database = database; this.fija = null; }

    /** V48: para los Business Components de un procedimiento: lee y graba con 'c', sin cerrarla ni confirmar. */
    public OptimistTrnDatos(Connection c) { this.database = null; this.fija = c; }

    /** V65m: clave duplicada (MySQL 1062 / 1586). Las otras violaciones (clave foránea 1452, nulo 1048...) NO lo son. */
    static boolean esDuplicado(SQLException ex) {
        return ex.getErrorCode() == 1062 || ex.getErrorCode() == 1586
                || (ex.getErrorCode() == 0 && "23505".equals(ex.getSQLState()));   // (otras bases: unique_violation)
    }

    private static final java.util.regex.Pattern FK = java.util.regex.Pattern.compile(
            "(?i)FOREIGN KEY \\(([^)]*)\\) REFERENCES `?(\\w+)`? \\(([^)]*)\\)");

    /**
     * V65m: el error de la base al grabar en 'tabla', dicho para el usuario. Clave foránea (1452): "No existe en Almacenes
     * AlmacenesEmp=1, AlmacenesDiv=2, AlmacenesId=1 (al grabar MovStockDet)". 'valores': columna (cualquier mayúscula) -> valor
     * de lo que se quiso grabar (puede ser null). Antes un error así se tomaba como "clave duplicada" y se seguía de largo.
     */
    static SQLException errorIntegridad(SQLException ex, String tabla, Map<String, Object> valores) {
        String m = ex.getMessage() == null ? "" : ex.getMessage();
        if (ex.getErrorCode() == 1452) {
            java.util.regex.Matcher x = FK.matcher(m);
            if (x.find()) {
                String[] mias = x.group(1).replace("`", "").split("\\s*,\\s*");
                String[] suyas = x.group(3).replace("`", "").split("\\s*,\\s*");
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < suyas.length; i++) {
                    Object v = null;
                    if (valores != null && i < mias.length)
                        for (Map.Entry<String, Object> e : valores.entrySet()) if (e.getKey().equalsIgnoreCase(mias[i].trim())) v = e.getValue();
                    if (b.length() > 0) b.append(", ");
                    b.append(suyas[i].trim()).append(valores == null ? "" : "=" + (v == null ? "(vacío)" : v));
                }
                return new SQLException("No existe en " + x.group(2) + " " + b + " (al grabar " + tabla + ").", ex.getSQLState(), ex.getErrorCode(), ex);
            }
            return new SQLException("Falta un registro relacionado al grabar " + tabla + ": " + m, ex.getSQLState(), ex.getErrorCode(), ex);
        }
        if (ex.getErrorCode() == 1048) return new SQLException("Falta un dato obligatorio al grabar " + tabla + ": " + m, ex.getSQLState(), ex.getErrorCode(), ex);
        return new SQLException("No se pudo grabar " + tabla + ": " + m, ex.getSQLState(), ex.getErrorCode(), ex);
    }

    private Connection abrir() throws SQLException {
        if (fija == null) return database.openConnection();
        // la conexión del procedimiento: close / commit / rollback / setAutoCommit no hacen nada (los decide el procedimiento)
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "close", "commit", "setAutoCommit" -> null;
                    // V65m: rollback(Savepoint) sí (deshace solo lo de un Business Component que falló); rollback() no
                    case "rollback" -> {
                        if (args == null || args.length == 0) yield null;
                        try { yield m.invoke(fija, args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    }
                    default -> {
                        try { yield m.invoke(fija, args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    }
                });
    }

    // ------------------------------------------------------------------ V65: siguiente número

    /**
     * V65: el número que se propone en el alta para una clave con 'siguiente: true': el mayor de la tabla + 1. Si la clave
     * es compuesta, entre los registros con las demás claves en su valor por defecto (ej. la empresa del usuario).
     * null si no se pudo calcular (el campo queda vacío, como antes).
     */
    public Object siguiente(Nivel nivel, Campo campo) {
        List<Campo> otras = nivel.claves().stream().filter(k -> !k.nombre().equalsIgnoreCase(campo.nombre()) && k.defectoActual() != null).toList();
        String sql = "SELECT COALESCE(MAX(" + col(campo.nombre()) + "),0)+1 FROM " + nivel.tabla()
                + (otras.isEmpty() ? "" : " WHERE " + otras.stream().map(k -> col(k.nombre()) + "=?").collect(Collectors.joining(" AND ")));
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement(sql)) {
            int i = 1;
            for (Campo k : otras) p.setObject(i++, k.defectoActual());
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? OptimistTrnDefinicion.normalizar(campo.dominio(), r.getLong(1)) : null;
            }
        } catch (SQLException | RuntimeException e) {
            System.out.println("[TRN] " + nivel.tabla() + "." + campo.nombre() + ": no se pudo calcular el siguiente: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ lectura

    /** WorkWith: solo claves + columnas del WorkWith (una tabla de artículos puede tener cientos de columnas). */
    public List<Map<String, Object>> listar(Trn trn) throws SQLException {
        try { return listar0(trn); } catch (SQLException e) { throw diagnosticar(trn, e); }
    }

    private List<Map<String, Object>> listar0(Trn trn) throws SQLException {
        ConsultaWw q = new ConsultaWw(trn, true);
        return q.leer(q.sql(null, false, -1, 0));
    }

    // ------------------------------------------------------------------ V50o: WorkWith paginado en la base

    /**
     * V50o: una página del WorkWith (workwith.paginarEnServidor): solo los renglones que se muestran (LIMIT), con los filtros
     * de las columnas en el WHERE y el orden en el ORDER BY. filtros: clave de la columna -> el texto del filtro de la grilla
     * (texto común: contiene; números y fechas con la marca de OptimistWorkWith). orden: clave de la columna o null (el del YAML).
     * cantidad < 0 = todos (para el Excel).
     */
    public List<Map<String, Object>> listarPagina(Trn trn, Map<String, String> filtros, String orden, boolean desc,
                                                  int desde, int cantidad) throws SQLException {
        return listarPagina(trn, filtros, orden, desc, desde, cantidad, null);
    }

    /** V50o: cuántos renglones cumplen el filtro fijo y los de las columnas (solo con los JOIN que esos filtros necesitan). */
    public int contar(Trn trn, Map<String, String> filtros) throws SQLException {
        return contar(trn, filtros, null);
    }

    /**
     * V52: columnas calculadas que se suman a la consulta del WorkWith de una TRN (las consultas, ej. el stock de los
     * artículos). join: un LEFT JOIN más (ej. a una tabla derivada con las sumas por artículo, alias libre) con sus
     * parámetros; columnas: clave -> expresión SQL (se leen en la fila con esa clave, se pueden filtrar como números y
     * ordenar); condicion: una condición más del WHERE (ej. "stock positivo") con sus parámetros. El join se agrega solo
     * si alguna columna, filtro, orden o la condición lo usa (conJoin).
     */
    public record Extra(String join, List<Object> paramsJoin, Map<String, String> columnas, String condicion, List<Object> paramsCondicion,
                        Map<String, Object> variables) {
        public Extra {
            paramsJoin = paramsJoin == null ? List.of() : paramsJoin;
            columnas = columnas == null ? Map.of() : columnas;
            paramsCondicion = paramsCondicion == null ? List.of() : paramsCondicion;
            variables = variables == null ? Map.of() : variables;
        }
        public Extra(String join, List<Object> paramsJoin, Map<String, String> columnas, String condicion, List<Object> paramsCondicion) {
            this(join, paramsJoin, columnas, condicion, paramsCondicion, null);
        }
    }

    /**
     * V66l: las variables de una consulta (&Fecha, &Empresa, un parámetro de ParamGlb...) como variables de la sesión de
     * MySQL (@cns_Fecha), en la MISMA conexión y antes de la consulta: así se pueden usar en cualquier parte del SQL
     * (columnas, join, condición, filtros y orden) sin cuidar el orden de los '?'.
     */
    static void ponerVariables(Connection c, Extra extra) throws SQLException {
        if (extra == null || extra.variables().isEmpty()) return;
        List<String> partes = new ArrayList<>();
        List<Object> vs = new ArrayList<>();
        for (Map.Entry<String, Object> e : extra.variables().entrySet()) {
            if (!e.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            partes.add("@cns_" + e.getKey() + "=?");
            Object v = e.getValue();
            if (v instanceof java.time.LocalDate d) v = java.sql.Date.valueOf(d);
            else if (v instanceof java.time.LocalDateTime d) v = java.sql.Timestamp.valueOf(d);
            else if (v instanceof Boolean b) v = b ? 1 : 0;
            vs.add(v);
        }
        if (partes.isEmpty()) return;
        try (PreparedStatement p = c.prepareStatement("SET " + String.join(",", partes))) {
            for (int i = 0; i < vs.size(); i++) p.setObject(i + 1, vs.get(i));
            p.execute();
        }
    }

    /** V52: una página del WorkWith con las columnas calculadas de 'extra' (null = como siempre). */
    public List<Map<String, Object>> listarPagina(Trn trn, Map<String, String> filtros, String orden, boolean desc,
                                                  int desde, int cantidad, Extra extra) throws SQLException {
        try {
            // (sin el join de una consulta: puede repetir la clave, ej. una fila por cuota en la cuenta corriente)
            if (cantidad >= 0 && trn.cabecera().claves().size() == 1 && (extra == null || extra.join() == null || extra.join().isBlank())) {
                // V84h: en dos pasos (Diego: la página de Comprobantes tardaba 4,5 s). Primero las claves de la página, con
                // solo los JOIN que piden los filtros y el orden; después las columnas (los LEFT JOIN de las inferidas y las
                // subconsultas, como el Saldo) solo para esos renglones. En una sola consulta, MySQL armaba todas las columnas
                // de TODOS los comprobantes filtrados para ordenarlos y quedarse con 40.
                ConsultaWw k = new ConsultaWw(trn, false);
                k.extra(extra, false);
                k.filtrar(filtros);
                k.condicionExtra();
                String claves = k.sqlClaves(orden, desc, cantidad, desde);
                ConsultaWw q = new ConsultaWw(trn, true);
                q.extra(extra, true);
                String col = "t0." + col(trn.cabecera().claves().get(0).nombre());
                q.condiciones.add(col + " IN (SELECT k0 FROM (" + claves + ") kk)");
                q.params.add(new Object[]{SUBCONSULTA, k});
                return q.leer(q.sql(orden, desc, -1, 0));
            }
            ConsultaWw q = new ConsultaWw(trn, true);
            q.extra(extra, true);
            q.filtrar(filtros);
            q.condicionExtra();
            return q.leer(q.sql(orden, desc, cantidad, desde));
        } catch (SQLException e) { throw diagnosticar(trn, e); }
    }

    /** V52: el COUNT con las columnas calculadas: el join de 'extra' solo si un filtro o la condición lo necesitan. */
    public int contar(Trn trn, Map<String, String> filtros, Extra extra) throws SQLException {
        return contarYSumar(trn, filtros, extra, List.of()).cantidad();
    }

    /** V77: las monedas (tabla Monedas) para el combo del filtro de la columna de moneda; vacío si no se pudieron leer. */
    public List<OptimistTrnDefinicion.Valor> monedas() {
        List<OptimistTrnDefinicion.Valor> r = new ArrayList<>();
        try (Connection c = abrir(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT MonedasId, MonedasDescrip FROM Monedas ORDER BY MonedasId")) {
            while (rs.next()) {
                String id = texto(rs.getObject(1)), de = texto(rs.getObject(2));
                if (id == null || id.isBlank()) continue;
                r.add(new OptimistTrnDefinicion.Valor(id.strip(), id.strip() + (de == null || de.isBlank() ? "" : " - " + de.strip())));
            }
        } catch (SQLException | RuntimeException ex) { return List.of(); }
        return r;
    }

    /** V75: la cantidad de registros y la suma de algunas columnas (claves de la grilla), con los mismos filtros. */
    public record Conteo(int cantidad, Map<String, BigDecimal> sumas, List<PorMoneda> porMoneda) {
        public Conteo(int cantidad, Map<String, BigDecimal> sumas) { this(cantidad, sumas, List.of()); }
        public static Conteo de(int cantidad) { return new Conteo(cantidad, Map.of()); }
    }
    /** V77: los totales de una moneda (moneda: el código como está en la tabla Monedas, ej. "$", "U$S", "USD"). */
    public record PorMoneda(String moneda, int cantidad, Map<String, BigDecimal> sumas) {}

    public Conteo contarYSumar(Trn trn, Map<String, String> filtros, Extra extra, java.util.Collection<String> sumar) throws SQLException {
        return contarYSumar(trn, filtros, extra, sumar, null);
    }

    /**
     * V75: COUNT(*) y SUM(columna) en la MISMA consulta (una sola lectura): los totales de importes al pie del WorkWith.
     * sumar: claves de columnas de la cabecera (propias o inferidas) o de 'extra' (columnasSql); las que no se pueden
     * sumar en la base (fórmulas que se calculan en Java) se saltean y quedan sin total.
     */
    /** V77: moneda: la columna con la moneda del renglón (o null): los totales salen por moneda (GROUP BY). */
    public Conteo contarYSumar(Trn trn, Map<String, String> filtros, Extra extra, java.util.Collection<String> sumar, String moneda) throws SQLException {
        try {
            ConsultaWw q = new ConsultaWw(trn, false);
            q.extra(extra, false);
            q.filtrar(filtros);
            q.condicionExtra();
            List<String> claves = new ArrayList<>();
            StringBuilder sumas = new StringBuilder();
            for (String k : sumar == null ? List.<String>of() : sumar) {
                String ex = q.exprExtra(k);
                if (ex == null) {
                    Campo cc = trn.cabecera().campo(k);
                    if (cc == null || !cc.dominio().esNumerico() || cc.dominio().esCombo()) continue;
                    try { ex = expresion(trn.cabecera(), cc.nombre(), q.alias, q.joins, 0); }
                    catch (RuntimeException noSeSuma) { continue; }   // fórmula sin columna en la base
                }
                claves.add(k);
                sumas.append(",SUM(").append(ex).append(')');
            }
            // V77: por moneda: una fila por moneda (la misma lectura, agrupada)
            String exMon = null;
            if (moneda != null && !claves.isEmpty()) {
                exMon = q.exprExtra(moneda);
                Campo cm = exMon == null ? trn.cabecera().campo(moneda) : null;
                if (cm != null) {
                    try { exMon = expresion(trn.cabecera(), cm.nombre(), q.alias, q.joins, 0); } catch (RuntimeException sinColumna) { exMon = null; }
                }
            }
            String sql = "SELECT COUNT(*)" + sumas + (exMon == null ? "" : "," + exMon) + " FROM " + trn.cabecera().tabla() + " t0"
                    + q.joins + q.joinExtra() + q.where() + (exMon == null ? "" : " GROUP BY " + exMon + " ORDER BY " + exMon);
            try (Connection c = abrir()) {
                ponerVariables(c, extra);   // V66l
                try (PreparedStatement p = c.prepareStatement(sql)) {
                q.bind(p);
                try (ResultSet r = p.executeQuery()) {
                    int total = 0;
                    Map<String, BigDecimal> m = new LinkedHashMap<>();
                    for (String k : claves) m.put(k, BigDecimal.ZERO);
                    List<PorMoneda> pm = new ArrayList<>();
                    while (r.next()) {
                        int n = r.getInt(1);
                        total += n;
                        Map<String, BigDecimal> sm = new LinkedHashMap<>();
                        for (int i = 0; i < claves.size(); i++) {
                            Object v = r.getObject(i + 2);
                            BigDecimal b = v == null ? BigDecimal.ZERO : decimal(v);
                            sm.put(claves.get(i), b);
                            m.merge(claves.get(i), b, BigDecimal::add);
                        }
                        if (exMon != null) {
                            Object mo = r.getObject(claves.size() + 2);
                            pm.add(new PorMoneda(mo == null ? "" : String.valueOf(mo).strip(), n, sm));
                        }
                        if (exMon == null) break;
                    }
                    return new Conteo(total, m, pm);
                }
                }
            }
        } catch (SQLException e) { throw diagnosticar(trn, e); }
    }

    /** V84h: marca, en los parámetros, de la subconsulta de las claves de la página. */
    private static final Object SUBCONSULTA = new Object();

    /** La consulta del WorkWith: columnas (claves + las del WorkWith + ocultas), LEFT JOIN de las inferidas, filtro fijo y orden. */
    private final class ConsultaWw {
        final Trn trn;
        final Nivel cab;
        final List<Campo> cols = new ArrayList<>();
        final List<Campo> conCodigo = new ArrayList<>();
        final Map<Referencia, String> alias = new LinkedHashMap<>();
        final StringBuilder joins = new StringBuilder();
        final List<String> select = new ArrayList<>();
        final List<String> condiciones = new ArrayList<>();
        /** Parámetros del WHERE, en orden: {Campo o null, valor}. */
        final List<Object[]> params = new ArrayList<>();
        /** V52: columnas calculadas (ver Extra); usaExtra = el join de extra va en la consulta. */
        Extra extra;
        boolean usaExtra;
        final List<String> extraLeidas = new ArrayList<>();

        /** V52: conColumnas = true: las columnas calculadas van en el SELECT (y entonces el join también). */
        void extra(Extra e, boolean conColumnas) {
            extra = e;
            if (e == null || !conColumnas || e.columnas().isEmpty()) return;
            usaExtra = true;
            for (Map.Entry<String, String> x : e.columnas().entrySet()) {
                select.add(x.getValue());
                extraLeidas.add(x.getKey());
            }
        }

        /** V52: la condición de 'extra' (ej. stock positivo) al WHERE. */
        void condicionExtra() {
            if (extra == null || extra.condicion() == null || extra.condicion().isBlank()) return;
            usaExtra = true;
            condiciones.add("(" + extra.condicion() + ")");
            for (Object v : extra.paramsCondicion()) params.add(new Object[]{null, v});
        }

        String joinExtra() { return usaExtra && extra != null && extra.join() != null ? " " + extra.join() : ""; }

        /** V52: la expresión de una columna calculada (y desde ahí la consulta lleva el join), o null si no es de 'extra'. */
        String exprExtra(String clave) {
            if (extra == null || clave == null) return null;
            String e = extra.columnas().get(clave);
            if (e != null) usaExtra = true;
            return e;
        }

        /** conColumnas = false: solo el WHERE (para el COUNT, sin los JOIN de las columnas). */
        ConsultaWw(Trn trn, boolean conColumnas) {
            this.trn = trn;
            this.cab = trn.cabecera();
            if (conColumnas) armarColumnas();
            // V30: filtro fijo del WorkWith (ej. la empresa y división del usuario). Sin valor en el contexto, no filtra.
            for (Map.Entry<String, Object> f : trn.workwith().filtro().entrySet()) {
                Campo c = cab.campo(f.getKey());
                Object crudo = f.getValue();
                String op = "=";
                if (crudo instanceof OptimistTrnDefinicion.Condicion cd) { op = cd.operador(); crudo = cd.valor(); }   // V43
                Object v = OptimistTrnDefinicion.resolver(c.dominio(), crudo);
                if (v == null) continue;
                condiciones.add("t0." + col(c.nombre()) + op + "?");
                params.add(new Object[]{c, v});
            }
        }

        private void armarColumnas() {
            cols.addAll(cab.claves());
            for (String n : trn.workwith().columnas()) {
                Campo c = cab.campo(n);
                if (!cols.contains(c)) cols.add(c);
                // V40: el campo del que sale el color de la insignia (ej. StatusLogisticoColor) también se trae (sin mostrarse)
                if (c.insignia() != null && c.insignia().colorDe() != null) {
                    Campo cc = cab.campo(c.insignia().colorDe());
                    if (cc != null && !cols.contains(cc)) cols.add(cc);
                }
            }
            // V50e: la clave de un enlace (PRSPEDNumero abre el PED por PRSPEDId) también se trae, sin mostrarse
            for (String n : trn.workwith().columnas()) {
                OptimistTrnDefinicion.Enlace en = OptimistTrnDefinicion.enlace(cab.tabla(), cab.campo(n).nombre());
                Campo ck = en == null || en.clave() == null ? null : cab.campo(en.clave());
                if (ck != null && !cols.contains(ck)) cols.add(ck);
            }
            // V41: workwith.ocultas: se traen en la consulta, pero no son columnas (acciones, procedimientos)
            for (String n : trn.workwith().ocultas()) {
                Campo c = cab.campo(n);
                if (c != null && !cols.contains(c)) cols.add(c);
            }
            for (Campo c : cols) select.add(expresion(cab, c.nombre(), alias, joins, 0));
            // V47: las columnas que son la clave interna de una tabla con código (ArticulosPK, EntidadesPK) muestran el código
            for (String n : trn.workwith().columnas()) {
                Campo c = cab.campo(n);
                Referencia ref = referenciaConCodigo(cab, c.nombre());
                if (ref == null || !c.persistido()) continue;
                select.add(codigoDe(c));
                conCodigo.add(c);
            }
        }

        /** La expresión SQL del código que se muestra en lugar de la clave interna (V47), o null si la columna no tiene. */
        String codigoDe(Campo c) {
            Referencia ref = referenciaConCodigo(cab, c.nombre());
            if (ref == null || !c.persistido()) return null;
            return aliasDe(cab, ref, alias, joins, 0) + "." + ref.codigo().columna();
        }

        String where() { return condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones); }

        String sql(String ordenClave, boolean desc, int cantidad, int desde) {
            List<String> ordenSql = new ArrayList<>();
            String ordenExtra = exprExtra(ordenClave);   // V52: ordenar por una columna calculada (ej. Cant. Stock)
            if (ordenExtra != null) ordenSql.add(ordenExtra + (desc ? " DESC" : ""));
            else if (ordenClave != null && cab.campo(ordenClave) != null) {
                Campo c = cab.campo(ordenClave);
                String cod = codigoDe(c);
                ordenSql.add((cod != null ? cod : expresion(cab, c.nombre(), alias, joins, 0)) + (desc ? " DESC" : ""));
            }
            // el orden del YAML: el inicial, y a igualdad del elegido por el usuario
            // V41: el orden puede usar campos inferidos (con el mismo LEFT JOIN de su referencia)
            for (String o : cab.orden()) {
                String[] p = o.trim().split("\\s+");
                ordenSql.add(expresion(cab, p[0], alias, joins, 0) + (p.length > 1 ? " " + p[1] : ""));
            }
            // desempate fijo: las páginas no se pisan. V84h: también con el orden del YAML (ej. CPBCIdDescending, que en los
            // comprobantes grabados sin él queda en 0): con la página en dos pasos, el empate tiene que dar siempre lo mismo
            for (Campo k : cab.claves()) ordenSql.add("t0." + col(k.nombre()));
            String sql = "SELECT " + String.join(",", select) + " FROM " + cab.tabla() + " t0" + joins + joinExtra() + where()
                    + " ORDER BY " + String.join(",", ordenSql);
            if (cantidad >= 0) sql += " LIMIT " + Math.max(0, cantidad) + " OFFSET " + Math.max(0, desde);
            return sql;
        }

        void bind(PreparedStatement p) throws SQLException { bind(p, 1); }

        /** Pone los parámetros desde el '?' número i; devuelve el número del siguiente. */
        int bind(PreparedStatement p, int i) throws SQLException {
            // V52: los parámetros del join de 'extra' van primero (en el SQL el JOIN está antes del WHERE)
            if (usaExtra && extra != null && extra.join() != null) for (Object v : extra.paramsJoin()) p.setObject(i++, v);
            for (Object[] x : params) {
                if (x[0] == SUBCONSULTA && x[1] instanceof ConsultaWw sub) i = sub.bind(p, i);   // V84h: la de las claves
                else if (x[0] instanceof Campo c) escribir(p, i++, c, x[1]);
                else p.setObject(i++, x[1]);
            }
            return i;
        }

        /** V84h: solo la clave de los renglones de la página (alias k0), con el WHERE, el orden y el LIMIT de sql(). */
        String sqlClaves(String ordenClave, boolean desc, int cantidad, int desde) {
            String s = sql(ordenClave, desc, cantidad, desde);   // arma el ORDER BY (y los JOIN que el orden necesite)
            String desdeFrom = s.substring(s.indexOf(" FROM " + cab.tabla() + " t0"));
            return "SELECT t0." + col(cab.claves().get(0).nombre()) + " AS k0" + desdeFrom;
        }

        List<Map<String, Object>> leer(String sql) throws SQLException {
            List<Map<String, Object>> filas = new ArrayList<>();
            try (Connection c = abrir()) {
                ponerVariables(c, extra);   // V66l
                try (PreparedStatement p = c.prepareStatement(sql)) {
                bind(p);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        Map<String, Object> fila = leerFila(r, cols);
                        for (int i = 0; i < conCodigo.size(); i++) fila.put(claveCodigo(conCodigo.get(i).nombre()), texto(r.getObject(cols.size() + i + 1)));
                        // V52: las columnas calculadas van después de las propias y de los códigos
                        for (int i = 0; i < extraLeidas.size(); i++) fila.put(extraLeidas.get(i), r.getObject(cols.size() + conCodigo.size() + i + 1));
                        filas.add(fila);
                    }
                }
                }
            }
            return filas;
        }

        /**
         * Los filtros de las columnas de la grilla, en el WHERE. Igual que el filtro en memoria de OptimistWorkWith:
         * números (=, !=, <, <=, >, >=, entre) y fechas (desde - hasta) por su valor; combos y Sí/No por el texto que se ve
         * (los valores cuyo texto contiene lo escrito); el código en lugar de la clave interna; el resto, "contiene".
         */
        void filtrar(Map<String, String> filtros) {
            if (filtros == null) return;
            for (Map.Entry<String, String> f : filtros.entrySet()) {
                String texto = f.getValue();
                if (texto == null || texto.isBlank()) continue;
                // V52: filtro de una columna calculada (ej. Cant. Stock >= 10): por su expresión, como número
                if (extra != null && extra.columnas().containsKey(f.getKey())) {
                    String ex = exprExtra(f.getKey());
                    if (texto.startsWith(OptimistWorkWith.NUM)) filtroNumero(null, ex, texto);
                    else if (texto.startsWith(OptimistWorkWith.FEC)) filtroFecha(null, ex, texto);   // V66k: columnas SQL de una consulta
                    else if (!texto.startsWith(OptimistWorkWith.MUL)) {                                // V66k: texto, "contiene"
                        condiciones.add(ex + " LIKE ?");
                        params.add(new Object[]{null, "%" + texto.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"});
                    }
                    continue;
                }
                Campo c = cab.campo(f.getKey());
                if (c == null) continue;
                String cod = codigoDe(c);
                String expr = cod != null ? cod : expresion(cab, c.nombre(), alias, joins, 0);
                if (texto.startsWith(OptimistWorkWith.NUM)) { filtroNumero(c, expr, texto); continue; }
                if (texto.startsWith(OptimistWorkWith.FEC)) { filtroFecha(c, expr, texto); continue; }
                OptimistTrnDefinicion.Dominio d = c.dominio();
                // V60: combo de selección múltiple: los valores elegidos del dominio (IN)
                if (texto.startsWith(OptimistWorkWith.MUL)) {
                    List<String> elegidos = OptimistWorkWith.valoresMultiple(texto);
                    if (elegidos.isEmpty()) continue;
                    // V77: combo con valores de una tabla (ej. la moneda): los elegidos tal cual (igual, no "contiene")
                    if (!d.esCombo()) { enLista(c, expr, new ArrayList<>(elegidos)); continue; }
                    List<Object> vs = d.valores() == null ? new ArrayList<>() : d.valores().stream()
                            .filter(v -> elegidos.contains(String.valueOf(v.valor()).trim()))
                            .map(OptimistTrnDefinicion.Valor::valor).collect(Collectors.toList());
                    enLista(c, expr, vs);
                    continue;
                }
                String t = texto.trim().toLowerCase(Locale.ROOT);
                if (cod == null && d.esCombo()) {
                    List<Object> vs = d.valores().stream().filter(v -> v.texto() != null && v.texto().toLowerCase(Locale.ROOT).contains(t))
                            .map(OptimistTrnDefinicion.Valor::valor).collect(Collectors.toList());
                    enLista(c, expr, vs);
                } else if (cod == null && d.tipo() == OptimistTrnDefinicion.Tipo.BOOLEAN) {
                    List<Object> vs = new ArrayList<>();
                    if ("sí".contains(t) || "si".contains(t)) vs.add(Boolean.TRUE);
                    if ("no".contains(t)) vs.add(Boolean.FALSE);
                    enLista(c, expr, vs);
                } else {
                    condiciones.add(expr + " LIKE ?");
                    params.add(new Object[]{null, "%" + texto.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"});
                }
            }
        }

        private void enLista(Campo c, String expr, List<Object> vs) {
            if (vs.isEmpty()) { condiciones.add("1=0"); return; }
            condiciones.add(expr + " IN (" + marcas(vs.size()) + ")");
            for (Object v : vs) params.add(new Object[]{c, v});
        }

        private void filtroNumero(Campo c, String expr, String texto) {
            String[] p = texto.substring(2).split(OptimistWorkWith.SEP, -1);
            String op = p[0];
            java.math.BigDecimal a = OptimistWorkWith.numeroFiltro(p.length > 1 ? p[1] : null), b = OptimistWorkWith.numeroFiltro(p.length > 2 ? p[2] : null);
            if (op.equals("entre")) {
                if (a != null) { condiciones.add(expr + ">=?"); params.add(new Object[]{null, a}); }
                if (b != null) { condiciones.add(expr + "<=?"); params.add(new Object[]{null, b}); }
                return;
            }
            if (a == null) return;
            String sqlOp = switch (op) { case "!=" -> "<>"; case "<", "<=", ">", ">=" -> op; default -> "="; };
            condiciones.add(expr + sqlOp + "?");
            params.add(new Object[]{null, a});
        }

        private void filtroFecha(Campo c, String expr, String texto) {
            String[] p = texto.substring(2).split(OptimistWorkWith.SEP, -1);
            LocalDate d = p[0].isEmpty() ? null : LocalDate.parse(p[0]);
            LocalDate h = p.length < 2 || p[1].isEmpty() ? null : LocalDate.parse(p[1]);
            // hasta inclusive, aunque la columna tenga hora: < el día siguiente
            if (d != null) { condiciones.add(expr + ">=?"); params.add(new Object[]{null, java.sql.Date.valueOf(d)}); }
            if (h != null) { condiciones.add(expr + "<?"); params.add(new Object[]{null, java.sql.Date.valueOf(h.plusDays(1))}); }
        }
    }

    /** "t0.Columna" para un campo propio; "tN.ColumnaRemota" para uno inferido (agregando los JOIN necesarios). */
    private String expresion(Nivel cab, String local, Map<Referencia, String> alias, StringBuilder joins, int profundidad) {
        Campo c = cab.campo(local);
        String sx = OptimistTrnDefinicion.sqlLista(cab.tabla(), c.nombre());   // V82p: columna fórmula con su SQL
        if (sx != null) return "(" + sx + ")";
        if (c.persistido()) return "t0." + col(c.nombre());
        if (profundidad > 5) throw new IllegalStateException("Referencias circulares en " + local);
        Referencia ref = cab.referencias().stream().filter(r -> r.trae().containsKey(c.nombre())).findFirst()
                .orElseThrow(() -> new IllegalStateException(local + " es inferido pero ninguna referencia lo trae"));
        return aliasDe(cab, ref, alias, joins, profundidad) + "." + col(ref.trae().get(c.nombre()));
    }

    /** Alias del LEFT JOIN a la tabla de una referencia ("t3"); lo agrega si todavía no está. */
    private String aliasDe(Nivel cab, Referencia ref, Map<Referencia, String> alias, StringBuilder joins, int profundidad) {
        String a = alias.get(ref);
        if (a == null) {
            List<String> on = new ArrayList<>();
            for (Map.Entry<String, String> fk : ref.campos().entrySet())
                on.add("#." + col(fk.getValue()) + "=" + expresion(cab, fk.getKey(), alias, joins, profundidad + 1));
            a = "t" + (alias.size() + 1);
            alias.put(ref, a);
            String al = a;
            List<String> condiciones = new ArrayList<>(on.stream().map(x -> x.replace("#.", al + ".")).toList());
            condiciones.addAll(ref.condicionesFijas(al + "."));   // V26: donde
            joins.append(" LEFT JOIN ").append(ref.tabla()).append(' ').append(a).append(" ON ")
                 .append(String.join(" AND ", condiciones));
        }
        return a;
    }

    /** V47: la referencia con código cuya clave es este campo (ArticulosPK -> Articulos), o null. */
    public static Referencia referenciaConCodigo(Nivel nivel, String campo) {
        for (Referencia r : nivel.referencias())
            if (r.conCodigo() && r.campos().keySet().iterator().next().equalsIgnoreCase(campo)) return r;
        return null;
    }

    /** V47: dónde deja listar() el código de una columna de clave interna (en la misma fila del WorkWith). */
    public static String claveCodigo(String campo) { return "\u0001codigo:" + campo; }

    public Registro cargar(Trn trn, Map<String, Object> clave) throws SQLException {
        try { return cargar0(trn, clave); } catch (SQLException e) { throw diagnosticar(trn, e); }
    }

    private Registro cargar0(Trn trn, Map<String, Object> clave) throws SQLException {
        return cargar0(trn, clave, trn.detallesVisibles());
    }

    /** V48c: solo la cabecera (con sus inferidos), para controlar si alguien la cambió mientras estaba abierta. */
    public Registro cargarCabecera(Trn trn, Map<String, Object> clave) throws SQLException {
        try { return cargar0(trn, clave, List.of()); } catch (SQLException e) { throw diagnosticar(trn, e); }
    }

    /** V48: Business Component (&PRS.Load(id)): la cabecera y los niveles que se graban (los visibles). */
    public Registro cargarBc(Trn trn, Map<String, Object> clave) throws SQLException {
        return cargar0(trn, clave, trn.detallesVisibles());
    }

    private Registro cargar0(Trn trn, Map<String, Object> clave, List<Nivel> niveles) throws SQLException {
        Nivel cab = trn.cabecera();
        try (Connection c = abrir()) {
            Map<String, Object> cabecera;
            try (PreparedStatement p = c.prepareStatement("SELECT " + columnas(cab.persistidos()) + " FROM "
                    + cab.tabla() + " WHERE " + igualdades(cab.claves()))) {
                bindClave(p, 1, cab.claves(), clave);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new SQLException(capital(trn.singular()) + " no encontrad" + trn.genero() + ": " + textoClave(cab.claves(), clave));
                    cabecera = leerFila(r, cab.persistidos());
                }
            }
            Map<ClaveCache, Map<String, Object>> cache = new HashMap<>();
            resolverReferencias(c, cab, null, cabecera, Map.of(), cache);
            Map<String, List<Linea>> detalles = new LinkedHashMap<>();
            for (Nivel det : niveles) {
                List<Linea> lineas = new ArrayList<>();
                try (PreparedStatement p = c.prepareStatement("SELECT " + columnas(det.persistidos()) + " FROM "
                        + det.tabla() + " WHERE " + igualdades(cab.claves()) + " ORDER BY " + orden(det))) {
                    bindClave(p, 1, cab.claves(), clave);
                    try (ResultSet r = p.executeQuery()) {
                        while (r.next()) {
                            Map<String, Object> v = leerFila(r, det.persistidos());
                            lineas.add(new Linea(v, subMapa(v, det.claves())));
                        }
                    }
                }
                for (Linea l : lineas) resolverReferencias(c, det, cab, l.valores(), cabecera, cache);
                detalles.put(det.nombre(), lineas);
            }
            return new Registro(cabecera, detalles);
        }
    }

    // ------------------------------------------------------------------ referencias (atributos inferidos)

    private record ClaveCache(String tabla, List<String> columnas, List<String> valores) {}

    /** Completa en 'valores' los campos inferidos. Pantalla: se llama cuando cambia una clave foránea. */
    public void completarReferencias(Nivel nivel, Nivel cabecera, Map<String, Object> valores,
                                     Map<String, Object> valoresCabecera) throws SQLException {
        try (Connection c = abrir()) {
            resolverReferencias(c, nivel, cabecera, valores, valoresCabecera, new HashMap<>(), null);
        }
    }

    /**
     * V65: como completarReferencias, y devuelve las referencias cuyo valor cargado no existe en su tabla (ej. una forma
     * de pago escrita a mano que no está). No cuentan las vacías, las 'opcional' en 0, las que admiten nulo en 0, las que
     * se cargan por código (esas ya avisan solas) ni las que dependen de campos inferidos.
     */
    public List<Referencia> completarReferenciasYFaltantes(Nivel nivel, Nivel cabecera, Map<String, Object> valores,
                                                          Map<String, Object> valoresCabecera) throws SQLException {
        List<Referencia> faltan = new ArrayList<>();
        try (Connection c = abrir()) {
            resolverReferencias(c, nivel, cabecera, valores, valoresCabecera, new HashMap<>(), faltan);
        }
        return faltan;
    }

    /** V65i: alguna parte de la clave foránea (compuesta) admite nulo y está vacía: GeneXus no controla la referencia. */
    private static boolean algunaNula(Referencia ref, Nivel nivel, Map<String, Object> valores) {
        if (ref.campos().size() < 2) return false;
        return ref.campos().keySet().stream().anyMatch(l -> {
            Campo f = nivel.campo(l);
            return f != null && f.nulo() && OptimistProcedimiento.Funciones.vacioGx(valores.get(f.nombre()));
        });
    }

    /** V65: ¿esta referencia se controla al salir del campo? (las mismas que se validan al grabar) */
    private boolean seControla(Referencia ref, Nivel nivel, Nivel cabecera, Map<String, Object> valores, Map<String, Object> valoresCabecera) {
        if (ref.conCodigo()) return false;
        boolean todasPropias = ref.campos().keySet().stream().allMatch(l -> { Campo f = campoLocal(nivel, cabecera, l); return f != null && f.persistido(); });
        if (!todasPropias) return false;
        java.util.function.Function<String, Object> v = valorDe(nivel, cabecera, valores, valoresCabecera);
        boolean todasVacias = ref.campos().keySet().stream().allMatch(l -> OptimistProcedimiento.Funciones.vacioGx(v.apply(l)));
        if (todasVacias && (OptimistTrnDefinicion.referenciaOpcional(nivel.tabla(), ref)
                || ref.campos().keySet().stream().allMatch(l -> { Campo f = nivel.campo(l); return f != null && f.nulo(); }))) return false;
        if (algunaNula(ref, nivel, valores)) return false;   // V65i: como al grabar
        return true;
    }

    /** Varias pasadas: una referencia puede depender de un valor que trae otra (A -> B -> C). */
    private void resolverReferencias(Connection c, Nivel nivel, Nivel cabecera, Map<String, Object> valores,
                                     Map<String, Object> valoresCabecera, Map<ClaveCache, Map<String, Object>> cache) throws SQLException {
        resolverReferencias(c, nivel, cabecera, valores, valoresCabecera, cache, null);
    }

    private void resolverReferencias(Connection c, Nivel nivel, Nivel cabecera, Map<String, Object> valores,
                                     Map<String, Object> valoresCabecera, Map<ClaveCache, Map<String, Object>> cache,
                                     List<Referencia> faltan) throws SQLException {
        List<Referencia> pendientes = new ArrayList<>(nivel.referencias());
        for (int pasada = 0; pasada < 4 && !pendientes.isEmpty(); pasada++) {
            for (Iterator<Referencia> it = pendientes.iterator(); it.hasNext(); ) {
                Referencia ref = it.next();
                Map<String, Object> fk = valoresFk(ref, nivel, cabecera, valores, valoresCabecera);
                boolean depende = ref.campos().keySet().stream().anyMatch(l -> esInferidoPendiente(nivel, l, pendientes, ref));
                if (depende && pasada < 3) continue;
                it.remove();
                Map<String, Object> traido = fk == null ? null : buscarUno(c, ref, nivel, cabecera, fk, cache,
                        valorDe(nivel, cabecera, valores, valoresCabecera));
                if (faltan != null && fk != null && traido == null && seControla(ref, nivel, cabecera, valores, valoresCabecera)) faltan.add(ref);
                // V65: existe pero no cumple el 'donde' (un artículo que después se desactivó): la descripción se trae igual
                if (fk != null && traido == null && !ref.donde().isEmpty() && !ref.trae().isEmpty())
                    traido = buscarUno(c, new Referencia(ref.tabla(), ref.campos(), ref.trae(), ref.descripcion(), new LinkedHashMap<>(), ref.codigo()),
                            nivel, cabecera, fk, cache, valorDe(nivel, cabecera, valores, valoresCabecera));
                for (Map.Entry<String, String> t : ref.trae().entrySet()) {
                    Campo local = nivel.campo(t.getKey());
                    if (local != null && local.inferido()) valores.put(t.getKey(), traido == null ? null : traido.get(t.getKey()));
                }
            }
        }
    }

    private boolean esInferidoPendiente(Nivel nivel, String local, List<Referencia> pendientes, Referencia actual) {
        Campo c = nivel.campo(local);
        if (c == null || !c.inferido()) return false;
        return pendientes.stream().anyMatch(r -> r != actual && r.trae().containsKey(c.nombre()));
    }

    /** Valores de la FK (null si alguna parte está vacía: no se busca). */
    private Map<String, Object> valoresFk(Referencia ref, Nivel nivel, Nivel cabecera,
                                          Map<String, Object> valores, Map<String, Object> valoresCabecera) {
        Map<String, Object> fk = new LinkedHashMap<>();
        for (String local : ref.campos().keySet()) {
            Object v = nivel.campo(local) != null ? valores.get(local) : valoresCabecera.get(local);
            if (vacio(v)) return null;
            fk.put(local, v);
        }
        return fk;
    }

    private Campo campoLocal(Nivel nivel, Nivel cabecera, String local) {
        Campo c = nivel.campo(local);
        return c != null ? c : cabecera == null ? null : cabecera.campo(local);
    }

    /** V47: valor actual de un campo (del nivel o de la cabecera), para los 'donde' que dependen de un campo. */
    private java.util.function.Function<String, Object> valorDe(Nivel nivel, Nivel cabecera, Map<String, Object> valores,
                                                               Map<String, Object> valoresCabecera) {
        return n -> {
            Campo f = campoLocal(nivel, cabecera, n);
            if (f == null) return null;
            return nivel.campo(f.nombre()) != null ? valores.get(f.nombre()) : valoresCabecera == null ? null : valoresCabecera.get(f.nombre());
        };
    }

    private Map<String, Object> buscarUno(Connection c, Referencia ref, Nivel nivel, Nivel cabecera,
                                          Map<String, Object> fk, Map<ClaveCache, Map<String, Object>> cache,
                                          java.util.function.Function<String, Object> valorDe) throws SQLException {
        List<String> fijas = ref.condiciones("", valorDe);   // V26 donde; V47 también las que dependen de un campo
        List<Campo> fkCampos = ref.campos().keySet().stream().map(l -> campoLocal(nivel, cabecera, l)).toList();
        List<String> localesTrae = new ArrayList<>(ref.trae().keySet());
        List<String> remotas = new ArrayList<>(ref.campos().values());
        remotas.add("|");
        remotas.addAll(ref.trae().values());
        remotas.addAll(fijas);   // V26: la caché distingue referencias con distinto 'donde'
        // la caché guarda por COLUMNA REMOTA: dos referencias a la misma tabla con distintos nombres locales la comparten bien
        ClaveCache k = new ClaveCache(ref.tabla(), remotas,
                fkCampos.stream().map(fc -> String.valueOf(fk.get(fc.nombre()))).toList());
        Map<String, Object> remoto;
        if (cache.containsKey(k)) remoto = cache.get(k);
        else {
            remoto = null;
            String sql = "SELECT " + (ref.trae().isEmpty() ? "1" : ref.trae().values().stream().map(OptimistTrnDatos::col).collect(Collectors.joining(","))) + " FROM " + ref.tabla()
                    + " WHERE " + java.util.stream.Stream.concat(ref.campos().values().stream().map(x -> col(x) + "=?"),
                                                                  fijas.stream()).collect(Collectors.joining(" AND "));
            try (PreparedStatement p = c.prepareStatement(sql)) {
                int i = 1;
                for (Campo fc : fkCampos) escribir(p, i++, fc, fk.get(fc.nombre()));
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) {
                        remoto = new LinkedHashMap<>();
                        for (int j = 0; j < localesTrae.size(); j++) {
                            Campo lc = nivel.campo(localesTrae.get(j));
                            remoto.put(ref.trae().get(localesTrae.get(j)), lc == null ? rs.getObject(j + 1) : leer(rs, j + 1, lc.dominio()));
                        }
                    }
                }
            }
            cache.put(k, remoto);
        }
        if (remoto == null) return null;
        Map<String, Object> r = new LinkedHashMap<>();
        for (String l : localesTrae) r.put(l, remoto.get(ref.trae().get(l)));
        return r;
    }

    /**
     * Buscador: hasta 'limite' filas de la tabla referenciada, filtrando por clave o descripción.
     * V24: referencias de clave compuesta (Tipo + Subtipo, Marca + Modelo). 'local' es el campo que
     * se busca (el último de la clave); 'padres' trae los valores de los otros campos de la clave
     * (local -> valor), que filtran la búsqueda: solo los subtipos del tipo elegido.
     */
    /**
     * V68: la lupa por páginas (como el WorkWith): en los filtros, ORDEN = la columna por la que se ordena ("-Columna" =
     * descendente); con limite < 0 no trae filas sino una sola con TOTAL = cuántas cumplen los filtros.
     */
    public static final String ORDEN = "\u0003orden", TOTAL = "\u0003total";

    /** V68: el ORDER BY de la lupa: la columna pedida (clave de la lupa -> expresión SQL) o la de siempre. */
    static String ordenLupa(Map<String, String> filtros, Map<String, String> expresiones, String porDefecto) {
        String o = filtros == null ? null : filtros.get(ORDEN);
        if (o == null || o.isBlank()) return porDefecto;
        boolean desc = o.startsWith("-");
        String k = desc ? o.substring(1) : o;
        for (Map.Entry<String, String> e : expresiones.entrySet())
            if (e.getKey().equalsIgnoreCase(k)) return e.getValue() + (desc ? " DESC" : "");
        return porDefecto;
    }

    /** V68: cuántas filas cumplen (la lupa muestra "página 3 de 124"), como una fila con TOTAL. */
    List<Map<String, Object>> contarLupa(String desdeWhere, List<Object> parametros) throws SQLException {
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM " + desdeWhere)) {
            for (int i = 0; i < parametros.size(); i++) p.setObject(i + 1, parametros.get(i));
            try (ResultSet rs = p.executeQuery()) {
                Map<String, Object> f = new HashMap<>();
                f.put(TOTAL, rs.next() ? rs.getLong(1) : 0L);
                return new ArrayList<>(List.of(f));
            }
        }
    }

    public List<Map<String, Object>> buscar(Referencia ref, String local, Map<String, Object> padres,
                                            Nivel nivel, Nivel cabecera, String texto, int limite) throws SQLException {
        return buscar(ref, local, padres, nivel, cabecera, texto, limite, 0);
    }

    /** V48e: desde = cuántas filas saltear (la lupa va pidiendo de a páginas al bajar). */
    public List<Map<String, Object>> buscar(Referencia ref, String local, Map<String, Object> padres,
                                            Nivel nivel, Nivel cabecera, String texto, int limite, int desde) throws SQLException {
        return buscar(ref, local, padres, nivel, cabecera, texto, null, limite, desde);
    }

    /**
     * V80: en 'padres' de la lupa, LIBRE + campo = un campo de la clave de la referencia que está vacío y es de solo lectura
     * (ej. la Clase del comprobante en Modelos de Comprobantes, que en GeneXus completaba el Prompt): no filtra, se muestra
     * como columna y vuelve en la fila elegida con la clave LIBRE + campo, para completarlo.
     */
    public static final String LIBRE = "\u0001libre:";

    /** V50k: con un filtro por columna ("codigo" y "descripcion", como el WorkWith) además del texto que busca en las dos. */
    public List<Map<String, Object>> buscar(Referencia ref, String local, Map<String, Object> padres, Nivel nivel, Nivel cabecera,
                                            String texto, Map<String, String> filtros, int limite, int desde) throws SQLException {
        String remota = ref.campos().get(local);
        if (remota == null) throw new SQLException(local + " no es parte de la referencia a " + ref.tabla());
        Campo fc = campoLocal(nivel, cabecera, local);
        String desc = ref.descripcion() != null ? ref.descripcion()
                : ref.trae().values().stream().filter(v -> !v.equalsIgnoreCase(remota)).findFirst().orElse(remota);
        // V26: donde (ej. solo proveedores); V47: también los que dependen de un campo (el CPI dice si son clientes o proveedores)
        Map<String, Object> todos = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (padres != null) todos.putAll(padres);
        List<String> condiciones = new ArrayList<>(ref.condiciones("", todos::get));
        condiciones.addAll(OptimistTrnDefinicion.condicionesLupa(nivel.tabla(), ref, ""));   // V65: dondeLupa (solo en la lupa)
        String cod = ref.conCodigo() ? ref.codigo().columna() : null;   // V47: se busca y se muestra el código, no la clave interna
        List<Object> parametros = new ArrayList<>();
        List<String> libres = new ArrayList<>();   // V80
        for (Map.Entry<String, String> e : ref.campos().entrySet()) {
            if (e.getKey().equalsIgnoreCase(local)) continue;
            Object v = padres.get(e.getKey());
            if (vacio(v) && padres.containsKey(LIBRE + e.getKey())) { libres.add(e.getKey()); continue; }
            if (vacio(v)) throw new IllegalArgumentException(e.getKey());
            condiciones.add(e.getValue() + " = ?");
            parametros.add(v);
        }
        if (filtros != null)   // V80: el filtro de cada columna libre
            for (String k : libres) {
                Campo ck = campoLocal(nivel, cabecera, k);
                String cl = condicionFiltro(filtros.get(LIBRE + k), ref.campos().get(k), ck == null || ck.dominio().esCombo() ? null : ck.dominio(), parametros);
                if (cl != null) condiciones.add(cl);
            }
        if (texto != null && !texto.isBlank()) {
            condiciones.add("(CAST(" + (cod != null ? cod : remota) + " AS CHAR) LIKE ? OR CAST(" + desc + " AS CHAR) LIKE ?)");
            parametros.add("%" + texto.trim() + "%");
            parametros.add("%" + texto.trim() + "%");
        }
        if (filtros != null) {   // V50k
            String c1 = condicionFiltro(filtros.get("codigo"), cod != null ? cod : remota, cod != null ? null : fc.dominio(), parametros);
            if (c1 != null) condiciones.add(c1);
            String c2 = condicionFiltro(filtros.get("descripcion"), desc, null, parametros);
            if (c2 != null) condiciones.add(c2);
        }
        String desdeWhere = ref.tabla() + (condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones));
        if (limite < 0) return contarLupa(desdeWhere, parametros);   // V68
        Map<String, String> exprOrden = new LinkedHashMap<>(Map.of("codigo", cod != null ? cod : remota, "descripcion", desc));
        for (String k : libres) exprOrden.put(LIBRE + k, ref.campos().get(k));   // V80: también se ordena por la libre
        String orden = ordenLupa(filtros, exprOrden, cod != null ? cod : remota);
        StringBuilder masLibres = new StringBuilder();
        for (String k : libres) masLibres.append(',').append(ref.campos().get(k));
        if (!libres.isEmpty() && (filtros == null || filtros.get(ORDEN) == null || filtros.get(ORDEN).isBlank()))
            orden = String.join(",", libres.stream().map(k -> ref.campos().get(k)).toList()) + "," + orden;   // V80: agrupado por la libre
        String sql = "SELECT " + remota + "," + desc + (cod != null ? "," + cod : "") + masLibres + " FROM " + desdeWhere
                + " ORDER BY " + orden + " LIMIT " + Math.max(1, Math.min(limite, 1000)) + (desde > 0 ? " OFFSET " + desde : "");
        List<Map<String, Object>> r = new ArrayList<>();
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.size(); i++) p.setObject(i + 1, parametros.get(i));
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("clave", leer(rs, 1, fc.dominio()));
                    Object d = rs.getObject(2);
                    f.put("descripcion", d == null ? "" : String.valueOf(d).stripTrailing());
                    if (cod != null) f.put("codigo", texto(rs.getObject(3)));
                    int base = cod != null ? 4 : 3;
                    for (int i = 0; i < libres.size(); i++) {   // V80
                        Campo ck = campoLocal(nivel, cabecera, libres.get(i));
                        f.put(LIBRE + libres.get(i), ck == null ? rs.getObject(base + i) : leer(rs, base + i, ck.dominio()));
                    }
                    r.add(f);
                }
            }
        }
        return r;
    }

    /**
     * V48: la lupa con las columnas de 'workwith.prompt' de la TRN destino (ej. Articulos: código, descripción, tipo, marca,
     * modelo), cada una con su filtro (contiene). Devuelve filas con "clave" (el valor para el campo local) y las columnas.
     */
    public List<Map<String, Object>> buscarPrompt(Trn destino, Referencia ref, String local, Map<String, Object> padres,
                                                  Nivel nivel, Nivel cabecera, Map<String, String> filtros, int limite) throws SQLException {
        return buscarPrompt(destino, ref, local, padres, nivel, cabecera, filtros, limite, 0);
    }

    /** V48e: desde = cuántas filas saltear (la lupa va pidiendo de a páginas al bajar). */
    public List<Map<String, Object>> buscarPrompt(Trn destino, Referencia ref, String local, Map<String, Object> padres,
                                                  Nivel nivel, Nivel cabecera, Map<String, String> filtros, int limite, int desde) throws SQLException {
        String remota = ref.campos().get(local);
        if (remota == null) throw new SQLException(local + " no es parte de la referencia a " + ref.tabla());
        Campo fc = campoLocal(nivel, cabecera, local);
        Nivel cab = destino.cabecera();
        Map<Referencia, String> alias = new LinkedHashMap<>();
        StringBuilder joins = new StringBuilder();
        List<String> cols = destino.workwith().prompt();
        List<String> select = new ArrayList<>(List.of("t0." + remota));
        for (String n : cols) select.add(expresion(cab, n, alias, joins, 0));
        Map<String, Object> todos = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (padres != null) todos.putAll(padres);
        List<String> condiciones = new ArrayList<>(ref.condiciones("t0.", todos::get));
        condiciones.addAll(OptimistTrnDefinicion.condicionesLupa(nivel.tabla(), ref, "t0."));   // V65: dondeLupa (solo en la lupa)
        List<Object> parametros = new ArrayList<>();
        List<String> libres = new ArrayList<>();   // V80
        for (Map.Entry<String, String> e : ref.campos().entrySet()) {
            if (e.getKey().equalsIgnoreCase(local)) continue;
            Object v = todos.get(e.getKey());
            if (vacio(v) && todos.containsKey(LIBRE + e.getKey())) { libres.add(e.getKey()); select.add("t0." + e.getValue()); continue; }
            if (vacio(v)) throw new IllegalArgumentException(e.getKey());
            condiciones.add("t0." + e.getValue() + " = ?");
            parametros.add(v);
        }
        for (int i = 0; i < cols.size(); i++) {   // V50k: cada filtro según su tipo, como en el WorkWith
            String cond = condicionFiltro(filtros == null ? null : filtros.get(cols.get(i)), select.get(i + 1),
                    cab.campo(cols.get(i)).dominio(), parametros);
            if (cond != null) condiciones.add(cond);
        }
        String desdeWhere = cab.tabla() + " t0" + joins + (condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones));
        if (limite < 0) return contarLupa(desdeWhere, parametros);   // V68
        Map<String, String> exprs = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) exprs.put(cols.get(i), select.get(i + 1));
        String sql = "SELECT " + String.join(",", select) + " FROM " + desdeWhere
                + " ORDER BY " + ordenLupa(filtros, exprs, select.get(1)) + " LIMIT " + Math.max(1, Math.min(limite, 1000)) + (desde > 0 ? " OFFSET " + desde : "");
        List<Map<String, Object>> r = new ArrayList<>();
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.size(); i++) p.setObject(i + 1, parametros.get(i));
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("clave", leer(rs, 1, fc.dominio()));
                    for (int i = 0; i < cols.size(); i++) f.put(cols.get(i), leer(rs, i + 2, cab.campo(cols.get(i)).dominio()));
                    for (int i = 0; i < libres.size(); i++) {   // V80: van al final del SELECT
                        Campo ck = campoLocal(nivel, cabecera, libres.get(i));
                        int col = cols.size() + 2 + i;
                        f.put(LIBRE + libres.get(i), ck == null ? rs.getObject(col) : leer(rs, col, ck.dominio()));
                    }
                    r.add(f);
                }
            }
        }
        return r;
    }

    /**
     * V50k: la lupa de un web panel sobre la tabla de una TRN que tiene 'workwith.prompt' (ej. Entidades): las columnas del
     * prompt (con sus filtros por tipo) más las que el panel necesita que le devuelva, filtrando por los 'donde' del panel.
     */
    public List<Map<String, Object>> buscarTabla(Trn destino, Collection<String> extra, Map<String, Object> iguales,
                                                 Map<String, String> filtros, int limite, int desde) throws SQLException {
        Nivel cab = destino.cabecera();
        Map<Referencia, String> alias = new LinkedHashMap<>();
        StringBuilder joins = new StringBuilder();
        LinkedHashSet<String> cols = new LinkedHashSet<>(destino.workwith().prompt());
        for (String x : extra) { Campo c = cab.campo(x); if (c != null) cols.add(c.nombre()); }
        List<String> nombres = new ArrayList<>(cols);
        List<String> select = new ArrayList<>();
        for (String n : nombres) select.add(expresion(cab, n, alias, joins, 0));
        List<String> condiciones = new ArrayList<>();
        List<Object> parametros = new ArrayList<>();
        if (iguales != null) for (Map.Entry<String, Object> e : iguales.entrySet()) {
            Object v = e.getValue();
            if (vacio(v)) continue;   // la variable vacía no filtra (como antes)
            Campo c = cab.campo(e.getKey());
            String ex = c != null ? expresion(cab, c.nombre(), alias, joins, 0) : "t0." + col(e.getKey());
            if (v instanceof Collection<?> l && !l.isEmpty()) {   // V66y: {Status: [P, V]} = cualquiera de esos valores
                condiciones.add(ex + " IN (" + String.join(",", Collections.nCopies(l.size(), "?")) + ")");
                parametros.addAll(l);
            } else {
                condiciones.add(ex + " = ?");
                parametros.add(v);
            }
        }
        for (int i = 0; i < nombres.size(); i++) {
            String cond = condicionFiltro(filtros == null ? null : filtros.get(nombres.get(i)), select.get(i),
                    cab.campo(nombres.get(i)).dominio(), parametros);
            if (cond != null) condiciones.add(cond);
        }
        String desdeWhere = cab.tabla() + " t0" + joins + (condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones));
        if (limite < 0) return contarLupa(desdeWhere, parametros);   // V68
        Map<String, String> exprs = new LinkedHashMap<>();
        for (int i = 0; i < nombres.size(); i++) exprs.put(nombres.get(i), select.get(i));
        String sql = "SELECT " + String.join(",", select) + " FROM " + desdeWhere
                + " ORDER BY " + ordenLupa(filtros, exprs, select.get(0)) + " LIMIT " + Math.max(1, Math.min(limite, 1000)) + (desde > 0 ? " OFFSET " + desde : "");
        List<Map<String, Object>> r = new ArrayList<>();
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.size(); i++) p.setObject(i + 1, parametros.get(i));
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> f = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                    for (int i = 0; i < nombres.size(); i++) f.put(nombres.get(i), leer(rs, i + 1, cab.campo(nombres.get(i)).dominio()));
                    r.add(f);
                }
            }
        }
        return r;
    }

    /**
     * V50k: la condición SQL de un filtro de la lupa, con el mismo texto que arma el filtro del WorkWith:
     * número ("\u0002N" op, valor, hasta: =, !=, <, <=, >, >=, entre), fecha ("\u0002F" desde, hasta), combo (por su texto)
     * o texto (contiene). null = no filtra. Agrega los valores a 'params'.
     */
    public static String condicionFiltro(String filtro, String expr, Dominio d, List<Object> params) {
        if (filtro == null || filtro.isBlank()) return null;
        if (filtro.startsWith("\u0002N")) {
            String[] p = filtro.substring(2).split("\u0001", -1);
            String op = p[0];
            java.math.BigDecimal a = numeroFiltro(p.length > 1 ? p[1] : null), b = numeroFiltro(p.length > 2 ? p[2] : null);
            if (op.equals("entre")) {
                List<String> c = new ArrayList<>();
                if (a != null) { c.add(expr + " >= ?"); params.add(a); }
                if (b != null) { c.add(expr + " <= ?"); params.add(b); }
                return c.isEmpty() ? null : String.join(" AND ", c);
            }
            if (a == null) return null;
            String sqlOp = switch (op) { case "!=" -> "<>"; case "<", "<=", ">", ">=" -> op; default -> "="; };
            params.add(a);
            return expr + " " + sqlOp + " ?";
        }
        if (filtro.startsWith("\u0002F")) {
            String[] p = filtro.substring(2).split("\u0001", -1);
            List<String> c = new ArrayList<>();
            if (!p[0].isEmpty()) { c.add(expr + " >= ?"); params.add(java.sql.Date.valueOf(p[0])); }
            if (p.length > 1 && !p[1].isEmpty()) { c.add(expr + " < ?"); params.add(java.sql.Date.valueOf(java.time.LocalDate.parse(p[1]).plusDays(1))); }
            return c.isEmpty() ? null : String.join(" AND ", c);
        }
        if (filtro.startsWith(OptimistWorkWith.MUL)) {   // V60: combo de selección múltiple (los valores elegidos)
            List<String> elegidos = OptimistWorkWith.valoresMultiple(filtro);
            if (elegidos.isEmpty()) return null;
            List<Object> vals = new ArrayList<>();
            if (d != null && d.esCombo()) {
                for (var v : d.valores()) if (elegidos.contains(String.valueOf(v.valor()).trim())) vals.add(v.valor());
            } else vals.addAll(elegidos);
            if (vals.isEmpty()) return "1=0";
            params.addAll(vals);
            return expr + " IN (" + String.join(",", Collections.nCopies(vals.size(), "?")) + ")";
        }
        String t = filtro.trim();
        if (d != null && d.esCombo()) {   // por el texto que ve el usuario ("CUIT", "Cliente"), o por el valor
            String tl = t.toLowerCase(Locale.ROOT);
            List<Object> vals = new ArrayList<>();
            for (var v : d.valores())
                if (String.valueOf(v.texto()).toLowerCase(Locale.ROOT).contains(tl) || String.valueOf(v.valor()).trim().equalsIgnoreCase(t)) vals.add(v.valor());
            if (vals.isEmpty()) return "1=0";
            params.addAll(vals);
            return expr + " IN (" + String.join(",", Collections.nCopies(vals.size(), "?")) + ")";
        }
        params.add("%" + t + "%");
        return "CAST(" + expr + " AS CHAR) LIKE ?";
    }

    /** V50k: número escrito en un filtro ("1.234,56", "1234.5", "-3") o null (igual que el del WorkWith). */
    static java.math.BigDecimal numeroFiltro(String t) {
        if (t == null || t.isBlank()) return null;
        String x = t.trim();
        if (x.contains(",")) x = x.replace(".", "").replace(",", ".");
        else if (x.matches("-?\\d{1,3}(\\.\\d{3})+")) x = x.replace(".", "");
        try { return new java.math.BigDecimal(x); } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------------ V47: código en lugar de la clave interna

    private static String texto(Object o) { return o == null ? "" : String.valueOf(o).stripTrailing(); }

    /** El código de una clave interna (ArticulosPK 75 -> "TTESP0500002"). null si no existe. */
    public String codigoDe(Referencia ref, Object clave) throws SQLException {
        if (!ref.conCodigo() || vacio(clave)) return null;
        String sql = "SELECT " + ref.codigo().columna() + " FROM " + ref.tabla() + " WHERE " + ref.campos().values().iterator().next() + " = ?";
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, clave);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? texto(rs.getObject(1)) : null; }
        }
    }

    /**
     * Registros con ese código (sin distinguir mayúsculas), respetando el 'donde' de la referencia (ej. solo clientes).
     * Cada uno: clave (la interna), codigo, descripcion y segun (ej. el tipo de entidad). Normalmente uno; puede haber
     * dos si el código se repite entre tipos (un cliente y un proveedor) y la TRN no dice cuál.
     */
    public List<Map<String, Object>> porCodigo(Referencia ref, String codigo, java.util.function.Function<String, Object> valorDe,
                                              Campo campoClave) throws SQLException {
        if (!ref.conCodigo() || codigo == null || codigo.isBlank()) return List.of();
        String remota = ref.campos().values().iterator().next();
        String desc = ref.descripcion() != null ? ref.descripcion()
                : ref.trae().values().stream().filter(v -> !v.equalsIgnoreCase(remota) && !v.equalsIgnoreCase(ref.codigo().columna()))
                        .findFirst().orElse(ref.codigo().columna());
        String seg = ref.codigo().segun();
        List<String> condiciones = new ArrayList<>(ref.condiciones("", valorDe));
        condiciones.add(0, ref.codigo().columna() + " = ?");
        String sql = "SELECT " + remota + "," + ref.codigo().columna() + "," + desc + (seg != null ? "," + seg : "")
                + " FROM " + ref.tabla() + " WHERE " + String.join(" AND ", condiciones) + " LIMIT 20";
        List<Map<String, Object>> r = new ArrayList<>();
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement(sql)) {
            // siempre como texto: contra una columna numérica (EntidadesId) MySQL lo convierte; contra una de texto
            // (ArticulosId) compara sin distinguir mayúsculas. Como número, "75" encontraría también "75ABC".
            p.setString(1, codigo.trim());
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("clave", leer(rs, 1, campoClave.dominio()));
                    f.put("codigo", texto(rs.getObject(2)));
                    f.put("descripcion", texto(rs.getObject(3)));
                    if (seg != null) f.put("segun", rs.getObject(4));
                    r.add(f);
                }
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ grabación

    /**
     * nuevo=true: INSERT de cabecera (con clave autonumerada si corresponde).
     * Detalles: borra quitadas, actualiza existentes, inserta nuevas. usuario: para la auditoría.
     * Devuelve la clave de la cabecera (útil cuando es autonumerada).
     */
    public Map<String, Object> guardar(Trn trn, Registro reg, boolean nuevo, String usuario) throws SQLException {
        return guardar(trn, reg, nuevo, usuario, null);
    }

    /**
     * V35: lo que se ejecuta después de grabar cabecera y detalles y ANTES de confirmar la transacción, con la misma
     * conexión (las reglas on AfterInsert/AfterUpdate/BeforeComplete). Si lanza una excepción, no se graba nada.
     */
    @FunctionalInterface
    public interface Enganche { void antesDeConfirmar(Connection c, Map<String, Object> clave, Map<String, Object> cabecera) throws SQLException; }

    public Map<String, Object> guardar(Trn trn, Registro reg, boolean nuevo, String usuario, Enganche enganche) throws SQLException {
        // V49y: si se graba una tabla de configuración, lo recordado de los procedimientos de configuración se olvida
        String tb = trn.cabecera().tabla().toLowerCase(Locale.ROOT);
        if (tb.startsWith("paramglb") || tb.startsWith("procesos") || tb.startsWith("usrproc") || tb.startsWith("vercampos")
                || tb.startsWith("secuser") || tb.startsWith("empresa") || tb.startsWith("tcbte") || tb.startsWith("tasasiva"))
            OptimistProcedimiento.olvidarMemoria();
        try { return guardar0(trn, reg, nuevo, usuario, enganche); } catch (SQLException e) { throw diagnosticar(trn, e); }
    }

    /** V35: para las reglas y el inicio (abren una conexión cuando la necesitan). */
    /** V36: mientras se edita un formulario, reglas y fórmulas comparten una conexión y lo ya leído (OptimistLecturas). */
    public OptimistCalculo.Abridor abridor() { return OptimistLecturas.compartido(this::abrir); }

    /** V83: contenido de un archivo (Blob) del registro; null si está vacío. */
    public byte[] leerArchivo(Trn trn, Map<String, Object> clave, String columna) throws SQLException {
        Nivel cab = trn.cabecera();
        try (Connection c = abrir();
             PreparedStatement p = c.prepareStatement("SELECT " + col(columna) + " FROM " + cab.tabla() + " WHERE " + igualdades(cab.claves()))) {
            bindClave(p, 1, cab.claves(), clave);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getBytes(1) : null; }
        }
    }

    /**
     * V83: graba un archivo (Blob) con su nombre y extensión, en la conexión de la transacción del Confirmar.
     * contenido null = se quita el archivo (nombre y extensión quedan vacíos).
     */
    public static void grabarArchivo(Connection c, Trn trn, Map<String, Object> clave, Archivo a, byte[] contenido,
                                     String nombre, String tipo) throws SQLException {
        Nivel cab = trn.cabecera();
        StringBuilder set = new StringBuilder(col(a.nombre()) + "=?");
        if (a.nombreArchivo() != null) set.append(',').append(col(a.nombreArchivo())).append("=?");
        if (a.tipoArchivo() != null) set.append(',').append(col(a.tipoArchivo())).append("=?");
        try (PreparedStatement p = c.prepareStatement("UPDATE " + cab.tabla() + " SET " + set + " WHERE " + igualdades(cab.claves()))) {
            int i = 1;
            if (contenido == null) p.setNull(i++, Types.LONGVARBINARY); else p.setBytes(i++, contenido);
            if (a.nombreArchivo() != null) p.setString(i++, contenido == null || nombre == null ? "" : nombre);
            if (a.tipoArchivo() != null) p.setString(i++, contenido == null || tipo == null ? "" : tipo);
            bindClave(p, i, cab.claves(), clave);
            p.executeUpdate();
        }
    }

    private Map<String, Object> guardar0(Trn trn, Registro reg, boolean nuevo, String usuario, Enganche enganche) throws SQLException {
        Nivel cab = trn.cabecera();
        Map<String, Object> valores = new LinkedHashMap<>(reg.cabecera());
        Protegidos prot = PROTEGIDOS.get();   // V60b: duplicación (Clone)
        if (nuevo) aplicarDefectos(cab, valores, prot == null ? null : prot.cab());
        Map<String, List<Map<String, Object>>> valoresLineas = new LinkedHashMap<>();
        for (Nivel det : trn.detallesVisibles()) {   // un detalle oculto no se toca
            List<Map<String, Object>> vs = new ArrayList<>();
            List<java.util.Set<String>> protDet = prot == null ? null : prot.lineas().get(det.nombre());
            int i = 0;
            for (Linea l : reg.lineas(det.nombre())) {
                Map<String, Object> v = new LinkedHashMap<>(l.valores());
                if (l.nueva()) aplicarDefectos(det, v, protDet != null && i < protDet.size() ? protDet.get(i) : null);
                vs.add(v);
                i++;
            }
            valoresLineas.put(det.nombre(), vs);
        }
        // V34: fórmulas que se graban (guardar: true), con todo el contexto: líneas, otras fórmulas, procedimientos, find
        if (OptimistCalculo.tieneFormulas(trn)) {
            OptimistCalculo.Resultado r = OptimistCalculo.calcular(trn, valores, valoresLineas, this::abrir);
            r.aplicarGuardadas(trn, valores, valoresLineas);
        }
        auditar(trn, valores, nuevo, usuario);
        validar(cab, valores, trn.titulo(), nuevo);
        Map<String, List<Linea>> detalles = new LinkedHashMap<>();
        for (Nivel det : trn.detallesVisibles()) {
            List<Linea> ls = new ArrayList<>();
            List<Linea> originales = reg.lineas(det.nombre());
            List<Map<String, Object>> vs = valoresLineas.get(det.nombre());
            for (int i = 0; i < originales.size(); i++) ls.add(new Linea(vs.get(i), originales.get(i).claveOriginal()));
            validarDetalle(det, ls);
            detalles.put(det.nombre(), ls);
        }

        // V65k: dentro del Confirmar de la pantalla, la conexión de su transacción (la de las reglas): commit lo hace ella al final
        try (Connection c = fija == null && OptimistLecturas.enTransaccion() ? abridor().abrir() : abrir()) {
            c.setAutoCommit(false);
            // V65m: en una conexión compartida (Business Component de un procedimiento, o el Confirmar de la pantalla) no se
            // puede deshacer todo: si este grabado falla, se vuelve al punto de antes (no queda la cabecera sin sus renglones)
            boolean compartida = fija != null || OptimistLecturas.enTransaccion();
            java.sql.Savepoint punto = compartida && !c.getAutoCommit() ? c.setSavepoint() : null;
            try {
                validarReferencias(c, cab, null, valores, Map.of(), trn.titulo(), nuevo ? null : Map.of(), nuevo ? null : subMapa(valores, cab.claves()));
                for (Nivel det : trn.detallesVisibles())
                    for (Linea l : detalles.get(det.nombre()))
                        validarReferencias(c, det, cab, l.valores(), valores, det.titulo(), l.claveOriginal(), nuevo ? null : subMapa(valores, cab.claves()));

                // V30: dos detalles pueden compartir numerador (MovStock: Partidas y PartidasAlta usan MovStockLastPartida).
                // 'ultimos' va por columna de numerador, no por detalle.
                List<String> conNumerador = trn.detallesVisibles().stream().map(Nivel::numerador)
                        .filter(Objects::nonNull).distinct().toList();
                Map<String, Long> ultimos = new HashMap<>();
                Map<String, Object> clave;
                if (nuevo) {
                    Campo auto = cab.autonumerico();
                    List<Campo> cols = cab.persistidos().stream().filter(x -> !x.autonumerico()).toList();
                    // en el alta todos los numeradores arrancan en 0, también el de un detalle oculto
                    List<String> todosNumeradores = trn.detalles().stream().map(Nivel::numerador)
                            .filter(Objects::nonNull).distinct().toList();
                    String extra = todosNumeradores.stream().map(n -> "," + n).collect(Collectors.joining());
                    Map<String, Object> omit = omitidas(c, cab.tabla(), cols, todosNumeradores);
                    String sql = "INSERT INTO " + cab.tabla() + " (" + columnas(cols) + extra + columnasOmitidas(omit) + ") VALUES ("
                            + marcas(cols.size()) + ",0".repeat(todosNumeradores.size()) + ",?".repeat(omit.size()) + ")";
                    try (PreparedStatement p = auto == null ? c.prepareStatement(sql) : c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                        int io = bindValores(p, 1, cols, valores);
                        for (Object vo : omit.values()) p.setObject(io++, vo);
                        p.executeUpdate();
                        if (auto != null) {
                            try (ResultSet k = p.getGeneratedKeys()) {
                                if (!k.next()) throw new SQLException("MySQL no devolvió el número de " + auto.etiqueta());
                                valores.put(auto.nombre(), k.getLong(1));
                            }
                        }
                    } catch (SQLIntegrityConstraintViolationException dup) {
                        if (!esDuplicado(dup)) throw errorIntegridad(dup, cab.tabla(), valores);   // V65m
                        throw new SQLException("Ya existe " + trn.singular() + " " + textoClave(cab.claves(), valores), dup);
                    }
                    clave = subMapa(valores, cab.claves());
                    conNumerador.forEach(n -> ultimos.put(n, 0L));
                } else {
                    clave = subMapa(valores, cab.claves());
                    if (!conNumerador.isEmpty()) {
                        String cols = String.join(",", conNumerador);
                        try (PreparedStatement p = c.prepareStatement("SELECT " + cols + " FROM " + cab.tabla()
                                + " WHERE " + igualdades(cab.claves()) + " FOR UPDATE")) {
                            bindClave(p, 1, cab.claves(), clave);
                            try (ResultSet r = p.executeQuery()) {
                                if (!r.next()) throw new SQLException(capital(trn.singular()) + " no encontrad" + trn.genero());
                                for (int i = 0; i < conNumerador.size(); i++) ultimos.put(conNumerador.get(i), r.getLong(i + 1));
                            }
                        }
                    }
                    List<Campo> set = cab.noClaves();
                    if (!set.isEmpty()) {
                        try (PreparedStatement p = c.prepareStatement("UPDATE " + cab.tabla() + " SET " + asignaciones(set)
                                + " WHERE " + igualdades(cab.claves()))) {
                            int i = bindValores(p, 1, set, valores);
                            bindClave(p, i, cab.claves(), clave);
                            if (p.executeUpdate() != 1) throw new SQLException(capital(trn.singular()) + " no encontrad" + trn.genero());
                        }
                    }
                }
                for (Nivel det : trn.detallesVisibles()) {
                    String num = det.numerador() == null ? "#" + det.nombre() : det.numerador();
                    long ult = guardarDetalle(c, cab, det, clave, detalles.get(det.nombre()), nuevo, ultimos.getOrDefault(num, 0L));
                    ultimos.put(num, ult);
                }
                if (!conNumerador.isEmpty()) {
                    try (PreparedStatement p = c.prepareStatement("UPDATE " + cab.tabla() + " SET "
                            + conNumerador.stream().map(n -> col(n) + "=?").collect(Collectors.joining(","))
                            + " WHERE " + igualdades(cab.claves()))) {
                        int i = 1;
                        for (String n : conNumerador) p.setLong(i++, ultimos.get(n));
                        bindClave(p, i, cab.claves(), clave);
                        p.executeUpdate();
                    }
                }
                OptimistLecturas.escribio();   // V65k: lo leído antes de grabar ya no vale (las reglas de después leen lo grabado)
                if (enganche != null) {
                    Map<String, Object> todo = new LinkedHashMap<>(valores);
                    if (clave != null) todo.putAll(clave);
                    enganche.antesDeConfirmar(c, clave, todo);
                }
                c.commit();
                return clave;
            } catch (SQLException | RuntimeException e) {
                if (punto != null) { try { c.rollback(punto); } catch (SQLException ignorado) { } }
                else c.rollback();
                throw e;
            }
        }
    }

    /** Compatibilidad V21 (un solo detalle, sin usuario). */
    public void guardar(Trn trn, Map<String, Object> cabecera, List<Linea> lineas, boolean nuevo) throws SQLException {
        Map<String, List<Linea>> d = new LinkedHashMap<>();
        if (!trn.detallesVisibles().isEmpty()) d.put(trn.detallesVisibles().get(0).nombre(), lineas);
        guardar(trn, new Registro(cabecera, d), nuevo, null);
    }

    private long guardarDetalle(Connection c, Nivel cab, Nivel det, Map<String, Object> claveCab, List<Linea> lineas,
                                boolean nuevo, long ultimo) throws SQLException {
        List<Campo> clavesDet = det.claves();

        // 1) Borrar las líneas que estaban en la base y el usuario quitó (primero, por si reingresó la misma clave).
        if (!nuevo) {
            Set<String> conservar = lineas.stream().filter(l -> !l.nueva())
                    .map(l -> textoClave(clavesDet, l.claveOriginal())).collect(Collectors.toSet());
            List<Map<String, Object>> borrar = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT " + columnas(clavesDet) + " FROM " + det.tabla()
                    + " WHERE " + igualdades(cab.claves()))) {
                bindClave(p, 1, cab.claves(), claveCab);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        Map<String, Object> k = leerFila(r, clavesDet);
                        if (!conservar.contains(textoClave(clavesDet, k))) borrar.add(k);
                    }
                }
            }
            if (!borrar.isEmpty()) {
                try (PreparedStatement p = c.prepareStatement("DELETE FROM " + det.tabla() + " WHERE "
                        + igualdades(cab.claves()) + " AND " + igualdades(clavesDet))) {
                    for (Map<String, Object> k : borrar) {
                        int i = bindClave(p, 1, cab.claves(), claveCab);
                        bindClave(p, i, clavesDet, k);
                        p.executeUpdate();
                    }
                }
            }
        }
        // 2) Actualizar existentes (la clave de una línea existente no se modifica, igual que en GeneXus).
        List<Campo> set = det.noClaves();
        if (!set.isEmpty()) {
            try (PreparedStatement p = c.prepareStatement("UPDATE " + det.tabla() + " SET " + asignaciones(set)
                    + " WHERE " + igualdades(cab.claves()) + " AND " + igualdades(clavesDet))) {
                for (Linea l : lineas) {
                    if (l.nueva()) continue;
                    int i = bindValores(p, 1, set, l.valores());
                    i = bindClave(p, i, cab.claves(), claveCab);
                    bindClave(p, i, clavesDet, l.claveOriginal());
                    if (p.executeUpdate() != 1)
                        throw new SQLException(det.titulo() + ": línea no encontrada " + textoClave(clavesDet, l.claveOriginal()));
                }
            }
        }
        // 3) Insertar nuevas (con numerador si el detalle lo usa).
        List<Campo> cols = new ArrayList<>(cab.claves());
        cols.addAll(det.persistidos());
        Map<String, Object> omit = omitidas(c, det.tabla(), cols, List.of());
        try (PreparedStatement p = c.prepareStatement("INSERT INTO " + det.tabla() + " (" + columnas(cols) + columnasOmitidas(omit)
                + ") VALUES (" + marcas(cols.size()) + ",?".repeat(omit.size()) + ")")) {
            for (Linea l : lineas) {
                if (!l.nueva()) continue;
                Map<String, Object> v = new LinkedHashMap<>(l.valores());
                if (det.numerador() != null) v.put(clavesDet.get(0).nombre(), ++ultimo);
                v.putAll(claveCab);
                int io = bindValores(p, 1, cols, v);
                for (Object vo : omit.values()) p.setObject(io++, vo);
                try { p.executeUpdate(); }
                catch (SQLIntegrityConstraintViolationException dup) {
                    if (!esDuplicado(dup)) throw errorIntegridad(dup, det.tabla(), v);   // V65m
                    throw new SQLException("Ya existe la línea " + textoClave(clavesDet, v) + " en " + det.titulo(), dup);
                }
            }
        }
        return ultimo;
    }

    /** Borra detalles y cabecera en una transacción. Las FK de otras tablas pueden impedirlo. */
    public void borrar(Trn trn, Map<String, Object> clave) throws SQLException {
        try (Connection c = abrir()) {
            c.setAutoCommit(false);
            try {
                borrar(c, trn, clave);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * V50m: borra detalles y cabecera en la conexión dada, sin Commit (lo usa también el Delete() de un Business Component,
     * dentro de la transacción del procedimiento). Los niveles van del último al primero: los de abajo antes que su padre.
     */
    void borrar(Connection c, Trn trn, Map<String, Object> clave) throws SQLException {
        Nivel cab = trn.cabecera();
        List<Nivel> dets = new ArrayList<>(trn.detalles());
        java.util.Collections.reverse(dets);
        for (Nivel det : dets) {
            try (PreparedStatement p = c.prepareStatement("DELETE FROM " + det.tabla() + " WHERE " + igualdades(cab.claves()))) {
                bindClave(p, 1, cab.claves(), clave);
                p.executeUpdate();
            }
        }
        try (PreparedStatement p = c.prepareStatement("DELETE FROM " + cab.tabla() + " WHERE " + igualdades(cab.claves()))) {
            bindClave(p, 1, cab.claves(), clave);
            if (p.executeUpdate() != 1) throw new SQLException(capital(trn.singular()) + " no encontrad" + trn.genero());
        }
    }

    // ------------------------------------------------------------------ diagnóstico de columnas

    /**
     * Si MySQL dice "Unknown column", compara TODO el YAML contra information_schema y
     * devuelve un error con la lista completa de columnas que no existen (tabla.columna).
     */
    SQLException diagnosticar(Trn trn, SQLException e) {
        if (e.getMessage() == null || !e.getMessage().contains("Unknown column")) return e;
        try {
            List<String> faltan = columnasInexistentes(trn);
            if (faltan.isEmpty()) return e;
            return new SQLException(e.getMessage() + ". Columnas del YAML que no existen en la base: " + String.join(", ", faltan), e);
        } catch (SQLException otro) {
            return e;
        }
    }

    /** tabla.columna del YAML (cabecera, detalles, referencias y numeradores) que no están en la base. */
    public List<String> columnasInexistentes(Trn trn) throws SQLException {
        Map<String, Set<String>> esperadas = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> esp = (t, c) -> esperadas.computeIfAbsent(t, x -> new LinkedHashSet<>()).add(c);
        Nivel cab = trn.cabecera();
        cab.persistidos().forEach(c -> esp.accept(cab.tabla(), c.nombre()));
        List<Nivel> niveles = new ArrayList<>();
        niveles.add(cab);
        for (Nivel d : trn.detalles()) {
            niveles.add(d);
            cab.claves().forEach(c -> esp.accept(d.tabla(), c.nombre()));
            d.persistidos().forEach(c -> esp.accept(d.tabla(), c.nombre()));
            if (d.numerador() != null) esp.accept(cab.tabla(), d.numerador());
        }
        for (Nivel n : niveles)
            for (Referencia r : n.referencias()) {
                r.campos().values().forEach(c -> esp.accept(r.tabla(), c));
                r.trae().values().forEach(c -> esp.accept(r.tabla(), c));
                if (r.descripcion() != null) esp.accept(r.tabla(), r.descripcion());
                r.donde().keySet().forEach(c -> esp.accept(r.tabla(), c));
            }
        Map<String, Set<String>> reales = new HashMap<>();
        String sql = "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ("
                + marcas(esperadas.size()) + ")";
        try (Connection c = abrir(); PreparedStatement p = c.prepareStatement(sql)) {
            int i = 1;
            for (String t : esperadas.keySet()) p.setString(i++, t);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) reales.computeIfAbsent(r.getString(1).toLowerCase(Locale.ROOT), x -> new HashSet<>())
                        .add(r.getString(2).toLowerCase(Locale.ROOT));
            }
        }
        List<String> faltan = new ArrayList<>();
        esperadas.forEach((t, cols) -> {
            Set<String> hay = reales.get(t.toLowerCase(Locale.ROOT));
            if (hay == null) { faltan.add(t + " (la tabla no existe)"); return; }
            for (String col : cols) if (!hay.contains(col.toLowerCase(Locale.ROOT))) faltan.add(t + "." + col);
        });
        return faltan;
    }

    // ------------------------------------------------------------------ validación, defectos y auditoría

    /** V34: calcula las fórmulas del formulario abierto (en vivo). */
    public OptimistCalculo.Resultado calcular(Trn trn, Map<String, Object> cabecera, Map<String, List<Map<String, Object>>> lineas) {
        return OptimistCalculo.calcular(trn, cabecera, lineas, abridor());
    }

    static void aplicarDefectos(Nivel nivel, Map<String, Object> v) { aplicarDefectos(nivel, v, null); }

    /** V60b: salvo != null: esos campos no toman el valor por defecto (una duplicación: quedan como en el original). */
    static void aplicarDefectos(Nivel nivel, Map<String, Object> v, java.util.Set<String> salvo) {
        for (Campo c : nivel.persistidos())
            if (c.defectoActual() != null && vacio(v.get(c.nombre())) && (salvo == null || !salvo.contains(c.nombre())))
                v.put(c.nombre(), c.defectoActual());
    }

    /**
     * V60b: mientras un Business Component hecho con Clone() se graba: los campos (de la cabecera y de cada renglón, en
     * orden) que quedan como en el original y no toman el valor por defecto. null = ninguno.
     */
    private record Protegidos(java.util.Set<String> cab, Map<String, List<java.util.Set<String>>> lineas) {}
    private static final ThreadLocal<Protegidos> PROTEGIDOS = new ThreadLocal<>();
    static void protegerDefectos(java.util.Set<String> cab, Map<String, List<java.util.Set<String>>> lineas) {
        if (cab == null && lineas == null) PROTEGIDOS.remove();
        else PROTEGIDOS.set(new Protegidos(cab == null ? java.util.Set.of() : cab, lineas == null ? Map.of() : lineas));
    }

    static void auditar(Trn trn, Map<String, Object> v, boolean nuevo, String usuario) {
        Map<String, String> a = trn.auditoria();
        LocalDateTime ahora = LocalDateTime.now().withNano(0);
        String quien = usuario == null ? "" : usuario;
        if (nuevo) {
            if (a.containsKey("altaFecha")) v.put(a.get("altaFecha"), fechaPara(trn.cabecera().campo(a.get("altaFecha")), ahora));
            if (a.containsKey("altaUsuario")) v.put(a.get("altaUsuario"), quien);
            // V61b: un alta no tiene modificación: fecha vacía de GeneXus (1000-01-01) y usuario vacío. En una duplicación
            // (Clone) antes quedaban los del original.
            if (a.containsKey("modificacionFecha"))
                v.put(a.get("modificacionFecha"), fechaPara(trn.cabecera().campo(a.get("modificacionFecha")), FECHA_VACIA.atStartOfDay()));
            if (a.containsKey("modificacionUsuario")) v.put(a.get("modificacionUsuario"), "");
        } else {
            if (a.containsKey("modificacionFecha")) v.put(a.get("modificacionFecha"), fechaPara(trn.cabecera().campo(a.get("modificacionFecha")), ahora));
            if (a.containsKey("modificacionUsuario")) v.put(a.get("modificacionUsuario"), quien);
        }
    }
    private static Object fechaPara(Campo c, LocalDateTime ahora) {
        return c.dominio().tipo() == Tipo.DATE ? ahora.toLocalDate() : ahora;
    }

    public static void validar(Nivel nivel, Map<String, Object> v, String donde) { validar(nivel, v, donde, false); }

    public static void validar(Nivel nivel, Map<String, Object> v, String donde, boolean alta) {
        for (Campo c : nivel.persistidos()) {
            Object x = v.get(c.nombre());
            if (c.autonumerico() && alta) continue;
            if (c.obligatorio() && vacio(x))
                throw new IllegalArgumentException(c.mensaje() != null ? donde + ": " + c.mensaje() : donde + ": " + c.etiqueta() + " es obligatorio");
            if (vacio(x)) continue;
            Dominio d = c.dominio();
            if (d.esTexto() && String.valueOf(x).length() > d.longitud())
                throw new IllegalArgumentException(c.etiqueta() + ": máximo " + d.longitud() + " caracteres");
            if (d.esNumerico()) {
                BigDecimal n = decimal(x);
                if (n.scale() > d.decimales() && n.setScale(d.decimales(), RoundingMode.DOWN).compareTo(n) != 0)
                    throw new IllegalArgumentException(c.etiqueta() + ": máximo " + d.decimales() + " decimales");
                int enteros = n.abs().toBigInteger().toString().length();
                if (enteros > Math.max(1, d.longitud() - d.decimales()))
                    throw new IllegalArgumentException(c.etiqueta() + ": máximo " + (d.longitud() - d.decimales()) + " dígitos enteros");
            }
            // V48c: el vacío de GeneXus (0 / "") en un campo no obligatorio se acepta aunque no sea uno de los valores
            // (ej. Medida de Tiempo en un presupuesto sin plazo de entrega: el PRS lo tiene en 0 y se copia al pedido)
            boolean vacioGx = !c.obligatorio() && (d.esNumerico() ? decimal(x).signum() == 0 : String.valueOf(x).isBlank());
            if (d.esCombo() && !vacioGx && d.valores().stream().noneMatch(val -> igual(val.valor(), x)))
                throw new IllegalArgumentException(c.etiqueta() + ": valor no permitido " + x);
        }
    }

    private static void validarDetalle(Nivel det, List<Linea> lineas) {
        // V65: clave con 'siguiente: true': las líneas nuevas sin número toman el mayor + 1 (en el orden de la grilla)
        for (Campo k : det.claves()) {
            if (det.numerador() != null || !OptimistTrnDefinicion.siguiente(det.tabla(), k.nombre())) continue;
            long max = 0;
            for (Linea l : lineas) {
                Object v = l.nueva() ? l.valores().get(k.nombre()) : l.claveOriginal().get(k.nombre());
                if (!vacio(v)) max = Math.max(max, decimal(v).longValue());
            }
            for (Linea l : lineas)
                if (l.nueva() && OptimistProcedimiento.Funciones.vacioGx(l.valores().get(k.nombre())))
                    l.valores().put(k.nombre(), OptimistTrnDefinicion.normalizar(k.dominio(), ++max));
        }
        Set<String> claves = new HashSet<>();
        int n = 0;
        for (Linea l : lineas) {
            n++;
            if (det.numerador() != null && l.nueva()) {
                // la clave la asigna el numerador; solo se validan los demás campos
                Map<String, Object> v = new HashMap<>(l.valores());
                v.put(det.claves().get(0).nombre(), 0);
                validar(det, v, det.titulo() + " línea " + n);
                continue;
            }
            validar(det, l.valores(), det.titulo() + " línea " + n);
            Map<String, Object> k = l.nueva() ? l.valores() : l.claveOriginal();
            if (!claves.add(textoClave(det.claves(), k)))
                throw new IllegalArgumentException(det.titulo() + ": " + textoClave(det.claves(), k) + " está repetido");
        }
    }

    /** Integridad referencial: si la FK tiene valor, tiene que existir en la tabla referenciada. */
    private void validarReferencias(Connection c, Nivel nivel, Nivel cabecera, Map<String, Object> valores,
                                    Map<String, Object> valoresCabecera, String donde) throws SQLException {
        validarReferencias(c, nivel, cabecera, valores, valoresCabecera, donde, null, null);
    }

    /**
     * V65: claveLinea / claveCab: un renglón ya grabado. Sus referencias con 'donde' (ej. artículo activo) se validan solo si
     * el renglón cambió esa referencia: un pedido viejo con un artículo que después se desactivó se puede seguir modificando.
     */
    private void validarReferencias(Connection c, Nivel nivel, Nivel cabecera, Map<String, Object> valores,
                                    Map<String, Object> valoresCabecera, String donde,
                                    Map<String, Object> claveLinea, Map<String, Object> claveCab) throws SQLException {
        Map<ClaveCache, Map<String, Object>> cache = new HashMap<>();
        for (Referencia ref : nivel.referencias()) {
            boolean todasPropias = ref.campos().keySet().stream().allMatch(l -> {
                Campo f = campoLocal(nivel, cabecera, l);
                return f != null && f.persistido();
            });
            if (!todasPropias) continue;
            // V50y: una referencia que admite nulo con 0 (o vacío) se graba NULL, como GeneXus (antes: "no existe Transporte Id 0")
            boolean anular = ref.campos().keySet().stream().allMatch(l -> {
                Campo f = nivel.campo(l);
                return f != null && f.nulo() && OptimistProcedimiento.Funciones.vacioGx(valores.get(f.nombre()));
            });
            if (anular) { for (String l : ref.campos().keySet()) valores.put(nivel.campo(l).nombre(), null); continue; }
            // V65i: como GeneXus, una clave foránea compuesta con alguna parte nula no se controla (ej. PED con la lista 99 y
            // sin número vigente: "no existe Lista / Número 99 / 0"); esa parte se graba NULL
            if (algunaNula(ref, nivel, valores)) {
                for (String l : ref.campos().keySet()) { Campo f = nivel.campo(l); if (f != null && f.nulo() && OptimistProcedimiento.Funciones.vacioGx(valores.get(f.nombre()))) valores.put(f.nombre(), null); }
                continue;
            }
            // V65: referencia opcional: con 0 o vacío no se valida (queda 0, la columna no admite nulo)
            if (OptimistTrnDefinicion.referenciaOpcional(nivel.tabla(), ref) && ref.campos().keySet().stream()
                    .allMatch(l -> OptimistProcedimiento.Funciones.vacioGx(valorDe(nivel, cabecera, valores, valoresCabecera).apply(l)))) continue;
            Map<String, Object> fk = valoresFk(ref, nivel, cabecera, valores, valoresCabecera);
            if (fk == null) continue;
            if (claveLinea != null && claveCab != null && !ref.donde().isEmpty() && sinCambios(c, nivel, ref, fk, claveCab, claveLinea)) continue;
            if (buscarUno(c, ref, nivel, cabecera, fk, cache, valorDe(nivel, cabecera, valores, valoresCabecera)) == null) {
                String quien = ref.campos().keySet().stream().map(l -> campoLocal(nivel, cabecera, l).etiqueta()).collect(Collectors.joining(" / "));
                String valor = fk.values().stream().map(String::valueOf).collect(Collectors.joining(" / "));
                if (ref.conCodigo()) { String cod = codigoDe(ref, fk.values().iterator().next()); if (cod != null) valor = cod; }
                String motivo = motivoNoValido(c, nivel, ref, fk);
                throw new IllegalArgumentException(donde + ": " + (motivo == null ? "no existe " + quien + " " + valor : quien + " " + valor + " " + motivo));
            }
        }
    }

    /** V65: ¿el renglón grabado tiene en la base los mismos valores de esta referencia? (entonces no se vuelve a validar) */
    private boolean sinCambios(Connection c, Nivel det, Referencia ref, Map<String, Object> fk, Map<String, Object> claveCab,
                               Map<String, Object> claveLinea) throws SQLException {
        List<String> locales = new ArrayList<>(fk.keySet());
        if (locales.stream().anyMatch(l -> det.campo(l) == null)) return false;   // la referencia usa campos de la cabecera
        List<String> claves = new ArrayList<>(claveCab.keySet());
        claves.addAll(claveLinea.keySet());
        String sql = "SELECT " + locales.stream().map(OptimistTrnDatos::col).collect(Collectors.joining(",")) + " FROM " + det.tabla()
                + " WHERE " + claves.stream().map(k -> col(k) + "=?").collect(Collectors.joining(" AND "));
        try (PreparedStatement p = c.prepareStatement(sql)) {
            int i = 1;
            for (String k : claveCab.keySet()) p.setObject(i++, claveCab.get(k));
            for (String k : claveLinea.keySet()) p.setObject(i++, claveLinea.get(k));
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return false;
                for (int j = 0; j < locales.size(); j++)
                    if (!igual(leer(rs, j + 1, det.campo(locales.get(j)).dominio()), fk.get(locales.get(j)))) return false;
                return true;
            }
        }
    }

    /**
     * V65: el registro existe pero no cumple el 'donde' de la referencia (ej. un artículo que no está activo): el 'mensaje' de la
     * referencia ("no está activo") o "no está habilitado". null si directamente no existe.
     */
    private String motivoNoValido(Connection c, Nivel nivel, Referencia ref, Map<String, Object> fk) throws SQLException {
        if (ref.donde().isEmpty()) return null;
        Referencia sinDonde = new Referencia(ref.tabla(), ref.campos(), new LinkedHashMap<>(), ref.descripcion(), new LinkedHashMap<>(), ref.codigo());
        List<Campo> fkCampos = new ArrayList<>();
        for (String l : ref.campos().keySet()) { Campo f = nivel.campo(l); if (f == null) return null; fkCampos.add(f); }
        String sql = "SELECT 1 FROM " + ref.tabla() + " WHERE " + sinDonde.campos().values().stream().map(x -> col(x) + "=?").collect(Collectors.joining(" AND "));
        try (PreparedStatement p = c.prepareStatement(sql)) {
            int i = 1;
            for (Campo fc : fkCampos) escribir(p, i++, fc, fk.get(fc.nombre()));
            try (ResultSet rs = p.executeQuery()) { if (!rs.next()) return null; }
        }
        String m = OptimistTrnDefinicion.mensajeReferencia(nivel.tabla(), ref);
        return m != null ? m : "no está habilitado";
    }

    /** V65: para los avisos al salir del campo: ídem motivoNoValido, con su propia conexión. */
    public String motivoNoValido(Nivel nivel, Referencia ref, Map<String, Object> fk) {
        try (Connection c = abrir()) { return motivoNoValido(c, nivel, ref, fk); }
        catch (SQLException | RuntimeException e) { return null; }
    }

    // ------------------------------------------------------------------ conversión de tipos

    static Map<String, Object> leerFila(ResultSet r, List<Campo> campos) throws SQLException {
        Map<String, Object> v = new LinkedHashMap<>();
        int i = 1;
        for (Campo c : campos) v.put(c.nombre(), leer(r, i++, c.dominio()));
        return v;
    }

    static Object leer(ResultSet r, int i, Dominio d) throws SQLException {
        return switch (d.tipo()) {
            case CHAR, VARCHAR -> { String s = r.getString(i); yield s == null ? "" : s.stripTrailing(); }
            case NUMERIC -> {
                BigDecimal b = r.getBigDecimal(i);
                if (b == null) yield null;
                yield d.esEntero() ? (Object) b.longValue() : b.setScale(d.decimales(), RoundingMode.HALF_UP);
            }
            case DATE -> { LocalDate f = r.getObject(i, LocalDate.class); yield fechaVacia(f) ? null : f; }
            case DATETIME -> { LocalDateTime f = r.getObject(i, LocalDateTime.class); yield fechaVacia(f) ? null : f; }
            case BOOLEAN -> { boolean b = r.getBoolean(i); yield r.wasNull() ? null : b; }
        };
    }

    /** Vacío: texto "" (o NULL si nulo:true), número 0 (o NULL), fecha 1000-01-01 (V61b: siempre), booleano false. Como GeneXus. */
    static void escribir(PreparedStatement p, int i, Campo c, Object x) throws SQLException {
        Dominio d = c.dominio();
        switch (d.tipo()) {
            case CHAR, VARCHAR -> {
                if (vacio(x)) { if (c.nulo()) p.setNull(i, Types.VARCHAR); else p.setString(i, ""); }
                else p.setString(i, d.tipo() == Tipo.CHAR ? String.valueOf(x).stripTrailing() : String.valueOf(x));
            }
            case NUMERIC -> {
                if (vacio(x)) { if (c.nulo()) p.setNull(i, Types.DECIMAL); else p.setBigDecimal(i, BigDecimal.ZERO); }
                else if (d.esEntero()) p.setLong(i, decimal(x).longValueExact());
                else p.setBigDecimal(i, decimal(x).setScale(d.decimales(), RoundingMode.HALF_UP));
            }
            // V61b: la fecha vacía se graba siempre como la de GeneXus (1000-01-01), aunque el campo admita nulo
            case DATE -> {
                if (vacio(x)) p.setObject(i, FECHA_VACIA);
                else p.setObject(i, x instanceof LocalDate f ? f : x instanceof LocalDateTime t ? t.toLocalDate() : LocalDate.parse(String.valueOf(x)));
            }
            case DATETIME -> {
                if (vacio(x)) p.setObject(i, FECHA_VACIA.atStartOfDay());
                else p.setObject(i, x instanceof LocalDateTime f ? f : x instanceof LocalDate f2 ? f2.atStartOfDay() : LocalDateTime.parse(String.valueOf(x)));
            }
            case BOOLEAN -> p.setBoolean(i, Boolean.TRUE.equals(x) || "true".equalsIgnoreCase(String.valueOf(x)));
        }
    }

    /** V48: GeneXus guarda la fecha vacía como 1000-01-01 en las columnas que no admiten NULL; se muestra vacía. */
    static final LocalDate FECHA_VACIA = LocalDate.of(1000, 1, 1);

    static boolean fechaVacia(Object x) {
        return x instanceof LocalDate f ? f.getYear() <= 1000 : x instanceof LocalDateTime t ? t.getYear() <= 1000
                : x instanceof java.sql.Date d ? d.toLocalDate().getYear() <= 1000 : x instanceof java.sql.Timestamp t2 && t2.toLocalDateTime().getYear() <= 1000;
    }

    static boolean vacio(Object x) { return x == null || (x instanceof String s && s.isBlank()); }

    static BigDecimal decimal(Object x) {
        if (x instanceof BigDecimal b) return b;
        if (x instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(String.valueOf(x).trim());
    }

    /** Compara valores de combo sin importar si vienen como Long, BigDecimal o String. */
    static boolean igual(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if ((a instanceof Number || b instanceof Number)) {
            try { return decimal(a).compareTo(decimal(b)) == 0; } catch (RuntimeException e) { return false; }
        }
        return String.valueOf(a).stripTrailing().equals(String.valueOf(b).stripTrailing());
    }

    /** Clave como texto normalizado: sirve para comparar y para los mensajes. */
    static String textoClave(List<Campo> claves, Map<String, Object> v) {
        return claves.stream().map(c -> {
            Object x = v.get(c.nombre());
            if (x == null) return "";
            if (x instanceof LocalDate f) return f.format(DMY);
            if (c.dominio().esNumerico()) return decimal(x).stripTrailingZeros().toPlainString();
            return String.valueOf(x).stripTrailing();
        }).collect(Collectors.joining(" / "));
    }

    static Map<String, Object> subMapa(Map<String, Object> v, List<Campo> campos) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Campo c : campos) m.put(c.nombre(), v.get(c.nombre()));
        return m;
    }

    // ------------------------------------------------------------------ armado de SQL

    /** V48c: nombre de la columna en MySQL: GeneXus recorta a 40 caracteres (TransportesTarifasPorImportePorPorcentaje -> ...Porcentaj). */
    static String col(String nombre) { return nombre != null && nombre.length() > 40 ? nombre.substring(0, 40) : nombre; }

    static String columnas(List<Campo> cs) { return cs.stream().map(c -> col(c.nombre())).collect(Collectors.joining(",")); }
    static String marcas(int n) { return String.join(",", Collections.nCopies(n, "?")); }
    static String igualdades(List<Campo> cs) { return cs.stream().map(c -> col(c.nombre()) + "=?").collect(Collectors.joining(" AND ")); }
    static String asignaciones(List<Campo> cs) { return cs.stream().map(c -> col(c.nombre()) + "=?").collect(Collectors.joining(",")); }
    /** Orden de las líneas de un detalle: solo sus campos guardados (un inferido necesitaría el JOIN de su referencia). */
    static String orden(Nivel n) {
        List<String> propios = n.orden().stream().filter(o -> { Campo c = n.campo(o.trim().split("\\s+")[0]); return c != null && c.persistido(); }).toList();
        return propios.isEmpty() ? columnas(n.claves()) : String.join(",", propios);
    }

    /**
     * V61f: columnas de la tabla que no admiten nulo, no tienen valor por defecto y la TRN no graba (ej. ArticulosFoto, la
     * imagen de GeneXus, que gx2yaml omite): en el alta van con el vacío de GeneXus (0, "", 1000-01-01, sin bytes), como
     * las graba GeneXus. Antes MySQL cortaba el alta ("Field 'ArticulosFoto' doesn't have a default value").
     * Devuelve nombre real de la columna -> valor.
     */
    private static Map<String, Object> omitidas(Connection c, String tabla, List<Campo> cols, List<String> otras) {
        try {
            OptimistProcedimiento.TablaBD t = OptimistProcedimiento.esquema(c).get(tabla.replace("`", "").toLowerCase(Locale.ROOT));
            if (t == null || t.vacios().isEmpty()) return Map.of();
            Set<String> puestas = new HashSet<>();
            for (Campo x : cols) {
                String n = x.nombre().toLowerCase(Locale.ROOT);
                puestas.add(n);
                if (n.length() > 40) puestas.add(n.substring(0, 40));   // GeneXus recorta los nombres de columna a 40
            }
            for (String o : otras) puestas.add(o.toLowerCase(Locale.ROOT));
            Map<String, Object> r = new LinkedHashMap<>();
            for (var e : t.vacios().entrySet())
                if (!puestas.contains(e.getKey())) r.put(t.columnas().getOrDefault(e.getKey(), e.getKey()), e.getValue());
            return r;
        } catch (SQLException ex) {
            return Map.of();
        }
    }

    private static String columnasOmitidas(Map<String, Object> omit) {
        return omit.keySet().stream().map(k -> ",`" + k + "`").collect(Collectors.joining());
    }

    private static int bindValores(PreparedStatement p, int i, List<Campo> cs, Map<String, Object> v) throws SQLException {
        for (Campo c : cs) escribir(p, i++, c, v.get(c.nombre()));
        return i;
    }
    private static int bindClave(PreparedStatement p, int i, List<Campo> cs, Map<String, Object> v) throws SQLException {
        return bindValores(p, i, cs, v);
    }
    private static String capital(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
