package ar.com.datafix.optimist;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * E4 - Los usuarios de la aplicación. El motor no da por sentada ninguna tabla: cada aplicación dice en
 * src/main/resources/seguridad.yaml dónde están sus usuarios (guía, sección 24):
 * <pre>
 * usuarios:
 *   tabla: SecUser           # la de WorkWithPlus; puede ser cualquier otra
 *   id: SecUserId
 *   nombre: SecUserName      # lo que se escribe en "Usuario"
 *   clave: SecUserPassword
 *   tipoClave: texto         # texto | md5 | sha1 | sha256 (el hash en hexadecimal)
 * </pre>
 * Una aplicación con otro sistema de ingreso (ej. GAM de GeneXus) lo resuelve con su extensión (OptimistExtension.autenticar).
 * En el contexto el usuario queda siempre como &SecUserId y &SecUserName, como en GeneXus, aunque la tabla sea otra.
 */
public final class OptimistUsuarios {
    private OptimistUsuarios() {}

    /** El resultado de un ingreso: si es válido, el id y el nombre del usuario (como están en la base). */
    public record Ingreso(boolean valido, Object id, String nombre) {
        public static Ingreso rechazado() { return new Ingreso(false, null, null); }
        public static Ingreso de(Object id, String nombre) { return new Ingreso(true, id, nombre); }
    }

    record Config(String tabla, String id, String nombre, String clave, String tipoClave) {}

    private static volatile Config CONFIG;

    /** seguridad.yaml (se lee una vez). Sin el archivo: error claro al ingresar. */
    @SuppressWarnings("unchecked")
    static Config config() throws SQLException {
        Config c = CONFIG;
        if (c != null) return c;
        Map<String, Object> y;
        try (InputStream in = OptimistRecursos.abrir("seguridad.yaml")) {
            if (in == null) throw new SQLException("Falta configurar los usuarios de la aplicación: src/main/resources/seguridad.yaml (guía, sección 24)");
            y = new Yaml().load(in);
        } catch (SQLException e) { throw e; }
        catch (Exception e) { throw new SQLException("seguridad.yaml: " + e.getMessage(), e); }
        Object u = y == null ? null : y.get("usuarios");
        if (!(u instanceof Map<?, ?> m)) throw new SQLException("seguridad.yaml: falta 'usuarios:' con tabla, id, nombre y clave");
        Map<String, Object> mm = (Map<String, Object>) m;
        c = new Config(ident(mm, "tabla"), ident(mm, "id"), ident(mm, "nombre"), ident(mm, "clave"),
                mm.get("tipoClave") == null ? "texto" : String.valueOf(mm.get("tipoClave")).trim().toLowerCase(Locale.ROOT));
        if (!c.tipoClave().matches("texto|md5|sha1|sha256"))
            throw new SQLException("seguridad.yaml: tipoClave '" + c.tipoClave() + "' no válido (texto, md5, sha1 o sha256)");
        CONFIG = c;
        return c;
    }

    private static String ident(Map<String, Object> m, String clave) throws SQLException {
        Object v = m.get(clave);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (!s.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new SQLException("seguridad.yaml: usuarios." + clave + " falta o no es un nombre válido");
        return s;
    }

    /**
     * Valida usuario y contraseña: primero las extensiones de la aplicación (la primera que contesta manda), si no, la tabla de
     * seguridad.yaml. La comparación la hace la base (como siempre: con su collation; ej. en MySQL sin distinguir mayúsculas).
     */
    public static Ingreso validar(Connection cn, String usuario, String clave) throws SQLException {
        for (OptimistExtension e : OptimistExtension.todas()) {
            Ingreso i = e.autenticar(cn, usuario, clave);
            if (i != null) return i;
        }
        Config c = config();
        String valor = clave == null ? "" : clave;
        if (!c.tipoClave().equals("texto")) valor = hash(c.tipoClave(), valor);
        String sql = "SELECT " + c.id() + ", " + c.nombre() + " FROM " + c.tabla() + " WHERE " + c.nombre() + "=? AND "
                + (c.tipoClave().equals("texto") ? c.clave() + "=?" : "LOWER(" + c.clave() + ")=?");
        try (PreparedStatement p = cn.prepareStatement(sql)) {
            p.setString(1, usuario);
            p.setString(2, valor);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Ingreso.rechazado();
                return Ingreso.de(r.getObject(1), r.getString(2));
            }
        }
    }

    static String hash(String tipo, String texto) throws SQLException {
        try {
            String alg = switch (tipo) { case "md5" -> "MD5"; case "sha1" -> "SHA-1"; default -> "SHA-256"; };
            return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new SQLException("No se pudo calcular el hash " + tipo + ": " + e.getMessage(), e); }
    }
}
