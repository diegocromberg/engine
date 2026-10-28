package ar.com.datafix.optimist;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * V66l - Las clases del tema de GeneXus (resources/clases_gx.yaml, lo genera herramientas/gx2clases.py desde la KB).
 * En GeneXus muchos colores salen de una clase: escrita en el código (&AcumSaldo.Class = "AttributeTitleRed") o guardada
 * en un parámetro (ParamGlb 177 ClassTitleClienteDebe = "AttributeBoldRed", distinto en cada instalación). css() da el
 * estilo de la celda con el color, fondo, negrita, itálica y subrayado de la clase; la letra y el tamaño no (manda la del
 * usuario). Una clase que no está en el tema no pinta nada.
 */
public final class OptimistClasesGx {
    private OptimistClasesGx() {}

    private static volatile Map<String, String> clases;

    /** El estilo CSS de una clase de GeneXus ("color:#FF0800;font-weight:700;"), o null si no la conoce. */
    public static String css(String clase) {
        if (clase == null || clase.isBlank()) return null;
        return cargar().get(clase.trim().toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> cargar() {
        Map<String, String> c = clases;
        if (c != null) return c;
        Map<String, String> r = new HashMap<>();
        try (InputStream in = OptimistClasesGx.class.getClassLoader().getResourceAsStream("clases_gx.yaml")) {
            if (in != null) {
                Object y = new Yaml().load(in);
                if (y instanceof Map<?, ?> m)
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (!(e.getValue() instanceof Map<?, ?> p)) continue;
                        StringBuilder sb = new StringBuilder();
                        if (p.get("color") != null) sb.append("color:").append(limpio(p.get("color"))).append(';');
                        if (p.get("fondo") != null) sb.append("background-color:").append(limpio(p.get("fondo"))).append(';');
                        if (Boolean.TRUE.equals(p.get("negrita"))) sb.append("font-weight:700;");
                        if (Boolean.TRUE.equals(p.get("italica"))) sb.append("font-style:italic;");
                        if (Boolean.TRUE.equals(p.get("subrayado"))) sb.append("text-decoration:underline;");
                        if (sb.length() > 0) r.put(String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT), sb.toString());
                    }
            }
        } catch (Exception ex) {
            System.out.println("[clases_gx.yaml] no se pudo leer: " + ex.getMessage());
        }
        clases = r;
        return r;
    }

    /** Un color del tema, sin nada que pueda romper el estilo (comillas, punto y coma, llaves). */
    private static String limpio(Object v) {
        return String.valueOf(v).trim().replaceAll("[\"';{}<>]", "");
    }
}
