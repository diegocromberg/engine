#!/usr/bin/env python3
"""
E4e - Importa TRN de una KB de GeneXus a una aplicación de OPTIMIST Engine, con TODO el flujo con que se migró OPTIMIST ERP
(regla del importador: CLAUDE.md del motor). Es lo que corre el botón "Importar de GeneXus" del Designer (adentro del
navegador, con Pyodide) y también se puede correr a mano:

  python3 importar_kb.py Factory.xml --recursos ../../factory/src/main/resources --trns Incidentes,Clientes

Pasos, para las TRN pedidas:
  1. la TRN (gx2yaml)                       5. acciones del WorkWith (acciones_gx)
  2. fórmulas redundantes y las que faltan   6. lo que llaman las acciones, las reglas y el inicio, hasta el final:
     (parchar_redundantes, parchar_formulas)    procedimientos (gx2proc), impresiones (gx2prn), Data Providers (gx2dp)
  3. paneles plegables (paneles_gx)              y web panels (gx2wbp)
  4. reglas y evento Start (parchar_reglas)  7. subtipos.yaml y clases_gx.yaml si la aplicación no los tiene
Al final arma el INFORME de lo que no se pudo migrar (trn/_gx/_informe_importacion.txt y .json) y deja, en cada TRN
incompleta, el bloque "# ---- SIN MIGRAR" (el Designer lo muestra en una franja).

No pisa procedimientos, web panels ni impresiones que la aplicación ya tiene (pueden tener retoques a mano).
"""
import argparse, contextlib, io, json, os, re, sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gx2yaml as g
import gx2proc as gp
import parchar_formulas as pf
import parchar_redundantes as pr
import parchar_reglas as prg
import paneles_gx as pan
import acciones_gx as acc
import gx2wbp as gw
import gx2prn as gprn
import gx2dp as gdp
import subtipos_gx as gsub
import gx2clases as gcl

T_TRN = '1db606f2-af09-4cf9-a3b5-b481519d28f6'
T_PROC = '84a12160-f59b-4ad7-a683-ea4481ac23e9'
T_WBP = 'c9584656-94b6-4ccd-890f-332d11fc2c25'
T_DP = '2a9e9aba-d2de-4801-ae7f-5e3819222daf'
P_LAYOUT = 'c414ed00'
NATIVOS = {'loadwwpcontext', 'setwwpcontext', 'getwwpcontext'}   # los resuelve el motor
SIN_MIGRAR = '# ---------------------------------------------------------------- SIN MIGRAR'

AVISAR = print   # el Designer lo cambia para mostrar el avance


def correr(modulo, args):
    """Corre el main() de una herramienta con esos argumentos; devuelve lo que escribió."""
    salida = io.StringIO()
    viejo = sys.argv
    sys.argv = [modulo.__name__ + '.py'] + [str(a) for a in args]
    try:
        with contextlib.redirect_stdout(salida):
            try:
                modulo.main()
            except SystemExit:
                pass
    finally:
        sys.argv = viejo
    return salida.getvalue()


def objetos_kb(xml):
    """{nombre: (tipo, tiene_layout)} de todos los objetos de la KB (una pasada; caché al lado del XML)."""
    cache = os.path.join(os.path.dirname(os.path.abspath(xml)), 'objetos_v2.json')
    if os.path.exists(cache) and os.path.getmtime(cache) >= os.path.getmtime(xml):
        return {k: tuple(v) for k, v in json.load(open(cache, encoding='utf-8')).items()}
    r, pila = {}, []
    for ev, el in ET.iterparse(xml, events=('start', 'end')):
        if ev == 'start':
            pila.append(el.tag)
            continue
        pila.pop()
        if len(pila) == 2 and el.tag == 'Object':
            # una impresión: el código imprime bloques del Layout ("print Bloque") y el Layout los tiene
            fuente = ''.join(p.findtext('Source') or '' for p in el.findall('Part') if (p.get('type') or '').startswith('528d1c06'))
            capa = bool(re.search(r'(?im)^\s*print\s+[A-Za-z_]\w*\s*$', fuente)) and any(
                (p.get('type') or '').startswith(P_LAYOUT) for p in el.findall('Part'))
            r[el.get('name')] = (el.get('type'), capa)
            el.clear()
        elif len(pila) == 2:
            el.clear()
    json.dump(r, open(cache, 'w', encoding='utf-8'))
    return r


def llamados(texto):
    """Nombres que el código llama: X.Call(...), X.Udp(...), X.Link(...), Call(X, ...), Create(...) no."""
    c = g.sin_comentarios(texto or '')
    c = re.sub(r'"[^"\n]*"|\'[^\'\n]*\'', '""', c)
    r = set()
    for m in re.finditer(r'(?i)(?<![&\w.])([A-Za-z_][\w.]*?)\s*\.\s*(?:call|udp|link|popup|submit)\s*\(', c):
        r.add(m.group(1).split('.')[-1])
    for m in re.finditer(r'(?i)(?<![&\w.])call\s*\(\s*([A-Za-z_]\w*)', c):
        r.add(m.group(1))
    return r


def importar(xml, recursos, trns, avisar=None):
    avisar = avisar or AVISAR
    d_trn = os.path.join(recursos, 'trn')
    d_proc = os.path.join(recursos, 'proc')
    d_wbp = os.path.join(recursos, 'wbp')
    d_imp = os.path.join(recursos, 'impresiones')
    for d in (d_trn, d_proc):
        os.makedirs(d, exist_ok=True)
    avisar('Leyendo la KB…', 0.02)
    kb = pf.cargar_kb(xml)
    pf.GLOBALES.clear()   # los dominios de ESTA aplicación (el Designer puede importar a otra en el mismo Python)
    globales = os.path.join(recursos, 'dominios.yaml')
    if os.path.exists(globales):
        import yaml
        pf.GLOBALES.update(str(k) for k in ((yaml.safe_load(open(globales, encoding='utf-8')) or {}).get('dominios') or {}))
    objetos = objetos_kb(xml)
    por_minus = {k.lower(): k for k in objetos}
    hechas, errores = [], {}
    # 1. las TRN
    for i, n in enumerate(trns):
        avisar(f'Convirtiendo {n} ({i + 1} de {len(trns)})…', 0.05 + 0.25 * i / max(1, len(trns)))
        try:
            y = g.generar(kb, n, set())
        except Exception as e:
            errores[n] = f'{type(e).__name__}: {e}'
            continue
        open(os.path.join(d_trn, n + '.yaml'), 'w', encoding='utf-8').write(y)
        hechas.append(n)
    if not hechas:
        return armar_informe(recursos, kb, [], errores, {}, objetos)
    solo = ','.join(hechas)
    # 2-5
    avisar('Fórmulas…', 0.32)
    correr(pr, [xml, '--trn', d_trn, '--solo', solo])
    correr(pf, [xml, '--trn', d_trn, '--solo', solo])
    avisar('Paneles…', 0.38)
    correr(pan, [xml, '--cache', os.path.join(os.path.dirname(os.path.abspath(xml)), 'kb_v7.json'), '--trn', d_trn, '--solo', solo])
    avisar('Reglas y evento Start…', 0.45)
    correr(prg, [xml, '--trn', d_trn, '--proc', d_proc, '--solo', solo, '--pisar'])
    # 6. lo que llaman acciones, reglas, inicio, procedimientos y web panels (hasta que no aparezca nada nuevo)
    nuevos = {}   # nombre -> tipo ('procedimiento', 'impresión', 'data provider', 'web panel')
    lista_reglas = os.path.join(d_proc, '_nuevos.txt')   # los que trajo parchar_reglas (los llaman reglas e inicio)
    if os.path.exists(lista_reglas):
        for x in open(lista_reglas, encoding='utf-8').read().split():
            capa = objetos.get(x, (None, False))[1]
            nuevos[x] = 'impresión' if capa else 'procedimiento'
            if capa:
                correr(gprn, [xml, x, '--salida', d_imp])
        os.remove(lista_reglas)
    procs_kb = gp.leer_procs(xml)
    usos = {}     # nombre llamado -> quién lo llama
    for vuelta in range(8):
        avisar('Acciones del WorkWith y lo que llaman…' if vuelta == 0 else f'Lo que llaman los procedimientos (vuelta {vuelta + 1})…',
               min(0.9, 0.55 + 0.05 * vuelta))
        correr(acc, [xml, '--trn', d_trn, '--solo', solo])
        tienen = {f[:-5].lower() for d in (d_proc, d_wbp) if os.path.isdir(d) for f in os.listdir(d) if f.endswith(('.proc', '.yaml'))}
        pedir = {}
        textos = [(n, open(os.path.join(d_trn, n + '.yaml'), encoding='utf-8').read()) for n in hechas]
        # lo nuevo y también lo que ya estaba y se usa (E4e: para seguir lo que llama e informarlo)
        usados = {k.lower() for k in usos}
        textos += [(f[:-5], open(os.path.join(d, f), encoding='utf-8').read()) for d in (d_proc, d_wbp) if os.path.isdir(d)
                   for f in os.listdir(d) if f.endswith(('.proc', '.yaml')) and (f[:-5] in nuevos or f[:-5].lower() in usados)]
        cuantos = len(usos)
        for quien, t in textos:
            nombres = llamados(t) | {m.group(1) for m in re.finditer(r'(?:\bproc|\bpanel):\s*([A-Za-z_]\w*)', t)}
            for x in nombres:
                k = x.lower()
                if k in NATIVOS:
                    continue
                real = por_minus.get(k)
                if real is not None or k not in tienen:
                    usos.setdefault(real or x, set()).add(quien)
                if real is None or k in tienen:
                    continue
                tipo, capa = objetos[real]
                if tipo == T_PROC:
                    pedir[real] = 'impresión' if capa else 'procedimiento'
                elif (tipo or '').startswith(T_DP[:8]):
                    pedir[real] = 'data provider'
                elif tipo == T_WBP and not real.lower().startswith('ww'):
                    pedir[real] = 'web panel'
        if not pedir and len(usos) == cuantos:
            break
        for real, tipo in sorted(pedir.items()):
            avisar(f'{tipo.capitalize()} {real}…', min(0.92, 0.6 + 0.05 * vuelta))
            if tipo in ('procedimiento', 'impresión'):
                txt, _ = gp.armar(real, procs_kb[real], kb, gp.enumerados(kb))
                open(os.path.join(d_proc, real + '.proc'), 'w', encoding='utf-8').write(txt)
                if tipo == 'impresión':
                    correr(gprn, [xml, real, '--salida', d_imp])
            elif tipo == 'data provider':
                correr(gdp, [xml, real, '--salida', d_proc])
            else:
                correr(gw, [xml, real, '--salida', d_wbp])
            nuevos[real] = tipo
    # 7. subtipos y clases del tema (de toda la KB), si la aplicación no los tiene
    if not os.path.exists(os.path.join(recursos, 'subtipos.yaml')):
        correr(gsub, [xml, '--salida', os.path.join(recursos, 'subtipos.yaml')])
    if not os.path.exists(os.path.join(recursos, 'clases_gx.yaml')):
        correr(gcl, [xml, '--salida', os.path.join(recursos, 'clases_gx.yaml')])
    avisar('Armando el informe…', 0.96)
    return armar_informe(recursos, kb, hechas, errores, nuevos, objetos, usos)


# ----------------------------------------------------------------------------- informe

BLOQUE = re.compile(r'(?is)^\s*(Event|Sub)\s+(.+?)\s*$(.*?)^\s*End(?:Event|Sub)\b', re.M)
SOLO_PANTALLA = re.compile(r'(?i)^\s*(GridLevel_\w+\.AddLines\s*\(.*\)|[A-Za-z_]\w*_Cell\.Class\s*=.*|Return|'
                           r'GXUITabsPanel\w*\.\w+\s*\(.*\))\s*;?\s*$')


def cuerpo_util(cuerpo):
    """El código de un evento sin lo del patrón WorkWithPlus, sin [web]/[win] y sin comentarios."""
    t = prg.GEN.sub('', cuerpo)
    t = prg.bloques_plataforma(t)
    return [l.strip() for l in g.sin_comentarios(t).split('\n') if l.strip()]


def eventos_sin_migrar(eventos):
    """Los eventos de la TRN que no se pasan: todos menos Start (va a 'inicio:') y los que son solo del patrón o de la
    pantalla de GeneXus (ej. 'AgregarFilas': el motor tiene su propio Agregar Filas)."""
    r = []
    for m in BLOQUE.finditer((eventos or '').replace('\r\n', '\n')):
        tipo, nom = m.group(1).capitalize(), g.sin_comentarios(m.group(2)).strip()
        if tipo == 'Event' and re.fullmatch(r'(?i)start', nom):
            continue
        util = [l for l in cuerpo_util(m.group(3)) if not SOLO_PANTALLA.match(l)]
        if util:
            r.append(f'{"Evento" if tipo == "Event" else "Subrutina"} {nom}')
    return r


def seguridad_wwp(eventos):
    """Permisos de WorkWithPlus (SecIsAuthByFunctionalityKey) que el motor todavía no aplica."""
    t = eventos or ''
    r = []
    modos = sorted(set(re.findall(r"SecIsAuthByFunctionalityKey(?:\.Udp)?\(\s*!?'WW\w+?_(Insert|Update|Delete)'", t)))
    if modos:
        es = {'Insert': 'alta', 'Update': 'modificación', 'Delete': 'baja'}
        r.append('Permisos de WorkWithPlus para ' + ', '.join(es[x] for x in ('Insert', 'Update', 'Delete') if x in modos)
                 + ' (SecIsAuthByFunctionalityKey): el motor todavía no los aplica; cualquier usuario puede hacerlo')
    attrs = re.findall(r"SecIsAuthByFunctionalityKey(?:\.Udp)?\(\s*!?'(\w+?)_Attribute'", t)
    if attrs:
        r.append('Permisos de WorkWithPlus por campo (' + ', '.join(dict.fromkeys(attrs))
                 + '): en GeneXus se ocultan a quien no tiene el permiso; el motor todavía no los aplica')
    return r


def revisar_de(texto):
    r = []
    for l in texto.split('\n'):
        s = l.strip()
        m = re.search(r'REVISAR(?: \(de la pantalla de GeneXus\))?:\s*(.+)$', s)
        if m:
            r.append(m.group(1).strip())
    return r


def armar_informe(recursos, kb, hechas, errores, nuevos, objetos, usos=None):
    usos = usos or {}
    d_trn = os.path.join(recursos, 'trn')
    inf = {'trns': {}, 'objetos': {}, 'faltan': [], 'errores': errores}
    os.makedirs(os.path.join(d_trn, '_gx'), exist_ok=True)
    for n in hechas:
        ruta = os.path.join(d_trn, n + '.yaml')
        texto = open(ruta, encoding='utf-8').read()
        items = []
        ev = (kb.get('objetivos', {}).get(n) or {}).get('eventos')
        for e in eventos_sin_migrar(ev):
            items.append(f"{e}: no se migró (su código está en _gx/{n}.gx.txt)")
        items += seguridad_wwp(ev)
        for r in revisar_de(texto):
            items.append('Revisar: ' + r)
        m = re.search(r'^# Atributos con tipos no soportados todavía[^:]*:\s*(.+)$', texto, re.M)
        if m:
            items.append('Atributos de tipos que el motor no maneja (no están en la TRN): ' + m.group(1).strip())
        m = re.search(r'^# Omitidos[^:]*:\s*(.+)$', texto, re.M)
        if m:
            items.append('Atributos omitidos (en GeneXus son fórmulas o vienen de otra tabla): ' + m.group(1).strip())
        if re.search(r'^# -+ PENDIENTE', texto, re.M):
            for l in texto[texto.index('PENDIENTE'):].split('\n')[2:]:
                if l.startswith('#   '):
                    items.append('Regla sin migrar: ' + l[4:].strip())
        inf['trns'][n] = items
        open(os.path.join(d_trn, '_gx', n + '.gx.txt'), 'w', encoding='utf-8').write(texto_para_ia(kb, n, items))
        # el bloque SIN MIGRAR al final del YAML (lo muestra el Designer)
        texto = quitar_sin_migrar(texto)
        if items:
            texto = texto.rstrip('\n') + '\n\n' + SIN_MIGRAR + '\n' \
                    + '# Lo que GeneXus tenía y esta TRN todavía no hace (detalle: trn/_gx/_informe_importacion.txt):\n' \
                    + ''.join(f'#   - {x}\n' for x in items)
        open(ruta, 'w', encoding='utf-8').write(texto)
    for real, tipo in sorted(nuevos.items()):
        sub = 'wbp' if tipo == 'web panel' else 'proc'
        ruta = os.path.join(recursos, sub, real + ('.yaml' if sub == 'wbp' else '.proc'))
        txt = open(ruta, encoding='utf-8').read() if os.path.exists(ruta) else ''
        inf['objetos'][real] = {'tipo': tipo, 'revisar': revisar_de(txt) if txt else ['no se pudo traducir'],
                                'usan': sorted(usos.get(real, []))}
    # E4e: lo que la aplicación ya tenía y usan las TRN: también va al informe (con lo que tenga para revisar)
    for x in sorted(usos):
        if x in nuevos or x in inf['objetos']:
            continue
        for sub, ext, tipo in (('proc', '.proc', 'procedimiento'), ('wbp', '.yaml', 'web panel')):
            ruta = os.path.join(recursos, sub, x + ext)
            if os.path.exists(ruta):
                txt = open(ruta, encoding='utf-8').read()
                if tipo == 'procedimiento' and os.path.exists(os.path.join(recursos, 'impresiones', x + '.yaml')):
                    tipo = 'impresión'
                o = {'tipo': tipo, 'revisar': revisar_de(txt), 'usan': sorted(usos[x]), 'yaEstaba': True}
                if re.search(r'(?i)SMTPSession|&SMTP\b', txt):
                    o['nota'] = 'manda mails: el servidor de correo de cada cliente va en su optimist.cfg (mail.host, mail.usuario, mail.clave…)'
                inf['objetos'][x] = o
                break
    for x, quien in sorted(usos.items()):
        if x not in objetos and x.lower() not in {k.lower() for k in objetos}:
            inf['faltan'].append({'nombre': x, 'usan': sorted(quien)})
    os.makedirs(os.path.join(d_trn, '_gx'), exist_ok=True)
    json.dump(inf, open(os.path.join(d_trn, '_gx', '_informe_importacion.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    open(os.path.join(d_trn, '_gx', '_informe_importacion.txt'), 'w', encoding='utf-8').write(texto_informe(inf))
    return inf


def texto_para_ia(kb, n, items):
    """trn/_gx/<TRN>.gx.txt: lo que no se migró y el código original de GeneXus, para pasárselo a la IA de cada uno."""
    o = kb.get('objetivos', {}).get(n) or {}
    l = [f'# {n} — lo que el importador de OPTIMIST Engine no pudo migrar', '',
         f'TRN: {n} · {(kb.get("trns", {}).get(n) or {}).get("desc") or ""}', '',
         f'Cómo usarlo: pasale este archivo y trn/{n}.yaml a la IA que uses, junto con GUIA_CONFIGURACION_TRN.md de OPTIMIST Engine,',
         'y pedile que resuelva lo pendiente de abajo en el YAML (reglas:, inicio:) o en procedimientos .proc, respetando la guía.', '',
         '## Pendiente', '\n'.join('- ' + x for x in items) if items else '(nada: la TRN quedó completa)', '',
         '## Reglas (completas, como están en GeneXus)', '```', (o.get('reglas') or '').strip(), '```', '',
         '## Eventos (completos, como están en GeneXus)', '```', (o.get('eventos') or '').strip(), '```']
    return '\n'.join(l) + '\n'


def preinforme(xml):
    """Antes de importar: lo que ya se sabe que no se va a poder migrar de cada TRN (eventos, permisos de WorkWithPlus)."""
    kb = pf.cargar_kb(xml)
    r = {}
    for n, o in (kb.get('objetivos') or {}).items():
        ev = o.get('eventos')
        items = [f'{e}: no se migra' for e in eventos_sin_migrar(ev)] + seguridad_wwp(ev)
        if items:
            r[n] = items
    return r


def quitar_sin_migrar(texto):
    i = texto.find(SIN_MIGRAR)
    return texto if i < 0 else texto[:i].rstrip('\n') + '\n'


def texto_informe(inf):
    l = ['INFORME DE IMPORTACIÓN', '======================', '']
    total = sum(len(v) for v in inf['trns'].values()) + sum(len(o['revisar']) for o in inf['objetos'].values()) \
        + len(inf['faltan']) + len(inf['errores'])
    l.append('Todo lo de GeneXus quedó migrado.' if total == 0 else
             f'Quedaron {total} cosas sin migrar o para revisar. Hasta resolverlas, la aplicación NO hace todo lo que hacía GeneXus.')
    if inf['errores']:
        l += ['', 'TRN que no se pudieron importar:'] + [f'  - {n}: {e}' for n, e in inf['errores'].items()]
    for n, items in inf['trns'].items():
        l += ['', f'TRN {n}: ' + ('completa' if not items else f'{len(items)} pendiente' + ('s' if len(items) > 1 else ''))] + [f'  - {x}' for x in items]
    if inf['objetos']:
        l += ['', 'Objetos que usan las TRN (los nuevos se trajeron de GeneXus):']
        for n, o in inf['objetos'].items():
            l.append(f'  {o["tipo"]} {n}' + (f' (lo usa {", ".join(o["usan"])})' if o['usan'] else '')
                     + (' — ya estaba en la aplicación, no se tocó' if o.get('yaEstaba') else '')
                     + ('' if not o['revisar'] else f': {len(o["revisar"])} para revisar'))
            l += [f'      - {x}' for x in o['revisar']]
            if o.get('nota'):
                l.append(f'      · {o["nota"]}')
    if inf['faltan']:
        l += ['', 'Llamados a objetos que no están en el export de la KB (exportalos también y volvé a importar):']
        l += [f'  - {x["nombre"]} (lo usa {", ".join(x["usan"])})' for x in inf['faltan']]
    return '\n'.join(l) + '\n'


# ----------------------------------------------------------------------------- Designer (Pyodide)

def preparar_kb(ruta, trabajo):
    """El XML de la KB en una carpeta propia (ahí van las cachés). Un XPZ se descomprime; un XML se enlaza (no se copia)."""
    os.makedirs(trabajo, exist_ok=True)
    if ruta.lower().endswith('.xpz'):
        import shutil, zipfile
        with zipfile.ZipFile(ruta) as z:
            n = next((x for x in z.namelist() if x.lower().endswith('.xml')), None)
            if not n:
                raise ValueError('el XPZ no tiene adentro el XML de la KB')
            destino = os.path.join(trabajo, os.path.basename(n))
            with z.open(n) as i, open(destino, 'wb') as o:
                shutil.copyfileobj(i, o)
        return destino
    destino = os.path.join(trabajo, os.path.basename(ruta))
    if not os.path.lexists(destino):
        os.symlink(ruta, destino)
    return destino


def foto(recursos):
    """{ruta relativa: hash} de todos los archivos de la aplicación (para saber después qué cambió)."""
    import hashlib
    r = {}
    for raiz, _, archivos in os.walk(recursos):
        for f in archivos:
            ruta = os.path.join(raiz, f)
            r[os.path.relpath(ruta, recursos).replace(os.sep, '/')] = hashlib.sha1(open(ruta, 'rb').read()).hexdigest()
    return r


def importar_designer(xml, recursos, trns, avisar):
    """Lo que corre el Designer: importa en la copia de la aplicación y devuelve el informe y qué archivos son nuevos o
    cambiaron (JSON). El Designer los graba en la carpeta real con sus reglas (.bak de las TRN; no pisa procedimientos)."""
    antes = foto(recursos)
    inf = importar(xml, recursos, trns, avisar)
    despues = foto(recursos)
    nuevos = sorted(k for k in despues if k not in antes)
    cambiados = sorted(k for k in despues if k in antes and antes[k] != despues[k])
    return json.dumps({'informe': inf, 'texto': texto_informe(inf), 'nuevos': nuevos, 'cambiados': cambiados}, ensure_ascii=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--recursos', required=True, help='src/main/resources de la aplicación')
    ap.add_argument('--trns', required=True, help='TRN separadas por coma')
    a = ap.parse_args()
    inf = importar(a.xml, a.recursos, [x.strip() for x in a.trns.split(',') if x.strip()],
                   lambda t, f=None: print(t, flush=True))
    print(texto_informe(inf))


if __name__ == '__main__':
    main()
