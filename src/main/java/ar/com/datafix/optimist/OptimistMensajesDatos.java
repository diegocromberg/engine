package ar.com.datafix.optimist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * V72 - Los mensajes internos del usuario (los que crea MensajesCrear: MensajesEnvios + MensajesEnviosUsuarios con
 * Forma de Comunicación 1 = Mensaje Interno), sin Vaadin (la campanita y su ventana están en OptimistMensajes).
 *
 * Leído / no leído:
 *  - MensajesEnviosUsuariosDiaHoraVisto (Fecha de Visualización) es la PRIMERA vez que el usuario lo leyó: se pone una sola
 *    vez y nunca se borra ni se cambia (Diego: es la prueba ante "yo no recibí nada"). GeneXus la pone igual al dar OK.
 *  - Marcar como no leído agrega un renglón en la tabla MensajesNoLeidos (del ERP nuevo; se crea sola si no existe), que no
 *    toca la Fecha de Visualización. Volver a leerlo saca ese renglón.
 *  - No leído = sin Fecha de Visualización, o con un renglón en MensajesNoLeidos.
 * No se borra nada: ni el mensaje ni su envío.
 */
public final class OptimistMensajesDatos {
    private OptimistMensajesDatos() {}

    /** Un mensaje del usuario. objeto / objetoId: el comprobante al que se refiere (ej. "PED" y su PEDId), o vacío. */
    public record Mensaje(long envioId, int usuarioId, String tipo, String texto, String objeto, long objetoId,
                          LocalDateTime enviado, LocalDateTime visto, boolean leido) {}

    /** Las bases donde ya se verificó la tabla MensajesNoLeidos (cliente + base). */
    private static final Set<String> CON_TABLA = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Crea MensajesNoLeidos si no existe (una vez por base). */
    static void asegurarTabla(Connection c) throws SQLException {
        String k = OptimistProcedimiento.claveBase(c);
        if (CON_TABLA.contains(k)) return;
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS MensajesNoLeidos ("
                    + "MensajesEnviosId BIGINT NOT NULL, MensajesEnviosUsuariosId INT NOT NULL, "
                    + "PRIMARY KEY (MensajesEnviosId, MensajesEnviosUsuariosId)) ENGINE=InnoDB");
        }
        CON_TABLA.add(k);
    }

    private static final String DESDE =
            " FROM MensajesEnviosUsuarios u"
          + " JOIN MensajesEnvios e ON e.MensajesEnviosId = u.MensajesEnviosId"
          + " LEFT JOIN Mensajes m ON m.MensajesId = e.MensajesId"
          + " LEFT JOIN MensajesNoLeidos n ON n.MensajesEnviosId = u.MensajesEnviosId AND n.MensajesEnviosUsuariosId = u.MensajesEnviosUsuariosId"
          + " WHERE u.MensajesEnviosUsuariosSecUserId = ? AND u.MensajesEnviosUsuariosFormaComunicacion = 1";

    /** Sin leer: sin fecha de visualización (NULL o la vacía de GeneXus, 1000-01-01) o marcado como no leído. */
    private static final String SIN_LEER =
            " AND (u.MensajesEnviosUsuariosDiaHoraVisto IS NULL OR u.MensajesEnviosUsuariosDiaHoraVisto < '1001-01-01' OR n.MensajesEnviosId IS NOT NULL)";

    /** Cantidad sin leer y el envío más nuevo sin leer (para saber si llegó uno nuevo). */
    public record Resumen(int sinLeer, long ultimoSinLeer) {}

    static Resumen resumen(Connection c, long secUserId) throws SQLException {
        asegurarTabla(c);
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*), COALESCE(MAX(u.MensajesEnviosId), 0)" + DESDE + SIN_LEER)) {
            p.setLong(1, secUserId);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? new Resumen(rs.getInt(1), rs.getLong(2)) : new Resumen(0, 0); }
        }
    }

    static int contar(Connection c, long secUserId, boolean soloSinLeer) throws SQLException {
        asegurarTabla(c);
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*)" + DESDE + (soloSinLeer ? SIN_LEER : ""))) {
            p.setLong(1, secUserId);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    /** Una página, del más nuevo al más viejo. */
    static List<Mensaje> pagina(Connection c, long secUserId, boolean soloSinLeer, int desde, int cuantos) throws SQLException {
        asegurarTabla(c);
        List<Mensaje> r = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT u.MensajesEnviosId, u.MensajesEnviosUsuariosId, m.MensajesDescrip,"
                + " e.MensajesEnviosTexto, e.MensajesEnviosObjeto, e.MensajesEnviosObjetoId, u.MensajesEnviosUsuariosDiaHoraEnvio,"
                + " u.MensajesEnviosUsuariosDiaHoraVisto, n.MensajesEnviosId" + DESDE + (soloSinLeer ? SIN_LEER : "")
                + " ORDER BY u.MensajesEnviosUsuariosDiaHoraEnvio DESC, u.MensajesEnviosId DESC LIMIT ? OFFSET ?")) {
            p.setLong(1, secUserId);
            p.setInt(2, cuantos);
            p.setInt(3, desde);
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    LocalDateTime visto = fecha(rs.getTimestamp(8));
                    boolean marcadoNoLeido = rs.getObject(9) != null;
                    r.add(new Mensaje(rs.getLong(1), rs.getInt(2), texto(rs.getString(3)), texto(rs.getString(4)),
                            texto(rs.getString(5)).strip(), rs.getLong(6), fecha(rs.getTimestamp(7)), visto,
                            visto != null && !marcadoNoLeido));
                }
            }
        }
        return r;
    }

    /** Leído: la Fecha de Visualización se pone solo si está vacía (la primera vez); se saca la marca de no leído. */
    static void marcarLeido(Connection c, long secUserId, long envioId, int usuarioId) throws SQLException {
        asegurarTabla(c);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            try (PreparedStatement p = c.prepareStatement("UPDATE MensajesEnviosUsuarios SET MensajesEnviosUsuariosDiaHoraVisto = ?"
                    + " WHERE MensajesEnviosId = ? AND MensajesEnviosUsuariosId = ? AND MensajesEnviosUsuariosSecUserId = ?"
                    + " AND (MensajesEnviosUsuariosDiaHoraVisto IS NULL OR MensajesEnviosUsuariosDiaHoraVisto < '1001-01-01')")) {
                p.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
                p.setLong(2, envioId); p.setInt(3, usuarioId); p.setLong(4, secUserId);
                p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("DELETE FROM MensajesNoLeidos WHERE MensajesEnviosId = ? AND MensajesEnviosUsuariosId = ?")) {
                p.setLong(1, envioId); p.setInt(2, usuarioId);
                p.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(auto); }
    }

    /** No leído: solo la marca; la Fecha de Visualización queda como está. Solo un mensaje del propio usuario. */
    static void marcarNoLeido(Connection c, long secUserId, long envioId, int usuarioId) throws SQLException {
        asegurarTabla(c);
        try (PreparedStatement p = c.prepareStatement("INSERT IGNORE INTO MensajesNoLeidos (MensajesEnviosId, MensajesEnviosUsuariosId)"
                + " SELECT MensajesEnviosId, MensajesEnviosUsuariosId FROM MensajesEnviosUsuarios"
                + " WHERE MensajesEnviosId = ? AND MensajesEnviosUsuariosId = ? AND MensajesEnviosUsuariosSecUserId = ?")) {
            p.setLong(1, envioId); p.setInt(2, usuarioId); p.setLong(3, secUserId);
            p.executeUpdate();
        }
    }

    /** Todos leídos: la Fecha de Visualización a los que no la tienen y fuera las marcas de no leído del usuario. */
    static int marcarTodosLeidos(Connection c, long secUserId) throws SQLException {
        asegurarTabla(c);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            int n;
            try (PreparedStatement p = c.prepareStatement("UPDATE MensajesEnviosUsuarios SET MensajesEnviosUsuariosDiaHoraVisto = ?"
                    + " WHERE MensajesEnviosUsuariosSecUserId = ? AND MensajesEnviosUsuariosFormaComunicacion = 1"
                    + " AND (MensajesEnviosUsuariosDiaHoraVisto IS NULL OR MensajesEnviosUsuariosDiaHoraVisto < '1001-01-01')")) {
                p.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
                p.setLong(2, secUserId);
                n = p.executeUpdate();
            }
            try (PreparedStatement p = c.prepareStatement("DELETE n FROM MensajesNoLeidos n JOIN MensajesEnviosUsuarios u"
                    + " ON u.MensajesEnviosId = n.MensajesEnviosId AND u.MensajesEnviosUsuariosId = n.MensajesEnviosUsuariosId"
                    + " WHERE u.MensajesEnviosUsuariosSecUserId = ?")) {
                p.setLong(1, secUserId);
                n += p.executeUpdate();
            }
            c.commit();
            return n;
        } catch (SQLException e) { c.rollback(); throw e; }
        finally { c.setAutoCommit(auto); }
    }

    private static LocalDateTime fecha(Timestamp t) {
        if (t == null) return null;
        LocalDateTime f = t.toLocalDateTime();
        return f.getYear() <= 1000 ? null : f;   // la fecha vacía de GeneXus
    }

    private static String texto(String s) { return s == null ? "" : s; }
}
