package ar.com.datafix.optimist;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * E6c - La ventanita "Procesando…" con el % de avance (la de los web panels, V66x) para cualquier trabajo largo que corra en
 * segundo plano: el lote de una consulta (botones con tildes), por ejemplo. El trabajo corre en un hilo con el contexto del
 * usuario y la base del cliente; si tarda más de medio segundo aparece la ventanita, que muestra lo que el trabajo (o los
 * procedimientos que llama, con MostrarProgreso / TotalProgreso / PorcentajeProgreso) va informando. Al terminar, el
 * resultado vuelve a la pantalla en el hilo de la interfaz (alTerminar); un error también (como mensaje).
 */
final class OptimistProgreso {
    private OptimistProgreso() {}

    /** Lo que el trabajo puede ir informando por su cuenta (además de lo que digan los procedimientos). */
    interface Avance {
        void texto(String t);
        void avance(int hechos, int total);
    }

    /**
     * Corre el trabajo en segundo plano con la ventanita. bloquear: deja la pantalla deshabilitada mientras dura (true) y la
     * vuelve a habilitar (false). contarAvisos: cada MostrarProgreso de un procedimiento cuenta uno para el % (como en los web
     * panels); con false, el % lo lleva el trabajo con avance(). Sin UI (o si falla el hilo), corre en el acto.
     */
    static <T> void correr(String nombre, Consumer<Boolean> bloquear, boolean contarAvisos,
                           java.util.function.Function<Avance, T> trabajo, Consumer<T> alTerminar, Consumer<Throwable> alFallar) {
        UI ui = UI.getCurrent();
        if (ui == null) {
            Avance nada = new Avance() { public void texto(String t) { } public void avance(int h, int n) { } };
            try { alTerminar.accept(trabajo.apply(nada)); } catch (RuntimeException ex) { alFallar.accept(ex); }
            return;
        }
        OptimistContexto ctx = OptimistContexto.actual();
        OptimistDatabase.Cliente cliente = OptimistDatabase.clienteActual();
        AtomicReference<String> texto = new AtomicReference<>();
        AtomicBoolean listo = new AtomicBoolean(false);
        AtomicInteger letras = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(0);
        AtomicInteger hechos = new AtomicInteger(0);
        AtomicReference<Double> porcentaje = new AtomicReference<>();
        long inicio = System.currentTimeMillis();
        Dialog espera = new Dialog();
        OptimistVisuals.estilizarDialogo(espera);
        espera.setModal(false);   // con una ventana modal Vaadin deja de atender el "poll"
        espera.setCloseOnEsc(false);
        espera.setCloseOnOutsideClick(false);
        espera.setHeaderTitle("Procesando…");
        OptimistVisuals.atrasNoCierra(espera);
        Span detalle = new Span("Procesando…");
        detalle.getStyle().set("white-space", "nowrap").set("display", "block").set("overflow", "hidden").set("text-overflow", "ellipsis");
        espera.setWidth("min(460px, 92vw)");
        com.vaadin.flow.component.progressbar.ProgressBar barra = new com.vaadin.flow.component.progressbar.ProgressBar();
        barra.setIndeterminate(true);
        Span avance = new Span();
        avance.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        avance.setVisible(false);
        VerticalLayout caja = new VerticalLayout(detalle, barra, avance);
        caja.setPadding(false);
        caja.setSpacing(false);
        caja.getStyle().set("gap", "var(--lumo-space-xs)");
        caja.setWidthFull();
        espera.add(caja);
        bloquear.accept(true);
        int pollAntes = ui.getPollInterval();
        ui.setPollInterval(300);
        com.vaadin.flow.shared.Registration reg = ui.addPollListener(ev -> {
            if (listo.get() || System.currentTimeMillis() - inicio < 500) return;
            int n = letras.getAndSet(0);
            if (n > 0) espera.setWidth("min(calc(" + n + "ch + 3em), 92vw)");
            if (!espera.isOpened()) espera.open();
            String t = texto.get();
            detalle.setText(t == null || t.isBlank() ? "Procesando…" : t);
            Double pc = porcentaje.get();
            int tot = total.get();
            String txt = null;
            if (pc != null) {
                pc = Math.max(0, Math.min(100, pc));
                txt = "Avance: " + (int) Math.floor(pc) + " %";
            } else if (tot > 0) {
                int h = Math.min(hechos.get(), tot);
                pc = h * 100.0 / tot;
                java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("es-AR"));
                txt = "Avance: " + (int) Math.floor(pc) + " % (" + nf.format(h) + " de " + nf.format(tot) + ")";
            }
            if (txt != null) {
                barra.setIndeterminate(false);
                barra.setValue(pc / 100.0);
                avance.setText(txt);
                avance.setVisible(true);
            }
        });
        Avance av = new Avance() {
            public void texto(String t) { texto.set(t); }
            public void avance(int h, int n) { total.set(n); hechos.set(h); porcentaje.set(null); }
        };
        Thread hilo = new Thread(() -> {
            T r = null;
            Throwable falla = null;
            try {
                r = OptimistContexto.conContexto(ctx, () -> OptimistDatabase.conCliente(cliente, (Callable<T>) () -> {
                    OptimistProcedimiento.PROGRESO.set(t -> { texto.set(t); if (contarAvisos) hechos.incrementAndGet(); });
                    OptimistProcedimiento.PROGRESO_TOTAL.set(n -> { hechos.set(0); porcentaje.set(null); total.set(n); });
                    OptimistProcedimiento.PROGRESO_PORCENTAJE.set(porcentaje::set);
                    OptimistProcedimiento.PROGRESO_ANCHO.set(letras::set);
                    try { return trabajo.apply(av); }
                    finally {
                        OptimistProcedimiento.PROGRESO.remove();
                        OptimistProcedimiento.PROGRESO_ANCHO.remove();
                        OptimistProcedimiento.PROGRESO_TOTAL.remove();
                        OptimistProcedimiento.PROGRESO_PORCENTAJE.remove();
                        OptimistProcedimiento.PROGRESO_MANUAL.remove();
                    }
                }));
            } catch (Throwable ex) { falla = ex; }
            T fin = r;
            Throwable f = falla;
            try {
                ui.access(() -> {
                    listo.set(true);
                    reg.remove();
                    ui.setPollInterval(pollAntes);
                    espera.close();
                    bloquear.accept(false);
                    if (f != null) alFallar.accept(f); else alTerminar.accept(fin);
                });
            } catch (com.vaadin.flow.component.UIDetachedException ignorado) { /* cerraron la pestaña */ }
        }, "optimist-" + nombre);
        hilo.setDaemon(true);
        hilo.start();
    }
}
