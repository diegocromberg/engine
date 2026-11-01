package ar.com.datafix.optimist;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.*;
import java.util.*;

/**
 * E5a - El panel de Seguridad del motor (Diego, 01/11/2026), como el de WorkWithPlus: los permisos de cada rol (árbol TRN →
 * entrar, agregar, modificar, eliminar, campos; web panels; consultas; claves propias), los roles de cada usuario y el botón
 * "Actualizar permisos" (da de alta las claves nuevas y se las asigna al rol administrador; OptimistPermisos.actualizar).
 * En el menú: {titulo: Seguridad, ruta: seguridad}. Entra el rol administrador o quien tenga la clave OptimistSeguridad.
 * Trabaja con las tablas de la seguridad avanzada de WorkWithPlus (permisos.tipo: wwp).
 */
@Route("seguridad")
public class OptimistSeguridadView extends VerticalLayout implements BeforeEnterObserver {
    private final OptimistDatabase db;

    public OptimistSeguridadView(@Autowired OptimistDatabase db) { this.db = db; }

    /** Un renglón de SecFunctionality. */
    record Func(long id, String clave, String descripcion, int tipo, Long padre) {}
    record Rol(long id, String nombre, String descripcion) {}
    record Usuario(Object id, String nombre) {}

    private List<Rol> roles = new ArrayList<>();
    private Rol rolActual;
    private final Set<Long> marcadas = new HashSet<>();
    private Set<Long> originales = new HashSet<>();
    private final Grid<Rol> grillaRoles = new Grid<>();
    private final TreeGrid<Func> arbol = new TreeGrid<>();
    private TreeData<Func> datosArbol = new TreeData<>();
    private final Map<Long, Func> funcs = new LinkedHashMap<>();
    private final Span tituloPermisos = new Span();
    private final Button guardarPermisos = new Button("Guardar permisos");

    @Override
    public void beforeEnter(BeforeEnterEvent e) {
        if (OptimistShell.usuario() == null) { OptimistShell.alLogin(e); return; }
        OptimistShell.rutaActual("seguridad");
        if (!OptimistPermisos.activo() || !OptimistPermisos.config().tipo().equals("wwp")) {
            aviso("La aplicación no maneja permisos con las tablas de WorkWithPlus (seguridad.yaml: permisos: {tipo: wwp}; guía 24.3).");
            return;
        }
        if (!OptimistPermisos.puedeSeguridad()) { aviso("No tenés permiso para entrar a Seguridad."); return; }
        try { armar(); }
        catch (SQLException | RuntimeException ex) { aviso("No se pudo abrir Seguridad: " + ex.getMessage()); }
    }

    private void aviso(String texto) {
        Span s = new Span(texto);
        s.getStyle().set("padding", "28px").set("color", "#ff8585");
        OptimistShell.montar(this, s, "Seguridad");
    }

    // ------------------------------------------------------------------ pantalla

    private void armar() throws SQLException {
        Button actualizar = new Button("Actualizar permisos", VaadinIcon.REFRESH.create(), e -> confirmar(
                "¿Confirmás Actualizar permisos?",
                "Da de alta las funcionalidades nuevas (TRN, campos, web panels, consultas, acciones) y se las asigna al rol "
                        + OptimistPermisos.config().rolAdministrador() + ". No borra nada.", this::actualizar));
        actualizar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        actualizar.getElement().setAttribute("title", "Como SecFunctionalitiesToLoad de WorkWithPlus: después de agregar TRN o funcionalidades");
        HorizontalLayout barra = new HorizontalLayout(new H3("Seguridad"), actualizar);
        barra.setAlignItems(FlexComponent.Alignment.CENTER);

        TabSheet solapas = new TabSheet();
        solapas.add("Roles y permisos", panelRoles());
        solapas.add("Usuarios", panelUsuarios());
        solapas.setWidthFull();

        VerticalLayout cont = new VerticalLayout(barra, solapas);
        cont.setPadding(true);
        cont.setSizeFull();
        OptimistShell.montar(this, cont, "Seguridad");
        leerFuncionalidades();
        leerRoles();
    }

    private Component panelRoles() {
        grillaRoles.addColumn(Rol::nombre).setHeader("Rol").setAutoWidth(true).setFlexGrow(1);
        grillaRoles.addColumn(Rol::descripcion).setHeader("Descripción").setAutoWidth(true).setFlexGrow(2);
        grillaRoles.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grillaRoles.setWidth("380px");
        grillaRoles.setHeight("min(60vh, 560px)");
        grillaRoles.addSelectionListener(e -> elegirRol(e.getFirstSelectedItem().orElse(null)));
        Button nuevo = new Button("Nuevo rol", VaadinIcon.PLUS.create(), e -> editarRol(null));
        Button cambiar = new Button("Cambiar", VaadinIcon.PENCIL.create(), e -> { if (rolActual != null) editarRol(rolActual); });
        Button borrar = new Button("Eliminar", VaadinIcon.CLOSE.create(), e -> {
            if (rolActual == null) return;
            if (rolActual.nombre().equalsIgnoreCase(OptimistPermisos.config().rolAdministrador())) {
                Notification.show("El rol administrador no se elimina.", 5000, Notification.Position.MIDDLE);
                return;
            }
            Rol r = rolActual;
            confirmar("¿Confirmás eliminar el rol " + r.nombre() + "?", "Se le quitan sus permisos y se lo saca a los usuarios que lo tienen.", () -> eliminarRol(r));
        });
        VerticalLayout izq = new VerticalLayout(new HorizontalLayout(nuevo, cambiar, borrar), grillaRoles);
        izq.setPadding(false);
        izq.setWidth(null);

        arbol.addComponentHierarchyColumn(f -> {
            Checkbox c = new Checkbox(marcadas.contains(f.id()));
            c.setEnabled(rolActual != null);
            c.addValueChangeListener(ev -> { if (ev.isFromClient()) marcar(f, ev.getValue()); });
            Span t = new Span(f.descripcion() == null || f.descripcion().isBlank() ? f.clave() : f.descripcion());
            HorizontalLayout h = new HorizontalLayout(c, t);
            h.setSpacing(true);
            h.setAlignItems(FlexComponent.Alignment.CENTER);
            return h;
        }).setHeader("Permiso").setAutoWidth(true).setFlexGrow(3);
        arbol.addColumn(Func::clave).setHeader("Clave").setAutoWidth(true).setFlexGrow(2);
        arbol.addColumn(f -> switch (f.tipo()) { case 1 -> "Modo"; case 2 -> "Acción"; case 3 -> "Solapa"; case 4 -> "Objeto"; case 5 -> "Campo"; default -> ""; })
                .setHeader("Tipo").setAutoWidth(true);
        arbol.addThemeVariants(GridVariant.LUMO_COMPACT);
        arbol.setHeight("min(60vh, 560px)");
        arbol.setWidthFull();
        TextField buscar = new TextField();
        buscar.setPlaceholder("Buscar permiso…");
        buscar.setClearButtonVisible(true);
        buscar.setValueChangeMode(ValueChangeMode.LAZY);
        buscar.addValueChangeListener(e -> filtrar(e.getValue()));
        Button todo = new Button("Tildar todo", e -> { if (rolActual != null) { marcadas.addAll(funcs.keySet()); refrescarArbol(); } });
        Button nada = new Button("Destildar todo", e -> { if (rolActual != null) { marcadas.clear(); refrescarArbol(); } });
        guardarPermisos.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        guardarPermisos.addClickListener(e -> guardarPermisos());
        guardarPermisos.setEnabled(false);
        tituloPermisos.getStyle().set("font-weight", "600");
        HorizontalLayout arribaDer = new HorizontalLayout(tituloPermisos, buscar, todo, nada, guardarPermisos);
        arribaDer.setAlignItems(FlexComponent.Alignment.BASELINE);
        arribaDer.getStyle().set("flex-wrap", "wrap");
        VerticalLayout der = new VerticalLayout(arribaDer, arbol);
        der.setPadding(false);
        der.setWidthFull();
        HorizontalLayout h = new HorizontalLayout(izq, der);
        h.setWidthFull();
        return h;
    }

    private Component panelUsuarios() {
        Grid<Usuario> usuarios = new Grid<>();
        usuarios.addColumn(Usuario::nombre).setHeader("Usuario").setAutoWidth(true);
        usuarios.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        usuarios.setWidth("380px");
        usuarios.setHeight("min(60vh, 560px)");
        TextField buscar = new TextField();
        buscar.setPlaceholder("Buscar usuario…");
        buscar.setClearButtonVisible(true);
        buscar.setValueChangeMode(ValueChangeMode.LAZY);
        CheckboxGroup<Rol> rolesUsuario = new CheckboxGroup<>();
        rolesUsuario.setItemLabelGenerator(r -> r.nombre() + (r.descripcion() == null || r.descripcion().isBlank() || r.descripcion().equals(r.nombre()) ? "" : " — " + r.descripcion()));
        rolesUsuario.getStyle().set("display", "flex").set("flex-direction", "column");
        rolesUsuario.setEnabled(false);
        Span titulo = new Span("Elegí un usuario");
        titulo.getStyle().set("font-weight", "600");
        Button guardar = new Button("Guardar roles");
        guardar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        guardar.setEnabled(false);
        List<Usuario> todos = new ArrayList<>();
        try { todos.addAll(leerUsuarios()); } catch (SQLException e) { Notification.show("Usuarios: " + e.getMessage(), 8000, Notification.Position.MIDDLE); }
        usuarios.setItems(todos);
        buscar.addValueChangeListener(e -> {
            String t = e.getValue().trim().toLowerCase(Locale.ROOT);
            usuarios.setItems(todos.stream().filter(u -> t.isEmpty() || u.nombre().toLowerCase(Locale.ROOT).contains(t)).toList());
        });
        usuarios.addSelectionListener(e -> {
            Usuario u = e.getFirstSelectedItem().orElse(null);
            rolesUsuario.setItems(roles);
            rolesUsuario.setEnabled(u != null);
            guardar.setEnabled(u != null);
            titulo.setText(u == null ? "Elegí un usuario" : "Roles de " + u.nombre());
            if (u == null) return;
            try {
                Set<Long> tiene = rolesDe(u);
                rolesUsuario.setValue(new HashSet<>(roles.stream().filter(r -> tiene.contains(r.id())).toList()));
            } catch (SQLException ex) { Notification.show(ex.getMessage(), 8000, Notification.Position.MIDDLE); }
        });
        guardar.addClickListener(e -> {
            Usuario u = null;
            for (Usuario x : usuarios.getSelectedItems()) { u = x; break; }
            if (u == null) return;
            List<Long> ids = new ArrayList<>();
            for (Rol r : rolesUsuario.getValue()) ids.add(r.id());
            try {
                guardarRoles(u, ids);
                Notification.show("Roles de " + u.nombre() + " guardados.", 3000, Notification.Position.BOTTOM_START);
            } catch (SQLException ex) { Notification.show("No se guardó: " + ex.getMessage(), 10000, Notification.Position.MIDDLE); }
        });
        VerticalLayout izq = new VerticalLayout(buscar, usuarios);
        izq.setPadding(false);
        izq.setWidth(null);
        VerticalLayout der = new VerticalLayout(new HorizontalLayout(titulo, guardar), rolesUsuario);
        der.setPadding(false);
        HorizontalLayout h = new HorizontalLayout(izq, der);
        h.setWidthFull();
        return h;
    }

    // ------------------------------------------------------------------ roles y permisos

    private void leerFuncionalidades() throws SQLException {
        funcs.clear();
        try (Connection cn = db.openConnection(); Statement s = cn.createStatement(); ResultSet r = s.executeQuery(
                "SELECT SecFunctionalityId, SecFunctionalityKey, SecFunctionalityDescription, SecFunctionalityType, SecParentFunctionalityId"
                        + " FROM SecFunctionality ORDER BY SecFunctionalityDescription, SecFunctionalityKey")) {
            while (r.next()) {
                long p = r.getLong(5);
                Long padre = r.wasNull() || p == 0 ? null : p;
                funcs.put(r.getLong(1), new Func(r.getLong(1), trim(r.getString(2)), trim(r.getString(3)), r.getInt(4), padre));
            }
        }
        armarArbol(null);
    }

    private void armarArbol(String filtro) {
        datosArbol = new TreeData<>();
        String t = filtro == null ? "" : filtro.trim().toLowerCase(Locale.ROOT);
        Set<Long> mostrar = new HashSet<>();
        for (Func f : funcs.values()) {
            if (!t.isEmpty() && !(f.clave() + " " + f.descripcion()).toLowerCase(Locale.ROOT).contains(t)) continue;
            // la que coincide, con sus padres
            for (Func x = f; x != null && mostrar.add(x.id()); x = x.padre() == null ? null : funcs.get(x.padre())) { }
        }
        // los padres antes que los hijos (un padre que no está, o un ciclo: queda arriba)
        Set<Long> puestos = new HashSet<>();
        boolean cambio = true;
        while (cambio) {
            cambio = false;
            for (Func f : funcs.values()) {
                if (!mostrar.contains(f.id()) || puestos.contains(f.id())) continue;
                Func padre = f.padre() == null ? null : funcs.get(f.padre());
                if (padre != null && padre.id() != f.id() && mostrar.contains(padre.id()) && !puestos.contains(padre.id())) continue;
                datosArbol.addItem(padre != null && puestos.contains(padre.id()) ? padre : null, f);
                puestos.add(f.id());
                cambio = true;
            }
        }
        arbol.setDataProvider(new TreeDataProvider<>(datosArbol));
        if (!t.isEmpty()) arbol.expandRecursively(datosArbol.getRootItems(), 3);
    }

    private void filtrar(String texto) { armarArbol(texto); }

    private void marcar(Func f, boolean si) {
        // tildar o destildar un permiso hace lo mismo con todo lo que tiene adentro (ej. la TRN: sus modos y campos)
        Deque<Func> pila = new ArrayDeque<>(List.of(f));
        while (!pila.isEmpty()) {
            Func x = pila.pop();
            if (si) marcadas.add(x.id()); else marcadas.remove(x.id());
            for (Func h : funcs.values()) if (Objects.equals(h.padre(), x.id()) && h.id() != x.id()) pila.push(h);
        }
        refrescarArbol();
    }

    private void refrescarArbol() {
        arbol.getDataProvider().refreshAll();
        guardarPermisos.setEnabled(rolActual != null && !marcadas.equals(originales));
    }

    private void leerRoles() throws SQLException {
        roles = new ArrayList<>();
        try (Connection cn = db.openConnection(); Statement s = cn.createStatement();
             ResultSet r = s.executeQuery("SELECT SecRoleId, SecRoleName, SecRoleDescription FROM SecRole ORDER BY SecRoleName")) {
            while (r.next()) roles.add(new Rol(r.getLong(1), trim(r.getString(2)), trim(r.getString(3))));
        }
        grillaRoles.setItems(roles);
        Rol antes = rolActual;
        elegirRol(antes == null ? null : roles.stream().filter(x -> x.id() == antes.id()).findFirst().orElse(null));
        if (rolActual != null) grillaRoles.select(rolActual);
    }

    private void elegirRol(Rol r) {
        rolActual = r;
        marcadas.clear();
        if (r != null) {
            try (Connection cn = db.openConnection();
                 PreparedStatement p = cn.prepareStatement("SELECT SecFunctionalityId FROM SecFunctionalityRole WHERE SecRoleId = ?")) {
                p.setLong(1, r.id());
                try (ResultSet x = p.executeQuery()) { while (x.next()) marcadas.add(x.getLong(1)); }
            } catch (SQLException e) { Notification.show(e.getMessage(), 8000, Notification.Position.MIDDLE); }
        }
        originales = new HashSet<>(marcadas);
        tituloPermisos.setText(r == null ? "Elegí un rol" : "Permisos de " + r.nombre());
        refrescarArbol();
    }

    private void guardarPermisos() {
        if (rolActual == null) return;
        Set<Long> quitar = new HashSet<>(originales); quitar.removeAll(marcadas);
        Set<Long> poner = new HashSet<>(marcadas); poner.removeAll(originales);
        try (Connection cn = db.openConnection()) {
            cn.setAutoCommit(false);
            try {
                try (PreparedStatement d = cn.prepareStatement("DELETE FROM SecFunctionalityRole WHERE SecRoleId = ? AND SecFunctionalityId = ?")) {
                    for (Long id : quitar) { d.setLong(1, rolActual.id()); d.setLong(2, id); d.addBatch(); }
                    d.executeBatch();
                }
                try (PreparedStatement i = cn.prepareStatement("INSERT INTO SecFunctionalityRole (SecFunctionalityId, SecRoleId) VALUES (?, ?)")) {
                    for (Long id : poner) { i.setLong(1, id); i.setLong(2, rolActual.id()); i.addBatch(); }
                    i.executeBatch();
                }
                cn.commit();
            } catch (SQLException e) { cn.rollback(); throw e; }
            OptimistPermisos.releer(cn);
            originales = new HashSet<>(marcadas);
            refrescarArbol();
            Notification.show("Permisos de " + rolActual.nombre() + " guardados (" + poner.size() + " agregados, " + quitar.size() + " quitados).",
                    4000, Notification.Position.BOTTOM_START);
        } catch (SQLException e) { Notification.show("No se guardó: " + e.getMessage(), 10000, Notification.Position.MIDDLE); }
    }

    private void editarRol(Rol r) {
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setHeaderTitle(r == null ? "Nuevo rol" : "Rol " + r.nombre());
        TextField nombre = new TextField("Nombre");
        nombre.setMaxLength(20);
        nombre.setRequired(true);
        TextField desc = new TextField("Descripción");
        desc.setMaxLength(120);
        desc.setWidth("360px");
        if (r != null) { nombre.setValue(r.nombre()); desc.setValue(r.descripcion() == null ? "" : r.descripcion()); }
        d.add(new VerticalLayout(nombre, desc));
        Button ok = new Button("Confirmar", e -> {
            String n = nombre.getValue().trim();
            if (n.isEmpty()) { nombre.setInvalid(true); nombre.setErrorMessage("Falta el nombre"); return; }
            try (Connection cn = db.openConnection()) {
                if (r == null) {
                    try (PreparedStatement p = cn.prepareStatement("INSERT INTO SecRole (SecRoleName, SecRoleDescription) VALUES (?, ?)")) {
                        p.setString(1, n); p.setString(2, desc.getValue().trim()); p.executeUpdate();
                    }
                } else try (PreparedStatement p = cn.prepareStatement("UPDATE SecRole SET SecRoleName = ?, SecRoleDescription = ? WHERE SecRoleId = ?")) {
                    p.setString(1, n); p.setString(2, desc.getValue().trim()); p.setLong(3, r.id()); p.executeUpdate();
                }
                d.close();
                leerRoles();
            } catch (SQLException ex) { Notification.show("No se guardó: " + ex.getMessage(), 10000, Notification.Position.MIDDLE); }
        });
        ok.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        OptimistVisuals.pieIzquierda(d, ok, new Button("Cancelar", e -> d.close()));
        d.open();
        nombre.focus();
    }

    private void eliminarRol(Rol r) {
        try (Connection cn = db.openConnection()) {
            cn.setAutoCommit(false);
            try {
                for (String sql : List.of("DELETE FROM SecFunctionalityRole WHERE SecRoleId = ?", "DELETE FROM SecUserRole WHERE SecRoleId = ?",
                        "DELETE FROM SecRole WHERE SecRoleId = ?"))
                    try (PreparedStatement p = cn.prepareStatement(sql)) { p.setLong(1, r.id()); p.executeUpdate(); }
                cn.commit();
            } catch (SQLException e) { cn.rollback(); throw e; }
            OptimistPermisos.releer(cn);
            rolActual = null;
            leerRoles();
        } catch (SQLException e) { Notification.show("No se eliminó: " + e.getMessage(), 10000, Notification.Position.MIDDLE); }
    }

    // ------------------------------------------------------------------ usuarios

    private List<Usuario> leerUsuarios() throws SQLException {
        OptimistUsuarios.Config c = OptimistUsuarios.config();
        List<Usuario> r = new ArrayList<>();
        try (Connection cn = db.openConnection(); Statement s = cn.createStatement();
             ResultSet x = s.executeQuery("SELECT " + c.id() + ", " + c.nombre() + " FROM " + c.tabla() + " ORDER BY " + c.nombre())) {
            while (x.next()) r.add(new Usuario(x.getObject(1), trim(x.getString(2))));
        }
        return r;
    }

    private Set<Long> rolesDe(Usuario u) throws SQLException {
        Set<Long> r = new HashSet<>();
        try (Connection cn = db.openConnection(); PreparedStatement p = cn.prepareStatement("SELECT SecRoleId FROM SecUserRole WHERE SecUserId = ?")) {
            p.setObject(1, u.id());
            try (ResultSet x = p.executeQuery()) { while (x.next()) r.add(x.getLong(1)); }
        }
        return r;
    }

    private void guardarRoles(Usuario u, List<Long> nuevos) throws SQLException {
        try (Connection cn = db.openConnection()) {
            cn.setAutoCommit(false);
            try {
                try (PreparedStatement p = cn.prepareStatement("DELETE FROM SecUserRole WHERE SecUserId = ?")) { p.setObject(1, u.id()); p.executeUpdate(); }
                try (PreparedStatement p = cn.prepareStatement("INSERT INTO SecUserRole (SecUserId, SecRoleId) VALUES (?, ?)")) {
                    for (Long id : nuevos) { p.setObject(1, u.id()); p.setLong(2, id); p.addBatch(); }
                    p.executeBatch();
                }
                cn.commit();
            } catch (SQLException e) { cn.rollback(); throw e; }
            OptimistPermisos.releer(cn);
        }
    }

    // ------------------------------------------------------------------ Actualizar permisos

    private void actualizar() {
        try (Connection cn = db.openConnection()) {
            OptimistPermisos.Actualizacion a = OptimistPermisos.actualizar(cn);
            OptimistPermisos.releer(cn);
            leerFuncionalidades();
            leerRoles();
            String rol = OptimistPermisos.config().rolAdministrador();
            Notification.show(a.nuevas().isEmpty() && a.asignadasAlAdministrador() == 0 ? "Los permisos ya estaban al día."
                    : a.nuevas().size() + " funcionalidad" + (a.nuevas().size() == 1 ? "" : "es") + " nueva" + (a.nuevas().size() == 1 ? "" : "s")
                    + (a.rolCreado() ? "; se creó el rol " + rol : "") + "; " + a.asignadasAlAdministrador() + " asignada"
                    + (a.asignadasAlAdministrador() == 1 ? "" : "s") + " al rol " + rol + ".", 8000, Notification.Position.MIDDLE);
        } catch (SQLException | RuntimeException e) { Notification.show("No se pudo actualizar: " + e.getMessage(), 12000, Notification.Position.MIDDLE); }
    }

    private void confirmar(String pregunta, String detalle, Runnable si) {
        ConfirmDialog c = new ConfirmDialog();
        c.setHeader(pregunta);
        c.setText(detalle);
        c.setCancelable(true);
        c.setCancelText("Cancelar");
        c.setConfirmText("Confirmar");
        c.addConfirmListener(e -> si.run());
        c.open();
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
