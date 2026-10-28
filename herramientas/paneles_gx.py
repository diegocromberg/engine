#!/usr/bin/env python3
"""
V33 - Agrega a los YAML los paneles de GeneXus (los DVelop Panel que se abren y cierran).

No regenera la TRN: toma la 'pantalla' que ya tiene el YAML (con los retoques a mano que haya) y
envuelve en <panel Título [cerrado]> ... <endpanel> los renglones y detalles que en el web form de
GeneXus están dentro de cada panel. Paneles dentro de paneles se respetan; dos paneles que en
GeneXus están uno al lado del otro quedan en un <renglon>.
Los paneles fijos (Collapsible=False, como el "Información Principal" que envuelve todo) no se traen.

  python3 paneles_gx.py OptimistU12NEW.xml --cache kb.json --trn ..\\src\\main\\resources\\trn [--solo PRS,PED] [--probar]

--probar: no graba, solo informa qué haría.
"""
import argparse, html, json, os, re, sys
import gx2yaml as g


# ------------------------------------------------------------------ paneles del web form
def paneles_del_webform(wf, niveles):
    """Devuelve (camino_de_atributo, camino_de_detalle, info_panel).
    camino = tupla de ids de paneles plegables, de afuera hacia adentro."""
    pila = []                 # [(id|None, es_plegable)]
    info, por_attr, por_grilla = {}, {}, {}
    n = 0
    for m in re.finditer(r'<gxgxui\.Panel\b([^>]*)>|</gxgxui\.Panel>|<gxAttribute AttID="([^"]+)"|<gxGrid\b(.*?)</gxGrid>', wf, re.S):
        if m.group(0).startswith('</gxgxui.Panel'):
            if pila: pila.pop()
            continue
        if m.group(1) is not None:
            a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
            plegable = a.get('Collapsible') == 'True'
            pid = None
            if plegable:
                n += 1; pid = n
                info[pid] = {'titulo': html.unescape(a.get('Title', '')).strip() or 'Panel', 'cerrado': a.get('Collapsed') == 'True'}
            pila.append(pid)
            continue
        camino = tuple(p for p in pila if p is not None)
        if m.group(2) is not None:
            por_attr.setdefault(html.unescape(m.group(2)), camino)
        else:
            cols = {html.unescape(c) for c in re.findall(r'ColAttId="([^"]+)"', m.group(3))}
            mejor = max(((len(cols & v), k) for k, v in niveles.items()), default=(0, None))
            if mejor[0] > 0:
                por_grilla.setdefault(mejor[1], camino)
    return por_attr, por_grilla, info


# ------------------------------------------------------------------ pantalla: leer y escribir (igual que el Designer)
def leer_pantalla(txt):
    raiz = {'items': []}
    pila = [raiz]
    tabla, implicita = None, False
    for crudo in txt.split('\n'):
        s = crudo.strip()
        if not s:
            continue
        m = re.match(r'^<\s*(?:fin\s+|/)(\w+)\s*>$', s, re.I)
        if m:
            s = '<end' + m.group(1).lower() + '>'
        if s.startswith('#'):
            if implicita: tabla, implicita = None, False
            if tabla is not None: tabla.setdefault('coments', []).append(s)
            else: pila[-1]['items'].append({'t': 'coment', 'texto': s})
            continue
        m = re.match(r'^<solapa\s+(.*)>$', s, re.I)
        if m:
            tabla, implicita = None, False
            del pila[1:]
            so = {'t': 'solapa', 'titulo': m.group(1).strip(), 'items': []}
            pila[-1]['items'].append(so); pila.append(so); continue
        if re.match(r'^<endsolapa>$', s, re.I):
            tabla, implicita = None, False
            del pila[1:]; continue
        if re.match(r'^<(panel|renglon|renglón)\b', s, re.I) or re.match(r'^<end(panel|renglon|renglón)>$', s, re.I):
            raise ValueError('ya tiene paneles')
        m = re.match(r'^<tabla(\s[^>]*)?>$', s, re.I)
        if m:
            tabla, implicita = {'t': 'tabla', 'filas': [], 'op': (m.group(1) or '').strip()}, False
            pila[-1]['items'].append(tabla); continue
        if re.match(r'^<end(table|tabla)>$', s, re.I):
            tabla, implicita = None, False; continue
        m = re.match(r'^<detalle\s+(\S+?)\s*>$', s, re.I)
        if m:
            if implicita: tabla, implicita = None, False
            pila[-1]['items'].append({'t': 'detalle', 'nombre': m.group(1)}); continue
        if tabla is None:
            tabla, implicita = {'t': 'tabla', 'filas': [], 'op': ''}, True
            pila[-1]['items'].append(tabla)
        tabla['filas'].append(s.split())
    return raiz


def texto_pantalla(raiz):
    L = []
    def emitir(items, ind):
        for it in items:
            t = it['t']
            if t == 'solapa':
                if L and L[-1] != '': L.append('')
                L.append(' ' * ind + f"<solapa {it['titulo']}>"); emitir(it['items'], ind + 2); L.append(' ' * ind + '<endsolapa>')
            elif t == 'panel':
                op = ' '.join(x for x in [it['titulo'], 'cerrado' if it.get('cerrado') else ''] if x)
                L.append(' ' * ind + f'<panel {op}>'); emitir(it['items'], ind + 2); L.append(' ' * ind + '<endpanel>')
            elif t == 'renglon':
                L.append(' ' * ind + '<renglon>'); emitir(it['items'], ind + 2); L.append(' ' * ind + '<endrenglon>')
            elif t == 'tabla':
                L.append(' ' * ind + '<tabla' + (' ' + it['op'] if it.get('op') else '') + '>')
                for c in it.get('coments', []): L.append(' ' * (ind + 2) + c)
                w = {}
                for f in it['filas']:
                    for i, x in enumerate(f): w[i] = max(w.get(i, 0), len(x) + 2)
                for f in it['filas']:
                    L.append((' ' * (ind + 2) + ''.join(x.ljust(w[i]) if i < len(f) - 1 else x for i, x in enumerate(f))).rstrip())
                L.append(' ' * ind + '<endtable>')
            elif t == 'detalle':
                L.append(' ' * ind + f"<detalle {it['nombre']}>")
            elif t == 'coment':
                L.append(' ' * ind + it['texto'])
    emitir(raiz['items'], 2)
    return L


# ------------------------------------------------------------------ envolver en paneles
def camino_celda(tok, por_attr):
    if tok == '-': return None
    return por_attr.get(tok.split(':')[0], ())


def hojas_de_tabla(tabla, por_attr):
    """Parte una tabla en pedazos según el panel de cada renglón. Devuelve [(camino, hoja)]."""
    hojas = []
    ultimo = ()
    grupo, clave_grupo = [], None
    op, primera = tabla.get('op', ''), [True]

    def nueva_tabla(filas):
        o = op
        if not primera[0]:   # el título de la tabla solo en el primer pedazo
            o = ' '.join(x for x in op.split() if '=' in x)
        primera[0] = False
        return {'t': 'tabla', 'filas': filas, 'op': o}

    def cerrar():
        nonlocal grupo, clave_grupo
        if not grupo: return
        tipo, cam, ramas = clave_grupo
        if tipo == 'simple':
            hojas.append((cam, nueva_tabla(grupo)))
        else:   # renglones con campos de paneles distintos, uno al lado del otro en GeneXus
            hijos = []
            for r in ramas:
                sub = []
                for fila in grupo:
                    cel = [x for x in fila if (camino_celda(x, por_attr) or ())[len(cam):len(cam) + 1] == (r,)]
                    if cel: sub.append(cel)
                hs = hojas_de_tabla({'filas': sub, 'op': ''}, por_attr)
                hijos.extend(plegar(hs, cam))
            sueltos = [[x for x in fila if (camino_celda(x, por_attr) or cam) == cam and x != '-'] for fila in grupo]
            sueltos = [f for f in sueltos if f]
            if sueltos: hojas.append((cam, nueva_tabla(sueltos)))
            hojas.append((cam, {'t': 'renglon', 'items': hijos}))
        grupo, clave_grupo = [], None

    for fila in tabla['filas']:
        caminos = [c for c in (camino_celda(x, por_attr) for x in fila) if c is not None]
        if not caminos:
            clave = clave_grupo or ('simple', ultimo, None)
        else:
            comun = caminos[0]
            for c in caminos[1:]:
                k = 0
                while k < min(len(comun), len(c)) and comun[k] == c[k]: k += 1
                comun = comun[:k]
            ramas = []
            for c in caminos:
                if len(c) > len(comun) and c[len(comun)] not in ramas: ramas.append(c[len(comun)])
            clave = ('simple', comun, None) if len(ramas) <= 1 and all(c == caminos[0] for c in caminos) else ('lado', comun, tuple(ramas))
            if clave[0] == 'simple' and ramas:   # todos en el mismo panel
                clave = ('simple', caminos[0], None)
        if clave != clave_grupo:
            cerrar()
            clave_grupo = clave
        grupo.append(fila)
        ultimo = clave[1]
    cerrar()
    return hojas


def plegar(hojas, base, info=None):
    """[(camino, hoja)] -> bloques anidados en paneles, a partir del camino 'base'."""
    raiz = []
    pila = [(base, raiz)]
    for camino, hoja in hojas:
        while not (len(pila[-1][0]) <= len(camino) and camino[:len(pila[-1][0])] == pila[-1][0]):
            pila.pop()
        for k in range(len(pila[-1][0]), len(camino)):
            pid = camino[k]
            p = {'t': 'panel', 'titulo': INFO[pid]['titulo'], 'cerrado': INFO[pid]['cerrado'], 'items': []}
            pila[-1][1].append(p)
            pila.append((camino[:k + 1], p['items']))
        pila[-1][1].append(hoja)
    return raiz


INFO = {}


def envolver(items, por_attr, por_grilla):
    hojas, ultimo = [], ()
    for it in items:
        if it['t'] == 'tabla':
            hs = hojas_de_tabla(it, por_attr)
            hojas.extend(hs)
            if hs: ultimo = hs[-1][0]
        elif it['t'] == 'detalle':
            c = por_grilla.get(it['nombre'], ())
            hojas.append((c, it)); ultimo = c
        else:   # comentario: queda con lo anterior
            hojas.append((ultimo, it))
    return plegar(hojas, ())


# ------------------------------------------------------------------ principal
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--cache', required=True)
    ap.add_argument('--trn', required=True, help='carpeta src/main/resources/trn')
    ap.add_argument('--solo', default='')
    ap.add_argument('--probar', action='store_true')
    a = ap.parse_args()
    kb = json.load(open(a.cache, encoding='utf-8')) if os.path.exists(a.cache) else None
    if kb is None or kb.get('_version') != 8:
        kb = g.leer_kb(a.xml, {'*'}); kb['_trn'] = '*'
        json.dump(kb, open(a.cache, 'w', encoding='utf-8'), ensure_ascii=False)
    solo = {x.strip().lower() for x in a.solo.split(',') if x.strip()}
    hechos, salteados = [], []
    for f in sorted(os.listdir(a.trn)):
        if not f.endswith('.yaml'): continue
        n = f[:-5]
        if solo and n.lower() not in solo: continue
        obj = kb['objetivos'].get(n)
        if not obj or 'Collapsible="True"' not in (obj.get('webform') or ''): continue
        niveles = {lv['nombre']: {x['n'] for x in lv['atts']} for lv in kb['trns'][n]['niveles'][0]['niveles']}
        por_attr, por_grilla, info = paneles_del_webform(obj['webform'], niveles)
        INFO.clear(); INFO.update(info)
        ruta = os.path.join(a.trn, f)
        txt = open(ruta, encoding='utf-8').read()
        m = re.search(r'^pantalla: \|\n((?:  .*\n|\n)*)', txt, re.M)
        if not m:
            salteados.append((n, 'no tiene pantalla')); continue
        cuerpo = m.group(1)
        try:
            raiz = leer_pantalla('\n'.join(l[2:] if l.startswith('  ') else l for l in cuerpo.split('\n')))
        except ValueError as e:
            salteados.append((n, str(e))); continue
        antes = json.dumps(raiz, sort_keys=True)
        if any(it['t'] == 'solapa' for it in raiz['items']):
            for it in raiz['items']:
                if it['t'] == 'solapa':
                    it['items'] = envolver(it['items'], por_attr, por_grilla)
        else:
            raiz['items'] = envolver(raiz['items'], por_attr, por_grilla)
        cant = json.dumps(raiz).count('"t": "panel"')
        if not cant:
            salteados.append((n, 'sus paneles plegables no tienen campos de la pantalla')); continue
        nuevo = '\n'.join(texto_pantalla(raiz)) + '\n'
        if not a.probar:
            fin = m.end(1)
            # el bloque capturado puede terminar con renglones vacíos: se conservan
            vacios = len(cuerpo) - len(cuerpo.rstrip('\n')) - 1
            open(ruta, 'w', encoding='utf-8').write(txt[:m.start(1)] + nuevo + '\n' * max(0, vacios) + txt[fin:])
        hechos.append((n, cant))
    print('Con paneles:', len(hechos), '·', sum(c for _, c in hechos), 'paneles')
    for n, c in hechos: print(f'  {n}: {c}')
    if salteados:
        print('Salteadas:')
        for n, r in salteados: print(f'  {n}: {r}')


if __name__ == '__main__':
    main()
