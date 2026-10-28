#!/usr/bin/env python3
"""
V34 - Completa los YAML existentes para que sus fórmulas se puedan calcular, SIN regenerarlos
(se respetan los retoques a mano: solo se agregan renglones y se corrigen fórmulas que nadie tocó).

Por cada trn/*.yaml:
  1. Agrega, ocultos, los atributos que usan las fórmulas y no estaban (otras fórmulas, como PRSTotalIva01,
     o datos de otra tabla, como ArticulosTotalizadorItemizado), con su referencia ('trae').
  2. Corrige las fórmulas que quedaron con comentarios de GeneXus metidos en la misma línea
     (solo si la fórmula es la original de la KB: si la tocaste a mano, no se cambia).

Uso:
  python3 parchar_formulas.py OptimistU12NEW.xml --trn ../src/main/resources/trn [--solo PRS,PED] [--ver]
  --ver   muestra lo que cambiaría sin grabar
"""
import argparse, json, os, re, sys
import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gx2yaml as g


def cargar_kb(xml):
    cache = os.path.join(os.path.dirname(os.path.abspath(xml)), 'kb_v7.json')
    if os.path.exists(cache):
        kb = json.load(open(cache, encoding='utf-8'))
        if kb.get('_version') == 8 and kb.get('_trn') == '*':
            return kb
    kb = g.leer_kb(xml, {'*'})
    kb['_trn'] = '*'
    json.dump(kb, open(cache, 'w', encoding='utf-8'), ensure_ascii=False)
    return kb


def niveles(doc):
    """{'': campos/referencias de la cabecera, 'Det': ... de cada detalle}"""
    r = {'': (doc.get('campos') or [], doc.get('referencias') or [])}
    for d in doc.get('detalles') or []:
        r[d.get('nombre')] = (d.get('campos') or [], d.get('referencias') or [])
    return r


def rango_nivel(lineas, nivel):
    """(inicio, fin) de las líneas del nivel en el texto: la cabecera va hasta 'detalles:'."""
    if nivel == '':
        fin = next((i for i, l in enumerate(lineas) if re.match(r'^(detalles|pantalla|workwith|reglas):', l)), len(lineas))
        return 0, fin
    ini = next((i for i, l in enumerate(lineas) if re.match(r'^  - nombre:\s*' + re.escape(nivel) + r'\s*$', l)), None)
    if ini is None:
        return None
    fin = next((i for i in range(ini + 1, len(lineas)) if re.match(r'^  - nombre:', lineas[i]) or re.match(r'^\S', lineas[i])), len(lineas))
    return ini, fin


def linea_campo(lineas, ini, fin, nombre):
    for i in range(ini, fin):
        if re.match(r'^\s*- \{nombre:\s*' + re.escape(nombre) + r'\s*[,}]', lineas[i]):
            return i
    return None


def parchar(texto, generado, M, kb):
    """Devuelve (texto nuevo, lista de cambios)."""
    try:
        viejo = yaml.safe_load(texto) or {}
        nuevo = yaml.safe_load(generado) or {}
    except yaml.YAMLError as e:
        return texto, [f'no se pudo leer el YAML: {e}']
    lineas = texto.split('\n')
    glineas = generado.split('\n')
    cambios = []
    nv, nn = niveles(viejo), niveles(nuevo)
    enums = g.enumerados(kb)
    for nivel, (gcampos, grefs) in nn.items():
        if nivel not in nv:
            continue
        vcampos, vrefs = nv[nivel]
        existentes = {str(c.get('nombre')).lower() for c in vcampos if isinstance(c, dict)}
        rng = rango_nivel(lineas, nivel)
        grng = rango_nivel(glineas, nivel)
        if rng is None or grng is None:
            continue
        # 2. fórmulas con comentarios adentro (las originales de la KB)
        for c in vcampos:
            if not isinstance(c, dict) or not c.get('formula'):
                continue
            n = str(c['nombre'])
            original = M.formula(n)
            if not original:
                continue
            limpia = g.limpiar_formula(M, original, enums)
            crudo = ' '.join(original.split())
            if str(c['formula']).strip() == crudo and limpia != crudo:
                i = linea_campo(lineas, *rng, n)
                if i is None:
                    continue
                nueva = re.sub(r'formula:\s*("(?:[^"\\]|\\.)*"|\'(?:[^\']|\'\')*\'|[^,}]*)', lambda m: 'formula: ' + g.q(limpia), lineas[i], count=1)
                if nueva != lineas[i]:
                    lineas[i] = nueva
                    cambios.append(f'{nivel or "cabecera"}: fórmula de {n} sin comentarios')
        # 1. campos que faltan (fórmulas ocultas y datos inferidos que usan las fórmulas)
        agregar = []
        for c in gcampos:
            if not isinstance(c, dict):
                continue
            n = str(c.get('nombre'))
            if n.lower() in existentes or not (c.get('formula') or c.get('inferido')):
                continue
            j = linea_campo(glineas, *grng, n)
            if j is None:
                continue
            l = glineas[j]
            if 'oculto: true' not in l:
                l = l.rstrip()[:-1].rstrip() + ', oculto: true}'   # en un YAML existente, lo agregado va oculto
            agregar.append((n, l, c))
        if not agregar:
            continue
        ultimo = max((i for i in range(*rng) if re.match(r'^\s*- \{nombre:', lineas[i])), default=None)
        if ultimo is None:
            continue
        ind = len(lineas[ultimo]) - len(lineas[ultimo].lstrip())
        nuevas = [' ' * ind + l.lstrip() for _, l, _ in agregar]
        lineas[ultimo + 1:ultimo + 1] = nuevas
        cambios += [f'{nivel or "cabecera"}: + {n}' + (' (fórmula)' if c.get('formula') else ' (inferido)') for n, _, c in agregar]
        # referencias de los inferidos agregados
        inferidos = {n for n, _, c in agregar if c.get('inferido')}
        if not inferidos:
            continue
        rng = rango_nivel(lineas, nivel)
        for gr in grefs:
            trae = {k: v for k, v in (gr.get('trae') or {}).items() if k in inferidos}
            if not trae:
                continue
            igual = next((vr for vr in vrefs if vr.get('tabla') == gr.get('tabla') and vr.get('campos') == gr.get('campos')), None)
            if igual is not None:
                # se agrega al 'trae' de la referencia que ya estaba
                campos_txt = ', '.join(f'{k}: {v}' for k, v in gr['campos'].items())
                for i in range(*rng):
                    if re.match(r'^\s*- tabla:\s*' + re.escape(str(gr['tabla'])) + r'\s*$', lineas[i]) and i + 2 < len(lineas) \
                            and lineas[i + 1].strip() == 'campos: {' + campos_txt + '}' and lineas[i + 2].strip().startswith('trae: {'):
                        extra = ', '.join(f'{k}: {v}' for k, v in trae.items())
                        lineas[i + 2] = lineas[i + 2].rstrip()[:-1] + (', ' if lineas[i + 2].strip() != 'trae: {}' else '') + extra + '}'
                        cambios.append(f'{nivel or "cabecera"}: referencia {gr["tabla"]} trae además {", ".join(trae)}')
                        break
                else:
                    cambios.append(f'{nivel or "cabecera"}: REVISAR: no encontré el texto de la referencia a {gr["tabla"]} para agregar {", ".join(trae)}')
                continue
            # referencia nueva, al final de las referencias del nivel (o se crea la sección)
            base = '' if nivel == '' else '    '
            bloque = [base + f'  - tabla: {gr["tabla"]}',
                      base + '    campos: {' + ', '.join(f'{k}: {v}' for k, v in gr['campos'].items()) + '}',
                      base + '    trae: {' + ', '.join(f'{k}: {v}' for k, v in trae.items()) + '}']
            ini, fin = rango_nivel(lineas, nivel)
            sec = next((i for i in range(ini, fin) if lineas[i] == base + 'referencias:'), None)
            if sec is None:
                ult = max(i for i in range(ini, fin) if re.match(r'^\s*- \{nombre:', lineas[i]))
                lineas[ult + 1:ult + 1] = [base + 'referencias:'] + bloque
            else:
                k = sec + 1
                while k < fin and (lineas[k].startswith(base + '  ') and not re.match(r'^' + base + r'\S', lineas[k])):
                    k += 1
                lineas[k:k] = bloque
            rng = rango_nivel(lineas, nivel)
            cambios.append(f'{nivel or "cabecera"}: + referencia {gr["tabla"]} ({", ".join(trae)})')
    # 3. dominios que usan los campos agregados y la TRN no tiene
    texto2 = '\n'.join(lineas)
    try:
        doc2 = yaml.safe_load(texto2) or {}
    except yaml.YAMLError as e:
        return texto2, cambios + [f'REVISAR: el YAML quedó mal ({e})']
    propios = {str(k) for k in (doc2.get('dominios') or {})}
    usados = set()
    for campos, _ in niveles(doc2).values():
        for c in campos:
            if isinstance(c, dict) and c.get('dominio'):
                usados.add(str(c['dominio']))
    faltan = [d for d in usados if d not in propios and d not in GLOBALES and not re.match(r'(?i)^(numeric|character|char|varchar|longvarchar|date|datetime|boolean)\b', d)]
    if faltan:
        gdom = rango_dominios(glineas)
        bloques = []
        for d in sorted(faltan):
            if gdom is None:
                break
            i = next((k for k in range(*gdom) if re.match(r'^  ' + re.escape(d) + r':', glineas[k]) or re.match(r'^  "' + re.escape(d) + r'":', glineas[k])), None)
            if i is None:
                cambios.append(f'REVISAR: falta el dominio {d}')
                continue
            j = i + 1
            while j < gdom[1] and glineas[j].startswith('    '):
                j += 1
            bloques += glineas[i:j]
            cambios.append(f'+ dominio {d}')
        if bloques:
            vdom = rango_dominios(lineas)
            if vdom is None:
                fin = next((k for k in range(len(lineas)) if lineas[k].startswith('# ------') and 'PENDIENTE' in lineas[k]), len(lineas))
                lineas[fin:fin] = ['dominios:'] + bloques
            else:
                lineas[vdom[1]:vdom[1]] = bloques
    return '\n'.join(lineas), cambios


GLOBALES = set()


def rango_dominios(lineas):
    ini = next((i for i, l in enumerate(lineas) if l.rstrip() == 'dominios:'), None)
    if ini is None:
        return None
    fin = ini + 1
    while fin < len(lineas) and (lineas[fin].startswith('  ') or lineas[fin].strip() == '' and fin + 1 < len(lineas) and lineas[fin + 1].startswith('  ')):
        fin += 1
    return ini + 1, fin


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--trn', required=True, help='carpeta de los YAML')
    ap.add_argument('--solo', default='')
    ap.add_argument('--ver', action='store_true')
    a = ap.parse_args()
    kb = cargar_kb(a.xml)
    globales = os.path.join(os.path.dirname(os.path.abspath(a.trn)), 'dominios.yaml')
    if os.path.exists(globales):
        GLOBALES.update(str(k) for k in ((yaml.safe_load(open(globales, encoding='utf-8')) or {}).get('dominios') or {}))
    M = g._modelo(kb)
    solo = {x.strip().lower() for x in a.solo.split(',') if x.strip()}
    total = 0
    for archivo in sorted(os.listdir(a.trn)):
        if not archivo.endswith('.yaml'):
            continue
        ruta = os.path.join(a.trn, archivo)
        texto = open(ruta, encoding='utf-8').read()
        m = re.search(r'^transaccion:\s*(\S+)', texto, re.M)
        if not m or (solo and m.group(1).lower() not in solo):
            continue
        n = m.group(1)
        if n not in kb['trns'] or n not in kb.get('objetivos', {}):
            continue
        try:
            generado = g.generar(kb, n, set())
        except Exception as e:
            print(f'!! {archivo}: no se pudo generar ({type(e).__name__}: {e})')
            continue
        nuevo, cambios = parchar(texto, generado, M, kb)
        if not cambios:
            continue
        total += 1
        print(f'== {archivo}')
        for c in cambios:
            print('   ' + c)
        if not a.ver and nuevo != texto:
            open(ruta, 'w', encoding='utf-8').write(nuevo)
    print(f'{total} archivos {"a cambiar" if a.ver else "cambiados"}')


if __name__ == '__main__':
    main()
