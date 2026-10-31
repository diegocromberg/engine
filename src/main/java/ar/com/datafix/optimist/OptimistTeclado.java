package ar.com.datafix.optimist;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dialog.Dialog;

/**
 * V89 (Diego, 31/10/2026) — el teclado en los formularios (TRN y web panels), para todas las aplicaciones:
 * <ul>
 * <li><b>Cursor en el primer campo editable</b> al abrir (alta o modificación de una TRN, un web panel en ventana o como
 *     página). En Visualizar no (no hay nada para cargar). Si mientras tanto se abrió otra ventana (una lupa, la
 *     calculadora) o el usuario ya está en un campo, no le saca el foco.</li>
 * <li><b>Enter pasa al campo siguiente</b>, como TAB (en el orden de la pantalla; en una grilla de renglones, al de la
 *     derecha y después al renglón de abajo). Se saltean los de solo lectura, los deshabilitados y los ocultos (las otras
 *     solapas). En el último campo no hace nada.</li>
 * <li><b>Enter nunca confirma ni toca un botón</b>: Confirmar, Procesar, etc. van siempre con el mouse (o Espacio sobre el
 *     botón).</li>
 * <li>Excepciones: en un texto de varios renglones Enter es un renglón nuevo; en un combo o una fecha con la lista abierta,
 *     elige la opción; en las lupas, la calculadora, los PostIts y los mensajes de confirmación sigue como siempre (no son
 *     formularios).</li>
 * </ul>
 * Va todo del lado del navegador: un solo script por página (window.optimistTeclado), que escucha el Enter en los
 * formularios marcados (el tema "optimist-teclado" de la ventana, o el atributo data-optimist-teclado de la página).
 */
public final class OptimistTeclado {

    private OptimistTeclado() { }

    static final String TEMA = "optimist-teclado";
    static final String ATRIBUTO = "data-optimist-teclado";

    /** El script (idempotente): define window.optimistTeclado y engancha una sola vez el Enter en el documento. */
    static final String SCRIPT = """
        (function(){
          if (window.optimistTeclado) return;
          const TEMA = '%s', ATR = '%s';
          const marcado = el => el && el.getAttribute && (el.hasAttribute(ATR)
              || (' ' + (el.getAttribute('theme') || '') + ' ').indexOf(' ' + TEMA + ' ') >= 0);
          // las raíces donde buscar los campos de un formulario (la ventana: su overlay y el diálogo; la página: ella misma)
          const raices = el => {
            const r = [el];
            if (el.owner) r.push(el.owner);
            if (el.$ && el.$.overlay) r.push(el.$.overlay);
            if (el._overlayElement) r.push(el._overlayElement);
            return r.filter((x, i) => x && r.indexOf(x) === i);
          };
          const host = c => c.closest('vaadin-text-field,vaadin-text-area,vaadin-integer-field,vaadin-number-field,vaadin-big-decimal-field,'
              + 'vaadin-email-field,vaadin-password-field,vaadin-date-picker,vaadin-time-picker,vaadin-date-time-picker,vaadin-combo-box,'
              + 'vaadin-multi-select-combo-box,vaadin-select,vaadin-checkbox,vaadin-radio-button,vaadin-custom-field') || c;
          const visible = c => !!(c.offsetWidth || c.offsetHeight || c.getClientRects().length)
              && getComputedStyle(c).visibility !== 'hidden';
          const editable = c => {
            if (c.disabled || c.readOnly || c.type === 'hidden' || c.tabIndex < 0 && c.tagName !== 'VAADIN-SELECT-VALUE-BUTTON') return false;
            const h = host(c);
            if (h.hasAttribute('readonly') || h.hasAttribute('disabled') || h.readonly || h.disabled) return false;
            if (c.closest('[aria-hidden="true"]')) return false;
            return visible(c);
          };
          const campos = raiz => {
            let todos = [];
            for (const r of raices(raiz)) todos = todos.concat(Array.from(r.querySelectorAll('input,textarea,vaadin-select-value-button')));
            todos = todos.filter((c, i) => todos.indexOf(c) === i && editable(c));
            todos.sort((a, b) => a === b ? 0 : (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) ? -1 : 1);
            // en una grilla (renglones) el orden de la pantalla: de izquierda a derecha y de arriba abajo
            const res = [];
            for (let i = 0; i < todos.length; ) {
              const g = todos[i].closest('vaadin-grid');
              if (!g) { res.push(todos[i++]); continue; }
              const enG = [];
              while (i < todos.length && todos[i].closest('vaadin-grid') === g) enG.push(todos[i++]);
              const pos = c => c.getBoundingClientRect();
              enG.sort((a, b) => { const pa = pos(a), pb = pos(b);
                return Math.abs(pa.top - pb.top) > 4 ? pa.top - pb.top : pa.left - pb.left; });
              res.push(...enG);
            }
            return res;
          };
          const formulario = ev => {
            for (const el of ev.composedPath()) if (marcado(el)) return el;
            return null;
          };
          document.addEventListener('keydown', ev => {
            if (ev.key !== 'Enter' || ev.shiftKey || ev.ctrlKey || ev.altKey || ev.metaKey || ev.isComposing) return;
            const f = formulario(ev);
            if (!f) return;
            const path = ev.composedPath();
            const t = path[0];
            // nunca un botón: confirmar es con el mouse
            if (path.some(e => e.tagName === 'VAADIN-BUTTON' || e.tagName === 'BUTTON')) {
              ev.preventDefault(); ev.stopPropagation(); return;
            }
            if (!t || !t.tagName) return;
            if (t.tagName === 'TEXTAREA') return;                      // varios renglones: renglón nuevo
            const h = host(t);
            if (h.opened || h.hasAttribute('opened')) return;          // combo / fecha / select abierto: elige
            if (t.tagName !== 'INPUT' && t.tagName !== 'VAADIN-SELECT-VALUE-BUTTON') return;
            ev.preventDefault();
            // en una grilla, que la grilla no lo tome (saldría del campo a la celda); el valor lo toma al perder el foco
            if (t.closest('vaadin-grid')) ev.stopPropagation();
            const lista = campos(f);
            const i = lista.indexOf(t);
            const sig = i >= 0 ? lista[i + 1] : null;
            // primero que el campo tome su valor (el change de Vaadin sale con el Enter o al perder el foco)
            if (sig) setTimeout(() => { sig.focus(); if (sig.select && sig.type !== 'checkbox') try { sig.select(); } catch (e) { } }, 0);
          }, true);
          window.optimistTeclado = {
            // el cursor en el primer campo editable; reintenta mientras la ventana termina de armarse
            enfocar(raiz, intentos) {
              intentos = intentos === undefined ? 12 : intentos;
              const rs = raices(raiz);
              const act = document.activeElement;
              // el usuario ya está en un campo de este formulario
              if (act && /^(INPUT|TEXTAREA|VAADIN-SELECT-VALUE-BUTTON)$/.test(act.tagName) && rs.some(r => r.contains(act))) return;
              // hay otra ventana arriba (una lupa, la calculadora, una confirmación): no se le saca el foco
              const abiertas = Array.from(document.querySelectorAll('vaadin-dialog-overlay[opened],vaadin-confirm-dialog-overlay[opened]'));
              const ultima = abiertas[abiertas.length - 1];
              const tapada = ultima && !rs.includes(ultima) && !rs.includes(ultima.owner);
              const lista = tapada ? [] : campos(raiz);
              if (lista.length) { lista[0].focus(); return; }
              if (intentos > 0) setTimeout(() => window.optimistTeclado.enfocar(raiz, intentos - 1), 100);
            }
          };
        })();
        """.formatted(TEMA, ATRIBUTO);

    /** Una ventana de formulario (TRN o web panel): Enter como TAB y, si enfocar, el cursor en el primer campo editable. */
    public static void ventana(Dialog d, boolean enfocar) {
        d.addThemeName(TEMA);
        d.getElement().executeJs(SCRIPT + (enfocar ? "setTimeout(() => window.optimistTeclado.enfocar(this), 120);" : ""));
    }

    /** Un formulario como página (web panel desde el menú): igual que la ventana. */
    public static void pagina(Component raiz, boolean enfocar) {
        raiz.getElement().setAttribute(ATRIBUTO, "");
        raiz.getElement().executeJs(SCRIPT + (enfocar ? "setTimeout(() => window.optimistTeclado.enfocar(this), 120);" : ""));
    }

    /** Vuelve a poner el cursor en el primer campo editable (ej. un web panel después de su Start). */
    public static void enfocar(Component raiz) {
        raiz.getElement().executeJs(SCRIPT + "setTimeout(() => window.optimistTeclado.enfocar(this), 120);");
    }
}
