package ar.com.datafix.optimist;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.util.Properties;

/** Apariencia MAESTRA y persistente por usuario. Toda TRN debe usar esta clase. */
public final class OptimistApariencia {
    private final VerticalLayout vista,contenido,tarjeta; private final Grid<?> grilla; private final H1 titulo; private final Span marca; private final Button nuevo; private final ComboBox<Integer> filas; private final String anchoNormal;
    private final OptimistUserConfig store=new OptimistUserConfig(); private final String usuario; private Properties cfg;
    public OptimistApariencia(VerticalLayout vista,VerticalLayout contenido,VerticalLayout tarjeta,Grid<?> grilla,H1 titulo,Span marca,Button nuevo,ComboBox<Integer> filas,String anchoNormal){
        this.vista=vista;this.contenido=contenido;this.tarjeta=tarjeta;this.grilla=grilla;this.titulo=titulo;this.marca=marca;this.nuevo=nuevo;this.filas=filas;this.anchoNormal=anchoNormal;
        usuario=OptimistShell.usuario()==null?"anonimo":OptimistShell.usuario();cfg=store.cargar(usuario);aplicar();   // V53c: los renglones por página los guarda cada WorkWith (Pantalla), no la apariencia
    }
    public Button boton(){Button b=new Button("⚙ Apariencia",e->abrir());b.getElement().setAttribute("title","Configuración personal de pantalla");return b;}
    public boolean pantallaCompleta(){return Boolean.parseBoolean(cfg.getProperty("pantallaCompleta","false"));}
    private Input color(String valor){Input i=new Input();i.getElement().setAttribute("type","color");i.setValue(valor);i.getStyle().set("width","70px").set("height","38px");return i;}
    private void abrir(){
        Dialog d=new Dialog();OptimistVisuals.estilizarDialogo(d, cfg);d.setHeaderTitle("Configuración de pantalla — "+usuario);
        ComboBox<String> modo=new ComboBox<>("Modo");modo.setItems("Oscuro","Claro","Personalizado");modo.setValue(cfg.getProperty("modo","Oscuro"));
        Input fondo=color(cfg.getProperty("fondo","#091e24"));Input texto=color(cfg.getProperty("texto","#f5f7fb"));Input acento=color(cfg.getProperty("acento","#0ea88f"));
        HorizontalLayout paleta=new HorizontalLayout(campoColor("Fondo",fondo),campoColor("Letras",texto),campoColor("Principal",acento));
        ComboBox<String> fuente=new ComboBox<>("Tipografía");fuente.setItems("Arial","Verdana","Georgia","Tahoma","Trebuchet MS","Calibri","Cambria","Garamond","Century Gothic","Courier New","Times New Roman","Perpetua Titling MT");fuente.setValue(cfg.getProperty("fuente","Arial"));
        ComboBox<Integer> letra=new ComboBox<>("Tamaño de letra (px)");letra.setItems(12,13,14,15,16,18,20,22,24,26,28);letra.setValue(Integer.parseInt(cfg.getProperty("letra","15")));
        ComboBox<String> den=new ComboBox<>("Densidad de la grilla");den.setItems("Supercompacta","Compacta","Normal","Cómoda");den.setValue(cfg.getProperty("densidad","Normal"));
        // V34: formularios de las TRN más apretados (o más holgados), para pantallas chicas
        ComboBox<String> denForm=new ComboBox<>("Densidad de los formularios");denForm.setItems("Supercompacta","Compacta","Normal","Cómoda");denForm.setValue(cfg.getProperty("densidadFormulario","Normal"));
        ComboBox<String> pantalla=new ComboBox<>("Tamaño de pantalla");pantalla.setItems("Vista normal","Pantalla completa");pantalla.setValue(pantallaCompleta()?"Pantalla completa":"Vista normal");
        // V46: menú al costado (como hasta ahora) o arriba con desplegables (como el OPTIMIST ERP de GeneXus). Rearma la pantalla.
        ComboBox<String> menu=new ComboBox<>("Menú");menu.setItems("Vertical","Horizontal");menu.setValue(OptimistShell.menuHorizontal(cfg)?"Horizontal":"Vertical");
        menu.setHelperText("Vertical: al costado. Horizontal: barra arriba con desplegables (en el celular sigue al costado).");
        menu.addValueChangeListener(e->{if(e.isFromClient()&&e.getValue()!=null){cfg.setProperty("menu",e.getValue());guardar();UI.getCurrent().getPage().reload();}});
        // V56: acciones de cada renglón del WorkWith en el menú de los 3 puntitos o en el renglón (como GeneXus). Rearma la pantalla.
        ComboBox<String> acciones=new ComboBox<>("Acciones del renglón");acciones.setItems("Menú","Renglón");acciones.setValue("Renglón".equalsIgnoreCase(cfg.getProperty("acciones","Menú").trim())?"Renglón":"Menú");
        acciones.setHelperText("Menú: los 3 puntitos. Renglón: un ícono por acción, se toca una sola vez (en el celular sigue el menú).");
        acciones.addValueChangeListener(e->{if(e.isFromClient()&&e.getValue()!=null){cfg.setProperty("acciones",e.getValue());guardar();UI.getCurrent().getPage().reload();}});
        final boolean[] cambiandoModo = {false};
        Runnable cambio=()->{cfg.setProperty("modo",modo.getValue());cfg.setProperty("fondo",fondo.getValue());cfg.setProperty("texto",texto.getValue());cfg.setProperty("acento",acento.getValue());cfg.setProperty("fuente",fuente.getValue());cfg.setProperty("letra",letra.getValue().toString());cfg.setProperty("densidad",den.getValue());cfg.setProperty("densidadFormulario",denForm.getValue());cfg.setProperty("pantallaCompleta",String.valueOf("Pantalla completa".equals(pantalla.getValue())));guardar();aplicar();};
        // Los cambios programáticos de la paleta no deben convertir el modo elegido en Personalizado.
        modo.addValueChangeListener(e->{
            cambiandoModo[0] = true;
            if ("Oscuro".equals(e.getValue())) {
                fondo.setValue("#091e24"); texto.setValue("#f5f7fb");
            } else if ("Claro".equals(e.getValue())) {
                fondo.setValue("#eef2f5"); texto.setValue("#203344");
            }
            cambio.run();
            cambiandoModo[0] = false;
        });
        fondo.addValueChangeListener(e->{
            if(e.isFromClient() && !cambiandoModo[0]) {modo.setValue("Personalizado"); cambio.run();}
        });
        texto.addValueChangeListener(e->{
            if(e.isFromClient() && !cambiandoModo[0]) {modo.setValue("Personalizado"); cambio.run();}
        });
        acento.addValueChangeListener(e->cambio.run());
        fuente.addValueChangeListener(e->cambio.run());
        letra.addValueChangeListener(e->cambio.run());
        den.addValueChangeListener(e->cambio.run());
        denForm.addValueChangeListener(e->cambio.run());
        pantalla.addValueChangeListener(e->cambio.run());
        VerticalLayout campos=new VerticalLayout(modo,paleta,fuente,letra,den,denForm,pantalla,menu,acciones);campos.setWidth("480px");d.add(campos);
        OptimistVisuals.pieIzquierda(d,new Button("Restablecer",e->{boolean recargar=OptimistShell.menuHorizontal(cfg)||"Renglón".equalsIgnoreCase(cfg.getProperty("acciones","Menú").trim());cfg=OptimistUserConfig.defaults();guardar();aplicar();d.close();if(recargar)UI.getCurrent().getPage().reload();else abrir();}),new Button("Cerrar",e->d.close()));d.open();
    }
    private VerticalLayout campoColor(String nombre,Input input){VerticalLayout v=new VerticalLayout(new Span(nombre),input);v.setPadding(false);v.setSpacing(false);return v;}
    /** V29: graba SOLO las claves de Apariencia (lo demás del archivo, como el WorkWith, no se toca). */
    private void guardar(){try{java.util.Map<String,String> m=new java.util.HashMap<>();for(String k:OptimistUserConfig.CLAVES_APARIENCIA)m.put(k,cfg.getProperty(k));store.guardarClaves(usuario,m);}catch(Exception e){Notification.show("No se pudo guardar "+store.ruta(usuario)+": "+e.getMessage(),7000,Notification.Position.MIDDLE);}}
    private void aplicar(){
        String fondo=cfg.getProperty("fondo","#091e24"),texto=cfg.getProperty("texto","#f5f7fb"),color=cfg.getProperty("acento","#0ea88f"),fuente=cfg.getProperty("fuente","Arial"),densidad=cfg.getProperty("densidad","Normal");int letra=Integer.parseInt(cfg.getProperty("letra","15"));boolean completa=pantallaCompleta();
        vista.getStyle().set("background",fondo).set("color",texto).set("font-family",fuente+",sans-serif").set("font-size",letra+"px").set("padding","0").set("margin","0");
        contenido.setMaxWidth(completa?"none":anchoNormal);contenido.getStyle().set("margin",completa?"0":"auto").set("padding",completa?"0 15px 0 0":"28px 28px 0 28px").set("box-sizing","border-box");
        tarjeta.getStyle().set("background",fondo).set("color",texto).set("border-color",color);titulo.getStyle().set("color",texto);marca.getStyle().set("color",color);nuevo.getStyle().set("background",color).set("color","white");
        vista.getElement().getStyle().set("--lumo-primary-color",color).set("--lumo-body-text-color",texto).set("--lumo-font-family",fuente+",sans-serif");
        OptimistVisuals.estilizarGrilla(grilla, fondo, texto);
        grilla.getElement().getStyle().set("--lumo-font-size-m",letra+"px").set("--lumo-primary-color",color).set("--lumo-base-color",fondo).set("--lumo-body-text-color",texto).set("--lumo-header-text-color",texto).set("--lumo-font-family",fuente+",sans-serif").set("--lumo-size-m",switch(densidad){case "Supercompacta"->"26px";case "Compacta"->"34px";case "Cómoda"->"56px";default->"44px";});
        OptimistVisuals.aplicarPaletaGlobal(cfg);
    }
}
