#!/usr/bin/env python3
"""
V21 - Generador GeneXus -> YAML del motor de TRN de OPTIMIST ERP.

Lee el export XML completo de la KB (streaming, no carga los 200 MB en memoria)
y genera src/main/resources/trn/<Trn>.yaml con:
  - campos de la cabecera y de cada nivel, con su tipo real (dominio / subtipo)
  - qué atributos se guardan en la tabla y cuáles se infieren de otra (referencias)
  - valores de los dominios enumerados (combos)
  - solapas y a qué solapa va cada campo / grilla (según el web form)
  - reglas simples: NoAccept, Default con constante, Error ... if X.IsEmpty(), Serial,
    auditoría (Create/LastModified Date/UserName)
  - las reglas que el motor todavía no entiende quedan listadas como comentario "PENDIENTE"

Uso:
  python3 gx2yaml.py OptimistU12NEW.xml Articulos --solapas 1,2,4,6,7 --salida ../src/main/resources/trn
"""
import argparse, html, json, re, sys, os
import xml.etree.ElementTree as ET

T_DOM = '00972a17-9975-449e-aab1-d26165d51393'
T_TAB = '857ca50e-7905-0000-0007-c5d9ff2975ec'
T_TRN = '1db606f2-af09-4cf9-a3b5-b481519d28f6'
T_SUB = '87313f43-5eb2-41d7-9b8c-e8d9f5bf9588'
T_WP = 'c9584656-94b6-4ccd-890f-332d11fc2c25'   # web panel (los WW<trn> del patrón)
T_INST = '07135890-56fc-489b-b408-063722fa9f7d'   # E2: instancia del patrón WorkWithPlus (WorkWithPlus<Trn>)
P_STRUCT = '264be5fb-1b28-4b25-a598-6ca900dd059f'
P_WEBFORM = 'd24a58ad-57ba-41b7-9e6e-eaca3543c778'
P_RULES = '9b0a32a3-de6d-4be1-a4dd-1b85d3741534'
P_CONDITIONS = '763f0d8b-d8ac-4db4-8dd4-de8979f2b5b9'
P_EVENTS = 'c44bd5ff-f918-415b-98e6-aca44fed84fa'


# ----------------------------------------------------------------------------- lectura de la KB

def props(el):
    p = el.find('Properties')
    return {x.findtext('Name'): x.findtext('Value') for x in p.findall('Property')} if p is not None else {}


def nivel(l):
    return {'nombre': l.get('Name'), 'desc': l.get('Description'),
            'atts': [{'n': (a.text or '').strip(), 'key': a.get('key') == 'True',
                      'nullable': a.get('isNullable') == 'True',
                      'red': a.get('isRedundant') == 'True'} for a in l.findall('Attribute')],   # V41: fórmula redundante (se graba)
            'descAtt': l.findtext('DescriptionAttribute'),
            'niveles': [nivel(s) for s in l.findall('Level')]}


def normalizar_kb(kb):
    """V82m: algunos atributos traen su dominio en 'DomainKey' y no en 'idBasedOn' (ej. PRSEmpresa, EmpDiv). Sin esto
    caían en Numeric(4): PRSEmpresa.ToString() daba "  99" y la numeración "PRESUPUESTOS9999" arrancaba de nuevo."""
    for a in kb.get('attrs', {}).values():
        if a.get('DomainKey') and not a.get('idBasedOn') and not a.get('ATTCUSTOMTYPE'):
            a['idBasedOn'] = 'Domain:' + a['DomainKey']
    return kb


def leer_kb(path, trn_objetivo):
    """trn_objetivo: un nombre, un conjunto de nombres, o '*' (todas). V28: guarda web form y reglas de cada objetivo."""
    objetivos = trn_objetivo if isinstance(trn_objetivo, (set, frozenset)) else {trn_objetivo}
    kb = {'attrs': {}, 'doms': {}, 'subs': {}, 'tablas': {}, 'indices': {}, 'trns': {}, 'objetivo': None,
          'objetivos': {}, 'ww': {}, 'ww_cond': {}, 'ww_inst': {}, 'checksums': {}, '_version': 8}
    stack = []
    for ev, el in ET.iterparse(path, events=('start', 'end')):
        if ev == 'start':
            stack.append(el.tag)
            continue
        stack.pop()
        if len(stack) != 2:
            continue
        if el.tag == 'Attribute':
            kb['attrs'][el.get('name')] = props(el) | {'_desc': el.get('description')}
        elif el.tag == 'Object':
            t, n = el.get('type'), el.get('name')
            if t == T_DOM:
                kb['doms'][n] = props(el) | {'_desc': el.get('description')}
            elif t == T_SUB:
                for s in el.iter('Subtype'):
                    # V30: un subtipo puede estar en varios grupos (PRSEntidadesPK: STPRS_Entidades y STPRS_Contactos)
                    e = kb['subs'].setdefault(s.findtext('Name'), {'super': (s.findtext('Supertype') or '').strip(),
                                                                    'grupo': n, 'grupos': []})
                    e['grupos'].append(n)
            elif t == T_WP and n and n[:2].upper() == 'WW':
                # V30: grilla del WorkWith de GeneXus (columnas, títulos, visibles y orden)
                wf = next((p for p in el.findall('Part') if p.get('type') == P_WEBFORM), None)
                src = (wf.findtext('Source') or '') if wf is not None else ''
                m = re.search(r'<gxGrid .*?</gxGrid>', src, re.S)
                if m:
                    kb['ww'][n[2:].lower()] = m.group(0)
                cond = next((p for p in el.findall('Part') if p.get('type') == P_CONDITIONS), None)
                if cond is not None and (cond.findtext('Source') or '').strip():
                    kb['ww_cond'][n[2:].lower()] = cond.findtext('Source')
            elif t == T_INST:
                # E2: columnas, títulos, orden y lupa del WorkWith según la instancia del patrón (GeneXus 17: el web panel
                # del WW se llama <Trn>WW y no WW<Trn>; la instancia está igual en todas las versiones)
                d = el.find('Part/Data')
                r_ = ww_de_instancia(d.text if d is not None else '')
                if r_:
                    kb['ww_inst'][r_[0].lower()] = r_[1]
            elif t == T_TAB:
                keys = [i.text.strip() for i in el.iter('Item')] if el.find('Part') is not None else []
                # solo el Key del part de estructura (el primero); los índices no usan <Item>
                kb['tablas'][n] = keys
                # atributos que figuran en algún índice: si una fórmula está acá, es redundante (se guarda)
                kb['indices'][n] = sorted({(m.text or '').strip() for m in el.iter('Member')})
            elif t == T_TRN:
                kb['checksums'][n] = el.get('checksum') or ''   # E2: para saber si la TRN cambió en GeneXus después de importarla
                parts = {p.get('type'): p for p in el.findall('Part')}
                st = parts.get(P_STRUCT)
                if st is not None:
                    kb['trns'][n] = {'desc': el.get('description'), 'niveles': [nivel(l) for l in st.findall('Level')]}
                if n in objetivos or '*' in objetivos:
                    src = lambda k: (parts[k].findtext('Source') or '') if k in parts else ''
                    kb['objetivos'][n] = {'webform': src(P_WEBFORM), 'reglas': src(P_RULES), 'eventos': src(P_EVENTS)}
                    if n == trn_objetivo:
                        kb['objetivo'] = kb['objetivos'][n]
        el.clear()
    return normalizar_kb(kb)


def ww_de_instancia(data):
    """E2: (trn, {'cols': [(att, título)], 'orden': [(att, desc)], 'prompt': [(att, título)]}) de una instancia de
    WorkWithPlus, o None. El título vacío es el del atributo (=Attribute.ContextualTitle / ColumnTitle)."""
    data = (data or '').strip()
    if '<transaction' not in data:
        return None
    try:
        x = ET.fromstring(re.sub(r'^<\?xml[^>]*\?>', '', data))
    except ET.ParseError:
        return None
    tr = x.find('transaction')
    nombre = (tr.get('transaction') or '')[37:] if tr is not None else ''
    lv = x.find('level')
    if not nombre or lv is None:
        return None
    def att(e):
        return (e.get('attribute') or '')[37:]
    def grilla(nodo):
        g = nodo.find('.//grid') if nodo is not None else None
        if g is None:
            return []
        r = []
        for ga in g.findall('gridAttribute'):
            a = att(ga)
            if not a or ga.get('visible') == 'False':
                continue
            t = (ga.get('description') or '').strip()
            r.append((a, '' if t.startswith('=') else t))
        return r
    sel = lv.find('selection')
    orden = []
    o1 = sel.find('.//orders/order') if sel is not None else None
    if o1 is not None:
        orden = [(att(a), a.get('ascending') == 'False') for a in o1.findall('attribute') if att(a)]
    return nombre, {'cols': grilla(sel), 'orden': orden, 'prompt': grilla(lv.find('prompt'))}


# ----------------------------------------------------------------------------- modelo

# V32: dominios semánticos predefinidos de GeneXus (módulo GeneXus); no vienen en la exportación de la KB
DOMINIOS_GENEXUS = {
    'Address, GeneXus': {'ATTCUSTOMTYPE': 'bas:VarChar', 'Length': '1024'},
    'Email, GeneXus': {'ATTCUSTOMTYPE': 'bas:VarChar', 'Length': '100'},
    'Url, GeneXus': {'ATTCUSTOMTYPE': 'bas:VarChar', 'Length': '1000'},
    'Phone, GeneXus': {'ATTCUSTOMTYPE': 'bas:Character', 'Length': '20'},
    'Html, GeneXus': {'ATTCUSTOMTYPE': 'bas:LongVarChar', 'Length': '2000'},
    'Geolocation, GeneXus': {'ATTCUSTOMTYPE': 'bas:Character', 'Length': '50'},
    'Time, GeneXus': {'ATTCUSTOMTYPE': 'bas:DateTime'},
}


class Modelo:
    def __init__(self, kb):
        self.kb = kb
        self.tabla_por_claves = {frozenset(v): k for k, v in kb['tablas'].items() if v}
        self.claves_de = {k: set(v) for k, v in kb['tablas'].items()}
        self.todas_las_claves = set().union(*self.claves_de.values()) if self.claves_de else set()
        # atributos no clave vistos en cada tabla (normalizados a supertipo)
        self.no_claves = {}
        self.no_claves_lit = {}
        self._niveles = []
        for trn in kb['trns'].values():
            for lv in trn['niveles']:
                self._recorrer(lv, [])
        # V30: de esos, los que la tabla GUARDA de verdad. En un nivel, un atributo que es columna de otra
        # tabla cuya clave está completa en el nivel es inferido ahí (ej. en Transportes,
        # PaisProvinciaNombreCorto viene de PaisProvincia): no es columna de Transportes.
        base, base_lit = self.no_claves, self.no_claves_lit
        for _pasada in range(2):   # 2da pasada: contra lo que la otra tabla guarda, no contra lo que muestra
            self.guardados, self.guardados_lit = {}, {}
            for tabla, atts, todos in self._niveles:
                for n in atts:
                    if not self._inferido_en(n, tabla, todos, base, base_lit):
                        self.guardados.setdefault(tabla, set()).update((n, self.norm(n)))
                        self.guardados_lit.setdefault(tabla, set()).add(n)
            base, base_lit = self.guardados, self.guardados_lit

    def _inferido_en(self, n, tabla, todos, base, base_lit):
        gs = self.grupos(n)
        if gs:
            # subtipo: lo infieren los miembros de SU grupo (puede ser la misma tabla: PlanCtasBienesDeUsoDescrip)
            miembros = {self.norm(c) for c in todos if self.grupos(c) & gs}
            na = self.norm(n)
            # un grupo de subtipos que contiene la clave completa de una tabla infiere todos sus demás miembros
            return any(k2 and k2 <= miembros and na not in k2 for t2, k2 in self.claves_de.items())
        lit = set(todos)
        return any(t2 != tabla and k2 and k2 <= lit and n not in k2 and n in base_lit.get(t2, ())
                   for t2, k2 in self.claves_de.items())

    def _recorrer(self, lv, claves_padre):
        claves = claves_padre + [a['n'] for a in lv['atts'] if a['key']]
        tabla = self.tabla_por_claves.get(frozenset(claves))
        if tabla:
            s = self.no_claves.setdefault(tabla, set())
            sl = self.no_claves_lit.setdefault(tabla, set())
            atts = [a['n'] for a in lv['atts'] if not a['key'] and not self.formula(a['n'])]
            for n in atts:
                s.add(n)
                s.add(self.norm(n))
                sl.add(n)
            self._niveles.append((tabla, atts, claves + [a['n'] for a in lv['atts']]))
        for sub in lv['niveles']:
            self._recorrer(sub, claves)

    def columna_en(self, n, tabla):
        """Nombre real de la columna en 'tabla' para el atributo n (subiendo por los supertipos)."""
        visto = set()
        a = n
        while a not in visto:
            if a in self.guardados_lit.get(tabla, ()):
                return a
            visto.add(a)
            if a not in self.kb['subs']:
                break
            a = self.kb['subs'][a]['super']
        return self.norm(n)

    def norm(self, a):
        visto = set()
        while a in self.kb['subs'] and a not in visto:
            visto.add(a)
            a = self.kb['subs'][a]['super']
        return a

    def grupos(self, a):
        s = self.kb['subs'].get(a)
        return set(s.get('grupos') or [s['grupo']]) if s else set()

    def grupo(self, a):
        s = self.kb['subs'].get(a)
        return s['grupo'] if s else None

    def formula(self, a):
        return (self.kb['attrs'].get(a) or {}).get('Formula')

    # --- tipos
    def tipo(self, a):
        """(nombre_dominio, 'Tipo(l.d)', valores_enum|None, props_attr)"""
        base = self.norm(a)
        p = self.kb['attrs'].get(a, {})
        pb = self.kb['attrs'].get(base, {})
        fuente = p if (p.get('ATTCUSTOMTYPE') or p.get('idBasedOn')) else pb
        dom = None
        if (fuente.get('idBasedOn') or '').startswith('Domain:'):
            dom = fuente['idBasedOn'].split(':', 1)[1]
            if dom not in self.kb['doms'] and dom in DOMINIOS_GENEXUS:
                # V32: dominios semánticos que trae GeneXus (no están en la KB): antes caían en Numeric(4)
                fuente = DOMINIOS_GENEXUS[dom] | {k: v for k, v in fuente.items() if k in ('ControlType',)}
            else:
                fuente = self.kb['doms'].get(dom, {}) | {k: v for k, v in fuente.items() if k in ('ControlType',)}
        t = (fuente.get('ATTCUSTOMTYPE') or 'bas:Numeric').split(':')[-1]
        lon = int(fuente.get('Length') or 0)
        dec = int(fuente.get('Decimals') or 0)
        mapa = {'Numeric': 'Numeric', 'Character': 'Character', 'VarChar': 'VarChar', 'LongVarChar': 'VarChar',
                'Date': 'Date', 'DateTime': 'DateTime', 'Boolean': 'Boolean', 'GUID': 'GUID'}
        tt = mapa.get(t)
        if tt == 'GUID':
            return dom, 'GUID', None, fuente   # V83: el motor lo trata como texto de 36
        if tt is None:
            return dom, None, None, fuente  # Blob, Image, GUID, etc: no soportado todavía
        if tt == 'Numeric':
            lon = lon or 4
            txt = f'Numeric({lon}.{dec})' if dec else f'Numeric({lon})'
        elif tt in ('Character', 'VarChar'):
            if t == 'LongVarChar' and lon < 1:
                lon = 2097152   # V83: LongVarChar sin largo = el de GeneXus (2 MB); antes caía en 20 por la línea de abajo
            lon = lon or 20
            txt = f'{tt}({lon})'
        else:
            txt = tt
        enum = None
        if fuente.get('IDEnumDefinedValues'):
            enum = []
            for parte in fuente['IDEnumDefinedValues'].split(';'):
                v = [x.strip() for x in parte.split(',', 2)]
                if len(v) == 3:
                    enum.append({'valor': v[0].strip('"'), 'nombre': v[1], 'texto': v[2]})
        if enum is None and tt != 'Boolean':
            # V28: combo definido en el ATRIBUTO (Control Type = Combo Box + Control Values), no en un dominio
            for fp in (p, pb):
                if fp.get('ControlType') == 'Combo Box' and fp.get('ControlValues'):
                    enum = []
                    for parte in fp['ControlValues'].split(','):
                        if ':' not in parte:
                            continue
                        texto, valor = parte.rsplit(':', 1)
                        enum.append({'valor': valor.strip().strip('"'), 'nombre': texto.strip(), 'texto': texto.strip()})
                    if enum:
                        dom = base[0].upper() + base[1:] if dom is None else dom
                    else:
                        enum = None
                    break
        return dom, txt, enum, fuente

    def valor_inicial(self, a):
        """V28: Initial Value del atributo (solo constantes simples), o None."""
        p = self.kb['attrs'].get(a) or {}
        v = (p.get('ATT_INITIAL_VALUE') or '').strip()
        if not v:
            return None
        if v.lower() in ('true', 'false'):
            return v.lower() == 'true'
        if re.fullmatch(r'-?\d+', v):
            return int(v)
        if re.fullmatch(r'-?\d+\.\d+', v):
            return float(v)
        m = re.fullmatch(r'["\'](.*)["\']', v)
        return m.group(1) if m else None

    def autonumerico(self, a):
        dom, _, _, f = self.tipo(a)
        return (f.get('AUTONUMBER') == 'True') or ((self.kb['attrs'].get(a) or {}).get('AUTONUMBER') == 'True')

    def etiqueta(self, a):
        p = self.kb['attrs'].get(a) or self.kb['attrs'].get(self.norm(a)) or {}
        return p.get('ContextualTitle') or p.get('Description') or p.get('_desc') or a

    # --- clasificación de atributos de un nivel
    def analizar_nivel(self, atts, claves_padre, ctx_extra):
        """Devuelve dict con tabla, claves, propios, inferidos (attr->ref), formulas, referencias."""
        claves = claves_padre + [a['n'] for a in atts if a['key']]
        tabla = self.tabla_por_claves.get(frozenset(claves))
        ctx = [a['n'] for a in atts] + claves_padre + ctx_extra
        ctx_norm = {}
        for c in ctx:
            ctx_norm.setdefault(self.norm(c), []).append(c)
        propios, inferidos, formulas, descartados = [], {}, [], []
        refs = {}
        ctx_simple = {c for c in ctx if not self.grupos(c)}
        for a in atts:
            n = a['n']
            if a['key']:
                continue
            if self.formula(n):
                formulas.append(n)
                continue
            if self.formula(self.norm(n)):
                descartados.append(n)   # V30: subtipo de una fórmula (ej. GetContactosTelefono.udp): no es columna
                continue
            gs = self.grupos(n)
            elegido = None
            if gs:
                # subtipo: se infiere por los miembros de SU grupo presentes en el nivel
                miembros = {self.norm(c): c for c in ctx if self.grupos(c) & gs}
                na = self.norm(n)
                cands = [t2 for t2, k2 in self.claves_de.items()
                         if k2 and k2 <= set(miembros) and na not in k2 and na in self.guardados.get(t2, ())]
                if not cands:   # tabla sin transacción propia en la KB (ej. Contactos): lo que se vio en ella
                    cands = [t2 for t2, k2 in self.claves_de.items()
                             if k2 and k2 <= set(miembros) and na not in k2 and na in self.no_claves.get(t2, ())]
                if cands:
                    t2 = max(cands, key=lambda t: len(self.claves_de[t]))   # V30: la más específica (EntidadesDir antes que Entidades)
                    elegido = (t2, tuple((miembros[k], k) for k in sorted(self.claves_de[t2])), self.columna_en(n, t2))
            if elegido is None:
                # también un subtipo puede venir por OTRA clave foránea que lo tiene como columna propia
                # (ej. TipoArtsConceptoCMPId llega por TipoArtsId)
                cands = [t2 for t2, k2 in self.claves_de.items()
                         if k2 and t2 != tabla and k2 <= ctx_simple and n not in k2 and n in self.guardados.get(t2, ())]
                if cands:
                    t2 = max(cands, key=lambda t: (len(os.path.commonprefix([t, n])), -len(self.claves_de[t])))
                    elegido = (t2, tuple((k, k) for k in sorted(self.claves_de[t2])), n)
            if elegido is None and gs and self.norm(n) not in self.todas_las_claves and any(
                    self.norm(x) in self.todas_las_claves for x, e in self.kb['subs'].items() if gs & set(e.get('grupos') or [e['grupo']])):
                # V30: subtipo de un grupo que apunta a otra tabla, pero la clave de ese grupo no está en el nivel
                # (llega por otra tabla, ej. UnidadConsumoDescort vía el artículo): no es columna; se omite
                descartados.append(n)
                continue
            if elegido is None:
                propios.append(n)
                continue
            t2, fk, col = elegido
            refs.setdefault((t2, fk), []).append((n, col))
            inferidos[n] = (t2, fk)
        return {'tabla': tabla, 'claves': claves, 'propios': propios, 'inferidos': inferidos,
                'formulas': formulas, 'refs': refs, 'descartados': descartados}

    def atributo_descripcion(self, tabla):
        """DescriptionAttribute de la transacción/nivel que define la tabla (para el buscador)."""
        for trn in self.kb['trns'].values():
            for lv in trn['niveles']:
                r = self._buscar_desc(lv, [], tabla)
                if r:
                    return r
        return None

    def _buscar_desc(self, lv, cp, tabla):
        claves = cp + [a['n'] for a in lv['atts'] if a['key']]
        if self.tabla_por_claves.get(frozenset(claves)) == tabla and sin_none(lv.get('descAtt')):
            return sin_none(lv.get('descAtt'))
        for s in lv['niveles']:
            r = self._buscar_desc(s, claves, tabla)
            if r:
                return r
        return None


# ----------------------------------------------------------------------------- web form y reglas

def gx17_a_html(s):
    """
    V83: el web form "layout" de GeneXus 17 (WorkWithPlus nuevo: <table><row><cell><data attribute=...>) llevado al
    formato de GeneXus 15 que entiende el resto del conversor (<tr><td><gxTextBlock/><gxAttribute/>, <gxGrid>).
    Solo el formulario principal (Form id="1"); la botonera y los controles ocultos del patrón quedan afuera.
    """
    m = re.search(r'<Form id="1"[^>]*>(.*?)</Form>', s, re.S)
    s = m.group(1) if m else s
    s = re.split(r'<actionGroups>', s)[0]
    s = re.sub(r'PATTERN_ELEMENT_CUSTOM_PROPERTIES="[^"]*"', '', s)

    def atributo(a, tag):
        r = re.search(r'\b' + a + r'="([^"]*)"', tag)
        return r.group(1) if r else None

    def data(m):
        tag = m.group(0)
        att = atributo('attribute', tag)
        if not att:
            return ''
        cap = atributo('labelCaption', tag)
        txt = f'<gxTextBlock CaptionExpression="{cap}" />' if cap and atributo('labelPosition', tag) != 'None' else ''
        return txt + f'<gxAttribute AttID="{att}" />'

    def grilla(m):
        nombre = atributo('controlName', m.group(1)) or 'GridLevel'
        cols = ''
        for it in re.finditer(r'<item\b[^>]*/?>', m.group(2)):
            a = atributo('attribute', it.group(0))
            if not a:
                continue
            tit = atributo('titleExp', it.group(0)) or atributo('title', it.group(0)) or a
            vis = 'False' if atributo('visible', it.group(0)) == 'False' else 'True'
            cols += f'<gxColumn ColAttId="{a}" ColTitleExpression="{tit}" ColVisible="{vis}" />'
        return f'<gxGrid ControlName="{nombre}">{cols}</gxGrid>'

    def texto(m):
        cap = atributo('caption', m.group(0))
        return f'<gxTextBlock CaptionExpression="{cap}" />' if cap else ''

    s = re.sub(r'<simplegrid\b([^>]*)>(.*?)</simplegrid>', grilla, s, flags=re.S)
    s = re.sub(r'<textblock\b[^>]*/>', texto, s)   # la etiqueta de los combos (TableSplitted: texto + combo + clave oculta)
    s = re.sub(r'<data\b[^>]*/>', data, s)
    s = re.sub(r'<row\b', '<tr', s).replace('</row>', '</tr>')
    s = re.sub(r'<cell\b', '<td', s).replace('</cell>', '</td>')
    return s


def leer_solapas(webform):
    s = webform
    if 'type="layout"' in s[:400]:
        s = gx17_a_html(s)   # V83: GeneXus 17
    # la última solapa termina donde termina el control de solapas: lo que sigue son controles ocultos del patrón
    # V30: los controles de solapas pueden estar anidados (PED: solapas dentro de una solapa). Se toma el
    # de afuera; el contenido de uno de adentro queda plano dentro de la solapa de afuera que lo contiene.
    internos = []
    pila = []
    fin = -1
    for m in re.finditer(r'<gxgxui\.TabPanel[ >]|</gxgxui\.TabPanel>', s):
        if m.group(0).startswith('</'):
            if not pila:
                continue
            ini = pila.pop()
            if pila:
                internos.append((ini, m.end()))
            else:
                fin = m.start()
                break
        else:
            pila.append(m.start())
    if fin > 0:
        s = s[:fin]
    pos = [(m.group(1), m.start()) for m in re.finditer(r'<container containerId="(tabPanel\d+)"', s)
           if not any(a <= m.start() < b for a, b in internos)]
    titulos = {}
    for m in re.finditer(r'<container containerId="Title(tabPanel\d+)">([^<]*)', s):
        if not any(a <= m.start() < b for a, b in internos):
            titulos.setdefault(m.group(1), m.group(2))
    if not pos:
        # formulario sin solapas: todo el form (hasta la botonera) es un solo bloque, sin <solapa>
        fin = s.find('<table id="TableActions"')
        seg = s[:fin] if fin > 0 else s
        pos = [('tabPanel0', 0)]
        titulos = {'tabPanel0': ''}
        s = seg
    solapas = []
    for i, (tid, p) in enumerate(pos):
        seg = s[p: pos[i + 1][1] if i + 1 < len(pos) else len(s)]
        campos = []
        ultimo = None
        for m in re.finditer(r'<gxTextBlock CaptionExpression="([^"]*)"[^>]*/>|<gxAttribute AttID="([^"]+)"', seg):
            if m.group(1) is not None:
                ultimo = html.unescape(m.group(1))
            else:
                a = html.unescape(m.group(2))
                if not a.startswith('&'):
                    campos.append((a, ultimo or a))
                ultimo = None
        renglones = renglones_de(seg)
        grillas = []
        for g in re.finditer(r'<gxGrid [^>]*ControlName="(?:GridLevel_)?([A-Za-z0-9_]+)"(.*?)</gxGrid>', seg, re.S):
            cols = [(html.unescape(c.group(1)), html.unescape(c.group(2)), c.group(3) != 'False')
                    for c in re.finditer(r'<gxColumn [^>]*ColAttId="([^"]+)"[^>]*ColTitleExpression="([^"]*)"[^>]*ColVisible="(\w+)"', g.group(2))]
            grillas.append((g.group(1), [c for c in cols if not c[0].startswith('&')]))
        solapas.append({'id': int(tid[8:]), 'titulo': html.unescape(titulos.get(tid, tid)), 'campos': campos,
                        'renglones': renglones, 'grillas': grillas})
    return solapas


def renglones_de(seg):
    """
    Renglones de pantalla a partir del HTML del web form de GeneXus.
    Una celda con atributos directos es un renglón; una celda con tablas adentro aporta los renglones
    de esas tablas. Las celdas de un mismo <tr> se combinan lado a lado (renglón i con renglón i),
    así dos tablas puestas una al lado de la otra quedan como en GeneXus.
    """
    tokens = re.finditer(r'<(/?)(table|tr|td)\b[^>]*?(/?)>|<gxAttribute AttID="([^"]+)"|<gxGrid\b.*?</gxGrid>', seg, re.S)
    raiz = {'tipo': 'td', 'hijos': []}
    pila = [raiz]
    for m in tokens:
        if m.group(4) is not None:
            a = html.unescape(m.group(4))
            if not a.startswith('&'):
                pila[-1]['hijos'].append(a)
            continue
        if m.group(2) is None:
            continue  # grilla: la maneja el detalle
        cierre, tag, autocierre = m.group(1) == '/', m.group(2), m.group(3) == '/'
        if cierre:
            while len(pila) > 1 and pila[-1]['tipo'] != tag:
                pila.pop()
            if len(pila) > 1:
                pila.pop()
        elif not autocierre:
            nodo = {'tipo': tag, 'hijos': []}
            pila[-1]['hijos'].append(nodo)
            pila.append(nodo)

    def de_celda(td):
        filas, directos = [], []
        for h in td['hijos']:
            if isinstance(h, str):
                directos.append(h)
            elif h['tipo'] == 'table':
                if directos:
                    filas.append(directos); directos = []
                filas.extend(de_tabla(h))
            elif h['tipo'] in ('tr', 'td'):   # HTML desprolijo: se toma como contenido
                filas.extend(de_tabla({'hijos': [h]}) if h['tipo'] == 'tr' else de_celda(h))
        if directos:
            filas.append(directos)
        return filas

    def de_tabla(t):
        filas = []
        for tr in t['hijos']:
            if isinstance(tr, str) or tr['tipo'] != 'tr':
                if not isinstance(tr, str) and tr['tipo'] == 'table':
                    filas.extend(de_tabla(tr))
                continue
            celdas = [de_celda(td) for td in tr['hijos'] if not isinstance(td, str) and td['tipo'] == 'td']
            celdas = [c for c in celdas if c]
            if not celdas:
                continue
            for i in range(max(len(c) for c in celdas)):
                fila = [a for c in celdas if i < len(c) for a in c[i]]
                if fila:
                    filas.append(fila)
        return filas

    return de_celda(raiz)


def limpiar_reglas(txt):
    txt = re.sub(r'/\*.*?\*/', '', txt, flags=re.S)
    txt = re.sub(r'//[^\n]*', '', txt)
    # de los bloques [win] solo se rescatan los Default (sirven para claves que no se ven en la grilla)
    win = ' '.join(re.findall(r'\[win\]\s*\{(.*?)\}', txt, flags=re.S))
    txt = re.sub(r'\[win\]\s*\{.*?\}', '', txt, flags=re.S)
    txt += ';' + ';'.join('WINDEFAULT' + d for d in re.findall(r'(?i)default\([^;]*\)', win))
    txt = re.sub(r'\[web\]\s*\{', '', txt)
    txt = txt.replace('}', '')
    return [r.strip() for r in re.split(r';', txt) if r.strip()]


def literal(v):
    v = v.strip()
    if re.fullmatch(r'-?\d+(\.\d+)?', v):
        return float(v) if '.' in v else int(v)
    if v.lower() in ('true', 'false'):
        return v.lower() == 'true'
    m = re.fullmatch(r"['\"](.*)['\"]", v, re.S)
    if m:
        return m.group(1)
    m = re.fullmatch(r'([A-Za-z]\w*)\.([A-Za-z]\w*)', v)
    if m:
        return ('ENUM', m.group(1), m.group(2))
    return None


def analizar_reglas(reglas):
    r = {'default_win': {}, 'visibleSi': {}, 'noaccept': set(), 'default': {}, 'error_vacio': {}, 'serial': {}, 'auditoria': {}, 'prompt': set(), 'pendientes': []}
    for x in reglas:
        xs = ' '.join(x.split())
        m = re.fullmatch(r'(?i)noaccept\((\w+)\)', xs)
        if m: r['noaccept'].add(m.group(1)); continue
        m = re.fullmatch(r'(\w+)\.Visible\s*=\s*\(?\s*&ModuloMutual\s*=\s*true\s*\)?', xs)
        if m: r['visibleSi'][m.group(1)] = 'ModuloMutual'; continue
        m = re.fullmatch(r'(?i)prompt\(\s*\w+\s*,\s*(\w+).*\)', xs) or re.fullmatch(r'(?i)prompt\((\w+)\)', xs)
        if m: r['prompt'].add(m.group(1)); continue
        m = re.fullmatch(r'(?i)noprompt\((\w+)\)', xs)
        if m: continue
        m = re.fullmatch(r'(?i)WINDEFAULTdefault\(\s*(\w+)\s*,\s*(.+)\)', xs)
        if m:
            v = literal(m.group(2))
            if v is not None: r['default_win'][m.group(1)] = v
            continue
        m = re.fullmatch(r'(?i)default\(\s*(\w+)\s*,\s*&WWPContext\.(\w+)\s*\)', xs)
        if m:
            r['default'][m.group(1)] = '&' + m.group(2)   # V30: valor del contexto de sesión
            continue
        m = re.fullmatch(r'(?i)default\(\s*(\w+)\s*,\s*(.+)\)', xs)
        if m:
            v = literal(m.group(2))
            if v is not None: r['default'][m.group(1)] = v
            else: r['pendientes'].append(xs)
            continue
        m = re.fullmatch(r"(?i)error\(\s*['\"](.+?)['\"]\s*\)\s*if\s+(\w+)\.IsEmpty\(\)", xs)
        if m: r['error_vacio'][m.group(2)] = m.group(1); continue
        m = re.fullmatch(r"(?i)error\(\s*format\(\s*['\"]WWP_RequiredAttribute['\"]\s*,\s*['\"](.+?)['\"]\s*\)\s*\)\s*if\s+(\w+)\.IsEmpty\(\)", xs)
        if m: r['error_vacio'][m.group(2)] = 'Ingrese ' + m.group(1); continue   # E2: el obligatorio de WorkWithPlus (GX17)
        m = re.fullmatch(r'(?i)serial\(\s*(\w+)\s*,\s*(\w+)(\s*,\s*\d+)?\s*\)', xs)
        if m: r['serial'][m.group(1)] = m.group(2); continue
        m = re.fullmatch(r'(\w+)\s*=\s*(Now\(\)|&WWPContext\.SecUserName)\s+if\s+&Mode\s*=\s*TrnMode\.(Insert|Update)\s+on\s+AfterValidate', xs)
        if m:
            k = ('alta' if m.group(3) == 'Insert' else 'modificacion') + ('Fecha' if m.group(2).startswith('Now') else 'Usuario')
            r['auditoria'][k] = m.group(1); continue
        if re.fullmatch(r'(\w+)\s*=\s*&\1\s+if\s+not\s+&\1\.IsEmpty\(\)', xs) \
                or re.fullmatch(r'(?i)noaccept\((\w+)\)\s+if\s+not\s+&\1\.IsEmpty\(\)', xs):
            continue  # E2: WorkWithPlus (GX17) recibe la clave por parámetro; el motor lo resuelve al abrir el registro
        if re.match(r'(?i)parm\(', xs) or re.search(r'&Insert_\w+', xs) or re.search(r'_Cell\.Class', xs) or re.search(r'&ArticulosPK\b', xs):
            continue  # reglas del patrón WorkWithPlus que el motor resuelve a su manera
        r['pendientes'].append(xs)
    return r


# ----------------------------------------------------------------------------- YAML

def no_soportado_tipo(M, n):
    return {n} if M.tipo(n)[1] is None else set()


def _singular(p):
    low = p.lower()
    if low.endswith('iones'): return p[:-5] + 'ión'
    if low.endswith(('íses', 'eses')): return p[:-2]
    if low.endswith('ces') and len(low) > 4: return p[:-3] + 'z'
    if re.search(r'[rlndjy]es$', low) and not low.endswith('lles'): return p[:-2]
    if low.endswith('s') and len(low) > 3 and not low.endswith(('is', 'us')): return p[:-1]
    return p


def nombres_de(titulo):
    """(singular, plural, 'f' o None) a partir del título: se pasan a singular las palabras hasta un 'de'."""
    t = (titulo or '').strip()
    pal = t.split(' ')
    out = []
    corte = False
    for w in pal:
        if w.lower() in ('de', 'del', 'por', 'para', 'con', 'en', 'y'):
            corte = True
        out.append(w if corte or w.isupper() else _singular(w))
    sing = ' '.join(out)
    s0 = out[0].lower()
    fem = s0.endswith(('a', 'ión', 'dad', 'tud', 'ez')) and not s0.endswith(('ma', 'pa'))
    minus = lambda x: x[:1].lower() + x[1:] if not x[:2].isupper() else x
    return minus(sing), minus(t), 'f' if fem else None


def sin_none(v):
    """GeneXus guarda '(None)' cuando no hay atributo descriptor."""
    v = (v or '').strip()
    return '' if v in ('(None)', 'None') else v


def q(s):
    s = str(s)
    # V59e: un código con cero adelante (001, 010) va entre comillas: sin ellas YAML lo lee como número octal (010 -> 8)
    if s == '' or re.search(r'[:#\[\]{},&*!|>\'"%@`?]', s) or s.strip() != s or s.lower() in ('true', 'false', 'null', 'yes', 'no', 'on', 'off') \
            or re.fullmatch(r'[+-]?0\d+', s):
        return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'
    return s


_MODELOS = {}


def _modelo(kb):
    """El modelo se arma una vez por KB (en tanda se generan cientos de TRN)."""
    if id(kb) not in _MODELOS:
        _MODELOS.clear()
        _MODELOS[id(kb)] = (kb, Modelo(kb))
    return _MODELOS[id(kb)][1]


def filtros_contexto(cond):
    """V30: condiciones fijas del WW de GeneXus contra el contexto: 'PRSEmpresa=&WWPContext.Empresa;'."""
    r = {}
    sin_comentarios = re.sub(r'/\*.*?\*/', ' ', cond or '', flags=re.S)
    sin_comentarios = re.sub(r'//[^\n]*', ' ', sin_comentarios)
    for linea in sin_comentarios.split(';'):
        m = re.fullmatch(r'\s*(\w+)\s*=\s*&WWPContext\.(\w+)\s*', linea)
        if m:
            r[m.group(1)] = '&' + m.group(2)
            continue
        # V43: condición fija contra una constante, sin 'when': 'PRSNumero > 0', "PEDStatus <> 'X'"
        m = re.fullmatch(r'\s*(\w+)\s*(<>|>=|<=|>|<|=)\s*(-?\d+(?:\.\d+)?|\'[^\']*\'|"[^"]*")\s*', linea)
        if m and m.group(1) not in r:
            v = m.group(3).strip('\'"')
            r[m.group(1)] = v if m.group(2) == '=' else m.group(2) + v
    return r


def leer_ww(grid):
    """V30: (columnas visibles [(atributo, título)], orden [(atributo, desc)]) de la grilla del WW de GeneXus."""
    cols = []
    for c in re.finditer(r'<gxColumn ([^>]*)/>', grid):
        at = dict(re.findall(r'(\w+)="([^"]*)"', c.group(1)))
        a = html.unescape(at.get('ColAttId', ''))
        if not a or a.startswith('&') or at.get('ColVisible', 'True') == 'False':
            continue
        cols.append((a, html.unescape(at.get('ColTitleExpression', '')).strip()))
    orden = []
    m = re.search(r'ControlOrder="([^"]*)"', grid)
    if m:
        linea = html.unescape(m.group(1)).split('\n')[0]
        linea = re.sub(r'\bwhen\b.*$', '', linea, flags=re.I)
        for tok in re.findall(r'\(?\s*[A-Za-z_][A-Za-z0-9_]*\s*\)?', linea):
            nom = tok.strip('() ')
            orden.append((nom, tok.strip().startswith('(')))
    return cols, orden


def cerrar_cadenas(info, mostrar):
    """V30: una referencia que se muestra puede usar como clave foránea un campo que trae OTRA referencia
    (A -> B -> C). Ese campo se agrega inferido y oculto. Devuelve (mostrar ampliado, ocultos agregados)."""
    mostrar = set(mostrar)
    extra = set()
    while True:
        nuevos = set()
        for (t2, fk), trae in info['refs'].items():
            if any(l in mostrar for l, _ in trae):
                for l, _ in fk:
                    if l in info['inferidos'] and l not in mostrar:
                        nuevos.add(l)
        if not nuevos:
            return mostrar, extra
        mostrar |= nuevos
        extra |= nuevos


# ----------------------------------------------------------------------------- fórmulas (V34)

def enumerados(kb):
    """{'parametro.ivatipo1': '16', ...}: valores de los dominios enumerados, listos para escribir como constante."""
    r = {}
    for dn, d in kb['doms'].items():
        vals = d.get('IDEnumDefinedValues')
        if not vals:
            continue
        texto = (d.get('ATTCUSTOMTYPE') or '').split(':')[-1] in ('Character', 'VarChar', 'LongVarChar')
        for parte in re.split(r'(?<!\\);', vals):
            v = [x.strip() for x in parte.split(',', 2)]
            if len(v) >= 2 and v[1]:
                val = v[0].strip('"')
                r[f'{dn}.{v[1]}'.lower()] = f'"{val}"' if texto else val
    # V35: modos de la transacción (&Mode)
    r.update({'trnmode.insert': '"INS"', 'trnmode.update': '"UPD"', 'trnmode.delete': '"DLT"', 'trnmode.display': '"DSP"'})
    return r


def reemplazar_enums(src, enums):
    """Dominio.Valor -> constante, fuera de los textos y sin tocar &variables ni llamadas (X.udp(...))."""
    partes = re.split(r'("[^"\n]*"|\'[^\'\n]*\')', src)
    for i in range(0, len(partes), 2):
        partes[i] = re.sub(r'(?<![&\w.])[A-Za-z_]\w*\.[A-Za-z_]\w*(?![\w(.])',
                           lambda m: enums.get(m.group(0).lower(), m.group(0)), partes[i])
    return ''.join(partes)


def sin_comentarios(txt):
    """Saca /* */ y // (renglón por renglón, antes de juntar: si no, un comentario se come la fórmula)."""
    out, i, q = [], 0, None
    while i < len(txt):
        c = txt[i]
        if q:
            out.append(c)
            if c == q or c == '\n':
                q = None
        elif c in '"\'':
            q = c; out.append(c)
        elif txt.startswith('/*', i):
            j = txt.find('*/', i + 2)
            i = len(txt) if j < 0 else j + 2
            out.append(' ')
            continue
        elif txt.startswith('//', i):
            while i < len(txt) and txt[i] != '\n':
                i += 1
            continue
        else:
            out.append(c)
        i += 1
    return ''.join(out)


def limpiar_formula(M, txt, enums):
    """Fórmula de GeneXus -> una línea que entiende el motor: sin comentarios, enumerados reemplazados,
    find(Col, ...) con la tabla (find(Tabla.Col, ...))."""
    f = ' '.join(sin_comentarios(txt or '').split()).strip()
    f = re.sub(r';\s*$', '', f)
    f = reemplazar_enums(f, enums)

    def calificar(m):
        col = m.group(1)
        tablas = [t for t, cols in M.guardados_lit.items() if col in cols]
        if not tablas:
            tablas = [t for t, cols in M.no_claves_lit.items() if col in cols]
        return f'find({tablas[0]}.{col},' if tablas else m.group(0)
    return re.sub(r'(?i)\bfind\s*\(\s*([A-Za-z_]\w*)\s*,', calificar, f)


def atributos_formula(kb, f):
    """Atributos de la KB que usa una fórmula ya limpia (no &variables, ni Proc.udp, ni Tabla.Columna)."""
    r = set()
    for m in re.finditer(r'(?<![&\w.])([A-Za-z_]\w*)(?![\w.(])', re.sub(r'"[^"]*"|\'[^\']*\'', '', f)):
        if m.group(1) in kb['attrs']:
            r.add(m.group(1))
    return r


def dependencias(kb, M, semillas, enums):
    """Atributos que necesitan las fórmulas de 'semillas' (y las fórmulas de esos, y así)."""
    necesarios, pendientes = set(), list(semillas)
    while pendientes:
        a = pendientes.pop()
        fo = M.formula(a)
        if not fo:
            continue
        for d in atributos_formula(kb, limpiar_formula(M, fo, enums)):
            if d not in necesarios:
                necesarios.add(d)
                pendientes.append(d)
    return necesarios


def generar(kb, trn_nombre, solapas_ids, workwith=None, textos=None, previos_det=None, extra=None):
    """extra (V35): atributos que usan las reglas; entran ocultos (con lo que necesitan sus fórmulas)."""
    textos = textos or {}
    previos_det = previos_det or {}
    M = _modelo(kb)
    trn = kb['trns'][trn_nombre]
    # V41: fórmulas redundantes = las marcadas isRedundant en la estructura de la TRN (antes: si estaban en un índice)
    marcadas = set()
    def _red(lv):
        for a in lv['atts']:
            if a.get('red'): marcadas.add(a['n'])
        for s_ in lv['niveles']: _red(s_)
    for lv_ in trn['niveles']: _red(lv_)
    def es_redundante(n, tabla):
        return n in marcadas or n in kb['indices'].get(tabla or '', [])
    cab = trn['niveles'][0]
    obj = kb.get('objetivos', {}).get(trn_nombre) or kb['objetivo']
    solapas = [s for s in leer_solapas(obj['webform']) if not solapas_ids or s['id'] in solapas_ids]
    if 'type="layout"' in (obj.get('webform') or '')[:400]:
        # V83: en GeneXus 17 las claves foráneas se ven con un combo (DVelop ExtendedCombo) que muestra la descripción de la
        # otra tabla; el form solo tiene la clave. Se agrega su descripción al lado, así el motor arma la referencia y la lupa.
        # la descripción: el primer atributo de la otra TRN (no clave) que viene después de la clave en esta estructura
        orden_cab = [a['n'] for a in trn['niveles'][0]['atts']]
        descr = {}
        for tn, t in kb['trns'].items():
            n0 = t['niveles'][0] if t.get('niveles') else None
            if not n0 or tn == trn_nombre:
                continue
            ks = [a['n'] for a in n0['atts'] if a['key']]
            if len(ks) != 1 or ks[0] not in orden_cab:
                continue
            otros = {a['n'] for a in n0['atts'] if not a['key']}
            d = n0.get('descAtt')
            if not d or d not in orden_cab:
                d = next((x for x in orden_cab[orden_cab.index(ks[0]) + 1:] if x in otros), None)
            if d:
                descr.setdefault(ks[0], d)
        propios_cab = set(orden_cab)
        for s_ in solapas:
            ya = {a for a, _ in s_['campos']}
            nuevos = []
            for a, t in s_['campos']:
                nuevos.append((a, t))
                d = descr.get(a)
                if d and d not in ya and d in propios_cab and not any(x['key'] and x['n'] == a for x in trn['niveles'][0]['atts']):
                    nuevos.append((d, M.etiqueta(d))); ya.add(d)
                    for r in s_['renglones']:
                        if a in r and d not in r:
                            r.insert(r.index(a) + 1, d)
            s_['campos'] = nuevos
    reglas = analizar_reglas(limpiar_reglas(obj['reglas']))

    campos_en_solapas = {a for s in solapas for a, _ in s['campos']}
    if not (obj.get('webform') or '').strip():
        # V28: sin web form en GeneXus (tablas internas, padrones...): todos los campos visibles, sin pantalla
        def todos(lv):
            r = {a['n'] for a in lv['atts']}
            for sub in lv['niveles']: r |= todos(sub)
            return r
        campos_en_solapas = todos(cab)
    etiqueta_form = {a: t for s in solapas for a, t in s['campos']}
    # V30: la grilla del form no siempre se llama como el nivel (GridLevel_PEDImp -> Impuestos, Cbtes -> CbtesCli):
    # se asocia al nivel que tiene más columnas de la grilla
    niveles_cab = {lv['nombre']: {a['n'] for a in lv['atts']} for lv in trn['niveles'][0]['niveles']}
    usados = set()
    for s_ in solapas:
        nuevas = []
        for g_, cols in s_['grillas']:
            atts_g = {c for c, _, _ in cols}
            mejor = max(((len(atts_g & v), k) for k, v in niveles_cab.items() if k not in usados), default=(0, None))
            if mejor[0] > 0:
                usados.add(mejor[1])
                nuevas.append((mejor[1], cols))
        s_['grillas'] = nuevas
    grillas_elegidas = {g: cols for s in solapas for g, cols in s['grillas']}

    dominios_usados = {}
    emitidos = set()
    claves_cab_ = [a['n'] for a in trn['niveles'][0]['atts'] if a['key']]
    fks_actuales = set()
    lineas = []
    out = lineas.append
    no_soportados = []
    archivos_cab = []   # V83: (nombre, etiqueta, propiedades) de los Blob de la cabecera
    omitidos = []

    nulables = set()
    def marcar_nulables(lv):
        for a in lv['atts']:
            if a.get('nullable'): nulables.add(a['n'])
        for sub in lv['niveles']: marcar_nulables(sub)
    marcar_nulables(cab)

    def campo_yaml(n, ind, clave=False, inferido=False, formula=None, etiqueta=None, oculto=False, redundante=False):
        dom, txt, enum, fuente = M.tipo(n)
        if txt is None:
            # V83: Blob de la cabecera -> 'archivos:' (con los campos de nombre y extensión del archivo)
            if (fuente.get('ATTCUSTOMTYPE') or '').endswith('Blob') and ind == 2 and not inferido and not formula:
                archivos_cab.append((n, etiqueta or M.etiqueta(n), fuente))
                return
            no_soportados.append(n)
            return
        nombre_dom = dom or None
        if nombre_dom and nombre_dom not in dominios_usados:
            dominios_usados[nombre_dom] = (txt, enum)
        if ind == 2:
            emitidos.add(n)
        partes = [f'nombre: {n}', f'dominio: {q(nombre_dom or txt)}', f'etiqueta: {q(etiqueta or M.etiqueta(n))}']
        if clave: partes.append('clave: true')
        if clave and ind == 2 and len(claves_cab_) == 1 and M.autonumerico(n): partes.append('autonumerico: true')
        if inferido: partes.append('inferido: true')
        if formula: partes.append('formula: ' + q(limpiar_formula(M, formula, enums)))   # V34: sin comentarios, en una línea
        if formula and redundante: partes.append('guardar: true')
        if n in reglas['noaccept'] and not clave: partes.append('soloLectura: true')
        # los Default de [win] solo valen para claves que no se ven (en la web no hay dónde cargarlas)
        v_def = reglas['default'].get(n)
        if v_def is None and clave and n not in fks_actuales:
            # claves que no son clave foránea (ej. Filtro01 = 0, TipoEntidad = Genérica)
            v_def = reglas['default_win'].get(n)
        if v_def is None and not clave and not inferido and not formula:
            v_def = M.valor_inicial(n)   # V28: Initial Value del atributo
        if v_def is not None:
            v = v_def
            if isinstance(v, tuple):
                ev = next((e for e in (enum or []) if e['nombre'].lower() == v[2].lower()), None)
                v = ev['valor'] if ev else None
            if v is not None: partes.append('default: ' + (q(v) if isinstance(v, str) else str(v).lower() if isinstance(v, bool) else str(v)))
        if txt == 'GUID' and not clave and not inferido and v_def is None:
            partes.append('default: "&NewGuid"')   # V83: como GUID.NewGuid() de GeneXus, en el alta
        if n in reglas['error_vacio'] and not clave: partes.append('obligatorio: true'); partes.append('mensaje: ' + q(reglas['error_vacio'][n]))
        if n in reglas['visibleSi']: partes.append('visibleSi: ' + reglas['visibleSi'][n])
        if n in nulables and not clave and not inferido: partes.append('nulo: true')
        if oculto: partes.append('oculto: true')
        out(' ' * ind + '- {' + ', '.join(partes) + '}')

    def referencias_yaml(info, ind):
        if not info['refs']:
            return
        out(' ' * ind + 'referencias:')
        for (t2, fk), trae in info['refs'].items():
            desc = M.atributo_descripcion(t2)
            out(' ' * ind + f'  - tabla: {t2}')
            out(' ' * ind + '    campos: {' + ', '.join(f'{l}: {r}' for l, r in fk) + '}')
            out(' ' * ind + '    trae: {' + ', '.join(f'{l}: {r}' for l, r in trae) + '}')
            if desc:
                out(' ' * ind + f'    descripcion: {desc}')

    enums = enumerados(kb)
    # V34: lo que necesitan las fórmulas que se muestran (o se guardan) entra oculto, así se pueden calcular
    semillas = set()
    idx_cab = kb['indices'].get(M.tabla_por_claves.get(frozenset(a['n'] for a in cab['atts'] if a['key'])) or '', [])
    for a_ in cab['atts']:
        if M.formula(a_['n']) and (a_['n'] in campos_en_solapas or a_['n'] in idx_cab):
            semillas.add(a_['n'])
    for lv_ in cab['niveles']:
        cols_ = grillas_elegidas.get(lv_['nombre'])
        if cols_ is None:
            continue
        vis_ = {c for c, _, v in cols_ if v}
        for a_ in lv_['atts']:
            if M.formula(a_['n']) and a_['n'] in vis_:
                semillas.add(a_['n'])
    necesarios = dependencias(kb, M, semillas, enums)
    if extra:
        necesarios |= set(extra) | dependencias(kb, M, set(extra), enums)

    # ---------------- cabecera
    info = M.analizar_nivel(cab['atts'], [], [])
    out(f'# TRN {trn_nombre} - GENERADA desde la KB GeneXus con herramientas/gx2yaml.py')
    out(f'# Solapas incluidas: {", ".join(s["titulo"] for s in solapas)}')
    out('# Los campos propios que no están en esas solapas se incluyen ocultos: se preservan al modificar')
    out('# y se graban vacíos en el alta.')
    out(f'transaccion: {trn_nombre}')
    titulo = textos.get("titulo") or trn["desc"] or trn_nombre
    out(f'titulo: {q(titulo)}')
    # V28: singular/plural/género deducidos del título ("Vendedores" -> vendedor / vendedores)
    sing_, plur_, gen_ = nombres_de(titulo)
    textos.setdefault('singular', sing_)
    out(f'singular: {q(textos.get("singular"))}')
    out(f'plural: {q(textos.get("plural") or plur_)}')
    if textos.get('genero') or gen_:
        out(f'genero: {textos.get("genero") or gen_}')
    out(f'tabla: {info["tabla"]}')
    if textos.get('encabezado'):
        out(f'encabezado: {textos["encabezado"]}')
    else:
        desc = sin_none(cab.get('descAtt'))
        if desc in set(reglas['serial'].values()) or (desc and no_soportado_tipo(M, desc)):
            desc = None   # V30: el numerador o una imagen no son campos de la cabecera
        desc = desc or next(
            (c for c in info['propios'] if re.search(r'(Descrip|Nombre|RazonSocial)$', c)), '')
        propios_enc = [c for c in (workwith or []) if c != desc] or \
                      [c for c in ('ArticulosId',) if c in info['propios']] or [a['n'] for a in cab['atts'] if a['key']]
        sing = (textos.get('singular') or trn_nombre.lower())
        partes = [sing[:1].upper() + sing[1:], '{' + propios_enc[0] + '}']
        if desc and desc != propios_enc[0]:
            partes += ['-', '{' + desc + '}']
        out('encabezado: "' + ' '.join(partes) + '"')
    if reglas['auditoria']:
        out('auditoria: {' + ', '.join(f'{k}: {v}' for k, v in reglas['auditoria'].items()) + '}')
    numeradores = set(reglas['serial'].values())
    out('campos:')
    orden = [a['n'] for a in cab['atts'] if a['n'] not in numeradores]
    # V30: columnas y orden del WorkWith de GeneXus (WW<trn>), si no vinieron por parámetro
    ww_gx, orden_gx, prompt_gx = ([], [], [])
    atts_cab = {a['n'] for a in cab['atts']}
    inst = kb.get('ww_inst', {}).get(trn_nombre.lower())
    if workwith is None and kb.get('ww', {}).get(trn_nombre.lower()):
        ww_gx, orden_gx = leer_ww(kb['ww'][trn_nombre.lower()])
    elif workwith is None and inst:
        ww_gx, orden_gx, prompt_gx = list(inst['cols']), list(inst['orden']), list(inst['prompt'])   # E2: GeneXus 17
    ok_ww = lambda c: (c in atts_cab and c not in info['formulas'] and c not in info['descartados'] and c not in numeradores
                       and not no_soportado_tipo(M, c))
    ww_gx = [(c, t) for c, t in ww_gx if ok_ww(c)]
    prompt_gx = [(c, t) for c, t in prompt_gx if ok_ww(c)]
    ww_inferidos = {c for c, _ in ww_gx + prompt_gx if c in info['inferidos']}
    nec_inf_cab = {c for c in necesarios if c in info['inferidos']}
    mostrar_cab, extra_cab = cerrar_cadenas(info, set(campos_en_solapas) | ww_inferidos | nec_inf_cab)
    extra_cab |= (ww_inferidos | nec_inf_cab) - set(campos_en_solapas)
    for n in orden:
        a = next(x for x in cab['atts'] if x['n'] == n)
        if n in no_soportado_tipo(M, n):
            fu = M.tipo(n)[3]
            if (fu.get('ATTCUSTOMTYPE') or '').endswith('Blob') and n not in info['inferidos'] and n not in info['formulas']:
                archivos_cab.append((n, etiqueta_form.get(n) or M.etiqueta(n), fu))   # V83: Blob -> 'archivos:'
            continue
        if n in info['descartados']:
            omitidos.append(n)
            continue
        if a['key']:
            campo_yaml(n, 2, clave=True, etiqueta=etiqueta_form.get(n))
        elif n in info['formulas']:
            red = es_redundante(n, info['tabla'])
            if n in campos_en_solapas or red or n in necesarios:
                campo_yaml(n, 2, formula=M.formula(n), etiqueta=etiqueta_form.get(n), redundante=red,
                           oculto=n not in campos_en_solapas)
        elif n in info['inferidos']:
            if n in mostrar_cab:
                campo_yaml(n, 2, inferido=True, etiqueta=etiqueta_form.get(n), oculto=n in extra_cab)
        else:
            campo_yaml(n, 2, etiqueta=etiqueta_form.get(n), oculto=n not in campos_en_solapas and n not in reglas['auditoria'].values())
    # referencias solo de las que traen algo que se muestra
    info['refs'] = {k: [(l, r) for l, r in v if l in mostrar_cab] for k, v in info['refs'].items()}
    info['refs'] = {k: v for k, v in info['refs'].items() if v}
    referencias_yaml(info, 0)

    # ---------------- detalles
    detalles = []
    claves_cab = [a['n'] for a in cab['atts'] if a['key']]
    for lv in cab['niveles']:
        if lv['nombre'] not in grillas_elegidas:
            continue
        cols = grillas_elegidas[lv['nombre']]
        visibles = {c for c, _, vis in cols if vis}
        tit = {c: t for c, t, _ in cols}
        inf = M.analizar_nivel(lv['atts'], claves_cab, [])
        detalles.append((lv, inf, visibles, tit))
    # claves foráneas de todos los detalles: sus Default de [win] (ej. Lista de Precios = 1) no se usan
    for _, inf_, _, _ in detalles:
        for (_t, fk) in inf_['refs']:
            fks_actuales.update(l for l, _r in fk)
    if detalles:
        out('detalles:')
        for lv, inf, visibles, tit in detalles:
            prev = previos_det.get(lv['nombre'], {})
            out(f'  - nombre: {lv["nombre"]}')
            out(f'    titulo: {prev.get("titulo") or q(lv["desc"] or lv["nombre"])}')
            out(f'    tabla: {inf["tabla"]}')
            if prev.get('orden'):
                out(f'    orden: {prev["orden"]}')
            claves_det = [a['n'] for a in lv['atts'] if a['key']]
            ser = [c for c in claves_det if c in reglas['serial']]
            if ser and len(claves_det) > 1:
                ser = []   # V30: el numerador exige una única clave en el detalle; el renglón se carga a mano
                out('    # en GeneXus ' + claves_det[0] + ' se numeraba solo (serial); acá el detalle tiene clave compuesta')
            if ser:
                out(f'    numerador: {reglas["serial"][ser[0]]}')
            out('    campos:')
            nec_inf = {c for c in necesarios if c in inf['inferidos']}
            mostrar_det, extra_det = cerrar_cadenas(inf, visibles | nec_inf)
            extra_det |= nec_inf - visibles
            for a in lv['atts']:
                n = a['n']
                if n in inf['descartados']:
                    omitidos.append(n)
                    continue
                if a['key']:
                    campo_yaml(n, 6, clave=True, etiqueta=tit.get(n), oculto=n not in visibles and not (n in reglas['serial'] and not ser))
                elif n in inf['formulas']:
                    red = es_redundante(n, inf['tabla'])
                    if red or n in visibles or n in necesarios:   # V34: las que se ven o que otra fórmula usa
                        campo_yaml(n, 6, formula=M.formula(n), etiqueta=tit.get(n), redundante=red, oculto=n not in visibles)
                    continue
                elif n in inf['inferidos']:
                    if n in mostrar_det:
                        campo_yaml(n, 6, inferido=True, etiqueta=tit.get(n), oculto=n in extra_det)
                else:
                    campo_yaml(n, 6, etiqueta=tit.get(n), oculto=n not in visibles)
            inf['refs'] = {k: [(l, r) for l, r in v if l in mostrar_det] for k, v in inf['refs'].items()}
            inf['refs'] = {k: v for k, v in inf['refs'].items() if v}
            referencias_yaml(inf, 4)

    def estirable(n):
        txt = M.tipo(n)[1] or ''
        m = re.match(r'(VarChar|Character)\((\d+)\)', txt)
        return bool(m) and int(m.group(2)) >= 20 and not M.tipo(n)[2]

    # ---------------- pantalla (texto: <solapa>, <tabla>, <detalle>; campos separados por espacios)
    out('pantalla: |')
    for s_ in solapas:
        vistos = set()
        filas = []
        for r in s_['renglones']:
            f = [a for a in r if a in emitidos and a not in vistos]
            vistos.update(f)
            if f:
                filas.append(f)
        resto = [a for a, _ in s_['campos'] if a in emitidos and a not in vistos]
        if resto:
            filas.append(resto)
        # V61b: la auditoría siempre en dos renglones: fecha y usuario de alta / fecha y usuario de modificación
        aud = reglas['auditoria']
        aud_set = set(aud.values())
        pos = next((i for i, f in enumerate(filas) if any(a in aud_set for a in f)), None)
        filas_aud = []
        if pos is not None:
            # V61c: en su propia tabla, al final de la solapa, con la primera columna fija (las fechas): así el usuario
            # queda al lado y no al otro extremo del formulario
            filas_aud = [x for x in ([a for a in (aud.get('altaFecha'), aud.get('altaUsuario')) if a and a in emitidos],
                                     [a for a in (aud.get('modificacionFecha'), aud.get('modificacionUsuario')) if a and a in emitidos]) if x]
            filas = [r for r in ([a for a in f if a not in aud_set] for f in filas) if r]
        gs = [g for g, _ in s_['grillas'] if any(d[0]['nombre'] == g for d in detalles)]
        if not filas and not gs and not filas_aud:
            continue
        con_solapa = bool(s_['titulo'])
        ind = '  ' if con_solapa else ''
        if con_solapa:
            out(f'  <solapa {s_["titulo"]}>')
        if filas:
            ncol = max(len(f) for f in filas)
            celdas = []
            for f in filas:
                c = list(f)
                if len(c) < ncol and estirable(c[-1]):   # un texto largo al final ocupa las columnas que sobran
                    c[-1] = f'{c[-1]}:{ncol - len(c) + 1}'
                celdas.append(c)
            anchos = [max(len(c[i]) for c in celdas if i < len(c)) for i in range(ncol)]
            out(ind + '  <tabla>')
            for c in celdas:
                out(ind + '    ' + '  '.join(x.ljust(anchos[i]) for i, x in enumerate(c)).rstrip())
            out(ind + '  <endtable>')
        if filas_aud:
            w = max(len(f[0]) for f in filas_aud) + 4
            out(ind + '  <tabla anchos=300px,1fr>')
            for f in filas_aud:
                out(ind + '    ' + (f[0].ljust(w) + f[1] if len(f) > 1 else f[0]))
            out(ind + '  <endtable>')
        for g in gs:
            out(ind + f'  <detalle {g}>')
        if con_solapa:
            out('  <endsolapa>')

    if archivos_cab:
        # V83: archivos (Blob): se suben y se bajan desde el panel "Archivos" del formulario
        propios = {a['n'] for a in cab['atts']}
        out('archivos:')
        for n, et, pr in archivos_cab:
            partes = [f'nombre: {n}', f'etiqueta: {q(et)}']
            for clave, prop, suf in (('nombreArchivo', 'AttFileNameAtt', 'FileName'), ('tipoArchivo', 'AttFileTypeAtt', 'FileType')):
                c = pr.get(prop) or (n + suf)
                if c in propios and c in emitidos:
                    partes.append(f'{clave}: {c}')
            out('  - {' + ', '.join(partes) + '}')
    out('workwith:')
    claves_cab = [a['n'] for a in cab['atts'] if a['key']]
    # V30: solo los que se emitieron (una imagen/Blob no está en campos)
    propios_sin_num = [c for c in info['propios'] if c not in numeradores and c in emitidos]
    propios_ww = ([c for c in ('ArticulosId', 'ArticulosDescrip', 'ArticulosDescort', 'ArticulosEstado') if c in info['propios']]
                  or (claves_cab + propios_sin_num)[:4])
    ww_gx = [(c, t) for c, t in ww_gx if c in emitidos]
    if workwith:
        out('  columnas: [' + ', '.join(workwith) + ']')
    elif ww_gx:
        # título propio solo si difiere de la etiqueta del formulario
        partes = []
        for c, t in ww_gx:
            et = etiqueta_form.get(c) or M.etiqueta(c)
            partes.append(q(f'{c}: {t}') if t and t != et and t != c else c)
        out('  columnas: [' + ', '.join(partes) + ']')
    else:
        out('  columnas: [' + ', '.join(propios_ww) + ']')
    prompt_gx = [(c, t) for c, t in prompt_gx if c in emitidos]
    if prompt_gx:   # E2: la lupa con las columnas de la instancia de WorkWithPlus
        out('  prompt: [' + ', '.join(q(f'{c}: {t}') if t and t != (etiqueta_form.get(c) or M.etiqueta(c)) and t != c else c
                                      for c, t in prompt_gx) + ']')
    guardables = {c for c in emitidos if c in claves_cab or c in info['propios']}
    filtro = {c: v for c, v in filtros_contexto(kb.get('ww_cond', {}).get(trn_nombre.lower())).items() if c in guardables}
    if filtro:
        out('  filtro: {' + ', '.join(f'{c}: {q(v)}' for c, v in filtro.items()) + '}')
    # V41: el orden también acepta fórmulas redundantes (grabadas en la tabla, ej. CPBCIdDescending = 9999999999-CPBCId)
    redundantes = {c for c in info['formulas'] if es_redundante(c, info['tabla'])}
    orden_gx = [(c, d) for c, d in orden_gx if c in guardables or (c in redundantes and c in emitidos)]
    if orden_gx:
        out('orden: [' + ', '.join(c + (' DESC' if d else '') for c, d in orden_gx) + ']')
    else:
        orden_ww = next((c for c in propios_ww if c not in claves_cab), propios_ww[0])
        out('orden: [' + orden_ww + ']')

    # ---------------- dominios usados
    out('dominios:')
    for d, (txt, enum) in sorted(dominios_usados.items()):
        if enum:
            out(f'  {d}:')
            out(f'    tipo: {q(txt)}')
            out('    valores:')
            for e in enum:
                out(f'      - {{valor: {q(e["valor"])}, texto: {q(e["texto"])}}}')
        else:
            out(f'  {d}: {q(txt)}')

    # ---------------- pendientes
    out('')
    out('# ---------------------------------------------------------------- PENDIENTE')
    out('# Reglas de GeneXus que el motor todavía no interpreta (van a la clase de reglas Java o a una próxima versión):')
    for p in reglas['pendientes']:
        out('#   ' + p[:230])
    if no_soportados:
        out('# Atributos con tipos no soportados todavía (Blob/Image/GUID...): ' + ', '.join(no_soportados))
    if omitidos:
        out('# Omitidos (en GeneXus son fórmulas o llegan por otra tabla que este nivel no referencia): ' + ', '.join(omitidos))
    return '\n'.join(lineas) + '\n'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('trn')
    ap.add_argument('--solapas', default='', help='números de solapa separados por coma (1 = primera)')
    ap.add_argument('--salida', default='.')
    ap.add_argument('--cache', help='archivo JSON para guardar/leer la KB ya procesada')
    ap.add_argument('--titulo'); ap.add_argument('--singular'); ap.add_argument('--plural')
    ap.add_argument('--genero', help='f = femenino')
    ap.add_argument('--encabezado', help='título del formulario, ej: "Pedido {PedidosNumero} de {ClientesRazonSocial}"')
    ap.add_argument('--workwith', help='columnas del WorkWith separadas por coma (propias o inferidas). '
                                        'Si no se indica, se conservan las del YAML existente')
    a = ap.parse_args()
    if a.cache and os.path.exists(a.cache):
        kb = normalizar_kb(json.load(open(a.cache, encoding="utf-8")))   # V82m
        if kb.get('_trn') != a.trn or kb.get('_version') != 8:
            kb = None
    else:
        kb = None
    if kb is None:
        kb = leer_kb(a.xml, a.trn)
        kb['_trn'] = a.trn
        if a.cache:
            json.dump(kb, open(a.cache, 'w', encoding='utf-8'), ensure_ascii=False)
    if a.trn not in kb['trns']:
        sys.exit(f'No existe la transacción {a.trn} en la KB')
    ids = {int(x) for x in a.solapas.split(',') if x.strip()}
    os.makedirs(a.salida, exist_ok=True)
    destino = os.path.join(a.salida, a.trn + '.yaml')
    ww = [x.strip() for x in a.workwith.split(',') if x.strip()] if a.workwith else None
    if ww is None and os.path.exists(destino):
        # se conservan las columnas del WorkWith que ya tenía el YAML (pueden haberse tocado a mano)
        m = re.search(r'^workwith:\s*\n\s+columnas:\s*\[([^\]]*)\]', open(destino, encoding='utf-8').read(), re.M)
        if m:
            ww = [x.strip() for x in m.group(1).split(',') if x.strip()]
            print('Se conservan las columnas del WorkWith:', ', '.join(ww))
    # títulos y textos: parámetros, o los que ya tenía el YAML (pueden haberse tocado a mano)
    textos = {}
    previo = open(destino, encoding='utf-8').read() if os.path.exists(destino) else ''
    for k in ('titulo', 'singular', 'plural', 'genero', 'encabezado'):
        v = getattr(a, k)
        if not v and previo:
            m = re.search(r'^' + k + r':\s*(.+?)\s*$', previo, re.M)
            v = (m.group(1) if k == 'encabezado' else m.group(1).strip('"')) if m else None
        if v:
            textos[k] = v
    # de cada detalle se conservan 'titulo' y 'orden' del YAML existente
    previos_det = {}
    for bloque in re.split(r'\n  - nombre: ', previo)[1:]:
        nom = bloque.split('\n', 1)[0].strip()
        d = {}
        for k in ('titulo', 'orden'):
            m = re.search(r'^    ' + k + r':\s*(.+?)\s*$', bloque, re.M)
            if m: d[k] = m.group(1)
        previos_det[nom] = d
    y = generar(kb, a.trn, ids, ww, textos, previos_det)
    open(destino, 'w', encoding='utf-8').write(y)
    print('Generado', destino)


if __name__ == '__main__':
    main()
