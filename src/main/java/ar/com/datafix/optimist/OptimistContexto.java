package ar.com.datafix.optimist;

import com.vaadin.flow.server.VaadinSession;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * V30 - Contexto de la sesión: el WWPContext de GeneXus.
 * Se arma al iniciar sesión, igual que SecLogIn -> SetDefaultUsuario:
 *   PerfilUsuario        -> Empresa, División y color del logo del usuario
 *   Empresa/División     -> EmpresaCaption ("Razón social - División")
 *   EmpDivStkCst         -> empresa/división de stock, costos, listas de precios y cta2
 *                           (stock y costos, si están vacías, son la misma empresa/división)
 *   SecUser              -> SecUserEntidadesPK
 * En los YAML se usa como "&Empresa", "&Division", "&EmpLst"...:
 *   - default: "&Empresa"                         valor inicial en el alta
 *   - workwith.filtro: {PRSEmpresa: "&Empresa"}   el WorkWith muestra solo esa empresa
 */
public final class OptimistContexto implements Serializable {
    public static final String ATRIBUTO = "optimist.contexto";

    static { OptimistTrnDefinicion.instalarContexto(OptimistContexto::valorActual); }

    private final Map<String, Object> valores = new LinkedHashMap<>();

    // V48e: menú del usuario (tablas Menu y MenuSecUser de GeneXus), leído una vez al iniciar sesión
    List<String[]> menuNodos;            // {MenuId, MenuTitulo, MenuURL, MenuParentId}, por MenuId
    Set<String> menuPermitidos;          // MenuId que tiene el usuario en MenuSecUser
    transient Object menuArmado;         // el menú ya armado (OptimistMenu), para no rearmarlo en cada pantalla
    Set<String> menuRutas, menuRutasPermitidas;   // rutas de todo el menú / las del usuario (para no entrar por la URL)

    /** Contexto del usuario de esta sesión (null si no inició sesión). */
    public static OptimistContexto actual() {
        OptimistContexto h = HILO.get();   // V66x: un proceso en segundo plano (botón de un web panel) usa el de su sesión
        if (h != null) return h;
        VaadinSession s = VaadinSession.getCurrent();
        return s == null ? null : (OptimistContexto) s.getAttribute(ATRIBUTO);
    }

    /** V66x: el contexto de un hilo en segundo plano (no hay sesión de Vaadin en ese hilo). */
    private static final ThreadLocal<OptimistContexto> HILO = new ThreadLocal<>();

    /** V66x: corre algo en segundo plano con el contexto del usuario (el de la sesión que lo pidió). */
    public static <T> T conContexto(OptimistContexto ctx, java.util.concurrent.Callable<T> trabajo) throws Exception {
        OptimistContexto antes = HILO.get();
        HILO.set(ctx);
        try { return trabajo.call(); }
        finally { if (antes == null) HILO.remove(); else HILO.set(antes); }
    }

    static Object valorActual(String variable) {
        OptimistContexto c = actual();
        return c == null ? null : c.valor(variable);
    }

    public Object valor(String variable) {
        for (Map.Entry<String, Object> e : valores.entrySet())
            if (e.getKey().equalsIgnoreCase(variable)) return e.getValue();
        return null;
    }

    public Map<String, Object> valores() { return Collections.unmodifiableMap(valores); }

    /** V34: cambia un valor (SetWWPContext desde un procedimiento). */
    public void poner(String variable, Object valor) {
        String existente = null;
        for (String k : valores.keySet()) if (k.equalsIgnoreCase(variable)) existente = k;
        valores.put(existente == null ? variable : existente, valor);
    }

    public String caption() {
        Object c = valor("EmpresaCaption");
        return c == null ? "" : c.toString();
    }

    public boolean tieneEmpresa() { return !vacio(valor("Empresa")); }

    public void guardarEnSesion() {
        VaadinSession.getCurrent().setAttribute(ATRIBUTO, this);
    }

    /**
     * SetDefaultUsuario de GeneXus. Devuelve null si el usuario no existe.
     * Lanza SQLException con el mensaje para el usuario si no está habilitado.
     */
    public static OptimistContexto cargar(Connection cn, String usuario) throws SQLException {
        OptimistContexto x = new OptimistContexto();
        Object secUserId;
        boolean empresas = OptimistDatabase.conEmpresas();   // V83: sistema.empresas=false -> solo SecUser
        if (!empresas) {
            try (PreparedStatement p = cn.prepareStatement("SELECT SecUserId, SecUserName FROM SecUser WHERE SecUserName=?")) {
                p.setString(1, usuario);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) return null;
                    secUserId = r.getObject(1);
                    x.valores.put("SecUserId", secUserId);
                    x.valores.put("SecUserName", r.getString(2));
                }
            }
        } else
        try (PreparedStatement p = cn.prepareStatement(
                "SELECT SecUserId, SecUserName, SecUserHabilitado, SecUserEntidadesPK FROM SecUser WHERE SecUserName=?")) {
            p.setString(1, usuario);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                secUserId = r.getObject(1);
                x.valores.put("SecUserId", secUserId);
                x.valores.put("SecUserName", r.getString(2));
                Object hab = r.getObject(3);
                if (hab != null && !r.getBoolean(3)) throw new SQLException("Usuario no habilitado");
                x.valores.put("SecUserEntidadesPK", r.getObject(4));
            }
        }
        if (empresas)
        try (PreparedStatement p = cn.prepareStatement(
                "SELECT EmpresaId, EmpresaDivisionId, PerfilUsuarioColorLogo FROM PerfilUsuario WHERE PerfilUsuarioUserId=?")) {
            p.setObject(1, secUserId);
            try (ResultSet r = p.executeQuery()) {
                if (r.next()) {
                    x.valores.put("Empresa", r.getObject(1));
                    x.valores.put("Division", r.getObject(2));
                    x.valores.put("PerfilUsuarioColorLogo", r.getObject(3));
                }
            }
        }
        Object emp = x.valor("Empresa"), div = x.valor("Division");
        if (!vacio(emp)) {
            try (PreparedStatement p = cn.prepareStatement(
                    "SELECT e.EmpresaRazonSocial, d.EmpresaDivisionNombre FROM EmpresaDivision d"
                            + " JOIN Empresa e ON e.EmpresaId=d.EmpresaId WHERE d.EmpresaId=? AND d.EmpresaDivisionId=?")) {
                p.setObject(1, emp); p.setObject(2, div);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) x.valores.put("EmpresaCaption", trim(r.getString(1)) + " - " + trim(r.getString(2)));
                }
            }
            try (PreparedStatement p = cn.prepareStatement(
                    "SELECT STEmpresaStockId, STEmpresaDivisionStockId, STEmpresaCostosId, STEmpresaDivisionCostosId,"
                            + " STEmpresaListasId, STEmpresaDivisionListasId, STEmpresaCta2Id, STEmpresaDivisionCta2Id"
                            + " FROM EmpDivStkCst WHERE STEmpresaId=? AND STEmpresaDivisionId=?")) {
                p.setObject(1, emp); p.setObject(2, div);
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        x.valores.put("EmpStk", r.getObject(1));
                        x.valores.put("DivStk", r.getObject(2));
                        x.valores.put("EmpCst", r.getObject(3));
                        x.valores.put("DivCst", r.getObject(4));
                        if (!vacio(r.getObject(5)) && !vacio(r.getObject(6))) {
                            x.valores.put("EmpLst", r.getObject(5));
                            x.valores.put("DivLst", r.getObject(6));
                        }
                        if (!vacio(r.getObject(7)) && !vacio(r.getObject(8))) {
                            x.valores.put("EmpCta2", r.getObject(7));
                            x.valores.put("DivCta2", r.getObject(8));
                        }
                    }
                }
            }
        }
        // igual que GeneXus: stock y costos vacíos = la empresa/división del usuario
        if (vacio(x.valor("EmpStk"))) x.valores.put("EmpStk", emp);
        if (vacio(x.valor("DivStk"))) x.valores.put("DivStk", div);
        if (vacio(x.valor("EmpCst"))) x.valores.put("EmpCst", emp);
        if (vacio(x.valor("DivCst"))) x.valores.put("DivCst", div);
        // V48e: menú y permisos del usuario. Si las tablas no están, queda el menu.yaml de siempre.
        try {
            List<String[]> nodos = new ArrayList<>();
            try (PreparedStatement p = cn.prepareStatement("SELECT MenuId, MenuTitulo, MenuURL, MenuParentId FROM Menu WHERE MenuInstalled <> 0 ORDER BY MenuId");
                 ResultSet r = p.executeQuery()) {
                while (r.next()) nodos.add(new String[]{trim(r.getString(1)), trim(r.getString(2)), trim(r.getString(3)), trim(r.getString(4))});
            }
            Set<String> permitidos = new HashSet<>();
            try (PreparedStatement p = cn.prepareStatement("SELECT MenuId FROM MenuSecUser WHERE SecUserId=?")) {
                p.setObject(1, secUserId);
                try (ResultSet r = p.executeQuery()) { while (r.next()) permitidos.add(trim(r.getString(1))); }
            }
            x.menuNodos = nodos;
            x.menuPermitidos = permitidos;
        } catch (SQLException sinTablasDeMenu) { /* menú de menu.yaml */ }
        return x;
    }

    /** IsEmpty() de GeneXus: null, texto en blanco o número 0. */
    static boolean vacio(Object v) {
        if (v == null) return true;
        if (v instanceof Number n) return n.doubleValue() == 0;
        return v.toString().isBlank();
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    @Override public String toString() { return "Contexto" + valores; }

    /** Administrador sin empresa: puede entrar (como en GeneXus). */
    public static boolean esAdministrador(String usuario) {
        return usuario != null && usuario.toLowerCase(Locale.ROOT).equals("administrator");
    }
}
