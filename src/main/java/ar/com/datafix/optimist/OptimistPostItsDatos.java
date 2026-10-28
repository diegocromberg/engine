package ar.com.datafix.optimist;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.UnaryOperator;

/** V69 - Los PostIts en la base (tabla PostIts de GeneXus) y su JSON, sin Vaadin (ver OptimistPostIts). */
public final class OptimistPostItsDatos {
    private OptimistPostItsDatos() {}

    /** Un PostIt. x / y / ancho / alto: en píxeles de la pantalla. vence: null = sin vencimiento. fecha: cuándo se pegó. */
    public record PostIt(String id, String texto, String color, int x, int y, int ancho, int alto, LocalDate vence,
                           String usuario, String fecha) {
        PostIt con(String texto, String color, LocalDate vence) {
            return new PostIt(id, texto, color, x, y, ancho, alto, vence, usuario, fecha);
        }
        PostIt en(int x, int y, int ancho, int alto) {
            return new PostIt(id, texto, color, x, y, ancho, alto, vence, usuario, fecha);
        }
    }

    /** Los colores (nombre -> fondo). */
    static final LinkedHashMap<String, String> COLORES = new LinkedHashMap<>();
    static {
        COLORES.put("amarillo", "#fff59d");
        COLORES.put("rosa", "#f8bbd0");
        COLORES.put("verde", "#c8e6c9");
        COLORES.put("celeste", "#b3e5fc");
        COLORES.put("naranja", "#ffcc80");
    }


    // ------------------------------------------------------------------ la tabla PostIts

    /** El PostItsId de un registro: "TRN|clave" (si pasa de 40 letras: el principio de la TRN y un resumen de la clave). */
    static String idRegistro(String trn, String clave) {
        String id = trn + "|" + clave;
        if (id.length() <= 40) return id;
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return trn.substring(0, Math.min(7, trn.length())) + "|" + sb.substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) { return id.substring(0, 40); }
    }

    static List<PostIt> leer(Connection c, String id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT PostItsXML FROM PostIts WHERE PostItsId = ?")) {
            p.setString(1, id);
            try (ResultSet rs = p.executeQuery()) { return rs.next() ? desdeJson(rs.getString(1)) : new ArrayList<>(); }
        }
    }

    /** Para el WorkWith: de varios registros a la vez (una consulta por página). id -> PostIts. */
    static Map<String, List<PostIt>> leerVarios(Connection c, Collection<String> ids) throws SQLException {
        Map<String, List<PostIt>> r = new HashMap<>();
        if (ids.isEmpty()) return r;
        List<String> l = new ArrayList<>(new LinkedHashSet<>(ids));
        try (PreparedStatement p = c.prepareStatement("SELECT PostItsId, PostItsXML FROM PostIts WHERE PostItsId IN ("
                + String.join(",", Collections.nCopies(l.size(), "?")) + ")")) {
            for (int i = 0; i < l.size(); i++) p.setString(i + 1, l.get(i));
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) {
                    List<PostIt> ps = desdeJson(rs.getString(2));
                    if (!ps.isEmpty()) r.put(rs.getString(1), ps);
                }
            }
        }
        return r;
    }

    /**
     * Cambia los PostIts de un registro en una transacción: lee cómo están AHORA (bloqueando el registro: otro usuario pudo
     * pegar otro en el medio), aplica el cambio y graba. Sin PostIts, el registro de PostIts se borra.
     */
    static List<PostIt> cambiar(Connection c, String id, UnaryOperator<List<PostIt>> cambio) throws SQLException {
        {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                List<PostIt> actuales;
                boolean existe;
                try (PreparedStatement p = c.prepareStatement("SELECT PostItsXML FROM PostIts WHERE PostItsId = ? FOR UPDATE")) {
                    p.setString(1, id);
                    try (ResultSet rs = p.executeQuery()) {
                        existe = rs.next();
                        actuales = existe ? desdeJson(rs.getString(1)) : new ArrayList<>();
                    }
                }
                List<PostIt> nuevos = cambio.apply(new ArrayList<>(actuales));
                String sql = nuevos.isEmpty() ? "DELETE FROM PostIts WHERE PostItsId = ?"
                        : existe ? "UPDATE PostIts SET PostItsXML = ? WHERE PostItsId = ?" : "INSERT INTO PostIts (PostItsXML, PostItsId) VALUES (?, ?)";
                if (!nuevos.isEmpty() || existe)
                    try (PreparedStatement p = c.prepareStatement(sql)) {
                        if (nuevos.isEmpty()) p.setString(1, id);
                        else { p.setString(1, aJson(nuevos)); p.setString(2, id); }
                        p.executeUpdate();
                    }
                c.commit();
                return nuevos;
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            } finally { c.setAutoCommit(auto); }
        }
    }

    // ------------------------------------------------------------------ JSON (sin librerías: solo textos y números)

    static String aJson(List<PostIt> ps) {
        StringBuilder sb = new StringBuilder("[");
        for (PostIt p : ps) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":").append(q(p.id())).append(",\"texto\":").append(q(p.texto())).append(",\"color\":").append(q(p.color()))
              .append(",\"x\":").append(p.x()).append(",\"y\":").append(p.y()).append(",\"ancho\":").append(p.ancho()).append(",\"alto\":").append(p.alto())
              .append(",\"vence\":").append(q(p.vence() == null ? "" : p.vence().toString())).append(",\"usuario\":").append(q(p.usuario()))
              .append(",\"fecha\":").append(q(p.fecha())).append('}');
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch); }
            }
        }
        return sb.append('"').toString();
    }

    /** Lee la lista; lo que no es una lista de PostIts (ej. el XML viejo de GeneXus) da una lista vacía. */
    static List<PostIt> desdeJson(String s) {
        List<PostIt> r = new ArrayList<>();
        if (s == null || !s.strip().startsWith("[")) return r;
        try {
            int[] i = {s.indexOf('[') + 1};
            while (true) {
                saltar(s, i);
                if (i[0] >= s.length() || s.charAt(i[0]) == ']') break;
                if (s.charAt(i[0]) == ',') { i[0]++; continue; }
                if (s.charAt(i[0]) != '{') break;
                i[0]++;
                Map<String, String> o = new HashMap<>();
                while (true) {
                    saltar(s, i);
                    char ch = s.charAt(i[0]);
                    if (ch == '}') { i[0]++; break; }
                    if (ch == ',') { i[0]++; continue; }
                    String k = cadena(s, i);
                    saltar(s, i);
                    i[0]++;   // :
                    saltar(s, i);
                    String v;
                    if (s.charAt(i[0]) == '"') v = cadena(s, i);
                    else { int ini = i[0]; while (i[0] < s.length() && ",}] \n\r\t".indexOf(s.charAt(i[0])) < 0) i[0]++; v = s.substring(ini, i[0]); }
                    o.put(k, v);
                }
                LocalDate vence = null;
                try { if (o.get("vence") != null && !o.get("vence").isBlank()) vence = LocalDate.parse(o.get("vence")); } catch (RuntimeException ignorado) { }
                r.add(new PostIt(o.getOrDefault("id", UUID.randomUUID().toString()), o.getOrDefault("texto", ""),
                        COLORES.containsKey(o.get("color")) ? o.get("color") : "amarillo", entero(o.get("x"), 120), entero(o.get("y"), 120),
                        entero(o.get("ancho"), 240), entero(o.get("alto"), 180), vence, o.getOrDefault("usuario", ""), o.getOrDefault("fecha", "")));
            }
        } catch (RuntimeException e) { /* mal armado: lo que se pudo leer */ }
        return r;
    }

    private static void saltar(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }

    private static String cadena(String s, int[] i) {
        StringBuilder sb = new StringBuilder();
        i[0]++;   // "
        while (i[0] < s.length()) {
            char ch = s.charAt(i[0]++);
            if (ch == '"') break;
            if (ch == '\\' && i[0] < s.length()) {
                char e = s.charAt(i[0]++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16)); i[0] += 4; }
                    default -> sb.append(e);
                }
            } else sb.append(ch);
        }
        return sb.toString();
    }

    static int entero(String s, int porDefecto) {
        try { return s == null ? porDefecto : (int) Math.round(Double.parseDouble(s.replace("px", "").trim())); }
        catch (NumberFormatException e) { return porDefecto; }
    }

}
