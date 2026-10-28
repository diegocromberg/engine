package ar.com.datafix.optimist;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.server.StreamRegistration;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.server.VaadinSession;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V49 - Muestra el PDF de una impresión (ej. Imprimir Presupuesto) con el visor del navegador (imprimir, descargar, zoom).
 * V49e: en la misma ventana desde donde se imprimió, ocupando toda la zona de trabajo (como un formulario en pantalla
 * completa), al ancho de la página. Sin pestañas nuevas ni ventanas chicas que haya que agrandar.
 */
public final class OptimistVisorPdf {
    private OptimistVisorPdf() {}

    public static void abrir(String titulo, String nombreArchivo, byte[] pdf) {
        // V66: lo que armó el procedimiento no es un PDF (el Excel de los Libros de IVA): se baja, como el Excel del WorkWith
        if (nombreArchivo.toLowerCase().endsWith(".xlsx")) { bajar(nombreArchivo, pdf); return; }
        String archivo = nombreArchivo.toLowerCase().endsWith(".pdf") ? nombreArchivo : nombreArchivo + ".pdf";
        StreamResource recurso = new StreamResource(archivo, () -> new ByteArrayInputStream(pdf));
        recurso.setContentType("application/pdf");
        recurso.setHeader("Content-Disposition", "inline; filename=\"" + archivo.replace("\"", "") + "\"");
        // V84h: el PDF sigue disponible después de cerrar la ventana (hasta el próximo): el lector de PDF del navegador (o una
        // extensión, ej. doqment PDF Reader) lo puede volver a pedir al ir Atrás y avisaba "Archivo PDF faltante"
        VaadinSession ses = VaadinSession.getCurrent();
        Object previo = ses.getAttribute(ULTIMO_PDF);
        if (previo instanceof StreamRegistration pr) { try { pr.unregister(); } catch (RuntimeException ignorado) { } }
        StreamRegistration reg = ses.getResourceRegistry().registerResource(recurso);
        ses.setAttribute(ULTIMO_PDF, reg);
        String url = reg.getResourceUri().toString();

        Dialog d = new Dialog();
        d.setHeaderTitle(titulo);
        OptimistVisuals.estilizarDialogo(d);
        OptimistVisuals.dimensionarFormulario(d, true, null);
        d.addThemeName("no-padding");
        OptimistVisuals.botonCerrar(d);   // V65
        IFrame visor = new IFrame(url + "#view=FitH&zoom=page-width");   // al ancho de la página (Chrome/Edge y Firefox)
        visor.setSizeFull();
        visor.getStyle().set("border", "none").set("display", "block").set("min-height", "calc(100vh - 150px)");
        d.add(visor);
        Button descargar = new Button("Descargar", e -> UI.getCurrent().getPage().executeJs(
                "const a=document.createElement('a');a.href=$0;a.download=$1;document.body.appendChild(a);a.click();a.remove();", url, archivo));
        Button cerrar = new Button("Cerrar", e -> d.close());
        cerrar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        OptimistVisuals.pieIzquierda(d, cerrar, descargar);
        d.addDialogCloseActionListener(e -> d.close());
        d.open();
        pasoAtras(d);   // V84h
    }

    /** V84h: el parámetro de la dirección que marca el paso del PDF en el historial (las pantallas lo ignoran). */
    static final String PARAM_PDF = "pdf";

    /**
     * V84h: "de A a B, al volver de B se vuelve a A", también con las ventanas (Diego: imprimía desde Comprobantes, tocaba
     * Atrás y en vez de cerrar el PDF iba a la pantalla anterior). Con una ventana abierta (un PDF, una lupa, un web panel en
     * ventana, el detalle de una lupa, la calculadora...), el Atrás del navegador (o del mouse) cierra SOLO la de más arriba
     * y se redirige (BeforeLeave, forwardTo) a la dirección en la que se estaba: la pantalla y las ventanas de abajo quedan
     * como estaban (página, filtros y orden de la lista; en un web panel, lo cargado y procesado).
     * - El formulario de una TRN tiene su propio paso (?abrir=): con él arriba, el Atrás navega como siempre y lo cierra.
     * - Con "Procesando…" arriba el Atrás no hace nada.
     * Lo que se probó antes y no sirvió: un paso propio del PDF en el historial (?pdf=1): Chrome saltea al ir Atrás el paso
     * de la pantalla cuando la página agrega uno sin un clic inmediato ("history manipulation intervention") e iba al inicio;
     * postergar la navegación y volver con history.forward(): la segunda vez la navegación postergada seguía pendiente.
     * La dirección en la que se estaba es la de la última navegación (AfterNavigation) o la del último registro abierto de
     * una TRN (ubicacion()): la que guarda Vaadin internamente puede quedar en la del ingreso.
     */
    private static void pasoAtras(Dialog d) { }

    /** V84h: lo llama OptimistCodigoCliente al crear cada UI (pestaña): la dirección actual y el Atrás con una ventana abierta. */
    static void registrar(UI ui) {
        ui.addAfterNavigationListener(ev -> com.vaadin.flow.component.ComponentUtil.setData(ui, UBICACION, ev.getLocation()));
        ui.addBeforeLeaveListener(ev -> {
            if (ev.getTrigger() != com.vaadin.flow.router.NavigationTrigger.HISTORY) return;   // menú, vínculo: se va (las ventanas se cierran solas)
            if (volviendoDePdf()) return;   // el "adelante" de la vuelta: no cierra otra ventana
            java.util.List<com.vaadin.flow.component.Component> abiertas = OptimistVisuals.ventanasAbiertas(ui);
            if (abiertas.isEmpty()) return;
            com.vaadin.flow.component.Component arriba = abiertas.get(abiertas.size() - 1);
            String marca = OptimistVisuals.marcaAtras(arriba);
            if ("formulario".equals(marca)) return;   // el registro de una TRN: su paso del historial lo cierra
            Object u = com.vaadin.flow.component.ComponentUtil.getData(ui, UBICACION);
            if (!(u instanceof com.vaadin.flow.router.Location aca)) return;
            if (!"fijo".equals(marca)) {
                if (arriba instanceof Dialog dl) dl.close();
                else if (arriba instanceof com.vaadin.flow.component.confirmdialog.ConfirmDialog cd) cd.close();
            }
            // la redirección y, si el navegador quedó en la dirección anterior, el "adelante" que lo devuelve: durante esos dos
            // pasos (3 segundos como mucho) las pantallas no se tocan y las ventanas de abajo no se cierran
            com.vaadin.flow.component.ComponentUtil.setData(ui, VOLVIENDO, System.currentTimeMillis());
            ev.forwardTo(aca.getPath(), aca.getQueryParameters());
            ui.getPage().executeJs("history.forward()");
        });
    }

    /** V84h: la dirección en la que se está, cuando cambia sin navegar (el registro de una TRN, con history.pushState). */
    static void ubicacion(com.vaadin.flow.router.Location loc) {
        UI ui = UI.getCurrent();
        if (ui != null && loc != null) com.vaadin.flow.component.ComponentUtil.setData(ui, UBICACION, loc);
    }

    /** V84h: se está volviendo a la misma pantalla después de cerrar una ventana con Atrás: la pantalla no se toca. */
    static boolean volviendoDePdf() {
        UI ui = UI.getCurrent();
        Object o = ui == null ? null : com.vaadin.flow.component.ComponentUtil.getData(ui, VOLVIENDO);
        return o instanceof Long desde && System.currentTimeMillis() - desde < 3000;
    }

    private static final String UBICACION = "optimist.ubicacion";
    private static final String VOLVIENDO = "optimist.volviendoDePdf";
    private static final String ULTIMO_PDF = "optimist.ultimoPdf";

    /** V66: baja un archivo (un .xlsx) en el navegador, sin ventana. */
    public static void bajar(String archivo, byte[] datos) {
        StreamResource recurso = new StreamResource(archivo, () -> new ByteArrayInputStream(datos));
        recurso.setContentType(archivo.toLowerCase().endsWith(".xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/octet-stream");
        recurso.setHeader("Content-Disposition", "attachment; filename=\"" + archivo.replace("\"", "") + "\"");
        VaadinSession ses = VaadinSession.getCurrent();
        // se guarda solo la última descarga, para no acumular archivos en la sesión (como el Excel del WorkWith)
        if (ses.getAttribute("optimist.descarga") instanceof StreamRegistration anterior) anterior.unregister();
        StreamRegistration reg = ses.getResourceRegistry().registerResource(recurso);
        ses.setAttribute("optimist.descarga", reg);
        UI.getCurrent().getPage().executeJs(
                "const a=document.createElement('a');a.href=$0;a.download=$1;document.body.appendChild(a);a.click();a.remove();",
                reg.getResourceUri().toString(), archivo);
    }
}
