package ar.com.datafix.optimist;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import java.util.Properties;

/** Estilos compartidos por todas las transacciones y sus diálogos. */
public final class OptimistVisuals {
    private OptimistVisuals() {}

    /** Una sola fuente de verdad para WorkWith, TRN, WebPanels y overlays. */
    public static void aplicarPaletaGlobal(Properties cfg) {
        String fondo = cfg.getProperty("fondo", "#091e24");
        String texto = cfg.getProperty("texto", "#f5f7fb");
        String acento = cfg.getProperty("acento", "#0ea88f");
        String fuente = cfg.getProperty("fuente", "Arial");
        String letra = cfg.getProperty("letra", "15") + "px";
        String modo = cfg.getProperty("modo", "Oscuro");
        // V66p: las densidades se publican en <html> para que las tomen TODAS las grillas y los formularios que no son ventana
        String densForm = clave(cfg.getProperty("densidadFormulario", "Normal"));
        String densGrilla = clave(cfg.getProperty("densidad", "Normal"));
        boolean claro = "Claro".equals(modo) || ("Personalizado".equals(modo) && esClaro(fondo));
        UI ui = UI.getCurrent();
        if (ui == null) return;
        ui.getPage().executeJs("""
            const root = document.documentElement;
            const vars = {
              '--optimist-fondo': $0, '--optimist-texto': $1,
              '--optimist-acento': $2, '--optimist-fuente': $3,
              '--optimist-letra': $4, '--lumo-base-color': $0,
              '--lumo-body-text-color': $1, '--lumo-header-text-color': $1,
              '--lumo-secondary-text-color': $1, '--lumo-primary-color': $2,
              '--lumo-font-family': $3, '--lumo-font-size-m': $4,
              '--vaadin-grid-cell-background': $0,
              '--vaadin-grid-background': $0
            };
            for (const [k, v] of Object.entries(vars)) root.style.setProperty(k, v);
            root.setAttribute('theme', $5);
            root.setAttribute('data-optimist-form', $6);
            root.setAttribute('data-optimist-grid', $7);
            document.body.style.backgroundColor = $0;
            document.body.style.color = $1;
            """, fondo, texto, acento, "'" + fuente.replace("'", "") + "', Arial, sans-serif", letra,
                claro ? "light" : "dark", densForm, densGrilla);
    }

    /** V66p: "Supercompacta" -> "supercompacta", "Cómoda" -> "comoda" (el valor del atributo que usa el CSS). */
    private static String clave(String densidad) {
        String d = densidad == null ? "normal" : java.text.Normalizer.normalize(densidad.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        return d.isEmpty() ? "normal" : d;
    }

    private static boolean esClaro(String hex) {
        try {
            int rgb = Integer.parseInt(hex.replace("#", ""), 16);
            int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            return (r * 299 + g * 587 + b * 114) / 1000 >= 145;
        } catch (Exception ex) { return false; }
    }

    public static void estilizarDialogo(Dialog dialogo, Properties cfg) {
        dialogo.addThemeName("optimist-dialog");
        // E4g (Diego, 29/10/2026): todas las ventanas (lupas, web panels, formularios, PDF…) se pueden mover: se arrastran
        // desde el título (o un espacio libre, si no tienen título), como cualquier ventana de Windows
        dialogo.setDraggable(true);
        // V34: densidad de los formularios (Apariencia): la toma el overlay del diálogo por su tema
        String densidad = cfg == null ? "Normal" : cfg.getProperty("densidadFormulario", "Normal");
        if ("Supercompacta".equals(densidad)) dialogo.addThemeName("optimist-form-supercompacta");   // V36
        else if ("Compacta".equals(densidad)) dialogo.addThemeName("optimist-form-compacta");
        else if ("Cómoda".equals(densidad)) dialogo.addThemeName("optimist-form-comoda");
        // El overlay vive en document.body; NO hereda estilos del componente padre.
        // Los tokens se definen en documentElement y la hoja de tema del overlay.
        aplicarPaletaGlobal(cfg);
    }

    /**
     * V65l: el Atrás / Adelante es el del navegador: su historial es la pila. Cada pantalla (WorkWith, web panel, consulta) y
     * cada registro abierto (trn/x?abrir=, ?ver=, ?nuevo) es una dirección. Las ventanas chicas (lupas, PDF, web panels en
     * ventana, columnas, ayuda) NO son pasos: al ir Atrás o Adelante se cierran solas (cerrarVentanas) y queda la pantalla de
     * esa dirección. Antes (V65 / V65b) cada ventana agregaba un paso "invisible" y, cerrada con sus botones, el paso quedaba
     * en el historial: Atrás y Adelante caían en pasos muertos y no hacían lo que se esperaba.
     */
    private static final String NAVEGANDO = "optimist-navegando";

    /** V65l: se está yendo a otra dirección (Atrás, Adelante, vínculo, menú): las ventanas se cierran sin tocar el historial. */
    public static boolean navegando() {
        UI ui = UI.getCurrent();
        return ui != null && Boolean.TRUE.equals(com.vaadin.flow.component.ComponentUtil.getData(ui, NAVEGANDO));
    }

    /** V65l: cierra todas las ventanas abiertas de la UI (lo llama la navegación, antes de mostrar la pantalla de destino). */
    public static void cerrarVentanas(UI ui) {
        java.util.List<com.vaadin.flow.component.Component> abiertas = new java.util.ArrayList<>();
        juntarVentanas(ui, abiertas);
        if (abiertas.isEmpty()) return;
        com.vaadin.flow.component.ComponentUtil.setData(ui, NAVEGANDO, Boolean.TRUE);
        try {
            // de la de más arriba a la de más abajo (la última abierta primero)
            for (int i = abiertas.size() - 1; i >= 0; i--) {
                com.vaadin.flow.component.Component v = abiertas.get(i);
                try {
                    if (v instanceof Dialog d) d.close();
                    else if (v instanceof com.vaadin.flow.component.confirmdialog.ConfirmDialog cd) cd.close();
                } catch (RuntimeException ex) { System.out.println("[navegación] " + ex); }
            }
        } finally {
            com.vaadin.flow.component.ComponentUtil.setData(ui, NAVEGANDO, null);
        }
    }

    private static void juntarVentanas(com.vaadin.flow.component.Component c, java.util.List<com.vaadin.flow.component.Component> abiertas) {
        if (c instanceof Dialog d && d.isOpened()) abiertas.add(d);
        else if (c instanceof com.vaadin.flow.component.confirmdialog.ConfirmDialog cd && cd.isOpened()) abiertas.add(cd);
        if (c instanceof Grid<?>) return;   // adentro de una grilla no hay ventanas (y puede tener muchas columnas)
        c.getChildren().forEach(h -> juntarVentanas(h, abiertas));
    }

    /** V84h: el formulario de una TRN: tiene su paso en el historial (?abrir=); el Atrás lo cierra navegando, como siempre. */
    public static void sinPasoAtras(Dialog d) { com.vaadin.flow.component.ComponentUtil.setData(d, MARCA_ATRAS, "formulario"); }

    /** V84h: una ventana que el Atrás no cierra (ej. "Procesando…"): el Atrás no hace nada mientras está abierta. */
    public static void atrasNoCierra(Dialog d) { com.vaadin.flow.component.ComponentUtil.setData(d, MARCA_ATRAS, "fijo"); }

    private static final String MARCA_ATRAS = "optimist.marcaAtras";

    static String marcaAtras(com.vaadin.flow.component.Component c) {
        Object o = c == null ? null : com.vaadin.flow.component.ComponentUtil.getData(c, MARCA_ATRAS);
        return o == null ? null : o.toString();
    }

    /** V84h: las ventanas abiertas de la UI, de la de más abajo a la de más arriba (la última abierta al final). */
    static java.util.List<com.vaadin.flow.component.Component> ventanasAbiertas(UI ui) {
        java.util.List<com.vaadin.flow.component.Component> abiertas = new java.util.ArrayList<>();
        juntarVentanas(ui, abiertas);
        return abiertas;
    }

    /**
     * V21: tamaño de los formularios de TODAS las TRN.
     * Pantalla completa = ocupa la zona de trabajo (a la derecha del menú), nunca encima del menú.
     * El ancho del menú lo publica OptimistShell en --optimist-menu-ancho.
     */
    public static void dimensionarFormulario(Dialog dialogo, boolean pantallaCompleta, String anchoNormal) {
        if (pantallaCompleta) {
            dialogo.addThemeName("optimist-completa");
            dialogo.setWidth("100%");
            dialogo.setHeight("100%");
        } else {
            dialogo.setWidth(anchoNormal);
        }
    }

    /** V65: la X arriba a la derecha para cerrar la ventana (lupas, PDF, web panels), como cualquier ventana de Windows. */
    public static void botonCerrar(Dialog dialogo) {
        com.vaadin.flow.component.button.Button x = new com.vaadin.flow.component.button.Button(
                com.vaadin.flow.component.icon.VaadinIcon.CLOSE.create(), e -> dialogo.close());
        x.addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_TERTIARY_INLINE);
        x.getElement().setAttribute("title", "Cerrar");
        x.getElement().setAttribute("aria-label", "Cerrar");
        x.getStyle().set("margin-left", "auto").set("cursor", "pointer");
        dialogo.getHeader().add(x);
    }

    public static void estilizarDialogo(Dialog dialogo) {
        String usuario = OptimistShell.usuario();
        estilizarDialogo(dialogo, new OptimistUserConfig().cargar(
            usuario == null ? "anonimo" : usuario));
    }

    public static void estilizarGrilla(Grid<?> grilla, String fondo, String texto) {
        grilla.addThemeName("optimist-grid");
        grilla.getElement().getStyle()
            .set("--optimist-fondo", fondo)
            .set("--optimist-texto", texto)
            .set("--vaadin-grid-cell-background", fondo)
            .set("--vaadin-grid-background", fondo)
            .set("--lumo-base-color", fondo)
            .set("--lumo-body-text-color", texto)
            .set("background-color", fondo)
            .set("color", texto);
    }

    /**
     * V32: botones del pie de un formulario de TRN, SIEMPRE a la izquierda. No depende del CSS del pie
     * (en las TRN de dos niveles quedaban a la derecha): un relleno elástico después de los botones los empuja.
     */
    public static void pieIzquierda(Dialog dialogo, com.vaadin.flow.component.Component... botones) {
        dialogo.getFooter().add(botones);
        com.vaadin.flow.component.html.Div r = relleno();
        com.vaadin.flow.component.ComponentUtil.setData(dialogo, RELLENO, r);   // V82i: para poner algo al lado de los botones
        dialogo.getFooter().add(r);
    }

    /** V32: para los web panels: botones centrados (relleno elástico a los dos lados). */
    public static void pieCentrado(Dialog dialogo, com.vaadin.flow.component.Component... botones) {
        dialogo.getFooter().add(relleno());
        dialogo.getFooter().add(botones);
        com.vaadin.flow.component.html.Div r = relleno();
        com.vaadin.flow.component.ComponentUtil.setData(dialogo, RELLENO, r);   // V82i: para poner algo al lado de los botones
        dialogo.getFooter().add(r);
    }

    /** V82i: la clase del espacio elástico del pie (lo que va pegado a los botones se pone antes de él). */
    static final String RELLENO = "optimist-pie-relleno";

    /** V82i: agrega un botón a la derecha de los botones del pie (antes del espacio elástico), ej. PostIt. */
    public static void alLadoDeLosBotones(Dialog dialogo, com.vaadin.flow.component.Component boton) {
        Object r = com.vaadin.flow.component.ComponentUtil.getData(dialogo, RELLENO);
        if (r instanceof com.vaadin.flow.component.Component relleno && relleno.getParent().isPresent()) {
            dialogo.getFooter().remove(relleno);   // lo que estaba después del espacio (si algo) queda igual a la derecha
            dialogo.getFooter().add(boton, relleno);
        } else dialogo.getFooter().add(boton);
    }

    private static com.vaadin.flow.component.html.Div relleno() {
        com.vaadin.flow.component.html.Div r = new com.vaadin.flow.component.html.Div();
        r.getStyle().set("flex", "1 1 auto").set("min-width", "0");
        r.addClassName(RELLENO);
        return r;
    }
}
