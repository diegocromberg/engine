package ar.com.datafix.optimist;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * V53 - Instalación única: el código de cliente llega en la dirección (https://servidor/OPTIMIST/?CK=<UUID>), en
 * cualquier pantalla. Se anota en la sesión antes de abrir la pantalla, así no se pierde cuando el sistema manda al
 * login. Si la sesión ya estaba abierta con OTRO cliente, se cierra (hay que volver a ingresar en el cliente nuevo).
 */
@Component
public class OptimistCodigoCliente implements VaadinServiceInitListener {

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(ui -> OptimistVisorPdf.registrar(ui.getUI()));   // V84h: Atrás con un PDF abierto
        event.getSource().addUIInitListener(ui -> ui.getUI().addBeforeEnterListener(be -> {
            // V65l: Atrás / Adelante / vínculo / menú: las ventanas abiertas se cierran. V84h: salvo si el Atrás cerró una sola
            // ventana y se vuelve a la misma pantalla (las de abajo quedan)
            if (!OptimistVisorPdf.volviendoDePdf()) OptimistVisuals.cerrarVentanas(ui.getUI());
            String ck = codigo(be.getLocation().getQueryParameters().getParameters());
            if (ck == null) return;
            VaadinSession s = VaadinSession.getCurrent();
            if (s == null) return;
            Object actual = s.getAttribute(OptimistDatabase.ATRIBUTO_CLIENTE);
            if (actual instanceof OptimistDatabase.Cliente c && !c.uuid().equalsIgnoreCase(ck)) {
                // otro cliente: se cierra la sesión del anterior
                s.setAttribute("optimist.usuario", null);
                s.setAttribute(OptimistContexto.ATRIBUTO, null);
                s.setAttribute(OptimistDatabase.ATRIBUTO_CLIENTE, null);
            }
            s.setAttribute(OptimistDatabase.ATRIBUTO_CK, ck);
        }));
    }

    /** El ?CK= (o ?ck=) de la dirección, o null. */
    static String codigo(Map<String, List<String>> params) {
        for (Map.Entry<String, List<String>> e : params.entrySet())
            if (e.getKey().equalsIgnoreCase("CK") && e.getValue() != null && !e.getValue().isEmpty()) {
                String v = e.getValue().get(0);
                if (v != null && !v.isBlank()) return v.trim().toLowerCase(Locale.ROOT);
            }
        return null;
    }
}
