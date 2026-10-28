#!/usr/bin/env python3
"""
V48 - Web panels de GeneXus -> wbp/<Nombre>.yaml (web panel por YAML que interpreta el motor).

    python3 gx2wbp.py OptimistU12NEW.xml PRSAltaWbp [OtroWbp ...] --salida ../src/main/resources/wbp

Qué saca de la KB:
- Variables de la pantalla (los &Var del web form), en el orden del web form; cada renglón de las tablas de GeneXus
  es un renglón de 'pantalla'. ReadOnly -> soloLectura. Las que el Start oculta (&X.Visible = False) -> oculto.
- Tipos reales: si la variable está basada en un atributo o un dominio, su dominio (con los valores de los
  enumerados, para que se vean como combo: "Cliente" en lugar de 1).
- Las demás variables (las que usan los eventos) van en 'internas', con su tipo (como en un .proc).
- Prompt(...) de las reglas -> 'prompts': la lupa va en la primera variable editable; la tabla sale de la clave;
  las variables que el Start carga (ej. &EntidadesTipoEntidad = TipoEntidad.Cliente) filtran la lupa.
- Botones del web form (gxButton) -> 'botones'.
- Eventos y Sub TAL CUAL, con los enumerados reemplazados por su valor (como gx2proc.py) y
  &X.EnumerationDescription() -> EnumTexto.Udp(&X, "1=Activo;2=...").
Lo que no puede saber (los filtros propios del objeto prompt, ej. "solo facturas a clientes") se completa a mano:
lo deja anotado como REVISAR al principio del YAML.
"""
import argparse, html, json, os, re, sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gx2proc as gp
import gx2yaml as gy

T_WBP = 'c9584656-94b6-4ccd-890f-332d11fc2c25'
P_EVENTS = 'c44bd5ff'
P_RULES = '9b0a32a3'
P_VARS = 'e4c4ade7'
P_FORM = 'd24a58ad'
NO_VARS = ('Pgmname', 'Pgmdesc', 'Time', 'Today', 'Output')


def leer_wbps(xml, nombres):
    """{nombre: {'desc', 'eventos', 'reglas', 'vars': [{nombre, props}], 'form'}} de los web panels pedidos."""
    pedidos = {n.lower() for n in nombres}
    r, stack = {}, []
    for ev, el in ET.iterparse(xml, events=('start', 'end')):
        if ev == 'start':
            stack.append(el.tag)
            continue
        stack.pop()
        if len(stack) == 2 and el.tag == 'Object':
            if el.get('type') == T_WBP and (el.get('name') or '').lower() in pedidos:
                w = {'desc': el.get('description') or el.get('name'), 'eventos': '', 'reglas': '', 'vars': [], 'form': ''}
                for p in el.findall('Part'):
                    t = (p.get('type') or '')[:8]
                    if t == P_EVENTS:
                        w['eventos'] = p.findtext('Source') or ''
                    elif t == P_RULES:
                        w['reglas'] = p.findtext('Source') or ''
                    elif t == P_FORM:
                        w['form'] = p.findtext('Source') or ''
                    elif t == P_VARS:
                        w['vars'] = [{'nombre': v.get('Name'),
                                      'props': {x.findtext('Name'): x.findtext('Value') for x in v.iter('Property')}}
                                     for v in p.findall('Variable')]
                r[el.get('name')] = w
            el.clear()
    return r


def q(s):
    return gy.q(s)


def tipo_var(M, kb, nombre, props):
    """(nombre_dominio|None, 'Tipo(l.d)'|None, enum|None, gx_tipo_para_internas)."""
    base = props.get('idBasedOn') or ''
    if base.startswith(('Attribute:', 'Domain:')):
        falso = '__wbp_' + nombre
        kb['attrs'][falso] = {'idBasedOn': base}
        if base.startswith('Attribute:'):
            kb['attrs'][falso] = kb['attrs'].get(base.split(':', 1)[1], {}) or {'idBasedOn': base}
        try:
            dom, txt, enum, _ = M.tipo(falso)
        finally:
            kb['attrs'].pop(falso, None)
        if base.startswith('Attribute:') and dom is None:
            dom, txt, enum, _ = M.tipo(base.split(':', 1)[1])
        return dom, txt, enum, gp.tipo_de(props, kb) or txt
    t = gp.tipo_de(props, kb)
    return None, (t if t and not t.startswith(('Sdt(', 'Bc(', 'Ext(', 'Coleccion(')) else None), None, t


def celdas_de(form):
    """Renglones de la pantalla: [[('&Var', readonly, etiqueta)], ...] en el orden del web form (fila por fila)."""
    renglones = []
    # solo las filas más de adentro (las que no tienen otra tabla adentro): cada una es un renglón
    for tr in re.finditer(r'<tr>((?:(?!<tr>).)*?)</tr>', form, re.S):
        fila = tr.group(1)
        celdas, etiqueta = [], None
        for m in re.finditer(r'<gxTextBlock\b[^>]*?CaptionExpression="([^"]*)"|<gxAttribute\b([^>]*?)/>', fila):
            if m.group(1) is not None:
                etiqueta = html.unescape(m.group(1))
                continue
            a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(2)))
            att = html.unescape(a.get('AttID', ''))
            if att.startswith('&'):
                celdas.append((att[1:], a.get('ReadOnly') == 'True', etiqueta))
            etiqueta = None
        if celdas:
            renglones.append(celdas)
    return renglones


def botones_de(form):
    r = []
    for m in re.finditer(r'<gxButton\b([^>]*?)/>', form):
        a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        ev = html.unescape(a.get('Event', '')).replace("'", '').strip()
        cap = html.unescape(a.get('CaptionExpression', ev))
        if cap.startswith('GX_Btn'):
            cap = {'GX_BtnCancel': 'Cancelar', 'GX_BtnEnter': 'Confirmar'}.get(cap, cap[6:])
        r.append((cap, ev or 'Cancel'))
    return r


def tabla_de(kb, atributos):
    """La tabla cuya clave es alguno de los atributos (la primera que encuentre, en el orden dado)."""
    for a in atributos:
        for t, claves in kb['tablas'].items():
            if claves == [a]:
                return t
    for a in atributos:
        for t, claves in kb['tablas'].items():
            if a in claves:
                return t
    return None


def generar(nombre, w, kb, M, enums):
    revisar = []
    vars_props = {v['nombre']: v['props'] for v in w['vars'] if v['nombre'] not in NO_VARS}
    renglones = celdas_de(w['form'])
    en_pantalla, solo_lectura, etiquetas = [], set(), {}
    for fila in renglones:
        for v, ro, et in fila:
            if v not in en_pantalla:
                en_pantalla.append(v)
            if ro:
                solo_lectura.add(v)
            if et:
                etiquetas[v] = et
    # variables del form que no están en ninguna tabla (ej. <gxAttribute AttID="&TcbteCPI" /> suelto)
    for m in re.finditer(r'AttID="&amp;([A-Za-z_]\w*)"|AttID="&([A-Za-z_]\w*)"', w['form']):
        v = m.group(1) or m.group(2)
        if v not in en_pantalla:
            en_pantalla.append(v)
    eventos = w['eventos'].replace('\r\n', '\n')
    start = re.search(r'(?is)event\s+start\b(.*?)endevent', eventos)
    start_txt = start.group(1) if start else ''
    ocultas = {m.group(1) for m in re.finditer(r'(?i)&(\w+)\.Visible\s*=\s*False', start_txt)}
    cargadas_start = {m.group(1) for m in re.finditer(r'(?im)^\s*&(\w+)\s*=', start_txt)}
    sueltas = [v for v in en_pantalla if all(v != c[0] for f in renglones for c in f)]
    ocultas |= set(sueltas)   # las que están sueltas, fuera de las tablas, GeneXus no las muestra

    dominios, lineas_vars, enum_de = {}, [], {}
    for v in en_pantalla:
        p = vars_props.get(v)
        if p is None:
            revisar.append(f'&{v} está en la pantalla pero no en las variables del web panel')
            continue
        dom, txt, enum, _ = tipo_var(M, kb, v, p)
        if txt is None:
            revisar.append(f'&{v}: tipo no soportado ({p.get("idBasedOn") or p.get("ATTCUSTOMTYPE")})')
            continue
        if dom and dom not in dominios:
            dominios[dom] = (txt, enum)
        if enum:
            enum_de[v.lower()] = enum
        partes = [f'nombre: {v}', f'dominio: {q(dom or txt)}', f'etiqueta: {q(etiquetas.get(v) or M.etiqueta(v) if v in kb["attrs"] else etiquetas.get(v, v))}']
        if v in solo_lectura:
            partes.append('soloLectura: true')
        if v in ocultas:
            partes.append('oculto: true')
        lineas_vars.append('  - {' + ', '.join(partes) + '}')

    internas = []
    ancho = max([len(n) for n in vars_props] + [8]) + 2
    for v, p in vars_props.items():
        if v in en_pantalla:
            continue
        dom, txt, enum, gx = tipo_var(M, kb, v, p)
        if enum:
            enum_de[v.lower()] = enum
        if gx is None:
            revisar.append(f'&{v}: tipo no soportado ({p.get("idBasedOn") or p.get("ATTCUSTOMTYPE")})')
            gx = 'Ext(Desconocido)'
        internas.append(f'  &{v.ljust(ancho)}{gx}')

    # eventos: enumerados por su valor y EnumerationDescription -> EnumTexto.Udp
    fuente = gp.reemplazar_enums(eventos, enums).strip('\n')

    def enum_desc(m):
        e = enum_de.get(m.group(1).lower())
        if not e:
            revisar.append(f'&{m.group(1)}.EnumerationDescription(): no se encontraron los valores del dominio')
            return f'&{m.group(1)}'
        vals = ';'.join(f'{x["valor"]}={x["texto"]}' for x in e)
        return f'EnumTexto.Udp(&{m.group(1)}, "{vals}")'
    fuente = re.sub(r'(?i)&(\w+)\.EnumerationDescription\(\s*\)', enum_desc, fuente)

    # V82: el "Initial Value" de las variables, al empezar el Start (si no hay Start, uno nuevo)
    ini = gp.valores_iniciales([{'nombre': v, 'props': p} for v, p in vars_props.items()], enums)
    if ini:
        bloque = '\n\t/*\tValores iniciales de las variables (en GeneXus, su "Initial Value")\t*/\n' + '\n'.join('\t' + x for x in ini) + '\n'
        if re.search(r'(?im)^\s*event\s+start\b', fuente):
            fuente = re.sub(r'(?im)^(\s*event\s+start\b[^\n]*\n)', lambda m: m.group(1) + bloque, fuente, count=1)
        else:
            fuente = 'Event Start\n' + bloque + 'EndEvent\n\n' + fuente

    # prompts
    prompts = []
    reglas = re.sub(r'/\*.*?\*/', '', w['reglas'], flags=re.S)
    for m in re.finditer(r'(?is)\bprompt\s*\(\s*(\w+)\s*,(.*?)\)\s*;', reglas):
        args = [a.strip().lstrip('&') for a in m.group(2).split(',') if a.strip()]
        editable = next((a for a in args if a in en_pantalla and a not in solo_lectura and a not in ocultas), None)
        if editable is None:
            revisar.append(f'Prompt {m.group(1)}: ninguna de sus variables es editable en la pantalla')
            continue
        attrs = [a for a in args if a in kb['attrs'] or a.lower() in {k.lower() for k in kb['attrs']}]
        tabla = tabla_de(kb, attrs)
        if tabla is None:
            revisar.append(f'Prompt {m.group(1)} (&{editable}): sus variables no son atributos, no se sabe en qué tabla busca: '
                           f'falta la lupa (agregarla a mano en prompts:)')
            continue
        i = args.index(editable)
        desc = args[i + 1] if i + 1 < len(args) else None
        devuelve = {a: a for a in args}
        cargadas_l = {x.lower() for x in cargadas_start}
        donde = {a: '&' + a for a in args if a.lower() in cargadas_l and a != editable}
        prompts.append((editable, tabla, editable, desc, donde, devuelve, m.group(1)))
        revisar.append(f'Prompt {m.group(1)} (lupa de &{editable}): si el objeto prompt filtra algo más '
                       f'(ej. solo clientes, solo facturas), agregarlo en su "donde"')

    botones = botones_de(w['form'])
    # Parm(in: &A, &B, ...) de las reglas -> parm: [A, B]
    parm = []
    mp = re.search(r'(?is)\bparm\s*\((.*?)\)\s*;', reglas)
    if mp:
        for x in mp.group(1).split(','):
            x = re.sub(r'(?i)^\s*(in|out|inout)\s*:\s*', '', x.strip()).lstrip('&').strip()
            if x:
                parm.append(x)
                if not re.fullmatch(r'[A-Za-z_]\w*', x):
                    revisar.append(f'Parm: "{x}" no es una variable')

    out = [f'# Web panel {nombre} - GENERADO desde la KB GeneXus con herramientas/gx2wbp.py',
           '# Se puede retocar a mano o con el OPTIMIST Designer; los eventos son código GeneXus (como un .proc).']
    for r in revisar:
        out.append('# REVISAR: ' + r)
    out += [f'panel: {nombre}', f'titulo: {q(w["desc"])}', ] + ([f'parm: [{", ".join(parm)}]'] if parm else []) + ['variables:'] + lineas_vars
    out.append('pantalla:')
    for fila in renglones:
        vs = [c[0] for c in fila if c[0] in en_pantalla and c[0] not in ocultas]
        if vs:
            out.append('  - [' + ', '.join(vs) + ']')
    if prompts:
        out.append('prompts:')
        for ed, tabla, cod, desc, donde, dev, obj in prompts:
            out.append(f'  - variable: {ed}            # Prompt({obj}, ...)')
            out.append(f'    tabla: {tabla}')
            out.append(f'    codigo: {cod}')
            if desc:
                out.append(f'    descripcion: {desc}')
            if donde:
                out.append('    donde: {' + ', '.join(f'{k}: {q(v)}' for k, v in donde.items()) + '}')
            out.append('    devuelve: {' + ', '.join(f'{k}: {v}' for k, v in dev.items()) + '}')
    out.append('botones:')
    for cap, ev in botones:
        out.append(f'  - {{titulo: {q(cap)}, evento: {q(ev)}}}')
    if internas:
        out.append('internas: |')
        out += ['  ' + l for l in internas]
    out.append('eventos: |')
    out += ['  ' + l if l.strip() else '' for l in fuente.split('\n')]
    if dominios:
        out.append('dominios:')
        for d, (txt, enum) in sorted(dominios.items()):
            if enum:
                out.append(f'  {d}:')
                out.append(f'    tipo: {q(txt)}')
                out.append('    valores:')
                for e in enum:
                    out.append(f'      - {{valor: {q(e["valor"])}, texto: {q(e["texto"])}}}')
            else:
                out.append(f'  {d}: {q(txt)}')
    return '\n'.join(out) + '\n', revisar


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('nombres', nargs='+')
    ap.add_argument('--salida', required=True)
    a = ap.parse_args()
    kb = gp.cargar_kb(a.xml)
    M = gy._modelo(kb)
    enums = gp.enumerados(kb)
    wbps = leer_wbps(a.xml, a.nombres)
    os.makedirs(a.salida, exist_ok=True)
    for n in a.nombres:
        w = next((v for k, v in wbps.items() if k.lower() == n.lower()), None)
        if w is None:
            print(f'{n}: no está en la KB (o no es un web panel)')
            continue
        nombre = next(k for k in wbps if k.lower() == n.lower())
        texto, revisar = generar(nombre, w, kb, M, enums)
        open(os.path.join(a.salida, nombre + '.yaml'), 'w', encoding='utf-8').write(texto)
        print(f'{nombre}.yaml: {len(revisar)} cosas para revisar')


if __name__ == '__main__':
    main()
