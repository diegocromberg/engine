package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistMensajesDatos.Mensaje;
import ar.com.datafix.optimist.OptimistMensajesDatos.Resumen;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V72 - La campanita de mensajes (Diego, 24/10/2026), en la barra de arriba entre Ayuda y el usuario / empresa.
 *  - Muestra en chiquito la cantidad de mensajes internos sin leer; se actualiza al cambiar de pantalla y cada 2 minutos.
 *  - Cuando llega uno nuevo (y al entrar, si hay sin leer): un aviso abajo a la derecha con un sonido de advertencia.
 *  - Al tocarla: la ventana de mensajes, del más nuevo al más viejo, por páginas; Sin leer / Todos; marcar leído / no leído;
 *    marcar todos como leídos; "Ver" abre el comprobante (PED, OTR, movimiento de stock...) en modo ver.
 *  - No se puede borrar nada. La Fecha de Visualización es la primera vez que se leyó y no cambia (ver OptimistMensajesDatos).
 */
final class OptimistMensajes {
    private OptimistMensajes() {}

    private static final int POR_PAGINA = 20;
    private static final int CADA_MS = 120_000;
    private static final DateTimeFormatter DMYHM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Los objetos de MensajesCrear que no se llaman como su TRN. */
    private static final Map<String, String> OBJETO_TRN = Map.of("MSTK", "MovStock", "PEDP", "PED");

    private static final String UI_CAMPANA = "optimist.mensajes.campana";
    private static final String UI_POLL = "optimist.mensajes.poll";
    private static final String SES_ULTIMO = "optimist.mensajes.ultimo";

    /** La campanita para la barra (null si no hay usuario o base). verticalMenu: en el menú vertical, centrada. */
    static Component campana(boolean menuVertical) {
        Long usuario = secUserId();
        OptimistDatabase db = OptimistDatabase.instancia();
        UI ui = UI.getCurrent();
        if (usuario == null || db == null || ui == null) return null;

        Span numero = new Span();
        numero.getStyle().set("position", "absolute").set("top", "2px").set("right", "0px").set("min-width", "16px")
                .set("height", "16px").set("padding", "0 4px").set("box-sizing", "border-box").set("border-radius", "8px")
                .set("background", "#d32f2f").set("color", "#fff").set("font-size", "10px").set("font-weight", "700")
                .set("line-height", "16px").set("text-align", "center").set("pointer-events", "none");
        numero.setVisible(false);
        Button b = new Button(VaadinIcon.BELL_O.create());
        b.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        b.getStyle().set("flex-shrink", "0").set("color", "var(--optimist-texto)").set("margin", "0");
        b.getElement().setAttribute("aria-label", "Mensajes");
        Div caja = new Div(b, numero);
        caja.getStyle().set("position", "relative").set("display", "inline-flex").set("flex-shrink", "0");
        if (menuVertical) caja.getStyle().set("align-self", "center");
        b.addClickListener(e -> ventana(usuario, db, numero, b));

        ComponentUtil.setData(ui, UI_CAMPANA, new Object[]{numero, b});
        actualizar(ui, usuario, db, false);
        if (ComponentUtil.getData(ui, UI_POLL) == null) {
            ComponentUtil.setData(ui, UI_POLL, Boolean.TRUE);
            // V66x: la ventanita "Procesando…" cambia el intervalo mientras corre y lo devuelve al terminar
            if (ui.getPollInterval() < 0 || ui.getPollInterval() > CADA_MS) ui.setPollInterval(CADA_MS);
            long[] ultimaVez = {System.currentTimeMillis()};
            ui.addPollListener(ev -> {
                long ahora = System.currentTimeMillis();
                if (ahora - ultimaVez[0] < CADA_MS - 5_000) return;   // no en cada consulta rápida de "Procesando…"
                ultimaVez[0] = ahora;
                actualizar(ui, usuario, db, true);
            });
        }
        return caja;
    }

    /** Pone el número y, si llegó uno nuevo (o es el primer vistazo de la sesión con mensajes sin leer), avisa con sonido. */
    private static void actualizar(UI ui, long usuario, OptimistDatabase db, boolean avisarNuevos) {
        Object[] c = (Object[]) ComponentUtil.getData(ui, UI_CAMPANA);
        if (c == null) return;
        Resumen r;
        try (Connection cn = db.openConnection()) { r = OptimistMensajesDatos.resumen(cn, usuario); }
        catch (SQLException | RuntimeException ex) { return; }   // sin las tablas de mensajes: la campanita queda sin número
        mostrarNumero((Span) c[0], (Button) c[1], r.sinLeer());
        com.vaadin.flow.server.VaadinSession s = com.vaadin.flow.server.VaadinSession.getCurrent();
        Object antes = s == null ? null : s.getAttribute(SES_ULTIMO);
        if (s != null) s.setAttribute(SES_ULTIMO, Math.max(r.ultimoSinLeer(), antes instanceof Long l ? l : 0L));
        if (r.sinLeer() == 0) return;
        if (antes == null) aviso(ui, usuario, db, r.sinLeer() == 1 ? "Tenés 1 mensaje sin leer." : "Tenés " + r.sinLeer() + " mensajes sin leer.");
        else if (avisarNuevos && antes instanceof Long l && r.ultimoSinLeer() > l) aviso(ui, usuario, db, "Llegó un mensaje nuevo.");
    }

    private static void mostrarNumero(Span numero, Button b, int n) {
        numero.setText(n > 99 ? "99+" : String.valueOf(n));
        numero.setVisible(n > 0);
        b.setIcon(n > 0 ? VaadinIcon.BELL.create() : VaadinIcon.BELL_O.create());
        b.getElement().setAttribute("title", n == 0 ? "Mensajes" : n == 1 ? "1 mensaje sin leer" : n + " mensajes sin leer");
    }

    /** El aviso de abajo a la derecha, con el sonido. Tocarlo abre los mensajes. */
    private static void aviso(UI ui, long usuario, OptimistDatabase db, String texto) {
        Notification n = new Notification();
        n.setPosition(Notification.Position.BOTTOM_END);
        n.setDuration(8000);
        n.addThemeVariants(NotificationVariant.LUMO_PRIMARY);
        Button ver = new Button("Ver", e -> {
            n.close();
            Object[] c = (Object[]) ComponentUtil.getData(ui, UI_CAMPANA);
            if (c != null) ventana(usuario, db, (Span) c[0], (Button) c[1]);
        });
        ver.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        ver.getStyle().set("color", "inherit").set("text-decoration", "underline");
        HorizontalLayout h = new HorizontalLayout(VaadinIcon.BELL.create(), new Span(texto), ver);
        h.setAlignItems(FlexComponent.Alignment.CENTER);
        n.add(h);
        n.open();
        // sonido de advertencia (dos tonos), sin archivo; si el navegador no deja reproducir sonido, no pasa nada
        ui.getPage().executeJs("try { const A = window.AudioContext || window.webkitAudioContext; if (!A) return;"
                + " const a = new A(); const t = a.currentTime;"
                + " [[880, 0], [660, 0.18]].forEach(([f, d]) => { const o = a.createOscillator(), g = a.createGain();"
                + "  o.type = 'sine'; o.frequency.value = f; g.gain.setValueAtTime(0.0001, t + d);"
                + "  g.gain.exponentialRampToValueAtTime(0.25, t + d + 0.02); g.gain.exponentialRampToValueAtTime(0.0001, t + d + 0.16);"
                + "  o.connect(g); g.connect(a.destination); o.start(t + d); o.stop(t + d + 0.17); });"
                + " setTimeout(() => a.close(), 800); } catch (e) {}");
    }

    // ------------------------------------------------------------------ la ventana de mensajes

    private static void ventana(long usuario, OptimistDatabase db, Span numero, Button campana) {
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setHeaderTitle("Mensajes");
        d.setWidth("min(900px, 96vw)");
        d.setMaxHeight("90vh");
        Button x = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> d.close());
        x.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        x.getElement().setAttribute("aria-label", "Cerrar");
        x.getElement().setAttribute("title", "Cerrar");
        d.getHeader().add(x);   // arriba a la derecha

        RadioButtonGroup<String> filtro = new RadioButtonGroup<>();
        filtro.setItems("Sin leer", "Todos");
        filtro.setValue("Sin leer");
        Button todos = new Button("Marcar todos como leídos", VaadinIcon.CHECK.create());
        todos.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout arriba = new HorizontalLayout(filtro, todos);
        arriba.setWidthFull();
        arriba.setAlignItems(FlexComponent.Alignment.CENTER);
        arriba.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);

        VerticalLayout lista = new VerticalLayout();
        lista.setPadding(false);
        lista.setSpacing(false);
        lista.setWidthFull();

        Button primera = new Button(VaadinIcon.ANGLE_DOUBLE_LEFT.create());
        Button anterior = new Button(VaadinIcon.ANGLE_LEFT.create());
        Button siguiente = new Button(VaadinIcon.ANGLE_RIGHT.create());
        Button ultima = new Button(VaadinIcon.ANGLE_DOUBLE_RIGHT.create());
        for (Button p : List.of(primera, anterior, siguiente, ultima)) p.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        Span info = new Span();
        info.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        HorizontalLayout pie = new HorizontalLayout(primera, anterior, info, siguiente, ultima);
        pie.setAlignItems(FlexComponent.Alignment.CENTER);
        pie.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        pie.setWidthFull();

        d.add(arriba, lista, pie);
        Button cerrar = new Button("Cerrar", e -> d.close());
        d.getFooter().add(cerrar);

        int[] pag = {0};
        Runnable[] cargar = new Runnable[1];
        cargar[0] = () -> {
            boolean sinLeer = "Sin leer".equals(filtro.getValue());
            lista.removeAll();
            try (Connection cn = db.openConnection()) {
                int total = OptimistMensajesDatos.contar(cn, usuario, sinLeer);
                int paginas = Math.max(1, (total + POR_PAGINA - 1) / POR_PAGINA);
                if (pag[0] >= paginas) pag[0] = paginas - 1;
                List<Mensaje> ms = OptimistMensajesDatos.pagina(cn, usuario, sinLeer, pag[0] * POR_PAGINA, POR_PAGINA);
                if (ms.isEmpty()) {
                    Span vacio = new Span(sinLeer ? "No tenés mensajes sin leer." : "No tenés mensajes.");
                    vacio.getStyle().set("padding", "24px 0").set("color", "var(--lumo-secondary-text-color)").set("align-self", "center");
                    lista.add(vacio);
                }
                for (Mensaje m : ms) lista.add(renglon(m, usuario, db, d, cargar));
                info.setText(total == 0 ? "" : (total == 1 ? "1 mensaje" : String.format(Locale.of("es", "AR"), "%,d mensajes", total))
                        + " · página " + (pag[0] + 1) + " de " + paginas);
                primera.setEnabled(pag[0] > 0); anterior.setEnabled(pag[0] > 0);
                siguiente.setEnabled(pag[0] < paginas - 1); ultima.setEnabled(pag[0] < paginas - 1);
                pie.setVisible(total > POR_PAGINA);
                Resumen r = OptimistMensajesDatos.resumen(cn, usuario);
                mostrarNumero(numero, campana, r.sinLeer());
                todos.setEnabled(r.sinLeer() > 0);
            } catch (SQLException ex) {
                Notification.show("Mensajes: " + ex.getMessage(), 8000, Notification.Position.MIDDLE);
            }
        };
        filtro.addValueChangeListener(e -> { pag[0] = 0; cargar[0].run(); });
        primera.addClickListener(e -> { pag[0] = 0; cargar[0].run(); });
        anterior.addClickListener(e -> { pag[0] = Math.max(0, pag[0] - 1); cargar[0].run(); });
        siguiente.addClickListener(e -> { pag[0]++; cargar[0].run(); });
        ultima.addClickListener(e -> { pag[0] = Integer.MAX_VALUE / 2; cargar[0].run(); });
        todos.addClickListener(e -> {
            try (Connection cn = db.openConnection()) { OptimistMensajesDatos.marcarTodosLeidos(cn, usuario); }
            catch (SQLException ex) { Notification.show("Mensajes: " + ex.getMessage(), 8000, Notification.Position.MIDDLE); }
            cargar[0].run();
        });
        cargar[0].run();
        d.open();
    }

    /** Un mensaje: punto y negrita si no está leído; fecha, tipo, texto; Ver (si tiene comprobante) y leído / no leído. */
    private static Component renglon(Mensaje m, long usuario, OptimistDatabase db, Dialog d, Runnable[] cargar) {
        Span punto = new Span();
        punto.getStyle().set("width", "8px").set("height", "8px").set("border-radius", "50%").set("flex-shrink", "0")
                .set("margin-top", "6px").set("background", m.leido() ? "transparent" : "var(--lumo-primary-color)");
        Span cuando = new Span(m.enviado() == null ? "" : m.enviado().format(DMYHM));
        cuando.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)").set("white-space", "nowrap");
        Span tipo = new Span(m.tipo().isBlank() ? "Mensaje" : m.tipo().strip());
        tipo.getStyle().set("font-weight", m.leido() ? "500" : "700");
        HorizontalLayout titulo = new HorizontalLayout(tipo, cuando);
        titulo.setAlignItems(FlexComponent.Alignment.BASELINE);
        titulo.setSpacing(true);
        Div texto = new Div();
        texto.setText(m.texto().replace("\r", "").strip());
        texto.getStyle().set("white-space", "pre-wrap").set("font-weight", m.leido() ? "400" : "600")
                .set("font-size", "var(--lumo-font-size-s)").set("overflow-wrap", "anywhere");
        Span visto = new Span(m.visto() == null ? "" : "Visto por primera vez: " + m.visto().format(DMYHM));
        visto.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        visto.setVisible(m.visto() != null);
        VerticalLayout cuerpo = new VerticalLayout(titulo, texto, visto);
        cuerpo.setPadding(false);
        cuerpo.setSpacing(false);
        cuerpo.getStyle().set("flex", "1").set("min-width", "0").set("gap", "2px");

        HorizontalLayout acciones = new HorizontalLayout();
        acciones.setSpacing(false);
        acciones.getStyle().set("gap", "4px").set("flex-shrink", "0");
        String ruta = rutaObjeto(m);
        if (ruta != null) {
            Button ver = new Button("Ver", VaadinIcon.EXTERNAL_LINK.create(), e -> {
                marcar(m, usuario, db, true);
                d.close();
                UI.getCurrent().navigate(ruta, com.vaadin.flow.router.QueryParameters.of("ver", String.valueOf(m.objetoId())));
            });
            ver.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            ver.getElement().setAttribute("title", "Abrir el comprobante");
            acciones.add(ver);
        }
        Button leido = new Button(m.leido() ? "No leído" : "Leído", m.leido() ? VaadinIcon.ENVELOPE.create() : VaadinIcon.ENVELOPE_OPEN.create(), e -> {
            marcar(m, usuario, db, !m.leido());
            cargar[0].run();
        });
        leido.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        leido.getElement().setAttribute("title", m.leido() ? "Marcar como no leído" : "Marcar como leído");
        acciones.add(leido);

        HorizontalLayout r = new HorizontalLayout(punto, cuerpo, acciones);
        r.setWidthFull();
        r.setAlignItems(FlexComponent.Alignment.START);
        r.getStyle().set("padding", "8px 4px").set("border-bottom", "1px solid var(--lumo-contrast-10pct)").set("box-sizing", "border-box")
                .set("background", m.leido() ? "transparent" : "var(--lumo-primary-color-10pct)");
        return r;
    }

    private static void marcar(Mensaje m, long usuario, OptimistDatabase db, boolean leido) {
        try (Connection cn = db.openConnection()) {
            if (leido) OptimistMensajesDatos.marcarLeido(cn, usuario, m.envioId(), m.usuarioId());
            else OptimistMensajesDatos.marcarNoLeido(cn, usuario, m.envioId(), m.usuarioId());
        } catch (SQLException ex) {
            Notification.show("Mensajes: " + ex.getMessage(), 8000, Notification.Position.MIDDLE);
        }
    }

    /** trn/<ruta> (se abre con ?ver=<id>) si el objeto del mensaje es una TRN de clave simple; si no, null (sin botón Ver). */
    private static String rutaObjeto(Mensaje m) {
        if (m.objeto().isBlank() || m.objetoId() == 0) return null;
        String nombre = OBJETO_TRN.getOrDefault(m.objeto().toUpperCase(Locale.ROOT), m.objeto());
        try {
            for (OptimistTrnDefinicion.Trn t : OptimistTrnCatalogo.todas())
                if (t.nombre().equalsIgnoreCase(nombre) && t.cabecera().claves().size() == 1)
                    return "trn/" + t.ruta();
        } catch (RuntimeException ex) { return null; }
        return null;
    }

    private static Long secUserId() {
        OptimistContexto ctx = OptimistContexto.actual();
        Object v = ctx == null ? null : ctx.valor("SecUserId");
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? null : Long.valueOf(String.valueOf(v).trim()); } catch (NumberFormatException e) { return null; }
    }
}
