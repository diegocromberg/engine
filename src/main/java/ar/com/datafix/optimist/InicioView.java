package ar.com.datafix.optimist;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

/** Pantalla principal. No abre ninguna TRN automáticamente. */
@Route("")
public class InicioView extends VerticalLayout implements com.vaadin.flow.router.BeforeEnterObserver {
    public InicioView() {
        // V53: sin sesión no se arma nada: beforeEnter manda al login (con el ?CK= de la dirección)
        if (OptimistShell.usuario() == null || OptimistContexto.actual() == null) return;
        setPadding(false); setSpacing(false); setSizeFull(); setMinHeight("100vh");

        Div bienvenida = new Div();
        bienvenida.setSizeFull();
        bienvenida.getStyle()
            .set("min-height", "100vh")
            .set("display", "flex")
            .set("align-items", "center")
            .set("justify-content", "center")
            .set("box-sizing", "border-box");

        Image logo = new Image("images/Logo-OPTIMIST-Software-con-recuadro-AZUL-727x80.png", "OPTIMIST SOFTWARE");
        logo.setWidth("min(727px, 72vw)");
        logo.getStyle().set("height", "auto");
        bienvenida.add(logo);

        OptimistShell.rutaActual("");
        OptimistShell.montar(this, bienvenida);
    }

    @Override
    public void beforeEnter(com.vaadin.flow.router.BeforeEnterEvent e) {
        if (OptimistShell.usuario() == null || OptimistContexto.actual() == null) OptimistShell.alLogin(e);
    }
}
