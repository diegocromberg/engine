package ar.com.datafix.optimist;

import ar.com.datafix.optimist.OptimistTrnDefinicion.Campo;
import ar.com.datafix.optimist.OptimistTrnDefinicion.Dominio;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * V71 - Calculadora de los campos numéricos (Diego, 24/10/2026): doble clic en un campo numérico editable (formulario y
 * renglones de las TRN, campos de los web panels) abre una calculadora que arranca con el valor del campo.
 * "Copiar" pasa el resultado al campo (redondeado a sus decimales; si no entra, avisa y no copia), cierra la ventana y
 * dispara lo mismo que si el usuario lo hubiera escrito (fórmulas, reglas). "Cerrar", la X (arriba a la derecha) o Esc
 * cierran sin copiar.
 * Las cuentas se hacen en el navegador (cada tecla no viaja al servidor); al copiar, el servidor lee el resultado.
 * Teclado: números, coma o punto decimal, + - * / (también x), Enter o = (resultado), Retroceso, Supr o C (borrar), %.
 */
final class OptimistCalculadora {
    private OptimistCalculadora() {}

    /**
     * Engancha el doble clic al control. cambios: las acciones que el motor registró para "el usuario cambió el valor"
     * (se ejecutan después de copiar, porque escribir el valor desde el servidor no las dispara).
     */
    static void enganchar(Campo c, Component comp, Supplier<Object> leer, Consumer<Object> escribir, List<Runnable> cambios) {
        comp.getElement().addEventListener("dblclick", e -> {
            if (comp instanceof HasValue<?, ?> hv && hv.isReadOnly()) return;
            if (!comp.getElement().isEnabled()) return;
            abrir(c, comp, leer, escribir, cambios);
        });
    }

    private static void abrir(Campo c, Component comp, Supplier<Object> leer, Consumer<Object> escribir, List<Runnable> cambios) {
        Dialog d = new Dialog();
        OptimistVisuals.estilizarDialogo(d);
        d.setHeaderTitle("Calculadora");
        d.setCloseOnOutsideClick(false);
        d.setCloseOnEsc(true);
        Button x = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> d.close());
        x.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        x.getElement().setAttribute("aria-label", "Cerrar");
        x.getElement().setAttribute("title", "Cerrar");
        d.getHeader().add(x);   // el título va a la izquierda y lo que se agrega al encabezado, a la derecha

        Div calc = new Div();
        calc.getElement().setAttribute("tabindex", "0");
        calc.getStyle().set("outline", "none").set("width", "260px");
        d.add(calc);

        Object v = leer.get();
        String inicial = "0";
        if (v != null) {
            try { inicial = OptimistTrnDatos.decimal(v).stripTrailingZeros().toPlainString(); } catch (RuntimeException ex) { inicial = "0"; }
        }

        Button copiar = new Button("Copiar", e -> calc.getElement().executeJs("return $0._optCalc ? $0._optCalc.resultado() : ''", calc)
                .then(String.class, r -> copiar(c, comp, escribir, cambios, d, r)));
        copiar.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button cerrar = new Button("Cerrar", e -> d.close());
        d.getFooter().add(cerrar, copiar);

        d.open();
        calc.getElement().executeJs(JS, calc, inicial);
    }

    private static void copiar(Campo c, Component comp, Consumer<Object> escribir, List<Runnable> cambios, Dialog d, String r) {
        if (r == null || r.isBlank()) {
            Notification.show("No hay un resultado para copiar.", 4000, Notification.Position.MIDDLE);
            return;
        }
        BigDecimal b;
        try { b = new BigDecimal(r.trim()); } catch (NumberFormatException ex) {
            Notification.show("No hay un resultado para copiar.", 4000, Notification.Position.MIDDLE);
            return;
        }
        Dominio dom = c.dominio();
        b = b.setScale(Math.max(dom.decimales(), 0), RoundingMode.HALF_UP);
        int enteros = dom.longitud() - dom.decimales();
        if (dom.longitud() > 0 && enteros > 0) {
            int tiene = b.abs().setScale(0, RoundingMode.DOWN).toPlainString().length();
            if (tiene > enteros) {
                Notification.show("El resultado (" + OptimistTrnCampos.formatoMiles(dom).format(b) + ") no entra en "
                        + c.etiqueta() + ": admite hasta " + enteros + (enteros == 1 ? " dígito entero." : " dígitos enteros."),
                        6000, Notification.Position.MIDDLE);
                return;
            }
        }
        escribir.accept(dom.esEntero() ? (Object) b.longValue() : b);
        d.close();
        for (Runnable cambio : List.copyOf(cambios)) cambio.run();
        if (comp instanceof Focusable<?> f) f.focus();
    }

    /** $0: el div de la calculadora; $1: el valor inicial (con punto decimal). Arma las teclas y atiende el teclado. */
    private static final String JS = """
        const el = $0;
        let actual = ($1 || '0'), acum = null, op = null, nuevo = true;
        const SIM = {'+': '+', '-': '−', '*': '×', '/': '÷'};
        function num(s) { return Number(s); }
        function str(n) {
          if (!isFinite(n)) return 'Error';
          let s = String(Number(n.toPrecision(15)));
          if (s.indexOf('e') >= 0) s = n.toFixed(10).replace(/\\.?0+$/, '');
          return s;
        }
        function fmt(s) {
          if (s === 'Error') return s;
          let neg = s.startsWith('-'); if (neg) s = s.substring(1);
          let i = s.indexOf('.'), ent = i >= 0 ? s.substring(0, i) : s, dec = i >= 0 ? s.substring(i + 1) : null;
          ent = (ent || '0').replace(/\\B(?=(\\d{3})+(?!\\d))/g, '.');
          return (neg ? '-' : '') + ent + (dec !== null ? ',' + dec : '');
        }
        function cuenta(a, b, o) {
          a = num(a); b = num(b);
          if (o === '+') return str(a + b);
          if (o === '-') return str(a - b);
          if (o === '*') return str(a * b);
          if (o === '/') return b === 0 ? 'Error' : str(a / b);
          return str(b);
        }
        function digito(dg) {
          if (nuevo || actual === 'Error') { actual = dg; nuevo = false; }
          else if (actual === '0') actual = dg;
          else if (actual === '-0') actual = '-' + dg;
          else if (actual.replace(/[-.]/g, '').length < 18) actual += dg;
        }
        function coma() {
          if (nuevo || actual === 'Error') { actual = '0.'; nuevo = false; }
          else if (actual.indexOf('.') < 0) actual += '.';
        }
        function operador(o) {
          if (actual === 'Error') return;
          if (op !== null && !nuevo) actual = cuenta(acum, actual, op);
          if (actual === 'Error') { acum = null; op = null; nuevo = true; return; }
          acum = actual; op = o; nuevo = true;
        }
        function igual() {
          if (op !== null && actual !== 'Error') actual = cuenta(acum, actual, op);
          acum = null; op = null; nuevo = true;
        }
        function porcentaje() {
          if (actual === 'Error') return;
          if (op === '+' || op === '-') actual = str(num(acum) * num(actual) / 100);
          else actual = str(num(actual) / 100);
          nuevo = true;
        }
        function signo() {
          if (actual === 'Error' || actual === '0') return;
          actual = actual.startsWith('-') ? actual.substring(1) : '-' + actual;
        }
        function atras() {
          if (nuevo || actual === 'Error') return;
          actual = actual.substring(0, actual.length - 1);
          if (actual === '' || actual === '-') actual = '0';
        }
        function borrar() { actual = '0'; acum = null; op = null; nuevo = true; }

        el.innerHTML = '';
        const pan = document.createElement('div');
        pan.style.cssText = 'background:var(--lumo-contrast-5pct);border-radius:var(--lumo-border-radius-m);padding:6px 10px;margin-bottom:8px;text-align:right;';
        const exp = document.createElement('div');
        exp.style.cssText = 'min-height:1.3em;font-size:var(--lumo-font-size-s);color:var(--lumo-secondary-text-color);';
        const vis = document.createElement('div');
        vis.style.cssText = 'font-size:var(--lumo-font-size-xxl);font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
        pan.appendChild(exp); pan.appendChild(vis); el.appendChild(pan);
        const teclas = document.createElement('div');
        teclas.style.cssText = 'display:grid;grid-template-columns:repeat(4,1fr);gap:6px;';
        el.appendChild(teclas);
        function mostrar() {
          exp.textContent = (op !== null && acum !== null) ? fmt(acum) + ' ' + SIM[op] : '';
          vis.textContent = fmt(actual);
        }
        const T = [['C', borrar, 'f'], ['⌫', atras, 'f'], ['%', porcentaje, 'f'], ['÷', () => operador('/'), 'o'],
                   ['7', () => digito('7')], ['8', () => digito('8')], ['9', () => digito('9')], ['×', () => operador('*'), 'o'],
                   ['4', () => digito('4')], ['5', () => digito('5')], ['6', () => digito('6')], ['−', () => operador('-'), 'o'],
                   ['1', () => digito('1')], ['2', () => digito('2')], ['3', () => digito('3')], ['+', () => operador('+'), 'o'],
                   ['±', signo, 'f'], ['0', () => digito('0')], [',', coma], ['=', igual, 'i']];
        for (const [txt, fn, tipo] of T) {
          const b = document.createElement('button');
          b.type = 'button'; b.tabIndex = -1; b.textContent = txt;
          let fondo = 'var(--lumo-contrast-5pct)', color = 'var(--lumo-body-text-color)';
          if (tipo === 'f') fondo = 'var(--lumo-contrast-10pct)';
          if (tipo === 'o') { fondo = 'var(--lumo-primary-color-10pct)'; color = 'var(--lumo-primary-text-color)'; }
          if (tipo === 'i') { fondo = 'var(--lumo-primary-color)'; color = 'var(--lumo-primary-contrast-color)'; }
          b.style.cssText = 'height:2.6em;border:none;border-radius:var(--lumo-border-radius-m);cursor:pointer;'
            + 'font-family:inherit;font-size:var(--lumo-font-size-l);background:' + fondo + ';color:' + color + ';';
          b.addEventListener('click', ev => { ev.preventDefault(); fn(); mostrar(); el.focus(); });
          teclas.appendChild(b);
        }
        el.addEventListener('keydown', ev => {
          const k = ev.key;
          let hecho = true;
          if (k >= '0' && k <= '9' && k.length === 1) digito(k);
          else if (k === ',' || k === '.' || k === 'Decimal') coma();
          else if (k === '+') operador('+');
          else if (k === '-') operador('-');
          else if (k === '*' || k === 'x' || k === 'X') operador('*');
          else if (k === '/') operador('/');
          else if (k === 'Enter' || k === '=') igual();
          else if (k === 'Backspace') atras();
          else if (k === 'Delete' || k === 'c' || k === 'C') borrar();
          else if (k === '%') porcentaje();
          else hecho = false;
          if (hecho) { ev.preventDefault(); ev.stopPropagation(); mostrar(); }
        });
        el._optCalc = { resultado: () => { if (op !== null) igual(); mostrar(); return actual === 'Error' ? '' : actual; } };
        mostrar();
        setTimeout(() => el.focus(), 50);
        """;
}
