package ar.com.datafix.optimist;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.beans.factory.annotation.Autowired;

@Route("login")
public class LoginView extends VerticalLayout implements com.vaadin.flow.router.BeforeEnterObserver {
    private final OptimistDatabase db;
    /** V53: el código de cliente y el cliente con el que se va a ingresar (los resuelve beforeEnter). */
    private final String[] ck={null};
    private final OptimistDatabase.Cliente[] cliente={null};
    private final Span avisoCliente=new Span();

    public LoginView(@Autowired OptimistDatabase db, @Autowired OptimistPrecalentar precalentar){
        this.db=db;
        setSizeFull();setMinHeight("100vh");setAlignItems(Alignment.CENTER);setJustifyContentMode(JustifyContentMode.CENTER);
        getStyle().set("background","#091e24");
        addClassName("optimist-login");
        // V21: antes del login no hay paleta de usuario; forzamos Lumo oscuro para que etiquetas, campos y el ojito se vean bien.
        // V36: al cerrar sesión queda la paleta del usuario anterior (letra azul, fondo claro...): el login siempre arranca limpio.
        UI.getCurrent().getPage().executeJs("""
            const root = document.documentElement;
            for (const k of [...root.style]) if (k.startsWith('--optimist-') || k.startsWith('--lumo-') || k.startsWith('--vaadin-')) root.style.removeProperty(k);
            root.setAttribute('theme','dark');
            document.body.style.backgroundColor = '';
            document.body.style.color = '';
            """);
        Span marca=new Span(OptimistDatabase.nombreSistema());marca.getStyle().set("font-family","'Perpetua Titling MT','Perpetua',Georgia,serif").set("font-weight","bold").set("font-size","clamp(32px, 5vw, 43px)").set("color","#69b9d4").set("width","320px").set("text-align","center").set("white-space","nowrap").set("letter-spacing","-1px");
        H1 titulo=new H1("Ingreso al sistema");titulo.getStyle().set("color","white").set("font-size","19px").set("font-weight","400").set("margin","4px 0 18px").set("width","320px").set("text-align","center");
        TextField usuario=new TextField("Usuario"); PasswordField clave=new PasswordField("Contraseña");
        usuario.setWidth("320px");clave.setWidth("320px");
        usuario.getElement().setAttribute("autocomplete","username");clave.getElement().setAttribute("autocomplete","current-password");
        // V53: instalación única: el cliente sale del ?CK= de la dirección; se resuelve al abrir la pantalla (beforeEnter)
        avisoCliente.getStyle().set("width","320px").set("text-align","center").set("margin","-10px 0 14px").set("font-size","15px");
        avisoCliente.setVisible(false);
        Button ingresar=new Button("Ingresar");ingresar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);ingresar.setWidth("320px");
        Runnable login=()->{try{
            String u=usuario.getValue().trim();
            if(db.modoCentral()){
                // V53: sin cliente no se puede validar; con un código que no existe, el mismo aviso que un usuario incorrecto
                if(cliente[0]==null){
                    Notification.show(ck[0]==null?"Falta el código de cliente en la dirección (…/?CK=código)":"Usuario o contraseña incorrectos",5000,Notification.Position.MIDDLE);
                    return;
                }
                VaadinSession.getCurrent().setAttribute(OptimistDatabase.ATRIBUTO_CLIENTE,cliente[0]);
            }
            // E4: los usuarios salen de seguridad.yaml (o de una extensión de la aplicación)
            OptimistUsuarios.Ingreso ingreso;
            try(java.sql.Connection cn=db.openConnection()){ ingreso=OptimistUsuarios.validar(cn,u,clave.getValue()); }
            if(ingreso.valido()){
                // V30: contexto de la sesión (WWPContext), como SecLogIn -> SetDefaultUsuario (lo propio lo agrega la aplicación, E3)
                OptimistContexto ctx;
                try(java.sql.Connection cn=db.openConnection()){ ctx=OptimistContexto.cargar(cn,u,ingreso); }
                catch(java.sql.SQLException ex){
                    if("Usuario no habilitado".equals(ex.getMessage())){Notification.show(ex.getMessage(),5000,Notification.Position.MIDDLE);return;}
                    throw ex;
                }
                if(ctx==null){Notification.show("Usuario inexistente",4000,Notification.Position.MIDDLE);return;}
                // E3: lo que controla la aplicación (ej. OPTIMIST ERP: usuario sin empresa/división asignada)
                for(OptimistExtension ext:OptimistExtension.todas()){
                    String no=ext.validarIngreso(ctx,u);
                    if(no!=null){Notification.show(no,8000,Notification.Position.MIDDLE);return;}
                }
                ctx.guardarEnSesion();
                VaadinSession.getCurrent().setAttribute("optimist.usuario",u);
                UI.getCurrent().getPage().executeJs("localStorage.setItem('optimist.usuario',$0)",u);
                if(db.modoCentral()){
                    precalentar.cliente(cliente[0]);   // V53: la primera vez que entra alguien de ese cliente
                    recordarCodigo(ck[0]);             // V56a: para cuando el login se abra sin el ?CK= (reinicio del servidor)
                }
                UI.getCurrent().navigate("", OptimistShell.codigoCliente());   // V53: la dirección sigue con el ?CK= (favoritos por empresa)
            }else{
                if(db.modoCentral()) VaadinSession.getCurrent().setAttribute(OptimistDatabase.ATRIBUTO_CLIENTE,null);
                Notification.show("Usuario o contraseña incorrectos",4000,Notification.Position.MIDDLE);
            }
        }catch(Exception ex){Notification.show("No se pudo validar el usuario: "+ex.getMessage(),8000,Notification.Position.MIDDLE);}};
        ingresar.addClickListener(e->login.run()); clave.addKeyPressListener(com.vaadin.flow.component.Key.ENTER,e->login.run());
        add(marca,titulo,avisoCliente,usuario,clave,ingresar);
    }

    /** V56a: cookie con el último código de cliente con que se ingresó en este navegador (instalación única). */
    private static final String COOKIE_CK="optimist_ck";

    private static void recordarCodigo(String codigo){
        if(codigo==null||codigo.isBlank()) return;
        try{
            com.vaadin.flow.server.VaadinRequest req=com.vaadin.flow.server.VaadinService.getCurrentRequest();
            com.vaadin.flow.server.VaadinResponse res=com.vaadin.flow.server.VaadinService.getCurrentResponse();
            if(res==null) return;
            jakarta.servlet.http.Cookie c=new jakarta.servlet.http.Cookie(COOKIE_CK,codigo.trim().toLowerCase(java.util.Locale.ROOT));
            String ruta=req==null||req.getContextPath()==null||req.getContextPath().isEmpty()?"/":req.getContextPath();
            c.setPath(ruta);
            c.setMaxAge(60*60*24*365);   // un año
            c.setHttpOnly(true);
            c.setAttribute("SameSite","Lax");
            res.addCookie(c);
        }catch(RuntimeException ignorado){ /* es una comodidad: si no se puede, se sigue sin ella */ }
    }

    private static String codigoRecordado(){
        com.vaadin.flow.server.VaadinRequest req=com.vaadin.flow.server.VaadinService.getCurrentRequest();
        if(req==null||req.getCookies()==null) return null;
        for(jakarta.servlet.http.Cookie c:req.getCookies())
            if(COOKIE_CK.equals(c.getName())&&c.getValue()!=null&&c.getValue().matches("[0-9a-fA-F-]{8,64}")) return c.getValue().toLowerCase(java.util.Locale.ROOT);
        return null;
    }

    /**
     * V53: el código de cliente: el ?CK= de esta dirección o el que quedó en la sesión (OptimistCodigoCliente). Se resuelve
     * acá y no al armar la pantalla: la primera vez el login se arma antes de que se anote el código de la dirección.
     */
    @Override
    public void beforeEnter(com.vaadin.flow.router.BeforeEnterEvent e){
        if(!db.modoCentral()) return;
        String deLaDireccion=OptimistCodigoCliente.codigo(e.getLocation().getQueryParameters().getParameters());
        // V56a: sin ?CK= en la dirección ni en la sesión (ej. se reinició el servidor y la página se recargó desde otra
        // pantalla, que no lleva el código): el último cliente con que se ingresó en este navegador, de nuevo en la dirección
        if(deLaDireccion==null&&VaadinSession.getCurrent().getAttribute(OptimistDatabase.ATRIBUTO_CK)==null){
            String recordado=codigoRecordado();
            if(recordado!=null){ e.forwardTo("login",com.vaadin.flow.router.QueryParameters.of("CK",recordado)); return; }
        }
        if(deLaDireccion!=null) VaadinSession.getCurrent().setAttribute(OptimistDatabase.ATRIBUTO_CK,deLaDireccion);
        ck[0]=(String)VaadinSession.getCurrent().getAttribute(OptimistDatabase.ATRIBUTO_CK);
        cliente[0]=null;
        avisoCliente.setVisible(true);
        try{ cliente[0]=db.buscarCliente(ck[0]); }
        catch(java.sql.SQLException ex){ avisoCliente.setText(ex.getMessage()); avisoCliente.getStyle().set("color","#ff8585"); return; }
        if(cliente[0]!=null){ avisoCliente.setText(cliente[0].razonSocial()); avisoCliente.getStyle().set("color","#69b9d4"); }
        else if(ck[0]==null){ avisoCliente.setText("Falta el código de cliente en la dirección (…/?CK=código)"); avisoCliente.getStyle().set("color","#ff8585"); }
        else avisoCliente.setVisible(false);   // código inexistente: no se dice nada (al ingresar, "Usuario o contraseña incorrectos")
    }
}
