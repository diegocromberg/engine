package ar.com.datafix.optimist;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.router.QueryParameters;

import java.util.*;

/**
 * V84c: "de la pantalla A a la B, al cerrar la B se vuelve a la A" también con las ventanas (Diego, 26/10/2026). Desde una
 * ventana (la lupa de los comprobantes de una estadística, los movimientos de stock de un artículo...) se abre un registro
 * de una TRN o una consulta, que ocupan la pantalla entera: al cerrarlo, se vuelve a la pantalla de abajo CON las ventanas
 * que estaban abiertas, en el mismo orden (antes volvía a la pantalla sin las ventanas).
 * <ul>
 *   <li>Cada ventana que puede abrir otra pantalla se anota con {@link #registrar(Dialog, Runnable)}: cómo volver a
 *       abrirla.</li>
 *   <li>Para ir a otra pantalla desde una ventana se usa {@link #navegar(String, QueryParameters)}: se recuerdan las ventanas
 *       abiertas y la dirección de la pantalla de abajo.</li>
 *   <li>Al volver a esa dirección (Cerrar / Volver / Atrás del navegador), se vuelven a abrir.</li>
 * </ul>
 */
final class OptimistVentanas {
    private OptimistVentanas() {}

    private record Abierta(Dialog dialogo, Runnable reabrir) {}

    /** Lo de cada pestaña del navegador (UI): las ventanas abiertas y, por dirección, las que hay que volver a abrir. */
    private static final class Estado {
        final List<Abierta> abiertas = new ArrayList<>();
        final Map<String, List<Runnable>> aReabrir = new HashMap<>();
        boolean navegando;
        boolean escuchando;
    }

    private static Estado estado(UI ui) {
        Estado e = ComponentUtil.getData(ui, Estado.class);
        if (e == null) { e = new Estado(); ComponentUtil.setData(ui, Estado.class, e); }
        if (!e.escuchando) {
            e.escuchando = true;
            Estado es = e;
            ui.addAfterNavigationListener(ev -> {
                List<Runnable> rs = es.aReabrir.remove(ev.getLocation().getPathWithQueryParameters());
                if (rs == null || rs.isEmpty()) return;
                // después de que la pantalla quedó armada, las ventanas en el orden en que estaban (la de arriba, última)
                ui.beforeClientResponse(ui, ctx -> {
                    for (Runnable r : rs) {
                        try { r.run(); } catch (RuntimeException ex) { System.out.println("[OPTIMIST] No se pudo volver a abrir una ventana: " + ex.getMessage()); }
                    }
                });
            });
        }
        return e;
    }

    /**
     * La ventana (abierta o por abrirse) y cómo volver a abrirla (con los mismos datos). Al cerrarla con Cerrar o la X, se
     * olvida; si se vuelve a abrir, vuelve a contar.
     */
    static void registrar(Dialog d, Runnable reabrir) {
        UI ui = UI.getCurrent();
        if (ui == null || d == null || reabrir == null) return;
        Estado e = estado(ui);
        Abierta a = new Abierta(d, reabrir);
        if (d.isOpened()) e.abiertas.add(a);
        // V84e: la misma ventana se puede volver a abrir (ej. un panel en ventana): al abrirse vuelve a la lista
        d.addOpenedChangeListener(ev -> {
            if (ev.isOpened()) { if (!e.abiertas.contains(a)) e.abiertas.add(a); }
            else if (!e.navegando) e.abiertas.remove(a);
        });
        d.addDetachListener(ev -> { if (!e.navegando) e.abiertas.remove(a); });
    }

    /** Ir a otra pantalla (una TRN, una consulta) desde una ventana: al volver, las ventanas abiertas se vuelven a abrir. */
    /**
     * V84 / E3: el registro de una TRN en modo ver (las lupas del stock del ERP, los vínculos de los web panels). Al cerrarlo
     * se vuelve a la pantalla de abajo con las ventanas que estaban abiertas.
     */
    static void abrirRegistro(String trn, Object id) {
        if (id == null || OptimistFormula.num(id).signum() == 0) return;
        OptimistTrnDefinicion.Trn t = OptimistPanelView.trn(trn);
        if (t == null) {
            com.vaadin.flow.component.notification.Notification.show("No existe la TRN " + trn, 5000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
            return;
        }
        navegar("trn/" + t.ruta(), QueryParameters.of("ver", OptimistFormula.num(id).stripTrailingZeros().toPlainString()));
    }

    static void navegar(String ruta, QueryParameters qp) {
        UI ui = UI.getCurrent();
        if (ui == null) return;
        Estado e = estado(ui);
        List<Runnable> rs = new ArrayList<>();
        for (Abierta a : e.abiertas) if (a.dialogo().isOpened()) rs.add(a.reabrir());
        if (!rs.isEmpty()) {
            String desde = ui.getInternals().getActiveViewLocation().getPathWithQueryParameters();
            e.aReabrir.put(desde, rs);
        }
        e.navegando = true;
        try {
            for (Abierta a : new ArrayList<>(e.abiertas)) if (a.dialogo().isOpened()) a.dialogo().close();
            e.abiertas.clear();
            if (qp == null) ui.navigate(ruta); else ui.navigate(ruta, qp);
        } finally { e.navegando = false; }
    }
}
