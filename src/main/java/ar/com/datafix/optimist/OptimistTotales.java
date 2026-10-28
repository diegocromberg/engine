package ar.com.datafix.optimist;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Span;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * V84i: los totales al pie de las listas (Diego, 28/10/2026: un cliente no quiere que los vendedores vean los totales).
 * <ul>
 *   <li>Solo los usuarios autorizados en el optimist.cfg del cliente: {@code totales.usuarios=diego,juan} (sin distinguir
 *       mayúsculas; {@code *} = todos). Sin la línea, nadie.</li>
 *   <li>Por defecto, apagados en todas las listas (WorkWith, consultas y resultados de los web panels): ni se muestran ni se
 *       calculan (la suma de lo filtrado es la parte cara de la cantidad).</li>
 *   <li>El usuario autorizado los prende con el botón Σ de la lista (entre Apariencia y PostIt) y los apaga con el mismo
 *       botón (Σ tachada). Queda recordado por usuario y por lista.</li>
 * </ul>
 */
final class OptimistTotales {
    private OptimistTotales() {}

    /** El usuario de la sesión puede ver los totales (totales.usuarios del optimist.cfg de su cliente). */
    static boolean autorizado() {
        String u = OptimistShell.usuario();
        OptimistDatabase d = OptimistDatabase.instancia();
        if (u == null || d == null) return false;
        String lista = d.propiedadAlDia("totales.usuarios", "");   // al día: se puede cambiar sin reiniciar
        if (lista == null) return false;
        for (String x : lista.split("[,;]")) {
            String t = x.trim();
            if (t.equals("*") || t.equalsIgnoreCase(u.trim())) return true;
        }
        return false;
    }

    /** Si el usuario los tenía prendidos en esta lista (clave de sus preferencias). */
    static boolean prendidos(String clave) {
        String u = OptimistShell.usuario();
        if (u == null || clave == null) return false;
        return "true".equalsIgnoreCase(new OptimistUserConfig().cargar(u).getProperty(clave, "false").trim());
    }

    static void recordar(String clave, boolean prendidos) {
        String u = OptimistShell.usuario();
        if (u == null || clave == null) return;
        new OptimistUserConfig().guardarClave(u, clave, String.valueOf(prendidos));
    }

    /** El botón Σ: muestra Σ con los totales apagados (tocarlo los prende) y Σ tachada con los totales prendidos. */
    static Button boton(Consumer<Boolean> alCambiar) {
        Button b = new Button();
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        b.addClickListener(e -> {
            boolean ahora = !Boolean.TRUE.equals(b.getElement().getProperty("optimistTotales", false));
            pintar(b, ahora);
            alCambiar.accept(ahora);
        });
        pintar(b, false);
        return b;
    }

    static void pintar(Button b, boolean prendidos) {
        b.getElement().setProperty("optimistTotales", prendidos);
        Span s = new Span("Σ");
        s.getStyle().set("font-size", "1.25em").set("font-weight", "700").set("line-height", "1");
        if (prendidos) s.getStyle().set("text-decoration", "line-through").set("text-decoration-thickness", "2px");
        b.setIcon(s);
        b.setText(null);
        b.getElement().setAttribute("title", prendidos ? "Ocultar los totales" : "Mostrar los totales");
        b.getElement().setAttribute("aria-label", prendidos ? "Ocultar los totales" : "Mostrar los totales");
    }

    /** Saca un renglón del pie de una grilla (Grid.removeFooterRow); si la versión de Vaadin no lo tiene, lo deja vacío. */
    static void sacarPie(com.vaadin.flow.component.grid.Grid<?> g, com.vaadin.flow.component.grid.FooterRow pie) {
        if (g == null || pie == null) return;
        try {
            g.getClass().getMethod("removeFooterRow", com.vaadin.flow.component.grid.FooterRow.class).invoke(g, pie);
            return;
        } catch (Exception noEsta) { /* sigue */ }
        for (var c : g.getColumns()) { try { pie.getCell(c).setText(""); } catch (RuntimeException ignorado) { } }
    }

    /** La clave de las preferencias de una lista (ww.&lt;id&gt;.totales, wbp.&lt;panel&gt;.totales). */
    static String clave(String prefijo, String id) {
        return prefijo + "." + (id == null ? "" : id).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_") + ".totales";
    }
}
