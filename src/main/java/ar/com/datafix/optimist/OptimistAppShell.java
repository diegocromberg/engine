package ar.com.datafix.optimist;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.component.dependency.CssImport;

@Theme
@CssImport("./styles/optimist-global.css")
@CssImport(value = "./styles/optimist-grid.css", themeFor = "vaadin-grid")
@CssImport(value = "./styles/optimist-dialog.css", themeFor = "vaadin-dialog-overlay")
public class OptimistAppShell implements AppShellConfigurator {

    // V65l: sin script propio de Atrás: el historial es el del navegador (ver OptimistVisuals.cerrarVentanas)
}
