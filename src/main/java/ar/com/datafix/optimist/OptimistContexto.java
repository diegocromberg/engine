package ar.com.datafix.optimist;

import com.vaadin.flow.server.VaadinSession;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V30 - Contexto de la sesión: el WWPContext de GeneXus.
 * Se arma al iniciar sesión (SecLogIn -> SetDefaultUsuario): el motor pone el usuario (SecUserId, SecUserName) y cada
 * aplicación agrega lo suyo con sus extensiones (E3; en OPTIMIST ERP: empresa, división, stock, costos, listas...).
 * En los YAML se usa como "&SecUserId", "&Empresa"...:
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

    /** E3: lo que se muestra al lado del usuario en la barra (en OPTIMIST ERP: "Razón social - División"); lo pone la aplicación. */
    private String caption = "";
    public String caption() { return caption; }
    public void caption(String c) { caption = c == null ? "" : c; }

    public void guardarEnSesion() {
        VaadinSession.getCurrent().setAttribute(ATRIBUTO, this);
    }

    /**
     * SetDefaultUsuario de GeneXus. Devuelve null si el usuario no existe.
     * El motor lee el usuario de SecUser (SecUserId, SecUserName); lo demás del contexto (empresa, división...) lo agrega
     * cada aplicación con sus extensiones (E3, OptimistExtension.alIniciarSesion), que pueden cortar el ingreso con un
     * SQLException con el mensaje para el usuario (ej. "Usuario no habilitado").
     */
    public static OptimistContexto cargar(Connection cn, String usuario) throws SQLException {
        OptimistContexto x = new OptimistContexto();
        try (PreparedStatement p = cn.prepareStatement("SELECT SecUserId, SecUserName FROM SecUser WHERE SecUserName=?")) {
            p.setString(1, usuario);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                x.valores.put("SecUserId", r.getObject(1));
                x.valores.put("SecUserName", r.getString(2));
            }
        }
        for (OptimistExtension e : OptimistExtension.todas()) e.alIniciarSesion(cn, usuario, x);
        return x;
    }

    /** IsEmpty() de GeneXus: null, texto en blanco o número 0. */
    static boolean vacio(Object v) {
        if (v == null) return true;
        if (v instanceof Number n) return n.doubleValue() == 0;
        return v.toString().isBlank();
    }

    @Override public String toString() { return "Contexto" + valores; }

}
