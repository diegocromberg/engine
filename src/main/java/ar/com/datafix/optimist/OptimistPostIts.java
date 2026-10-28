package ar.com.datafix.optimist;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import ar.com.datafix.optimist.OptimistPostItsDatos.PostIt;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * V69 - Los PostIts (el PostIt de GeneXus, que dejó de andar): notas de colores pegadas a UN registro de una TRN ("Ojo que el
 * cheque vence mañana", "El cliente lo quiere mañana"). Se ven sobre el formulario, en el lugar donde se dejaron.
 * - Se guardan en la tabla PostIts de GeneXus: un registro por registro de la TRN (PostItsId = "TRN|clave", hasta 40 letras) y en
 *   PostItsXML la lista de PostIts (en JSON): texto, color, posición y tamaño en pantalla, vencimiento, usuario y fecha.
 * - Los ven todos; solo el que lo pegó le cambia el texto, el color o el vencimiento y lo saca. Cualquiera lo mueve o lo agranda
 *   (Diego, 23/10/2026: el lugar queda para todos).
 * - Con vencimiento: desde ese día, borde rojo y "Vence hoy" / "Vencido".
 * - En el WorkWith, un puntito en la fila de los registros que tienen PostIt (con el texto al pasar el mouse).
 */
public final class OptimistPostIts {
    private OptimistPostIts() {}

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DMYHM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    static final Map<String, String> COLORES = OptimistPostItsDatos.COLORES;
    /** V82i: el tamaño de un PostIt nuevo (antes 240 x 180: muy chico, Diego 25/10/2026). */
    static final int ANCHO = 380, ALTO = 320;


    static List<PostIt> cambiar(OptimistDatabase db, String id, UnaryOperator<List<PostIt>> cambio) throws SQLException {
        try (Connection c = db.openConnection()) { return OptimistPostItsDatos.cambiar(c, id, cambio); }
    }

    static int entero(String s, int porDefecto) { return OptimistPostItsDatos.entero(s, porDefecto); }

    // ------------------------------------------------------------------ en el formulario de la TRN

    /**
     * Pone el botón "PostIt" en el pie del formulario de un registro ya grabado y, al abrirse el formulario, sus PostIts
     * en el lugar donde quedaron. Al cerrar el formulario se cierran.
     */
    public static void enFormulario(OptimistDatabase db, Dialog formulario, String trn, String clave) {
        if (clave == null || clave.isBlank() || !OptimistDatabase.conPostIts()) return;   // V83: sistema sin PostIts
        String id = OptimistPostItsDatos.idRegistro(trn, clave);
        List<Dialog> abiertos = new ArrayList<>();
        Button pegar = new Button(VaadinIcon.NOTEBOOK.create(), e -> {
            String usuario = usuario();
            int n = abiertos.size();
            PostIt p = new PostIt(UUID.randomUUID().toString(), "", "amarillo", 160 + 24 * n, 140 + 24 * n, ANCHO, ALTO, null,
                    usuario, LocalDateTime.now().format(DMYHM));
            try {
                cambiar(db, id, l -> { l.add(p); return l; });
                abiertos.add(ventana(db, id, p, abiertos, true));
            } catch (SQLException ex) { error(ex); }
        });
        pegar.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        pegar.getElement().setAttribute("title", "Pegar un PostIt en este registro");
        pegar.setText("PostIt");
        OptimistVisuals.alLadoDeLosBotones(formulario, pegar);   // V82i: a la derecha de los botones (como en los web panels)
        formulario.addOpenedChangeListener(ev -> {
            if (ev.isOpened()) {
                try (Connection c = db.openConnection()) {
                    for (PostIt p : OptimistPostItsDatos.leer(c, id)) abiertos.add(ventana(db, id, p, abiertos, false));
                } catch (SQLException ex) { error(ex); }
            } else {
                for (Dialog d : new ArrayList<>(abiertos)) d.close();
                abiertos.clear();
            }
        });
    }

    private static String usuario() {
        String u = OptimistShell.usuario();
        return u == null ? "" : u.strip();
    }

    private static void error(SQLException ex) {
        Notification.show("PostIts: " + ex.getMessage(), 8000, Notification.Position.MIDDLE);
    }

    /** La ventanita de un PostIt: arrastrable, de tamaño variable, sin bloquear el formulario. */
    private static Dialog ventana(OptimistDatabase db, String id, PostIt inicial, List<Dialog> abiertos, boolean nuevo) {
        PostIt[] p = {inicial};
        boolean mio = usuario().equalsIgnoreCase(inicial.usuario());
        Dialog d = new Dialog();
        d.setModal(false);
        d.setDraggable(true);
        d.setResizable(true);
        d.setCloseOnEsc(false);
        d.setCloseOnOutsideClick(false);
        d.addThemeName("optimist-postit");
        // V69c: con la API de la ventana (antes la propiedad del elemento: al volver a abrir no respetaba el lugar)
        d.setLeft(Math.max(0, inicial.x()) + "px");
        d.setTop(Math.max(0, inicial.y()) + "px");
        d.setWidth(Math.max(160, inicial.ancho()) + "px");
        d.setHeight(Math.max(120, inicial.alto()) + "px");

        TextArea texto = new TextArea();
        texto.setValue(inicial.texto() == null ? "" : inicial.texto());
        texto.setWidthFull();
        texto.setHeightFull();
        texto.setReadOnly(!mio);
        texto.setPlaceholder(mio ? "Escribí la nota..." : "");
        texto.setValueChangeMode(ValueChangeMode.ON_BLUR);
        texto.addClassName("optimist-postit-texto");
        Span info = new Span();
        info.addClassNames("optimist-postit-info", "draggable", "draggable-leaf-only");
        info.getStyle().set("cursor", "move");
        Runnable pintar = () -> {
            for (String c : COLORES.keySet()) d.removeThemeName("postit-" + c);
            d.addThemeName("postit-" + p[0].color());
            LocalDate v = p[0].vence();
            boolean vencido = v != null && !v.isAfter(LocalDate.now());
            if (vencido) d.addThemeName("postit-vencido"); else d.removeThemeName("postit-vencido");
            String quien = p[0].usuario() + (p[0].fecha() == null || p[0].fecha().isBlank() ? "" : " · " + p[0].fecha());
            String estado = v == null ? "" : v.isBefore(LocalDate.now()) ? " · VENCIDO (" + v.format(DMY) + ")"
                    : v.isEqual(LocalDate.now()) ? " · VENCE HOY" : " · vence " + v.format(DMY);
            info.setText(quien + estado);
        };
        java.util.function.Consumer<UnaryOperator<PostIt>> guardar = f -> {
            try {
                List<PostIt> l = cambiar(db, id, lista -> {
                    for (int i = 0; i < lista.size(); i++) if (lista.get(i).id().equals(p[0].id())) lista.set(i, f.apply(lista.get(i)));
                    return lista;
                });
                l.stream().filter(x -> x.id().equals(p[0].id())).findFirst().ifPresent(x -> p[0] = x);
                pintar.run();
            } catch (SQLException ex) { error(ex); }
        };
        texto.addValueChangeListener(e -> { if (e.isFromClient() && mio) guardar.accept(x -> x.con(e.getValue(), x.color(), x.vence())); });

        // arriba: colores, vencimiento y la cruz (solo el que lo pegó)
        HorizontalLayout arriba = new HorizontalLayout();
        arriba.setAlignItems(HorizontalLayout.Alignment.CENTER);
        arriba.setSpacing(false);
        arriba.getStyle().set("gap", "4px").set("width", "100%").set("cursor", "move");
        // V69b: Vaadin mueve la ventana solo desde lo que tiene la clase "draggable" (o el espacio entre componentes): la barra de
        // arriba la ocupaba entera y no se podía mover. "draggable-leaf-only": se arrastra desde la barra, no desde sus botones.
        arriba.addClassNames("draggable", "draggable-leaf-only");
        // V69c: la manija para moverlo, a la vista (cuatro flechas)
        com.vaadin.flow.component.icon.Icon manija = VaadinIcon.ARROWS.create();
        manija.setSize("14px");
        manija.getStyle().set("cursor", "move").set("color", "rgba(0,0,0,.55)").set("flex", "0 0 auto");
        manija.getElement().setAttribute("title", "Arrastrá desde acá para moverlo");
        manija.addClassName("draggable");
        arriba.add(manija);
        if (mio) {
            for (Map.Entry<String, String> c : COLORES.entrySet()) {
                Div punto = new Div();
                punto.getStyle().set("width", "14px").set("height", "14px").set("border-radius", "50%").set("cursor", "pointer")
                        .set("background", c.getValue()).set("border", "1px solid rgba(0,0,0,.35)");
                punto.getElement().setAttribute("title", c.getKey());
                punto.addClickListener(e -> guardar.accept(x -> x.con(x.texto(), c.getKey(), x.vence())));
                arriba.add(punto);
            }
            DatePicker vence = new DatePicker();
            vence.setLocale(Locale.forLanguageTag("es-AR"));
            vence.setPlaceholder("Vence");
            vence.setClearButtonVisible(true);
            vence.setValue(inicial.vence());
            vence.setWidth("130px");
            vence.addThemeName("small");
            vence.getElement().setAttribute("title", "Vencimiento (desde ese día se ve en rojo)");
            vence.addValueChangeListener(e -> { if (e.isFromClient()) guardar.accept(x -> x.con(x.texto(), x.color(), e.getValue())); });
            arriba.add(vence);
        }
        Div relleno = new Div();
        relleno.getStyle().set("flex", "1 1 auto").set("align-self", "stretch").set("min-height", "18px").set("cursor", "move");
        relleno.addClassNames("draggable", "draggable-leaf-only");
        arriba.add(relleno);
        if (mio) {
            Button sacar = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> new ConfirmDialog("PostIt", "¿Sacar este PostIt?", "Sí", ev -> {
                try {
                    cambiar(db, id, lista -> { lista.removeIf(x -> x.id().equals(p[0].id())); return lista; });
                    abiertos.remove(d);
                    d.close();
                } catch (SQLException ex) { error(ex); }
            }, "No", ev -> { }).open());
            sacar.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            sacar.getElement().setAttribute("title", "Sacar el PostIt");
            arriba.add(sacar);
        }
        d.getHeader().add(arriba);
        d.add(texto);
        d.getFooter().add(info);

        // moverlo o agrandarlo: el lugar queda guardado (para todos)
        // V69c: con los eventos de la ventana (antes un evento del elemento que no llegaba: el lugar no se guardaba)
        d.addDraggedListener(e -> {
            try { guardar.accept(x -> x.en(entero(e.getLeft(), x.x()), entero(e.getTop(), x.y()), x.ancho(), x.alto())); }
            catch (RuntimeException ex) { Notification.show("PostIt: no se pudo guardar el lugar: " + ex.getMessage(), 8000, Notification.Position.MIDDLE); }
        });
        d.addResizeListener(e -> {
            try { guardar.accept(x -> x.en(entero(e.getLeft(), x.x()), entero(e.getTop(), x.y()), entero(e.getWidth(), x.ancho()), entero(e.getHeight(), x.alto()))); }
            catch (RuntimeException ex) { Notification.show("PostIt: no se pudo guardar el tamaño: " + ex.getMessage(), 8000, Notification.Position.MIDDLE); }
        });
        pintar.run();
        d.open();
        if (nuevo && mio) texto.focus();
        return d;
    }

    // ------------------------------------------------------------------ V82i: los de la pantalla (personales)

    /**
     * V82i - PostIts de la PANTALLA (no de un registro): los WorkWith, los web panels, las consultas. Cada usuario ve solo
     * los suyos (ej. cómo completar los parámetros de un proceso, lo que antes se anotaba en un cuaderno). Se guardan en la
     * misma tabla PostIts con PostItsId = "P:usuario|ruta" (ruta: "trn/cpbc", "wbp/wplistarstock"...).
     * V82q: el botón lo arma cada pantalla con sus botones (como Excel o Apariencia), una sola vez; el shell, al montar cada
     * pantalla, solo cierra los PostIts de la anterior y abre los de esta (abrirDePantalla).
     */
    public static void abrirDePantalla() {
        com.vaadin.flow.component.UI ui = com.vaadin.flow.component.UI.getCurrent();
        if (ui == null) return;
        for (Dialog d : new ArrayList<>(abiertosDePantalla(ui))) d.close();
        abiertosDePantalla(ui).clear();
        if (!OptimistDatabase.conPostIts()) return;   // V83: sistema sin PostIts
        String id = idPantalla();
        OptimistDatabase db = OptimistDatabase.instancia();
        if (id == null || db == null) return;
        try (Connection c = db.openConnection()) {
            for (PostIt p : OptimistPostItsDatos.leer(c, id)) abiertosDePantalla(ui).add(ventana(db, id, p, abiertosDePantalla(ui), false));
        } catch (SQLException | RuntimeException ex) { /* sin la tabla PostIts: sin PostIts */ }
    }

    /** El botón "PostIt" de la pantalla (lo pone cada pantalla con sus botones, al armarla). */
    public static Button boton() {
        Button pegar = new Button("PostIt", VaadinIcon.NOTEBOOK.create(), e -> {
            com.vaadin.flow.component.UI ui = com.vaadin.flow.component.UI.getCurrent();
            String id = idPantalla();
            OptimistDatabase db = OptimistDatabase.instancia();
            if (ui == null || id == null || db == null) return;
            List<Dialog> abiertos = abiertosDePantalla(ui);
            int n = abiertos.size();
            PostIt p = new PostIt(UUID.randomUUID().toString(), "", "amarillo", 220 + 24 * n, 120 + 24 * n, ANCHO, ALTO, null,
                    usuario(), LocalDateTime.now().format(DMYHM));
            try {
                cambiar(db, id, l -> { l.add(p); return l; });
                abiertos.add(ventana(db, id, p, abiertos, true));
            } catch (SQLException ex) { error(ex); }
        });
        pegar.addThemeVariants(ButtonVariant.LUMO_TERTIARY);   // como el de las TRN
        pegar.getStyle().set("flex-shrink", "0");
        pegar.getElement().setAttribute("title", "Pegar un PostIt en esta pantalla (solo lo ves vos)");
        pegar.setVisible(OptimistDatabase.conPostIts());   // V83: sistema sin PostIts (sistema.postits=false)
        return pegar;
    }

    /** La clave de los PostIts de la pantalla actual para el usuario ("P:usuario|ruta"), o null sin usuario. */
    private static String idPantalla() {
        String usuario = usuario();
        if (usuario.isEmpty()) return null;
        String ruta = OptimistShell.rutaPantalla();
        return OptimistPostItsDatos.idRegistro("P:" + usuario.toLowerCase(Locale.ROOT), ruta == null ? "" : ruta.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static List<Dialog> abiertosDePantalla(com.vaadin.flow.component.UI ui) {
        Object o = com.vaadin.flow.component.ComponentUtil.getData(ui, "optimist.postits.pantalla");
        if (o instanceof List<?> l) return (List<Dialog>) l;
        List<Dialog> nueva = new ArrayList<>();
        com.vaadin.flow.component.ComponentUtil.setData(ui, "optimist.postits.pantalla", nueva);
        return nueva;
    }

    // ------------------------------------------------------------------ en el WorkWith

    /** El puntito de la fila (null si el registro no tiene PostIts): del color del primero, con los textos al pasar el mouse. */
    public static Component marca(List<PostIt> ps) {
        if (ps == null || ps.isEmpty()) return null;
        Div punto = new Div();
        String color = COLORES.getOrDefault(ps.get(0).color(), "#fff59d");
        boolean vencido = ps.stream().anyMatch(x -> x.vence() != null && !x.vence().isAfter(LocalDate.now()));
        punto.getStyle().set("width", "7px").set("height", "7px").set("border-radius", "50%").set("flex", "0 0 auto")
                .set("background", color).set("border", vencido ? "1px solid #c62828" : "1px solid rgba(0,0,0,.45)");
        StringBuilder t = new StringBuilder();
        for (PostIt p : ps) {
            if (t.length() > 0) t.append("\n");
            String x = p.texto() == null ? "" : p.texto().strip().replace('\n', ' ');
            t.append("• ").append(x.length() > 80 ? x.substring(0, 80) + "…" : x);
        }
        punto.getElement().setAttribute("title", t.toString());
        return punto;
    }
}
