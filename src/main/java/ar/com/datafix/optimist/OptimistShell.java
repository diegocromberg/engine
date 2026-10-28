package ar.com.datafix.optimist;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.contextmenu.SubMenu;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.server.VaadinSession;
import java.util.*;

/**
 * Shell MAESTRO obligatorio de OPTIMIST ERP.
 * Toda pantalla funcional se monta aquí: identidad, usuario, menú, búsqueda y navegación.
 */
public final class OptimistShell {
    private OptimistShell() {}

    public static String usuario() {
        Object u = VaadinSession.getCurrent().getAttribute("optimist.usuario");
        return u == null ? null : u.toString();
    }

    public static boolean exigirLogin() {
        // V30: sin contexto (sesión abierta antes de esta versión) se vuelve a pedir el login
        if (usuario() != null && OptimistContexto.actual() != null) return true;
        irAlLogin();
        return false;
    }

    /** V53: el ?CK= que hay que llevar al login (el de la sesión), o vacío. */
    static com.vaadin.flow.router.QueryParameters codigoCliente() {
        Object ck = VaadinSession.getCurrent() == null ? null : VaadinSession.getCurrent().getAttribute(OptimistDatabase.ATRIBUTO_CK);
        return ck == null ? com.vaadin.flow.router.QueryParameters.empty() : com.vaadin.flow.router.QueryParameters.of("CK", String.valueOf(ck));
    }

    /** V53: al login conservando el código de cliente (instalación única). */
    public static void irAlLogin() { UI.getCurrent().navigate("login", codigoCliente()); }

    /**
     * V53: desde el beforeEnter de una pantalla: al login con el ?CK= de esta dirección (o el de la sesión). La primera vez
     * que se entra con ?CK= el código todavía puede no estar anotado: se lleva el de la dirección.
     */
    public static void alLogin(com.vaadin.flow.router.BeforeEnterEvent e) {
        String ck = OptimistCodigoCliente.codigo(e.getLocation().getQueryParameters().getParameters());
        e.forwardTo("login", ck != null ? com.vaadin.flow.router.QueryParameters.of("CK", ck) : codigoCliente());
    }

    public static void montar(VerticalLayout raiz, Component contenido) {
        montar(raiz, contenido, "");
    }

    public static void montar(VerticalLayout raiz, Component contenido, String tituloPantalla) {
        if (!exigirLogin()) return;
        java.util.Properties cfgUsuario = new OptimistUserConfig().cargar(usuario());
        OptimistVisuals.aplicarPaletaGlobal(cfgUsuario);
        // V46: menú horizontal (barra arriba con desplegables), si el usuario lo eligió en Apariencia. En el celular, siempre el vertical.
        if (menuHorizontal(cfgUsuario) && !pantallaAngosta()) { montarHorizontal(raiz, contenido, tituloPantalla); return; }
        altoBarra("0px");
        // V29: ancho del menú elegido por el usuario (arrastrando el borde), guardado en su archivo
        final int[] anchoAbierto = {anchoGuardado(cfgUsuario)};
        anchoMenu(anchoAbierto[0] + "px");
        raiz.removeAll();
        // V32: la aplicación ocupa exactamente la ventana; el menú y la zona de trabajo tienen su propia barra de desplazamiento
        // (en pantallas bajas el formulario se podía cortar sin poder bajar a los botones)
        raiz.setPadding(false); raiz.setSpacing(false); raiz.setWidthFull(); raiz.setHeight("100vh"); raiz.getStyle().set("overflow", "hidden");

        VerticalLayout menu = new VerticalLayout();
        menu.setPadding(false); menu.setSpacing(false); menu.setWidth(anchoAbierto[0] + "px"); menu.setMinWidth(anchoAbierto[0] + "px");
        menu.addClassName("optimist-menu-lateral");
        menu.getStyle().set("background", FONDO_MENU).set("color", "var(--optimist-texto)").set("height", "100vh").set("box-shadow", "2px 0 8px #0003").set("overflow-x", "hidden").set("overflow-y", "auto")
            .set("position", "relative");

        // V29: manija para cambiar el ancho del menú arrastrando su borde derecho
        Div manija = new Div();
        manija.addClassName("optimist-menu-manija");
        manija.getElement().setAttribute("title", "Arrastrar para cambiar el ancho del menú");
        manija.getStyle().set("position", "absolute").set("top", "0").set("right", "0").set("bottom", "0")
            .set("width", "6px").set("cursor", "col-resize").set("z-index", "5");
        manija.getElement().executeJs(
            "const menu=$0, h=this;" +
            "h.addEventListener('mousedown', ev => { ev.preventDefault();" +
            "  const x0=ev.clientX, w0=menu.getBoundingClientRect().width;" +
            "  const mv=e=>{ const w=Math.min(600,Math.max(220,w0+e.clientX-x0));" +
            "    menu.style.width=w+'px'; menu.style.minWidth=w+'px';" +
            "    document.documentElement.style.setProperty('--optimist-menu-ancho', w+'px'); };" +
            "  const up=()=>{ document.removeEventListener('mousemove',mv); document.removeEventListener('mouseup',up);" +
            "    h.dispatchEvent(new CustomEvent('optimist-ancho',{detail: Math.round(menu.getBoundingClientRect().width)})); };" +
            "  document.addEventListener('mousemove',mv); document.addEventListener('mouseup',up); });",
            menu.getElement());

        Button colapsar = new Button("☰");
        colapsar.getElement().setAttribute("title", "Contraer / expandir menú");
        colapsar.getStyle().set("margin", "8px").set("min-width", "44px");

        Span logo = new Span(OptimistDatabase.nombreSistema());
        logo.getStyle().set("font-family", "'Perpetua Titling MT','Perpetua',Georgia,serif")
            .set("font-weight", "bold").set("font-size", "28px").set("padding", "12px 1px 8px 5px").set("color", "var(--optimist-texto)").set("white-space", "nowrap");

        HorizontalLayout cabecera = new HorizontalLayout(colapsar, logo);
        cabecera.setWidthFull(); cabecera.setAlignItems(HorizontalLayout.Alignment.CENTER); cabecera.setSpacing(false);

        Span sesion = new Span("Usuario: " + usuario());
        sesion.getStyle().set("color", "var(--optimist-texto)").set("padding", "0 16px 8px").set("font-size", "13px").set("white-space", "nowrap");
        // V30: empresa/división en la que trabaja (EmpresaCaption del WWPContext)
        OptimistContexto ctx = OptimistContexto.actual();
        Span empresa = new Span(ctx == null ? "" : ctx.caption());
        empresa.getElement().setAttribute("title", empresa.getText());
        empresa.getStyle().set("color", "var(--optimist-texto)").set("padding", "0 16px 8px").set("font-size", "12px")
                .set("font-weight", "600").set("white-space", "nowrap").set("overflow", "hidden").set("text-overflow", "ellipsis")
                .set("display", "block").set("max-width", "100%").set("box-sizing", "border-box");
        empresa.setVisible(!empresa.getText().isEmpty());

        TextField buscar = new TextField();
        buscar.setPlaceholder("Buscar en el menú..."); buscar.setClearButtonVisible(true);
        buscar.setWidth("calc(100% - 24px)"); buscar.getStyle().set("margin", "6px 12px 14px");
        buscar.setValueChangeMode(ValueChangeMode.EAGER);
        menu.add(cabecera, sesion, empresa, buscar);

        VerticalLayout navegacion = new VerticalLayout();
        navegacion.setPadding(false); navegacion.setSpacing(false); navegacion.setWidthFull();
        menu.add(navegacion);

        // V21: el menú se define en resources/menu.yaml (categorías, subgrupos, orden e ítems). Nada entra solo.
        List<Grupo> grupos = new ArrayList<>();
        for (OptimistMenu.Carpeta cat : OptimistMenu.cargar()) {
            Grupo g = armar(cat, 0, "", grupos);
            if (grupos.isEmpty()) {
                Button inicio = item("Inicio", "");
                g.contenido().addComponentAsFirst(inicio); g.items().add(inicio);
            }
            navegacion.add(g.panel()); grupos.add(g);
        }

        // la ruta marcada vale solo para esta pantalla: una vista que no la avise no hereda la anterior
        ComponentUtil.setData(UI.getCurrent(), "optimist.ayuda.ruta", ComponentUtil.getData(UI.getCurrent(), "optimist.ruta"));   // V48: para Ayuda
        ComponentUtil.setData(UI.getCurrent(), "optimist.ruta", null);

        Button ayuda = OptimistAyuda.botonBarra();   // V48
        Component campana = OptimistMensajes.campana(true);   // V72: la campanita de mensajes, arriba de Ayuda
        OptimistPostIts.abrirDePantalla();   // V82i / V82q: los PostIts de la pantalla (el botón lo pone cada pantalla)
        if (campana != null) { campana.getElement().getStyle().set("margin-top", "auto"); menu.add(campana); }
        ayuda.setWidthFull(); if (campana == null) ayuda.getStyle().set("margin-top", "auto"); menu.add(ayuda);
        Button salir = new Button("Cerrar sesión", e -> cerrarSesion());
        salir.setWidthFull(); menu.add(salir);

        buscar.addValueChangeListener(e -> {
            String q = e.getValue().trim().toLowerCase(Locale.ROOT);
            for (Grupo g : grupos) g.filtrar(q, false);
        });

        // contraido[0]: menú angosto (solo ☰). movil[0]: pantalla de celular (V28)
        final boolean[] contraido = {false};
        final boolean[] movil = {false};
        java.util.function.Consumer<Boolean> aplicar = c -> {
            contraido[0] = c;
            String abierto = anchoAbierto[0] + "px";
            String ancho = c ? "64px" : (movil[0] ? "min(" + abierto + ", 85vw)" : abierto);
            menu.setWidth(ancho); menu.setMinWidth(ancho);
            anchoMenu(c ? "64px" : abierto);
            manija.setVisible(!c && !movil[0]);
            logo.setVisible(!c); sesion.setVisible(!c); buscar.setVisible(!c); navegacion.setVisible(!c); salir.setVisible(!c);
            // V28: en el celular, el menú abierto va ENCIMA de la pantalla (no la empuja)
            if (movil[0] && !c) menu.addClassName("optimist-menu-flotante");
            else menu.removeClassName("optimist-menu-flotante");
        };
        colapsar.addClickListener(e -> aplicar.accept(!contraido[0]));
        manija.getElement().addEventListener("optimist-ancho", ev -> {
            int w = (int) Math.round(ev.getEventData().getNumber("event.detail"));
            anchoAbierto[0] = Math.max(220, Math.min(600, w));
            menu.setWidth(anchoAbierto[0] + "px"); menu.setMinWidth(anchoAbierto[0] + "px");
            anchoMenu(anchoAbierto[0] + "px");
            new OptimistUserConfig().guardarClave(usuario(), "menuAncho", String.valueOf(anchoAbierto[0]));
        }).addEventData("event.detail");
        menu.add(manija);
        // V32b: el menú ahora tiene alto fijo (la ventana) y barra propia; sin esto el navegador "aplastaba"
        // sus renglones para que entraran (la empresa debajo del usuario se veía cortada por la mitad)
        menu.getChildren().forEach(hijo -> hijo.getElement().getStyle().set("flex-shrink", "0"));
        // V28: en el celular el menú arranca contraído; se abre con ☰ y se cierra al elegir una opción
        UI.getCurrent().getPage().retrieveExtendedClientDetails(d -> {
            if (d.getWindowInnerWidth() > 0 && d.getWindowInnerWidth() <= 700) {
                movil[0] = true;
                aplicar.accept(true);
            }
        });

        Div area = areaDeTrabajo(contenido, tituloPantalla, "100vh");
        HorizontalLayout todo = new HorizontalLayout(menu, area);
        todo.setPadding(false); todo.setSpacing(false); todo.setSizeFull(); todo.setFlexGrow(1, area);
        raiz.add(todo);
    }

    /** Título único de la TRN (en el shell, nunca duplicado dentro de cada vista) y el contenido, con barra de desplazamiento propia. */
    private static Div areaDeTrabajo(Component contenido, String tituloPantalla, String alto) {
        VerticalLayout zonaTrabajo = new VerticalLayout();
        zonaTrabajo.setPadding(false); zonaTrabajo.setSpacing(false); zonaTrabajo.setSizeFull();
        if (tituloPantalla != null && !tituloPantalla.isBlank()) {
            Span tituloBarra = new Span(tituloPantalla);
            tituloBarra.getStyle().set("font-size", "20px").set("font-weight", "600")
                .set("padding", "12px 22px").set("box-sizing", "border-box")
                .set("background", "var(--optimist-fondo)").set("color", "var(--optimist-texto)").set("width", "100%");
            zonaTrabajo.add(tituloBarra);
        }
        zonaTrabajo.add(contenido);
        Div area = new Div(zonaTrabajo); area.setSizeFull();
        area.getStyle().set("min-width", "0").set("min-height", "0").set("height", alto).set("overflow", "auto");
        return area;
    }

    private static void cerrarSesion() {
        VaadinSession.getCurrent().setAttribute("optimist.usuario", null);
        VaadinSession.getCurrent().setAttribute(OptimistContexto.ATRIBUTO, null);
        UI.getCurrent().getPage().executeJs("localStorage.removeItem('optimist.usuario')");
        irAlLogin();   // V53: con el código de cliente
    }

    // ------------------------------------------------------------------------------------------------
    // V46: MENÚ HORIZONTAL, como el OPTIMIST ERP de GeneXus. Barra arriba: logo, categorías de menu.yaml
    // (cada una despliega sus opciones AL HACER CLIC, y los subgrupos como submenús), buscador, usuario,
    // empresa y Cerrar sesión. Las categorías que no entran van al botón "···" del final de la barra.
    // ------------------------------------------------------------------------------------------------

    /** Alto de la barra del menú horizontal (los formularios en pantalla completa arrancan debajo). */
    private static final String ALTO_BARRA = "52px";

    /** V48e: el fondo del menú (barra horizontal o lateral) es un 10% más oscuro que el de la pantalla. */
    private static final String FONDO_MENU = "color-mix(in srgb, var(--optimist-fondo) 90%, #000)";

    /** Opción del menú para el buscador: texto, camino de carpetas ("Ventas › Comprobantes") y ruta. */
    private record Opcion(String texto, String camino, String ruta) {}

    /** V46: "Horizontal" o "Vertical" (por defecto), elegido en Apariencia. */
    /** V56: las acciones de cada renglón del WorkWith: "Menú" (los 3 puntitos, por defecto) o "Renglón" (un botón por
     *  acción, como GeneXus). En el celular, siempre el menú. */
    public static boolean accionesEnRenglon(java.util.Properties cfg) {
        return "Renglón".equalsIgnoreCase(cfg.getProperty("acciones", "Menú").trim()) && !pantallaAngosta();
    }

    public static boolean menuHorizontal(java.util.Properties cfg) {
        return "Horizontal".equalsIgnoreCase(cfg.getProperty("menu", "Vertical").trim());
    }

    /** V46: el navegador es de celular (se recuerda en la sesión): ahí el menú es siempre el vertical con ☰. */
    static boolean pantallaAngosta() {
        return Boolean.TRUE.equals(VaadinSession.getCurrent().getAttribute("optimist.angosta"));
    }

    private static void montarHorizontal(VerticalLayout raiz, Component contenido, String tituloPantalla) {
        anchoMenu("0px");
        altoBarra(ALTO_BARRA);
        raiz.removeAll();
        raiz.setPadding(false); raiz.setSpacing(false); raiz.setWidthFull(); raiz.setHeight("100vh"); raiz.getStyle().set("overflow", "hidden");

        Span logo = new Span(OptimistDatabase.nombreSistema());
        logo.getStyle().set("font-family", "'Perpetua Titling MT','Perpetua',Georgia,serif")
            .set("font-weight", "bold").set("font-size", "22px").set("padding", "0 10px 0 16px").set("color", "var(--optimist-texto)")
            .set("white-space", "nowrap").set("flex-shrink", "0").set("cursor", "pointer");
        logo.getElement().setAttribute("title", "Inicio");
        logo.getElement().addEventListener("click", e -> UI.getCurrent().navigate(""));

        MenuBar barra = new MenuBar();
        barra.addThemeVariants(MenuBarVariant.LUMO_TERTIARY);
        barra.addClassName("optimist-menu-barra");
        barra.setOpenOnHover(false);   // V48e: las categorías se abren solo con clic
        barra.getStyle().set("flex", "1 1 auto").set("min-width", "0")
            .set("--lumo-primary-text-color", "var(--optimist-texto)").set("--lumo-body-text-color", "var(--optimist-texto)");
        String actual = rutaActual();
        List<Opcion> opciones = new ArrayList<>();
        boolean primera = true;
        for (OptimistMenu.Carpeta cat : OptimistMenu.cargar()) {
            MenuItem arriba = barra.addItem(cat.nombre());
            boolean marcada = false;
            if (primera) {   // "Inicio" va siempre al principio de la primera categoría (como en el menú vertical)
                marcada = hoja(arriba.getSubMenu(), "Inicio", "", cat.nombre(), actual, opciones);
                primera = false;
            }
            marcada |= llenar(arriba.getSubMenu(), cat, cat.nombre(), actual, opciones);
            if (marcada) marcar(arriba);
        }
        // V48c: al hacer clic en una categoría de la barra se pinta en el momento (y se despinta la anterior), sin esperar
        // a que se abra la pantalla elegida. Cuando se abre, el menú se vuelve a armar marcando la pantalla nueva.
        barra.getElement().executeJs(
            "const bar = this;"
          + "bar.addEventListener('click', ev => {"
          + "  const btn = ev.composedPath().find(n => n && n.localName === 'vaadin-menu-bar-button');"
          + "  if (!btn) return;"
          + "  const pintar = (b, si) => [b, ...b.querySelectorAll('vaadin-menu-bar-item')].forEach(x => {"
          + "    x.style.fontWeight = si ? '700' : ''; x.style.color = si ? 'var(--optimist-acento)' : ''; });"
          + "  bar.querySelectorAll('vaadin-menu-bar-button').forEach(b => pintar(b, b === btn));"
          + "});"
        // V48e: los desplegables se abren SOLO con clic (Vaadin los abre al pasar el mouse): en el documento se frena el
        // mouseover dentro de la barra y de sus desplegables, y al hacer clic en una subcarpeta se le pasa a Vaadin un
        // mouseover "propio" para que la abra. La subcarpeta abierta queda pintada (y se despintan sus hermanas), así se
        // ve el camino Ventas > Procesos > Presupuestos. El verde al pasar el mouse lo pone el CSS (optimist-global.css).
          + "if (!window.__optimistMenuClic) {"
          + "  window.__optimistMenuClic = true;"
          + "  let propio = false;"
          + "  const esMenu = n => n && n.localName && (n.localName === 'vaadin-menu-bar' || n.localName === 'vaadin-menu-bar-overlay'"
          + "      || n.localName === 'vaadin-menu-bar-submenu' || n.localName === 'vaadin-context-menu-overlay');"
          + "  const pintar = (x, si) => { x.style.fontWeight = si ? '700' : ''; x.style.color = si ? 'var(--optimist-acento)' : ''; };"
          + "  document.addEventListener('mouseover', ev => {"
          + "    if (!propio && ev.composedPath().some(esMenu)) ev.stopPropagation();"
          + "  }, true);"
          + "  document.addEventListener('click', ev => {"
          + "    const it = ev.composedPath().find(n => n && (n.localName === 'vaadin-menu-bar-item' || n.localName === 'vaadin-context-menu-item'));"
          + "    if (!it || (it.closest && it.closest('vaadin-menu-bar-button'))) return;"
          + "    if (it.parentElement) Array.from(it.parentElement.children).forEach(s => { if (s.localName === it.localName) pintar(s, s === it); });"
          + "    if (it.getAttribute('aria-haspopup') !== 'true') return;"
          + "    propio = true;"
          + "    try { it.dispatchEvent(new MouseEvent('mouseover', {bubbles: true, composed: true})); } finally { propio = false; }"
          + "  }, true);"
          + "}");
        // la ruta marcada vale solo para esta pantalla (como en el menú vertical)
        ComponentUtil.setData(UI.getCurrent(), "optimist.ayuda.ruta", ComponentUtil.getData(UI.getCurrent(), "optimist.ruta"));   // V48: para Ayuda
        ComponentUtil.setData(UI.getCurrent(), "optimist.ruta", null);

        // buscador: escribiendo parte del nombre aparecen las opciones (con su carpeta) y al elegir una se abre
        ComboBox<Opcion> buscar = new ComboBox<>();
        buscar.setPlaceholder("Buscar en el menú...");
        buscar.setClearButtonVisible(true);
        buscar.setItems(opciones);
        buscar.setItemLabelGenerator(o -> o.texto() + "  —  " + o.camino());
        buscar.setWidth("240px");
        buscar.getStyle().set("flex-shrink", "0").set("--vaadin-combo-box-overlay-width", "460px");
        buscar.addValueChangeListener(e -> {
            if (e.isFromClient() && e.getValue() != null) UI.getCurrent().navigate(e.getValue().ruta());
        });

        // usuario y empresa/división en la que trabaja (EmpresaCaption del WWPContext)
        OptimistContexto ctx = OptimistContexto.actual();
        String caption = ctx == null ? "" : ctx.caption();
        Span usu = new Span("Usuario: " + usuario());
        usu.getStyle().set("font-size", "13px").set("white-space", "nowrap");
        Span emp = new Span(caption);
        emp.getStyle().set("font-size", "12px").set("font-weight", "600").set("white-space", "nowrap")
            .set("overflow", "hidden").set("text-overflow", "ellipsis").set("max-width", "220px");
        emp.setVisible(!caption.isEmpty());
        VerticalLayout quien = new VerticalLayout(usu, emp);
        quien.setPadding(false); quien.setSpacing(false); quien.setWidth(null);
        quien.addClassName("optimist-barra-quien");
        quien.getElement().setAttribute("title", "Usuario: " + usuario() + (caption.isEmpty() ? "" : "\n" + caption));
        logo.getElement().setAttribute("title", "Inicio — Usuario: " + usuario() + (caption.isEmpty() ? "" : " — " + caption));
        quien.getStyle().set("flex-shrink", "0").set("color", "var(--optimist-texto)").set("line-height", "1.25");

        Button salir = new Button("Cerrar sesión", e -> cerrarSesion());
        salir.getStyle().set("flex-shrink", "0");

        HorizontalLayout cabecera = new HorizontalLayout(logo, barra, buscar, OptimistAyuda.botonBarra(), quien, salir);   // V48: Ayuda
        OptimistPostIts.abrirDePantalla();   // V82i / V82q: los PostIts de la pantalla (el botón lo pone cada pantalla)
        Component campana = OptimistMensajes.campana(false);   // V72: la campanita de mensajes, entre Ayuda y el usuario
        if (campana != null) cabecera.addComponentAtIndex(cabecera.indexOf(quien), campana);
        cabecera.setWidthFull(); cabecera.setHeight(ALTO_BARRA); cabecera.setSpacing(false);
        cabecera.setAlignItems(HorizontalLayout.Alignment.CENTER);
        cabecera.getStyle().set("background", FONDO_MENU).set("color", "var(--optimist-texto)")
            .set("box-shadow", "0 2px 8px #0003").set("padding", "0 12px 0 0").set("gap", "12px").set("box-sizing", "border-box")
            .set("flex-shrink", "0").set("position", "relative").set("z-index", "2");

        Div area = areaDeTrabajo(contenido, tituloPantalla, "calc(100vh - " + ALTO_BARRA + ")");
        raiz.add(cabecera, area);
        raiz.setFlexGrow(1, area);

        // en el celular no entra una barra: se recuerda en la sesión y se vuelve a armar con el menú vertical (☰)
        UI.getCurrent().getPage().retrieveExtendedClientDetails(d -> {
            if (d.getWindowInnerWidth() > 0 && d.getWindowInnerWidth() <= 700 && !pantallaAngosta()) {
                VaadinSession.getCurrent().setAttribute("optimist.angosta", Boolean.TRUE);
                rutaActual(actual);   // para que el menú vertical marque la misma pantalla
                montar(raiz, contenido, tituloPantalla);
            }
        });
    }

    /** Agrega a un desplegable las opciones y subcarpetas de una carpeta. Devuelve si adentro está la pantalla actual. */
    private static boolean llenar(SubMenu sub, OptimistMenu.Carpeta c, String camino, String actual, List<Opcion> opciones) {
        boolean contiene = false;
        for (Object h : c.hijos()) {
            if (h instanceof OptimistMenu.Item it && it.pendiente()) pendiente(sub, it.titulo());   // V49n
            else if (h instanceof OptimistMenu.Item it) contiene |= hoja(sub, it.titulo(), it.ruta(), camino, actual, opciones);
            else if (h instanceof OptimistMenu.Carpeta s) {
                MenuItem carpeta = sub.addItem(s.nombre());
                boolean adentro = llenar(carpeta.getSubMenu(), s, camino + " › " + s.nombre(), actual, opciones);
                if (s.hijos().isEmpty()) carpeta.setEnabled(false);
                if (adentro) marcar(carpeta);
                contiene |= adentro;
            }
        }
        return contiene;
    }

    /** V49n: opción que todavía no está en OPTIMIST: en gris; al tocarla, el aviso. No va al buscador. */
    private static void pendiente(SubMenu sub, String texto) {
        MenuItem mi = sub.addItem(texto, e -> avisoPendiente(texto));
        mi.getElement().getStyle().set("color", "var(--lumo-disabled-text-color)").set("font-style", "italic");
        mi.getElement().setAttribute("title", texto + ": " + OptimistMenu.avisoPendiente());
    }

    private static void avisoPendiente(String texto) {
        com.vaadin.flow.component.notification.Notification.show(texto + ": " + OptimistMenu.avisoPendiente() + ".", 4000,
                com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
    }

    private static boolean hoja(SubMenu sub, String texto, String ruta, String camino, String actual, List<Opcion> opciones) {
        MenuItem mi = sub.addItem(texto, e -> UI.getCurrent().navigate(ruta));
        opciones.add(new Opcion(texto, camino, ruta));
        boolean es = actual != null && actual.equalsIgnoreCase(ruta);
        if (es) marcar(mi);
        return es;
    }

    /** La pantalla actual y las carpetas que la contienen van en negrita y con el color principal. */
    private static void marcar(MenuItem mi) {
        mi.getElement().getStyle().set("font-weight", "700").set("color", "var(--optimist-acento)");
    }

    /** V46: alto de la barra del menú horizontal ("0px" con el vertical): los formularios en pantalla completa arrancan debajo. */
    private static void altoBarra(String alto) {
        UI.getCurrent().getPage().executeJs("document.documentElement.style.setProperty('--optimist-menu-alto', $0)", alto);
    }

    /** V21: carpeta del menú (categoría o subgrupo) con sus botones y subcarpetas. */
    private record Grupo(String ruta, Details panel, VerticalLayout contenido, List<Button> items, List<Grupo> hijos) {
        void agregar(Button b) { contenido.add(b); items.add(b); }
        void agregar(Grupo g) { contenido.add(g.panel()); hijos.add(g); }
        /** V24: cierra esta carpeta y todas las de adentro (y las olvida en la sesión). */
        void cerrar() {
            panel.setOpened(false);
            carpetasAbiertas().remove(ruta);
            hijos.forEach(Grupo::cerrar);
        }
        /** Devuelve si quedó visible. padreCoincide: el nombre de una carpeta superior coincide con la búsqueda. */
        boolean filtrar(String q, boolean padreCoincide) {
            boolean todo = q.isBlank() || padreCoincide || panel.getSummaryText().toLowerCase(Locale.ROOT).contains(q);
            boolean alguno = false;
            for (Button b : items) {
                boolean m = todo || b.getText().toLowerCase(Locale.ROOT).contains(q);
                b.setVisible(m); alguno |= m;
            }
            for (Grupo h : hijos) alguno |= h.filtrar(q, todo && !q.isBlank());
            boolean visible = q.isBlank() || todo || alguno;
            panel.setVisible(visible);
            if (!q.isBlank() && visible) panel.setOpened(true);
            return visible;
        }
    }

    /**
     * Arma una carpeta del menú (recursivo). Cada carpeta recuerda si el usuario la dejó abierta
     * (en la sesión), así al navegar el menú queda como estaba.
     * V24: acordeón. Al abrir una carpeta se cierran sus hermanas (las del mismo nivel), así queda
     * una sola rama abierta. 'hermanas' es la lista donde el padre va juntando sus subcarpetas.
     */
    private static Grupo armar(OptimistMenu.Carpeta c, int nivel, String padre, List<Grupo> hermanas) {
        String ruta = padre + "/" + c.nombre();
        VerticalLayout contenido = new VerticalLayout(); contenido.setPadding(false); contenido.setSpacing(false);
        Details d = new Details(c.nombre(), contenido); d.setWidthFull();
        d.getStyle().set("color", "var(--optimist-texto)");
        if (nivel > 0) d.getStyle().set("padding-left", "14px").set("font-size", "0.95em");
        Set<String> abiertos = carpetasAbiertas();
        d.setOpened(abiertos.contains(ruta));
        Grupo g = new Grupo(ruta, d, contenido, new ArrayList<>(), new ArrayList<>());
        d.addOpenedChangeListener(e -> {
            if (!e.isFromClient()) return;   // lo que abre el buscador no se recuerda
            if (e.isOpened()) {
                carpetasAbiertas().add(ruta);
                for (Grupo h : hermanas) if (h != g) h.cerrar();
            } else g.cerrar();
        });
        for (Object h : c.hijos()) {
            if (h instanceof OptimistMenu.Item it && it.pendiente()) {   // V49n: en gris; al tocarla, el aviso
                Button b = new Button(it.titulo(), e -> avisoPendiente(it.titulo()));
                b.addClassName("optimist-menu-opcion");
                b.setWidthFull();
                b.getStyle().set("justify-content", "flex-start").set("background", "transparent")
                        .set("color", "var(--lumo-disabled-text-color)").set("font-style", "italic");
                b.getElement().setAttribute("title", it.titulo() + ": " + OptimistMenu.avisoPendiente());
                g.agregar(b);
            }
            else if (h instanceof OptimistMenu.Item it) g.agregar(item(it.titulo(), it.ruta()));
            else if (h instanceof OptimistMenu.Carpeta sub) g.agregar(armar(sub, nivel + 1, ruta, g.hijos()));
        }
        return g;
    }

    /** Carpetas del menú abiertas por el usuario en esta sesión (arranca todo cerrado). */
    @SuppressWarnings("unchecked")
    private static Set<String> carpetasAbiertas() {
        VaadinSession s = VaadinSession.getCurrent();
        Object v = s.getAttribute("optimist.menu.abiertas");
        if (v instanceof Set<?> set) return (Set<String>) set;
        Set<String> nuevo = java.util.concurrent.ConcurrentHashMap.newKeySet();
        s.setAttribute("optimist.menu.abiertas", nuevo);
        return nuevo;
    }

    /** V29: ancho del menú guardado por el usuario (220..600), 330 por defecto. */
    private static int anchoGuardado(java.util.Properties cfg) {
        try { return Math.max(220, Math.min(600, Integer.parseInt(cfg.getProperty("menuAncho", "330").trim()))); }
        catch (Exception e) { return 330; }
    }

    /** V21: los formularios en pantalla completa se ubican a la derecha de este ancho. */
    private static void anchoMenu(String ancho) {
        UI.getCurrent().getPage().executeJs("document.documentElement.style.setProperty('--optimist-menu-ancho', $0)", ancho);
    }

    private static Button item(String texto, String ruta) {
        Button b = new Button(texto, e -> UI.getCurrent().navigate(ruta));
        b.addClassName("optimist-menu-opcion");
        b.setWidthFull(); b.getStyle().set("justify-content", "flex-start");
        b.getStyle().set("color", "var(--optimist-texto)").set("background", "transparent");
        // V24: la pantalla actual va recuadrada, con el mismo borde que el recuadro del WorkWith
        String actual = rutaActual();
        if (actual != null && actual.equalsIgnoreCase(ruta)) {
            // V25: recuadro ceñido al texto (~2 mm a los costados, ~1 mm arriba y abajo), alineado a la izquierda
            b.setWidth(null);
            b.getStyle().set("border", "1px solid var(--optimist-acento)").set("border-radius", "6px").set("font-weight", "600")
                    .set("padding", "4px 8px").set("height", "auto").set("min-height", "0")
                    .set("margin", "2px 0 2px 6px").set("align-self", "flex-start");
        }
        return b;
    }

    /**
     * V48e: permisos del menú (MenuSecUser). Si el usuario escribe en el navegador la dirección de una opción del menú
     * que no tiene asignada, ve el aviso en lugar de la pantalla. Solo se controla al entrar escribiendo la dirección
     * (o al refrescar): lo que se abre desde otra pantalla (ej. el PED desde "Generar Pedido" del PRS) pasa siempre,
     * igual que en GeneXus, donde el permiso es del menú y no del programa.
     */
    static boolean sinPermiso(VerticalLayout vista, com.vaadin.flow.router.BeforeEnterEvent e, String ruta) {
        if (e.getTrigger() != com.vaadin.flow.router.NavigationTrigger.PAGE_LOAD) return false;
        if (OptimistMenu.permitida(ruta)) return false;
        Span aviso = new Span("No tenés permiso para entrar a esta opción del menú.");
        aviso.getStyle().set("padding", "28px").set("color", "#ff8585");
        montar(vista, aviso, "Sin permiso");
        return true;
    }

    /** V24: cada vista avisa su ruta antes de montar el shell, para marcar el ítem del menú. */
    public static void rutaActual(String ruta) {
        ComponentUtil.setData(UI.getCurrent(), "optimist.ruta", ruta == null ? null : ruta.replaceAll("^/+", ""));
    }

    /** V48: la ruta de la pantalla montada (para el botón Ayuda; rutaActual() se borra al armar el menú). */
    static String rutaPantalla() {
        Object v = ComponentUtil.getData(UI.getCurrent(), "optimist.ayuda.ruta");
        return v == null ? null : v.toString();
    }

    static String rutaActual() {
        Object v = ComponentUtil.getData(UI.getCurrent(), "optimist.ruta");
        return v == null ? null : v.toString();
    }
}
