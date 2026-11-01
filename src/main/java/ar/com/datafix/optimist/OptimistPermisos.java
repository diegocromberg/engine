package ar.com.datafix.optimist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.sql.*;
import java.util.*;

/**
 * E5a - Permisos (Diego, 01/11/2026): quién puede entrar a cada TRN, dar de alta, modificar, borrar, ver un campo, abrir un web
 * panel o una consulta, o hacer una acción. Se configura en src/main/resources/seguridad.yaml (guía, sección 24.3):
 * <pre>
 * permisos:
 *   tipo: wwp                       # wwp (seguridad avanzada de WorkWithPlus) | extension (la aplicación) | gam (más adelante)
 *   rolAdministrador: Administrator # el rol que tiene todo ("Actualizar permisos" le asigna lo nuevo)
 *   claves:                         # funcionalidades propias (además de las de cada TRN, panel y consulta)
 *     VerRentabilidad: Ver la rentabilidad
 * </pre>
 * Sin 'permisos:' todos pueden todo (como antes). Con wwp, la misma lógica que SecIsAuthByFunctionalityKey de GeneXus: la clave
 * tiene que estar en SecFunctionality y el usuario tener (por SecUserRole) un rol que la tenga (SecFunctionalityRole). Las
 * claves, como las de WorkWithPlus: WW&lt;Trn&gt; (entrar al WorkWith), WW&lt;Trn&gt;_Insert / _Update / _Delete, &lt;Campo&gt;_Attribute,
 * el nombre del web panel o de la consulta, y las que se declaran (permiso: en acciones y botones; claves: arriba).
 * Se leen una vez por sesión (al iniciar sesión), como GeneXus con la WebSession.
 */
public final class OptimistPermisos {
    private OptimistPermisos() {}
    private static final Logger LOG = LoggerFactory.getLogger(OptimistPermisos.class);

    /** La configuración (permisos: de seguridad.yaml). null = sin permisos (todos pueden todo). */
    /** administradores: usuarios (SecUserName) que siempre son administradores, aunque no tengan el rol (para no quedar afuera). */
    /** entrada: clave (WW&lt;Trn&gt; / nombre del panel o consulta para entrar) | menu (E5b: entrar lo decide el menú del usuario, como OPTIMIST). */
    public record Config(String tipo, String rolAdministrador, Map<String, String> claves, Set<String> administradores, String entrada) {}

    private static volatile Optional<Config> CONFIG;

    @SuppressWarnings("unchecked")
    public static Config config() {
        Optional<Config> c = CONFIG;
        if (c != null) return c.orElse(null);
        Config r = null;
        try (InputStream in = OptimistRecursos.abrir("seguridad.yaml")) {
            Map<String, Object> y = in == null ? null : new Yaml().load(in);
            Object p = y == null ? null : y.get("permisos");
            if (p instanceof Map<?, ?> m) {
                Map<String, Object> mm = (Map<String, Object>) m;
                String tipo = String.valueOf(mm.getOrDefault("tipo", "wwp")).trim().toLowerCase(Locale.ROOT);
                if (tipo.equals("gam")) throw new IllegalStateException("seguridad.yaml: permisos.tipo gam todavía no está (usar wwp o extension)");
                if (!tipo.matches("wwp|extension")) throw new IllegalStateException("seguridad.yaml: permisos.tipo '" + tipo + "' no válido (wwp o extension)");
                Map<String, String> claves = new LinkedHashMap<>();
                if (mm.get("claves") instanceof Map<?, ?> cm)
                    for (Map.Entry<?, ?> e : cm.entrySet()) {
                        String k = String.valueOf(e.getKey()).trim();
                        if (!k.matches("[A-Za-z_][A-Za-z0-9_]{0,99}")) throw new IllegalStateException("seguridad.yaml: permisos.claves: clave no válida '" + k + "'");
                        claves.put(k, e.getValue() == null ? k : String.valueOf(e.getValue()).trim());
                    }
                Set<String> admins = new HashSet<>();
                if (mm.get("administradores") instanceof List<?> la) {
                    for (Object o : la) if (o != null) admins.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
                } else if (mm.get("administradores") != null) admins.add(String.valueOf(mm.get("administradores")).trim().toLowerCase(Locale.ROOT));
                String entrada = String.valueOf(mm.getOrDefault("entrada", "clave")).trim().toLowerCase(Locale.ROOT);
                if (!entrada.matches("clave|menu")) throw new IllegalStateException("seguridad.yaml: permisos.entrada '" + entrada + "' no válido (clave o menu)");
                r = new Config(tipo, String.valueOf(mm.getOrDefault("rolAdministrador", "Administrator")).trim(), Map.copyOf(claves), Set.copyOf(admins), entrada);
            } else if (p != null && !Boolean.FALSE.equals(p)) throw new IllegalStateException("seguridad.yaml: 'permisos:' tiene que tener tipo, rolAdministrador…");
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("seguridad.yaml: " + e.getMessage(), e); }
        CONFIG = Optional.ofNullable(r);
        return r;
    }

    /** Si la aplicación maneja permisos. */
    public static boolean activo() { return config() != null; }

    // ------------------------------------------------------------------ las claves

    public static String claveTrn(OptimistTrnDefinicion.Trn t) { return "WW" + t.nombre(); }
    public static String claveModo(OptimistTrnDefinicion.Trn t, String modo) { return "WW" + t.nombre() + "_" + modo; }   // Insert, Update, Delete
    public static String claveCampo(String campo) { return campo + "_Attribute"; }

    // ------------------------------------------------------------------ al iniciar sesión

    /** Las claves del usuario (en minúscula) y si es administrador, en su contexto. Lo llama OptimistContexto.cargar. */
    static void alIniciarSesion(Connection cn, OptimistContexto ctx) throws SQLException {
        Config c = config();
        if (c == null) return;
        Object id = ctx.valor("SecUserId");
        Set<String> claves = new HashSet<>();
        boolean admin = false;
        if (c.tipo().equals("extension")) {
            for (OptimistExtension e : OptimistExtension.todas()) {
                Set<String> s = e.permisos(cn, ctx);
                if (s != null) { s.forEach(x -> claves.add(x.toLowerCase(Locale.ROOT))); break; }
            }
        } else {
            try (PreparedStatement p = cn.prepareStatement("SELECT DISTINCT f.SecFunctionalityKey FROM SecUserRole ur"
                    + " JOIN SecFunctionalityRole fr ON fr.SecRoleId = ur.SecRoleId"
                    + " JOIN SecFunctionality f ON f.SecFunctionalityId = fr.SecFunctionalityId WHERE ur.SecUserId = ?")) {
                p.setObject(1, id);
                try (ResultSet r = p.executeQuery()) { while (r.next()) if (r.getString(1) != null) claves.add(r.getString(1).trim().toLowerCase(Locale.ROOT)); }
            }
            try (PreparedStatement p = cn.prepareStatement("SELECT COUNT(*) FROM SecUserRole ur JOIN SecRole r ON r.SecRoleId = ur.SecRoleId"
                    + " WHERE ur.SecUserId = ? AND r.SecRoleName = ?")) {
                p.setObject(1, id);
                p.setString(2, c.rolAdministrador());
                try (ResultSet r = p.executeQuery()) { admin = r.next() && r.getInt(1) > 0; }
            }
        }
        Object nom = ctx.valor("SecUserName");
        if (nom != null && c.administradores().contains(String.valueOf(nom).trim().toLowerCase(Locale.ROOT))) admin = true;
        // el administrador tiene todo: el rol (Actualizar permisos le asigna lo nuevo) o la lista 'administradores'
        ctx.permisos = claves;
        ctx.permisosAdmin = admin;
    }

    // ------------------------------------------------------------------ preguntar

    /** SecIsAuthByFunctionalityKey: si el usuario de la sesión tiene esa clave. Sin permisos configurados: siempre. */
    public static boolean puede(String clave) {
        if (clave == null || config() == null) return true;
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx == null) return false;
        if (ctx.permisosAdmin && config().administradores().contains(String.valueOf(ctx.valor("SecUserName")).trim().toLowerCase(Locale.ROOT)))
            return true;   // de la lista 'administradores': todo, aunque su rol no tenga la clave
        Set<String> s = ctx.permisos;
        return s != null && s.contains(clave.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * E5b: con `entrada: menu` entrar a una TRN, un web panel o una consulta lo decide el menú del usuario (MenuSecUser,
     * OptimistMenu.permitida), como en OPTIMIST: la clave WW&lt;Trn&gt; / el nombre del panel queda para los hipervínculos y las
     * acciones que la pregunten. Con `entrada: clave` (por defecto), la clave del programa, como WorkWithPlus.
     */
    public static boolean entradaPorMenu() { Config c = config(); return c != null && c.entrada().equals("menu"); }

    /** Entrar a un programa: su clave, salvo que la entrada la decida el menú. */
    public static boolean puedeEntrar(String clave) { return entradaPorMenu() || puede(clave); }

    /** Si el usuario tiene el rol administrador (entra siempre al panel de Seguridad, aunque falten las claves). */
    public static boolean esAdministrador() {
        if (config() == null) return true;
        OptimistContexto ctx = OptimistContexto.actual();
        return ctx != null && ctx.permisosAdmin;
    }

    /** El panel de Seguridad: el administrador, o quien tenga la clave OptimistSeguridad. Solo con permisos configurados. */
    public static final String CLAVE_SEGURIDAD = "OptimistSeguridad";
    public static boolean puedeSeguridad() { return activo() && (esAdministrador() || puede(CLAVE_SEGURIDAD)); }

    /** Una ruta del menú (trn/x, wbp/x, cns/x, seguridad). Las demás pantallas no tienen clave. */
    public static boolean puedeRuta(String ruta) {
        if (ruta == null || config() == null) return true;
        if (entradaPorMenu() && !ruta.replaceAll("^/+", "").toLowerCase(Locale.ROOT).startsWith("seguridad")) return true;   // E5b: lo decide el menú
        String r = ruta.replaceAll("^/+", "");
        int i = r.indexOf('/');
        String tipo = i < 0 ? r : r.substring(0, i), nombre = i < 0 ? "" : r.substring(i + 1);
        int q = nombre.indexOf('?'); if (q >= 0) nombre = nombre.substring(0, q);
        switch (tipo.toLowerCase(Locale.ROOT)) {
            case "trn" -> {
                OptimistTrnDefinicion.Trn t = OptimistTrnCatalogo.cargar().trns().get(nombre.toLowerCase(Locale.ROOT));
                return t == null || puede(claveTrn(t));
            }
            case "wbp", "cns" -> { return nombre.isEmpty() || puede(nombre); }
            case "seguridad" -> { return puedeSeguridad(); }
            default -> { return true; }
        }
    }

    /** Si el campo de esa TRN está protegido (permisos.campos del YAML) y el usuario no tiene su clave: no se ve. */
    public static boolean campoOculto(OptimistTrnDefinicion.Trn t, String campo) {
        if (config() == null || t == null || campo == null) return false;
        return OptimistTrnDefinicion.camposConPermiso(t.nombre()).contains(campo.toLowerCase(Locale.ROOT)) && !puede(claveCampo(campo));
    }

    // ------------------------------------------------------------------ Actualizar permisos

    /** Una funcionalidad (renglón de SecFunctionality). tipo: 1 Mode, 2 Action, 3 Tab, 4 Object, 5 Attribute (WorkWithPlus). */
    public record Funcionalidad(String clave, String descripcion, int tipo, String padre) {}

    /** Todas las funcionalidades de la aplicación: las de cada TRN, web panel y consulta, y las declaradas. Los padres primero. */
    public static List<Funcionalidad> funcionalidades() {
        List<Funcionalidad> r = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        java.util.function.Consumer<Funcionalidad> agregar = f -> { if (vistas.add(f.clave().toLowerCase(Locale.ROOT))) r.add(f); };
        agregar.accept(new Funcionalidad(CLAVE_SEGURIDAD, "Seguridad (usuarios, roles y permisos)", 4, null));
        List<OptimistTrnDefinicion.Trn> trns = new ArrayList<>(OptimistTrnCatalogo.cargar().trns().values());
        trns.sort(Comparator.comparing(OptimistTrnDefinicion.Trn::nombre, String.CASE_INSENSITIVE_ORDER));
        for (OptimistTrnDefinicion.Trn t : trns) {
            String ww = claveTrn(t);
            agregar.accept(new Funcionalidad(ww, t.titulo(), 4, null));
            agregar.accept(new Funcionalidad(claveModo(t, "Insert"), t.titulo() + ": agregar", 1, ww));
            agregar.accept(new Funcionalidad(claveModo(t, "Update"), t.titulo() + ": modificar", 1, ww));
            agregar.accept(new Funcionalidad(claveModo(t, "Delete"), t.titulo() + ": eliminar", 1, ww));
            for (String campo : OptimistTrnDefinicion.camposConPermiso(t.nombre())) {
                OptimistTrnDefinicion.Campo c = t.cabecera().campo(campo);
                String n = c == null ? campo : c.nombre();
                agregar.accept(new Funcionalidad(claveCampo(n), t.titulo() + ": ver " + (c == null ? n : c.etiqueta()), 5, ww));
            }
            List<OptimistTrnDefinicion.AccionRenglon> acs = new ArrayList<>(t.workwith().acciones().renglon());
            acs.addAll(t.workwith().botones());
            for (OptimistTrnDefinicion.AccionRenglon a : acs)
                if (a.permiso() != null) agregar.accept(new Funcionalidad(a.permiso(), t.titulo() + ": " + a.titulo(), 2, ww));
        }
        for (String p : new TreeSet<>(OptimistPanelView.nombresPaneles())) {
            OptimistPanel x;
            try { x = OptimistPanelView.cargar(p); } catch (RuntimeException e) { continue; }
            agregar.accept(new Funcionalidad(x.nombre, x.titulo == null || x.titulo.isBlank() ? x.nombre : x.titulo, 4, null));
            List<OptimistPanel.Boton> bs = new ArrayList<>(x.botones);
            if (x.resultado != null) bs.addAll(x.resultado.botones());
            for (OptimistPanel.Boton b : bs)
                if (b.permiso() != null) agregar.accept(new Funcionalidad(b.permiso(), x.titulo + ": " + b.titulo(), 2, x.nombre));
        }
        for (String cn : new TreeSet<>(OptimistConsultaView.nombres())) {
            String real = OptimistConsultaView.nombreReal(cn), tit = OptimistConsultaView.tituloDe(cn);
            agregar.accept(new Funcionalidad(real, tit, 4, null));
            for (OptimistConsultaView.AccionCns a : OptimistConsultaView.accionesConPermiso(cn))   // E5b
                agregar.accept(new Funcionalidad(a.permiso(), tit + ": " + a.titulo(), 2, real));
        }
        Config c = config();
        if (c != null) c.claves().forEach((k, d) -> agregar.accept(new Funcionalidad(k, d, 2, null)));
        return r;
    }

    /** Lo que hizo "Actualizar permisos". */
    public record Actualizacion(List<String> nuevas, int asignadasAlAdministrador, boolean rolCreado) {}

    /**
     * "Actualizar permisos" (SecFunctionalitiesToLoad + SecGrantAllAccessToAdminRole de WorkWithPlus): da de alta en
     * SecFunctionality las claves que falten (con su descripción y su padre) y le asigna al rol administrador todo lo que no
     * tenga (si el rol no existe, lo crea). No borra nada. Todo en una sola transacción.
     */
    public static Actualizacion actualizar(Connection cn) throws SQLException {
        Config c = config();
        if (c == null || !c.tipo().equals("wwp")) throw new SQLException("Actualizar permisos es para permisos.tipo: wwp (seguridad.yaml)");
        boolean auto = cn.getAutoCommit();
        cn.setAutoCommit(false);
        try {
            Map<String, Long> ids = new HashMap<>();
            try (Statement s = cn.createStatement(); ResultSet r = s.executeQuery("SELECT SecFunctionalityId, SecFunctionalityKey FROM SecFunctionality")) {
                while (r.next()) if (r.getString(2) != null) ids.put(r.getString(2).trim().toLowerCase(Locale.ROOT), r.getLong(1));
            }
            List<String> nuevas = new ArrayList<>();
            try (PreparedStatement ins = cn.prepareStatement("INSERT INTO SecFunctionality (SecFunctionalityKey, SecFunctionalityDescription,"
                    + " SecFunctionalityType, SecParentFunctionalityId, SecFunctionalityActive) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                for (Funcionalidad f : funcionalidades()) {
                    String k = f.clave().toLowerCase(Locale.ROOT);
                    if (ids.containsKey(k)) continue;
                    ins.setString(1, corto(f.clave(), 100));
                    ins.setString(2, corto(f.descripcion(), 100));
                    ins.setInt(3, f.tipo());
                    Long padre = f.padre() == null ? null : ids.get(f.padre().toLowerCase(Locale.ROOT));
                    if (padre == null) ins.setNull(4, Types.BIGINT); else ins.setLong(4, padre);
                    ins.setBoolean(5, true);
                    ins.executeUpdate();
                    try (ResultSet g = ins.getGeneratedKeys()) {
                        if (g.next()) ids.put(k, g.getLong(1));
                        else try (PreparedStatement q = cn.prepareStatement("SELECT SecFunctionalityId FROM SecFunctionality WHERE SecFunctionalityKey = ?")) {
                            q.setString(1, f.clave());
                            try (ResultSet r = q.executeQuery()) { if (r.next()) ids.put(k, r.getLong(1)); }
                        }
                    }
                    nuevas.add(f.clave());
                }
            }
            // el rol administrador (si no está, se crea) y todo lo que le falte
            Long rol = null;
            boolean creado = false;
            try (PreparedStatement p = cn.prepareStatement("SELECT SecRoleId FROM SecRole WHERE SecRoleName = ?")) {
                p.setString(1, c.rolAdministrador());
                try (ResultSet r = p.executeQuery()) { if (r.next()) rol = r.getLong(1); }
            }
            if (rol == null) {
                try (PreparedStatement p = cn.prepareStatement("INSERT INTO SecRole (SecRoleName, SecRoleDescription) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1, c.rolAdministrador());
                    p.setString(2, c.rolAdministrador());
                    p.executeUpdate();
                    try (ResultSet g = p.getGeneratedKeys()) { if (g.next()) rol = g.getLong(1); }
                }
                if (rol == null) try (PreparedStatement p = cn.prepareStatement("SELECT SecRoleId FROM SecRole WHERE SecRoleName = ?")) {
                    p.setString(1, c.rolAdministrador());
                    try (ResultSet r = p.executeQuery()) { if (r.next()) rol = r.getLong(1); }
                }
                creado = true;
            }
            int asignadas = 0;
            try (PreparedStatement p = cn.prepareStatement("INSERT INTO SecFunctionalityRole (SecFunctionalityId, SecRoleId)"
                    + " SELECT f.SecFunctionalityId, ? FROM SecFunctionality f WHERE NOT EXISTS (SELECT 1 FROM SecFunctionalityRole x"
                    + " WHERE x.SecFunctionalityId = f.SecFunctionalityId AND x.SecRoleId = ?)")) {
                p.setLong(1, rol);
                p.setLong(2, rol);
                asignadas = p.executeUpdate();
            }
            cn.commit();
            LOG.info("Actualizar permisos: {} funcionalidades nuevas, {} asignadas al rol {}", nuevas.size(), asignadas, c.rolAdministrador());
            return new Actualizacion(nuevas, asignadas, creado);
        } catch (SQLException | RuntimeException e) {
            cn.rollback();
            throw e;
        } finally { cn.setAutoCommit(auto); }
    }

    /** Vuelve a leer los permisos del usuario de la sesión (después de cambiar roles o permisos). */
    public static void releer(Connection cn) throws SQLException {
        OptimistContexto ctx = OptimistContexto.actual();
        if (ctx != null) { alIniciarSesion(cn, ctx); ctx.menuArmado = null; }
    }

    private static String corto(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
}
