package ar.com.datafix.optimist;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.icon.VaadinIcon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V48 - Ayuda en pantalla: el botón "?" de una pantalla abre su ayuda (src/main/resources/ayuda/<nombre>.md).
 * Lista de una TRN: "PRS-lista"; formulario: "PRS". Sin archivo, no hay botón. En desarrollo se lee de src/main/resources
 * (un cambio en el .md se ve al volver a abrirla). El Markdown se pasa a HTML acá (títulos, párrafos, listas, tablas,
 * citas, negrita, itálica, código), sin librerías.
 */
public final class OptimistAyuda {
    private OptimistAyuda() {}

    /** El texto de la ayuda, o null si esa pantalla no tiene (el nombre del archivo sin importar mayúsculas). */
    static String texto(String nombre) {
        if (nombre == null || nombre.isBlank()) return null;
        try (InputStream in = OptimistRecursos.abrir("ayuda/" + nombre + ".md")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        try {
            for (org.springframework.core.io.Resource r : OptimistRecursos.listar("ayuda", ".md")) {
                String f = r.getFilename();
                if (f != null && f.equalsIgnoreCase(nombre + ".md"))
                    try (InputStream in = r.getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            }
        } catch (IOException e) { /* sin ayuda */ }
        return null;
    }

    /**
     * V48: la ayuda de la pantalla en la que está el usuario, según su ruta: trn/prs -> "PRS-lista" (la lista de la TRN),
     * wbp/x -> "x" (el web panel), cns/x -> "x" (la consulta, V52), "" -> "inicio".
     */
    static String deLaPantalla() {
        String ruta = OptimistShell.rutaPantalla();
        if (ruta == null || ruta.isBlank()) return "inicio";
        if (ruta.startsWith("trn/")) {
            String r = ruta.substring(4);
            for (OptimistTrnDefinicion.Trn t : OptimistTrnCatalogo.todas())
                if (t.ruta().equalsIgnoreCase(r)) return t.nombre() + "-lista";
            return r + "-lista";
        }
        if (ruta.startsWith("wbp/")) return ruta.substring(4);
        if (ruta.startsWith("cns/")) return ruta.substring(4);   // V52: la consulta (ayuda/ExtraWWArticulosCnsStock.md)
        return ruta.replace('/', '-');
    }

    /** V48: el botón "Ayuda" de la barra de arriba (en todas las pantallas): abre la ayuda de la pantalla actual. */
    public static Button botonBarra() {
        Button b = new Button("Ayuda", VaadinIcon.QUESTION_CIRCLE_O.create(), e -> {
            String n = deLaPantalla();
            if (texto(n) == null)
                com.vaadin.flow.component.notification.Notification.show("Esta pantalla todavía no tiene ayuda.", 4000,
                        com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
            else abrir(n);
        });
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        b.getStyle().set("flex-shrink", "0").set("color", "var(--optimist-texto)");
        b.getElement().setAttribute("title", "Ayuda de esta pantalla");
        return b;
    }

    public static boolean hay(String nombre) { return texto(nombre) != null; }

    /** El botón "?" que abre la ayuda; null si la pantalla no tiene. */
    public static Button boton(String nombre) {
        if (!hay(nombre)) return null;
        Button b = new Button("Ayuda", VaadinIcon.QUESTION_CIRCLE_O.create(), e -> abrir(nombre));
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        b.getElement().setAttribute("title", "Ayuda de esta pantalla");
        b.getElement().setAttribute("aria-label", "Ayuda");
        return b;
    }

    public static void abrir(String nombre) {
        String md = texto(nombre);
        if (md == null) return;
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setWidth("min(900px,96vw)");
        d.setMaxHeight("90vh");
        d.setHeaderTitle("Ayuda");
        Html h = new Html("<div class=\"optimist-ayuda\" style=\"line-height:1.5;font-size:var(--lumo-font-size-s)\">" + html(md) + "</div>");
        d.add(h);
        Button cerrar = new Button("Cerrar", e -> d.close());
        d.getFooter().add(cerrar);
        d.open();
    }

    // ------------------------------------------------------------------ Markdown -> HTML

    /** Markdown sencillo a HTML: #..#### títulos, párrafos, - y 1. listas, | tablas |, > citas, **negrita**, *itálica*, `código`, ``` bloques. */
    static String html(String md) {
        StringBuilder r = new StringBuilder();
        String[] ls = md.replace("\r", "").split("\n", -1);
        int i = 0;
        while (i < ls.length) {
            String l = ls[i];
            String t = l.trim();
            if (t.isEmpty()) { i++; continue; }
            if (t.startsWith("```")) {
                StringBuilder c = new StringBuilder();
                i++;
                while (i < ls.length && !ls[i].trim().startsWith("```")) { c.append(esc(ls[i])).append('\n'); i++; }
                i++;
                r.append("<pre style=\"background:var(--lumo-contrast-5pct);padding:8px;border-radius:6px;overflow-x:auto\"><code>").append(c).append("</code></pre>");
                continue;
            }
            Matcher h = Pattern.compile("^(#{1,4})\\s+(.*)$").matcher(t);
            if (h.matches()) {
                int n = h.group(1).length();
                r.append("<h").append(n + 1).append(" style=\"margin:").append(n == 1 ? "0 0 8px" : "16px 0 6px").append("\">")
                        .append(enLinea(h.group(2))).append("</h").append(n + 1).append(">");
                i++;
                continue;
            }
            if (t.startsWith("|")) {
                List<String[]> filas = new ArrayList<>();
                while (i < ls.length && ls[i].trim().startsWith("|")) {
                    String f = ls[i].trim();
                    if (!f.matches("^\\|[\\s:|-]+\\|?$")) filas.add(celdas(f));
                    i++;
                }
                r.append("<table style=\"border-collapse:collapse;margin:6px 0 10px;width:100%\">");
                for (int k = 0; k < filas.size(); k++) {
                    r.append("<tr>");
                    for (String c : filas.get(k)) {
                        String tag = k == 0 ? "th" : "td";
                        r.append('<').append(tag).append(" style=\"border:1px solid var(--lumo-contrast-20pct);padding:4px 8px;text-align:left;vertical-align:top\">")
                                .append(enLinea(c)).append("</").append(tag).append('>');
                    }
                    r.append("</tr>");
                }
                r.append("</table>");
                continue;
            }
            if (t.startsWith(">")) {
                StringBuilder c = new StringBuilder();
                while (i < ls.length && ls[i].trim().startsWith(">")) { c.append(ls[i].trim().substring(1).trim()).append(' '); i++; }
                r.append("<blockquote style=\"margin:8px 0;padding:6px 12px;border-left:4px solid var(--lumo-primary-color);background:var(--lumo-primary-color-10pct)\">")
                        .append(enLinea(c.toString().trim())).append("</blockquote>");
                continue;
            }
            boolean numerada = t.matches("^\\d+\\.\\s+.*");
            if (t.matches("^[-*]\\s+.*") || numerada) {
                String tag = numerada ? "ol" : "ul";
                r.append('<').append(tag).append(numerada ? " start=\"" + t.substring(0, t.indexOf('.')) + "\"" : "")
                        .append(" style=\"margin:4px 0 8px;padding-left:22px\">");
                int base = l.length() - l.stripLeading().length();
                while (i < ls.length) {
                    String x = ls[i];
                    String xt = x.trim();
                    int sangria = x.length() - x.stripLeading().length();
                    if (xt.isEmpty()) {   // un renglón vacío termina la lista, salvo que siga otro ítem
                        if (i + 1 < ls.length && ls[i + 1].trim().matches("^([-*]|\\d+\\.)\\s+.*")) { i++; continue; }
                        break;
                    }
                    if (sangria > base && xt.matches("^([-*]|\\d+\\.)\\s+.*")) {
                        // sublista
                        boolean subNum = xt.matches("^\\d+\\..*");
                        r.append(subNum ? "<ol" : "<ul").append(" style=\"margin:2px 0;padding-left:20px\">");
                        while (i < ls.length && (ls[i].length() - ls[i].stripLeading().length()) > base
                                && ls[i].trim().matches("^([-*]|\\d+\\.)\\s+.*")) {
                            r.append("<li>").append(enLinea(ls[i].trim().replaceFirst("^([-*]|\\d+\\.)\\s+", ""))).append("</li>");
                            i++;
                        }
                        r.append(subNum ? "</ol>" : "</ul>");
                        continue;
                    }
                    if (!xt.matches("^([-*]|\\d+\\.)\\s+.*")) {
                        if (sangria > base) { r.append("<div>").append(enLinea(xt)).append("</div>"); i++; continue; }   // continuación
                        break;
                    }
                    r.append("<li>").append(enLinea(xt.replaceFirst("^([-*]|\\d+\\.)\\s+", ""))).append("</li>");
                    i++;
                }
                r.append("</").append(tag).append('>');
                continue;
            }
            // párrafo: hasta un renglón vacío o el comienzo de otro bloque
            StringBuilder p = new StringBuilder(t);
            i++;
            while (i < ls.length) {
                String x = ls[i].trim();
                if (x.isEmpty() || x.startsWith("#") || x.startsWith("|") || x.startsWith(">") || x.startsWith("```")
                        || x.matches("^([-*]|\\d+\\.)\\s+.*")) break;
                p.append(' ').append(x);
                i++;
            }
            r.append("<p style=\"margin:6px 0\">").append(enLinea(p.toString())).append("</p>");
        }
        return r.toString();
    }

    private static String[] celdas(String fila) {
        String f = fila.trim();
        if (f.startsWith("|")) f = f.substring(1);
        if (f.endsWith("|")) f = f.substring(0, f.length() - 1);
        String[] c = f.split("\\|", -1);
        for (int k = 0; k < c.length; k++) c[k] = c[k].trim();
        return c;
    }

    /** Dentro de un renglón: `código`, **negrita**, *itálica* (la negrita puede envolver código). */
    static String enLinea(String s) {
        List<String> codigos = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            codigos.add(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement("\u0001" + (codigos.size() - 1) + "\u0002"));
        }
        m.appendTail(sb);
        String x = esc(sb.toString());
        x = x.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        x = x.replaceAll("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])", "<i>$1</i>");
        for (int k = 0; k < codigos.size(); k++)
            x = x.replace("\u0001" + k + "\u0002",
                    "<code style=\"background:var(--lumo-contrast-5pct);padding:0 3px;border-radius:3px\">" + esc(codigos.get(k)) + "</code>");
        return x;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
